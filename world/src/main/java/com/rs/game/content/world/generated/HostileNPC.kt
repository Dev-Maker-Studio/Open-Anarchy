package com.rs.game.content.world.generated

import com.rs.engine.pathfinder.DumbRouteFinder
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.npc.combat.NPCCombatDefinitions
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import com.rs.game.model.entity.player.Player
import world.gregs.voidps.type.Tile

private const val WANDER_CHANCE = 0.4
private const val WANDER_RADIUS = 10
private const val WANDER_STEPS = 8
private const val UNSCALED = 1.0

open class HostileNPC(id: Int, tile: Tile, val scale: Double, level: Int) : NPC(id, tile, null, true) {

    init {
        val base = NPCCombatDefinitions.getDefs(id)
        setLevels(base.levels.mapValues { (_, value) -> maxOf(1, (value * scale).toInt()) }.toMutableMap())
        setCombatLevel(level)
        hitpoints = maxHitpoints
    }

    override fun getMaxHitpoints(): Int = (super.getMaxHitpoints() * (if (scale > 0.0) scale else UNSCALED)).toInt()

    override fun getMaxHit(): Int = (super.getMaxHit() * (if (scale > 0.0) scale else UNSCALED)).toInt()

    override fun randomWalk() {
        if (hasWalkSteps() || Math.random() >= WANDER_CHANCE) return
        val target = tile.transform(Utils.randomInclusive(-WANDER_RADIUS, WANDER_RADIUS), Utils.randomInclusive(-WANDER_RADIUS, WANDER_RADIUS), 0)
        resetWalkSteps()
        DumbRouteFinder.addDumbPathfinderSteps(this, target, WANDER_STEPS, collisionStrategy)
        setRespawnTile(tile)
    }

    override fun sendDrop(player: Player, item: Item) {
        if (item.id == Obj.coins) return
        super.sendDrop(player, item)
    }
}
