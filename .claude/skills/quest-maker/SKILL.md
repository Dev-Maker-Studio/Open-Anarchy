---
name: quest-maker
description: Use when designing, validating, visualising or generating multihop quests for Darkan - quest chains that send the player between cities, biomes, items and mobs. Covers the quest spec v3 JSON, the quest.py CLI (validate, html, kotlin, scan, roundtrip), adding the quest to the Quest enum, and the multihop design checklist.
---

# quest-maker

A quest is a JSON spec of hops (see `SCHEMA.md`). The spec is the source of truth: the viewer and the Kotlin are generated from it, and `scan` reads the Kotlin back into the same spec. Load `darkan-code-standards`, `kotlin-plugins` and `content-development` before touching the generated Kotlin.

## Workflow

1. Design the chain on paper first: where it starts, which cities, biomes, items and mobs it visits, where it ends.
2. Write `<quest>.json` following `SCHEMA.md`; copy `examples/velneir_courier.json` (4 hops) or `examples/three_biomes_relic.json` (11 hops).
3. Validate until clean: `python3 -I scripts/quest.py validate spec.json --gamevals <dir> --hostile <file> --quest-kt <Quest.kt>`.
4. Look at it: `python3 -I scripts/quest.py html spec.json out.html` and open the file; check the lanes, the order and every dialogue.
5. Generate: `python3 -I scripts/quest.py kotlin spec.json <outdir>`, then copy the two files into `world/src/main/java/com/rs/game/content/quests/`.
6. Add the quest to the enum (below), let the user build on the remote host, and have the user play it.
7. Run `roundtrip` after every spec edit; it must print `OK`.

## Commands

All commands run from this folder with `python3 -I scripts/quest.py`.

| Command | Result |
|---|---|
| `validate <json> [--gamevals <dir>] [--hostile <file>] [--quest-kt <file>]` | structural and referential errors, or `OK` plus the dimensions the chain spans; exit 1 on errors |
| `html <json> [out.html]` | self-contained offline viewer: lanes per dimension, click a hop for details and dialogue, validation result on top |
| `kotlin <json> <outdir>` | `<Name>.kt` (QuestHandler, journal, rewards, embedded spec) and `<Name>Dialogue.kt` (hooks, conversations, requirement check) |
| `scan <dir> [out.json]` | the embedded spec, or the partial data of a hand-written quest |
| `roundtrip <json>` | json to Kotlin to json in a temp dir; `OK` or `DIFF` |

- Paths used here: gamevals `assets/server-cache/cities/gamevals`, hostile list `assets/data/hostile-mobs.json`, enum `world/src/main/java/com/rs/engine/quest/Quest.kt`.
- Stdlib only; the scripts import each other from `scripts/`, which is why `-I` still works (`quest.py` adds its own folder to the path).

## World facts to design with

- Nine biomes: plains, winter, wilderness, forest, desert, ocean, mountain, volcano, rune_essence. The generated world is warped Voronoi blobs of 200 to 400 tiles; the spawn is at 7500,7500 and is plains.
- Cities are JSON files in the world's `data/cities/*.json` (ids and names); use the real city id in `at.city`. That folder is not in every worktree; look it up before inventing an id.
- The level indicator rises by 1 for every 200 tiles from the spawn (`PortSites.levelAt`: `1 + max(|dx|, |dy|) / 200`). Hostile mob level is bounded by that distance, so a high level mob can only sit far away.
- Hostile mobs come from `assets/data/hostile-mobs.json` (`ID`, `name`, `level`); `validate --hostile` rejects a `mob_kill` npc that is not in it.
- Hostile mobs never drop coins. Pay in the quest rewards, not in a mob drop.
- An item must be obtainable in the world: from a mob the quest itself grants (`item_get` with `from.npc`), from a loc, or from an existing skill. Never require an item nothing produces.
- Quest drops are guaranteed while the hop is open: the generated hook adds the item on each kill until the player holds `amount`. Keep real drop tables out of the spec.

## Adding the quest to the Quest enum

Read `Quest.kt` first; do not edit it from this skill, hand the exact line to the user.

1. `Quest` is `enum class Quest(val id: Int, private val slotId: Int)`. `id` is the cache quest definition id (`gamevals/quest.json`, 204 ids); `QuestDefinitions` reads name, quest point reward, requirements and difficulty from the cache by that id.
2. `slotId` is the row in `Enum.enum_2252`; its struct drives the quest list entry (`structId`, `seasonalBit`). A quest that has no row there does not show in the quest tab.
3. So a brand new quest needs a cache quest definition and an `enum_2252` slot (cache-source work, see `content-development`), then one enum line such as `VELNEIR_COURIER(204, 194),` with the constant equal to the spec's `quest`.
4. The handler is found by `@QuestHandler` through `Quest.initializeHandlers`; a quest is "implemented" once a handler class exists, and `setStage` does nothing before that.
5. Until the enum line exists `validate --quest-kt` reports the quest as missing; that is expected for a new quest.

## Multihop checklist

- The chain has at least 3 hops and touches at least 2 of: city, biome, item, mob. `validate` prints which.
- Each hop unlocks the next: its completion sets the stage that the next hop's hook waits for.
- Nothing is unobtainable: every `obj` is dropped, found or crafted somewhere reachable before the hop that needs it.
- Travel is reasonable: keep each leg within a few hundred tiles unless the biome itself is the point; do not alternate far ends of the map.
- Difficulty rises with distance: mob levels and amounts grow along the chain, in step with the level indicator of where each hop sits.
- The player can recover: an item given or consumed is obtainable again, and the start NPC can repeat the current instruction.
- One trigger per stage: two hops with the same stage and trigger are rejected, so give parallel objectives different stages or triggers.
- End where the story ends: the last hop is an `npc_talk` or an NPC-aimed item hop holding the only `complete_quest`, ideally back in a city.

## Generated code rules

- No comments; ids are gamevals (`Npc.x`, `Obj.x`, `Loc.x`); hooks are top-level `@ServerStartupEvent` functions using `com.rs.plugin.kts` (`onNpcClick`, `onObjectClick`, `onNpcKillParticipation`, `onItemAddedToInventory`, `onItemOnNpc`, `onItemOnObject`, `onChunkEnter`).
- One handler per NPC and per loc is generated and branches on the quest stage, because a second `onNpcClick` for the same id silently replaces the first.
- If the NPC or loc already has other content, merge by hand; the generator does not know about it.
- Arrival hops are detected on chunk entry, so keep `radius` at 8 or more, and give city and biome hops an anchor only when the arrival must advance the quest.

## Round-trip rule

- Edit the JSON, never the generated `<Name>.kt` or `<Name>Dialogue.kt`; regenerate instead.
- If someone edits the Kotlin by hand, `scan` still returns the old embedded spec, so fold the change back into the JSON before regenerating.
- A quest without `QUEST_SPEC_JSON` is hand-written: `scan` extracts only handler arguments, stage numbers and journal strings.
- `roundtrip` must print `OK` before the spec is handed over.

## Not verified by the tools

- The generated Kotlin is not compiled by these scripts; build it on the remote host.
- Gamevals are checked by name against the cache gamevals, not for behaviour (for example that a loc really offers the option the hop uses).
