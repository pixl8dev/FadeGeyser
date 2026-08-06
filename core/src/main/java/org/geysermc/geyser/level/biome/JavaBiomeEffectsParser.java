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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses Java biome registry NBT / datapack biome JSON-equivalent fields into visual color data.
 */
public final class JavaBiomeEffectsParser {
    private JavaBiomeEffectsParser() {
    }

    /**
     * Parsed climate + visual effects for a single Java biome.
     */
    public record ParsedEffects(
            float temperature,
            float downfall,
            boolean rain,
            @Nullable Integer grassColor,
            @Nullable Integer foliageColor,
            @Nullable Integer dryFoliageColor,
            @Nullable Integer waterColor,
            @Nullable Integer skyColor,
            @Nullable Integer fogColor,
            List<String> tags
    ) {
    }

    public static ParsedEffects parse(NbtMap data) {
        if (data == null || data.isEmpty()) {
            return new ParsedEffects(0.5f, 0.5f, true, null, null, null, null, null, null, List.of());
        }

        float temperature = data.containsKey("temperature") ? data.getFloat("temperature") : 0.5f;
        float downfall = data.containsKey("downfall") ? data.getFloat("downfall") : 0.5f;
        boolean rain = true;
        if (data.containsKey("has_precipitation")) {
            rain = data.getBoolean("has_precipitation");
        } else if (data.containsKey("precipitation")) {
            String precip = data.getString("precipitation", "rain");
            rain = !"none".equalsIgnoreCase(precip);
        }

        NbtMap effects = data.getCompound("effects");
        Integer grassColor = getColor(effects, "grass_color");
        Integer foliageColor = getColor(effects, "foliage_color");
        Integer dryFoliageColor = getColor(effects, "dry_foliage_color");
        Integer waterColor = getColor(effects, "water_color");
        Integer skyColor = getColor(effects, "sky_color");
        Integer fogColor = getColor(effects, "fog_color");

        // Modern attribute-style keys (some datapacks / newer formats)
        NbtMap attributes = data.getCompound("attributes");
        if (attributes != null && !attributes.isEmpty()) {
            if (fogColor == null) {
                fogColor = getColorFromAttribute(attributes, "minecraft:visual/fog_color");
            }
            if (skyColor == null) {
                skyColor = getColorFromAttribute(attributes, "minecraft:visual/sky_color");
            }
            if (waterColor == null) {
                // water fog is separate; surface water is usually under effects
            }
        }

        String grassModifier = effects != null ? effects.getString("grass_color_modifier", "") : "";
        // Only apply modifiers / defaults when an explicit color or known modifier is present.
        // Do not invent grass/foliage colors for vanilla biomes (would fight Bedrock defaults).
        if (grassColor == null) {
            if ("swamp".equalsIgnoreCase(grassModifier)) {
                grassColor = 0x6A7039;
            } else if ("dark_forest".equalsIgnoreCase(grassModifier)) {
                grassColor = blend(approximateGrassColor(temperature, downfall), 0x28340A, 0.5f);
            }
        } else if ("swamp".equalsIgnoreCase(grassModifier)) {
            // Explicit color + swamp modifier: Java still applies swamp noise; use a swamp-leaning tint
            grassColor = blend(grassColor, 0x4C763C, 0.35f);
        }

        List<String> tags = new ArrayList<>();
        tags.add("overworld");
        if (temperature < 0.15f) {
            tags.add("cold");
        } else if (temperature > 0.95f) {
            tags.add("hot");
        }
        if (downfall < 0.2f) {
            tags.add("dry");
        } else if (downfall > 0.85f) {
            tags.add("wet");
        }

        return new ParsedEffects(temperature, downfall, rain, grassColor, foliageColor, dryFoliageColor,
                waterColor, skyColor, fogColor, tags);
    }

    /**
     * Parse from a Java datapack biome JSON object already loaded as nested maps
     * (same field names as the NBT form).
     */
    @SuppressWarnings("unchecked")
    public static ParsedEffects parseJson(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            return parse(NbtMap.EMPTY);
        }
        // Convert shallow known fields into NbtMap for a single parse path
        NbtMapBuilder builder = NbtMap.builder();
        putNumber(builder, "temperature", json.get("temperature"));
        putNumber(builder, "downfall", json.get("downfall"));
        if (json.get("has_precipitation") instanceof Boolean b) {
            builder.putBoolean("has_precipitation", b);
        }
        if (json.get("precipitation") instanceof String s) {
            builder.putString("precipitation", s);
        }
        Object effectsObj = json.get("effects");
        if (effectsObj instanceof Map<?, ?> effectsMap) {
            NbtMapBuilder effects = NbtMap.builder();
            for (Map.Entry<?, ?> e : effectsMap.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    continue;
                }
                Object val = e.getValue();
                if (val instanceof Number n) {
                    effects.putInt(key, n.intValue());
                } else if (val instanceof String s) {
                    effects.putString(key, s);
                }
            }
            builder.putCompound("effects", effects.build());
        }
        Object attributesObj = json.get("attributes");
        if (attributesObj instanceof Map<?, ?> attrMap) {
            NbtMapBuilder attributes = NbtMap.builder();
            for (Map.Entry<?, ?> e : attrMap.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    continue;
                }
                Object val = e.getValue();
                if (val instanceof Number n) {
                    attributes.putInt(key, n.intValue());
                }
            }
            builder.putCompound("attributes", attributes.build());
        }
        return parse(builder.build());
    }

    private static void putNumber(NbtMapBuilder builder, String key, Object value) {
        if (value instanceof Number n) {
            builder.putFloat(key, n.floatValue());
        }
    }

    private static @Nullable Integer getColor(NbtMap effects, String key) {
        if (effects == null || effects.isEmpty() || !effects.containsKey(key)) {
            return null;
        }
        Object value = effects.get(key);
        if (value instanceof Number n) {
            return n.intValue() & 0xFFFFFF;
        }
        if (value instanceof String s) {
            return parseHexColor(s);
        }
        // NBT int via typed getter
        try {
            return effects.getInt(key) & 0xFFFFFF;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable Integer getColorFromAttribute(NbtMap attributes, String key) {
        if (!attributes.containsKey(key)) {
            return null;
        }
        Object value = attributes.get(key);
        if (value instanceof Number n) {
            return n.intValue() & 0xFFFFFF;
        }
        return null;
    }

    public static @Nullable Integer parseHexColor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        try {
            return (int) (Long.parseLong(s, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Approximate Java grass colormap sampling from temperature/downfall when no explicit color is set.
     */
    public static int approximateGrassColor(float temperature, float downfall) {
        float temp = clamp(temperature, 0f, 1f);
        float rain = clamp(downfall, 0f, 1f) * temp;
        // Corners of the vanilla-ish grass colormap (approximate):
        // hot+dry ~ yellowish, cold ~ blue-green, wet ~ deep green
        int hotDry = 0xBFB755;
        int hotWet = 0x59C93C;
        int coldDry = 0x80B497;
        int coldWet = 0x27AB2E;
        int hot = blend(hotDry, hotWet, rain);
        int cold = blend(coldDry, coldWet, rain);
        return blend(cold, hot, temp);
    }

    /**
     * Invert a grass RGB into Bedrock climate so the network biome definition's
     * temperature/downfall sample a close grass colormap tint.
     * <p>
     * Multiplayer custom biomes reliably apply {@code mapWaterColor} from
     * {@code BiomeDefinitionListPacket}; grass often falls back to this colormap
     * when {@code client_biomes} grass_appearance does not stick. Result is
     * approximate (same family of greens/yellows/blues), not bit-exact hex.
     *
     * @return float[2] = { temperature, downfall }
     */
    public static float[] climateFromGrassColor(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        // Red/yellow vs blue → hotter vs colder. Allow slightly >1 for desert-like yellows.
        float temperature = clamp(0.35f + (r - b) * 1.1f + (r - g) * 0.35f, 0f, 1.4f);
        // Green dominance / lack of yellow → wetter
        float downfall = clamp(0.15f + (g - r * 0.55f) + (g - b * 0.2f) * 0.5f, 0f, 1f);
        return new float[]{temperature, downfall};
    }

    public static int approximateFoliageColor(float temperature, float downfall) {
        float temp = clamp(temperature, 0f, 1f);
        float rain = clamp(downfall, 0f, 1f) * temp;
        int hotDry = 0xAEA42A;
        int hotWet = 0x30BB0B;
        int coldDry = 0x60A17B;
        int coldWet = 0x1AAB1E;
        int hot = blend(hotDry, hotWet, rain);
        int cold = blend(coldDry, coldWet, rain);
        return blend(cold, hot, temp);
    }

    private static int blend(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
