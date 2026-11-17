package com.rs.game.content.world.generated

import com.rs.engine.pathfinder.DumbRouteFinder
import com.rs.game.World
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.dungeon.Dungeon
import org.darkan.core.dungeon.SpawnPoint
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.type.Tile
import java.util.Random
import kotlin.math.roundToInt

private const val CHECK_TICKS = 10
private const val RESPAWN_TICKS = 500
private const val MINUTE_TICKS = 100
private const val ENTRANCE_FACTOR = 0.8
private const val DEPTH_FACTOR = 0.4
private const val BOSS_FACTOR = 1.2
private const val NATIVE_REACH = 3
private const val NATIVE_HEADROOM = 20
private const val LEASH = 6
private const val WANDER_CHANCE = 0.3
private const val WANDER_STEPS = 6
private const val GROUND_LEVEL = 0

open class DungeonMob(id: Int, private val home: Tile, scale: Double, level: Int, private val slot: DungeonSlot?) : HostileNPC(id, home, scale, level) {

    override fun canAggroPlayer(target: Player): Boolean = true

    override fun randomWalk() {
        if (hasWalkSteps() || Math.random() >= WANDER_CHANCE) return
        val target = if (Utils.getDistance(tile, home) > LEASH) home
        else home.transform(Utils.randomInclusive(-LEASH, LEASH), Utils.randomInclusive(-LEASH, LEASH), 0)
        resetWalkSteps()
        DumbRouteFinder.addDumbPathfinderSteps(this, target, WANDER_STEPS, collisionStrategy)
    }

    override fun sendDeath(source: Entity) {
        super.sendDeath(source)
        slot?.died(respawnTicks())
    }

    protected open fun respawnTicks(): Int = RESPAWN_TICKS
}

class DungeonSlot(val dungeon: Dungeon, val point: SpawnPoint) {
    var mob: NPC? = null
    var readyAt = 0L

    val tile: Tile get() = Tile.of(dungeon.baseX + point.x, dungeon.baseY + point.y, GROUND_LEVEL)

    fun died(ticks: Int) {
        readyAt = World.getServerTicks() + ticks
    }
}

object DungeonMobs {

    private val slots: Map<Dungeon, List<DungeonSlot>> by lazy {
        GeneratedWorld.dungeons.associateWith { dungeon -> dungeon.layout.spawns.map { DungeonSlot(dungeon, it) } }
    }

    fun baseLevel(dungeon: Dungeon): Int = threatLevel(dungeon.entranceX, dungeon.entranceY)

    fun mobLevel(dungeon: Dungeon, point: SpawnPoint): Int =
        (baseLevel(dungeon) * (ENTRANCE_FACTOR + DEPTH_FACTOR * point.progress)).roundToInt().coerceAtLeast(1)

    fun bossLevel(dungeon: Dungeon, definition: BossDefinition): Int =
        (baseLevel(dungeon) * BOSS_FACTOR * definition.levelFactor).roundToInt().coerceAtLeast(1)

    fun mobId(dungeon: Dungeon, point: SpawnPoint): Int {
        val level = mobLevel(dungeon, point)
        val mobs = dungeon.style.mobs
        val fitting = mobs.filter { native(it) <= level * NATIVE_REACH + NATIVE_HEADROOM }.ifEmpty { listOf(mobs.minBy(::native)) }
        return fitting[Random(point.roll).nextInt(fitting.size)]
    }

    fun native(id: Int): Int = NPCDefinitions.getDefs(id).combatLevel.coerceAtLeast(1)

    fun respawnTicks(minutes: Int?): Int = minutes?.let { it * MINUTE_TICKS } ?: RESPAWN_TICKS

    fun slotsOf(dungeon: Dungeon): List<DungeonSlot> = slots[dungeon].orEmpty()

    private fun occupied(): Set<Dungeon> = World.players
        .filter { it.hasStarted() && !it.hasFinished() && GeneratedWorld.inDungeon(it.x, it.y) }
        .mapNotNullTo(HashSet()) { GeneratedWorld.dungeonAt(it.x, it.y) }

    private fun fill(slot: DungeonSlot) {
        val mob = slot.mob
        if (mob != null && !mob.hasFinished()) return
        slot.mob = null
        if (World.getServerTicks() < slot.readyAt) return
        slot.mob = if (slot.point.boss) DungeonBosses.spawn(slot) else {
            val id = mobId(slot.dungeon, slot.point)
            val level = mobLevel(slot.dungeon, slot.point)
            DungeonMob(id, slot.tile, level.toDouble() / native(id), level, slot)
        }
    }

    @ServerStartupEvent
    @JvmStatic
    fun start() {
        if (!EnvVars.generateMaps) return
        WorldTasks.scheduleTimer(CHECK_TICKS, CHECK_TICKS) {
            occupied().forEach { dungeon -> slotsOf(dungeon).forEach(::fill) }
            true
        }
    }
}
