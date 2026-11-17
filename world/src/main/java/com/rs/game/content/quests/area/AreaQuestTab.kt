package com.rs.game.content.quests.area

import com.rs.game.model.entity.player.Player
import com.rs.game.model.item.ItemTooltips
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Cs2
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Varcstr
import org.darkan.core.gameval.gen.Varp
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.IFEvents
import org.darkan.core.model.Item

private const val ROWS_KEY = "areaQuestRows"
private const val HEADER = 1
private const val NOT_STARTED = 2
private const val STARTED = 3
private const val COMPLETE = 4
private const val NO_QUESTS = "No quests discovered yet"
private const val JINGLE = 152
private const val STRIKE = "<str>"
private const val BLANK = ""

object AreaQuestTab {

    private class Row(val text: String, val state: Int, val quest: AreaQuest?)

    private fun rows(log: AreaQuestLog): List<Row> = buildList {
        val known = AreaQuestRegistry.quests.values.filter(log::known).groupBy { it.area }
        for (area in GeneratedWorld.cities.map { it.id } + known.keys.filter { id -> GeneratedWorld.cities.none { it.id == id } }) {
            val quests = known[area] ?: continue
            add(Row(AreaQuests.cityName(area), HEADER, null))
            for (quest in quests) {
                val state = when (quest.id) {
                    in log.completed -> COMPLETE
                    in log.active -> STARTED
                    else -> NOT_STARTED
                }
                add(Row(quest.name, state, quest))
            }
        }
        if (isEmpty()) add(Row(NO_QUESTS, HEADER, null))
    }

    @JvmStatic
    fun send(player: Player) {
        val log = AreaQuestLog.of(player)
        val rows = rows(log)
        player.tempAttribs.setO<List<Row>>(ROWS_KEY, rows)
        player.vars.setVar(Varp.qp, log.points)
        player.packets.sendItems(Inv.area_quests, rows.map { Item(Obj.coins, it.state).addMetaData(ItemTooltips.METADATA_KEY, it.text) }.toTypedArray<Item?>())
        player.packets.sendRunScript(Cs2.area_quests_draw)
        player.packets.setIFEvents(IFEvents(Interface.interface_190, component(Component.interface_190.component_190_15), 0, rows.size - 1).enableRightClickOption(0))
    }

    @JvmStatic
    fun click(player: Player, slot: Int) {
        val quest = player.tempAttribs.getO<List<Row>>(ROWS_KEY)?.getOrNull(slot)?.quest ?: return
        player.questManager.showJournal(quest.name, journal(player, quest))
    }

    private fun journal(player: Player, quest: AreaQuest): List<String> = buildList {
        val log = AreaQuestLog.of(player)
        val reward = AreaQuestRewards.of(quest)
        val progress = log.active[quest.id]
        val complete = quest.id in log.completed
        if (progress == null && !complete) {
            add("I can start this quest by speaking to ${AreaQuests.title(quest.source)}.")
            add(BLANK)
            add(quest.summary)
            add(BLANK)
            add("Length: ${quest.length}")
        } else {
            add("$STRIKE${AreaQuests.title(quest.source).replaceFirstChar { it.uppercase() }} asked for my help. ${quest.summary}")
            val done = if (complete) quest.steps.map { it.id } else progress?.done.orEmpty()
            for (id in done) quest.step(id)?.let { add("$STRIKE${AreaQuests.describe(quest, it)}") }
            if (progress != null) {
                val step = quest.step(progress.step)
                add(step?.let { AreaQuests.describe(quest, it) } ?: AreaQuests.describeReturn(quest))
                if (step != null && step.count > 1 && step.type != STEP_GATHER) add("Progress: ${progress.count} of ${step.count}")
            }
            if (complete) {
                add(BLANK)
                add("<col=FF0000>QUEST COMPLETE!")
            }
        }
        add(BLANK)
        add("Rewards:")
        addAll(reward.lines())
    }

    fun showComplete(player: Player, quest: AreaQuest, reward: QuestReward) {
        player.jingle(JINGLE)
        player.packets.sendVarcString(Varcstr.varcstr_359, reward.lines().joinToString("<br>"))
        player.interfaceManager.sendInterface(Interface.interface_1244)
        player.packets.setIFItem(Interface.interface_1244, component(Component.interface_1244.component_1244_24), reward.items.firstOrNull()?.first ?: Obj.coins, 1)
        player.packets.setIFText(Interface.interface_1244, component(Component.interface_1244.component_1244_25), "You have completed ${quest.name}!")
        player.packets.setIFText(Interface.interface_1244, component(Component.interface_1244.component_1244_27), "Quest points: ${AreaQuestLog.of(player).points}")
    }

    private fun component(hash: Int) = Utils.componentIdFromHash(hash)
}
