package com.rs.game.content.quests.area

import com.rs.game.model.entity.player.Player

private const val SAVE_KEY = "areaQuests"
private const val CACHE_KEY = "areaQuestLog"

class QuestProgress(var step: String, var count: Int = 0, val done: MutableList<String> = ArrayList())

class AreaQuestLog {
    val discovered: MutableSet<String> = LinkedHashSet()
    val active: MutableMap<String, QuestProgress> = LinkedHashMap()
    val completed: MutableMap<String, Int> = LinkedHashMap()

    val points get() = completed.values.sum()

    fun known(quest: AreaQuest) = quest.id in discovered || quest.id in active || quest.id in completed

    companion object {
        fun of(player: Player): AreaQuestLog {
            player.tempAttribs.getO<AreaQuestLog>(CACHE_KEY)?.let { return it }
            val saved = player.getO<String>(SAVE_KEY)
            val log = saved?.let { runCatching { AreaQuestRegistry.gson.fromJson(it, AreaQuestLog::class.java) }.getOrNull() } ?: AreaQuestLog()
            player.tempAttribs.setO<AreaQuestLog>(CACHE_KEY, log)
            return log
        }

        fun save(player: Player) {
            player.set(SAVE_KEY, compact.toJson(of(player)))
        }

        private val compact = com.google.gson.Gson()
    }
}
