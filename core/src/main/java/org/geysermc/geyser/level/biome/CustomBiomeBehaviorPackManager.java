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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.pack.GeyserResourcePack;
import org.geysermc.geyser.pack.ResourcePackHolder;
import org.geysermc.geyser.registry.loader.ResourcePackLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Behavior pack that registers each custom (non-vanilla) Java biome identifier on Bedrock.
 * Required for resource-pack {@code grass_appearance} / {@code foliage_appearance} with exact hex.
 * <p>
 * No worldgen rules — Java owns terrain. Does not touch {@code minecraft:*} biomes.
 */
public final class CustomBiomeBehaviorPackManager {
    /** v4: climate aligned with exact colormap sampling for custom biomes. */
    public static final UUID PACK_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567893");
    private static final String MODULE_UUID = "b2c3d4e5-f6a7-8901-bcde-f12345678904";

    private static final String PACK_NAME = "Geyser Custom Biome Data";
    private static final String PACK_DESCRIPTION = "Registers datapack biomes for exact grass/foliage colors (vanilla-safe)";
    private static final String BIOME_FORMAT_VERSION = "1.21.60";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile Path cachedPackPath;
    private static volatile String cachedContentHash = "";
    private static volatile ResourcePackHolder cachedHolder;

    private CustomBiomeBehaviorPackManager() {
    }

    public static @Nullable Path createOrUpdateBehaviorPack() {
        try {
            if (!GeyserImpl.getInstance().config().gameplay().generateCustomBiomeResourcePack()) {
                return null;
            }
            String mode = GeyserImpl.getInstance().config().gameplay().customBiomePackMode();
            if (mode != null && "none".equalsIgnoreCase(mode.trim())) {
                return null;
            }
        } catch (Exception ignored) {
        }
        if (!CustomBiomeRegistry.get().isEnabled()) {
            return null;
        }

        Collection<CustomBiomeDefinition> biomes = CustomBiomeRegistry.get().customNetworkBiomes();
        if (biomes.isEmpty()) {
            return null;
        }
        // Ensure colormap climates exist so BP climate matches network sampling.
        CustomBiomeResourcePackManager.prepareExactColormap();

        Path cacheDir = cacheDir();
        if (cacheDir == null) {
            return null;
        }
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            GeyserImpl.getInstance().getLogger().error("Unable to create custom biomes cache directory", e);
            return null;
        }

        String contentHash = computeContentHash(biomes);
        Path packPath = cacheDir.resolve("custom_biomes_bp.mcpack");
        Path hashMarker = cacheDir.resolve("custom_biomes_bp.hash");

        if (Files.isRegularFile(packPath) && contentHash.equals(cachedContentHash)
                && !CustomBiomeRegistry.get().isPackDirty()) {
            cachedPackPath = packPath;
            return packPath;
        }
        try {
            if (Files.isRegularFile(packPath) && Files.isRegularFile(hashMarker)) {
                String diskHash = Files.readString(hashMarker, StandardCharsets.UTF_8).trim();
                if (contentHash.equals(diskHash) && !CustomBiomeRegistry.get().isPackDirty()) {
                    cachedContentHash = contentHash;
                    cachedPackPath = packPath;
                    return packPath;
                }
            }
        } catch (IOException ignored) {
        }

        GeyserImpl.getInstance().getLogger().info(
                "Creating custom biome behavior pack (" + biomes.size() + " custom biomes)...");
        try {
            Files.deleteIfExists(packPath);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(packPath))) {
                writeManifest(zos, contentHash);
                for (CustomBiomeDefinition def : biomes) {
                    writeBiomeDefinition(zos, def);
                }
            }
            Files.writeString(hashMarker, contentHash, StandardCharsets.UTF_8);
            cachedContentHash = contentHash;
            cachedPackPath = packPath;
            cachedHolder = null;
            GeyserImpl.getInstance().getLogger().info(
                    "Finished creating custom biome behavior pack (" + biomes.size() + " biomes).");
            return packPath;
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Unable to create custom biome behavior pack", e);
            return null;
        }
    }

    public static @Nullable ResourcePackHolder getOrLoadHolder() {
        Path path = createOrUpdateBehaviorPack();
        if (path == null) {
            return null;
        }
        ResourcePackHolder existing = cachedHolder;
        if (existing != null && path.equals(cachedPackPath)) {
            return existing;
        }
        try {
            GeyserResourcePack pack = ResourcePackLoader.readPack(path).build();
            cachedHolder = ResourcePackHolder.of(pack);
            return cachedHolder;
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Failed to load custom biome behavior pack", e);
            return null;
        }
    }

    public static ResourcePacksInfoPacket.Entry infoPacketEntry() {
        ResourcePackHolder holder = getOrLoadHolder();
        if (holder == null) {
            return null;
        }
        var header = holder.pack().manifest().header();
        return new ResourcePacksInfoPacket.Entry(
                header.uuid(),
                header.version().toString(),
                holder.codec().size(),
                "",
                "",
                header.uuid().toString(),
                false,
                false,
                false,
                ""
        );
    }

    public static ResourcePackStackPacket.Entry stackPacketEntry() {
        ResourcePackHolder holder = getOrLoadHolder();
        if (holder == null) {
            return null;
        }
        var header = holder.pack().manifest().header();
        return new ResourcePackStackPacket.Entry(
                header.uuid().toString(),
                header.version().toString(),
                ""
        );
    }

    public static boolean isOurPack(UUID uuid) {
        return PACK_UUID.equals(uuid);
    }

    private static void writeManifest(ZipOutputStream zos, String contentHash) throws IOException {
        int versionPatch = Math.floorMod(contentHash.hashCode(), 100000);
        String manifest = """
                {
                  "format_version": 2,
                  "header": {
                    "name": "%s",
                    "description": "%s",
                    "uuid": "%s",
                    "version": [1, 0, %d],
                    "min_engine_version": [1, 21, 60]
                  },
                  "modules": [
                    {
                      "type": "data",
                      "description": "%s",
                      "uuid": "%s",
                      "version": [1, 0, %d]
                    }
                  ]
                }
                """.formatted(
                PACK_NAME, PACK_DESCRIPTION, PACK_UUID, versionPatch,
                PACK_DESCRIPTION, MODULE_UUID, versionPatch
        );
        putString(zos, "manifest.json", manifest);
    }

    private static void writeBiomeDefinition(ZipOutputStream zos, CustomBiomeDefinition def) throws IOException {
        String identifier = def.bedrockIdentifier();
        String fileName = "biomes/" + identifier.replace(':', '_') + ".json";

        // Minimal definition — climate matches ExactBiomeColormap so BP + network + textures agree.
        float temperature = def.temperature();
        float downfall = def.downfall();
        ExactBiomeColormap.Climate exact =
                CustomBiomeRegistry.get().exactColormap().climateFor(def.bedrockIdentifier());
        if (exact != null) {
            temperature = exact.temperature();
            downfall = exact.downfall();
        }
        JsonObject climate = new JsonObject();
        climate.addProperty("temperature", temperature);
        climate.addProperty("downfall", downfall);

        JsonArray tags = new JsonArray();
        tags.add("overworld");
        tags.add("custom");
        tags.add("no_legacy_worldgen");

        JsonObject tagsObj = new JsonObject();
        tagsObj.add("tags", tags);

        JsonObject components = new JsonObject();
        components.add("minecraft:climate", climate);
        components.add("minecraft:tags", tagsObj);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);

        JsonObject biome = new JsonObject();
        biome.add("description", description);
        biome.add("components", components);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", BIOME_FORMAT_VERSION);
        root.add("minecraft:biome", biome);

        putString(zos, fileName, GSON.toJson(root));
    }

    private static void putString(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String computeContentHash(Collection<CustomBiomeDefinition> biomes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(BIOME_FORMAT_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update("bp-v4-colormap-climate".getBytes(StandardCharsets.UTF_8));
            for (CustomBiomeDefinition def : biomes) {
                digest.update(def.bedrockIdentifier().getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.bedrockId()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.temperature()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.downfall()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.grassColor()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return Integer.toHexString(biomes.hashCode());
        }
    }

    private static @Nullable Path cacheDir() {
        try {
            return GeyserImpl.getInstance().getBootstrap().getConfigFolder()
                    .resolve("cache")
                    .resolve("custom_biomes");
        } catch (Exception e) {
            return null;
        }
    }
}
