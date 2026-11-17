---
name: boss-maker
description: Create or change a custom dungeon boss in the Darkan generated world. Use this for any task that adds a boss, changes a boss's npc, strength, abilities, respawn time or drops, or assigns a boss to a dungeon. Part of the dungeon-maker skill.
---

# boss-maker

A boss is one JSON file, `data/dungeons/bosses/<id>.json`. A dungeon uses the default boss of its theme unless its own file names another one in `boss`.

## Loop

1. `boss.py list` shows every boss and the dungeons that use it.
2. Pick the body: `boss.py npc <text>` searches npc gameval names. Choose an npc with an Attack option and working combat (anything in `data/npcs/hostile_mobs.json` is safe).
3. Write the file. Copy an existing boss as the start.
4. `boss.py validate` until it prints `OK`. It checks names against the gameval catalogs and every ability field.
5. To use it, set `"boss": "<id>"` in `data/dungeons/<dungeon>.json`.
6. Restart world 22. The start log prints `Boss <id>: ...` for any problem and the level each dungeon gives its boss is in `dungeon.py mobs <dungeon>`.
7. Fight it: `::dungeon <dungeon> boss`.

Run from the server root: `python3 -I .claude/skills/dungeon-maker/boss-maker/scripts/boss.py ...`.

## JSON

```json
{
  "id": "warden",
  "name": "The Fallen Warden",
  "npc": "black_knight",
  "level": 1.3,
  "hitpoints": 7.0,
  "damage": 1.25,
  "respawnMinutes": 5,
  "abilities": [
    {"type": "slam", "everyTicks": 20, "radius": 3, "damage": 1.2, "shout": "Kneel!"},
    {"type": "summon", "atHealth": [50], "npc": "skeleton_armed", "count": 2, "shout": "Rise and defend the keep!"},
    {"type": "enrage", "belowHealth": 25, "damage": 1.5, "shout": "None leave this hall!"}
  ],
  "drops": [
    {"item": "coins", "min": 60, "max": 120, "perLevel": true},
    {"item": "big_bones", "min": 1, "max": 3}
  ]
}
```

| Field | Meaning | Default |
|---|---|---|
| `id` | the file name without `.json` | required |
| `name` | the name shown over the boss | required |
| `npc` | npc gameval name used for the model, animations and attack style | required |
| `level` | multiplies the boss level (base level times 1.2 times this) | 1 |
| `hitpoints` | multiplies hitpoints after level scaling | 1 |
| `damage` | multiplies the max hit after level scaling | 1 |
| `respawnMinutes` | minutes before the boss returns | 5 |

## Abilities

| Type | Fields | What it does |
|---|---|---|
| `slam` | `everyTicks` (25), `radius` (3), `damage` (1), `shout` | shouts, then three ticks later hits every player within the radius for half to all of max hit times `damage` |
| `summon` | `atHealth` list of percentages, `npc`, `count` (2), `shout` | once per threshold, spawns minions at 0.6 of the boss level; they vanish when the boss dies or resets |
| `enrage` | `belowHealth` percentage, `damage`, `shout` | once, multiplies max hit by `damage` for the rest of the fight |
| `heal` | `everyTicks` (25), `percent`, `shout` | restores that percentage of max hitpoints |

- A tick is 0.6 seconds.
- A boss that has had no target for 100 ticks resets: full hitpoints, minions gone, thresholds armed again.
- A new ability type is one branch in `DungeonBoss.processNPC` plus a row in `boss.py`'s `ABILITIES` and `REQUIRED`.

## Drops

- Every boss also drops its npc's normal table.
- Each entry of `drops` names an obj by gameval (`boss.py obj <text>` searches), with `min` and `max` amounts, a `chance` from 0 to 1 (1 when absent), and `perLevel` to multiply the amount by the boss level.

## Balance guide

| Tier | hitpoints | damage | abilities |
|---|---|---|---|
| Easy | 4 to 5 | 1.0 to 1.1 | one |
| Normal | 6 to 7 | 1.15 to 1.25 | two or three |
| Hard | 9 to 12 | 1.3 to 1.5 | three or four, with slam every 15 to 20 ticks |

Because the level comes from the dungeon, the same boss is harder in a dungeon further from the centre. Do not hand tune a boss for one dungeon; change its factors.
