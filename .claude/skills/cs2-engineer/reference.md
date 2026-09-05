# Worked examples

## Bank placeholder button (varbit-driven, 2026-09-04)

Goal: the bank's placeholder toggle reflects a per-player setting the client owns.

```
config_varbit/11713.json                                bank_placeholders: varp 1248 bit 20 (first free bit)
gamevals/varbit.json                                    "11713": "bank_placeholders", coverage named +1
clientscripts/vars.d.ts                                 declare let varbit_bank_placeholders: number;
clientscripts/clientscript/bank_placeholder_button.ts   [clientscript,...] -> calls the proc
clientscripts/proc/bank_placeholder_button.ts           reads the varbit, ifSetGraphic / ifSetOp / tooltip hook
clientscripts/index.json                                6567, 6568 with nameHash of "[clientscript,...]" / "[proc,...]"
gamevals/clientscript.json                              the two names, coverage cache_ids +2, named +2
interfaces/762/124.json                                 onLoadScript + onVarpTransmit (varps [1248]) -> the clientscript
interfaces/762/125.json, 127.json                       icon components: defaultImage, size = graphic canvas, offset to centre
gamevals/component.json                                 762:124..127 named interface_762:placeholder_*
```

Server: `player.vars.saveVarBit(Varbit.bank_placeholders, 1)`; nothing else is sent per open.

Icons: `symbol_lock` (4346, 22x22) on the toggle; `symbol_lock_open` (12071, 22x26) drawn from its
pixels with the shackle lifted and the raised leg capped, bottom-aligned so the bodies match.

## World list bot icon (flag-driven, 2026-09-04)

Goal: rows whose flags carry bit 2 show a bot icon with a tooltip, in the list and in favourites.

```
graphics/12072/0.png                                     world_select_bots, 16x16 robot head
graphics/index.json                                      "12072": {"compression": "gzip", "nameHash": -1385813054}
gamevals/graphic.json + clientscripts/gamevals.d.ts     name + Graphic declaration
clientscripts/proc/lobby_worldswitcher_bots_icon.ts     (comp, id, x, y, xmode, flags): cc_add_graphic, ccSetPosition,
                                                        hide when testBit(flags, 2) == 0, else enter/exit tooltip hooks
clientscripts/clientscript/lobby_worldswitcher_timer.ts flag graphic at id 2n, bot icon at 2n+1 in column 910:73
clientscripts/cs2/cs2_3118.ts                           favourite rows: id 15 at ifGetX(910:74) - 24
clientscripts/cs2/cs2_3152.ts                           tooltip positioner subtracts ifGetScrollX/Y of every layer walked
```

First attempt used id `1000 + row` and hung the lobby with `RuntimeException:999`: cc ids must be
contiguous (see rule 3). The fix creates the icon for every row and hides it when unflagged.

Row geometry (interface 910, layer 63, rows 20 high): 68 favourite star (23w), 69/70 world number
and members star (x25, 53w), 71 players (x80, 73w), 72/73 country name and flag (x154, fills),
74 Type (right 94, 63w), 75 lootshare (right 52, 40w), 76 ping (right 0, 50w), 77/78 hover overlays.
