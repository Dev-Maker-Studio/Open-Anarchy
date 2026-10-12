package com.rs.game.content.quests.fairytales.fairytalept1

import com.rs.engine.quest.QuestOutline
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.plugin.annotations.ServerStartupEvent

const val STAGE_UNSTARTED = 0

const val STAGE_COMPLETE = 10

class FairyTalePt1 : QuestOutline() {
    override fun getJournalLines(player: Player, stage: Int) = when (stage) {
        STAGE_UNSTARTED -> listOf("I should talk to Martin the Master Gardener in Draynor Village.")

        STAGE_COMPLETE -> listOf("QUEST COMPLETE!")
        else -> listOf("Invalid quest stage. Report this to an administrator.")
    }

    override fun complete(player: Player) {
        player.skills.addXpQuest(Skills.FARMING, 3500.0)
        player.skills.addXpQuest(Skills.ATTACK, 2000.0)
        player.skills.addXpQuest(Skills.MAGIC, 1000.0)
        sendQuestCompleteInterface(player, 7410)
    }
}

@ServerStartupEvent
fun mapFairyTaleP1() {

}