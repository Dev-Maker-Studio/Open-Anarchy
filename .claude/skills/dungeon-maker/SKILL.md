---
name: dungeon-maker
description: Make, edit or review a dungeon in the Darkan generated world. Use this whenever the task touches data/dungeons, a dungeon entrance (a city manhole, a cave mouth or a trapdoor), a dungeon's seed, theme, layout, mob respawn points, difficulty scaling or boss, or asks to add a dungeon for a new city or biome. It holds the dungeon JSON, the three themes (cave, underground fortress, sewer), the seeded generator, the text preview and the rules for mobs. Custom bosses have their own subskill in boss-maker/SKILL.md.
---

# dungeon-maker

A dungeon is one small JSON file, `data/dungeons/<id>.json`. Everything else (rooms, corridors, walls, decoration, mob respawn points, the boss arena) is built from its `seed`, so the same file always gives the same dungeon. Load `darkan-code-standards` before touching Kotlin. To make or change a boss, read `boss-maker/SKILL.md` in this folder.

## Loop

1. `dungeon.py list` to see every dungeon, its level and its boss.
2. Edit the JSON, or `dungeon.py reseed <id> <seed>` to try another layout.
3. `dungeon.py validate` until it prints `OK`.
4. `dungeon.py plan <id>` and read the text map. It needs `./gradlew :core:compileKotlin` once after a Kotlin change and no running world.
5. Restart world 22 so the cache repacks the dungeon squares (see "What an edit needs"), then walk it with `::dungeon <id>` and `::dungeon <id> boss`.
6. `dungeon.py mobs <id>` lists every respawn point with its npc and level, as the running world chose them.
7. `dungeon.py shoot <id>` renders five pictures into `data/dungeon-views/<id>/` (`surface`, `hall-top`, `hall-tilt`, `boss-top`, `boss-tilt`) with the city-maker view harness: no login, about 20 seconds, and it shows the packed cache, so run it after the restart. Join them with `magick montage` and read the one sheet.

Run the tools from the server root: `python3 -I .claude/skills/dungeon-maker/scripts/dungeon.py ...`.

## Reading the text map

| Character | Meaning |
|---|---|
| `.` | floor |
| `~` | sewer water channel (walkable) |
| `:` | boss fight area |
| `E` / `a` | the way out (ladder or rope) and the tile a player arrives on |
| `0` to `9` | a mob respawn point; the digit is how deep it is, 0 at the entrance and 9 at the boss |
| `B` | the boss respawn point |
| `P` | the exit portal behind the boss |
| `o` | decoration that blocks its tile |

North is up. Check in this order: the entrance room has no mobs, digits rise towards `B`, nothing blocks a corridor, the boss room is the last room on the main path.

## JSON

```json
{"id": "velneir", "name": "Velneir Sewers", "theme": "sewer", "seed": 13149688715701638, "slot": 4,
 "entranceX": 7483, "entranceY": 7475, "origin": "city:velneir"}
```

| Field | Meaning |
|---|---|
| `theme` | `cave`, `fortress` or `sewer`. It picks the entrance object, floor, walls, decoration, mob pool and default boss |
| `seed` | the only input to the layout. Change it to get another dungeon |
| `slot` | which underground plot the dungeon uses. Unique per dungeon; never reuse one |
| `entranceX`, `entranceY` | sector tile of the surface entrance (south-west tile of the object) |
| `origin` | `city:<city id>` or `biome:<biome>`. The world plans one dungeon for every city and biome whose origin has no file |
| `boss` | optional boss id from `data/dungeons/bosses`; without it the theme default is used |
| `rooms` | optional main path length, 5 to 9; without it the seed chooses |

## Themes

| Theme | Entrance | Layout | Mobs |
|---|---|---|---|
| `sewer` | a manhole, one per city, beside a street | brick rooms, corridors three wide with a water channel | rats, spiders, snakes, zombies, slimes |
| `cave` | a cave mouth | round rooms and ragged tunnels, no built walls | bats, spiders, bears, cave crawlers, trolls |
| `fortress` | a trapdoor with a ladder below | stone halls and straight two wide corridors | zombies, ghosts, skeletons, rogues, black knights, demons |

Themes live in `core/.../dungeon/DungeonTheme.kt`. Every loc and npc there is a gameval constant; check a new one has no click handler elsewhere (`grep` the id in `world/src`) before using it as an entrance or exit.

## Rules the generator keeps

- The layout sits on a 4 by 4 grid of plots. A self avoiding walk picks the main path, the first room is the entrance, the last is the boss room, and one to three dead end side rooms hang off the path.
- The entrance room and the boss room never hold normal mobs.
- Depth is walking distance from the arrival tile divided by the walking distance to the boss.
- The surface entrance is placed on flat dry ground, off roads, away from cities, lodestones and scenery. A city manhole sits beside a street and outside every building.

## Mobs and difficulty

- Base level is the threat level at the entrance: 1 at the centre of the sector plus 1 for every 200 tiles away from it.
- A mob's level is the base level times 0.8 at the entrance rising to 1.2 at the boss.
- The boss's level is the base level times 1.2 times its own `level` factor.
- Only respawn points make mobs in a dungeon. Random hostile spawns, wildlife and weather are switched off there.
- Each theme has its own song list (`Biome.CAVE`, `FORTRESS`, `SEWER` in `BiomeMusic.generated`), which replaces the biome music. A boss fight plays one of the four songs in `BiomeMusic.bossFights`.
- A boss ignores Slayer level requirements; normal mobs keep them.
- A dead mob comes back at its respawn point after 5 minutes (500 ticks). A mob that only left because the dungeon emptied and unloaded comes back at once when someone returns.
- Claims, building and destroying are refused in a dungeon.

## What an edit needs

| Change | Needs |
|---|---|
| `seed`, `theme`, `rooms`, `slot` | restart world 22: the start writes the dungeon map squares into the cache tree and repacks them, and clients fetch the new squares |
| `boss`, or a boss file | restart world 22, no cache change |
| `entranceX`, `entranceY` | restart world 22 |
| A new dungeon | add the file with an unused `slot`, or delete nothing and add a city: the world plans the missing one at start |
| Kotlin (`DungeonTheme`, `DungeonGenerator`) | rebuild (`./gradlew :world:installDist`), then restart |

Never restart worlds 123 or the lobby for dungeon work.

## Code

| Part | File |
|---|---|
| Data, plots, layout types | `core/.../dungeon/Dungeon.kt` |
| Themes | `core/.../dungeon/DungeonTheme.kt` |
| Seeded layout | `core/.../dungeon/DungeonGenerator.kt` |
| Entrance sites | `core/.../dungeon/DungeonSites.kt` |
| Files and planning | `core/.../dungeon/DungeonRegistry.kt` |
| Text map | `core/.../dungeon/DungeonText.kt`, `DungeonPreview.kt` |
| Map squares | `core/.../cache/DungeonTerrain.kt`, `MapCodec.reserved` |
| Entrances, exits, `::dungeon` | `world/.../generated/Dungeons.kt` |
| Respawn points and levels | `world/.../generated/DungeonMobs.kt` |
| Bosses | `world/.../generated/DungeonBosses.kt` |
| Music | `world/.../utils/music/BiomeMusic.kt`, `world/.../generated/GeneratedMusic.kt` |
