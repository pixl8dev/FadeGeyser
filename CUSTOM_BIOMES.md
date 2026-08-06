# Custom Biome Colors (FadeGeyser)

Exact **grass**, **foliage**, and **water** colors from Java datapacks (e.g. **Terralith**) for Bedrock clients — **without breaking a vanilla dimension**.

## Design rules

| Rule | Why |
|------|-----|
| Only non-`minecraft:` biomes get custom network IDs (≥30000) | Vanilla dimension keeps normal Bedrock biome IDs |
| Custom biomes never reuse vanilla network IDs | Would recolor real vanilla biomes |
| Colormap base stays stock vanilla; exact colors only in a reserved band | Vanilla climates still sample normal pixels |
| Exact hex from Java registry / datapack | Matches Java client |
| Behavior pack registers custom biome identifiers | Client needs a real biome identity for custom IDs |

Vanilla biomes are only recolored if Java actually overrode their effects (same as Java singleplayer).

## Pipeline

```
Java registry / Terralith.zip
        │
        ├─ minecraft:*  → vanilla Bedrock ID (+ optional RP color if Java overrode effects)
        │                 vanilla climate → vanilla pixels on stock colormap
        │
        └─ terralith:*  → custom ID 30000+
                           ├─ Behavior pack biomes/*.json   (register identifier)
                           ├─ Resource pack textures/colormap/grass.png + foliage.png
                           │     (exact Java RGB at reserved pixels; vanilla base preserved)
                           ├─ BiomeDefinitionList climate → those pixels + exact mapWaterColor
                           └─ client_biomes/*.json          (exact hex backup)
```

**Why colormaps?** Protocol has no grass RGB field (only water). Multiplayer often ignores
`grass_appearance` for custom network IDs even when a BP is applied. Sampling exact pixels via
network temperature/downfall matches Java hex. Vanilla dimension biomes keep stock climates →
stock colormap pixels → normal vanilla look.

Same identifier string in BP, RP, and definition list (e.g. `terralith:moonlight_grove`).

## Config

```yaml
gameplay:
  enable-custom-biomes: true
  generate-custom-biome-resource-pack: true
  custom-biome-pack-mode: full   # full | water | none
  custom-biome-datapack-paths:
    - /path/to/Terralith.zip
```

Pre-warm from the datapack so the first Bedrock join already has colors (packs negotiate before the Java registry arrives).

## Packs

Generated under `cache/custom_biomes/`:

- `custom_biomes_bp.mcpack` — behavior pack (custom biomes only)
- `custom_biomes.mcpack` — resource pack (exact colors)
- `id_map.json` — stable Java → custom network ID

User files in `packs/` are never removed. Color RP is high priority.

After updates, delete `cache/custom_biomes/` and rejoin so clients re-download (UUID/version bumps also force this).

## Two dimensions

| Dimension | Biomes | Bedrock |
|-----------|--------|---------|
| Vanilla | `minecraft:*` | Default IDs and looks |
| Terralith | `terralith:*` | Custom IDs + BP/RP exact colors |

## Disable

```yaml
enable-custom-biomes: false
```
