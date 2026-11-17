package com.rs.game.content.world.generated

import com.rs.engine.pathfinder.DumbRouteFinder
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils
import org.darkan.core.map.pathfinder.Direction
import world.gregs.voidps.type.Tile

private const val WANDER_CHANCE = 0.1
private const val WANDER_STEPS = 4
private const val AGGRO_DISTANCE = 9

class CityGuard(id: Int, tile: Tile, private val roam: Int) : NPC(id, tile, Direction.SOUTH, false) {

    init {
        setForceAgressive(true)
        setCanAggroNPCs(true)
        setForceAggroDistance(AGGRO_DISTANCE)
    }

    override fun canAggroPlayer(target: Player): Boolean = false

    override fun canAggroNPC(target: NPC): Boolean = target is HostileNPC

    override fun randomWalk() {
        if (roam <= 0 || hasWalkSteps() || Math.random() >= WANDER_CHANCE) return
        val target = respawnTile.transform(Utils.randomInclusive(-roam, roam), Utils.randomInclusive(-roam, roam), 0)
        resetWalkSteps()
        DumbRouteFinder.addDumbPathfinderSteps(this, target, WANDER_STEPS, collisionStrategy)
    }
}
