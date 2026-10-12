package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val TASK_SIZE = 20
private const val LEVEL_BAND = 0.15
private const val SPAWN_RADIUS = 10
private const val MIN_SPAWN_DISTANCE = 2
private const val PLACEMENT_ATTEMPTS = 40
private const val CHECK_TICKS = 25
private const val ABANDON_DISTANCE = 64
private const val GROUND_LEVEL = 0

object SlayerAssignments {

    private class Assignment(val level: Int, val origin: Tile, val mobs: MutableSet<PassiveSlayerNPC>)

    private val active = ConcurrentHashMap<String, Assignment>()

    fun hasTask(player: Player): Boolean = active.containsKey(player.username)

    fun start(player: Player): Boolean {
        if (hasTask(player)) return false
        val level = threatLevel(player.tile)
        val pool = HostileMobList.between(ceil(level * (1 - LEVEL_BAND)).toInt(), floor(level * (1 + LEVEL_BAND)).toInt())
            .ifEmpty { HostileMobList.nearest(level) }
        val assignment = Assignment(level, player.tile, ConcurrentHashMap.newKeySet())
        val biome = GeneratedWorld.generator.biomeAt(player.x, player.y)
        val taken = mutableSetOf<Tile>()
        repeat(TASK_SIZE) {
            val entry = pool[Utils.random(pool.size)]
            val tile = findTile(player, NPCDefinitions.getDefs(entry.id).size, taken) ?: return@repeat
            taken.add(tile)
            val scale = hostileScale(entry.id, biome, level)
            val native = NPCDefinitions.getDefs(entry.id).combatLevel.coerceAtLeast(1)
            assignment.mobs.add(PassiveSlayerNPC(entry.id, tile, scale, (native * scale).toInt(), player))
        }
        if (assignment.mobs.isEmpty()) return false
        active[player.username] = assignment
        return true
    }

    fun abandon(player: Player) {
        active.remove(player.username)?.let { finishAll(it) }
    }

    fun mobDied(mob: PassiveSlayerNPC) {
        val owner = mob.owner
        val assignment = active[owner.username] ?: return
        assignment.mobs.remove(mob)
        if (assignment.mobs.isNotEmpty()) return
        active.remove(owner.username)
        if (owner.hasFinished()) return
        owner.addSlayerPoints(assignment.level)
        owner.sendMessage("You have slain the whole task and receive ${assignment.level} Slayer points.")
    }

    @ServerStartupEvent
    @JvmStatic
    fun init() {
        if (!EnvVars.generateMaps) return
        WorldTasks.scheduleTimer(CHECK_TICKS, CHECK_TICKS) {
            active.entries.removeIf { (_, assignment) ->
                val owner = assignment.mobs.firstOrNull()?.owner
                val abandoned = owner == null || owner.hasFinished() || Utils.getDistance(owner.tile, assignment.origin) > ABANDON_DISTANCE
                if (abandoned) finishAll(assignment)
                abandoned
            }
            true
        }
    }

    private fun finishAll(assignment: Assignment) {
        assignment.mobs.forEach { if (!it.hasFinished()) it.finish() }
        assignment.mobs.clear()
    }

    private fun findTile(player: Player, size: Int, taken: Set<Tile>): Tile? {
        repeat(PLACEMENT_ATTEMPTS) {
            val dx = Utils.randomInclusive(-SPAWN_RADIUS, SPAWN_RADIUS)
            val dy = Utils.randomInclusive(-SPAWN_RADIUS, SPAWN_RADIUS)
            if (maxOf(abs(dx), abs(dy)) < MIN_SPAWN_DISTANCE) return@repeat
            val tile = player.tile.transform(dx, dy, 0)
            if (tile.plane == GROUND_LEVEL && tile !in taken && World.floorAndWallsFree(tile, size) &&
                GeneratedWorld.generator.liquidTileAt(tile.x, tile.y) == null && !GeneratedWorld.onRoad(tile.x, tile.y)
            ) return tile
        }
        return null
    }
}
