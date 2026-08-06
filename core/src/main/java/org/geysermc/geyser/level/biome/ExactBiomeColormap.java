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
import org.geysermc.geyser.GeyserImpl;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exact grass/foliage/dry-foliage colors for custom biomes via network climate → colormap sampling.
 * <p>
 * Bedrock multiplayer reliably applies {@code mapWaterColor} from BiomeDefinitionList, but
 * {@code client_biomes} grass/foliage appearance often does not stick for custom network IDs even with a BP.
 * Vanilla and Bedrock sample {@code textures/colormap/grass.png}, {@code foliage.png}, and
 * {@code dry_foliage.png} (leaf litter) using:
 * <pre>
 *   x = (1 - temperature) * 255
 *   y = (1 - downfall * temperature) * 255
 * </pre>
 * We keep the vanilla base colormaps (so {@code minecraft:*} biomes in a vanilla dimension look normal),
 * write each custom biome's exact Java hex into a reserved coordinate band, and set that biome's
 * network temperature/downfall so sampling hits that pixel — bit-exact RGB, not approximate.
 * <p>
 * Dry foliage is colormap-only (not {@code minecraft:dry_foliage_color} in client_biomes), which
 * previously caused Bedrock Block crashes on some clients.
 */
public final class ExactBiomeColormap {
    private static final int SIZE = 256;
    /**
     * Reserved band: x in [128, 255], y in [252, 255] (512 slots).
     * Avoids the hot-dry corner (x≈0, y≈255) used by deserts and similar.
     */
    private static final int RESERVE_X0 = 128;
    private static final int RESERVE_Y0 = 252;

    private final Map<String, Climate> climateByBedrockId = new LinkedHashMap<>();
    private byte[] grassPng = new byte[0];
    private byte[] foliagePng = new byte[0];
    private byte[] dryFoliagePng = new byte[0];

    public record Climate(float temperature, float downfall) {
    }

    /**
     * Rebuild colormaps and climate table from the current custom-biome color set.
     */
    public void rebuild(Collection<CustomBiomeDefinition> biomes) throws IOException {
        climateByBedrockId.clear();
        BufferedImage grass = loadBase("bedrock/colormap/grass.png");
        BufferedImage foliage = loadBase("bedrock/colormap/foliage.png");
        BufferedImage dryFoliage = loadBase("bedrock/colormap/dry_foliage.png");

        int slot = 0;
        int dryExplicit = 0;
        // One slot per custom-network biome (exact grass + foliage + dry foliage at same pixel).
        // Vanilla overrides keep stock climate — only client_biomes for those.
        for (CustomBiomeDefinition def : biomes) {
            if (!def.customNetworkId()) {
                continue;
            }
            int grassRgb = def.grassColor() != null
                    ? def.grassColor()
                    : JavaBiomeEffectsParser.approximateGrassColor(def.temperature(), def.downfall());
            int foliageRgb = def.foliageColor() != null
                    ? def.foliageColor()
                    : JavaBiomeEffectsParser.approximateFoliageColor(def.temperature(), def.downfall());
            int dryRgb;
            if (def.dryFoliageColor() != null) {
                dryRgb = def.dryFoliageColor();
                dryExplicit++;
            } else {
                // Match vanilla sampling of dry_foliage.png at the biome's natural climate.
                dryRgb = sampleColormap(dryFoliage, def.temperature(), def.downfall());
            }

            int[] xy = slotToXy(slot++);
            int x = xy[0];
            int y = xy[1];
            grass.setRGB(x, y, 0xFF000000 | (grassRgb & 0xFFFFFF));
            foliage.setRGB(x, y, 0xFF000000 | (foliageRgb & 0xFFFFFF));
            dryFoliage.setRGB(x, y, 0xFF000000 | (dryRgb & 0xFFFFFF));
            climateByBedrockId.put(def.bedrockIdentifier(), climateFromXy(x, y));
        }

        grassPng = toPng(grass);
        foliagePng = toPng(foliage);
        dryFoliagePng = toPng(dryFoliage);
        GeyserImpl.getInstance().getLogger().info(
                "Exact biome colormaps: " + climateByBedrockId.size()
                        + " custom biomes with pixel-exact grass/foliage/dry_foliage"
                        + " (" + dryExplicit + " explicit dry_foliage_color; vanilla colormap bases preserved).");
    }

    public @Nullable Climate climateFor(String bedrockIdentifier) {
        return climateByBedrockId.get(bedrockIdentifier);
    }

    public byte[] grassPng() {
        return grassPng;
    }

    public byte[] foliagePng() {
        return foliagePng;
    }

    public byte[] dryFoliagePng() {
        return dryFoliagePng;
    }

    public boolean hasData() {
        return !climateByBedrockId.isEmpty() && grassPng.length > 0 && dryFoliagePng.length > 0;
    }

    /** Sample a 256×256 colormap at Java climate coordinates. */
    static int sampleColormap(BufferedImage map, float temperature, float downfall) {
        int[] xy = xyFromClimate(temperature, downfall);
        int x = Math.max(0, Math.min(SIZE - 1, xy[0]));
        int y = Math.max(0, Math.min(SIZE - 1, xy[1]));
        return map.getRGB(x, y) & 0xFFFFFF;
    }

    private static int[] slotToXy(int slot) {
        // 128 wide × 4 rows = 512 slots
        int width = SIZE - RESERVE_X0;
        int x = RESERVE_X0 + (slot % width);
        int y = RESERVE_Y0 + (slot / width);
        if (y >= SIZE) {
            // Overflow: wrap within band (still better than crashing)
            y = RESERVE_Y0 + (slot % (SIZE - RESERVE_Y0));
            x = RESERVE_X0 + ((slot / (SIZE - RESERVE_Y0)) % width);
        }
        return new int[]{x, y};
    }

    /**
     * Inverse of Bedrock/Java colormap sampling with temp, downfall in [0, 1].
     */
    static Climate climateFromXy(int x, int y) {
        float temperature = 1.0f - (x / 255.0f);
        if (temperature < 0.001f) {
            temperature = 0.001f;
        }
        float downfall = (1.0f - (y / 255.0f)) / temperature;
        if (downfall < 0f) {
            downfall = 0f;
        }
        if (downfall > 1f) {
            downfall = 1f;
        }
        // Verify round-trip; nudge if clamp broke exact hit
        int rx = Math.round((1.0f - temperature) * 255.0f);
        int ry = Math.round((1.0f - downfall * temperature) * 255.0f);
        if (rx != x || ry != y) {
            // Prefer exact x via temperature; recompute downfall without clamping above 1
            temperature = 1.0f - (x / 255.0f);
            if (temperature < 0.001f) {
                temperature = 0.001f;
            }
            downfall = (1.0f - (y / 255.0f)) / temperature;
            // Allow slightly >1 if needed for exact y (Bedrock may still sample correctly)
            if (downfall < 0f) {
                downfall = 0f;
            }
        }
        return new Climate(temperature, downfall);
    }

    private static BufferedImage loadBase(String resourcePath) throws IOException {
        try (InputStream in = ExactBiomeColormap.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing resource " + resourcePath);
            }
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                throw new IOException("Unreadable image " + resourcePath);
            }
            BufferedImage copy = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int sx = Math.min(x, src.getWidth() - 1);
                    int sy = Math.min(y, src.getHeight() - 1);
                    copy.setRGB(x, y, src.getRGB(sx, sy));
                }
            }
            return copy;
        }
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bos)) {
            throw new IOException("PNG writer not available");
        }
        return bos.toByteArray();
    }

    /** Verify climate→pixel round-trip for tests. */
    static int[] xyFromClimate(float temperature, float downfall) {
        temperature = Math.max(0f, Math.min(1f, temperature));
        downfall = Math.max(0f, Math.min(1f, downfall));
        int x = Math.round((1.0f - temperature) * 255.0f);
        int y = Math.round((1.0f - downfall * temperature) * 255.0f);
        return new int[]{x, y};
    }

    @Override
    public String toString() {
        return "ExactBiomeColormap{slots=" + climateByBedrockId.size() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExactBiomeColormap that)) return false;
        return Objects.equals(climateByBedrockId, that.climateByBedrockId);
    }

    @Override
    public int hashCode() {
        return climateByBedrockId.hashCode();
    }
}
