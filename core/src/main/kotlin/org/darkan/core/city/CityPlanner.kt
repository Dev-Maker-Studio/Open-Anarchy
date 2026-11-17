package org.darkan.core.city

import org.darkan.core.gameval.gen.Npc
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.util.Random

object CityPlanner {
    private const val SEED = 7271L
    private const val SPAWN_HALF = 40
    private const val HALF = 34
    private const val PLAZA_HALF = 4
    private const val RING = 20
    private const val AVENUE = 3
    private const val RING_WIDTH = 2
    private const val MIN_SIDE = 5
    private const val SIDE_SPREAD = 5
    private const val ATTEMPTS = 60
    private const val HOUSES = 14
    private const val SCAN_STEP = 6
    private const val SEARCH_STEP = 25
    private const val SEARCH_SPAN = 600
    private const val MAP_MARGIN = 80
    private const val GUARD_OFFSET = 3
    private const val ARMS = 4

    private class Site(val name: String, val arm: Int?, val distance: Int)

    private val sites = listOf(
        Site("Velneir", null, 0),
        Site("Aldmere", 0, 1250),
        Site("Brackenholt", 2, 1450),
        Site("Sarnwick", 1, 1150),
        Site("Dunhollow", 3, 1350),
    )

    private val badBiomes = setOf(WorldGenerator.Biome.OCEAN, WorldGenerator.Biome.MOUNTAIN, WorldGenerator.Biome.VOLCANO, WorldGenerator.Biome.RUNE_ESSENCE)

    fun plan(generator: WorldGenerator, spawnX: Int, spawnY: Int, mapMin: Int, mapMax: Int): List<City> =
        sites.mapIndexedNotNull { index, site ->
            if (site.arm == null) build(generator, site, index, spawnX, spawnY, SPAWN_HALF, 0)
            else locate(generator, site, mapMin, mapMax)?.let { (x, y, along) -> build(generator, site, index, x, y, HALF, along) }
        }

    private fun locate(generator: WorldGenerator, site: Site, mapMin: Int, mapMax: Int): Triple<Int, Int, Int>? {
        val arm = site.arm ?: return null
        for (step in 0..SEARCH_SPAN / SEARCH_STEP) {
            val along = site.distance + step * SEARCH_STEP
            val (x, y) = generator.roadCentreAt(arm, along)
            if (x - HALF < mapMin + MAP_MARGIN || y - HALF < mapMin + MAP_MARGIN || x + HALF > mapMax - MAP_MARGIN || y + HALF > mapMax - MAP_MARGIN) continue
            if (suitable(generator, x, y, HALF)) return Triple(x, y, along)
        }
        return null
    }

    private fun suitable(generator: WorldGenerator, cx: Int, cy: Int, half: Int): Boolean {
        val reach = half + SCAN_STEP
        if (generator.lodestoneSites().any { (lx, ly) -> Math.abs(lx - cx) < reach + WorldGenerator.LODESTONE_RADIUS && Math.abs(ly - cy) < reach + WorldGenerator.LODESTONE_RADIUS }) return false
        for (dx in -reach..reach step SCAN_STEP) for (dy in -reach..reach step SCAN_STEP) {
            if (generator.liquidTileAt(cx + dx, cy + dy) != null || generator.biomeAt(cx + dx, cy + dy) in badBiomes || generator.roadHazard(cx + dx, cy + dy)) return false
        }
        return true
    }

    private fun build(generator: WorldGenerator, site: Site, index: Int, cx: Int, cy: Int, half: Int, along: Int): City {
        val random = Random(SEED + index)
        val streets = streets(cx, cy, half, site.arm)
        val buildings = ArrayList<Building>()
        val residents = ArrayList<Resident>()
        val kinds = listOf(BuildingBuilder.BANK, BuildingBuilder.SMITHY, BuildingBuilder.MARKET, BuildingBuilder.GUARDHOUSE) + List(HOUSES) { BuildingBuilder.HOUSE }
        for (kind in kinds) {
            for (attempt in 0 until ATTEMPTS) {
                val candidate = candidate(generator, random, kind, cx, cy, half, streets, buildings) ?: continue
                buildings += candidate.first
                residents += candidate.second
                break
            }
        }
        residents += guards(generator, site, cx, cy, half, along)
        return City(site.name.lowercase(), site.name, SEED + index, cx, cy, half, generator.heightAt(cx, cy), streets, buildings, residents)
    }

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
        generator: WorldGenerator, random: Random, kind: String, cx: Int, cy: Int, half: Int, streets: List<Street>, placed: List<Building>,
    ): Pair<Building, List<Resident>>? {
        val street = streets[random.nextInt(streets.size)]
        val width = MIN_SIDE + 2 + random.nextInt(SIDE_SPREAD)
        val depth = MIN_SIDE + 2 + random.nextInt(SIDE_SPREAD)
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
        val result = BuildingBuilder.build(kind, bx, by, width, depth, door)
        return Building(kind, bx, by, width, depth, door.name, result.pieces) to result.residents
    }

    private fun guards(generator: WorldGenerator, site: Site, cx: Int, cy: Int, half: Int, along: Int): List<Resident> {
        val gates = if (site.arm == null) List(ARMS) { Triple(it, half, direction(it)) }
        else listOf(Triple(site.arm, along + half, direction(site.arm)), Triple(site.arm, along - half, -direction(site.arm)))
        val result = ArrayList<Resident>()
        for ((arm, distance, outward) in gates) {
            val (gx, gy) = generator.roadCentreAt(arm, distance)
            val shift = -outward * GUARD_OFFSET
            if (arm < 2) {
                result += Resident(Npc.guard1, gx - GUARD_OFFSET, gy + shift, "guard")
                result += Resident(Npc.guard1, gx + GUARD_OFFSET + 1, gy + shift, "guard")
            } else {
                result += Resident(Npc.guard1, gx + shift, gy - GUARD_OFFSET, "guard")
                result += Resident(Npc.guard1, gx + shift, gy + GUARD_OFFSET + 1, "guard")
            }
        }
        val spread = RING / 2
        result += listOf(cx - spread to cy + RING, cx + spread to cy - RING, cx - RING to cy - spread, cx + RING to cy + spread).map { (x, y) -> Resident(Npc.guard1, x, y, "patrol") }
        return result
    }

    private fun direction(arm: Int) = if (arm % 2 == 0) 1 else -1
}
