# The 1,000,000 x 1,000,000 generated world

A 727 client can only address a 16,384 x 16,384 tile plane: tile coordinates are 14 bits, a map square
is `m<x>_<y>` with x and y up to 255, and region ids are 16 bits. One cache is therefore one plane, and
the client checks every archive it loads against the index checksums it received at login, so a cache
cannot change under a running client. The generated world gets its size from running **many planes**.

## Design

The world is one deterministic function of global tile coordinates (`WorldGenerator`: biomes, heights,
lakes, scenery). A **window** is a 16,384 x 16,384 slice of it, hosted by one world server with its own
cache. The client reloads its cache from the world it selects, which already exists as the *own-cache
world* flag: a world whose index CRCs differ from the lobby's is listed as serving its own cache and the
client fetches JS5 from it (`SocialGateway.servesDifferentCache`). A window is therefore an ordinary world.

```
global tile (gx, gy)  ->  window (wx, wy), local tile (gx - origin(wx), gy - origin(wy))
```

- **Windows overlap by 2,048 tiles** (`WorldWindow`): stride 14,336, 70 windows per axis, the last one
  clamped to the world edge (origin 983,616). Both windows render the overlap identically because the
  generator is a pure function of global coordinates (checked: 352 sampled overlap squares are byte
  identical between window 0 and window 1).
- **Handover happens in the middle of the overlap**, 1,024 tiles from either window's edge, so the client
  never sees the void at a window boundary. `WorldWindow.owner(global)` names the window that owns a tile.
- **Handover = log out to the lobby, pick the next world.** The player's global position is stored in the
  `window_positions` collection when they leave a world (`Player.realFinish`). The world message names the
  target world (`WorldWindow.worldId`: 2000 + wy * 70 + wx). On login a window world maps the stored global
  position into its own local coordinates and teleports the player there. The client cannot change cache
  mid-session (see above), so this relog is the cheapest handover the protocol allows.
- **Each window builds its own cache** from the shared source tree: `WORLD_WINDOW_X/Y` set the generator's
  origin, so `m<x>_<y>` is generated from global square `origin/64 + x`. The index metadata (names) is
  identical for every window, only the archive contents and checksums differ. `WindowTree` hard-links the
  shared tree into `<CACHE_BUILD_PATH>-tree` so that per-window writes (the world map image) never touch the
  base tree, and `MapMaterializer` is off in window mode.
- **Ports and ids are derived from the window**: world `2000 + wy * 70 + wx`, port `44000 + wy * 70 + wx`.

## Running a window

```bash
scripts/window-world.sh 0 0      # world 2000, port 44000
scripts/window-world.sh 1 0      # world 2001, port 44001
```

The script points at the local lobby's gateway and uses the private gateway token from `.env`. The first
start of a window packs 131,072 map archives (a few minutes, all cores); later starts are incremental.
Neighbouring windows should be started before a player reaches the overlap.

## What this does not do

- **Players in different windows cannot see each other**, even in the overlap: each window is a separate
  world process. The handover point is the same for everybody, so players walking together hand over
  together.
- **No orchestrator yet.** Windows are started by hand. An orchestrator would start (and pre-pack) the
  neighbours of any occupied window and stop empty ones. With 4,900 windows only occupied ones can run.
- **The client does not pick the next world.** The player returns to the world list and selects the world
  named in the message.
- **Map-square discovery gating (`CACHE_GATE_MAPS`) is a lobby feature.** A window's own JS5 is served by the
  world, so gating would need `DiscoveredMapFileProvider` wired into the world's JS5 server too.
- **The world map image** is written per window for the first 4,096 x 4,096 local tiles only.
- **Instances, dungeoneering, clans and other state keyed by tile** stay per window.
