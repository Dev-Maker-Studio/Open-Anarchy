package com.rs.game.content.miniquests.recipe_for_disaster.another_cooks_quest

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.engine.miniquest.Miniquest
import com.rs.engine.miniquest.MiniquestOutline
import com.rs.engine.quest.Quest
import com.rs.engine.quest.QuestHandler
import com.rs.engine.quest.QuestOutline
import com.rs.game.content.miniquests.recipe_for_disaster.another_cooks_quest.cutscene.AnotherCooksQuestCutscene
import com.rs.game.content.world.doors.Doors
import com.rs.game.model.entity.player.Player
import org.darkan.core.model.Item
import org.darkan.core.Utils.strikeThroughIf
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onObjectClick

private const val ASH_ID = 592
private const val EYE_OF_NEWT_ID = 221
private const val FRUIT_BLAST_ID = 9514
private const val DIRTY_BLAST_ID = 7497
private const val GREENMANS_ALE_ID = 1909
private const val ROTTEN_TOMATO_ID = 2518

class AnotherCooksQuest : QuestOutline() {
    companion object {
        const val NOT_STARTED = 0
        const val COOKS_INGREDIENTS_NEEDED = 1
        const val COOKS_CUTSCENE = 2
        const val COMPLETED = 3
    }

    override fun getJournalLines(p: Player, stage: Int): List<String> {
        val lines = ArrayList<String>()
        when (stage) {
            NOT_STARTED -> listOf("I can start this quest by speaking to the cook in the Lumbridge castle kitchen.")
            COOKS_INGREDIENTS_NEEDED -> listOf("The Cook told you that he needs a newt's eye, a Greenman's ale, a rotten tomato, and a dirty blast in order to prepare for the centennial feast.",
                strikeThroughIf("An Eye of Newt") { p.inventory.containsItem(EYE_OF_NEWT_ID, 1) },
                strikeThroughIf("A Greenman's ale") { p.inventory.containsItem(GREENMANS_ALE_ID, 1) },
                strikeThroughIf("A rotten tomato") { p.inventory.containsItem(ROTTEN_TOMATO_ID, 1) },
                strikeThroughIf("A dirty blast") { p.inventory.containsItem(DIRTY_BLAST_ID, 1) }
            )
            COOKS_CUTSCENE -> listOf("The cook said that I should take a look at what he prepared in the banquet hall at Lumbridge castle.")
            COMPLETED -> {
                lines.add("")
                lines.add("")
                lines.add("MINIQUEST COMPLETE!")
            }
            else -> lines.add("Invalid miniquest stage. Report this to an administrator.")
        }
        return lines
    }

    override fun complete(p: Player) {
        sendQuestCompleteInterface(p, 7497)
    }

    override fun updateStage(p: Player, stage: Int) {
    }
}
@ServerStartupEvent
fun mapAnotherCooksQuest() {

    onObjectClick(12348, 12349, 12350) { e ->
        if (e.player.getQuestStage(Quest.ANOTHER_COOKS_QUEST) > 1 && !e.player.questManager.isComplete(Quest.RECIPE_FOR_DISASTER)) {
            AnotherCooksQuestCutscene(e.player)
        } else {
            if (e.obj.id == 12348) {
                Doors.handleDoor(e.player, e.obj)
            } else {
                Doors.handleDoubleDoor(e.player, e.obj)
            }
        }
    }

    onItemOnItem(intArrayOf(ASH_ID), intArrayOf(FRUIT_BLAST_ID)) { e ->
        when (e.player.getQuestStage(Quest.ANOTHER_COOKS_QUEST)) {
            1 -> {
                e.player.inventory.removeItems(Item(ASH_ID, 1), Item(FRUIT_BLAST_ID, 1))
                e.player.inventory.addItem(DIRTY_BLAST_ID, 1)
                e.player.startConversation {
                    player(HeadE.CONFUSED, "That looks disgusting, but it's what the cook asked for...")
                }
            }
        }
    }
}