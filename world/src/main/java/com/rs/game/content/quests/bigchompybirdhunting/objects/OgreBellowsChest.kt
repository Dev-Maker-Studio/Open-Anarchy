package com.rs.game.content.quests.bigchompybirdhunting.objects

import com.rs.engine.dialogue.HeadE.CALM_TALK
import com.rs.engine.quest.Quest
import com.rs.game.content.quests.bigchompybirdhunting.OGRE_BELLOWS
import com.rs.game.content.quests.bigchompybirdhunting.OGRE_BELLOWS_1
import com.rs.game.content.quests.bigchompybirdhunting.OGRE_BELLOWS_2
import com.rs.game.content.quests.bigchompybirdhunting.OGRE_BELLOWS_3
import com.rs.game.content.quests.bigchompybirdhunting.STAGE_GETTING_BLOATED_TOAD
import com.rs.game.model.entity.player.Player
import com.rs.game.World
import com.rs.game.model.gameobject.OwnedObject
import org.darkan.core.Utils
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.Constants
import org.darkan.core.utils.Ticks

private const val LOCKED_OGRE_CHEST = 3377
private const val UNLOCKED_OGRE_CHEST = 3378

@ServerStartupEvent
fun mapBigChompyOgreBellowsChest() {

    onObjectClick(LOCKED_OGRE_CHEST) { (player, obj) ->

        if (
            player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) <
            STAGE_GETTING_BLOATED_TOAD
        ) {
            player.sendMessage("Hmmm, a rock for a lock....")
            return@onObjectClick
        }

        if (Utils.random(4) == 0) {
            player.playerDialogue(
                CALM_TALK,
                "Humph!"
            )

            player.skills.drainLevel(
                Constants.STRENGTH,
                1
            )

            player.sendMessage(
                "You try to lift the huge rock, but it is too heavy."
            )

            return@onObjectClick
        }

        player.sendMessage(
            "Hmmm, a rock for a lock...."
        )

        player.playerDialogue(
            CALM_TALK,
            "Humph!"
        )

        player.sendMessage(
            "You manage to lift the huge rock off the chest."
        )

        World.spawnObjectTemporary(
            OwnedObject(player, obj, UNLOCKED_OGRE_CHEST),
            Ticks.fromSeconds(30)
        )

        player.playerDialogue(
            CALM_TALK,
            "I guess this is what an ogre would call a locked chest."
        )
    }

    onObjectClick(UNLOCKED_OGRE_CHEST) { (player, _) ->

        if (
            player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) <
            STAGE_GETTING_BLOATED_TOAD
        ) {
            player.sendMessage(
                "You search the chest but find nothing of interest."
            )
            return@onObjectClick
        }

        if (hasOgreBellows(player)) {
            player.sendMessage(
                "You search but find nothing in the ogre chest. You have already taken the ogre bellows."
            )

            player.simpleDialogue(
                "You search but find nothing",
                "in the ogre chest. You have already taken the ogre",
                "bellows."
            )

            return@onObjectClick
        }

        player.inventory.addItemDrop(
            OGRE_BELLOWS,
            1
        )

        player.sendMessage(
            "You search the chest and find a pair of ogre bellows."
        )
    }
}

private fun hasOgreBellows(player: Player): Boolean {
    val bellows = intArrayOf(
        OGRE_BELLOWS,
        OGRE_BELLOWS_1,
        OGRE_BELLOWS_2,
        OGRE_BELLOWS_3
    )

    for (itemId in bellows) {
        if (
            player.inventory.containsItem(itemId, 1) ||
            player.bank.containsItem(itemId, 1)
        ) {
            return true
        }
    }

    return false
}
