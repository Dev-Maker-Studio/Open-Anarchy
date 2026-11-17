package com.rs.game.content.world.generated

import com.rs.engine.pathfinder.DumbRouteFinder
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.npc.combat.NPCCombatDefinitions
import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile

private const val WANDER_CHANCE = 0.4
private const val WANDER_RADIUS = 10
private const val WANDER_STEPS = 8
private const val UNSCALED = 1.0
private const val RESTORE_TICKS = 100
private const val RESTORE_STEP = 1
private const val HAMMER_MAX_LEVEL = 10
private const val HAMMER_CHANCE = 0.2

open class HostileNPC(id: Int, tile: Tile, val scale: Double, private val level: Int) : NPC(id, tile, null, true) {

    private val scaledLevels: Map<NPCCombatDefinitions.Skill, Int> =
        NPCCombatDefinitions.getDefs(id).levels.mapValues { (_, value) -> maxOf(1, (value * scale).toInt()) }

    init {
        setLevels(scaledLevels.toMutableMap())
        setCombatLevel(level)
        setForceAgressive(true)
        hitpoints = maxHitpoints
    }

    override fun getMaxHitpoints(): Int = (super.getMaxHitpoints() * (if (scale > 0.0) scale else UNSCALED)).toInt()

    override fun getMaxHit(): Int = (super.getMaxHit() * (if (scale > 0.0) scale else UNSCALED)).toInt()

    override fun processNPC() {
        val restoring = tickCounter % RESTORE_TICKS == 0L
        super.processNPC()
        if (!restoring) return
        scaledLevels.forEach { (skill, level) ->
            val current = getStat(skill)
            if (current < level) setStat(skill, minOf(level, current + RESTORE_STEP))
        }
    }

    override fun canAggroPlayer(target: Player): Boolean =
        !GeneratedWorld.onRoad(target.x, target.y) && !GeneratedWorld.onRoad(x, y) && !GeneratedWorld.inCity(target.x, target.y) && !GeneratedWorld.inCity(x, y)

    override fun randomWalk() {
        if (hasWalkSteps() || Math.random() >= WANDER_CHANCE) return
        val target = tile.transform(Utils.randomInclusive(-WANDER_RADIUS, WANDER_RADIUS), Utils.randomInclusive(-WANDER_RADIUS, WANDER_RADIUS), 0)
        resetWalkSteps()
        DumbRouteFinder.addDumbPathfinderSteps(this, target, WANDER_STEPS, collisionStrategy)
        setRespawnTile(tile)
    }

    override fun drop(killer: Player, verifyCombatDefs: Boolean) {
        super.drop(killer, verifyCombatDefs)
        if (level < HAMMER_MAX_LEVEL && Math.random() < HAMMER_CHANCE) sendDrop(killer, Item(Obj.hammer, 1))
    }

    override fun sendDrop(player: Player, item: Item) {
        if (item.id == Obj.coins) return
        if (id in HILL_GIANT_IDS && item.id !in HILL_GIANT_DROPS) return
        super.sendDrop(player, item)
    }
}
