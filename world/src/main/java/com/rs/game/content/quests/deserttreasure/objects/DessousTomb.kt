package com.rs.game.content.quests.deserttreasure.objects

import com.rs.engine.quest.Quest
import com.rs.game.content.quests.deserttreasure.STAGE_DIAMONDS_OF_AZZANADRA
import com.rs.game.content.quests.deserttreasure.ownsActiveNpc
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import world.gregs.voidps.type.Tile

private val DESSOUS_TOMB = Tile.of(3568, 3401, 0)

@ServerStartupEvent
fun mapDessousTomb() {
    onItemOnObject(objectNamesOrIds = arrayOf("Tomb", "Grave"), itemNamesOrIds = arrayOf(Obj.fd_silver_pot_blood_garlic_spiced_blessed)) { (player, obj, item) ->
        if (obj.x != DESSOUS_TOMB.x || obj.y != DESSOUS_TOMB.y || obj.plane != DESSOUS_TOMB.plane)
            return@onItemOnObject
        useBloodPotOnTomb(player, item.id)
    }
}

private fun useBloodPotOnTomb(player: Player, itemId: Int) {
    if (player.getQuestStage(Quest.DESERT_TREASURE) < STAGE_DIAMONDS_OF_AZZANADRA) {
        player.sendMessage("Nothing interesting happens.")
        return
    }
    if (player.ownsActiveNpc(Npc.blooddiamond_vampirewarrior)) {
        player.sendMessage("Dessous has already been disturbed.")
        return
    }
    if (!player.inventory.containsItem(Obj.fd_silver_pot_blood_garlic_spiced_blessed, 1)) {
        player.sendMessage("You need a prepared pot of blood to lure Dessous.")
        return
    }

    player.inventory.deleteItem(itemId, 1)
    player.sendMessage("You pour the blood from the pot onto the tomb. A terrible presence stirs beneath the ground...")
    OwnedNPC(player, Npc.blooddiamond_vampirewarrior, DESSOUS_TOMB, true).apply {
        setAutoDespawnAtDistance(false)
    }
}
