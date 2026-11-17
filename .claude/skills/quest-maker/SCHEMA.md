# Quest spec v3

One JSON file describes one quest. `scripts/quest.py` validates it, draws it, generates Kotlin from it and reads the Kotlin back. Every id is a typed field, never prose, so the file round-trips.

## Top level

| Field | Type | Meaning |
|---|---|---|
| generatorVersion | string | must be `"3.0"` |
| quest | string | `Quest` enum constant, `[A-Z][A-Z0-9_]*`; the Kotlin class name is its PascalCase |
| name | string | display name |
| summary | string | one sentence |
| startNpc | gameval | `Npc.x`; must own a stage 0 `npc_talk` hop |
| requirements | list | typed, may be empty |
| rewards | list | typed, may be empty |
| stages | list | `{stage, title, journal}`; ascending integers from 0; the last one is the completed stage |
| hops | list | the ordered chain; the first hop is at stage 0 |

## Gamevals

- Written as a string `Npc.x`, `Obj.x`, `Loc.x`. The part after the dot is the name in `assets/server-cache/cities/gamevals/{npc,obj,loc}.json` (`entries` maps id to name).
- Keys checked everywhere in the file: `startNpc`, `npc`, `obj`, `loc`, `has_item`.
- `validate --gamevals <dir>` checks that each name exists; add `--hostile assets/data/hostile-mobs.json` to also require every `mob_kill` npc to be a hostile mob.

## requirements and rewards

| type | fields | where |
|---|---|---|
| skill | stat, level | requirements; checked by the start dialogue |
| quest | quest | requirements; checked by the start dialogue |
| item | obj, amount | requirements (itemsText only); rewards (given in `complete`) |
| questPoints | amount | both; the points come from the cache quest definition, the spec only records them |
| xp | stat, amount | rewards |
| coins | amount | rewards |

- `stat` is one of attack defense strength hitpoints range prayer magic cooking woodcutting fletching fishing firemaking crafting smithing mining herblore agility thieving slayer farming runecrafting hunter construction summoning dungeoneering.

## Hop

| Field | Required | Meaning |
|---|---|---|
| id | yes | `[a-z][a-z0-9_]*`, unique |
| stage | yes | quest stage active while the hop is open; must be listed in stages |
| kind | yes | see the kinds table |
| title | yes | short label |
| at | yes | location, see below |
| next | no | targets; every target is a later hop with a stage equal or higher |
| biome | no | extra biome tag for `mob_kill` |
| steps | npc_talk, optional on item_give and item_use aimed at an npc | dialogue |

Kinds and their fields:

| kind | extra fields | Kotlin hook |
|---|---|---|
| place | at.x, at.y, at.plane | `onChunkEnter` plus `withinDistance` |
| biome | at.biome | `onChunkEnter` when at has x,y,plane, else a journal waypoint |
| city | at.city | `onChunkEnter` when at has x,y,plane, else a journal waypoint |
| npc_talk | npc, steps | `onNpcClick` |
| item_get | obj, amount, from `{npc}` or `{loc}` optional | kill participation, object click, or `onItemAddedToInventory` |
| item_give | obj, amount, to `{npc}` or `{loc}` | `onItemOnNpc` or `onItemOnObject`, takes the item |
| item_use | obj, amount, on `{npc}` or `{loc}` | same, keeps the item |
| mob_kill | npc, amount | `onNpcKillParticipation` with a kill counter |
| object_interact | loc, option | `onObjectClick` |

- A hop at stage 0 other than `npc_talk` generates no hook, because the quest has not started.
- Two hooked hops with the same stage and the same trigger are rejected.

## at

- `{ "x": 7600, "y": 7500, "plane": 0, "radius": 16 }` a coordinate; radius is optional, at least 8, default 16.
- `{ "city": "velneir" }` a city id, optionally with x, y, plane as the arrival anchor.
- `{ "biome": "desert" }` one of plains winter wilderness forest desert ocean mountain volcano rune_essence, optionally with an anchor.

## next

- A list of hop ids: `["get_iron"]`.
- A choice entry: `{ "to": "kill_wolves", "when": { "has_item": "Obj.knife", "amount": 1 } }`. The generated hook picks the first entry whose condition holds, else the first entry without one.

## Dialogue steps

Each step has a `step` field.

| step | fields |
|---|---|
| say | who `npc` or `player`, head (a `HeadE` constant such as `CALM_TALK`), text |
| options | options: list of `{text, steps}`; `steps` may be empty |
| action | type, args |
| end | none |

Actions:

| type | args | Kotlin |
|---|---|---|
| give_item | obj, amount | `inventory.addItemDrop` |
| take_item | obj, amount | `inventory.deleteItem` |
| set_stage | stage | `questManager.setStage` |
| give_xp | stat, amount | `skills.addXp` |
| give_coins | amount | `inventory.addCoins` |
| complete_quest | none | `questManager.completeQuest` |

- Exactly one `complete_quest` exists in the whole spec.
- Every hop without `next` must hold it; every `npc_talk` hop with `next` must contain a `set_stage` to one of its targets' stages unless the target has the same stage.

## Example hop

```json
{
  "id": "get_iron",
  "stage": 1,
  "kind": "item_get",
  "title": "Take the iron bar",
  "obj": "Obj.iron_bar",
  "amount": 1,
  "from": { "loc": "Loc.chestclosed" },
  "at": { "city": "velneir" },
  "next": ["give_iron"]
}
```

## Round trip

- `kotlin` embeds the minified spec as `private const val QUEST_SPEC_JSON` in `<Name>.kt`; `scan` parses it back, so the result equals the input.
- Without that constant the Kotlin is hand-written and `scan` returns only handler arguments, stage numbers and journal strings.
