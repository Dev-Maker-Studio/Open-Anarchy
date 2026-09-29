package com.rs.game.content.quests.observatoryquest.objects

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import world.gregs.voidps.type.Tile

private const val OBSERVATORY_ORRERY = 25401

private val OBSERVATORY_ORRERY_TILE =
    Tile.of(2442, 3185, 0)

@ServerStartupEvent
fun mapObservatoryOrrery() {

    onObjectClick(
        OBSERVATORY_ORRERY,
        tiles = arrayOf(OBSERVATORY_ORRERY_TILE)
    ) { e ->

        e.player.sendMessage(
            "You look at the brass clockwork planetary model. It tracks the strange orbits of the celestial bodies."
        )
    }
}
