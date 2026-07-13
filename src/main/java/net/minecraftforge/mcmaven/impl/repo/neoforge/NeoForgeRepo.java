/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.neoforge;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.mappings.ParchmentVersion;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
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

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.w3c.dom.Element;

/**
 * NeoForge repository that bridges to NeoFormRuntime (NFRT) for artifact production.
 *
 * Instead of reimplementing the NeoForm decompile/patch/recompile pipeline, this repo
 * delegates the heavy lifting to NFRT. The Mavenizer then wraps NFRT's output JARs
 * into a maven repository structure with POM files, Gradle Module metadata, and the
 * output JSON expected by ForgeGradle.
 */
public final class NeoForgeRepo extends Repo {
    private final MCPConfigRepo mcpconfig;
    private final MavenCache neoforgeMaven;

    public NeoForgeRepo(Cache cache, MCPConfigRepo mcpconfig) {
        super(cache);
        this.mcpconfig = mcpconfig;
        this.neoforgeMaven = new MavenCache("neoforged", Constants.NEOFORGE_MAVEN, cache.root());
    }

    public NeoForgeInfo getInfo(Artifact artifact) {
        var version = artifact.getVersion();
        if (version == null)
            throw new IllegalArgumentException("No version specified for NeoForge");

        var pomFile = neoforgeMaven.download(artifact.withExtension("pom"));
        var pomInfo = parseNeoForgePom(pomFile, version);

        // Download and parse the moddev-config.json — the authoritative source for
        // NeoForge run configurations and the full library list.
        var configArtifact = Artifact.from(Constants.NEOFORGE_ARTIFACT + ':' + version + ":moddev-config").withExtension("json");
        var configFile = neoforgeMaven.download(configArtifact);
        var config = JsonData.fromJson(configFile, ModDevConfig.class);

        return new NeoForgeInfo(pomInfo.mcVersion(), pomInfo.neoformVersion(), pomInfo.dependencies(), config);
    }

    @Override
    public List<PendingArtifact> process(Artifact artifact, Mappings baseMappings, Map<String, Supplier<String>> outputJson) {
        var version = artifact.getVersion();
        if (version == null)
            throw new IllegalArgumentException("No version specified for NeoForge");

        LOGGER.info("Processing NeoForge (via NFRT): " + version);

        // Eagerly parse the POM to get MC version and dependencies
        var info = getInfo(artifact);
        var mcVersion = info.mcVersion();

        var build = new File(cache.root(), "neoforge/" + version);
        FileUtils.ensure(build);

        // Resolve mappings — NeoForge defaults to official (Mojmap)
        var mappings = baseMappings != null ? baseMappings.withMCVersion(mcVersion) : Mappings.of("official", mcVersion);
        var mappingChannel = mappings.channel();
        var mappingVersion = mappings.version() != null ? mappings.version() : mcVersion;

        // Download NFRT fat JAR
        var nfrtJar = neoforgeMaven.download(Constants.NFRT);

        // FancyModLoader changed its dev-mode game layout between major versions:
        //
        //  * FML 11+ (MC 1.21.11 / NeoForge 26.x): Minecraft and NeoForge live in SEPARATE jars on the
        //    classpath. We request the vanilla `gameJar` (patched MC, no NeoForge classes) and supply the
        //    NeoForge code via the published `universal` jar.
        //
        //  * FML 4.x (MC 1.21.1 / NeoForge 21.1.x): Minecraft and NeoForge must be COMBINED in a single jar
        //    (the FML mod marker, mixins config and jar-in-jar coremods all ride along), with the Minecraft
        //    resources split out into a separate `client-extra` jar. We request `gameJarWithNeoForge` plus
        //    `clientResources`, and do NOT emit a separate universal jar.
        //
        // The moddev-config's `modules` list (the BootstrapLauncher module path) is the structural signal:
        // it is populated for the FML-4.x module-path launch and empty for the FML-11 flat-classpath launch.
        var combined = info.config() != null && info.config().modules != null && !info.config().modules.isEmpty();

        // NFRT cache and work directories
        var nfrtHome = new File(cache.root(), "nfrt-home");
        var nfrtWork = new File(build, "nfrt-work");
        var compiledJar = new File(build, "compiled.jar");
        var sourcesJar = new File(build, "sources.jar");
        // Minecraft resources (client-extra) — only produced/used in the FML-4.x combined layout.
        var resourcesJar = new File(build, "client-extra.jar");

        // Parchment data download (if requested)
        final File parchmentData;
        if ("parchment".equals(mappingChannel) && mappingVersion != null) {
            var pv = ParchmentVersion.parse(mappingVersion).withMinecraft(mcVersion);
            var parchmentMaven = new MavenCache("parchment", ParchmentVersion.PARCHMENT_MAVEN, cache.root());
            parchmentData = parchmentMaven.download(pv.getArtifact());
        } else {
            parchmentData = null;
        }

        // NFRT invocation task — produces the main game jar (+ sources, + client-extra for the combined
        // layout) in one run. See the `combined` comment above for the two layouts.
        //
        // The Mavenizer owns all caching: NFRT runs with --disable-cache, but we wrap it in our own
        // HashStore check so the (expensive, ~minutes-long) NFRT invocation is skipped on every
        // subsequent configuration/IDE-sync once the outputs already exist for the same inputs.
        var nfrtTask = Task.named("nfrt-run[" + version + "]", () -> {
            var cacheKey = Util.cache(compiledJar)
                .add("neoforge", version)
                .add("nfrt", Constants.NFRT.toString())
                .add("layout", combined ? "combined" : "separate")
                .add("mappings", mappingChannel + '-' + mappingVersion);
            if (parchmentData != null)
                cacheKey.add("parchment", parchmentData);

            // checkCache verifies compiledJar exists + the key matches; we also require the aux jars.
            var auxReady = sourcesJar.exists() && (!combined || resourcesJar.exists());
            if (auxReady && Mavenizer.checkCache(compiledJar, cacheKey))
                return compiledJar;

            runNfrt(version, nfrtJar, nfrtHome, nfrtWork, compiledJar, sourcesJar,
                combined ? resourcesJar : null, parchmentData, combined, getJavaVersion(info));
            cacheKey.save();
            return compiledJar;
        });

        // Sources task — the sources JAR is already produced by the NFRT run
        var sourcesTask = Task.named("nfrt-sources[" + version + "]", Task.deps(nfrtTask), () -> sourcesJar);

        // Output JSON for ForgeGradle
        if (outputJson != null) {
            outputJson.put("mc.version", () -> mcVersion);
            outputJson.put("mcp.version", () -> info.neoformVersion() != null ? info.neoformVersion() : "UNKNOWN");
            outputJson.put("mappings.channel", () -> mappingChannel);
            outputJson.put("mappings.version", () -> mappingVersion);

            // On obfuscated Minecraft (<= 1.21.11) ForgeGradle's SlimeLauncher integration requires
            // mappings.srg.file/obf.file. NeoForge/FancyModLoader runs in Mojmap with no runtime SRG remap,
            // so emit shared no-op identity mappings purely to satisfy the launcher.
            if (MCPConfigRepo.isObfuscated(mcVersion))
                emitNoopSrgMappings(build, mcVersion, outputJson);

            // Module-path entries for BootstrapLauncher's '-p {modules}' run argument. Present on FML
            // versions that boot via the module path (MC 1.21.1 / FML 4.x); absent on newer configs (26.x).
            var modules = info.config() != null ? info.config().modules : null;
            if (modules == null || modules.isEmpty())
                outputJson.put("patcher.modules", () -> "");
            else
                outputJson.put("patcher.modules", () -> String.join(",", modules));
        }

        var name = Artifact.from(Constants.NEOFORGE_GROUP, Constants.NEOFORGE_NAME, version);

        // POM
        var pom = pending("NeoForge POM",
            pomTask(build, artifact, info, combined),
            name.withExtension("pom"), false);

        // Metadata zip (version.json + runs.json for SlimeLauncher)
        var metadata = pending("Metadata",
            metadataTask(build, info, version),
            name.withClassifier("metadata").withExtension("zip"), false, metadataVariant());

        // Sources JAR
        var sources = pending("Sources", sourcesTask,
            name.withClassifier("sources"), true,
            sourceVariant(mappings));

        // The secondary game root, layout-dependent:
        //  * separate (FML 11): the published NeoForge `universal` jar carries the NeoForge classes + FML
        //    markers as a separate classpath root from the patched-Minecraft main jar.
        //  * combined (FML 4.x): the main jar already contains the NeoForge classes; instead the secondary
        //    root is the Minecraft `client-extra` resources jar (assets/data, recognised by FML via the
        //    `client-extra` ignoreList entry). Carried under our own `client-extra` classifier.
        final PendingArtifact secondary;
        if (combined) {
            var resourcesTask = Task.named("nfrt-resources[" + version + "]", Task.deps(nfrtTask), () -> resourcesJar);
            secondary = pending("Client Extra", resourcesTask,
                name.withClassifier("client-extra"), true);
        } else {
            var universalTask = Task.named("download[neoforge-universal][" + version + "]", () ->
                neoforgeMaven.download(Artifact.from(Constants.NEOFORGE_ARTIFACT + ':' + version + ":universal")));
            secondary = pending("Universal", universalTask,
                name.withClassifier("universal"), true);
        }

        // Compiled JAR (main artifact) with class variants
        var javaVersion = getJavaVersion(info);
        var deps = collectDependencies(info, artifact, combined);
        var classes = pending("Classes", nfrtTask, name, false,
            () -> classVariants(mappings, javaVersion, deps, List.of()));

        var ret = new ArrayList<PendingArtifact>();
        ret.add(classes);
        ret.add(sources);
        ret.add(secondary);
        ret.add(metadata);
        ret.add(pom);
        return ret;
    }

    private void runNfrt(String neoforgeVersion, File nfrtJar, File nfrtHome,
            File nfrtWork, File compiledJar, File sourcesJar, File resourcesJar, File parchmentData, boolean combined,
            Integer mcJavaVersion) {
        FileUtils.ensure(nfrtHome);
        FileUtils.ensure(nfrtWork);

        // NFRT recompiles Minecraft with the javac of the JDK it runs on, so the JDK version decides the
        // synthetic lambda naming baked into the artifacts. Match Minecraft's required Java version (so we
        // stay byte-compatible with the mods built for it), but never below NFRT_JAVA_VERSION (NFRT's own
        // minimum and the pre-JEP-482 floor). See Constants.NFRT_JAVA_VERSION.
        int javaVersion = mcJavaVersion != null
            ? Math.max(mcJavaVersion, Constants.NFRT_JAVA_VERSION)
            : Constants.NFRT_JAVA_VERSION;

        File jdk;
        try {
            jdk = cache.jdks().get(javaVersion);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK " + javaVersion + " for NFRT", e);
        }

        var args = new ArrayList<String>(List.of(
            "run",
            // The :userdev GAV lets NFRT resolve the NeoForge userdev archive (patches + config) directly,
            // so we don't need an artifact manifest. The NeoForm version is implied by it.
            "--neoforge", Constants.NEOFORGE_ARTIFACT + ':' + neoforgeVersion + ":userdev",
            "--dist", "joined",
            "--home-dir", nfrtHome.getAbsolutePath(),
            "--work-dir", nfrtWork.getAbsolutePath(),
            // The Mavenizer owns caching/invalidation; NFRT should never reuse its own intermediate cache.
            "--disable-cache"
        ));

        if (combined) {
            // FML 4.x: one jar with patched Minecraft + NeoForge classes + FML markers, plus a separate
            // Minecraft resources (client-extra) jar. gameSources matches the combined classes.
            args.add("--write-result");
            args.add("gameJarWithNeoForge:" + compiledJar.getAbsolutePath());
            args.add("--write-result");
            args.add("gameSourcesWithNeoForge:" + sourcesJar.getAbsolutePath());
            args.add("--write-result");
            args.add("clientResources:" + resourcesJar.getAbsolutePath());
        } else {
            // FML 11: vanilla gameJar (patched Minecraft, no NeoForge classes); gameSources = matching sources.
            args.add("--write-result");
            args.add("gameJar:" + compiledJar.getAbsolutePath());
            args.add("--write-result");
            args.add("gameSources:" + sourcesJar.getAbsolutePath());
        }

        if (parchmentData != null) {
            args.add("--parchment-data");
            args.add(parchmentData.getAbsolutePath());
            args.add("--parchment-conflict-prefix");
            args.add("p_");
        }

        var logFile = new File(nfrtWork, "nfrt.log");
        LOGGER.info("Running NFRT to produce NeoForge artifacts...");
        var result = ProcessUtils.runJar(jdk, nfrtWork, logFile, nfrtJar, List.of(), args);

        if (result.exitCode != 0) {
            LOGGER.error("NFRT failed with exit code " + result.exitCode);
            LOGGER.error("See log: " + logFile.getAbsolutePath());
            throw new IllegalStateException("NFRT failed to produce NeoForge artifacts (exit code " + result.exitCode + ")");
        }

        if (!compiledJar.exists())
            throw new IllegalStateException("NFRT did not produce compiled JAR: " + compiledJar.getAbsolutePath());
        if (!sourcesJar.exists())
            throw new IllegalStateException("NFRT did not produce sources JAR: " + sourcesJar.getAbsolutePath());
        if (combined && !resourcesJar.exists())
            throw new IllegalStateException("NFRT did not produce client resources JAR: " + resourcesJar.getAbsolutePath());

        LOGGER.info("NFRT completed successfully");
    }

    private Task pomTask(File build, Artifact artifact, NeoForgeInfo info, boolean combined) {
        return Task.named("pom[" + artifact + ']', () -> {
            var output = new File(build, "neoforge.pom");
            var cacheKey = Util.cache(output).add("info", info.toString()).add("layout", combined ? "combined" : "separate");

            if (Mavenizer.checkCache(output, cacheKey))
                return output;

            var builder = new POMBuilder(artifact.getGroup(), artifact.getName(), artifact.getVersion())
                .preferGradleModule();

            builder.dependencies(deps -> {
                var seen = new java.util.HashSet<String>();
                // The secondary game root is a self-referential classified dependency that resolves against
                // our own repo. FML 11 (separate): the `universal` jar with NeoForge classes. FML 4.x
                // (combined): the `client-extra` jar with Minecraft resources (the combined classes are
                // already in the main artifact).
                var secondaryClassifier = combined ? "client-extra" : "universal";
                var secondary = Artifact.from(artifact.getGroup() + ':' + artifact.getName() + ':' + artifact.getVersion() + ':' + secondaryClassifier);
                deps.add(secondary);
                seen.add(secondary.getGroup() + ':' + secondary.getName());

                // MC libraries (non-OS only for POM)
                var mcTasks = mcpconfig.getMCTasks(info.mcVersion());
                var versionJson = JsonData.minecraftVersion(mcTasks.versionJson.execute());
                for (var lib : versionJson.getLibs()) {
                    if (lib.os.isEmpty()) {
                        var art = Artifact.from(lib.coord);
                        if (seen.add(art.getGroup() + ':' + art.getName()))
                            deps.add(art);
                    }
                }
                // NeoForge libraries from moddev-config.json (the authoritative classpath)
                if (info.config() != null && info.config().libraries != null) {
                    for (var coord : info.config().libraries) {
                        var art = Artifact.from(coord);
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

    private Task metadataTask(File build, NeoForgeInfo info, String neoforgeVersion) {
        return Task.named("metadata[neoforge]", () -> {
            var output = new File(build, "metadata.zip");
            var cacheKey = Util.cache(output)
                .add("neoforge", neoforgeVersion)
                .add("mc", info.mcVersion());

            if (Mavenizer.checkCache(output, cacheKey))
                return output;

            var metadataDir = new File(output.getParentFile(), "metadata");
            var minecraftDir = new File(metadataDir, "minecraft");
            var launcherDir = new File(metadataDir, "launcher");
            FileUtils.ensure(minecraftDir);
            FileUtils.ensure(launcherDir);

            // version.properties
            try (var w = new FileWriter(new File(metadataDir, "version.properties"))) {
                w.write("version=1\n");
            }

            // version.json from MC tasks
            var mcTasks = mcpconfig.getMCTasks(info.mcVersion());
            Files.copy(mcTasks.versionJson.execute().toPath(),
                new File(minecraftDir, "version.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING);

            // runs.json — NeoForge run configurations for SlimeLauncher,
            // derived from NeoForge's own moddev-config.json
            var runsJson = generateRunsJson(info);
            try (var w = new FileWriter(new File(launcherDir, "runs.json"))) {
                w.write(runsJson);
            }

            FileUtils.makeZip(metadataDir, output);
            cacheKey.save();
            return output;
        });
    }

    private String generateRunsJson(NeoForgeInfo info) {
        var runs = new HashMap<String, RunConfig>();

        for (var entry : info.config().runs.entrySet()) {
            var name = entry.getKey();
            var src = entry.getValue();
            if (src.main == null || "NONE".equals(src.main))
                continue;

            var rc = new RunConfig();
            rc.name = name;
            rc.main = src.main;
            rc.args = src.args != null ? src.args : List.of();
            rc.jvmArgs = src.jvmArgs != null ? src.jvmArgs : List.of();
            rc.client = src.client;
            rc.env = src.env != null ? src.env : Map.of();
            rc.props = src.props != null ? src.props : Map.of();
            runs.put(name, rc);
        }

        return JsonData.toJson(runs);
    }

    private Integer getJavaVersion(NeoForgeInfo info) {
        try {
            var mcTasks = mcpconfig.getMCTasks(info.mcVersion());
            var versionJson = JsonData.minecraftVersion(mcTasks.versionJson.execute());
            return versionJson.javaVersion != null ? versionJson.javaVersion.majorVersion : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Artifact> collectDependencies(NeoForgeInfo info, Artifact neoforge, boolean combined) {
        var deps = new ArrayList<Artifact>();
        var seen = new java.util.HashSet<String>();

        // Secondary game root as a Gradle Module variant dependency (not just a POM dep) so it survives
        // module-metadata resolution. FML 11 (separate): `universal` (NeoForge classes). FML 4.x (combined):
        // `client-extra` (Minecraft resources; the NeoForge classes are already in the main artifact).
        var secondaryClassifier = combined ? "client-extra" : "universal";
        var secondary = Artifact.from(neoforge.getGroup() + ':' + neoforge.getName() + ':' + neoforge.getVersion() + ':' + secondaryClassifier);
        deps.add(secondary);
        seen.add(secondary.getGroup() + ':' + secondary.getName() + ':' + secondary.getClassifier());

        // MC libraries (including OS-specific natives for variants). The dedup key MUST include the
        // classifier: each LWJGL module appears both as a base jar (e.g. org.lwjgl:lwjgl-glfw) AND as
        // a natives jar (org.lwjgl:lwjgl-glfw:natives-linux). Deduping by group:name only would drop
        // the natives, leaving LWJGL unable to load its shared libraries at runtime.
        var mcTasks = mcpconfig.getMCTasks(info.mcVersion());
        var versionJson = JsonData.minecraftVersion(mcTasks.versionJson.execute());
        for (var lib : versionJson.getLibs()) {
            var art = Artifact.from(lib.coord).withOS(lib.os);
            if (seen.add(art.getGroup() + ':' + art.getName() + ':' + art.getClassifier()))
                deps.add(art);
        }

        // NeoForge libraries from moddev-config.json (the authoritative classpath)
        if (info.config() != null && info.config().libraries != null) {
            for (var coord : info.config().libraries) {
                var art = Artifact.from(coord);
                if (seen.add(art.getGroup() + ':' + art.getName() + ':' + art.getClassifier()))
                    deps.add(art);
            }
        }

        return deps;
    }

    // --- POM parsing ---

    private PomInfo parseNeoForgePom(File pomFile, String neoforgeVersion) {
        try (var input = new java.io.FileInputStream(pomFile)) {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
            var deps = new ArrayList<PomDependency>();

            String mcVersion = null;
            String neoformVersion = null;

            var depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                var elem = (Element) depNodes.item(i);
                var group = getText(elem, "groupId");
                var name = getText(elem, "artifactId");
                var version = getText(elem, "version");
                var classifier = getText(elem, "classifier");
                var scope = getText(elem, "scope");

                // Find the NeoForm dependency to extract MC version
                if ("net.neoforged".equals(group) && "neoform".equals(name) && version != null) {
                    neoformVersion = version;
                    var dashIdx = version.indexOf('-');
                    mcVersion = dashIdx > 0 ? version.substring(0, dashIdx) : version;
                }

                // Only include compile/runtime deps (skip provided/test)
                if (scope == null || "compile".equals(scope) || "runtime".equals(scope)) {
                    deps.add(new PomDependency(group, name, version, classifier));
                }
            }

            if (mcVersion == null)
                throw new IllegalStateException("Could not determine MC version from NeoForge POM (no neoform dependency found)");

            LOGGER.info("NeoForge " + neoforgeVersion + " -> MC " + mcVersion + " (NeoForm " + neoformVersion + ")");

            return new PomInfo(mcVersion, neoformVersion, deps);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse NeoForge POM: " + pomFile.getAbsolutePath(), e);
        }
    }

    private static String getText(Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().trim();
    }

    // --- Records ---

    public record NeoForgeInfo(String mcVersion, String neoformVersion, List<PomDependency> dependencies, ModDevConfig config) {}
    public record PomInfo(String mcVersion, String neoformVersion, List<PomDependency> dependencies) {}
    public record PomDependency(String group, String name, String version, String classifier) {}

    /// NeoForge's moddev-config.json — the authoritative source for run configs and libraries.
    public static final class ModDevConfig {
        public int spec;
        public String mcp;
        public String sources;
        public String universal;
        public List<String> libraries;
        /// JVM module-path entries for BootstrapLauncher (the {@code -p {modules}} run argument). Present on
        /// FML versions that boot via the module path (e.g. MC 1.21.1 / FML 4.x); absent on newer configs.
        public List<String> modules;
        public Map<String, ModDevRun> runs;
    }

    public static final class ModDevRun {
        public String main;
        public List<String> args;
        public List<String> jvmArgs;
        public boolean client;
        public boolean server;
        public boolean dataGenerator;
        public boolean gameTest;
        public boolean unitTest;
        public Map<String, String> env;
        public Map<String, String> props;
    }
}
