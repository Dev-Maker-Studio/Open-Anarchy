---
name: cs2-engineer
description: How to author and change 727 clientscripts, interface components and graphics in the cache-source tree - the edit/pack/verify loop, id and name registration, the client rules that make scripts crash, hook and tooltip patterns, and where to read the client. Load before touching cache-source/clientscripts, interfaces, graphics or the gamevals those edits register. Maintained as we learn.
---

# CS2 engineering in the cache source tree

The client's interface behaviour is TypeScript in `cache-source/clientscripts/<category>/<name>.ts`,
compiled back into index 12 whenever the cache is built. This skill is the working knowledge for
changing it; `reference.md` beside it holds two worked examples (the bank placeholder button, the
world list bot icon). The toolchain itself is documented in `docs/cs2-editor.md`, the tree in
`docs/cache-source.md`. Code standards (`darkan-code-standards`) apply unchanged: gamevals for every
id, real names for what we author, no comments, no attribution.

## 1. The loop

0. **Preview without packing.** The interface editor reads the tree directly - components from
   `interfaces/<id>/*.json`, clientscripts compiled from their `.ts` - so an edit is visible in a
   render seconds later, with no pack and no restart:

   ```bash
   EXTRA="-Ddarkan.capture=/tmp/out.png -Ddarkan.captureAfter=30" \
     CACHE_PATH=$PWD/data/cache/ tools/capture-headless.sh /tmp/root.png 110 -- \
     interface 499 --source cache-source --var varbit_skill_guide_skill_v2=11 --stat all=99
   ```

   `--source cache-source` is the default when the tree is beside the working directory. `--var`,
   `--stat` and `--run` supply the game state an interface needs before it draws anything (the
   skill guide builds its list from `varbit_skill_guide_skill_v2` and `statBase`); in the GUI the
   same three live in the **State** panel, saving a `.ts` reloads it, and F7 re-reads everything.
   Graphics, fonts and models still come from the packed cache, so a *new* graphic needs step 2.
   See `docs/cs2-editor.md`, "Live source mode". Pack when you are ready to ship, not to look.
1. Edit the `.ts` (or interface JSON / graphic PNG) in `cache-source/`.
2. Pack: `tools/build/install/tools/bin/tools cache build cache-source data/cache` (the local
   `installDist` of the tools module; ~45 s for a full pack, seconds incrementally). It compiles
   every clientscript, so a type or name error fails here.
3. Read it back from the packed cache, never trust the source alone:
   `CACHE_PATH=$PWD/data/cache/ tools cs2 decompile <id>` must print exactly what you wrote.
   Graphics and components: decode them with the core decoders (`GraphicDecoder`, `InterfaceDecoder`)
   through `jshell --class-path "$(ls tools/build/install/tools/lib/*.jar | tr '\n' ':')"` with
   `CACHE_PATH` set (`kotlinc -script` rejects JDK 25).
4. **Look at it before handing it over.** The tools interface viewer renders any interface with its
   fonts and load scripts: `EXTRA="-Ddarkan.capture=<out.png> -Ddarkan.captureAfter=10"
   tools/capture-headless.sh <root.png> 100 -- interface <id>` (private Xvfb, llvmpipe; the
   viewer takes ~60 s to load, so settle 100). Crop the stage at 4x and judge text colour, size
   and centring there - cream on the gold login-button art read as "trash" on the user's GPU,
   dark brown (`0x2E1B08`) reads; verdana labels need the preset buttons' `-3` y nudge.
5. Commit in `cache-source` (branch `dev`), push, bump the submodule pointer in the server commit.
   The dev lobby and bleeding-edge world rebuild the cache from the tree on their next restart and
   log `cache build: clientscripts: n archives repacked`.
6. The only real test is the client. Screenshot it yourself with `client-shot.sh` beside this skill:
   it runs `~/darkan/darkan-bot/build/libs/darkan-client-shaded-*-all.jar` on a private Xvfb (GLX
   off, or Auto Setup chooses OpenGL and captures are black), with a scratch client home, against
   any lobby - a local one from the remote-built `lobby-server.jar` with `CACHE_SOURCE_PATH` in
   `.env` serves the tree you just edited (restart it after every edit; it repacks incrementally),
   and the dev lobby after its restart shows what players get (`10.69.69.22 43593` from the LAN; the
   `dev.darkan.org` proxy refuses that port from here). A fresh client home starts on the
   first-run graphics popup: `INPUT="mousemove 386 320 mousedown 1 sleep 0.3 mouseup 1"` clicks
   Auto Setup and the login screen follows; `JAVA_EXTRA="-Dautologin=user:pass -Dautologin.world=1"`
   logs in and enters a world for lobby and gameframe captures (`testes`/`testes` on the dev database).
   Ask for a client check only for what a screenshot cannot show: hover, tooltips, timing.

Local end-to-end loop (the only real test, 2026-09-06): `java -jar lobby/build/libs/lobby-server-3.0.0.jar`
and `world/build/libs/world-server-3.0.0.jar` from the server root (`.env` with `CACHE_SOURCE_PATH`;
jars from the remote build host, `scp` them back), then the client on a private Xvfb
(`Xvfb :90 -extension GLX`, `-Dautologin=user:pass`, click Play at (386,503) - `-Dautologin.world`
stalls) and `xdotool` clicks. **Restart the world as well as the lobby after every cache pack**: the
world's cache CRCs are read at startup and a stale world answers the login with "Darkan has been
updated". Never `pkill -f` a pattern that also appears in your own shell command line.

Gradle never runs on the desktop (remote build host, one build at a time); the local tools install
and `jshell` are fine.

## 2. Registering a new script, graphic or component

Ids are allocated by hand, next free at the end of the index. Everything is registered in the same
commit or the build fails with a coverage mismatch:

| Adding | Files |
|---|---|
| a script | `clientscripts/<proc\|clientscript>/<name>.ts` whose header comment is `// [proc,<name>]`; `clientscripts/index.json` entry `{"version": 1, "compression": "gzip", "nameHash": <hash of "[proc,<name>]">}`; `gamevals/clientscript.json` entry + `coverage.cache_ids` and `coverage.named` +1 |
| a graphic | `graphics/<id>/0.png` at the canvas size (one RGBA PNG per frame, nothing else, <= 256 colours); `graphics/index.json` entry `{"compression": "gzip", "nameHash": <hash of the name>}`; `gamevals/graphic.json` entry + coverage; a `/** graphic <id> */ const <name>: number;` line in `clientscripts/gamevals.d.ts` under `namespace Graphic` |
| a component | `interfaces/<iface>/<n>.json` (copy a sibling of the same type); `gamevals/component.json` entry `"<iface>:<n>": "interface_<iface>:<purpose>"` + coverage; the `Component.interface_<iface>` declaration in `gamevals.d.ts` |
| a varbit | see `darkan-code-standards` rule 5; plus `declare let varbit_<name>: number;` in `vars.d.ts` |
| an obj | `config_obj/<id>.json` (copy the same-role sibling; drop its `opcodeOrder` - see §3); no `index.json` change while the id lands in an existing archive (`id shr 8`); `gamevals/obj.json` entry + coverage; `/** obj <id> */` line under `namespace Obj` |
| a seq | `config_seq/<id>.json` (archive `id shr 7`, same rules); `gamevals/seq.json`; `namespace Seq` |
| a struct | `config/struct/<id>.json` - the FILENAME is the id, the `id` field inside is wrong for most files and is never read; `gamevals/struct.json`; `namespace Struct` |
| a model | `models/<id>.glb` written by `ModelGltf.write` + its own line `"<id>": {"compression": "gzip"}` in `models/index.json` (every model is its own archive, so the index line is mandatory); `gamevals/model.json`; `namespace Model` |
| an enum row | append `"<key>": <value>` to `config_enum/<id>.json` `map` and bump `length` |

The name hash is Jagex's: `h = h * 31 + byte` over the name, as a signed 32-bit int
(`"symbol_lock"` -> -329922702, `"[proc,bank_placeholder_button]"` -> 834915639). Scripts are hashed
with their bracketed category; graphics and other archives with the bare name.

Names follow the identified names around them (`world_select_stars_0` -> `world_select_bots`,
`symbol_lock` -> `symbol_lock_open`, `lobby_worldswitcher_drawlist` -> `lobby_worldswitcher_bots_icon`).
`gamevals.d.ts` and `vars.d.ts` are hand-maintained alongside the catalogs; keep ids sorted.

## 3. Client rules that crash or silently break a script

- **Instruction cap.** The client aborts a hook after 200,000 instructions and a load script
  after 2,000,000 (`CS2Executor.executeScript` in darkan-bot); the abort is silent and leaves
  whatever the script drew so far. A per-row lookup that rescans a 300-entry enum is enough to
  hit it - index rows by enum position and skip, never search (the old skill guide's
  `[proc,skillguide_tab_row]` is the pattern).
- **`cc_create` ids are contiguous per layer.** Creating id n when n-1 does not exist throws and
  aborts the script (see the bank placeholder work); derive cc ids from a drawn-slot counter, not
  from a data index that has gaps.
- **`opcodeOrder` is a whitelist.** A definition JSON that carries `opcodeOrder` (every unpacked file
  does) is encoded with exactly those opcodes; a field you set whose opcode is not in the list is
  silently dropped at pack time (an inventory option added to an obj whose order lacks 35 never
  reaches the client). Delete `opcodeOrder` from any file you edit; the encoder then writes its
  canonical order with every non-default field.
- **JSON formatting is the writer's.** `DefinitionJson` writes keys in field order, arrays and
  maps inline while `column + compact length <= 100`, else one entry per line at two-space indent;
  write edited files the same way or the next unpack churns them.

Read the client at `~/darkan/darkan-bot` (Kotlin) for anything below you are unsure of; the CS2
ops live in `com/jagex/game/runetek5/config/rsinterface/cs2/ops/`, hover and click dispatch in
`graphics/ifcomponent/InterfaceRenderer.kt`.

- **cc ids are contiguous from 0 per component - so create them in id order.** `cs2_3118` called
  the bots icon (id 15) right after cc 10 and threw `RuntimeException:14` on every favourites
  redraw; it is created last now. `cc_create` grows the slot array to `id + 1` and
  throws `RuntimeException("<id-1>")` if the slot before is empty; the whole script dies and the
  interface hangs (the world list stuck on "Please wait..." with `CS2 Error: RuntimeException:999`).
  Never offset ids (`1000 + row`). For a second child per row in a column, interleave (`2n`, `2n+1`)
  and create the extra child for every row, hiding it when unused - or give it a component of its own.
- **A `CS2 Error` trace ends with the script call stack, innermost first**, then the opcode index:
  `2994 / 6569 / 3115 / 113` = `cc_add_graphic` <- our proc <- `lobby_worldswitcher_timer`, op 113.
- **Hover hooks fire for every component under the cursor**, not just the topmost, so an icon under
  a full-row overlay still gets its own `ccHookMouseEnter`/`Exit`. Ops are collected the same way.
- **Graphics draw at the component's origin, unscaled.** Size the component (or the cc) to the
  graphic's canvas and position it; do not expect centring. Graphics are drawn as the whole canvas,
  so a frame's placement is where its pixels are.
- **`cc_add_graphic` / `cc_add_text` / `cc_add_rect` leave the new cc active**; `ccSetPosition`,
  `ccSetHide`, `ccHook*` right after them apply to it. `ccSetPosition(x, y, xmode, ymode)`: mode 0
  left/top, 1 centre, 2 right/bottom.
- **Hook type strings** spell the argument list: `I` component, `i` int, `s` string; `event_com`,
  `event_opindex`, `event_mousex/y` are filled in when the hook fires
  (`hook(cs2_3149, "Iis", [comp, id, "text"])`).
- **`ifGetX/Y` return resolved positions**, so right-anchored columns can be measured from them;
  scrolled layers are not accounted for by anything - subtract `ifGetScrollX/Y` yourself (see the
  world list tooltip fix in `reference.md`).
- **World list flags**: bit 0 members, 1 quickchat, 2 bots allowed (Jagex's PvP bit; the client
  never drew it and its PvP login warning keys off bit 10), 3 lootshare, 4 highlight, 5 the world
  serves its own cache (the lobby sets it when a world's JS5 index CRCs differ from its own;
  `lobby_worldswitcher_customcache_icon` draws it, the client switches JS5 endpoint on it).
- **Three children per row in a world list column**: `3n`, `3n+1`, `3n+2` (the flag graphic, the
  bots icon, the own-cache icon in `lobby_worldswitcher_timer`), created in that order every row.

- **The minimap and compass components (`contentType` 1338/1339) must match their mask graphic's size.**
  `ComponentMinimap.drawMinimap` throws `IllegalStateException` on the first draw if the component's
  width/height differ from the sprite built from its graphic (`mapmask` 154 vs `graphic_8728` 152):
  change the graphic and `ifSetSize` in the same script, for 548:153 as well as 746:190.
- **`ifGetGraphic` on any component id 0..count-1 is safe** (returns -1 for layers and text), so a
  loop over an interface can re-map every static graphic without a per-component list; scripts that
  set graphics later still need their constants wrapped (`gameframe_skin_graphic(Graphic.x)`), and a
  `case Graphic.x:` label cannot be wrapped.
- **Components draw in id order within a parent**: a text under later graphic siblings is hidden
  (the settings dropdown value had to be the last child of its layer).
- **Hover and click fall through to whatever lies under a component that has no op of its own**, so a
  dropdown list drawn over buttons shows their tooltips; give overlays an op or keep them clear.
- **Never resize a component to fit a different graphic - author the graphic at the component's
  size.** A type-5 component scales its graphic to its own size, so a 22x22 icon in a 30x37
  component is blown up, and every layout script that touches the component (`cs2_722` on each
  resize) fights the size change. The 2011 tab icons are 30x37 canvases (`toplevel_tab_icons_2011_*`)
  and no gameframe component changes size for the skin except the minimap ones the client forces.
- **A skin apply must never rewrite state it did not change.** Restores run only when the component
  still shows the 2011 graphic (`if (ifGetGraphic(c) == Graphic.x_2011)`), geometry only when the
  component still has the 2011 size; otherwise a varp transmit of the settings varp (any chat colour
  change) would reset script-driven state (deselected tab backgrounds, the xp token variant).
- **Wrap every script that names a mapped graphic, not just the gameframe's own.** The combat tab's
  boxes are set by `cs2_1134`/`cs2_1140` through `event_com` hooks and name no interface, so a
  scope of "scripts that mention gameframe components" missed them; hover states are JSON hook
  arguments to `graphic_swapper`/`cs2_299` (the 2012 tile 9179, the options button 9195, the worn
  button 3030, the slot 9167) and family siblings of mapped graphics (`*_1`/`*_2` hover and pressed
  variants) - map every sibling of a mapped family or the 2012 art leaks on hover.
- **1123 is a filled corner, not a frame corner**: the 2011 panel frame is 1121 across and 1122 down,
  overlapping at the corners; 1123 puts a dark stone block in each corner.
- **Verify against the stock cache, not against memory**: `git -C cache-source checkout dev`, pack,
  run, capture the same window sizes, and diff the chrome regions; the map and world content differ
  between sessions, so read the diff images, not the percentages.
- **Never call another interface's scripts from a gameframe load or var hook.** Calling `cs2_178`
  (the chat filter bar's own load script) and the orb updaters from the 746 hook left interface 751
  closed for the whole session - the bar under the chatbox vanished in both layouts. Re-run an
  interface's own script by adding the varp to that component's `varps` list (751:3, the world map
  button) or by giving the interface its own hook and case (the orbs, the chatbox frame).
- **The resizable tab bar in one picture**: 746:75-90 are the button backgrounds (8548, cleared to
  -1 on deselect by `cs2_1309`), 91-106 the always-on overlay that *is* the button's normal look
  (8547, `cs2_1292` swaps 8549 on hover), 59-74 the icons, 152-183 the selected-tab frames in
  pairs (8551 orange under 8550 white, both cleared to -1 by `cs2_1309` when a tab deselects). In
  the fixed frame and in 667 the same roles are 1835 (normal), 1837 (hover), 1836 (pressed) and the
  48x48 1841/1840 frames; both revisions clear the pair on every tab but the selected one, and the
  strip's rim is nothing more than the tiles' own bevelled edges.
- **Interface 746's sidestone frame in the resizable layout is a set of ccs on 746:108** built by
  `cs2_2968` from the `aif_window3_*` graphics on load; the chat frame is `cs2_5392` on 752:2.
  The 2011 panel frame is the classic stone set every 2011 window uses - 1123 corner with the
  bracket (flipped for the other three corners), 1121 tiled along the top and bottom, 1122 down the
  sides - built by `gameframe_skin_panel_frame` inside `cs2_2968`'s 2011 branch, corners 10 px in
  from the panel layer so the band sits against the tab content. The tab strip itself has no slab
  or frame in 667: the tiles are the whole strip.
- **Read the 667 interface definitions, not a render, for what 667 drew**: 667's stats tab has its
  own tile graphic (667 id 3030, now `stats_background_button_0_2011`) under every skill with
  yellow (0xFFFF00) level text where 727 uses 0xFF8C00. Its worn tab draws five connector lines as
  components of graphics 821 (vertical, 32 wide) and 822 (horizontal, 32 high) with 667's own
  geometry - 727's pieces are shorter and sit elsewhere, so the 2011 apply hides 387's connectors
  (3, 48-64) and creates the five as ccs on 387:2 (821 tiled 32x160 centred; 821 32x85 at x -56 and,
  hflipped, +56; 822 vflipped 80x32 at y 43 and 110x32 at y 82). The slot silhouettes are not in the
  JSON at all: `wornitems_slot` looks them up in `Enum.enum_796` (slot -> graphic 156-166/6301), so
  an enum lookup that yields a graphic needs `gameframe_skin_graphic(enumOp(...))` like any constant;
  667's silhouettes are darker (`worn_slot_<slot>_2011`) and are what makes the tab read as 2011.

- **Components can be re-typed in place.** Every `interfaces/<iface>/<n>.json` carries the full
  field superset, so turning a rect (type 3) into a graphic (type 5) or a text (4) is `type` plus the
  fields the new type reads; copy them from a sibling of that type. Repurposing dead components this
  way (the login screen's Facebook block became the Create Account button) leaves no gaps in the
  archive and nothing hidden.

- **One function per script file.** A `.ts` is exactly one `[proc,...]`/`[clientscript,...]`; a helper
  is its own registered script (`Unknown opcode function 'x'` otherwise). The compiler has no
  ternary `?:` and no `!bool`: use `if`/`else` and `x == false`. Locals are renumbered on read-back
  when you delete some, so after a pack copy the decompiled body over the source.
- **A layer (type 0) never gets a server op, and blank `' '` ops swallow clicks.** The right-click
  menu showed only Cancel with "Game Settings" on 261:32. Put the op on a full-size child
  (261:37, the text) with the click mask `setting.setting = 2` (bit 1 = op 1; 1026 on the graphics
  buttons); children copied from the login screen carry `' '` ops that consume the click - clear
  them. Ops on ccs need the server to send `IFEvents(layer, 0, n).enableRightClickOption(0)` after
  the interface opens, or the cc click never reaches the server.
- **Toolkit ids (client, 2026-09-08)**: 0 safe mode, 1 legacy OpenGL, 3 modern OpenGL (DirectX's
  old id, so `param_682` bitmasks and every script already treat it as hardware); software (2) and
  DirectX are gone. `DETAILCANSET_RENDERER` answers 3 for anything else.

## 4. Patterns worth copying
- **Game Settings window (interface 1321 `game_settings`, 2026-09-08)**: 742's frame (0-22 copied,
  18 the close X with op "Close", 19 the title), `interface_caption` 24, the `rows` layer 23
  (`onLoadScript`/`onVarpTransmit [1438]` -> `[clientscript,game_settings_init]`) and the hidden
  `dropdown` layer 25 with its background/border 26-27. `[proc,game_settings_rebuild]` draws one row
  per `game_settings_count()`: p12 label, a 140x16 value box (fill rect whose click hooks
  `game_settings_dropdown_open(row)`, border, p11 text from
  `game_settings_value_text(row, game_settings_current(row))`, the 2554 arrow; hover through
  `cs2_2691` on the fill), then sizes the window with room for the tallest list
  (`game_settings_values`). `[proc,game_settings_dropdown]` positions 25 under the box (`ifGetX/Y`
  of a sibling are parent-relative here), creates one p11 text cc per value with
  `ccSetOp(row + 1, "Select")` - the op index carries the row, the cc id the value - and the layer's
  `onMouseLeaveScript` closes it. The server (`GameSettings.kt`) opens the window from the Options
  tab button (261:32-37: login-button art 8699/8701, verdana 11 shaded cream label, the op on the
  full-size text 37), enables ops 0..rows-1 on the dropdown layer, and on a click sets
  `GameSetting.entries[opNum - 1]` to `slotId`; the var transmit redraws. Adding a setting = a case
  in `game_settings_label`, `game_settings_values`, `game_settings_value_text` and
  `game_settings_current`, `+1` in `game_settings_count`, and an enum entry in the same order.
- **Graphics Options renderer row** (`[proc,graphics_options_renderer_buttons]`, 977:52-54): the
  preset-button recipe (`set_but_*` tiles + verdana text, `stringWidth + 30`, uniform width, 16 px
  gaps, centred in 977:51) for Safe Mode / Legacy OpenGL / Modern OpenGL, `cs2_2697(toolkit,
  screen)` on click, the active toolkit drawn with the pressed tiles; the baked-text 3093-3122
  buttons are unused.
- **2011 skin, any window that shows worn slots**: give the interface the `gameframe_skin_update`
  hook on a hook-free layer (667:64), wrap its `enum_796` silhouette lookup
  (`cs2_2373`), and in `gameframe_skin_apply` hide its 2012 connector graphics and draw the five
  821/822 pieces as ccs on the connector layer (667:4, y one less than 387's). Hover art is per
  state: `cs2_5644` swaps 9208/9209 for the profanity button, so those needed wrapping and a 2011
  hover graphic (12417, the 12148 hover tile with the icon pixels both 2011 states share).


- **Gameframes**: the lobby screen 906, resizable frame 746 and fixed frame 548 each had a 50 px
  Home/Support/Forums/Clans bar as a root layer (906:1, 746:239, 548:2; buttons 509/517/525/533,
  244/252/260/268, 209/217/225/233) above a content root layer (906:0 and 335, 746:48, 548:204).
  The bar is hidden with height 0 since 2026-09-05 - `cc_gety_absolute_toplevel` subtracts its
  height, so hiding alone is not enough - and the content layers start at y 0. The fixed canvas
  size is `defaults/entity.json` `gameWidth`/`gameHeight` (765x503 now), read by the client at
  startup as `GAME_WIDTH`/`GAME_HEIGHT`; nothing in the client or server hard-codes it.
- **Login screen (interface 596, layer 4, 266x305)**: title 35 y11, username 38/39 y41-86, password
  40/41 y87-132, separators 42/43 y142, Log In 44 y149 (180x26: caps 54/56 graphic 8701 flipped,
  middle 55 graphic 8699 stretched, shadow text 57 colour 8082707 at (1,-2), text 58 at (0,-3), font
  4040; hover swaps 8701/8699 for 8702/8700 through `loginscreen_button_highlight`), Recover link
  45/53 y178, separators 46/47 y198, Create Account caption 52 y218 and button 59 y246 (parts 49/50/51,
  texts 48/60, click `login_register`). Layout is static: no script repositions these.

- **Gameframe skin (2026-09-06)**: `varbit_option_gameframe_skin` (varp 1438 bits 17-19, 0 = 2012,
  1 = 2011) drives `[proc,gameframe_skin_graphic]` (2012 -> 2011 graphic id, identity otherwise) and
  `[proc,gameframe_skin_apply]` (loop over an interface's components, `ifGetGraphic` ->
  `gameframe_skin_graphic_2012` -> `gameframe_skin_graphic`, then per-interface geometry, hides and
  the orb/chat/tab re-runs), hooked as `[clientscript,gameframe_skin_update]` on one hook-free layer of
  every gameframe interface (`onLoadScript` + `onVarpTransmit [1438]`). Scripts that pick 2012 art by
  `getWindowMode() >= 2` use `gameframe_skin_new()` instead. The 2011 graphics are the 667 cache's
  same-name versions, named `<name>_2011` (12073-12324), deduplicated pixel-for-pixel against the 727
  set first (`mapmask`/`compassmask` 2011 already existed as 8728/8729). Settings dropdown: 261:32-42,
  `[clientscript,options_gameframe_dropdown*]`, rows 41/42 click through to the server.
- **Skill guides - one data source (2026-09-09)**: `Enum.skillguide_skills[skill 1-25]` -> the
  skill's row enum (Crafting = 5465), one struct per row: `skillguide_name`, `skillguide_level`,
  `skillguide_item` (or `param_2214` graphic when there is no item), `skillguide_skill`,
  `skillguide_filter` (index into `Enum.skillguide_skill_filters[skill]`, whose names are the tabs:
  Crafting = Everything, Milestones, Armour, Jewellery, Objects, Other, Dungeoneering), and the
  optional `skillguide_members`, `skillguide_is_milestone` (also shown on tab 1), `skillguide_extrareq`,
  `skillguide_info`, `show_on_levelup`. The row enums are kept **sorted by level** (stable); the new
  guide (1218, `skillguide_initialise`) re-sorts anyway, the old parchment guide (499, `cs2_23`) and
  its level-up popup (741, `cs2_973`) read the enum in order through `cs2_12`/`cs2_13`/`cs2_14`/
  `cs2_1566`/`cs2_1567`, which now wrap `[proc,skillguide_tab_row]`, `[proc,skillguide_row_in_tab]`,
  `[proc,skillguide_legacy_name]` ("Members: " prefix, extrareq line) and
  `[proc,skillguide_legacy_unlock]` (the popup text). The 52 hard-coded per-skill table scripts they
  used to dispatch to (`cs2_2`, `cs2_3`, `cs2_977`-`cs2_1022`, `cs2_2787`, `cs2_2788`, `cs2_3354`,
  `cs2_3355`) are unreferenced and left in place. Adding a guide row = one struct + one enum entry at
  the right level position; never a script edit. The RS3 urn port (`docs/rs3-port.md`) is the worked
  example of adding objs, seqs, structs, models and guide rows together.
- **Var-driven interface state**: `onLoadScript` + `onVarpTransmit` (`varps: [parent varp]`) on the
  component, running a `[clientscript,x]` that calls `[proc,x]`; the proc reads `varbit_x` and sets
  graphic, op text and tooltip (`clientscripts/proc/bank_placeholder_button.ts`, modelled on
  `proc_bank_cert_button`). The server only writes the var.
- **Lobby tooltips (interface 910)**: box `910:14` (rect 15, fill 16, text 17); attach with
  `ccHookMouseEnter(hook(cs2_3149, "Iis", [comp, id, text]))` and
  `ccHookMouseExit(hook(cs2_3153, "Ii", [comp, id]))`; `cs2_3152` positions it. In the game frame
  the generic tooltip is `cs2_38` -> `cs2_569` -> `cs2_5335`.
- **Per-row extras in the world list**: one proc that creates the cc, positions it and hides or
  hooks it by flag, called from both `lobby_worldswitcher_timer` (list rows) and `cs2_3118`
  (favourite rows) - keep the two renderers in step.
- **Pixel art**: derive new icons from existing graphics' own pixels (copy regions with plain
  `paste`, never a masked paste - it squares partial alpha), keep the palette, render at 6-10x next
  to neighbours to judge, then compare the decoded frame with the PNG on every visible pixel.

## 5. Keep this skill current

When a change teaches something new - a client rule, an op's real behaviour, a hook that works or
does not - add it here in the same commit, with the script or client file that proves it. Remove
anything shown wrong. The reference examples should stay the shortest complete recipes we have.
