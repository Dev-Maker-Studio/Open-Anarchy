package org.darkan.core.city

import org.darkan.core.gameval.gen.Npc
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.util.Random

object CityPlanner {
    private const val SPAWN_HALF = 40
    private const val HALF = 34
    private const val PLAZA_HALF = 4
    private const val RING = 20
    private const val AVENUE = 3
    private const val RING_WIDTH = 2
    private const val MIN_SIDE = 5
    private const val SIDE_SPREAD = 5
    private const val ATTEMPTS = 200
    private const val COMPACT_ATTEMPTS = 400
    private const val HOUSES = 18
    private const val SCAN_STEP = 6
    private const val MAP_MARGIN = 80
    private const val GUARD_OFFSET = 3
    private const val ARMS = 4
    private const val EDGE_MARGIN = 2
    private const val CHURCH_EDGE = 1
    private const val HOME_COST = 4
    private const val SHOP_COST = 12

    private const val STYLE_SALT = 9001L
    private const val TWO_FLOOR_HOUSE_PERCENT = 40
    private const val PERCENT = 100
    private const val TALL = 2
    private const val LOW = 1

    private const val ROOF_SALT = 4409L
    private const val MIN_HOUSES = 3
    private const val SEED_STEP = 7919L
    private const val CITY_SALT = 7271L
    private const val NAME_SALT = 131L
    private const val MIN_HOME_STYLES = 2
    private const val HOME_STYLE_SPREAD = 2
    private const val MIN_ROOF_STYLES = 3
    private const val ROOF_STYLE_SPREAD = 2
    private const val LEGACY_SHOP_PREFIX = "city_"
    private const val REALTOR_SALT = 6143L
    private const val REALTOR_ATTEMPTS = 400
    private const val HOME_SALT = 2713L
    private const val MIN_HOMES = 2
    private const val HOME_SPREAD = 2
    const val CITIES = 40
    const val MIN_BIOMES = 2
    const val MAX_BIOMES = 5
    private const val BIOME_TILES = WorldGenerator.BIOME_SPACING
    private const val EDGE_BIOMES = 0.05
    private const val NEAR_BIOMES = 3.5
    private const val NEAR_ATTEMPTS = 50
    private const val PLACE_ATTEMPTS = 100
    private const val CHILDREN = 3
    private const val EXPAND_SALT = 5113L

    private val names = listOf("Velneir", "Aldmere", "Brackenholt", "Sarnwick", "Dunhollow")
    private val nameStarts = listOf("Ar", "Bel", "Cor", "Dra", "El", "Fen", "Gar", "Hal", "Ist", "Kel", "Lor", "Mor", "Nar", "Or", "Pel", "Ren", "Sel", "Tar", "Ul", "Var", "Wen", "Yar")
    private val nameMiddles = listOf("a", "an", "e", "en", "i", "o", "ow", "u", "ar", "el")
    private val nameEnds = listOf("mere", "holt", "wick", "hollow", "ford", "stead", "gate", "haven", "moor", "reach", "dale", "burn")
    private val civicKinds = setOf(BuildingBuilder.BANK, BuildingBuilder.GUARDHOUSE)

    private class Palette(seed: Long) {
        private val random = Random(seed + STYLE_SALT)
        val civic: WallStyle = WallStyle.entries[random.nextInt(WallStyle.entries.size)]
        val homes: List<WallStyle> = pick(WallStyle.entries - civic, MIN_HOME_STYLES + random.nextInt(HOME_STYLE_SPREAD))
        val civicRoof: RoofStyle = RoofStyle.entries[random.nextInt(RoofStyle.entries.size)]
        val roofs: List<RoofStyle> = pick(RoofStyle.entries - civicRoof, MIN_ROOF_STYLES + random.nextInt(ROOF_STYLE_SPREAD))

        private fun <T> pick(from: List<T>, count: Int): List<T> {
            val pool = from.toMutableList()
            return List(minOf(count, pool.size)) { pool.removeAt(random.nextInt(pool.size)) }
        }
    }

    private class Road(val arm: Int, val along: Int)

    fun citySeed(worldSeed: Long, index: Int): Long = worldSeed * SEED_STEP + CITY_SALT + index

    fun cityName(seed: Long, index: Int): String = names.getOrNull(index) ?: Random(seed + NAME_SALT).let { random ->
        nameStarts[random.nextInt(nameStarts.size)] + nameMiddles[random.nextInt(nameMiddles.size)] + nameEnds[random.nextInt(nameEnds.size)]
    }

    fun stub(name: String, seed: Long, centerX: Int, centerY: Int, halfSize: Int): City =
        City(name.lowercase().replace(' ', '_'), name, seed, centerX, centerY, halfSize, 0, emptyList(), emptyList(), emptyList())

    fun isStub(city: City): Boolean = city.streets.isEmpty() && city.buildings.isEmpty()

    private fun grow(generator: WorldGenerator, random: Random, centres: MutableList<Pair<Int, Int>>, mapMin: Int, mapMax: Int, count: Int) {
        var parent = 0
        while (parent < centres.size && centres.size < count) {
            val (px, py) = centres[parent++]
            var children = 0
            for (attempt in 0 until PLACE_ATTEMPTS) {
                if (children >= CHILDREN || centres.size >= count) break
                val ceiling = if (attempt < NEAR_ATTEMPTS) NEAR_BIOMES else MAX_BIOMES.toDouble()
                val biomes = MIN_BIOMES + EDGE_BIOMES + random.nextDouble() * (ceiling - MIN_BIOMES - EDGE_BIOMES)
                val angle = random.nextDouble() * 2 * Math.PI
                val x = px + Math.round(Math.cos(angle) * biomes * BIOME_TILES).toInt()
                val y = py + Math.round(Math.sin(angle) * biomes * BIOME_TILES).toInt()
                if (x - HALF < mapMin + MAP_MARGIN || y - HALF < mapMin + MAP_MARGIN || x + HALF > mapMax - MAP_MARGIN || y + HALF > mapMax - MAP_MARGIN) continue
                if (centres.any { (cx, cy) -> Math.hypot((cx - x).toDouble(), (cy - y).toDouble()) < MIN_BIOMES * BIOME_TILES }) continue
                if (!suitable(generator, x, y, HALF)) continue
                centres += x to y
                children++
            }
        }
    }

    private fun founded(generator: WorldGenerator, worldSeed: Long, centres: List<Pair<Int, Int>>, first: Int, taken: MutableSet<String>): List<City> =
        centres.drop(first).mapIndexed { offset, (x, y) ->
            val index = first + offset
            val seed = citySeed(worldSeed, index)
            val name = cityName(seed, index).let { if (taken.add(it)) it else "$it ${index + 1}".also(taken::add) }
            make(generator, stub(name, seed, x, y, if (index == 0) SPAWN_HALF else HALF))
        }

    fun plan(generator: WorldGenerator, worldSeed: Long, spawnX: Int, spawnY: Int, mapMin: Int, mapMax: Int, count: Int = CITIES): List<City> {
        val centres = arrayListOf(spawnX to spawnY)
        grow(generator, Random(worldSeed * SEED_STEP + EXPAND_SALT), centres, mapMin, mapMax, count)
        return founded(generator, worldSeed, centres, 0, HashSet())
    }

    fun extend(generator: WorldGenerator, worldSeed: Long, cities: List<City>, mapMin: Int, mapMax: Int, count: Int): List<City> {
        val centres = cities.mapTo(ArrayList()) { it.centerX to it.centerY }
        grow(generator, Random(worldSeed * SEED_STEP + EXPAND_SALT + cities.size), centres, mapMin, mapMax, count)
        return founded(generator, worldSeed, centres, cities.size, cities.mapTo(HashSet()) { it.name })
    }

    fun make(generator: WorldGenerator, stub: City): City {
        val half = stub.halfSize.takeIf { it > 0 } ?: HALF
        val road = roadThrough(generator, stub.centerX, stub.centerY, half)
        val crossroads = road != null && road.along <= half
        val random = Random(stub.seed)
        val styles = Random(stub.seed + STYLE_SALT)
        val palette = Palette(stub.seed)
        val streets = streets(stub.centerX, stub.centerY, half, road?.arm?.takeUnless { crossroads })
        val landmarks = withFarm(generator, withChurch(generator, stub.copy(halfSize = half, streets = streets)))
        val buildings = ArrayList(landmarks.buildings)
        val residents = ArrayList(landmarks.residents)
        fun place(kind: String) {
            for (attempt in 0 until ATTEMPTS + COMPACT_ATTEMPTS) {
                val candidate = candidate(generator, random, styles, palette, stub.id, kind, stub.centerX, stub.centerY, half, streets, buildings, attempt >= ATTEMPTS) ?: continue
                buildings += candidate.first
                residents += candidate.second
                break
            }
        }
        listOf(BuildingBuilder.BANK, BuildingBuilder.SMITHY, BuildingBuilder.MARKET, BuildingBuilder.GUARDHOUSE).forEach(::place)
        val civic = withRealtor(generator, landmarks.copy(buildings = buildings.toList(), residents = residents.toList()))
        buildings += civic.buildings - buildings.toSet()
        residents += civic.residents - residents.toSet()
        repeat(HOUSES) { place(BuildingBuilder.HOUSE) }
        residents += guards(generator, road, crossroads, stub.centerX, stub.centerY, half)
        return upgrade(generator, stub.copy(halfSize = half, baseHeight = generator.heightAt(stub.centerX, stub.centerY), streets = streets, buildings = buildings, residents = residents))
    }

    fun upgrade(generator: WorldGenerator, city: City): City = withFarm(generator, withChurch(generator, withHomes(withRealtor(generator, upgrade(city)))))

    private class Site(val x: Int, val y: Int, val cleared: List<Building>, val cost: Int, val distance: Int)

    private class Lot(val x: Int, val y: Int, val width: Int, val depth: Int, val door: Side, val distance: Int)

    private val movableCosts = mapOf(
        BuildingBuilder.HOUSE to 1, BuildingBuilder.MARKET to 2, BuildingBuilder.GUARDHOUSE to 6, BuildingBuilder.SMITHY to 6,
        Service.BARBER.kind to 8, Service.REALTOR.kind to 10, BuildingBuilder.BANK to 10,
    )

    private fun withChurch(generator: WorldGenerator, city: City): City {
        if (city.buildings.any { it.kind == ChurchTemplate.KIND }) return city.copy(residents = city.residents.filterNot { it.role == ChurchTemplate.GREETER })
        for (site in churchSites(generator, city).sortedWith(compareBy({ it.cost }, { it.distance }, { it.x }, { it.y }))) {
            val cleared = city.copy(
                buildings = city.buildings - site.cleared.toSet() + ChurchTemplate.building(site.x, site.y),
                residents = city.residents.filterNot { resident -> site.cleared.any { it.touches(resident.x, resident.y, resident.x, resident.y) } } + ChurchTemplate.residents(site.x, site.y),
            )
            return site.cleared.fold<Building, City?>(cleared) { moved, building -> moved?.let { rebuilt(generator, it, building) } } ?: continue
        }
        return city
    }

    private val farmMovableCosts = movableCosts - setOf(Service.REALTOR.kind, BuildingBuilder.BANK)

    private fun moveCost(building: Building): Int? = if (building.isForSale) HOME_COST else movableCosts[building.kind]

    private val droppableKinds = setOf(BuildingBuilder.HOUSE, BuildingBuilder.MARKET)

    private fun withFarm(generator: WorldGenerator, city: City): City {
        if (!FarmTemplate.inCity(city.seed) || city.buildings.any { it.kind == FarmTemplate.KIND }) return city
        val tiers = sequenceOf<(Building) -> Int?>(
            { building -> farmMovableCosts[building.kind].takeUnless { building.isForSale } },
            ::moveCost,
            { building -> moveCost(building) ?: SHOP_COST.takeIf { Service.of(building.kind)?.keeper == true } },
        )
        for (cost in tiers) placedFarm(generator, city, farmSites(generator, city, cost))?.let { return it }
        return city
    }

    private fun placedFarm(generator: WorldGenerator, city: City, sites: List<Site>): City? {
        for (site in sites.sortedWith(compareBy({ it.cost }, { -it.distance }, { it.x }, { it.y }))) {
            val cleared = city.copy(
                buildings = city.buildings - site.cleared.toSet() + FarmTemplate.building(site.x, site.y),
                residents = city.residents.filterNot { resident -> site.cleared.any { it.touches(resident.x, resident.y, resident.x, resident.y) } } + FarmTemplate.residents(site.x, site.y),
            )
            return site.cleared.fold<Building, City?>(cleared) { moved, building -> moved?.let { rebuilt(generator, it, building) ?: it.takeIf { building.kind in droppableKinds && !building.isForSale } } } ?: continue
        }
        return null
    }

    private fun farmSites(generator: WorldGenerator, city: City, cost: (Building) -> Int?): List<Site> {
        val width = FarmTemplate.width
        val depth = FarmTemplate.depth
        val reach = city.halfSize - CHURCH_EDGE
        val sites = ArrayList<Site>()
        for (x in city.centerX - reach..city.centerX + reach - width + 1) for (y in city.centerY - reach..city.centerY + reach - depth + 1) {
            val maxX = x + width - 1
            val maxY = y + depth - 1
            if (city.streets.any { it.touches(x, y, maxX, maxY) } || generator.roadTouches(x - 1, y - 1, maxX + 1, maxY + 1)) continue
            val cleared = city.buildings.filter { it.touches(x, y, maxX, maxY, 1) }
            val costs = cleared.map { cost(it) ?: -1 }
            if (costs.any { it < 0 }) continue
            sites += Site(x, y, cleared, costs.sum(), Math.abs(x + width / 2 - city.centerX) + Math.abs(y + depth / 2 - city.centerY))
        }
        return sites
    }

    private fun churchSites(generator: WorldGenerator, city: City): List<Site> {
        val width = ChurchTemplate.width
        val depth = ChurchTemplate.depth
        val reach = city.halfSize - CHURCH_EDGE
        val sites = ArrayList<Site>()
        for (x in city.centerX - reach..city.centerX + reach - width + 1) for (y in city.centerY - reach..city.centerY + reach - depth + 1) {
            val maxX = x + width - 1
            val maxY = y + depth - 1
            val entrance = y + ChurchTemplate.ENTRANCE_Y
            if ((entrance until entrance + ChurchTemplate.ENTRANCE_ROWS).any { row -> city.streets.none { it.touches(x - 1, row, x - 1, row) } }) continue
            if (city.streets.any { it.touches(x, y, maxX, maxY) } || generator.roadTouches(x, y - 1, maxX + 1, maxY + 1)) continue
            val cleared = city.buildings.filter { it.touches(x, y, maxX, maxY, 1) }
            val costs = cleared.map { (if (it.isForSale) HOME_COST else movableCosts[it.kind]) ?: return@map null }
            if (null in costs) continue
            sites += Site(x, y, cleared, costs.sumOf { it ?: 0 }, Math.abs(x + width / 2 - city.centerX) + Math.abs(y + depth / 2 - city.centerY))
        }
        return sites
    }

    private fun rebuilt(generator: WorldGenerator, city: City, old: Building): City? {
        val reach = city.halfSize - EDGE_MARGIN
        val lots = ArrayList<Lot>()
        for ((width, depth) in setOf(old.width to old.depth, old.depth to old.width)) {
            for (x in city.centerX - reach..city.centerX + reach - width + 1) for (y in city.centerY - reach..city.centerY + reach - depth + 1) {
                val maxX = x + width - 1
                val maxY = y + depth - 1
                if (city.streets.any { it.touches(x, y, maxX, maxY) } || city.buildings.any { it.touches(x, y, maxX, maxY, 1) }) continue
                if (generator.roadTouches(x - 1, y - 1, maxX + 1, maxY + 1)) continue
                val door = Side.entries.firstOrNull { side ->
                    val (doorX, doorY) = BuildingBuilder.doorTile(x, y, width, depth, side)
                    city.streets.any { it.touches(doorX + side.dx, doorY + side.dy, doorX + side.dx, doorY + side.dy) }
                } ?: continue
                lots += Lot(x, y, width, depth, door, Math.abs(x - old.x) + Math.abs(y - old.y))
            }
        }
        val lot = lots.minWithOrNull(compareBy({ it.distance }, { it.x }, { it.y }, { it.width })) ?: return null
        val plan = BuildingBuilder.plan(old.kind, lot.x, lot.y, lot.width, lot.depth, lot.door, old.levels, city.id, city.seed)
        val furniture = if (old.isForSale) plan.furniture.filter { it.loc == BuildingBuilder.LADDER || it.loc == BuildingBuilder.LADDER_TOP } else plan.furniture
        return city.copy(
            buildings = city.buildings + old.copy(x = lot.x, y = lot.y, width = lot.width, depth = lot.depth, door = lot.door.name, furniture = furniture),
            residents = city.residents + plan.residents,
        )
    }

    private fun withRealtor(generator: WorldGenerator, city: City): City {
        val kind = Service.REALTOR.kind
        if (city.buildings.any { it.kind == kind }) return city
        val random = Random(city.seed + REALTOR_SALT)
        val palette = Palette(city.seed)
        repeat(REALTOR_ATTEMPTS) {
            val (office, staff) = candidate(generator, random, random, palette, city.id, kind, city.centerX, city.centerY, city.halfSize, city.streets, city.buildings) ?: return@repeat
            return city.copy(buildings = city.buildings + office.copy(floor = Service.REALTOR.floor.id, roof = palette.civicRoof.id), residents = city.residents + staff)
        }
        val index = city.buildings.indexOfLast { it.kind == BuildingBuilder.MARKET }.takeIf { it >= 0 } ?: city.buildings.indexOfLast { it.kind == BuildingBuilder.HOUSE }
        if (index < 0) return city
        val old = city.buildings[index]
        val plan = BuildingBuilder.plan(kind, old.x, old.y, old.width, old.depth, Side.valueOf(old.door), old.levels, city.id, city.seed)
        val buildings = city.buildings.toMutableList()
        buildings[index] = old.copy(kind = kind, floor = Service.REALTOR.floor.id, furniture = plan.furniture)
        return city.copy(buildings = buildings, residents = city.residents + plan.residents)
    }

    private fun withHomes(city: City): City {
        if (city.buildings.any { it.isForSale }) return city
        val count = MIN_HOMES + Random(city.seed * SEED_STEP + HOME_SALT).nextInt(HOME_SPREAD)
        val listed = city.buildings.indices.filter { city.buildings[it].kind == BuildingBuilder.HOUSE }.take(count).toSet()
        return city.copy(buildings = city.buildings.mapIndexed { index, building ->
            if (index in listed) building.copy(forSale = true, furniture = building.furniture.filter { it.loc == BuildingBuilder.LADDER || it.loc == BuildingBuilder.LADDER_TOP }) else building
        })
    }

    fun upgrade(city: City): City {
        val palette = Palette(city.seed)
        val roofs = Random(city.seed + ROOF_SALT)
        val buildings = city.buildings.toMutableList()
        val residents = city.residents.mapTo(ArrayList()) { resident ->
            val shop = resident.shop ?: return@mapTo resident
            if (shop.startsWith(LEGACY_SHOP_PREFIX)) resident.copy(shop = "${city.id}_${shop.removePrefix(LEGACY_SHOP_PREFIX)}", npc = keeperNpc(city.seed, shop.removePrefix(LEGACY_SHOP_PREFIX))) else resident
        }
        val missing = Service.shops.takeIf { shops -> buildings.none { building -> shops.any { it.kind == building.kind } } }.orEmpty()
        val houses = buildings.indices.filter { buildings[it].kind == BuildingBuilder.HOUSE }
        for ((service, index) in missing.zip(houses.drop(MIN_HOUSES))) {
            val house = buildings[index]
            val plan = BuildingBuilder.plan(service.kind, house.x, house.y, house.width, house.depth, Side.valueOf(house.door), house.levels, city.id, city.seed)
            buildings[index] = house.copy(kind = service.kind, floor = service.floor.id, furniture = plan.furniture)
            residents += plan.residents
        }
        for (index in buildings.indices) {
            val building = buildings[index]
            val pick = roofs.nextInt(Int.MAX_VALUE)
            if (building.roof != null) continue
            val roof = if (building.kind in civicKinds) palette.civicRoof else palette.roofs[pick % palette.roofs.size]
            buildings[index] = building.copy(roof = roof.id)
        }
        return city.copy(buildings = buildings, residents = residents)
    }

    fun keeperNpc(seed: Long, kind: String): Int = Npc.wandering_merchant_01 + Random(seed + kind.hashCode()).nextInt(MERCHANT_LOOKS)

    private const val MERCHANT_LOOKS = 50
    private const val ROAD_SCAN_STEP = 1

    private fun roadThrough(generator: WorldGenerator, cx: Int, cy: Int, half: Int): Road? {
        var best: Road? = null
        var bestDistance = half / 2
        generator.roadArms().forEachIndexed { arm, road ->
            for (along in 0 until road.length step ROAD_SCAN_STEP) {
                val (x, y) = generator.roadCentreAt(arm, along)
                val distance = maxOf(Math.abs(x - cx), Math.abs(y - cy))
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = Road(arm, along)
                }
            }
        }
        return best
    }

    private fun suitable(generator: WorldGenerator, cx: Int, cy: Int, half: Int): Boolean {
        val reach = half + SCAN_STEP
        if (generator.lodestoneSites().any { (lx, ly) -> Math.abs(lx - cx) < reach + WorldGenerator.LODESTONE_RADIUS && Math.abs(ly - cy) < reach + WorldGenerator.LODESTONE_RADIUS }) return false
        for (dx in -reach..reach step SCAN_STEP) for (dy in -reach..reach step SCAN_STEP) {
            if (generator.liquidTileAt(cx + dx, cy + dy) != null || generator.biomeAt(cx + dx, cy + dy) in badBiomes || generator.roadHazard(cx + dx, cy + dy)) return false
        }
        return true
    }

    private val houseFloors = listOf(FloorStyle.PLANKS, FloorStyle.DARKWOOD, FloorStyle.SANDSTONE, FloorStyle.TERRACOTTA)

    private val badBiomes = setOf(WorldGenerator.Biome.OCEAN, WorldGenerator.Biome.MOUNTAIN, WorldGenerator.Biome.VOLCANO, WorldGenerator.Biome.RUNE_ESSENCE)

    private fun streets(cx: Int, cy: Int, half: Int, arm: Int?): List<Street> {
        val result = arrayListOf(
            Street(cx - PLAZA_HALF, cy - PLAZA_HALF, 2 * PLAZA_HALF + 1, 2 * PLAZA_HALF + 1),
            Street(cx - RING, cy + RING, 2 * RING + RING_WIDTH, RING_WIDTH),
            Street(cx - RING, cy - RING - RING_WIDTH, 2 * RING + RING_WIDTH, RING_WIDTH),
            Street(cx - RING - RING_WIDTH, cy - RING, RING_WIDTH, 2 * RING + RING_WIDTH),
            Street(cx + RING, cy - RING, RING_WIDTH, 2 * RING + RING_WIDTH),
        )
        val horizontal = arm == null || arm >= 2
        val vertical = arm == null || arm < 2
        if (horizontal) result += Street(cx - half, cy - 1, 2 * half + 1, AVENUE)
        if (vertical) result += Street(cx - 1, cy - half, AVENUE, 2 * half + 1)
        if (arm != null) result += if (horizontal) Street(cx - 1, cy - half, AVENUE, 2 * half + 1) else Street(cx - half, cy - 1, 2 * half + 1, AVENUE)
        return result
    }

    private fun candidate(
        generator: WorldGenerator, random: Random, styles: Random, palette: Palette, city: String, kind: String, cx: Int, cy: Int, half: Int, streets: List<Street>, placed: List<Building>,
        compact: Boolean = false,
    ): Pair<Building, List<Resident>>? {
        val street = streets[random.nextInt(streets.size)]
        val width = MIN_SIDE + 2 + if (compact) 0 else random.nextInt(SIDE_SPREAD)
        val depth = MIN_SIDE + 2 + if (compact) 0 else random.nextInt(SIDE_SPREAD)
        val sides = if (street.width > street.depth * 2) listOf(Side.SOUTH, Side.NORTH) else if (street.depth > street.width * 2) listOf(Side.WEST, Side.EAST) else Side.entries
        val door = sides[random.nextInt(sides.size)]
        val (bx, by) = when (door) {
            Side.SOUTH -> street.x + random.nextInt(street.width) - width / 2 to street.y + street.depth
            Side.NORTH -> street.x + random.nextInt(street.width) - width / 2 to street.y - depth
            Side.WEST -> street.x + street.width to street.y + random.nextInt(street.depth) - depth / 2
            Side.EAST -> street.x - width to street.y + random.nextInt(street.depth) - depth / 2
        }
        val maxX = bx + width - 1
        val maxY = by + depth - 1
        if (bx < cx - half + 2 || by < cy - half + 2 || maxX > cx + half - 2 || maxY > cy + half - 2) return null
        if (streets.any { it.touches(bx, by, maxX, maxY) } || placed.any { it.touches(bx, by, maxX, maxY, 1) }) return null
        if (generator.roadTouches(bx - 1, by - 1, maxX + 1, maxY + 1)) return null
        val (doorX, doorY) = BuildingBuilder.doorTile(bx, by, width, depth, door)
        val outX = doorX + door.dx
        val outY = doorY + door.dy
        if (streets.none { it.touches(outX, outY, outX, outY) }) return null
        val house = kind == BuildingBuilder.HOUSE
        val floors = when (kind) {
            BuildingBuilder.BANK, BuildingBuilder.GUARDHOUSE -> TALL
            BuildingBuilder.HOUSE -> if (styles.nextInt(PERCENT) < TWO_FLOOR_HOUSE_PERCENT) TALL else LOW
            else -> LOW
        }
        val wall = if (house) palette.homes[styles.nextInt(palette.homes.size)] else palette.civic
        val floor = when (kind) {
            BuildingBuilder.BANK -> FloorStyle.MARBLE
            BuildingBuilder.MARKET -> FloorStyle.COBBLE
            BuildingBuilder.SMITHY -> FloorStyle.SLATE
            BuildingBuilder.GUARDHOUSE -> FloorStyle.FLAGSTONE
            else -> houseFloors[styles.nextInt(houseFloors.size)]
        }
        val plan = BuildingBuilder.plan(kind, bx, by, width, depth, door, floors, city)
        return Building(kind, bx, by, width, depth, door.name, floors, wall.id, floor.id, plan.furniture) to plan.residents
    }

    private fun guards(generator: WorldGenerator, road: Road?, crossroads: Boolean, cx: Int, cy: Int, half: Int): List<Resident> {
        val gates = when {
            road == null -> emptyList()
            crossroads -> List(ARMS) { Triple(it, half, direction(it)) }
            else -> listOf(Triple(road.arm, road.along + half, direction(road.arm)), Triple(road.arm, road.along - half, -direction(road.arm)))
        }
        val result = ArrayList<Resident>()
        for ((arm, distance, outward) in gates) {
            val (gx, gy) = generator.roadCentreAt(arm, distance)
            val shift = -outward * GUARD_OFFSET
            if (arm < 2) {
                result += Resident(Npc.guard1, gx - GUARD_OFFSET, gy + shift, BuildingBuilder.GUARD)
                result += Resident(Npc.guard1, gx + GUARD_OFFSET + 1, gy + shift, BuildingBuilder.GUARD)
            } else {
                result += Resident(Npc.guard1, gx + shift, gy - GUARD_OFFSET, BuildingBuilder.GUARD)
                result += Resident(Npc.guard1, gx + shift, gy + GUARD_OFFSET + 1, BuildingBuilder.GUARD)
            }
        }
        val spread = RING / 2
        result += listOf(cx - spread to cy + RING, cx + spread to cy - RING, cx - RING to cy - spread, cx + RING to cy + spread).map { (x, y) -> Resident(Npc.guard1, x, y, BuildingBuilder.PATROL) }
        return result
    }

    private fun direction(arm: Int) = if (arm % 2 == 0) 1 else -1
}
