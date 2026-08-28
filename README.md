<!--
SPDX-FileCopyrightText: 2026 AcideFluorhydrique
SPDX-License-Identifier: GPL-3.0-or-later
-->

# MapConquer

A hex-grid turn-based grand-strategy game for Android. Free software, no ads, no
trackers, and **no Android permissions at all** — it never touches the network,
external storage or your location.

Two modes:

- **Campaign** — five training exercises, each built to teach one thing: an
  amphibious crossing, a supply line stretched across a desert, an island chain
  that has to be taken by sea and air, a defensive battle you win by not losing,
  and a mountain offensive where your vehicles cannot follow you.
- **Conquest** — the whole world map, a hundred-odd nations, everyone at peace on
  turn one. Pick anyone and take 80% of the planet.

Traditional Chinese, Simplified Chinese and English, switchable inside the app.

## What the game actually models

| System | Summary |
| --- | --- |
| Grid | Pointy-top hexes, axial maths, odd-r storage. 96×60 for the world map. |
| Terrain | 13 types, each with a movement cost, a defence bonus and an income. Vehicles cannot enter mountains, jungle or swamp. |
| Territory | Ownership is per **province**, not per hex. Take the province capital with a land unit and the whole province changes hands — income, supply and factories with it. |
| Units | 20 kinds across land, sea and air. Each has four separate attack values — against infantry, armour, ships and aircraft — so the counter matters more than the raw number. |
| Combat | Ratio-based (`55·A/(A+D)`), bounded by construction, ±8% deterministic variance. Artillery fires from range and takes no return fire; it is nearly helpless in melee. |
| Zones of control | Moving next to an enemy ends your move. Armour ignores this once per move, which is what makes it the tool for opening a breach. |
| Supply | Spreads from your own cities along your own territory, priced in movement cost — so mountains break a supply line faster than plains do. Out of supply means losing strength, then dying. |
| Experience | Units level 1→5 from damage dealt and kills. |
| Commanders | 16 fictional commanders with stacking skills, unlocked with medals earned from campaign stars, usable in every later game. |
| Research | Six branches, five levels each, +8% per level. |
| Diplomacy | War / peace / alliance, with truce cooldowns so the AI cannot flip-flop. |
| AI | Resource-based difficulty only — the AI plays by exactly the same combat rules you do. It runs as a resumable state machine so a hundred nations can move without freezing a frame. |

## Building

There is nothing to install beyond a JDK — Gradle fetches the rest.

```bash
./gradlew assembleDebug
```

Run the test suite (this is worth doing; see below):

```bash
./gradlew test
```

## Tests

The entire simulation layer — hex maths, pathfinding, map and scenario parsing,
combat, the turn engine and the AI — has no dependency on any Android API. That is
a deliberate architectural constraint, and the payoff is that it can all be tested
on a plain JVM against the *real* shipped maps and scenarios rather than against
fixtures:

- `HexMathTest` — coordinate round-trips, ring/disc sizes, line continuity.
- `PathfinderTest` — Dijkstra ranges, cost-aware routing, zones of control, no
  state leaking between searches.
- `MapAssetTest` — every shipped map parses; neighbour relations are symmetric;
  provinces are contiguous, land-only and hold their own capital.
- `ScenarioAssetTest` — every scenario cross-checks against its map: no province
  claimed twice, no ship spawned on land, no campaign objective you already own.
- `CombatTest` — damage bounds, the rock-paper-scissors relationships, terrain and
  entrenchment, artillery's asymmetry.
- `ConquestSmokeTest` — loads the real world map with 100+ nations and plays
  several complete turns with the AI driving every side, asserting a set of state
  invariants after every turn.

## Generated content

The maps, the scenarios and all three `strings.xml` files are **generated**, and
the generators are in `tools/`:

```bash
python3 tools/genworld.py     # → app/src/main/assets/{maps,scenarios}
python3 tools/genstrings.py   # → app/src/main/res/values*/strings.xml
```

- `tools/geodata.py` — coastlines, mountain ranges, deserts, jungles and rivers as
  hand-drawn latitude/longitude polygons. These are original simplified outlines,
  not derived from OpenStreetMap, Natural Earth or any other dataset — at 3.75° per
  hex there is nothing a real dataset would buy, and keeping everything under one
  licence makes the F-Droid build trivially auditable. If you want real data, swap
  `LAND`/`SEA` for polygons read from a GeoJSON; the rasteriser below does not care.
- `tools/hexraster.py` — projects those polygons onto the hex grid and paints
  terrain by latitude, elevation and biome.
- `tools/places.py` — the single source of truth for provinces and nations: one row
  per city with its coordinates, city tier, owner and its name in all three
  languages. Both the map files and the translations come from this table, so they
  can never drift apart.
- `tools/scenarios.py` — the campaign missions.

CI re-runs both generators and fails if the committed output differs, so the
assets in the repository always match the tools that produced them.

## Design notes

A few decisions worth knowing before reading the code:

- **Everything is drawn on a `Canvas`.** One `Activity`, one `SurfaceView`, no
  Compose, no fragments, no view hierarchy. Screens are states of `GameView`.
- **No binary assets.** Unit symbols are drawn as vectors, the icon is a vector,
  and sound effects are synthesised at runtime. Nothing in the APK needs a
  provenance statement.
- **Determinism.** The RNG is a seeded xorshift whose state is part of the save
  file, so a battle plays out the same way on a reload.
- **Tile indices, not objects.** Pathfinding and AI walk hundreds of thousands of
  tiles per turn; nothing on those paths allocates.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

This is an independent, original work. It is not affiliated with, derived from or
endorsed by any other strategy game.
