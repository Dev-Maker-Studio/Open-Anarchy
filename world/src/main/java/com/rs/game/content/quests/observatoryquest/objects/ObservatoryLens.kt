package com.rs.game.content.quests.observatoryquest.objects

import com.rs.engine.quest.Quest
import com.rs.game.content.quests.observatoryquest.STAGE_MAKING_LENS
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem

private const val MOLTEN_GLASS = 1775
private const val LENS_MOULD = 602
private const val OBSERVATORY_LENS = 603

@ServerStartupEvent
fun mapObservatoryLens() {

    onItemOnItem(
        MOLTEN_GLASS,
        LENS_MOULD
    ) { e ->

        val player = e.player

        if (
            player.getQuestStage(Quest.OBSERVATORY_QUEST) !=
            STAGE_MAKING_LENS
        ) {
            return@onItemOnItem
        }

        if (
            !player.inventory.containsItem(MOLTEN_GLASS, 1) ||
            !player.inventory.containsItem(LENS_MOULD, 1)
        ) {
            return@onItemOnItem
        }

        player.inventory.deleteItem(
            MOLTEN_GLASS,
            1
        )

        player.inventory.addItemDrop(
            OBSERVATORY_LENS,
            1
        )

        player.sendMessage(
            "You make the molten glass into a lens."
        )
    }
}