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

package org.geysermc.geyser.registry.mappings.versions.biome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.level.biome.BiomeMappingEntry;
import org.geysermc.geyser.registry.mappings.MappingsReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * format_version 1 biome mappings.
 * <pre>
 * {
 *   "format_version": 1,
 *   "biomes": {
 *     "namespace:biome_id": {
 *       "temperature": 0.7,
 *       "downfall": 0.8,
 *       "rain": true,
 *       "grass_color": "#9eb2e1",
 *       "foliage_color": "#7cc895",
 *       "dry_foliage_color": "#7b5e3b",
 *       "water_color": "#92b2e1",
 *       "sky_color": "#78a7ff",
 *       "fog_color": "#c0d8ff",
 *       "tags": ["overworld"]
 *     }
 *   }
 * }
 * </pre>
 * Colors may be {@code #rrggbb} / {@code #rgb} hex strings, decimal ints, or 0x-prefixed strings.
 */
public final class BiomeMappingsReader_v1 implements MappingsReader<String, BiomeMappingEntry> {

    @Override
    public void read(Path file, JsonObject mappings, BiConsumer<String, BiomeMappingEntry> consumer) {
        for (var entry : mappings.entrySet()) {
            String javaId = entry.getKey();
            if (javaId == null || javaId.isBlank()) {
                GeyserImpl.getInstance().getLogger().error(
                        "Biome mapping key empty in " + file);
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                GeyserImpl.getInstance().getLogger().error(
                        "Biome mapping for " + javaId + " in " + file + " must be a JSON object");
                continue;
            }
            try {
                consumer.accept(normalizeId(javaId), parseEntry(javaId, entry.getValue().getAsJsonObject()));
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().error(
                        "Error reading biome mapping for " + javaId + " in " + file, e);
            }
        }
    }

    private static BiomeMappingEntry parseEntry(String javaId, JsonObject obj) {
        float temperature = readFloat(obj, "temperature", 0.5f);
        float downfall = readFloat(obj, "downfall", 0.5f);
        boolean rain = true;
        if (obj.has("rain") && obj.get("rain").isJsonPrimitive()) {
            rain = obj.get("rain").getAsBoolean();
        } else if (obj.has("has_precipitation") && obj.get("has_precipitation").isJsonPrimitive()) {
            rain = obj.get("has_precipitation").getAsBoolean();
        }

        Integer grass = readColor(obj, "grass_color");
        Integer foliage = readColor(obj, "foliage_color");
        Integer dryFoliage = readColor(obj, "dry_foliage_color");
        Integer water = readColor(obj, "water_color");
        Integer sky = readColor(obj, "sky_color");
        Integer fog = readColor(obj, "fog_color");

        List<String> tags = new ArrayList<>();
        if (obj.has("tags") && obj.get("tags").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("tags");
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    tags.add(el.getAsString());
                }
            }
        }

        Integer bedrockId = null;
        if (obj.has("bedrock_id") && obj.get("bedrock_id").isJsonPrimitive()) {
            bedrockId = obj.get("bedrock_id").getAsInt();
        }

        return new BiomeMappingEntry(
                normalizeId(javaId),
                temperature,
                downfall,
                rain,
                grass,
                foliage,
                dryFoliage,
                water,
                sky,
                fog,
                tags,
                bedrockId
        );
    }

    private static String normalizeId(String id) {
        id = id.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            return "minecraft:" + id;
        }
        return id;
    }

    private static float readFloat(JsonObject obj, String key, float def) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return def;
        }
        return obj.get(key).getAsFloat();
    }

    static Integer readColor(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isNumber()) {
            return p.getAsInt() & 0xFFFFFF;
        }
        String s = p.getAsString().trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
            if (s.length() == 3) {
                // #rgb → #rrggbb
                s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
            }
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseInt(s.substring(2), 16) & 0xFFFFFF;
        }
        // decimal string
        return Integer.parseInt(s) & 0xFFFFFF;
    }
}
