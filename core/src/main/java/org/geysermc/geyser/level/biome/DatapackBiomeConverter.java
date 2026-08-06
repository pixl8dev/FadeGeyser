/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.level.biome;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.geysermc.geyser.GeyserImpl;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Converts Java datapack biome JSON files into {@link CustomBiomeDefinition}s
 * for pre-warming the custom biome registry / resource pack before the first join.
 */
public final class DatapackBiomeConverter {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private DatapackBiomeConverter() {
    }

    public static List<CustomBiomeDefinition> convert(Path input) throws IOException {
        if (input == null || !Files.exists(input)) {
            throw new IOException("Datapack path does not exist: " + input);
        }
        if (Files.isDirectory(input)) {
            return convertDirectory(input);
        }
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".jar")) {
            return convertZip(input);
        }
        throw new IOException("Unsupported datapack path (need directory or zip): " + input);
    }

    /**
     * Convert and register into the global {@link CustomBiomeRegistry}.
     *
     * @return number of biomes registered
     */
    public static int convertAndRegister(Path input) throws IOException {
        List<CustomBiomeDefinition> defs = convert(input);
        for (CustomBiomeDefinition def : defs) {
            CustomBiomeRegistry.get().registerFromConverter(def);
        }
        return defs.size();
    }

    private static List<CustomBiomeDefinition> convertDirectory(Path root) throws IOException {
        List<CustomBiomeDefinition> result = new ArrayList<>();
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) {
            // Some packs put data at the root already
            data = root;
        }
        try (Stream<Path> stream = Files.walk(data)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String rel = data.relativize(path).toString().replace('\\', '/');
                CustomBiomeDefinition def = fromRelativeJsonPath(rel, path);
                if (def != null) {
                    result.add(def);
                }
            }
        }
        return result;
    }

    private static List<CustomBiomeDefinition> convertZip(Path zipPath) throws IOException {
        List<CustomBiomeDefinition> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                // Strip optional leading folder if the zip was double-wrapped
                String rel = name;
                int dataIdx = name.indexOf("data/");
                if (dataIdx >= 0) {
                    rel = name.substring(dataIdx + "data/".length());
                } else if (name.startsWith("data/")) {
                    rel = name.substring("data/".length());
                } else {
                    continue;
                }
                if (!isBiomeJson(rel)) {
                    continue;
                }
                try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    Map<String, Object> json = GSON.fromJson(reader, MAP_TYPE);
                    CustomBiomeDefinition def = fromJson(javaIdentifierFromRel(rel), json);
                    if (def != null) {
                        result.add(def);
                    }
                } catch (Exception e) {
                    GeyserImpl.getInstance().getLogger().debug("Skipping biome JSON " + name + ": " + e.getMessage());
                }
            }
        }
        return result;
    }

    private static CustomBiomeDefinition fromRelativeJsonPath(String rel, Path path) {
        if (!isBiomeJson(rel)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, Object> json = GSON.fromJson(reader, MAP_TYPE);
            return fromJson(javaIdentifierFromRel(rel), json);
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().debug("Skipping biome JSON " + rel + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isBiomeJson(String rel) {
        // namespace/worldgen/biome/.../name.json  (not tags)
        if (!rel.endsWith(".json")) {
            return false;
        }
        // strip leading "data/" if present
        if (rel.startsWith("data/")) {
            rel = rel.substring(5);
        }
        int idx = rel.indexOf("/worldgen/biome/");
        if (idx < 0) {
            return false;
        }
        // exclude tags
        return !rel.contains("/tags/");
    }

    private static String javaIdentifierFromRel(String rel) {
        if (rel.startsWith("data/")) {
            rel = rel.substring(5);
        }
        // <namespace>/worldgen/biome/<path>.json
        int idx = rel.indexOf("/worldgen/biome/");
        if (idx < 0) {
            throw new IllegalArgumentException("Not a biome path: " + rel);
        }
        String namespace = rel.substring(0, idx);
        String path = rel.substring(idx + "/worldgen/biome/".length());
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - 5);
        }
        return namespace + ":" + path;
    }

    private static CustomBiomeDefinition fromJson(String javaIdentifier, Map<String, Object> json) {
        if (json == null) {
            return null;
        }
        JavaBiomeEffectsParser.ParsedEffects effects = JavaBiomeEffectsParser.parseJson(json);
        // bedrock id filled in by registry
        return new CustomBiomeDefinition(
                javaIdentifier,
                CustomBiomeRegistry.CUSTOM_BIOME_ID_START,
                true,
                effects.temperature(),
                effects.downfall(),
                effects.rain(),
                effects.grassColor(),
                effects.foliageColor(),
                effects.dryFoliageColor(),
                effects.waterColor(),
                effects.skyColor(),
                effects.fogColor(),
                effects.tags()
        );
    }

    /**
     * Pre-warm registry from configured datapack paths. Safe to call at startup.
     */
    public static void prewarmFromConfig(List<String> datapackPaths) {
        if (datapackPaths == null || datapackPaths.isEmpty()) {
            return;
        }
        for (String raw : datapackPaths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path path = Path.of(raw.trim());
            try {
                int count = convertAndRegister(path);
                GeyserImpl.getInstance().getLogger().info(
                        "Custom biomes: pre-warmed " + count + " biomes from " + path.getFileName());
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().warning(
                        "Custom biomes: failed to pre-warm from " + path + ": " + e.getMessage());
            }
        }
        CustomBiomeRegistry.get().savePersistedIds();
    }
}
