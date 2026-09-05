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

1. Edit the `.ts` (or interface JSON / graphic PNG) in `cache-source/`.
2. Pack: `tools/build/install/tools/bin/tools cache build cache-source data/cache` (the local
   `installDist` of the tools module; ~45 s for a full pack, seconds incrementally). It compiles
   every clientscript, so a type or name error fails here.
3. Read it back from the packed cache, never trust the source alone:
   `CACHE_PATH=$PWD/data/cache/ tools cs2 decompile <id>` must print exactly what you wrote.
   Graphics and components: decode them with the core decoders (`GraphicDecoder`, `InterfaceDecoder`)
   through `jshell --class-path "$(ls tools/build/install/tools/lib/*.jar | tr '\n' ':')"` with
   `CACHE_PATH` set (`kotlinc -script` rejects JDK 25).
4. Commit in `cache-source` (branch `dev`), push, bump the submodule pointer in the server commit.
   The dev lobby and bleeding-edge world rebuild the cache from the tree on their next restart and
   log `cache build: clientscripts: n archives repacked`.
5. The only real test is the client. Ask for a client check of anything you cannot decompile-verify:
   layout, hover, tooltips, timing.

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

The name hash is Jagex's: `h = h * 31 + byte` over the name, as a signed 32-bit int
(`"symbol_lock"` -> -329922702, `"[proc,bank_placeholder_button]"` -> 834915639). Scripts are hashed
with their bracketed category; graphics and other archives with the bare name.

Names follow the identified names around them (`world_select_stars_0` -> `world_select_bots`,
`symbol_lock` -> `symbol_lock_open`, `lobby_worldswitcher_drawlist` -> `lobby_worldswitcher_bots_icon`).
`gamevals.d.ts` and `vars.d.ts` are hand-maintained alongside the catalogs; keep ids sorted.

## 3. Client rules that crash or silently break a script

Read the client at `~/darkan/darkan-bot` (Kotlin) for anything below you are unsure of; the CS2
ops live in `com/jagex/game/runetek5/config/rsinterface/cs2/ops/`, hover and click dispatch in
`graphics/ifcomponent/InterfaceRenderer.kt`.

- **cc ids are contiguous from 0 per component.** `cc_create` grows the slot array to `id + 1` and
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
  never drew it and its PvP login warning keys off bit 10), 3 lootshare, 4 highlight.

## 4. Patterns worth copying

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
