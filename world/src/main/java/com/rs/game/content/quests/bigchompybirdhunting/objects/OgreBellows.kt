package com.rs.game.content.quests.bigchompybirdhunting

import com.rs.engine.quest.Quest
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnNpc
import com.rs.plugin.kts.onItemOnObject

private const val SWAMP_TOAD = 1013

private const val SWAMP_BUBBLES_1 = 684
private const val SWAMP_BUBBLES_2 = 735

private const val MAX_BLOATED_TOADS = 3

@ServerStartupEvent
fun mapBigChompyOgreBellows() {

    onItemOnObject(
        arrayOf(SWAMP_BUBBLES_1, SWAMP_BUBBLES_2),
        arrayOf(OGRE_BELLOWS)
    ) { (player, _, item) ->

        if (
            player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) <
            STAGE_GETTING_BLOATED_TOAD
        ) {
            player.sendMessage("Nothing interesting happens.")
            return@onItemOnObject
        }

        player.inventory.replaceItem(
            OGRE_BELLOWS_3,
            1,
            item.slot
        )

        player.sendMessage(
            "You fill the ogre bellows with swamp gas."
        )
    }

    onItemOnNpc(SWAMP_TOAD) { (player, item, _) ->

        if (
            player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) <
            STAGE_GETTING_BLOATED_TOAD
        ) {
            return@onItemOnNpc
        }

        when (item.id) {

            OGRE_BELLOWS -> {
                player.sendMessage(
                    "The air seems too thin to stay in the toad. Perhaps you need something thicker than air."
                )
            }

            OGRE_BELLOWS_3 -> {
                inflateToad(
                    player,
                    item.slot,
                    OGRE_BELLOWS_2
                )
            }

            OGRE_BELLOWS_2 -> {
                inflateToad(
                    player,
                    item.slot,
                    OGRE_BELLOWS_1
                )
            }

            OGRE_BELLOWS_1 -> {
                inflateToad(
                    player,
                    item.slot,
                    OGRE_BELLOWS
                )
            }
        }
    }
}

private fun inflateToad(
    player: com.rs.game.model.entity.player.Player,
    bellowsSlot: Int,
    nextBellowsId: Int
) {

    player.inventory.replaceItem(
        nextBellowsId,
        1,
        bellowsSlot
    )

    player.sendMessage(
        "You manage to catch the toad and inflate it with the swamp gas."
    )

    if (
        player.inventory.getNumberOf(BLOATED_TOAD) >=
        MAX_BLOATED_TOADS
    ) {
        player.sendMessage(
            "One of your bloated toads manage to escape."
        )
        return
    }

    player.inventory.addItemDrop(
        BLOATED_TOAD,
        1
    )

    player.sendMessage(
        "You add the bloated toad to your inventory."
    )

    if (
        player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) ==
        STAGE_GETTING_BLOATED_TOAD
    ) {
        player.setQuestStage(
            Quest.BIG_CHOMPY_BIRD_HUNTING,
            STAGE_SHOWING_BLOATED_TOAD
        )
    }
}
