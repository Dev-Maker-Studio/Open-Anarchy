# Area quest schema

A quest file lives in `data/quests/<area>/`. It holds one quest object, or `{"area": "...", "quests": [ ... ]}`.
`generated.json` is written by the game's seeded maker; hand and LLM quests go in their own files beside it.

## Quest

| Field | Required | Meaning |
|---|---|---|
| id | yes | unique in the world, lower snake case, `<area>_<giver>_<slug>` |
| name | yes | the name shown in the quest tab |
| area | yes | city id from `data/cities` |
| giver | yes | the invulnerable service NPC who offers it: `banker`, `barber`, `realtor`, or a shop kind the city has (`general_store`, `sword_shop`, `archery_shop`, `magic_shop`, `clothes_shop`, `food_shop`, `armour_shop`, `fishing_shop`, `staff_shop`) |
| length | yes | `short` (1-2 steps), `medium` (3-4), `long` (5-10); counted on the longest path |
| summary | yes | one sentence for the journal |
| intro | yes | the giver's offer, a list of dialogue lines |
| accept | no | the player's line that starts the quest |
| outro | no | the giver's lines at hand-in |
| requires | no | `{"questPoints": 4, "quests": ["other_quest_id"]}`; the giver hides the quest until met |
| rewards | no | `{"xp": ["mining", "slayer"], "items": [{"item": "bread", "amount": 3}]}`; see Rewards |
| steps | yes | the objectives, in order |

A dialogue line is a string spoken by the NPC. A line that starts with `player: ` is spoken by the player.

## Steps

Every step has `id` (unique in the quest, never `return`) and `type`. Steps run in list order. `next` names a later
step id to jump to, or `return` to go straight to the hand-in. After the last step the player returns to the giver.
`journal` overrides the journal line the game writes for the step.

| type | Fields | Done when |
|---|---|---|
| kill | `amount`, optional `mob` (creature name as the game shows it, lower case), optional `where` (a place) | that many matching hostile creatures die to the player |
| boss | `dungeon` (dungeon id) | the player kills that dungeon's boss |
| dungeoneering | `amount` | that many dungeoneering floors are completed |
| gather | `item` (obj gameval name), `amount`, optional `to`, optional `lines` | the player talks to `to` (default: the giver) holding the items; they are taken |
| deliver | `item` (a parcel obj), `to`, optional `lines` | the player talks to `to` holding the parcel; the giver hands it out when the step starts and again on request |
| talk | `to`, `lines` | the player talks to `to` |
| service | `to` | the player uses that NPC (any click: bank, trade, haircut, homes) |
| visit | `place` | the player stands in the place |
| choice | `prompt`, `options` (2-4 of `{"text", "next"}`), optional `to`, optional `lines` | the player picks an option at `to` (default: the giver); each option jumps to its `next` |

`to` is `{"area": "<city id>", "giver": "<giver>"}` and may be in another city.

A place is one of:

| Place | Means |
|---|---|
| `{"dungeon": "velneir"}` | inside that dungeon |
| `{"biome": "forest"}` | anywhere in that biome on the surface |
| `{"area": "velneir"}` | inside that city; with `"radius": 300` within that many tiles of its centre |
| `{"x": 7600, "y": 7500, "radius": 20}` | near a sector tile |

## Rewards

The game works rewards out; a file never states coins, xp amounts or quest points.

- Tier is the area's threat level: 1 at the centre of the world plus 1 for every 200 tiles away.
- Quest points: 1 short, 2 medium, 3 long, plus 1 for every 5 tiers. There is no cap on the total.
- Coins: `100 x weight x tier`, weight 1 short, 3 medium, 7 long.
- XP: `150 x weight x tier x (tier + 3) / 4`, split evenly over the skills in `rewards.xp`.
- `rewards.items` are extra items handed over as written; keep them small.

## Example

```json
{
  "id": "velneir_food_shop_empty_larder",
  "name": "The Empty Larder",
  "area": "velneir",
  "giver": "food_shop",
  "length": "medium",
  "summary": "The food shop keeper needs fish, and the sewer rats that ate the last delivery dealt with.",
  "intro": ["Rats got into the cellar and ate a week of stock.", "Clear them out of the sewers, then bring me fish to sell."],
  "accept": "I'll sort out your rats.",
  "outro": ["Shelves full and not a squeak below. Thank you."],
  "rewards": {"xp": ["slayer", "fishing"]},
  "steps": [
    {"id": "sewer", "type": "visit", "place": {"dungeon": "velneir"}},
    {"id": "rats", "type": "kill", "mob": "rat", "amount": 8, "where": {"dungeon": "velneir"}},
    {"id": "fish", "type": "gather", "item": "raw_trout", "amount": 6}
  ]
}
```
