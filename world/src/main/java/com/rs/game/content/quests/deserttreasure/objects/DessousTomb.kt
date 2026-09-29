package com.rs.game.content.quests.deserttreasure.objects

import com.rs.engine.quest.Quest
import com.rs.game.World
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.content.quests.deserttreasure.STAGE_DIAMONDS_OF_AZZANADRA
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import world.gregs.voidps.type.Tile

private const val DESSOUS = 1914
private const val BLOOD_POT_COMPLETE = 4667
private const val DESSOUS_TOMB_X = 3568
private const val DESSOUS_TOMB_Y = 3401
private const val DESSOUS_TOMB_PLANE = 0

@ServerStartupEvent
fun mapDessousTomb() {
    onItemOnObject(objectNamesOrIds = arrayOf("Tomb", "Grave"), itemNamesOrIds = arrayOf(BLOOD_POT_COMPLETE)) { (player, obj, item) ->
        if (obj.x != DESSOUS_TOMB_X || obj.y != DESSOUS_TOMB_Y || obj.plane != DESSOUS_TOMB_PLANE) {
            return@onItemOnObject
        }
        useBloodPotOnTomb(player, item.id)
    }
}

private fun useBloodPotOnTomb(player: Player, itemId: Int) {
    if (player.getQuestStage(Quest.DESERT_TREASURE) < STAGE_DIAMONDS_OF_AZZANADRA) {
        player.sendMessage("Nothing interesting happens.")
        return
    }
    if (World.getNPCs().any { npc -> npc is OwnedNPC && npc.owner === player && npc.id == DESSOUS && !npc.hasFinished() }) {
        player.sendMessage("Dessous has already been disturbed.")
        return
    }
    if (!player.inventory.containsItem(BLOOD_POT_COMPLETE, 1)) {
        player.sendMessage("You need a prepared pot of blood to lure Dessous.")
        return
    }

    player.inventory.deleteItem(itemId, 1)
    player.sendMessage("You pour the blood from the pot onto the tomb. A terrible presence stirs beneath the ground...")
    OwnedNPC(player, DESSOUS, Tile.of(DESSOUS_TOMB_X, DESSOUS_TOMB_Y, DESSOUS_TOMB_PLANE), true).apply {
        setAutoDespawnAtDistance(false)
    }
}