package com.rs.game.content.world.generated

import com.rs.engine.pathfinder.DumbRouteFinder
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.Hit.HitLook
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile

private const val WANDER_RANGE = 16
private const val WANDER_CHANCE = 0.4
private const val WANDER_STEPS = 8

private val damageLooks = setOf(HitLook.MELEE_DAMAGE, HitLook.RANGE_DAMAGE, HitLook.MAGIC_DAMAGE, HitLook.CANNON_DAMAGE)

class PassiveSlayerNPC(id: Int, private val origin: Tile, scale: Double, level: Int, val owner: Player) : HostileNPC(id, origin, scale, level) {

    init {
        setForceAgressive(false)
    }

    override fun canAggroPlayer(target: Player): Boolean = false

    override fun canAggroNPC(target: NPC): Boolean = false

    override fun randomWalk() {
        if (hasWalkSteps()) return
        if (Utils.getDistance(tile, origin) > WANDER_RANGE) {
            resetWalkSteps()
            DumbRouteFinder.addDumbPathfinderSteps(this, origin, WANDER_STEPS, collisionStrategy)
            return
        }
        if (Math.random() >= WANDER_CHANCE) return
        val target = origin.transform(Utils.randomInclusive(-WANDER_RANGE, WANDER_RANGE), Utils.randomInclusive(-WANDER_RANGE, WANDER_RANGE), 0)
        resetWalkSteps()
        DumbRouteFinder.addDumbPathfinderSteps(this, target, WANDER_STEPS, collisionStrategy)
    }

    override fun removeHitpoints(hit: Hit) {
        super.removeHitpoints(hit)
        val attacker = hit.source as? Player ?: return
        if (hit.look in damageLooks && hit.damage > 0) attacker.skills.addXp(Constants.SLAYER, hit.damage.toDouble())
    }

    override fun sendDeath(source: Entity) {
        super.sendDeath(source)
        SlayerAssignments.mobDied(this)
    }

    override fun drop(killer: Player, verifyCombatDefs: Boolean) {}

    override fun sendDrop(player: Player, item: Item) {}
}
