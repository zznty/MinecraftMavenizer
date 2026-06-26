/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.fabric;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks.MCFile;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.RunConfig;
import net.minecraftforge.util.file.FileUtils;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fabric repository support.
 *
 * <p>Unlike Forge/NeoForge there is no decompile/patch/recompile pipeline: Fabric runs the vanilla
 * Minecraft classes directly and applies its changes at runtime via Knot + Mixin. So this repo only needs
 * to produce a <em>merged</em> (client + server) Minecraft jar and wrap it as a maven dependency alongside
 * fabric-loader and its libraries.
 *
 * <p>Users declare a synthetic {@code net.fabricmc:fabric:<mcVersion>-<loaderVersion>} dependency (mirroring
 * how {@code net.minecraftforge:forge:<mc>-<forge>} encodes both versions in one coordinate).
 *
 * <p><b>Namespaces.</b> From Minecraft 26.1 onwards the game ships <em>unobfuscated</em> (no
 * {@code client_mappings}); Fabric no longer publishes intermediary mappings for those versions and its new
 * {@code net.fabricmc.fabric-loom} plugin does not remap Minecraft or mods. The merged vanilla jar is
 * therefore used as-is in the {@code official} namespace, and the loader's runtime-namespace detection
 * defaults to {@code official} when no mappings are present, so no game remap happens at launch.
 *
 * <p>Obfuscated versions ({@code <= 1.21.11}) would additionally need an {@code official -> intermediary}
 * tiny-remapper pass; that path is not yet implemented here.
 */
public final class FabricRepo extends Repo {
    private final MCPConfigRepo mcpconfig;
    private final MavenCache fabricMaven;

    public FabricRepo(Cache cache, MCPConfigRepo mcpconfig) {
        super(cache);
        this.mcpconfig = mcpconfig;
        this.fabricMaven = new MavenCache("fabric", Constants.FABRIC_MAVEN, cache.root());
    }

    /// Splits the synthetic {@code <mcVersion>-<loaderVersion>} version into its two halves.
    public static FabricVersion parseVersion(String version) {
        if (version == null)
            throw new IllegalArgumentException("No version specified for Fabric");
        var idx = version.indexOf('-');
        if (idx <= 0 || idx >= version.length() - 1)
            throw new IllegalArgumentException(
                "Fabric version must be '<mcVersion>-<loaderVersion>', got: " + version);
        return new FabricVersion(version.substring(0, idx), version.substring(idx + 1));
    }

    @Override
    public List<PendingArtifact> process(Artifact artifact, Mappings baseMappings, Map<String, Supplier<String>> outputJson) {
        var version = artifact.getVersion();
        var fv = parseVersion(version);
        var mcVersion = fv.mcVersion();
        var loaderVersion = fv.loaderVersion();

        // Obfuscated Minecraft (<= 1.21.11) ships scrambled names; Fabric distributes 'intermediary'
        // mappings (official -> intermediary) for those versions and runs the game in the intermediary
        // namespace. From 26.1+ Minecraft is unobfuscated, intermediary is empty/absent, and the game runs
        // in the 'official' namespace. We pick the namespace (and whether to run tiny-remapper) accordingly.
        var obfuscated = MCPConfigRepo.isObfuscated(mcVersion);
        var namespace = obfuscated ? "intermediary" : "official";
        LOGGER.info("Processing Fabric: MC " + mcVersion + " + loader " + loaderVersion
            + " (" + namespace + " namespace)");

        var build = new File(cache.root(), "fabric/" + version);
        FileUtils.ensure(build);

        // The mappings channel/version we advertise to ForgeGradle (a Gradle-module attribute identifier,
        // distinct from the loader namespace above). 'intermediary' is not a registered Mappings channel and
        // mods compile against the delivered jar directly (no Yarn/named layer yet), so we keep the base
        // 'official' channel for both namespaces.
        var mappings = baseMappings != null ? baseMappings.withMCVersion(mcVersion) : Mappings.of("official", mcVersion);

        var mcTasks = mcpconfig.getMCTasks(mcVersion);

        // Parse the loader's installer descriptor (runtime libraries + Knot main classes).
        var loaderInfo = getLoaderInfo(loaderVersion);

        // Merge client + server into a single game jar.
        var mergeTask = mergeTask(build, mcTasks, mcVersion);
        // On obfuscated versions, remap the merged jar official -> intermediary. This is the main artifact.
        var gameTask = obfuscated ? remapTask(build, mcTasks, mcVersion, mergeTask) : mergeTask;

        // Output JSON for ForgeGradle
        if (outputJson != null) {
            outputJson.put("mc.version", () -> mcVersion);
            outputJson.put("mcp.version", () -> mcVersion);
            outputJson.put("mappings.channel", mappings::channel);
            outputJson.put("mappings.version", () -> mappings.version() != null ? mappings.version() : mcVersion);

            // Obfuscated Minecraft requires mappings.srg.file/obf.file (Knot ignores them, but ForgeGradle
            // demands they exist). Emit shared no-op identity mappings.
            if (obfuscated)
                emitNoopSrgMappings(build, mcVersion, outputJson);
        }

        var name = Artifact.from(Constants.FABRIC_GROUP, Constants.FABRIC_NAME, version);

        // POM
        var pom = pending("Fabric POM",
            pomTask(build, artifact, mcTasks, mcVersion, loaderVersion, loaderInfo),
            name.withExtension("pom"), false);

        // Metadata zip (version.json + runs.json for SlimeLauncher)
        var metadata = pending("Metadata",
            metadataTask(build, mcTasks, mcVersion, loaderVersion, loaderInfo, namespace),
            name.withClassifier("metadata").withExtension("zip"), false, metadataVariant());

        // Game jar (main artifact) with class variants
        var javaVersion = mcTasks.getJavaVersion();
        var deps = collectDependencies(mcTasks, loaderVersion, loaderInfo);
        var classes = pending("Classes", gameTask, name, false,
            () -> classVariants(mappings, javaVersion, deps, List.of()));

        var ret = new ArrayList<PendingArtifact>();
        ret.add(classes);
        ret.add(metadata);
        ret.add(pom);
        return ret;
    }

    // --- Merge ---

    private Task mergeTask(File build, MinecraftTasks mcTasks, String mcVersion) {
        return Task.named("fabric-merge[" + mcVersion + ']',
            Task.deps(mcTasks.versionFile(MCFile.CLIENT_JAR), mcTasks.extractServer()),
            () -> {
                var output = new File(build, "minecraft-merged.jar");
                var client = mcTasks.versionFile(MCFile.CLIENT_JAR).execute();
                var server = mcTasks.extractServer().execute();
                var tool = this.cache.maven().download(Constants.SIDE_STRIPPER); // mergetool fatjar (Forge maven)

                var cacheKey = Util.cache(output)
                    .add("tool", tool)
                    .add("client", client)
                    .add("server", server);

                if (Mavenizer.checkCache(output, cacheKey))
                    return output;

                File jdk;
                try {
                    jdk = this.cache.jdks().get(Constants.SIDE_STRIPPER_JAVA_VERSION);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to find JDK for mergetool", e);
                }

                // mergetool 1.2.5: --merge --client <jar> --server <jar> --output <jar> [--ann ..] [--keep-data] [--keep-meta]
                // We deliberately skip --ann: Fabric does not use Forge/FML @SideOnly annotations, and injecting
                // references to them would put unresolved Forge annotation classes on the compile classpath.
                //
                // --keep-data keeps the data-pack resources (and the root version.json the loader reads).
                // We deliberately DO NOT pass --keep-meta: that would retain Mojang's signed MANIFEST.MF whose
                // SHA-384 class digests no longer match after merging, so Knot's signature-verifying class
                // loader throws SecurityException ("SHA-384 digest error") on the first game class it loads.
                var args = List.of(
                    "--merge",
                    "--client", client.getAbsolutePath(),
                    "--server", server.getAbsolutePath(),
                    "--output", output.getAbsolutePath(),
                    "--keep-data"
                );

                var log = new File(build, "merge.log");
                var result = ProcessUtils.runJar(jdk, build, log, tool, List.of(), args);
                if (result.exitCode != 0)
                    throw new IllegalStateException("mergetool failed (exit " + result.exitCode + "), see log: " + log.getAbsolutePath());
                if (!output.exists())
                    throw new IllegalStateException("mergetool did not produce: " + output.getAbsolutePath());

                cacheKey.save();
                return output;
            });
    }

    // --- Remap (obfuscated only) ---

    /// Remaps the merged jar from the {@code official} namespace to {@code intermediary} using Fabric's
    /// tiny-remapper and the per-version {@code net.fabricmc:intermediary} tiny mappings. The Minecraft
    /// libraries are supplied as the remap classpath so tiny-remapper can resolve inheritance.
    private Task remapTask(File build, MinecraftTasks mcTasks, String mcVersion, Task mergeTask) {
        return Task.named("fabric-remap[" + mcVersion + ']',
            Task.deps(mergeTask),
            () -> {
                var output = new File(build, "minecraft-intermediary.jar");
                var merged = mergeTask.execute();
                var tool = fabricMaven.download(Constants.TINY_REMAPPER);

                // intermediary tiny mappings: net.fabricmc:intermediary:<mc> (jar with mappings/mappings.tiny)
                var intermediaryJar = fabricMaven.download(Artifact.from(Constants.FABRIC_INTERMEDIARY + ':' + mcVersion));
                var mappingsFile = new File(build, "intermediary.tiny");

                var libs = mcTasks.getClientLibraries();

                var cacheKey = Util.cache(output)
                    .add("tool", tool)
                    .add("merged", merged)
                    .add("intermediary", intermediaryJar);

                if (Mavenizer.checkCache(output, cacheKey))
                    return output;

                // Extract mappings/mappings.tiny from the intermediary jar.
                try (var jar = new java.util.jar.JarFile(intermediaryJar)) {
                    var entry = jar.getEntry("mappings/mappings.tiny");
                    if (entry == null)
                        throw new IllegalStateException("intermediary " + mcVersion + " is missing mappings/mappings.tiny");
                    FileUtils.ensureParent(mappingsFile);
                    try (var in = jar.getInputStream(entry)) {
                        Files.copy(in, mappingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (java.io.IOException e) {
                    return Util.sneak(e);
                }

                File jdk;
                try {
                    jdk = this.cache.jdks().get(Constants.TINY_REMAPPER_JAVA_VERSION);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to find JDK for tiny-remapper", e);
                }

                // tiny-remapper fat CLI: <input> <output> <mappings> <from> <to> [<classpath>]...
                var args = new ArrayList<String>(List.of(
                    merged.getAbsolutePath(),
                    output.getAbsolutePath(),
                    mappingsFile.getAbsolutePath(),
                    "official",
                    "intermediary"
                ));
                for (var lib : libs)
                    args.add(lib.file().getAbsolutePath());

                var log = new File(build, "remap.log");
                var result = ProcessUtils.runJar(jdk, build, log, tool, List.of(), args);
                if (result.exitCode != 0)
                    throw new IllegalStateException("tiny-remapper failed (exit " + result.exitCode + "), see log: " + log.getAbsolutePath());
                if (!output.exists())
                    throw new IllegalStateException("tiny-remapper did not produce: " + output.getAbsolutePath());

                cacheKey.save();
                return output;
            });
    }

    // --- Loader descriptor ---

    /// Downloads fabric-loader and parses {@code fabric-installer.json} for its runtime libraries and the
    /// Knot client/server main classes.
    public LoaderInfo getLoaderInfo(String loaderVersion) {
        var loaderJar = fabricMaven.download(Artifact.from(Constants.FABRIC_LOADER + ':' + loaderVersion));

        try (var jar = new java.util.jar.JarFile(loaderJar)) {
            var entry = jar.getEntry("fabric-installer.json");
            if (entry == null)
                throw new IllegalStateException("fabric-loader " + loaderVersion + " is missing fabric-installer.json");

            InstallerConfig config;
            try (var in = jar.getInputStream(entry);
                 var reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                config = Util.GSON.fromJson(reader, InstallerConfig.class);
            }

            var libs = new ArrayList<String>();
            if (config.libraries != null) {
                addLibs(libs, config.libraries.common);
                addLibs(libs, config.libraries.client);
                addLibs(libs, config.libraries.server);
            }

            var clientMain = config.mainClass != null ? config.mainClass.client : null;
            var serverMain = config.mainClass != null ? config.mainClass.server : null;
            if (clientMain == null || serverMain == null)
                throw new IllegalStateException("fabric-installer.json missing client/server mainClass");

            return new LoaderInfo(libs, clientMain, serverMain);
        } catch (java.io.IOException e) {
            return Util.sneak(e);
        }
    }

    private static void addLibs(List<String> out, List<InstallerLibrary> libs) {
        if (libs == null) return;
        for (var lib : libs) {
            if (lib != null && lib.name != null)
                out.add(lib.name);
        }
    }

    // --- POM ---

    private Task pomTask(File build, Artifact artifact, MinecraftTasks mcTasks, String mcVersion,
            String loaderVersion, LoaderInfo loaderInfo) {
        return Task.named("pom[" + artifact + ']', () -> {
            var output = new File(build, "fabric.pom");
            var cacheKey = Util.cache(output)
                .add("mc", mcVersion)
                .add("loader", loaderVersion);

            if (Mavenizer.checkCache(output, cacheKey))
                return output;

            var builder = new POMBuilder(artifact.getGroup(), artifact.getName(), artifact.getVersion())
                .preferGradleModule();

            builder.dependencies(deps -> {
                var seen = new HashSet<String>();

                // fabric-loader itself (provides Knot, Mixin bootstrap, the loader API).
                var loader = Artifact.from(Constants.FABRIC_LOADER + ':' + loaderVersion);
                deps.add(loader);
                seen.add(loader.getGroup() + ':' + loader.getName());

                // Loader runtime libraries (asm, sponge-mixin, ...) from fabric-installer.json — the loader POM
                // itself declares no dependencies, so these MUST be added explicitly.
                for (var coord : loaderInfo.libraries()) {
                    var art = Artifact.from(coord);
                    if (seen.add(art.getGroup() + ':' + art.getName()))
                        deps.add(art);
                }

                // Minecraft libraries (non-OS only for the POM).
                var versionJson = JsonData.minecraftVersion(mcTasks.versionJson.execute());
                for (var lib : versionJson.getLibs()) {
                    if (lib.os.isEmpty()) {
                        var art = Artifact.from(lib.coord);
                        if (seen.add(art.getGroup() + ':' + art.getName()))
                            deps.add(art);
                    }
                }
            });

            FileUtils.ensureParent(output);
            try (var os = new FileOutputStream(output)) {
                os.write(builder.build().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Util.sneak(e);
            }

            cacheKey.save();
            return output;
        });
    }

    // --- Metadata / runs.json ---

    private Task metadataTask(File build, MinecraftTasks mcTasks, String mcVersion,
            String loaderVersion, LoaderInfo loaderInfo, String namespace) {
        return Task.named("metadata[fabric]", () -> {
            var output = new File(build, "metadata.zip");
            var cacheKey = Util.cache(output)
                .add("mc", mcVersion)
                .add("loader", loaderVersion)
                .add("namespace", namespace);

            if (Mavenizer.checkCache(output, cacheKey))
                return output;

            var metadataDir = new File(output.getParentFile(), "metadata");
            var minecraftDir = new File(metadataDir, "minecraft");
            var launcherDir = new File(metadataDir, "launcher");
            FileUtils.ensure(minecraftDir);
            FileUtils.ensure(launcherDir);

            try (var w = new FileWriter(new File(metadataDir, "version.properties"))) {
                w.write("version=1\n");
            }

            Files.copy(mcTasks.versionJson.execute().toPath(),
                new File(minecraftDir, "version.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING);

            var runsJson = generateRunsJson(loaderInfo, namespace);
            try (var w = new FileWriter(new File(launcherDir, "runs.json"))) {
                w.write(runsJson);
            }

            FileUtils.makeZip(metadataDir, output);
            cacheKey.save();
            return output;
        });
    }

    private String generateRunsJson(LoaderInfo loaderInfo, String namespace) {
        var runs = new LinkedHashMap<String, RunConfig>();
        runs.put("client", knotRun("client", loaderInfo.clientMain(), true, namespace));
        runs.put("server", knotRun("server", loaderInfo.serverMain(), false, namespace));
        return JsonData.toJson(runs);
    }

    private RunConfig knotRun(String name, String main, boolean client, String namespace) {
        var rc = new RunConfig();
        rc.name = name;
        rc.main = main;
        rc.client = client;

        var props = new LinkedHashMap<String, String>();
        // Tells the loader we're in a dev launch (skips the production jar layout expectations).
        props.put("fabric.development", "true");
        // Avoid the Log4j JNDI lookup CVE in old transitive log4j (matches Loom's default).
        props.put("log4j2.formatMsgNoLookups", "true");
        // Pin BOTH the game-jar namespace and the runtime namespace to the namespace our jar is delivered in.
        // The loader only skips its runtime game-remap when game namespace == runtime namespace
        // (GameProviderHelper#deobfuscate). On unobfuscated MC both already default to 'official'; on
        // obfuscated MC the runtime namespace would otherwise default to 'named' (dev) and the loader would
        // try (and fail) to remap intermediary -> named without Yarn. Forcing both to our namespace is a no-op.
        props.put("fabric.gameMappingNamespace", namespace);
        props.put("fabric.runtimeMappingNamespace", namespace);
        // Mod source roots for the in-dev classpath-group discovery. Fabric's format differs from FML:
        // no '<modid>%%' prefix, single-entry groups are dropped (so don't pad), and groups are separated
        // by a DOUBLED path separator. These args are resolved by ForgeGradle's token engine.
        props.put("fabric.classPathGroups",
            "{source_roots,prefix=none,pad-single=false,group-separator=path-path}");
        rc.props = props;

        var jvmArgs = new ArrayList<String>();
        if (client)
            jvmArgs.add("-Djava.library.path={natives}");
        rc.jvmArgs = jvmArgs;

        var args = new ArrayList<String>();
        if (client) {
            args.add("--assetIndex");
            args.add("{asset_index}");
            args.add("--assetsDir");
            args.add("{assets_root}");
        }
        rc.args = args;

        rc.env = Map.of();
        return rc;
    }

    // --- Dependencies (variants) ---

    private List<Artifact> collectDependencies(MinecraftTasks mcTasks, String loaderVersion, LoaderInfo loaderInfo) {
        var deps = new ArrayList<Artifact>();
        var seen = new HashSet<String>();

        var loader = Artifact.from(Constants.FABRIC_LOADER + ':' + loaderVersion);
        deps.add(loader);
        seen.add(loader.getGroup() + ':' + loader.getName() + ':' + loader.getClassifier());

        for (var coord : loaderInfo.libraries()) {
            var art = Artifact.from(coord);
            if (seen.add(art.getGroup() + ':' + art.getName() + ':' + art.getClassifier()))
                deps.add(art);
        }

        // MC libraries (including OS-specific natives for variants). Dedup key MUST include the classifier so
        // the LWJGL natives jars aren't dropped (see NeoForgeRepo for the same rationale).
        var versionJson = JsonData.minecraftVersion(mcTasks.versionJson.execute());
        for (var lib : versionJson.getLibs()) {
            var art = Artifact.from(lib.coord).withOS(lib.os);
            if (seen.add(art.getGroup() + ':' + art.getName() + ':' + art.getClassifier()))
                deps.add(art);
        }

        return deps;
    }

    // --- Records / JSON shapes ---

    public record FabricVersion(String mcVersion, String loaderVersion) {}
    public record LoaderInfo(List<String> libraries, String clientMain, String serverMain) {}

    /// Shape of {@code fabric-installer.json} (only the fields we need).
    public static final class InstallerConfig {
        public int version;
        public InstallerLibraries libraries;
        public InstallerMainClass mainClass;
    }

    public static final class InstallerLibraries {
        public List<InstallerLibrary> common;
        public List<InstallerLibrary> client;
        public List<InstallerLibrary> server;
    }

    public static final class InstallerLibrary {
        public String name;
        public String url;
    }

    public static final class InstallerMainClass {
        public String client;
        public String server;
    }
}
