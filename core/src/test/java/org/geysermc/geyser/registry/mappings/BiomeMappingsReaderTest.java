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

package org.geysermc.geyser.registry.mappings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.level.biome.BiomeMappingEntry;
import org.geysermc.geyser.registry.mappings.versions.biome.BiomeMappingsReader_v1;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeMappingsReaderTest {

    @Test
    void readsHexAndClimate() {
        String json = """
                {
                  "terralith:moonlight_grove": {
                    "temperature": 0.7,
                    "downfall": 0.8,
                    "rain": true,
                    "grass_color": "#9eb2e1",
                    "foliage_color": "#7cc895",
                    "dry_foliage_color": 8082228,
                    "water_color": "0x92b2e1"
                  }
                }
                """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, BiomeMappingEntry> out = new LinkedHashMap<>();
        new BiomeMappingsReader_v1().read(Path.of("test.json"), root, out::put);

        assertEquals(1, out.size());
        BiomeMappingEntry e = out.get("terralith:moonlight_grove");
        assertNotNull(e);
        assertEquals(0.7f, e.temperature(), 0.001f);
        assertEquals(0x9eb2e1, e.grassColor());
        assertEquals(0x7cc895, e.foliageColor());
        assertEquals(8082228, e.dryFoliageColor());
        assertEquals(0x92b2e1, e.waterColor());
        assertTrue(e.rain());
    }

    @Test
    void normalizesBareMinecraftIds() {
        String json = """
                { "swamp": { "water_color": "#617b64" } }
                """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, BiomeMappingEntry> out = new LinkedHashMap<>();
        new BiomeMappingsReader_v1().read(Path.of("test.json"), root, out::put);
        assertTrue(out.containsKey("minecraft:swamp"));
        assertEquals(0x617b64, out.get("minecraft:swamp").waterColor());
    }
}
