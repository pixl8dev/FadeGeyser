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
import com.google.gson.JsonObject;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.pack.option.PriorityOption;
import org.geysermc.geyser.event.type.GeyserDefineResourcePacksEventImpl;
import org.geysermc.geyser.event.type.SessionLoadResourcePacksEventImpl;
import org.geysermc.geyser.pack.GeyserResourcePack;
import org.geysermc.geyser.pack.ResourcePackHolder;
import org.geysermc.geyser.pack.option.OptionHolder;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.loader.ResourcePackLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a Bedrock resource pack with <strong>exact</strong> Java grass/foliage/water hex colors.
 * <p>
 * Additive only (never replaces {@code packs/}). Includes vanilla-base colormaps with reserved
 * exact-color pixels for custom biomes, plus {@code client_biomes} entries. Vanilla biomes are
 * only recolored when Java actually overrode their effects.
 */
public final class CustomBiomeResourcePackManager {
    /**
     * v9: exact grass/foliage via textures/colormap + climate (client_biomes grass often ignored for custom IDs).
     */
    public static final UUID PACK_UUID = UUID.fromString("c0a1b2c3-d4e5-6789-abcd-ef012345678b");

    private static final String PACK_NAME = "Geyser Custom Biomes";
    private static final String PACK_DESCRIPTION = "Exact per-biome grass/foliage/water colors from Java datapacks";
    private static final String MODULE_UUID = "d1b2c3d4-e5f6-7890-bcde-f01234567892";
    private static final String CLIENT_BIOME_FORMAT_VERSION = "1.21.60";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile Path cachedPackPath;
    private static volatile String cachedContentHash = "";

    private CustomBiomeResourcePackManager() {
    }

    public static void createOrUpdatePacks() {
        // Colormap climates must exist before BP (BP climate mirrors them) and RP (embeds PNGs).
        prepareExactColormap();
        CustomBiomeBehaviorPackManager.createOrUpdateBehaviorPack();
        createOrUpdateResourcePack();
    }

    /**
     * Build pixel-exact grass/foliage maps + climate table for custom network biomes.
     * Safe to call repeatedly; skips when disabled or water-only mode.
     */
    public static void prepareExactColormap() {
        try {
            if (!CustomBiomeRegistry.get().isEnabled()) {
                return;
            }
            if (!GeyserImpl.getInstance().config().gameplay().generateCustomBiomeResourcePack()) {
                return;
            }
            String mode = GeyserImpl.getInstance().config().gameplay().customBiomePackMode();
            if (mode != null && ("none".equalsIgnoreCase(mode.trim()) || "water".equalsIgnoreCase(mode.trim()))) {
                return;
            }
            Collection<CustomBiomeDefinition> biomes = CustomBiomeRegistry.get().clientColorBiomes();
            if (biomes.isEmpty()) {
                return;
            }
            CustomBiomeRegistry.get().exactColormap().rebuild(biomes);
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Unable to prepare exact biome colormaps", e);
        }
    }

    public static @Nullable Path createOrUpdateResourcePack() {
        String mode = "full";
        try {
            if (!GeyserImpl.getInstance().config().gameplay().generateCustomBiomeResourcePack()) {
                return null;
            }
            mode = GeyserImpl.getInstance().config().gameplay().customBiomePackMode();
            if (mode == null || mode.isBlank()) {
                mode = "full";
            }
            mode = mode.trim().toLowerCase();
            if ("none".equals(mode)) {
                return null;
            }
        } catch (Exception ignored) {
        }
        if (!CustomBiomeRegistry.get().isEnabled()) {
            return null;
        }

        Collection<CustomBiomeDefinition> biomes = CustomBiomeRegistry.get().clientColorBiomes();
        if (biomes.isEmpty()) {
            return null;
        }

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

        final String packMode = mode;
        String contentHash = computeContentHash(biomes) + ":" + packMode + ":exact-v9-colormap";
        Path packPath = cacheDir.resolve("custom_biomes.mcpack");
        Path hashMarker = cacheDir.resolve("custom_biomes.hash");

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

        boolean waterOnly = "water".equals(packMode);
        Map<String, CustomBiomeDefinition> byBedrockId = new LinkedHashMap<>();
        for (CustomBiomeDefinition def : biomes) {
            byBedrockId.putIfAbsent(def.bedrockIdentifier(), def);
        }
        long customCount = biomes.stream().filter(CustomBiomeDefinition::customNetworkId).count();
        long vanillaOverrides = byBedrockId.size() - customCount;

        ExactBiomeColormap colormap = CustomBiomeRegistry.get().exactColormap();
        if (!waterOnly && !colormap.hasData()) {
            try {
                colormap.rebuild(byBedrockId.values());
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().error("Unable to build exact biome colormaps", e);
            }
        }

        GeyserImpl.getInstance().getLogger().info(
                "Creating custom biome color resource pack (" + byBedrockId.size()
                        + " client_biomes: " + customCount + " custom + "
                        + Math.max(0, vanillaOverrides) + " vanilla overrides, mode=" + packMode
                        + ", colormapSlots=" + (colormap.hasData() ? customCount : 0) + ")...");
        try {
            Files.deleteIfExists(packPath);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(packPath))) {
                writeManifest(zos, contentHash);
                writeBiomesClientJson(zos, byBedrockId.values());
                if (!waterOnly && colormap.hasData()) {
                    putBytes(zos, "textures/colormap/grass.png", colormap.grassPng());
                    putBytes(zos, "textures/colormap/foliage.png", colormap.foliagePng());
                }
                for (CustomBiomeDefinition def : byBedrockId.values()) {
                    writeClientBiomeFiles(zos, def, waterOnly);
                }
            }
            Files.writeString(hashMarker, contentHash, StandardCharsets.UTF_8);
            cachedContentHash = contentHash;
            cachedPackPath = packPath;
            CustomBiomeRegistry.get().clearPackDirty();
            GeyserImpl.getInstance().getLogger().info(
                    "Finished creating custom biome color resource pack (mode=" + packMode + ").");
            return packPath;
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Unable to create custom biome resource pack", e);
            return null;
        }
    }

    public static void registerWithDefineEvent(GeyserDefineResourcePacksEventImpl event) {
        Path packPath = createOrUpdateResourcePack();
        if (packPath == null) {
            return;
        }
        try {
            GeyserResourcePack pack = ResourcePackLoader.readPack(packPath).build();
            try {
                event.unregister(PACK_UUID);
            } catch (Exception ignored) {
            }
            event.register(pack, PriorityOption.HIGH);
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Failed to register custom biome resource pack", e);
        }
    }

    public static void registerWithSessionEvent(SessionLoadResourcePacksEventImpl event) {
        Path packPath = createOrUpdateResourcePack();
        if (packPath == null) {
            return;
        }
        try {
            GeyserResourcePack pack = ResourcePackLoader.readPack(packPath).build();
            if (event.resourcePacks().stream().anyMatch(p -> PACK_UUID.equals(p.uuid()))) {
                return;
            }
            event.register(pack, PriorityOption.HIGH);
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().debug("Custom biome pack session register: " + e.getMessage());
        }
    }

    public static void refreshGlobalRegistration() {
        Path packPath = createOrUpdateResourcePack(); // rebuilds colormap if needed, then RP; BP via prepare in BP call
        CustomBiomeBehaviorPackManager.createOrUpdateBehaviorPack();
        if (packPath == null || !Registries.RESOURCE_PACKS.loaded()) {
            return;
        }
        try {
            GeyserResourcePack pack = ResourcePackLoader.readPack(packPath).build();
            Registries.RESOURCE_PACKS.get().put(pack.uuid(),
                    new ResourcePackHolder(pack, new OptionHolder(PriorityOption.HIGH)));
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().debug(
                    "Failed to refresh custom biome pack in global registry: " + e.getMessage());
        }
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
                      "type": "resources",
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

    private static void writeBiomesClientJson(ZipOutputStream zos, Collection<CustomBiomeDefinition> biomes)
            throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> biomesMap = new LinkedHashMap<>();
        for (CustomBiomeDefinition def : biomes) {
            if (def.waterColor() == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("water_surface_color", CustomBiomeDefinition.toHex(def.waterColor()));
            biomesMap.put(def.bedrockIdentifier(), entry);
        }
        root.put("biomes", biomesMap);
        putString(zos, "biomes_client.json", GSON.toJson(root));
    }

    /**
     * One file per biome under {@code client_biomes/} only.
     * Triple paths + short minecraft: aliases + dry_foliage caused Bedrock "Block" on iOS.
     * Identifier matches BP / BiomeDefinitionList exactly for custom biomes.
     */
    private static void writeClientBiomeFiles(ZipOutputStream zos, CustomBiomeDefinition def, boolean waterOnly)
            throws IOException {
        String identifier = def.bedrockIdentifier();
        String fileStem = identifier.replace(':', '_');
        String json = buildClientBiomeJson(identifier, def, waterOnly);
        if (json == null) {
            return;
        }
        putString(zos, "client_biomes/" + fileStem + ".json", json);
    }

    private static @Nullable String buildClientBiomeJson(String identifier, CustomBiomeDefinition def,
                                                          boolean waterOnly) {
        JsonObject components = new JsonObject();
        // Exact Java hex — Microsoft mesa sample: "color": "#90814d"
        // Only grass + foliage + water (dry_foliage observed to Block some clients).
        if (!waterOnly) {
            if (def.grassColor() != null) {
                JsonObject grass = new JsonObject();
                grass.addProperty("color", CustomBiomeDefinition.toHex(def.grassColor()));
                components.add("minecraft:grass_appearance", grass);
            }
            if (def.foliageColor() != null) {
                JsonObject foliage = new JsonObject();
                foliage.addProperty("color", CustomBiomeDefinition.toHex(def.foliageColor()));
                components.add("minecraft:foliage_appearance", foliage);
            }
        }
        if (def.waterColor() != null) {
            JsonObject water = new JsonObject();
            water.addProperty("surface_color", CustomBiomeDefinition.toHex(def.waterColor()));
            components.add("minecraft:water_appearance", water);
        }
        if (components.size() == 0) {
            return null;
        }

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);

        JsonObject clientBiome = new JsonObject();
        clientBiome.add("description", description);
        clientBiome.add("components", components);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", CLIENT_BIOME_FORMAT_VERSION);
        root.add("minecraft:client_biome", clientBiome);
        return GSON.toJson(root);
    }

    private static void putString(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void putBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static String computeContentHash(Collection<CustomBiomeDefinition> biomes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CLIENT_BIOME_FORMAT_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update("exact-v9-colormap".getBytes(StandardCharsets.UTF_8));
            for (CustomBiomeDefinition def : biomes) {
                digest.update(def.bedrockIdentifier().getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.grassColor()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.foliageColor()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.waterColor()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.bedrockId()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(def.customNetworkId()).getBytes(StandardCharsets.UTF_8));
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
