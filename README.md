<!--
SPDX-FileCopyrightText: 2026 AcideFluorhydrique
SPDX-License-Identifier: GPL-3.0-or-later
-->

# MapConquer

A turn-based strategy game on a hex map, for Android. You command a nation's army
— infantry, tanks, artillery, ships and aircraft — take cities, keep your troops
supplied, and win the war one province at a time.

Free software, no ads, no trackers, and **no Android permissions at all**: it
never touches the network, your storage or your location. Traditional Chinese,
Simplified Chinese and English, switchable inside the app.

[中文說明](README.zh.md)

## What you can play

**Campaign.** Nine historical battles in three chapters — World War II in Europe,
World War II in the Pacific, and the Cold War — from Poland 1939 to the Central
Front 1985. Every battle can be played from either side. Each one has a clear
goal: take the named cities, or hold them until a given turn. Winning faster
earns more stars, and stars buy commanders you keep for every later game.

**Conquest.** The whole world, starting in the year you choose:

- **1939** and **1943** — the Axis against the Allies.
- **1950** — the Western and Eastern blocs.
- **1980** — an alternate history. An army that answers to no nation, the Ashen
  Legion, holds most of the world. Sixteen surviving powers, each down to a
  handful of cities, fight back together.

Pick a nation and defeat every nation at war with you. A nation that loses its
last city surrenders.

## How a turn works

You move first; the computer moves after you end your turn.

- **Move and attack.** Tap a unit. Blue hexes are where it can go, red hexes hold
  an enemy it can hit. A unit that attacks cannot move again that turn.
- **Take cities.** A city has its own defence. Wear it down to zero, then walk a
  land unit in: the whole province, with its income, becomes yours.
- **Build.** Tap one of your cities and press Build. Bigger cities build heavier
  units. You can build a unit as a formation of one to four.
- **Stay in supply.** Supply reaches out from your cities and your allies'. A unit
  outside it weakens and eventually starves. Turn on the Supply layer to see how
  far yours reaches; supply trucks carry it forward.
- **Use the air.** Tap a larger city or a carrier and press Air. Fighters hit
  infantry, bombers hit tanks and ships, and an airdrop puts infantry behind the
  lines.
- **Cross the sea.** Troops can wade across a strait on their own, but for a real
  crossing put them on a transport ship — and escort it.
- **Read the colours.** Gold is yours, green is an ally, red is an enemy, grey is
  neutral. A thick red border is a front line.

The in-game **How to Play** screen explains each of these in more detail.

## Units at a glance

You do not need to memorise this. The one rule that matters: every unit is good
against some things and poor against others, so send the right one.

| Land | What it is for |
| --- | --- |
| Infantry | Cheap and tough. The unit that takes and holds cities. |
| Mountain Infantry | Infantry that moves quickly through mountains and jungle. |
| Marines | The only unit that can land from a ship and fight the same turn. |
| Recon Vehicle | Very fast and sees far, but fragile. Finds the enemy. |
| Armour | Hits hard, breaks through enemy lines, and can strike again after destroying a target. |
| Anti-Tank Gun | Destroys tanks. Weak against everything else. |
| Artillery | Fires from two or three hexes away and takes no return fire. Helpless up close. |
| Rocket Artillery | Artillery with longer reach and a heavier punch, at a higher price. |
| Anti-Air | Weakens enemy air strikes and airdrops near it. |
| Supply Truck | Carries supply forward so an advance can keep going. |
| Headquarters | Strengthens nearby units and supplies them. |

| Sea | What it is for |
| --- | --- |
| Transport Ship | Carries three land units across the sea. Unarmed — escort it. |
| Destroyer | Cheap escort. Hunts submarines. |
| Cruiser | All-round warship that also shields nearby ships from air attack. |
| Battleship | The strongest ship. Shells the coast from three hexes out. |
| Submarine | Hidden until something comes close. Deadly to transports. |
| Carrier | A moving airfield: flies one air mission a turn. |

| Air mission | What it is for |
| --- | --- |
| Fighter Strike | Hits infantry hard. |
| Bomber Strike | Hits tanks and ships, or an empty city's defences. Needs a large city. |
| Airdrop | Puts a fresh infantry unit on an open land hex in range. |

## Installing and building

Signed APKs are attached to the
[GitHub releases](https://github.com/AcideFluorhydrique/mapconquer/releases).

To build it yourself you need only JDK 17 — Gradle fetches the rest:

```bash
./gradlew assembleRelease
```

Everything else a contributor needs — the rules in detail, tests, release
signing, the map generators and the design notes — is in
[docs/development.md](docs/development.md).

## Reference behaviour

Many of the rules — combat, victory, surrender, turn order, the shape of the
1980 conquest — follow the behaviour of a classic game in this genre, as
established by the maintainer's own play and analysis of it. What is known, what
is still uncertain and how it could be tested is written down in
[docs/original-behavior.md](docs/original-behavior.md).

That document records behaviour and the numbers that define it. The code here
was written independently from those descriptions, and nothing from that game —
no code, artwork, sound, maps or text — is included in this project. Where
MapConquer deliberately differs (supply lines, upkeep, its own unit roster and
maps), the document says so.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

MapConquer is independently written and is not affiliated with or endorsed by
the publisher of any other strategy game.
