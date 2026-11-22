package com.rs.game.content.world.generated

import com.google.gson.JsonParser
import org.darkan.core.Logger
import java.io.File

const val SPAWN_RING_TILES = 500
private const val RECHECK_MILLIS = 2000L
private const val REMOVED = "removed"

object SpawnAreas {

    private val file = File("data/npcs/spawn_areas.json")
    private var places = emptyMap<String, Map<String, IntArray>>()
    private var stamp = -1L
    private var checkedAt = 0L

    fun ids(place: String, x: Int, y: Int): IntArray? {
        refresh()
        return places[place]?.get((threatDistance(x, y) / SPAWN_RING_TILES).toString())
    }

    fun kept(place: String, ids: List<Int>): List<Int> {
        refresh()
        val removed = places[place]?.get(REMOVED) ?: return ids
        return ids.filter { it !in removed }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - checkedAt < RECHECK_MILLIS) return
        checkedAt = now
        val modified = file.lastModified()
        if (modified == stamp) return
        stamp = modified
        places = try {
            if (!file.exists()) emptyMap()
            else JsonParser.parseString(file.readText()).asJsonObject.entrySet().associate { (place, rings) ->
                place to rings.asJsonObject.entrySet().associate { (ring, ids) -> ring to ids.asJsonArray.map { it.asInt }.toIntArray() }
            }
        } catch (e: Exception) {
            Logger.handle(SpawnAreas::class.java, "refresh", e)
            emptyMap()
        }
    }
}
