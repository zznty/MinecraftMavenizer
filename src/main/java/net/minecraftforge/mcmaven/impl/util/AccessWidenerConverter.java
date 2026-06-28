/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import net.minecraftforge.srgutils.IMappingFile;

/// Converts Fabric-style Access Widener files (`.accesswidener v2 named`) to Forge-style
/// Access Transformer files (`.cfg`), remapping class/method/field names from the `named` namespace
/// (Mojang) to the target namespace (SRG for Forge).
public final class AccessWidenerConverter {
    private AccessWidenerConverter() { }

    public static File convert(File awFile, File srgMappingFile, File workDir) throws IOException {
        var mapping = IMappingFile.load(srgMappingFile);
        var atFile = new File(workDir, awFile.getName().replaceFirst("\\.accesswidener$", "") + ".cfg");

        try (var reader = Files.newBufferedReader(awFile.toPath(), StandardCharsets.UTF_8);
             var writer = Files.newBufferedWriter(atFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write("# Converted from " + awFile.getName() + "\n");

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("accessWidener"))
                    continue;

                var parts = line.split("\\s+");
                if (parts.length < 3) continue;

                var accessType = parts[0];
                var targetType = parts[1];
                var mojClass = parts[2];
                var srgClass = mapping.remapClass(mojClass);

                try {
                    switch (targetType) {
                        case "class" -> writer.write(toAtModifier(accessType) + " " + srgClass + "\n");
                        case "field" -> {
                            if (parts.length < 5) continue;
                            var mojField = parts[3];
                            var srgField = findSrgField(mapping, mojClass, mojField);
                            writer.write(toAtModifier(accessType) + " " + srgClass + " " + srgField + "\n");
                        }
                        case "method" -> {
                            if (parts.length < 5) continue;
                            var mojMethod = parts[3];
                            var mojDesc = parts[4];
                            var entry = findSrgMethod(mapping, mojClass, mojMethod, mojDesc);
                            if (entry == null) continue;
                            var srgDesc = mapping.remapDescriptor(mojDesc);
                            writer.write(toAtModifier(accessType) + " " + srgClass + " " + entry.mappedName + srgDesc + "\n");
                        }
                    }
                } catch (Exception e) {
                    writer.write("# ERROR: " + line + " (" + e.getMessage() + ")\n");
                }
            }
        }

        return atFile;
    }

    private static String toAtModifier(String awAccessType) {
        return switch (awAccessType) {
            case "accessible" -> "public";
            case "extendable", "mutable" -> "public-f";
            default -> throw new IllegalArgumentException("Unknown AW access type: " + awAccessType);
        };
    }

    private static String findSrgField(IMappingFile mapping, String mojClass, String mojField) {
        var cls = mapping.getClass(mojClass);
        if (cls == null) return mojField;
        for (var f : cls.getFields()) {
            if (mojField.equals(f.getOriginal()))
                return f.getMapped();
        }
        return mojField;
    }

    private static MethodEntry findSrgMethod(IMappingFile mapping, String mojClass, String mojMethod, String mojDesc) {
        var cls = mapping.getClass(mojClass);
        if (cls == null) return null;
        for (var m : cls.getMethods()) {
            if (mojMethod.equals(m.getOriginal()) && mojDesc.equals(m.getDescriptor()))
                return new MethodEntry(m.getMapped(), m.getMappedDescriptor());
        }
        return null;
    }

    private record MethodEntry(String mappedName, String mappedDescriptor) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: AccessWidenerConverter <aw-file> <srg-mapping.tsrg.gz>");
            System.exit(1);
        }
        var result = convert(new File(args[0]), new File(args[1]), new File("."));
        System.out.println("Wrote: " + result.getAbsolutePath());
    }
}
