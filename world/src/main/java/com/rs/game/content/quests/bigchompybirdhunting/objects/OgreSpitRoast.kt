package com.rs.game.content.quests.bigchompybirdhunting.objects

import com.rs.engine.quest.Quest
import com.rs.game.content.quests.bigchompybirdhunting.RAW_CHOMPY
import com.rs.game.content.quests.bigchompybirdhunting.SEASONED_CHOMPY
import com.rs.game.content.quests.bigchompybirdhunting.STAGE_COOKING_CHOMPY
import com.rs.game.content.quests.bigchompybirdhunting.dialogues.ASKED_BUGS_CHOMPY_INGREDIENT
import com.rs.game.content.quests.bigchompybirdhunting.dialogues.ASKED_FYCIE_CHOMPY_INGREDIENT
import com.rs.game.model.entity.async.schedule
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import com.rs.plugin.kts.onLogin

private const val DOOGLE_LEAVES = 1573
private const val ONION = 1957
private const val CABBAGE = 1965

private const val COOKING_CHOMPY = "big_chompy_cooking_chompy"

@ServerStartupEvent
fun mapBigChompyOgreSpitRoast() {

    onLogin { (player) ->
        if (player.getBool(COOKING_CHOMPY)) {
            player.set(COOKING_CHOMPY, false)

            if (player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) == STAGE_COOKING_CHOMPY &&
                !player.inventory.containsItem(RAW_CHOMPY, 1) &&
                !player.inventory.containsItem(SEASONED_CHOMPY, 1) &&
                !player.bank.containsItem(RAW_CHOMPY, 1) &&
                !player.bank.containsItem(SEASONED_CHOMPY, 1)
            ) {
                player.inventory.addItemDrop(RAW_CHOMPY, 1)
                player.sendMessage("Your interrupted chompy preparation has been restored so you can try again.")
            }
        }
    }

    onItemOnObject(
        objectNamesOrIds = arrayOf("Ogre spit-roast"),
        itemNamesOrIds = arrayOf(RAW_CHOMPY)
    ) { event ->

        val player = event.player

        if (
            player.getQuestStage(
                Quest.BIG_CHOMPY_BIRD_HUNTING
            ) != STAGE_COOKING_CHOMPY
        ) {
            return@onItemOnObject
        }

        cookSeasonedChompy(
            player,
            event.`object`
        )
    }
}

private fun cookSeasonedChompy(
    player: Player,
    spitRoast: GameObject
) {
    if (player.getBool(COOKING_CHOMPY)) {
        player.sendMessage(
            "You are already cooking the chompy."
        )
        return
    }

    if (!player.inventory.containsItem(RAW_CHOMPY, 1)) {
        return
    }

    val questAttribs =
        player.questManager.getAttribs(
            Quest.BIG_CHOMPY_BIRD_HUNTING
        )

    if (
        !questAttribs.getB(ASKED_FYCIE_CHOMPY_INGREDIENT) ||
        !questAttribs.getB(ASKED_BUGS_CHOMPY_INGREDIENT)
    ) {
        player.sendMessage(
            "You should find out how Fycie and Bugs want their chompy prepared first."
        )
        return
    }

    if (!player.inventory.containsItem(ONION, 1)) {
        player.sendMessage(
            "You need an onion to prepare the chompy."
        )
        return
    }

    if (!player.inventory.containsItem(DOOGLE_LEAVES, 1)) {
        player.sendMessage(
            "You need some doogle leaves to prepare the chompy."
        )
        return
    }

    if (!player.inventory.containsItem(CABBAGE, 1)) {
        player.sendMessage(
            "You need a cabbage to prepare the chompy."
        )
        return
    }

    player.inventory.deleteItem(
        RAW_CHOMPY,
        1
    )

    player.inventory.deleteItem(
        ONION,
        1
    )

    player.inventory.deleteItem(
        DOOGLE_LEAVES,
        1
    )

    player.inventory.deleteItem(
        CABBAGE,
        1
    )

    player.set(
        COOKING_CHOMPY,
        true
    )

    player.faceObject(
        spitRoast
    )

    player.schedule {

        player.sendMessage(
            "You carefully place the chompy bird on the spit-roast."
        )

        player.anim(896)

        wait(2)

        player.sendMessage(
            "You add the other ingredients and cook the food."
        )

        wait(3)

        player.sendMessage(
            "Eventually the chompy is cooked."
        )

        player.inventory.addItemDrop(
            SEASONED_CHOMPY,
            1
        )

        player.sendMessage(
            "You use the onion, doogle leaves and the cabbage with the chompy bird to make a seasoned chompy."
        )

        player.set(
            COOKING_CHOMPY,
            false
        )
    }
}
