package com.rs.game.content.quests.bigchompybirdhunting.objects

import com.rs.engine.quest.Quest
import com.rs.game.content.quests.bigchompybirdhunting.STAGE_COOKING_CHOMPY
import com.rs.game.content.quests.bigchompybirdhunting.dialogues.ASKED_BUGS_CHOMPY_INGREDIENT
import com.rs.game.content.quests.bigchompybirdhunting.dialogues.ASKED_FYCIE_CHOMPY_INGREDIENT
import com.rs.game.model.entity.async.schedule
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import com.rs.plugin.kts.onLogin
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq

private const val COOKING_CHOMPY = "big_chompy_cooking_chompy"

@ServerStartupEvent
fun mapBigChompyOgreSpitRoast() {
    onLogin { (player) ->
        if (!player.getBool(COOKING_CHOMPY)) return@onLogin
        player.set(COOKING_CHOMPY, false)
        if (player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) == STAGE_COOKING_CHOMPY &&
            !player.inventory.containsItem(Obj.raw_chompy, 1) &&
            !player.inventory.containsItem(Obj.cooked_s_chompy, 1) &&
            !player.bank.containsItem(Obj.raw_chompy, 1) &&
            !player.bank.containsItem(Obj.cooked_s_chompy, 1)
        ) {
            player.inventory.addItemDrop(Obj.raw_chompy, 1)
            player.sendMessage("Your interrupted chompy preparation has been restored so you can try again.")
        }
    }

    onItemOnObject(arrayOf("Ogre spit-roast"), arrayOf(Obj.raw_chompy)) { (player, obj) ->
        if (player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) == STAGE_COOKING_CHOMPY)
            cookSeasonedChompy(player, obj)
    }
}

private fun cookSeasonedChompy(player: Player, spitRoast: GameObject) {
    if (player.getBool(COOKING_CHOMPY)) {
        player.sendMessage("You are already cooking the chompy.")
        return
    }
    if (!player.inventory.containsItem(Obj.raw_chompy, 1)) return
    val questAttribs = player.questManager.getAttribs(Quest.BIG_CHOMPY_BIRD_HUNTING)
    if (!questAttribs.getB(ASKED_FYCIE_CHOMPY_INGREDIENT) || !questAttribs.getB(ASKED_BUGS_CHOMPY_INGREDIENT)) {
        player.sendMessage("You should find out how Fycie and Bugs want their chompy prepared first.")
        return
    }
    if (!player.inventory.containsItem(Obj.onion, 1)) {
        player.sendMessage("You need an onion to prepare the chompy.")
        return
    }
    if (!player.inventory.containsItem(Obj.doogleleaves, 1)) {
        player.sendMessage("You need some doogle leaves to prepare the chompy.")
        return
    }
    if (!player.inventory.containsItem(Obj.cabbage, 1)) {
        player.sendMessage("You need a cabbage to prepare the chompy.")
        return
    }
    player.inventory.deleteItem(Obj.raw_chompy, 1)
    player.inventory.deleteItem(Obj.onion, 1)
    player.inventory.deleteItem(Obj.doogleleaves, 1)
    player.inventory.deleteItem(Obj.cabbage, 1)
    player.set(COOKING_CHOMPY, true)
    player.faceObject(spitRoast)
    player.schedule {
        player.sendMessage("You carefully place the chompy bird on the spit-roast.")
        player.anim(Seq.human_cooking)
        wait(2)
        player.sendMessage("You add the other ingredients and cook the food.")
        wait(3)
        player.sendMessage("Eventually the chompy is cooked.")
        player.inventory.addItemDrop(Obj.cooked_s_chompy, 1)
        player.sendMessage("You use the onion, doogle leaves and the cabbage with the chompy bird to make a seasoned chompy.")
        player.set(COOKING_CHOMPY, false)
    }
}
