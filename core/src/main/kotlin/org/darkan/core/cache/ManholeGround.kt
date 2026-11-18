package org.darkan.core.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.darkan.core.gameval.gen.Overlay
import world.gregs.voidps.cache.definition.data.MapDefinition

object ManholeGround {
    private const val GROUND_LEVEL = 0
    private const val TILE_SEPARATOR = " "
    private const val PROPERTY_SEPARATOR = ","
    private const val OVERLAY_PREFIX = "o"
    private const val TILES = "tiles"
    private const val SIZE = MapDefinition.SIZE

    private val patch = listOf(
        Triple(-1, -1, "7/2"), Triple(-1, 0, "6/2"), Triple(-1, 1, "7/3"),
        Triple(0, -1, "6/1"), Triple(0, 0, "0/0"), Triple(0, 1, "8/1"),
        Triple(1, -1, "7/1"), Triple(1, 0, "6/0"), Triple(1, 1, "4/2"),
    )

    private val json = Json { prettyPrint = true }

    fun apply(terrain: ByteArray, regionX: Int, regionY: Int, manholes: List<Pair<Int, Int>>): ByteArray {
        val baseX = regionX * SIZE
        val baseY = regionY * SIZE
        val edits = HashMap<Pair<Int, Int>, String>()
        for ((x, y) in manholes) for ((dx, dy, shape) in patch) {
            val localX = x + dx - baseX
            val localY = y + dy - baseY
            if (localX in 0 until SIZE && localY in 0 until SIZE) edits[localX to localY] = shape
        }
        if (edits.isEmpty()) return terrain
        val root = json.parseToJsonElement(terrain.decodeToString()).jsonObject
        val levels = root.getValue(TILES).jsonArray
        val rows = levels[GROUND_LEVEL].jsonArray.map { it.jsonPrimitive.content.split(TILE_SEPARATOR).toMutableList() }
        for ((position, shape) in edits) {
            val tile = rows[position.first][position.second]
            val rest = tile.split(PROPERTY_SEPARATOR).filterNot { it.startsWith(OVERLAY_PREFIX) }
            rows[position.first][position.second] = (listOf("$OVERLAY_PREFIX${Overlay.overlay_239}/$shape") + rest).joinToString(PROPERTY_SEPARATOR)
        }
        val ground = JsonArray(rows.map { JsonPrimitive(it.joinToString(TILE_SEPARATOR)) })
        val patched = JsonObject(root + (TILES to JsonArray(listOf<JsonElement>(ground) + levels.drop(1))))
        return json.encodeToString(JsonObject.serializer(), patched).encodeToByteArray()
    }
}
