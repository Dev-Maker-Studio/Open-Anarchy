---
name: content-development
description: How server content is developed and proven in Darkan - the world module's plugin and dialogue patterns, cache-side definitions (objs, seqs, structs, enums, models) and their registration, importing assets from another revision, the pack/verify/read-back loop, and how the user checks it in the client. Load before writing or porting any game content. Maintained as we learn.
---

# Content development

Content is a server half (the `world` module) and a cache half (`cache-source/`), and neither is
done until the other agrees with it. Code standards
(`darkan-code-standards`) apply unchanged; clientscript, interface and graphic work follows
`cs2-engineer`. The RS3 urn port (`docs/rs3-port.md`, `docs/reference/rs3-urns.json`) is the
worked example of everything below.

## 1. The loop

1. Establish what the cache holds before designing: definitions in `cache-source/config_*`,
   names in `cache-source/gamevals/<type>.json`, the client's own tables in
   `cache-source/clientscripts` (grep the strings you see in game - a menu text, a guide row -
   the source may be a hard-coded script, not a definition). Assume nothing about ids or byte
   layouts; read them.
2. Allocate every new id next-free at the end of its index, in a deterministic order written
   down first (a spec under `docs/reference/`), and give every id its real name in the catalog.
3. Cache side: write the files, register them, pack, **read back** with the decoders (`jshell`
   against the tools install, `tools cs2 decompile <id>`), and `cache verify` against the
   baseline signature (§5).
4. Server side: Kotlin against the generated gameval constants; `scratchpad/remote-build.sh
   build` (never Gradle on the desktop; one build at a time). No content tests (§5).
5. Hand it to the user: local lobby + world + client on their display (§6). Never drive the
   client yourself to validate content; use the tools viewer for interface visuals.

## 2. Server content in the world module

- **Plugin handlers**: a `@PluginEventHandler object` (or a class's `companion object`) with
  `@JvmField val x = ItemClickHandler(keys, options) { e -> ... }`; `PluginManager` finds public
  static fields of `PluginHandler` type. Handlers: `ItemClickHandler(Array<Any> ids, Array<String>
  options)`, `ItemOnItemHandler(IntArray)`, `NPCDropHandler(npcs, Array<Any> items)`,
  `XPGainHandler { e -> }` (fires on every `Skills.addXp` with the raw xp, before rates),
  `ObjectClickHandler`, `ButtonClickHandler(Interface.x)`. `.plugin.kts` scripts get the same
  through `PluginGlobals` (`onItemClick`, `onItemOnItem`, `onXpDrop`, ...).
- **Ids** are `Obj.x`, `Seq.x`, `Interface.x`, `Component.interface_x.y`; a `Urn`-style table is
  an `enum class` with explicit fields per state (never `base + n` arithmetic) and
  `entries.associateBy` lookups in the companion.
- **Dialogues**: `player.startConversation { makeX(intArrayOf(obj), max); exec { ... } }` (the
  Kotlin builder, `DialogueDsl.kt`); the chosen MakeX amount is
  `MakeXStatement.getQuantity(player)` (varbits 8094/8095, 60 when nothing was set). Category
  pickers are `MakeXStatement(int[] icons, String[] labels)` with one `Dialogue` per option
  (`Conversation.addNext(statement, *options)`); `MakeXActionD`/`MakeXItem` do the timed
  crafting with xp, anim, level and skill.
- **Items**: `Item.addMetaData("key", value)` / `getMetaDataD` for per-item state (an urn's xp);
  `item.id = ...` then `inventory.refresh()` to transform in place; `inventory.getNumberOf`,
  `containsOneItem`, `hasFreeSlots`, `deleteItem(id, n)`, `addItem(Item(id, n))` (non-stackable
  n > 1 fills n slots). `player.getItemFromInv(id)` is the first match.
- **Animation**: `entity.anim(Animation)`; `setNextAnimation` is deprecated. XP: `skills.addXp`
  fires the event chain, `addXpLamp` does not.
- **Messages** use the definition names (`ItemDefinitions.getDefs(id).name.lowercase()`,
  `Utils.addArticle`), never a hard-coded copy of a name.

## 3. Cache definitions

- **New obj/seq/struct**: copy the same-role sibling file, set the fields, **delete
  `opcodeOrder`** (it is a whitelist; a field whose opcode is not listed is dropped at pack), and
  write it in the tree's JSON shape (`DefinitionJson`: field order, arrays/maps inline while the
  line fits 100 columns, else one entry per line). A new file inside an existing archive needs
  no `index.json` line; a new archive (every model) does. Struct filenames are the id; the `id`
  field inside is wrong for most and never read.
- **Seqs** that only differ by the held item are cloned from the same-tier sibling with
  `rightHandItem` moved; worn models must be set on the obj for the item to show in hand.
- **Skill guide rows**: one struct + one row in the skill's enum, level-sorted (see
  `cs2-engineer` §4 "Skill guides - one data source"); both guides read it.
- **Catalogs**: every new id gets a `gamevals/<type>.json` entry named for its purpose in the
  identified-name convention (an imported asset keeps its source revision's dev name) and the
  coverage block bumped; `clientscripts/gamevals.d.ts` gets the sorted declaration. A pre-existing
  id missing from a catalog (obj 25358 was) is a defect to fix, not a pattern to follow.
- **Data the client also holds elsewhere**: menu/guide/popup text that is hard-coded in a
  clientscript must be moved to the definitions it duplicates, not edited in two places.

## 4. Importing assets from another revision

`tools rs3port` (`docs/rs3-port.md`) is the pattern: a read-only source tree, a spec that maps
source ids to allocated 727 ids, a converter proven by **calibration** on assets both revisions
share (convert them, diff against the shipped files, gate on 100% where the data allows), a
learned crosswalk for anything that is not a copy (material ids, texture projections), and an
idempotent importer that refuses to run when an allocated id is not exactly one past the tree's
ceiling. Facts that only calibration found: RS3 negates y and reverses winding, keeps the 727
colour in a vertex stream, re-mapped every material id. Do not trust a plan's claims about a
format; measure on the shared assets.

## 5. Proving it

- **Tests**: content gets none - no table, flow or per-ticket tests for plugins, dialogues,
  shops, drops or definitions. Content is proven by the cache read-back and by the user in the
  client (§6). The fast suite (`world/src/test`, `WorldHarness`) is for engine-tier behaviour
  only (collision, line of sight, packet dispatch, containers), in a class named for the engine
  part it covers, and such a test must fail against the broken code before the fix.
  `ChunkManager.getChunk(tile.getChunkId())` loads a region's map, base locs and clipping for one
  (`LineOfSightTest`). Known flaky and unrelated: `SocialGatewayTest` friends-chat tests fail on
  `dev` too.
- **Verify signature**: `cache verify` already differs from `../cache` (authored graphics,
  scripts, obj 25358); save its per-index lines before you start and diff after - the only lines
  that may change are the archives your ids land in.
- **Remote host**: `/tmp` there is volatile; after a reboot recreate `.env`
  (`CACHE_PATH=/tmp/darkan-build/cache/`, no `CACHE_SOURCE_PATH`), rsync `data/cache/` and
  `data/` (minus cache) up, then build.

## 6. Handing it to the user

Start the lobby jar, then the world jar (`docs`/`local-e2e-loop`), restart both after every
pack, and launch the client on the user's display with **no** `-Dautologin` (a saved login plus
the flag makes the world reject the token: "LoginToken for X presented by Y"). Say what to look
at and stop. For interface visuals use the tools interface editor (`tools interface <id>`, live
source mode) - a screenshot of a rendered interface is cheap; a scripted walkthrough of the
game is not wanted.

## 7. Client rules met today

Instruction cap 200,000 per hook (2,000,000 per load script) - never search a list per row;
`cc_create` ids contiguous per layer; `opcodeOrder` whitelist; two skill guides, one source;
noted items are `null`-named objs with `notedTemplateId 799` and `noteId` both ways.

## 8. Keep this skill current

When content work teaches a rule - an API that is deprecated, a client behaviour, a format fact -
add it here in the same change, with the file that proves it.
