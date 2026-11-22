package com.rs.game.content.world.generated

import com.google.gson.Gson
import org.darkan.core.Logger
import org.darkan.core.Utils
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.model.Item
import java.io.File

private const val RECHECK_MILLIS = 2000L

class LevelDrop(val id: Int, val min: Int, val max: Int, val chance: Double, val outOf: Double)

class LevelRange(val min: Int, val max: Int, val drops: Array<LevelDrop>?)

class LevelDropLog(val id: Int, val name: String?, val byName: Boolean, val ranges: Array<LevelRange>?)

object LevelDrops {

    private val directory = File("data/npcs/level_drops")
    private val gson = Gson()
    private var byId = emptyMap<Int, LevelDropLog>()
    private var byName = emptyMap<String, LevelDropLog>()
    private var stamp = -1L
    private var checkedAt = 0L

    fun roll(npcId: Int, level: Int): List<Item> {
        refresh()
        val log = byId[npcId] ?: byName[NPCDefinitions.getDefs(npcId).name.trim().lowercase()] ?: return emptyList()
        return log.ranges.orEmpty()
            .filter { level in it.min..it.max }
            .flatMap { it.drops.orEmpty().asList() }
            .filter { Math.random() * it.outOf < it.chance }
            .map { Item(it.id, Utils.randomInclusive(it.min, maxOf(it.min, it.max))) }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - checkedAt < RECHECK_MILLIS) return
        checkedAt = now
        val modified = directory.lastModified()
        if (modified == stamp) return
        stamp = modified
        val logs = directory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull(::read)
        byId = logs.associateBy { it.id }
        byName = logs.filter { it.byName && it.name != null }.associateBy { it.name!!.trim().lowercase() }
    }

    private fun read(file: File): LevelDropLog? = try {
        gson.fromJson(file.readText(), LevelDropLog::class.java)
    } catch (e: Exception) {
        Logger.handle(LevelDrops::class.java, "read ${file.name}", e)
        null
    }
}
