<!--
SPDX-FileCopyrightText: 2026 AcideFluorhydrique
SPDX-License-Identifier: GPL-3.0-or-later
-->

# MapConquer

A hex-grid turn-based grand-strategy game for Android. Free software, no ads, no
trackers, and **no Android permissions at all** — it never touches the network,
external storage or your location.

Two modes:

- **Campaign** — three chapters (World War II in Europe, World War II in the
  Pacific, the Cold War), nine battles from Poland 1939 to the Central Front 1985.
  Every battle can be played from either side, and each side has its own goal:
  take the named cities, or hold them until the given turn. Faster wins earn more
  stars; stars pay for commanders you keep for every later game.
- **Conquest** — the whole world in 1939, 1943, 1950 or 1980, with the blocs of
  that year already drawn up. Take every city of every nation at war with you;
  allies and neutrals do not count. A nation that loses its last city surrenders:
  its remaining units disappear and its remaining land goes to whoever took that
  city.

You always move first in a round, and the AI moves after you.

Traditional Chinese, Simplified Chinese and English, switchable inside the app.

## What the game actually models

| System | Summary |
| --- | --- |
| Map | Pointy-top hexes, axial maths, odd-r storage. The world map is 120×76 and wraps east–west. It is deliberately stretched — Europe and East Asia wide, the oceans narrow — but the geography stays true: which countries share a border and which are separated by sea is checked every time the map is generated. |
| Terrain | 13 types, each with a movement cost, a defence bonus and an income. Vehicles cannot enter mountains, jungle or swamp. |
| Territory | Ownership is per **province**, not per hex; each province is one city and its land. A city falls when its defence is worn down and a land unit stands in it, and the whole province changes hands with it. Hong Kong, Gibraltar and Belfast are single-hex strongholds. |
| Units | 17 kinds: 11 land, 6 sea. Each has four attack values — against infantry, armour, ships and aircraft — so the counter matters more than the raw number. Units are built as formations of one to four. |
| Air power | Not units on the board but missions you pay for: fighter strikes, bomber strikes (which can also hit an empty city's walls) and airdrops, flown from cities and carriers (bombers need a large city). Anti-air, cruisers and destroyers blunt strikes near them. |
| Combat | Ratio-based (`55·A/(A+D)`), bounded by construction, ±8% deterministic variance. Artillery fires from range and takes no return fire; it is nearly helpless in melee. A unit inside a city takes half the damage, and the city's walls take a further 70% of the blow. |
| Zones of control | Moving next to an enemy ends your move. Armour ignores this once per move, which is what makes it the tool for opening a breach. |
| Supply | Spreads from your own cities along your own territory, priced in movement cost — so mountains break a supply line faster than plains do. Out of supply means losing strength, then dying. Supply trucks and headquarters carry a small supply bubble with them. |
| Experience | Units level 1→5 from damage dealt and kills. |
| Commanders | 16 fictional commanders with stacking skills, unlocked with medals earned from campaign stars, usable in every later game. |
| Research | Six branches, five levels each, +8% per level. |
| Diplomacy | Blocs go to war on their historical turn; you can declare war on a neutral, which makes it an enemy you must also defeat. Truce cooldowns stop the AI from flip-flopping. |
| AI | Resource-based difficulty only — the AI plays by exactly the same rules you do. It runs as a resumable state machine so a hundred nations can move without freezing a frame. |

## Reference behaviour

Some rules — victory, surrender, turn order — are modelled on the observable
behaviour of the classic games in this genre. What is known, what is still
uncertain and how it could be tested is written down in
[docs/original-behavior.md](docs/original-behavior.md). That document describes
behaviour only; no code, data or artwork from any other game is used in this
project.

## Building

There is nothing to install beyond JDK 17 — Gradle fetches the rest.

```bash
./gradlew assembleRelease
```

The result is an unsigned APK in `app/build/outputs/apk/release/`. Run the test
suite (this is worth doing; see below):

```bash
./gradlew test
```

## Releases and signing

Release APKs are signed with the project's own key, and the build is meant to be
**reproducible**: building the same commit from source must give a byte-identical
unsigned APK, so F-Droid can verify its own build against ours and ship the APK
we signed. Everyone then has the same signature and can move between F-Droid,
GitHub Releases and other stores without reinstalling.

CI (`.github/workflows/build.yml`) does this on every push:

1. builds the release APK with no signing configuration — the same artifact
   F-Droid builds;
2. signs it with `apksigner`, using the keystore in the `RELEASE_KEYSTORE`
   repository secret (base64) and the `RELEASE_KEYSTORE_PASSWORD`,
   `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets, and prints the
   certificate fingerprint;
3. in a separate job, builds the release twice from two different directories
   and fails unless the two APKs are identical.

Signing certificate SHA-256:

```
CE:85:A4:91:84:50:57:7B:3B:59:42:3E:A7:62:CC:86:76:E2:25:2C:6F:28:02:6A:C0:BB:B9:13:FC:4F:06:A0
```

To publish a release:

1. raise `versionCode` and `versionName` in `app/build.gradle.kts`;
2. write the changelog for the new `versionCode` in
   `fastlane/metadata/android/*/changelogs/<versionCode>.txt`;
3. commit, then tag the commit `v<versionName>` and push the tag:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

CI checks that the tag matches `versionName` and that the changelog exists, runs
every check above, and creates a GitHub Release with the signed
`mapconquer-<versionName>.apk` attached and the changelog as its notes.

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
  every land hex belongs to a province that holds its own capital; straits on
  the world map are water all the way across; a map's fingerprint changes when a
  single hex does.
- `ScenarioAssetTest` — every scenario cross-checks against its map: no province
  claimed twice, no ship spawned on land, no campaign objective you already own.
- `CombatTest` — damage bounds, the rock-paper-scissors relationships, terrain and
  entrenchment, artillery's asymmetry.
- `SessionTest` — the turn engine on small hand-written maps: capture, sieges,
  formations, air missions, surrender, the conquest victory and turn limit, and
  that a new game starts on the player's turn.
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
  not derived from OpenStreetMap, Natural Earth or any other dataset — at this
  scale there is nothing a real dataset would buy, and keeping everything under
  one licence makes the F-Droid build trivially auditable. It also lists which
  land masses must never touch (islands, and pairs such as Arabia and Africa).
- `tools/hexraster.py` — projects those polygons onto the hex grid, keeps every
  island and sea gap at least one hex of water, and paints terrain by latitude,
  elevation and biome.
- `tools/places.py` — the single source of truth for provinces and nations: one row
  per city with its coordinates, city tier, owner and its name in all three
  languages, plus how nations merge, split and ally in each year. Both the map
  files and the translations come from this table, so they can never drift apart.
- `tools/scenarios.py` — the campaign battles.
- `tools/topology.py` — the geography written down as rules: borders that must
  exist, seas that must stay seas, and countries whose mainland must be one piece.
  `genworld.py` fails when the world map breaks any of them.

CI re-runs both generators and fails if the committed output differs, so the
assets in the repository always match the tools that produced them.

## Design notes

A few decisions worth knowing before reading the code:

- **Everything is drawn on a `Canvas`.** One `Activity`, one `SurfaceView`, no
  Compose, no fragments, no view hierarchy. Screens are states of `GameView`.
- **No binary assets.** Unit symbols are drawn as vectors, the icon is a vector,
  flags are emoji — except the Soviet, East German and wartime German flags,
  which no emoji font has and which are drawn in code in the same waving style —
  and sound effects are synthesised at runtime. Nothing in the
  APK needs a provenance statement.
- **Determinism.** The RNG is a seeded xorshift whose state is part of the save
  file, so a battle plays out the same way on a reload.
- **Saves know their map.** A save records the map's fingerprint and a format
  version; one made on a different map, or under older rules, is refused rather
  than loaded into the wrong hexes.
- **Tile indices, not objects.** Pathfinding and AI walk hundreds of thousands of
  tiles per turn; nothing on those paths allocates.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

This is an independent, original work. It is not affiliated with, derived from or
endorsed by any other strategy game.
