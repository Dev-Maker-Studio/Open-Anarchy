package com.rs.game.content.bosses.godwars

import com.rs.game.model.entity.npc.NPC
import world.gregs.voidps.type.Tile

class GWDMinion(id: Int, tile: Tile, spawned: Boolean) : NPC(id, tile, spawned) {
    init {
        isIgnoreDocile = true
        setForceAgressive(true)
        setForceAggroDistance(64)
        isIntelligentRouteFinder = true
    }
}