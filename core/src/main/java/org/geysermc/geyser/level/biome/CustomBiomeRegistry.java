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
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitionData;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitions;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.cache.registry.RegistryEntryContext;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks custom / overridden biomes for Bedrock color packs.
 * <p>
 * <b>Source:</b> JSON files in {@code custom_mappings/} under the {@code "biomes"} key only
 * (same pattern as custom items). No Paper registry or datapack auto-scan.
 * <p>
 * <b>Vanilla-safe:</b> {@code minecraft:*} biomes keep their normal Bedrock network IDs unless a
 * mapping explicitly recolors them. Mapped non-vanilla biomes get custom network IDs
 * ({@link #CUSTOM_BIOME_ID_START}+), a generated behavior pack, exact water via
 * {@code mapWaterColor}, and exact grass/foliage/dry-foliage via reserved colormap pixels.
 */
public final class CustomBiomeRegistry {
    public static final int CUSTOM_BIOME_ID_START = 30_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ID_MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();

    private static final CustomBiomeRegistry INSTANCE = new CustomBiomeRegistry();

    private final Map<String, CustomBiomeDefinition> definitions = new ConcurrentHashMap<>();
    /** Biomes defined by custom_mappings (authoritative colors). */
    private final Map<String, BiomeMappingEntry> mappingEntries = new ConcurrentHashMap<>();
    private final Object2IntMap<String> idMap = new Object2IntOpenHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(CUSTOM_BIOME_ID_START);
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean packDirty = new AtomicBoolean(false);
    /** Ensures we only schedule one debounced BP/RP rebuild while the Java registry floods entries. */
    private final AtomicBoolean packRefreshScheduled = new AtomicBoolean(false);
    private volatile boolean enabled = true;
    /** Exact grass/foliage via colormap pixels for custom network biomes (vanilla-safe base image). */
    private final ExactBiomeColormap exactColormap = new ExactBiomeColormap();

    private CustomBiomeRegistry() {
        idMap.defaultReturnValue(-1);
    }

    public ExactBiomeColormap exactColormap() {
        return exactColormap;
    }

    public static CustomBiomeRegistry get() {
        return INSTANCE;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void loadPersistedIds() {
        Path path = idMapPath();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Integer> loaded = GSON.fromJson(reader, ID_MAP_TYPE);
            if (loaded == null) {
                return;
            }
            synchronized (idMap) {
                for (Map.Entry<String, Integer> entry : loaded.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    int id = entry.getValue();
                    if (id < CUSTOM_BIOME_ID_START) {
                        continue;
                    }
                    idMap.put(entry.getKey(), id);
                    nextId.updateAndGet(current -> Math.max(current, id + 1));
                }
            }
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().warning("Failed to load custom biome ID map: " + e.getMessage());
        }
    }

    /**
     * Load biome color mappings from {@code custom_mappings/*.json} (same directory as custom items).
     * Mapping files are the primary way to supply exact grass/foliage/water/dry-foliage colors.
     */
    public void loadFromCustomMappings() {
        if (!enabled) {
            return;
        }
        mappingEntries.clear();
        org.geysermc.geyser.registry.mappings.MappingsConfigReader.loadCustomMappingsFromJson(
                org.geysermc.geyser.registry.mappings.MappingsType.BIOMES,
                (javaId, entry) -> {
                    mappingEntries.put(javaId, entry);
                    applyMappingEntry(entry);
                });
        if (!mappingEntries.isEmpty()) {
            packDirty.set(true);
            GeyserImpl.getInstance().getLogger().info(
                    "Custom biomes: loaded " + mappingEntries.size()
                            + " biome mapping(s) from custom_mappings/");
        }
    }

    /**
     * Apply one mapping entry into the live definition table (used at load and when resolving).
     */
    public void applyMappingEntry(BiomeMappingEntry entry) {
        if (!enabled || entry == null) {
            return;
        }
        String id = entry.javaIdentifier();
        int vanillaId = Registries.BIOME_IDENTIFIERS.get().getOrDefault(id, -1);
        boolean customNetwork = vanillaId < 0;
        int networkId;
        if (customNetwork) {
            if (entry.preferredBedrockId() != null && entry.preferredBedrockId() >= CUSTOM_BIOME_ID_START) {
                synchronized (idMap) {
                    idMap.put(id, entry.preferredBedrockId());
                    nextId.updateAndGet(c -> Math.max(c, entry.preferredBedrockId() + 1));
                    dirty.set(true);
                }
                networkId = entry.preferredBedrockId();
            } else {
                networkId = allocateCustomId(id);
            }
        } else {
            networkId = vanillaId;
        }

        Integer grass = entry.grassColor();
        Integer foliage = entry.foliageColor();
        if (customNetwork) {
            if (grass == null) {
                grass = JavaBiomeEffectsParser.approximateGrassColor(entry.temperature(), entry.downfall());
            }
            if (foliage == null) {
                foliage = JavaBiomeEffectsParser.approximateFoliageColor(entry.temperature(), entry.downfall());
            }
        } else if (!entry.hasAnyColor()) {
            // Vanilla with empty mapping — nothing to do
            return;
        }

        String bedrockIdentifier = customNetwork
                ? CustomBiomeDefinition.toBedrockIdentifier(id)
                : id;
        registerOrUpdate(new CustomBiomeDefinition(
                id,
                bedrockIdentifier,
                networkId,
                customNetwork,
                entry.temperature(),
                entry.downfall(),
                entry.rain(),
                grass,
                foliage,
                entry.dryFoliageColor(),
                entry.waterColor(),
                entry.skyColor(),
                entry.fogColor(),
                entry.tags()
        ));
    }

    public boolean hasMapping(String javaIdentifier) {
        return javaIdentifier != null && mappingEntries.containsKey(javaIdentifier);
    }

    public @Nullable BiomeMappingEntry mapping(String javaIdentifier) {
        return javaIdentifier == null ? null : mappingEntries.get(javaIdentifier);
    }

    public void savePersistedIds() {
        if (!dirty.get()) {
            return;
        }
        Path path = idMapPath();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Map<String, Integer> snapshot;
            synchronized (idMap) {
                snapshot = new LinkedHashMap<>();
                for (Object2IntMap.Entry<String> entry : idMap.object2IntEntrySet()) {
                    snapshot.put(entry.getKey(), entry.getIntValue());
                }
            }
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(snapshot, writer);
            }
            dirty.set(false);
        } catch (IOException e) {
            GeyserImpl.getInstance().getLogger().warning("Failed to save custom biome ID map: " + e.getMessage());
        }
    }

    /**
     * Resolve the Bedrock biome ID for a Java registry entry.
     * <p>
     * Colors and pack entries come only from {@code custom_mappings}. Unmapped vanilla biomes keep
     * stock looks. Unmapped custom biomes get a stable network ID so chunks render, but no colors
     * until a mapping file includes them.
     */
    public int resolveBedrockId(RegistryEntryContext entry) {
        String javaIdentifier = entry.id().asString();
        int vanillaId = Registries.BIOME_IDENTIFIERS.get().getOrDefault(javaIdentifier, -1);

        if (!enabled) {
            return vanillaId >= 0 ? vanillaId : 0;
        }

        BiomeMappingEntry mapped = mappingEntries.get(javaIdentifier);
        if (mapped != null) {
            applyMappingEntry(mapped);
            CustomBiomeDefinition def = definitions.get(javaIdentifier);
            return def != null ? def.bedrockId() : (vanillaId >= 0 ? vanillaId : allocateCustomId(javaIdentifier));
        }

        if (vanillaId >= 0) {
            return vanillaId;
        }

        // Unmapped custom biome: network ID only (no auto colors from the Java registry).
        return allocateCustomId(javaIdentifier);
    }

    private void registerOrUpdate(CustomBiomeDefinition definition) {
        CustomBiomeDefinition previous = definitions.put(definition.javaIdentifier(), definition);
        if (!definition.equals(previous)) {
            packDirty.set(true);
        }
    }

    /**
     * Stable custom network ID (&gt;= {@link #CUSTOM_BIOME_ID_START}) for a Java biome identifier.
     * Entries below {@link #CUSTOM_BIOME_ID_START} in {@code id_map.json} are ignored and reallocated.
     */
    private int allocateCustomId(String javaIdentifier) {
        synchronized (idMap) {
            int existing = idMap.getInt(javaIdentifier);
            if (existing >= CUSTOM_BIOME_ID_START) {
                return existing;
            }
            int id = nextId.getAndIncrement();
            idMap.put(javaIdentifier, id);
            dirty.set(true);
            return id;
        }
    }

    public Collection<CustomBiomeDefinition> definitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public Collection<CustomBiomeDefinition> customNetworkBiomes() {
        return definitions.values().stream().filter(CustomBiomeDefinition::customNetworkId).toList();
    }

    public Collection<CustomBiomeDefinition> clientColorBiomes() {
        return definitions.values().stream().filter(CustomBiomeDefinition::hasClientColors).toList();
    }

    public boolean isPackDirty() {
        return packDirty.get();
    }

    public void clearPackDirty() {
        packDirty.set(false);
    }

    /**
     * Schedule a single debounced rebuild of the custom biome BP/RP after the Java registry settles.
     * Safe to call from the hot path of every registry entry — does not block the player thread.
     */
    public void schedulePackRefresh() {
        if (!packRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            GeyserImpl.getInstance().getScheduledThread().schedule(() -> {
                packRefreshScheduled.set(false);
                try {
                    savePersistedIds();
                    if (!enabled) {
                        return;
                    }
                    if (!GeyserImpl.getInstance().config().gameplay().generateCustomBiomeResourcePack()) {
                        return;
                    }
                    // Only rebuild if still dirty (new biomes since last pack write)
                    if (isPackDirty()) {
                        CustomBiomeResourcePackManager.refreshGlobalRegistration();
                    }
                } catch (Exception e) {
                    GeyserImpl.getInstance().getLogger().debug(
                            "Deferred custom biome pack refresh failed: " + e.getMessage());
                }
            }, 2, TimeUnit.SECONDS);
        } catch (Exception e) {
            packRefreshScheduled.set(false);
            // Fallback: save IDs only; packs rebuild on next Bedrock login
            savePersistedIds();
        }
    }

    /**
     * Build BiomeDefinitionList: stock vanilla defs + custom network biomes + exact water
     * patches for vanilla biomes that Java overrode. Grass/foliage exact colors live in the RP.
     */
    public BiomeDefinitions buildBiomeDefinitions() {
        Map<String, BiomeDefinitionData> map = new LinkedHashMap<>(Registries.BIOMES.get().getDefinitions());
        for (CustomBiomeDefinition def : definitions.values()) {
            if (def.customNetworkId()) {
                // Terralith / datapack biomes — new named entries with custom network IDs
                map.put(def.bedrockIdentifier(), toBiomeDefinitionData(def));
                continue;
            }
            if (!def.hasClientColors()) {
                continue;
            }
            // Vanilla biome with Java color overrides — keep vanilla network id (null),
            // only replace water via mapWaterColor; grass/foliage stay RP client_biomes.
            BiomeDefinitionData existing = map.get(def.bedrockIdentifier());
            if (existing != null) {
                map.put(def.bedrockIdentifier(), patchVanillaWater(existing, def));
            } else {
                // Key might be un-namespaced in some mapping sets
                String shortId = stripMinecraftNamespace(def.bedrockIdentifier());
                existing = map.get(shortId);
                if (existing != null) {
                    map.put(shortId, patchVanillaWater(existing, def));
                }
            }
        }
        return new BiomeDefinitions(map);
    }

    private static String stripMinecraftNamespace(String id) {
        if (id != null && id.startsWith("minecraft:")) {
            return id.substring("minecraft:".length());
        }
        return id;
    }

    /**
     * Custom network biomes: exact water via mapWaterColor; exact grass/foliage via climate
     * pointing at reserved colormap pixels (see {@link ExactBiomeColormap}).
     */
    public static BiomeDefinitionData toBiomeDefinitionData(CustomBiomeDefinition def) {
        List<String> tags = def.tags().isEmpty()
                ? List.of("overworld", "custom")
                : def.tags();
        float temperature = def.temperature();
        float downfall = def.downfall();
        if (def.customNetworkId()) {
            ExactBiomeColormap.Climate climate =
                    CustomBiomeRegistry.get().exactColormap().climateFor(def.bedrockIdentifier());
            if (climate != null) {
                temperature = climate.temperature();
                downfall = climate.downfall();
            }
        }
        return new BiomeDefinitionData(
                def.customNetworkId() ? def.bedrockId() : null,
                temperature,
                downfall,
                0f,
                0f,
                0f,
                0f,
                0f,
                0.1f,
                0.2f,
                def.mapWaterColor(),
                def.rain(),
                tags,
                null
        );
    }

    /** Preserve vanilla climate/tags; only apply Java water color when present. */
    private static BiomeDefinitionData patchVanillaWater(BiomeDefinitionData existing, CustomBiomeDefinition def) {
        if (def.waterColor() == null) {
            return existing;
        }
        return new BiomeDefinitionData(
                existing.getId(),
                existing.getTemperature(),
                existing.getDownfall(),
                existing.getRedSporeDensity(),
                existing.getBlueSporeDensity(),
                existing.getAshDensity(),
                existing.getWhiteAshDensity(),
                existing.getFoliageSnow(),
                existing.getDepth(),
                existing.getScale(),
                def.mapWaterColor(),
                existing.isRain(),
                existing.getTags(),
                existing.getChunkGenData()
        );
    }

    private static Path idMapPath() {
        try {
            return GeyserImpl.getInstance().getBootstrap().getConfigFolder()
                    .resolve("cache")
                    .resolve("custom_biomes")
                    .resolve("id_map.json");
        } catch (Exception e) {
            return null;
        }
    }
}
