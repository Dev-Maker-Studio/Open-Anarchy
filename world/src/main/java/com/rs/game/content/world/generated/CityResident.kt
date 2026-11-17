package com.rs.game.content.world.generated

import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.map.pathfinder.Direction
import world.gregs.voidps.type.Tile

open class CityResident(id: Int, tile: Tile) : NPC(id, tile, Direction.SOUTH, true) {

    init {
        setRandomWalk(false)
    }

    override fun handlePreHit(hit: Hit) {
        hit.damage = 0
    }

    override fun removeHitpoints(hit: Hit) {}

    override fun canBeAttackedBy(player: Player): Boolean = false

    override fun canAggroPlayer(target: Player): Boolean = false
}
