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

import java.awt.Color;
import java.util.List;
import java.util.Objects;

/**
 * Client-facing visual data for a Java biome, used to build Bedrock
 * {@code BiomeDefinitionData} entries and {@code client_biomes} resource pack files.
 */
public final class CustomBiomeDefinition {
    private final String javaIdentifier;
    /**
     * Identifier used in Bedrock client_biomes / definition list keys and the behavior pack.
     * Custom biomes use the Java identifier (e.g. {@code terralith:moonlight_grove});
     * vanilla biomes use their normal {@code minecraft:*} name.
     */
    private final String bedrockClientIdentifier;
    private final int bedrockId;
    private final boolean customNetworkId;
    private final float temperature;
    private final float downfall;
    private final boolean rain;
    private final @Nullable Integer grassColor;
    private final @Nullable Integer foliageColor;
    private final @Nullable Integer dryFoliageColor;
    private final @Nullable Integer waterColor;
    private final @Nullable Integer skyColor;
    private final @Nullable Integer fogColor;
    private final List<String> tags;

    public CustomBiomeDefinition(
            String javaIdentifier,
            int bedrockId,
            boolean customNetworkId,
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
        this(javaIdentifier, null, bedrockId, customNetworkId, temperature, downfall, rain,
                grassColor, foliageColor, dryFoliageColor, waterColor, skyColor, fogColor, tags);
    }

    public CustomBiomeDefinition(
            String javaIdentifier,
            @Nullable String bedrockClientIdentifier,
            int bedrockId,
            boolean customNetworkId,
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
        this.javaIdentifier = Objects.requireNonNull(javaIdentifier, "javaIdentifier");
        this.bedrockClientIdentifier = bedrockClientIdentifier != null
                ? bedrockClientIdentifier
                : toBedrockIdentifier(javaIdentifier);
        this.bedrockId = bedrockId;
        this.customNetworkId = customNetworkId;
        this.temperature = temperature;
        this.downfall = downfall;
        this.rain = rain;
        this.grassColor = grassColor;
        this.foliageColor = foliageColor;
        this.dryFoliageColor = dryFoliageColor;
        this.waterColor = waterColor;
        this.skyColor = skyColor;
        this.fogColor = fogColor;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public String javaIdentifier() {
        return javaIdentifier;
    }

    /**
     * Bedrock identifier for client_biomes (must be a real game/BP biome for grass tinting).
     */
    public String bedrockIdentifier() {
        return bedrockClientIdentifier;
    }

    public static String toBedrockIdentifier(String javaIdentifier) {
        if (javaIdentifier == null) {
            return "minecraft:plains";
        }
        // Keep namespace:path, but flatten path separators and other illegal chars.
        int colon = javaIdentifier.indexOf(':');
        if (colon < 0) {
            return sanitizePath(javaIdentifier);
        }
        String ns = sanitizePath(javaIdentifier.substring(0, colon));
        String path = sanitizePath(javaIdentifier.substring(colon + 1));
        return ns + ":" + path;
    }

    private static String sanitizePath(String s) {
        // Bedrock identifiers: [a-z0-9._-] roughly; slash/spaces will crash some clients.
        return s.toLowerCase()
                .replace('/', '_')
                .replace(' ', '_')
                .replaceAll("[^a-z0-9._-]", "_");
    }

    public int bedrockId() {
        return bedrockId;
    }

    /**
     * {@code true} when this biome uses a custom Bedrock network ID (&gt;= 30000).
     * {@code false} for vanilla network IDs that only receive color overrides.
     */
    public boolean customNetworkId() {
        return customNetworkId;
    }

    public float temperature() {
        return temperature;
    }

    public float downfall() {
        return downfall;
    }

    public boolean rain() {
        return rain;
    }

    public @Nullable Integer grassColor() {
        return grassColor;
    }

    public @Nullable Integer foliageColor() {
        return foliageColor;
    }

    public @Nullable Integer dryFoliageColor() {
        return dryFoliageColor;
    }

    public @Nullable Integer waterColor() {
        return waterColor;
    }

    public @Nullable Integer skyColor() {
        return skyColor;
    }

    public @Nullable Integer fogColor() {
        return fogColor;
    }

    public List<String> tags() {
        return tags;
    }

    public boolean hasClientColors() {
        return grassColor != null || foliageColor != null || dryFoliageColor != null
                || waterColor != null || skyColor != null;
    }

    public Color mapWaterColor() {
        int rgb = waterColor != null ? waterColor : 0x3F76E4; // vanilla default-ish
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 165);
    }

    public static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int blue(int rgb) {
        return rgb & 0xFF;
    }

    /**
     * Bedrock samples use lowercase hex (e.g. {@code #90814d}).
     */
    public static String toHex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    public CustomBiomeDefinition withBedrockId(int newBedrockId, boolean custom) {
        return new CustomBiomeDefinition(
                javaIdentifier, bedrockClientIdentifier, newBedrockId, custom, temperature, downfall, rain,
                grassColor, foliageColor, dryFoliageColor, waterColor, skyColor, fogColor, tags
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomBiomeDefinition that)) return false;
        return bedrockId == that.bedrockId
                && customNetworkId == that.customNetworkId
                && Float.compare(that.temperature, temperature) == 0
                && Float.compare(that.downfall, downfall) == 0
                && rain == that.rain
                && javaIdentifier.equals(that.javaIdentifier)
                && bedrockClientIdentifier.equals(that.bedrockClientIdentifier)
                && Objects.equals(grassColor, that.grassColor)
                && Objects.equals(foliageColor, that.foliageColor)
                && Objects.equals(dryFoliageColor, that.dryFoliageColor)
                && Objects.equals(waterColor, that.waterColor)
                && Objects.equals(skyColor, that.skyColor)
                && Objects.equals(fogColor, that.fogColor)
                && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(javaIdentifier, bedrockClientIdentifier, bedrockId, customNetworkId, temperature, downfall, rain,
                grassColor, foliageColor, dryFoliageColor, waterColor, skyColor, fogColor, tags);
    }
}
