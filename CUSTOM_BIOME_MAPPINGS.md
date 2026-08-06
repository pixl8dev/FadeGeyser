# Custom Biome Mappings

Exact grass, foliage, dry foliage (leaf litter), and water colors for custom Java biomes on Bedrock — configured like **custom items**, not auto-scanned from Paper.

## How it works

```
Rainbow / converter / hand-written JSON
        │
        ▼
custom_mappings/*.json   ("biomes" key)
        │
        ▼
  CustomBiomeRegistry  →  Geyser generates BP + RP at runtime
        │
        ├─ minecraft:*  → normal Bedrock biome ID (+ optional color override if mapped)
        └─ other:*      → network ID ≥ 30000 + exact colors
```

| Source | Role |
|--------|------|
| **`custom_mappings/*.json`** | **Only** source of biome colors |
| Live Java/Paper registry | Allocates network IDs for unmapped custom biomes so chunks load; **does not** invent colors |
| Rainbow / pack converter | Tools that **generate** mapping files offline |

Drop mapping JSON next to item mappings:

```
plugins/Geyser-Spigot/   # or Geyser on Velocity/standalone
  custom_mappings/
    my_items.json
    my_biomes.json       # biome colors
  packs/                 # other Bedrock packs (untouched)
```

Restart Geyser after changing mappings. Generated color packs live under `cache/custom_biomes/`.

## File format

```json
{
  "format_version": 1,
  "biomes": {
    "terralith:moonlight_grove": {
      "temperature": 0.7,
      "downfall": 0.8,
      "rain": true,
      "grass_color": "#9eb2e1",
      "foliage_color": "#7cc895",
      "dry_foliage_color": "#7b5e3b",
      "water_color": "#92b2e1",
      "sky_color": "#78a7ff",
      "fog_color": "#c0d8ff",
      "tags": ["overworld"]
    },
    "minecraft:swamp": {
      "water_color": "#617b64",
      "grass_color": "#6a7039",
      "dry_foliage_color": "#7b5e3b"
    }
  }
}
```

### Rules

| Field | Required | Notes |
|-------|----------|--------|
| Object key | yes | Java biome id (`namespace:path`). Missing namespace → `minecraft:` |
| `temperature` | no | Default `0.5` |
| `downfall` | no | Default `0.5` |
| `rain` / `has_precipitation` | no | Default `true` |
| `grass_color` | no | Hex `#rrggbb`, `#rgb`, `0x…`, or decimal int |
| `foliage_color` | no | Tree leaves |
| `dry_foliage_color` | no | Leaf litter |
| `water_color` | no | Surface water |
| `sky_color` / `fog_color` | no | Optional |
| `tags` | no | e.g. `overworld` |
| `bedrock_id` | no | Preferred custom network id (≥ 30000) |

You can put **items**, **skulls**, **blocks**, **waypoint_styles**, and **biomes** in the same JSON file; Geyser only reads the keys it understands.

### Vanilla vs custom keys

| Java id | Bedrock network id | When to map |
|---------|-------------------|-------------|
| `minecraft:plains`, etc. | Stock vanilla id | Only if a datapack recolors that vanilla biome |
| `terralith:…`, `mynamespace:…` | Custom id ≥ 30000 | Always, for exact colors |

Unmapped custom biomes still get a network id (playable world) but **default** Bedrock looks until you add a mapping.

## Config

```yaml
gameplay:
  enable-custom-biomes: true
  generate-custom-biome-resource-pack: true
  custom-biome-pack-mode: full   # full | water | none
```

## Generating mappings

1. **Rainbow** — join a world with your datapacks, `/rainbow create <name>`, `/rainbow map biomes`, `/rainbow finish` → `custom_mappings/geyser_biome_mappings.json`
2. **Pack converter** — datapack zip → `geyser_biome_mappings.json`
3. Hand-write JSON from biome `effects` fields

## Runtime behavior

1. Geyser loads all `custom_mappings/**/*.json` and applies the `"biomes"` section.
2. Mapped non-`minecraft` biomes get BP registration + RP colormaps + water definitions.
3. Bedrock clients download the generated packs on join (additive with `packs/`).
4. Vanilla dimension biomes without mappings keep default Bedrock looks.

See also `CUSTOM_BIOMES.md` for the Bedrock color pipeline.
