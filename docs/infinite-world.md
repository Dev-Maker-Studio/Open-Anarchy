# The 26 x 26 sector generated world (file still named "infinite world")

A 727 client can only address a 16,384 x 16,384 tile plane: tile coordinates are 14 bits, a map square
is `m<x>_<y>` with x and y up to 255, and region ids are 16 bits. One cache is therefore one plane, and
the client checks every archive it loads against the index checksums it received at login, so a cache
cannot change under a running client. The generated world gets its size from running **many planes**.

## Design

The world is one deterministic function of global tile coordinates (`WorldGenerator`: biomes, heights,
lakes, scenery). A **sector** (code: window, `WorldWindow`, `WORLD_WINDOW_X/Y`) is a 15,000 x 15,000 slice of it, hosted by one world server with its own
cache. The client reloads its cache from the world it selects, which already exists as the *own-cache
world* flag: a world whose index CRCs differ from the lobby's is listed as serving its own cache and the
client fetches JS5 from it (`SocialGateway.servesDifferentCache`). A window is therefore an ordinary world.

```
global tile (gx, gy)  ->  window (wx, wy), local tile (gx - origin(wx), gy - origin(wy))
```

- **The grid is 26 x 26 sectors of 15,000 tiles, no overlap** (`WorldWindow`: `WINDOW_TILES` 15,000,
  `OVERLAP_TILES` 0, `GRID_WINDOWS` 26, `GRID_TILES` 390,000). Names are row letter then column letter
  (AA north-west, AZ north-east, ZA south-west, ZZ south-east); window x is the column (A = 0, west), window y
  counts from the south (Z = 0), so `MM` is window 12,13. A sector origin is its nominal origin
  (`index * 15000`) rounded down to a multiple of 64 so that map squares line up (MM: 179,968 / 194,944);
  the generator is a pure function of global coordinates, so the under 64 tile difference to a neighbour
  would render identically. Local tile = global tile - `WorldWindow.origin(window)`.
- **Only the sectors in `GENERATED_SECTORS` exist (default and beta: `MM`).** `GeneratedWorld` refuses to run
  a sector outside AA..ZZ or outside that list, `WindowOrchestrator.ensure`, the admin route
  `/admin/windows/{x}/{y}`, `WindowRules` (demand, launch, handover) and `scripts/window*.sh` refuse the
  others. There is no handover between sectors for now: `WindowRules.hand` does nothing for a sector that is
  not generated, and `WorldWindow.owner(global)` still names the owner so handover can be switched on by
  adding sectors to the list (the overlap based mid-point handover no longer exists; it would be at the edge).
- **The world origin 0,0 is local tile (0,0) of sector MM** (`WorldWindow.WORLD_ORIGIN_X/Y` = origin of MM).
  Roads, level indicator, ore tiers, ports and the spawn count from it, and players see local coordinates
  (spawn at 0,0,0). The roads run north and east inside MM; west and south leave the sector.
- **How a world becomes MM**: `WORLD_SECTOR=MM` (or `WORLD_WINDOW_X/Y=12,13`; neither set also means MM).
  This is an explicit sector setting and not windowed mode on purpose: windowed mode (`WORLD_WINDOW_X/Y`
  set) also turns on `WindowRules` (the `window_positions` collection, handover, neighbour demand, idle exit),
  makes `CacheSource` use a hard-linked `-tree` copy and turns `MapMaterializer` off. A single sector has no
  neighbours, so worlds 22 and 123 keep their normal position persistence (player saves) and keep saving
  explored squares into the cache source tree.
- **Handover (windowed mode only, not active in the beta) = log out to the lobby, pick the next world.** The player's global position is stored in the
  `window_positions` collection when they leave a world (`Player.realFinish`). The world message names the
  target world (`WorldWindow.worldId`: 2000 + wy * 26 + wx). On login a window world maps the stored global
  position into its own local coordinates and teleports the player there. The client cannot change cache
  mid-session (see above), so this relog is the cheapest handover the protocol allows.
- **Each window builds its own cache** from the shared source tree: `WORLD_WINDOW_X/Y` set the generator's
  origin, so `m<x>_<y>` is generated from global square `origin/64 + x`. The index metadata (names) is
  identical for every window, only the archive contents and checksums differ. `WindowTree` hard-links the
  shared tree into `<CACHE_BUILD_PATH>-tree` so that per-window writes (the world map image) never touch the
  base tree, and `MapMaterializer` is off in window mode.
- **Ports and ids are derived from the window**: world `2000 + wy * 26 + wx`, port `44000 + wy * 26 + wx`.

## Running a window

```bash
scripts/window-world.sh 12 13    # sector MM, world 2000 + 13 * 26 + 12 = 2350, port 44350 (other sectors are refused)
```

The script points at the local lobby's gateway and uses the private gateway token from `.env`. The first
start of a sector packs its map archives (a few minutes, all cores); later starts are incremental.

## What this does not do

- **Players in different windows cannot see each other**, even in the overlap: each window is a separate
  world process. The handover point is the same for everybody, so players walking together hand over
  together.
- **The orchestrator only starts generated sectors.** With `GENERATED_SECTORS=MM` nothing else starts.
  With 676 sectors only occupied ones could run.
- **The client does not pick the next world.** The player returns to the world list and selects the world
  named in the message.
- **Map-square discovery gating (`CACHE_GATE_MAPS`) is a lobby feature.** A window's own JS5 is served by the
  world, so gating would need `DiscoveredMapFileProvider` wired into the world's JS5 server too.
- **The world map image** is written per window for the first 4,096 x 4,096 local tiles only.
- **Instances, dungeoneering, clans and other state keyed by tile** stay per window.
