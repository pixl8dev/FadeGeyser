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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatapackBiomeConverterTest {

    @Test
    void parseMoonlightGroveColorsFromJsonMap() {
        // grass_color 10400481 = 0x9EA5E1-ish; verify parser path via JSON map
        var json = new java.util.LinkedHashMap<String, Object>();
        json.put("temperature", 0.7);
        json.put("downfall", 0.8);
        var effects = new java.util.LinkedHashMap<String, Object>();
        effects.put("grass_color", 10400481);
        effects.put("foliage_color", 8177813);
        effects.put("water_color", 9614049);
        json.put("effects", effects);

        JavaBiomeEffectsParser.ParsedEffects parsed = JavaBiomeEffectsParser.parseJson(json);
        assertEquals(10400481, parsed.grassColor());
        assertEquals(8177813, parsed.foliageColor());
        assertEquals(9614049, parsed.waterColor());
        assertEquals(0.7f, parsed.temperature(), 0.001f);
    }

    @Test
    void convertTerralithIfPresent() throws Exception {
        Path terralith = Path.of(System.getProperty("user.home"), "Downloads", "Terralith_26.2_v2.6.4.zip");
        Assumptions.assumeTrue(Files.isRegularFile(terralith), "Terralith zip not present — skipping");

        List<CustomBiomeDefinition> defs = DatapackBiomeConverter.convert(terralith);
        assertTrue(defs.size() > 50, "expected many Terralith biomes, got " + defs.size());

        CustomBiomeDefinition moonlight = defs.stream()
                .filter(d -> d.javaIdentifier().equals("terralith:moonlight_grove"))
                .findFirst()
                .orElse(null);
        assertNotNull(moonlight, "missing terralith:moonlight_grove");
        assertNotNull(moonlight.grassColor());
        assertNotNull(moonlight.foliageColor());
        assertNotNull(moonlight.waterColor());
        assertEquals(10400481, moonlight.grassColor());

        long customNamespace = defs.stream().filter(d -> d.javaIdentifier().startsWith("terralith:")).count();
        long vanillaOverrides = defs.stream().filter(d -> d.javaIdentifier().startsWith("minecraft:")).count();
        assertTrue(customNamespace >= 90, "custom terralith biomes: " + customNamespace);
        assertTrue(vanillaOverrides >= 1, "vanilla overrides: " + vanillaOverrides);
    }

    @Test
    void colorHelpers() {
        assertEquals("#" + Integer.toHexString(10400481).toUpperCase(),
                CustomBiomeDefinition.toHex(10400481).toUpperCase());
        assertEquals((10400481 >> 16) & 0xFF, CustomBiomeDefinition.red(10400481));
    }
}
