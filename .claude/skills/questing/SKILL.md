---
name: questing
description: Make, write, generate, review or debug area quests in the Darkan generated world - the quests that invulnerable city service NPCs (bankers, shopkeepers, barbers, realtors) hand out. Use this whenever the task touches data/quests, a quest's JSON, its decision tree page, quest givers, quest steps (kill, dungeon boss, dungeoneering, gather, deliver, talk, visit, service, choice), short, medium or long quests, quest rewards and quest points and how they scale with distance from the centre, the quest tab list, writing a batch of new quests for a city with an LLM, or remaking a city's seeded quests. For a hand-coded quest with its own Kotlin class and Quest enum entry use quest-maker instead.
---

# questing

An area quest is JSON in `data/quests/<city id>/`. The game reads every file there at start and runs the quests with one engine; no Kotlin is written per quest. The pipeline is always **JSON, then the decision tree page, then the game**. Load `darkan-code-standards` before touching Kotlin and `cs2-engineer` before touching the quest tab scripts.

## Loop

1. `quests.py list <area>` to see what the city already has, and `quests.py brief <area>` for its facts.
2. Write or edit JSON following `SCHEMA.md` (`examples/velneir_townsfolk.json` has one short, two medium and one long quest with a choice).
3. `quests.py validate` until it prints `OK`. Errors stop the game loading that quest; warnings are things to look at.
4. `quests.py html`, open `data/quest-views/index.html`, and read the tree of each quest you changed: lanes are stages, branches are choices.
5. In the game: `::areaquests reload` picks the files up with no restart. Talk to the giver, then check the quest tab.

Run from the server root: `python3 -I .claude/skills/questing/scripts/quests.py <command>`.

| Command | Does |
|---|---|
| `list [area]` | every quest with giver, length and step path, and the quest points the area holds |
| `show <quest id>` | one quest as text: steps, branches, rewards |
| `validate [area \| file...]` | the same checks the game makes, plus warnings; exit 1 on errors |
| `html [area]` | one decision tree page per quest in `data/quest-views/<area>/` and `index.html` |
| `tree <quest id>` | the tree JSON the page is drawn from |
| `brief <area> [count]` | a complete prompt for an LLM: the city's facts, the rules and the schema |

`data/quests/world.json` is written by the world at every start (areas with tier, givers, local creatures and reward numbers; dungeons with level, boss and creatures). The tools read it, so start world 22 once after adding a city or dungeon.

## Two ways to make quests

**Seeded maker.** `AreaQuestPlanner.make(city)` builds a city's quests from its seed so that the city holds exactly 20 quests: 20 less the hand and LLM written quests in the city's other files, split into 8 short, 8 medium and 4 long when nothing is hand written, shared out over the service NPCs in turn. Each long quest needs a short quest of the same giver. The same city always gets the same quests. It writes `generated.json` only when the file is missing, so a hand edit stays. To remake: delete the file and restart, or `::areaquests make <city>`. A new city gets its quests at the next start with nothing to do.

**LLM written.** Run `quests.py brief <area> <count>`, give the output to a model (or write the quests yourself from it), save the answer as `data/quests/<area>/<name>.json`, then validate and look at the trees. The brief lists the only ids and names the quests may use; anything else fails validation. Never put LLM quests in `generated.json`.

## How a quest plays

- **Givers** are the city's invulnerable service NPCs only. `Talk-to` on one opens the quest menu when it has an offer, a step of yours or a quest of yours in progress; "Something else." passes to the NPC's normal chat. Other options (Bank, Trade) are never taken over.
- **Discovery.** Hearing a giver's offers adds those quests to the quest tab in red. Accepting turns one yellow. Handing in turns it green and adds its quest points. A quest that `requires` something is not offered, and so not discovered, until the requirement is met.
- **Quest tab.** In generated worlds the tab lists only discovered area quests, grouped by city. Clicking one opens the journal: done steps struck through, the current step, the rewards. The server sends the rows as `Inv.area_quests` (one coin stack per row: the amount is the state, 1 header, 2 not started, 3 started, 4 complete; the tooltip is the text) and the client draws them itself: the `onTimer` of 190:15 runs `area_quests_timer`, which redraws from the inv as soon as the tab loads and every 3000 client cycles (one minute) after, so a reload of the tab or a vanilla `ql4_refresh` can never leave the vanilla list showing. The timer does nothing while the inv is empty, which is how a world without area quests keeps the vanilla list.
- **Quest points** have no cap. Every quest that is added raises what a player can earn.
- **Steps** are described in `SCHEMA.md`. Every quest ends by returning to its giver; that hand-in is not a step.

## Difficulty and rewards

- Tier is the threat level at the city's centre: 1 in the middle of the world, plus 1 for every 200 tiles out. Creatures and dungeons already scale with it, so the same step is harder farther out.
- The seeded maker multiplies amounts by `1 + 0.2 x (tier - 1)` and picks better goods at higher tiers. Do the same by hand: `brief` prints amounts that fit the tier.
- Rewards are computed, never written: quest points 1, 2, 3 by length plus 1 for every 5 tiers; coins `100 x weight x tier`; XP `150 x weight x tier x (tier + 3) / 4` over the skills in `rewards.xp`; weight 1, 3, 7. Change the numbers in `AreaQuestRewards.kt` only.
- Length is checked: short 1-2 steps, medium 3-4, long 5-10 on the longest path.

## Writing good quests

- Use what the city has: its own sewer and boss, the creatures listed around it, the nearest wild dungeon, the nearest other city. A long quest should leave the city.
- Name creatures the way the game shows them, lower case (`giant rat`). A `kill` with no `mob` counts any hostile creature; with no `where` it counts anywhere.
- A `gather` item must be something the world produces (`world.json` `gatherItems`): logs, ores, bars, bones, raw fish.
- A `deliver` uses a parcel item and must not go back to the giver. The giver hands the parcel out again if the player lost it.
- Put a `choice` in long quests: two routes that join again at a later step. Give the first branch's last step a `next`.
- Dialogue is original and short. Model the shape on the vanilla quests: an offer, a start prompt, steps gated on progress, a journal, a completion scroll.

## What an edit needs

| Changed | Needed |
|---|---|
| A quest file | `::areaquests reload` in game, or a restart |
| A city's seeded quests | delete `generated.json`, then restart or `::areaquests make <city>` |
| Kotlin | `JAVA_HOME=/home/jesse/.jdks/jdk-26.0.2.1+1 flock /tmp/darkan-build.lock nice -n 10 ./gradlew :world:installDist --offline --console=plain -q`, then `systemctl --user restart darkan-world-22` |
| `area_quests_draw.ts`, `area_quests_timer.ts` | compile each with `tools cs2 compile cache-source/clientscripts <6747\|6748>` (needs `JAVA_HOME` 26 and `CACHE_PATH=$PWD/data/cache-cities/`), then restart; the start repacks them |

`::areaquests reset` clears your own quest log for testing. Never restart the lobby or world 123.

## Viewing the trees

The pages load `data/quest-views/three.min.js` by relative path, so open them from disk in a browser, or serve the folder (`python3 -m http.server -d data/quest-views <port>`) for the app's preview pane. Without WebGL the page draws the same tree on a 2D canvas.

## Code

| Part | File (`world/src/main/java/com/rs/game/content/quests/area/`) |
|---|---|
| Schema classes, file loading, validation | `AreaQuestSpec.kt` |
| Giver lookup, dialogue, step progress, hooks, `::areaquests` | `AreaQuests.kt` |
| Player log (discovered, active, complete), saved on the player | `AreaQuestLog.kt` |
| Reward formula | `AreaQuestRewards.kt` |
| Quest tab rows, journal, completion scroll | `AreaQuestTab.kt` |
| Seeded maker and `world.json` | `AreaQuestPlanner.kt` |
| Quest tab scripts | `cache-source/clientscripts/proc/area_quests_draw.ts`, `clientscript/area_quests_timer.ts` |

Hooks outside the folder: `HostileNPC.drop` reports kills, `DungeonManager` reports a finished dungeoneering floor, and `QuestManager` hands the quest tab to `AreaQuestTab` when the world is generated.

## Not checked by the tools

- `validate` checks names against `world.json` and the obj catalog. It does not check that a creature is common enough to find, so prefer the first few listed.
- The tree page is drawn from the JSON, not from a play-through. Walk a new kind of quest once in the game.
