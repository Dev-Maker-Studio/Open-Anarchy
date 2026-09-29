package com.rs.game.content.world.areas.braindeath_island

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import world.gregs.voidps.type.Tile

private const val RUM_GEON_ENTRANCE = 9161
private const val RUM_GEON_EXIT = 36086

private const val FISHBOWL_HELMET = 7534
private const val DIVING_APPARATUS = 7535

private val UNDERWATER_ENTRANCE = Tile.of(1055, 5001, 0)
private val ISLAND_ENTRANCE = Tile.of(2126, 5147, 0)

private fun Player.hasDivingEquipment(): Boolean {
    return equipment.containsOneItem(FISHBOWL_HELMET) &&
            equipment.containsOneItem(DIVING_APPARATUS)
}

@ServerStartupEvent
fun mapBraindeathIsland() {
    onObjectClick(RUM_GEON_ENTRANCE) { e ->
        val player = e.player

        if (!player.hasDivingEquipment()) {
            player.sendMessage("You need to be wearing a fishbowl helmet and diving apparatus to go underwater.")
            return@onObjectClick
        }

        player.tele(UNDERWATER_ENTRANCE)
        player.controllerManager.startController(BraindeathUnderwaterController())
    }

    onObjectClick(RUM_GEON_EXIT) { e ->
        val player = e.player

        player.controllerManager.forceStop()
        player.tele(ISLAND_ENTRANCE)
    }
}