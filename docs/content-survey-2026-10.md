# Content survey, 30 Sep to 3 Oct 2026

Everything below is in `server/dev-123` (world 123 "Darkan") and the cache assets in `server-cache/dev-123`.
Source: `git diff dev..dev-123` (158 files) and the session reports. "Verified" means seen working in a running server;
nothing here has been played through in game yet, only compiled and started.

## Generated world (world generation session)
- `WorldGenerator`, `GeneratedWorld`, `MapGenerator`, `MapMaterializer`: biomes (plains, desert, forest, wilderness), water, lava,
  scenery per biome, spawn at 3222,3218 at the time of writing (the world origin is now 0,0; see src/implementation/world-generation.md section 3b). Squares are generated when the cache has no saved file for them, and saved when explored.
- `WorldMapImage`, `worldmapdata`: the world map image for the 64 x 64 square home area.
- `CACHE_GENERATE_SIZE` (this session): a dev subsample, only N tiles around the spawn are built, the rest is void.
- Sectors (called windows in the code until the rename): `WorldWindow`, `WindowTree`, `WindowOrchestrator`, `scripts/window*.sh`,
  `WindowRules`, `docs/infinite-world.md`. Verified: overlap terrain identical between neighbours, orchestrator endpoint, gated JS5.
  Not tested: edge hop, position save/restore, handover.
- `GeneratedWorldRules`, `GeneratedWildlife` (animals, fishing spots, implings, 1/25 roll), `GeneratedMusic` + `Biome`/`BiomeMusic`
  (music by biome), `BiomeCommands` (`biome`), `DesertRocks`, `TreePunching`, `TreePlanting`, `FishingSpotDepletion`, tree roots.
- `PackThreads`, `scripts/low-impact.sh`, the cache pack lock: throttled generation.
- `MapDiscovery`, `DiscoveredMapFileProvider`, `LoginProbe`: map fog gated per discovered square, headless login probe.

## World map (world map session)
- `WorldMapFog`, `WorldMapWaypoints`, `WorldMapClickHandler`, the `worldmap_*` client scripts and renamed varps (`worldmap_fog_0..7`,
  `worldmap_waypoint_0..7`, `worldmap_waypoint_mode`).

## Claims (claims session)
- `Claim`, `ClaimManager`, claim border locs (`claim_border_*`, `claim_highlight`), the `claim_settings` interface (1324),
  build and object-option checks in `WalkHandler`, `IfOnObjectHandler`, `OpObjHandler`, `DwarfMultiCannon`, `Firemaking`, claim PvP rules in `Player`.

## Merchant (Merchant NPC session)
- `MerchantNPC`, `MerchantShop`, `WanderingMerchant`, `Shop.java` changes, 50 `wandering_merchant_*` NPC definitions, combat def.

## Construction (implement construction session)
- `FurnitureBuilding`, `BuiltFurniture` (destroy and repair), `built_wooden_*` locs, 16 new models, design documents in `docs/designs/construction`.

## NPC spawning and implings (NPC spawning session)
- `NPCSpawns`, `ItemSpawns`, `Impling`: spawn rate 1/25, permanent death for generated wildlife, forest implings, no ground items and no
  hardcoded implings in generated worlds.

## Music (music session)
- `Music`, `MusicsManager`, `GeneratedMusic`: genre by biome.

## Other
- `Entity`, `Player`, `Skills`, `Woodcutting`, `Chunk`, `ChunkManager`, `NPCCombat`, `NPCBodyMeshModifier`, `GameFrame`, `WorldEncoder`,
  `WorldServer`, `SocialClient`, `GatewayProtocol`, lobby `SocialGateway`/`Routes`: supporting changes for the above.
- `WORLD_NO_NPCS` (this session): removes every NPC as it is created. Turn it off to get merchants, wildlife and spawns back.

## Known gaps
- The saved map squares in the cache were flat plains, so none of the biome content showed. Regenerating them is done for `stage-22`'s
  4000 x 4000 area; world 123 still needs it.
- The music session could not compile because of an `AudioCommands.kt` error mid-edit; the merged tree compiles clean.
