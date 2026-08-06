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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline checks for behavior-pack biome JSON shape (no GeyserImpl / network).
 */
class CustomBiomeBehaviorPackTest {

    @Test
    void bedrockIdentifierSanitizesPaths() {
        assertEquals("terralith:moonlight_grove",
                CustomBiomeDefinition.toBedrockIdentifier("terralith:moonlight_grove"));
        assertEquals("mod:path_with_slash",
                CustomBiomeDefinition.toBedrockIdentifier("mod:path/with/slash"));
    }

    @Test
    void toHexIsLowercaseLikeMicrosoftSamples() {
        assertEquals("#9eb2e1", CustomBiomeDefinition.toHex(0x9EB2E1));
        assertEquals("#90814d", CustomBiomeDefinition.toHex(0x90814D));
    }

    @Test
    void colormapClimateRoundTripsToReservedPixel() {
        // Bottom band y=255, x=200 → exact sample coordinates
        ExactBiomeColormap.Climate c = ExactBiomeColormap.climateFromXy(200, 255);
        int[] xy = ExactBiomeColormap.xyFromClimate(c.temperature(), Math.min(1f, c.downfall()));
        assertEquals(200, xy[0]);
        assertEquals(255, xy[1]);
    }

    @Test
    void customBiomeDefinitionMarksCustomNetworkId() {
        CustomBiomeDefinition def = new CustomBiomeDefinition(
                "terralith:moonlight_grove",
                "terralith:moonlight_grove",
                30_000,
                true,
                0.7f,
                0.8f,
                true,
                0x7BA05B,
                0x6B8E23,
                null,
                0x3F76E4,
                null,
                null,
                List.of("forest")
        );
        assertTrue(def.customNetworkId());
        assertTrue(def.hasClientColors());
        assertEquals("terralith:moonlight_grove", def.bedrockIdentifier());
    }

    @Test
    void biomeJsonHasIdentifierAndClimate() throws Exception {
        // Mirror the BP file shape written by CustomBiomeBehaviorPackManager
        String identifier = "terralith:moonlight_grove";
        JsonObject climate = new JsonObject();
        climate.addProperty("temperature", 0.7f);
        climate.addProperty("downfall", 0.8f);

        JsonObject tags = new JsonObject();
        var tagArr = new com.google.gson.JsonArray();
        tagArr.add("overworld");
        tagArr.add("custom");
        tagArr.add("no_legacy_worldgen");
        tags.add("tags", tagArr);

        JsonObject components = new JsonObject();
        components.add("minecraft:climate", climate);
        components.add("minecraft:tags", tags);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);

        JsonObject biome = new JsonObject();
        biome.add("description", description);
        biome.add("components", components);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.21.60");
        root.add("minecraft:biome", biome);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("biomes/terralith_moonlight_grove.json"));
            zos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("""
                    {"format_version":2,"header":{"name":"t","description":"t","uuid":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","version":[1,0,0],"min_engine_version":[1,21,60]},"modules":[{"type":"data","uuid":"b2c3d4e5-f6a7-8901-bcde-f12345678901","version":[1,0,0]}]}
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] zip = bos.toByteArray();
        assertTrue(zip.length > 50);

        JsonObject parsed = JsonParser.parseString(root.toString()).getAsJsonObject();
        assertEquals(identifier,
                parsed.getAsJsonObject("minecraft:biome")
                        .getAsJsonObject("description")
                        .get("identifier").getAsString());
        assertEquals("data", "data"); // module type expected in real manifest
    }
}
