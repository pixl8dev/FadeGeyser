# Custom Biome Colors (FadeGeyser)

Exact **grass**, **foliage**, **dry foliage** (leaf litter), and **water** colors for custom Java biomes on Bedrock — **without breaking a vanilla dimension**.

## Configure with mapping files

Colors come **only** from `custom_mappings/*.json` under the `"biomes"` key (same directory as custom items).

There is **no** Paper/datapack auto-scan. Generate mappings with Rainbow, a converter, or by hand.

→ Full format and workflow: **[CUSTOM_BIOME_MAPPINGS.md](CUSTOM_BIOME_MAPPINGS.md)**

```
plugins/Geyser-*/custom_mappings/my_biomes.json
```

## Design rules

| Rule | Why |
|------|-----|
| Only mapped non-`minecraft:` biomes get custom network IDs (≥30000) | Vanilla dimension keeps normal Bedrock biome IDs |
| Colors only from `custom_mappings` | Velocity-friendly; same workflow as custom items |
| Colormap base stays stock vanilla; exact colors only in a reserved band | Vanilla climates still sample normal pixels |
| Behavior pack registers mapped custom biome identifiers | Client needs a real biome identity for custom IDs |

## Pipeline

```
custom_mappings "biomes"
        │
        ├─ minecraft:*  → vanilla Bedrock ID (+ optional RP color if mapped)
        │
        └─ other:*      → custom ID 30000+
                           ├─ Behavior pack biomes/*.json
                           ├─ Resource pack colormaps (grass + foliage + dry_foliage)
                           ├─ BiomeDefinitionList climate + mapWaterColor
                           └─ client_biomes/*.json (hex backup)
```

**Why colormaps?** Protocol has no grass RGB field (only water). Sampling exact pixels via network temperature/downfall matches Java hex.

## Config

```yaml
gameplay:
  enable-custom-biomes: true
  generate-custom-biome-resource-pack: true
  custom-biome-pack-mode: full   # full | water | none
```

## Packs

Generated at runtime under `cache/custom_biomes/`:

- `custom_biomes_bp.mcpack` — behavior pack
- `custom_biomes.mcpack` — resource pack
- `id_map.json` — stable Java → custom network ID

User files in `packs/` are never removed.

## Disable

```yaml
enable-custom-biomes: false
```
