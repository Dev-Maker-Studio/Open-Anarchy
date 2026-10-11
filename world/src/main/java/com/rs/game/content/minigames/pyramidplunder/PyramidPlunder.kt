package com.rs.game.content.minigames.pyramidplunder

import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import world.gregs.voidps.type.Tile

val PYRAMID_PLUNDER_EXIT_TILE: Tile = Tile.of(3288, 2801, 0)
val PYRAMID_PLUNDER_DOORS: Array<Int> = arrayOf(16539, 16540, 16541, 16542)

@ServerStartupEvent
fun mapSceptreOfTheGodsTooltip() {
    ItemTooltips.metadata("teleCharges") { _, item, _ -> ItemTooltips.count("Teleports", item.getMetaDataI("teleCharges"), 10) }
}
