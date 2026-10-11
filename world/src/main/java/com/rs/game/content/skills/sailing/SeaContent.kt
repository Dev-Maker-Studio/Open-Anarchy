package com.rs.game.content.skills.sailing

import com.rs.game.World
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class SeaSpot(val key: String, val obj: GameObject, val point: ChartPoint?, val wreck: WreckSite?)

object SeaContent {
    private const val GROUND_LEVEL = 0
    private const val SPAWN_RADIUS = 56
    private const val DESPAWN_RADIUS = 110
    private const val CHART_REACH = 3
    private const val WRECK_RESPAWN_TICKS = 3000
    private const val BUOY_ROTATION = 0
    private const val WRECK_ROTATION = 0

    private val spots = ConcurrentHashMap<String, SeaSpot>()
    private val salvaged = ConcurrentHashMap<String, Long>()
    private val blocks = ConcurrentHashMap<String, List<ChartPoint>>()
    private val wrecks = ConcurrentHashMap<Long, Optional<WreckSite>>()

    fun sync(centres: List<Pair<Int, Int>>) {
        centres.forEach { (cx, cy) ->
            chartPoints(cx, cy).forEach { point -> spawnBuoy(point) }
            wreckSites(cx, cy).forEach { site -> spawnWreck(site) }
        }
        spots.values.toList().forEach { spot ->
            val x = spot.obj.tile.x
            val y = spot.obj.tile.y
            if (centres.none { (cx, cy) -> maxOf(abs(cx - x), abs(cy - y)) <= DESPAWN_RADIUS }) {
                World.removeObject(spot.obj)
                spots.remove(spot.key)
            }
        }
    }

    fun wreckAt(x: Int, y: Int): SeaSpot? = spots.values.firstOrNull { it.wreck != null && it.obj.tile.x == x && it.obj.tile.y == y }

    fun nearestWreck(x: Int, y: Int, reach: Int): SeaSpot? =
        spots.values.filter { it.wreck != null && maxOf(abs(it.wreck.x - x), abs(it.wreck.y - y)) <= reach }.minByOrNull { maxOf(abs(it.obj.tile.x - x), abs(it.obj.tile.y - y)) }

    fun chartNear(x: Int, y: Int): ChartPoint? =
        spots.values.firstOrNull { it.point != null && abs(it.point.x - x) <= CHART_REACH && abs(it.point.y - y) <= CHART_REACH }?.point

    fun consume(spot: SeaSpot) {
        val site = spot.wreck ?: return
        World.removeObject(spot.obj)
        spots.remove(spot.key)
        salvaged[site.key] = World.getServerTicks() + WRECK_RESPAWN_TICKS
    }

    fun clear() {
        spots.values.forEach { World.removeObject(it.obj) }
        spots.clear()
    }

    private fun chartPoints(x: Int, y: Int): List<ChartPoint> {
        val corners = listOf(x - SPAWN_RADIUS to y - SPAWN_RADIUS, x + SPAWN_RADIUS to y - SPAWN_RADIUS, x - SPAWN_RADIUS to y + SPAWN_RADIUS, x + SPAWN_RADIUS to y + SPAWN_RADIUS)
        return corners.distinctBy { Seas.key(it.first, it.second) }
            .flatMap { corner -> blocks.computeIfAbsent(Seas.key(corner.first, corner.second)) { Seas.pointsIn(corner.first, corner.second) } }
            .filter { abs(it.x - x) <= SPAWN_RADIUS && abs(it.y - y) <= SPAWN_RADIUS }
    }

    private fun wreckSites(x: Int, y: Int): List<WreckSite> {
        val result = mutableListOf<WreckSite>()
        val minX = Math.floorDiv(x - SPAWN_RADIUS, WreckField.CELL)
        val maxX = Math.floorDiv(x + SPAWN_RADIUS, WreckField.CELL)
        val minY = Math.floorDiv(y - SPAWN_RADIUS, WreckField.CELL)
        val maxY = Math.floorDiv(y + SPAWN_RADIUS, WreckField.CELL)
        for (cy in minY..maxY) for (cx in minX..maxX) {
            val site = wrecks.computeIfAbsent(cx.toLong() shl 32 or cy.toLong()) { Optional.ofNullable(WreckField.at(cx, cy)) }.orElse(null) ?: continue
            if (abs(site.x - x) <= SPAWN_RADIUS && abs(site.y - y) <= SPAWN_RADIUS) result.add(site)
        }
        return result
    }

    private fun spawnBuoy(point: ChartPoint) {
        val key = "c:${point.x}:${point.y}"
        if (spots.containsKey(key)) return
        val obj = GameObject(Loc.slug2_life_buoy_outside, ObjectShape.SCENERY_INTERACT, BUOY_ROTATION, Tile.of(point.x, point.y, GROUND_LEVEL))
        World.spawnObject(obj, false)
        spots[key] = SeaSpot(key, obj, point, null)
    }

    private fun spawnWreck(site: WreckSite) {
        if (spots.containsKey(site.key)) return
        val expiry = salvaged[site.key]
        if (expiry != null) {
            if (World.getServerTicks() < expiry) return
            salvaged.remove(site.key)
        }
        val obj = GameObject(WreckField.LOCS[site.wreck.ordinal], ObjectShape.SCENERY_INTERACT, WRECK_ROTATION, Tile.of(site.x, site.y, GROUND_LEVEL))
        World.spawnObject(obj, false)
        spots[site.key] = SeaSpot(site.key, obj, null, site)
    }
}
