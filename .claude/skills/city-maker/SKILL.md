---
name: city-maker
description: Make, edit or review a city in the Darkan generated world. Use this whenever the task touches data/cities, a building's walls, floors, roof, furniture, ladders or upper floors, a city's services (bank, merchant shops, barber, realtor) and their world map icons, the homes a city's realtor sells and who may build in them, making or remaking a city from a seed, a city's streets, or asks how a city or building looks - it holds the compact city JSON, the wall and floor styles, and a local view harness that renders every building floor and the whole city from above and at a tilt, plus the map, on a virtual display with no login.
---

# city-maker

A city is one JSON file, `data/cities/<id>.json`. Walls, windows, doors and floor tiles are derived from each building's fields, so the file holds only what a person decides. Load `darkan-code-standards` before touching Kotlin.

## Made from a seed

A city is made by `CityPlanner.make` from five numbers: `seed`, `centerX`, `centerY`, `halfSize` and its name. The same numbers always give the same city: streets, buildings, wall and roof styles, services, shopkeepers, the realtor, the homes for sale and guards. Nothing else is random.

- A city file that holds only `id`, `name`, `seed`, `centerX`, `centerY` and `halfSize` is a stub. The next world start makes the full city from it and writes the file back.
- `city.py remake <city> [seed]` turns a city back into a stub, with a new seed if one is given. `city.py new <name> <x> <y> <seed> [half]` writes a stub for a new city.
- A city on one of the four roads gets an avenue across it and gate guards on the road. A city at the crossroads gets gates on all four arms. A city off the roads gets both avenues and patrols only.
- An empty `data/cities` makes the world plan its cities from the world seed (`CityPlanner.plan`): the first at the spawn, the rest along the road arms in turn at a seeded distance. City seed is `citySeed(worldSeed, index)`. The first five names are fixed, later ones are built from the seed.
- A hand edit stays: a full file is never remade. `::cityverify` (and a log line at a debug start, "Cities against their seeds") says for each city whether it still matches its seed or was edited by hand.
- Remake when you want a different layout; edit by hand when you want one building changed.

## Loop

1. Read the city as text, not the raw file: `city.py plan <city>` shows the whole city in about 45 lines and `city.py building <city> <n>` shows one building floor by floor. Text is the cheapest way to know where things are.
2. Edit the JSON, one building or street at a time, so a bad view points at one change.
3. `city.py validate <city>` until it prints `OK`. It catches what a picture hides: a door that misses the street, furniture in a wall, an upper floor with no ladder.
4. Apply the edit (see "What an edit needs").
5. Look: `views.py shoot <city> b<n>` for the buildings you changed, then `views.py sheet <city> b<n>` and read the one sheet image. Shoot `city` and run `map` when the layout changed.
6. Fix what the views show and go back to 2.

Run both tools from the server root with `python3 -I .claude/skills/city-maker/scripts/<tool> ...`.

| `city.py` | Prints |
|---|---|
| `list` | every city with centre, size and building counts |
| `plan <city> [tiles per char]` | text map (`=` street, digit or letter = building index) and one line per building |
| `building <city> <n>` | each floor as a grid with a furniture legend |
| `validate [city...]` | `OK` or the problems; exit 1 on problems |
| `squares <city> [delete]` | saved map squares under the city |
| `remake <city> [seed]` | cuts the city file down to a stub so the next start makes it again |
| `new <name> <x> <y> <seed> [half]` | writes a stub for a new city |

| `views.py` | Does |
|---|---|
| `list <city>` | prints every view with its camera and writes `data/city-views/<city>.views.json` |
| `shoot <city> [view or prefix...]` | renders the views to `data/city-views/<city>/<view>.png`; a view whose world state, camera and cache are unchanged is skipped |
| `sheet <city> [view or prefix...]` | one contact sheet `sheet.jpg`, and prints which view is in which cell |
| `map <city>` | `map.png`, the city as the world map image stores it: floor and street colours, wall lines, furniture dots, north up |

## The view harness

`views.py` runs the native Lakan client (the same engine as the web client) in its offline capture mode under `xvfb-run`. Nothing logs in and the live world is not touched.

- **World state** is `data/city-views/<city>.state.json`: every object in and around the city (walls, furniture, ladders, scenery, dug roads). The world writes it at start, so it always matches the running server.
- **Camera** is one line per view in `<city>.views.json`: `tile`, `plane`, `yaw`, `pitch`, `zoom`, `roofs`. To keep a camera you tuned, edit the line and add `"pinned": true`; unpinned views are recomputed from the city file.
- `b<n>-roof` looks at the building from outside at 45 degrees with roofs shown. Use it for every roof change.
- Views per building floor: `b<n>-f<level>-top` (pitch 2980, the steepest the game camera allows, about 65 degrees) and `b<n>-f<level>-tilt` (pitch 2048, 45 degrees). The camera stands in the building on that level with roofs hidden, so floors above are not drawn. That is how a floor is checked without the one above covering it.
- City views: `city-top` and `city-tilt`, ground level with every floor shown.
- Terrain and floor tiles come from the packed cache `data/cache-cities`, so a floor or footprint change shows only after the restart that repacks it.

Reading the pictures:

- Read the sheet, not the single PNGs; a sheet of six views costs about what one full image does. Open a single PNG only to settle a detail.
- Judge in this order and stop at the first failure: walls closed and door on the street, floor right for the building, furniture inside and reachable, ladder on both levels, the building reads well from the tilt.
- In the far city views wood floors (`planks`, `darkwood`) can draw pale blue. That is the far renderer; judge floors in the building views.
- The furnace (`built_furnace`) does not draw in the harness yet. Check a smithy by `city.py building`, not by the picture.
- A furniture `loc` is looked up by name in the `Loc` constants (`BuildingBuilder.locId`); a name that is not there is left out without a warning. After a debug start, `data/city-views/<city>.state.json` lists every loc the world really spawned, so check there that a piece (the furnace is id 73931) is present.

## JSON

```json
{"kind": "house", "x": 7480, "y": 7486, "width": 7, "depth": 7, "door": "WEST", "floors": 2, "wall": "brick", "floor": "planks", "roof": "slate",
 "furniture": [{"loc": "table", "x": 3, "y": 3, "rotation": 0}, {"loc": "ladder", "x": 5, "y": 5, "rotation": 0}, {"loc": "laddertop", "x": 5, "y": 5, "rotation": 0, "level": 1}]}
```

- Building, street and resident `x`, `y` are sector tiles. Furniture `x`, `y` count from the building's south-west corner; `1..width-2` and `1..depth-2` are inside the walls.
- `door` is the side the door is on (`NORTH`, `EAST`, `SOUTH`, `WEST`), in the middle of that side, and it must open onto a street.
- `floors` is 1 to 3. `level` on furniture and residents is the floor, left out for the ground.
- `loc` is a loc gameval name from `cache-source/gamevals/loc.json`. `rotation` is 0 to 3. `shape` is left out for ordinary objects.
- `kind` labels the building. A service kind (see Services) also puts its icon on the world map; the furniture list is still what gets placed.
- `forSale: true` on a `house` puts it on the realtor's books (see Realtor and homes). It is left out everywhere else.
- `roof` is a roof style, or `none` for no roof. A building with no `roof` field gets one picked at the next start and written back to the file.
- Residents: `{"npc": <id>, "x", "y", "role"}` with role `banker`, `shopkeeper`, `barber`, `realtor`, `guard` or `patrol`. A shopkeeper also has `"shop": "<key>"`, the name its stock is saved under, unique in the world (the maker writes `<city>_<kind>`).

| `wall` | Looks like |
|---|---|
| `farmhouse`, `lightwood`, `darkwood` | timber in three tones |
| `brick` | red brick, painted brick upstairs |
| `plaster`, `greyplaster` | timber frame with plaster |
| `stone`, `whitestone` | grey city stone, pale castle stone |

| `floor` | Looks like |
|---|---|
| `planks`, `darkwood` | brown boards, golden-brown boards |
| `cobble`, `sandstone` | grey-tan cobbles, tan cobbles |
| `flagstone`, `stone`, `slate` | grey slabs, grey-purple stone, dark charcoal |
| `marble`, `cream` | white tiles, bright cream tiles |
| `terracotta`, `golden` | salmon, golden yellow |

| `roof` | Looks like |
|---|---|
| `slate`, `greyslate`, `darkslate` | grey slate in three tones |
| `blueslate`, `redslate` | blue-grey slate, red tiles |
| `thatch`, `straw`, `reed`, `hay` | thatch in four tones |
| `wood` | dark wooden boards |
| `none` | no roof |

To try a floor that is not in the table, write the overlay's gameval name, for example `"floor": "overlay_172"`, shoot the building and add it to `FloorStyle` once it looks right. Two overlays that looked right in the definitions (55 and 38) draw sky blue, which is why a new one is always looked at first.

## Roofs

Roofs follow the vanilla towns (Ardougne, Varrock, Falador): one roof kit loc placed in several shapes on the level above the top floor.

- Eaves (shape 18, corners 19) sit one tile outside the walls, the slope (shape 12, corners 16) sits on the wall tiles and the flat top (shape 17) fills the inside.
- Where the eaves of two buildings meet on one tile, the first building in the file keeps it.
- A roof kit must have models for shapes 12, 16, 17, 18 and 19. Add a kit to `RoofStyle` in `CityStyles.kt` and to `ROOFS` in `city.py`.
- The client hides the roof when the player stands inside, because building tiles carry the roof flag.

## Services

| `kind` | Map icon | Inside |
|---|---|---|
| `bank` | Bank | booths and bankers |
| `smithy` | Furnace and Anvil | furnace and anvil |
| `general_store`, `sword_shop`, `archery_shop`, `magic_shop`, `clothes_shop`, `food_shop`, `armour_shop`, `fishing_shop`, `staff_shop` | the matching shop icon | a counter and a shopkeeper |
| `barber` | Hairdresser | chairs, mirrors and the barber |
| `realtor` | Estate agent | a desk, bookcases and the realtor |

- The icon is the vanilla icon loc for that service (for example `general_store_icon`), placed as a ground decoration in the middle of the building. The world map and the map key pick it up from the map image.
- Shopkeepers, bankers and the barber are `CityResident` NPCs: they cannot be attacked or hurt and they do not walk.
- A city with no shop buildings at all gets them when it is made or loaded: houses beyond the first three are turned into shops, in the order of the table. To add one by hand, change a house's `kind`, give it a counter and add a `shopkeeper` resident inside it with its own `shop` key; `city.py validate` checks both.
- To add a new service, add it to `Service` in `CityStyles.kt` with its icon loc.

## Realtor and homes

Every city has one `realtor` building and two or three homes for sale. Both are added by `CityPlanner.upgrade(generator, city)`, which runs when a city is made and when an old file is loaded, so the same seed gives the same office and the same homes.

- The office is an extra building placed on a free street front from `Random(seed + REALTOR_SALT)`. If no front is free the city's `market` becomes the office, or failing that its last house.
- The homes are the first two or three `house` buildings (the count comes from the seed). Each gets `"forSale": true` and loses its furniture except ladders, so the buyer starts with an empty shell. A city that already has a home for sale is left alone, so to pick the homes by hand set `forSale` yourself; `city.py validate` checks a home is an empty house.
- A home is the house's footprint on every floor it has, and nothing else. City land cannot be claimed in claim mode; the realtor is the only way to own it. Land within 100 tiles of a city's edge (`GeneratedWorld.CITY_CLAIM_MARGIN`, `cityClaimZoneTouches`) cannot be claimed either, so a city is always ringed by open land; claims made before the rule stay, but any edit that touches the buffer is refused. Trees can't be planted or punched in the city or its buffer either (`ClaimManager.canGrowTrees`), except inside a home the player owns.
- The realtor (`Npc.city_realtor`, `CityRealtor.kt`) opens the `realtor` interface (`View-homes`, or through `Talk-to`). It lists only that city's homes, so a home is bought, priced and sold only at its own city's realtor.
- A home nobody owns costs a flat 10,000 coins (`CityHomes.REALTOR_PRICE`). The owner can list it at any price they type in, change the price, take it off the market, or sell it back to the realtor for 5,000 (`BUYBACK_PRICE`), which puts it back on the books at 10,000.
- When another player buys a listed home the coins wait with the realtor; the seller is paid the next time they talk to that realtor or open the list.
- Owning a home is what `ClaimManager.claimAt` reports for its tiles, so the owner can build, pick up furniture, lock doors and use chests there like on a land claim, and nobody else can. Furniture and chest contents left inside go with the home when it is sold.
- Ownership, asking prices and unpaid sales are saved in `data/city-homes/sector-<world>.json` on every change. The key is `<city>_<x>_<y>` of the house, so moving a home's building in the JSON orphans its owner; deleting the file returns every home to the realtor.
- Interface rows are pushed by the server, one `Cs2.realtor_home_add(title, status, button, button, button)` per home; each row is five ccs (title, status, three buttons), so the clicked row is `slotId / 5` and the button `slotId % 5 - 2`.

## Church

Every city has one `church`: the Lumbridge church stamped from `core/src/main/resources/city/lumbridge_church.json` (`ChurchTemplate`), 12 by 14, always with `door` `WEST`, so it needs a street on its west side at rows 6 and 7 of its footprint.

- Its `wall`, `floor`, `roof` and `furniture` fields are not used: every object and the floor tiles of all four levels come from the template. To move a church by hand change only `x` and `y`, and move its eight residents (`priest`, `greeter`, `congregant`) by the same amount.
- `CityPlanner.withChurch` adds it to a city that has none: the free lot nearest the centre, or else the lot whose buildings are cheapest to move (house, market, home for sale, guardhouse, smithy, barber, realtor, bank). Each moved building is built again by `rebuilt` on the nearest free street front. Shops are never moved. A moved home for sale changes its key in `data/city-homes`, so check that file before the church is first added to a world where homes are owned.
- The service, the sermon and the tithe are in `ChurchService.kt`, `Sermon.kt` and `Tithes.kt` beside the other city code. A tithe is shared between the city's shopkeepers with `CityShop.receive`.

## City shops are merchants

A city shopkeeper is a wandering merchant that cannot be hurt and stays in its shop (`CityShopkeeper.kt`). It uses a merchant look (`wandering_merchant_01` to `_50`, picked from the city seed) and the merchant shop window.

- A shop starts with the default stock of its kind and 0 coins. Its only coins are what players pay it, so it cannot buy anything until somebody has bought from it.
- The default stock is copied from a vanilla shop when the shopkeeper spawns, picked by the end of the `shop` key (`DEFAULT_STOCK` in `CityShopkeeper.kt`):

| Key ends with | Stock of |
|---|---|
| `general_store` | `varrock_general_store` |
| `sword_shop` | `varrock_sword_shop` |
| `archery_shop` | `lowes_archery_emporium` |
| `magic_shop` | `auburys_rune_shop` |
| `clothes_shop` | `thessalias_clothing` |
| `food_shop` | `wydins_food_store` |
| `armour_shop` | `horviks_armour_shop` |
| `fishing_shop` | `gerrants_fishy_business` |
| `staff_shop` | `zaffs_superior_staves` |

- A key with none of these endings gets no default stock. To change what a kind sells, point it at another file in `data/items/shops` or edit that file's `items`.
- It buys anything for the item's value while it has coins, and sells at 1.2 times the value. Coins it is paid go into its purse.
- Default stock below its starting amount comes back one at a time every 50 ticks, as in a vanilla shop. It never drops back down: extra a player sold stays.
- An item that is not default stock never restocks and never decays. It stays until a player buys it, and its slot is removed at zero. A shop holds 40 different items, default stock included.
- Each shopkeeper has its own stock and purse, saved per keeper (his service key plus his tile) in `data/city-shops/sector-<world>/<kind>_<x>_<y>_<plane>.json` on every sale, purchase and restock, so it survives restarts. Deleting that file (with the world stopped) puts the shop back to its default stock and 0 coins.
- Remaking a city keeps shop stock, because the key (`<city>_<kind>`) does not change.
- The general store also sells three estate deeds, refilled one at a time like a wandering merchant's.
- The shop kind sets the icon, the floor, the title and the default stock. It does not limit what the shop buys.

## Floors and levels

- A building's floor is a terrain overlay on every level it has; outside a building the ground or the street stays. Match it to the building: boards for homes, slabs for a guardhouse, slate for a smithy, tiles for a bank, cobbles for a market.
- Upper floors are reached by ladders only. Each level above the ground needs a `ladder` on the level below and a `laddertop` on the same tile one level up, with a free tile beside it on both levels.
- Upper floors get walls and windows all round and no door.
- Opening or closing a door of the map or a city is never saved (`WorldDelta.swing`, used by `Doors.java`), so every such door is closed again after a restart. Doors a player built keep their state.

## What an edit needs

| Changed | Needed |
|---|---|
| Kotlin | first `JAVA_HOME=/home/jesse/.jdks/jdk-26.0.2.1+1 flock /tmp/darkan-build.lock nice -n 10 ./gradlew :world:installDist --offline --console=plain -q` |
| Anything | `systemctl --user restart darkan-world-22`, then wait for `SocialClient connected` in `logs/world-22.log` (about 90 seconds). The start rewrites the world state, the city terrain squares and the map image, and repacks only what changed. |
| A new city file | the same; it must sit inside the map area to get a label |

- Never restart the lobby or world 123.
- An empty `data/cities` makes the world plan its cities again from the world seed.

## In the live client

The harness covers looks. To walk a city, use the web client (`https://devmakerstudio.com/open-anarchy/play`, world 22): the user signs in, then `::city <name>` teleports and `::cityshot <city> <n|all> <top|tilt> [level]` fixes the camera the same way, with `::cityshot off` to release it.

## Where the code is

| Part | File |
|---|---|
| Model, styles, services | `core/.../city/City.kt`, `CityStyles.kt` (add a style there and in `city.py`) |
| Walls, roofs, icons and default furniture | `core/.../city/BuildingBuilder.kt` |
| Default layout, roofs and services for old files | `core/.../city/CityPlanner.kt` (`upgrade`) |
| Invulnerable residents | `world/.../content/world/generated/CityResident.kt`, `Barber.kt` |
| Realtor, the homes interface and its dialogue | `world/.../content/world/generated/CityRealtor.kt` |
| Home ownership, prices and the saved ledger | `world/.../content/claims/CityHomes.kt`, read by `ClaimManager.claimAt` and `canConstruct` |
| Realtor office and homes for sale | `CityPlanner.withRealtor`, `withHomes` |
| City shops and their saved stock | `world/.../content/world/generated/CityShopkeeper.kt`, on top of `npcs/merchant/MerchantShop.kt` |
| Making a city from a seed | `CityPlanner.make`, `plan`, `stub`; stubs are filled in by `CityRegistry.load` |
| Floor tiles | `WorldGenerator.buildingFloors`, written to the tree by `core/.../cache/CityTerrain.kt` |
| Map image | `core/.../cache/WorldMapImage.kt` |
| World state export | `world/.../content/world/generated/CityViewExport.kt` |
| Spawning, commands | `world/.../content/world/generated/Cities.kt`, `CityCommands.kt` |
