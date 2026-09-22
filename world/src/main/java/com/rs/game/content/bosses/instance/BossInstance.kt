package com.rs.game.content.bosses.instance

import com.rs.game.World
import com.rs.game.map.instance.Instance
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.TaskInformation
import com.rs.game.tasks.WorldTasks
import com.rs.utils.Ticks
import org.darkan.core.Logger
import world.gregs.voidps.type.Tile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BossInstance(val def: BossInstanceDef, val ownerUsername: String, var settings: InstanceSettings) {
    val id: UUID = UUID.randomUUID()
    val instance: Instance = Instance.of(def.returnTo, def.widthRegions * 8, def.heightRegions * 8, true)
    private var paidUntil: Long = System.currentTimeMillis() + LIFETIME_MILLIS
    private val members = ConcurrentHashMap.newKeySet<String>()
    private var built = false
    private var exhausted = false
    private var expiryTask: TaskInformation? = null

    val memberCount: Int get() = members.size
    val millisLeft: Long get() = paidUntil - System.currentTimeMillis()
    val expired: Boolean get() = millisLeft <= 0

    fun build(onReady: () -> Unit) {
        if (built) {
            onReady()
            return
        }
        built = true
        val startedAt = System.currentTimeMillis()
        instance.copyMap(0, 0, def.planes.toIntArray(), def.originChunkX, def.originChunkY, def.planes.toIntArray(), def.widthRegions * 8, def.heightRegions * 8)
            .thenAccept {
                Logger.info(BossInstance::class.java, "build", "${def.key} instance built in ${System.currentTimeMillis() - startedAt}ms (${def.widthRegions * 8 * def.heightRegions * 8 * def.planes.size} chunks)")
                applySpawnSpeed()
                stageBoss()
                scheduleExpiry()
                onReady()
            }
    }

    fun localTile(worldTile: Tile): Tile {
        val offset = def.localOffset(worldTile)
        return instance.getLocalTile(offset[0], offset[1], offset[2])
    }

    fun refusalFor(joiner: Player, enteredPin: Int): String? {
        val owner = World.getPlayerByUsername(ownerUsername)
        return when {
            expired -> "That instance has expired."
            memberCount >= settings.maxPlayers -> "That instance is full."
            joiner.skills.combatLevel < settings.minCombat -> "You need a combat level of at least ${settings.minCombat} to join that instance."
            owner != null && !settings.protection.admits(owner, joiner, settings.pin, enteredPin) -> "You are not allowed to join that instance."
            else -> null
        }
    }

    /** A fresh payment buys another hour and wakes the arena back up. */
    fun renew() {
        paidUntil = System.currentTimeMillis() + LIFETIME_MILLIS
        exhausted = false
        applySpawnSpeed()
    }

    fun add(player: Player) = members.add(player.username)

    fun remove(player: Player) {
        members.remove(player.username)
    }

    fun npcsInside(): List<NPC> = World.NPCs.filterNotNull().filter { !it.hasFinished() && it.tile.getRegionId() in instanceRegions }

    private val instanceRegions: Set<Int>
        get() = (0 until def.widthRegions).flatMap { rx ->
            (0 until def.heightRegions).map { ry -> instance.tileBase.transform(rx * 64, ry * 64, 0).getRegionId() }
        }.toSet()

    fun applySpawnSpeed() {
        npcsInside().forEach { it.setRespawnDelayOverride(settings.spawnSpeed.ticks) }
    }

    /** The boss is not waiting when the door opens: it arrives on the timer the owner paid for. */
    private fun stageBoss() {
        npcsInside().filter { it.id == def.bossNpc }.forEach { it.setRespawnTask(settings.spawnSpeed.ticks) }
    }

    private fun stopRespawns() {
        npcsInside().forEach { it.setRespawnDelayOverride(Int.MAX_VALUE) }
        broadcast("Your instance has expired. Nothing further will respawn until you pay to renew it.")
    }

    private fun scheduleExpiry() {
        expiryTask = WorldTasks.scheduleLooping(Ticks.fromMinutes(1), Ticks.fromMinutes(1)) {
            val minutesLeft = (millisLeft / 60000L).toInt()
            if (minutesLeft in WARN_MINUTES) broadcast("Your boss instance expires in $minutesLeft minutes.")
            if (expired && !exhausted) {
                exhausted = true
                stopRespawns()
            }
        }
    }

    private fun broadcast(message: String) = onlineMembers().forEach { it.sendMessage(message) }

    private fun onlineMembers(): List<Player> = members.mapNotNull { World.getPlayerByUsername(it) }

    fun destroy() {
        expiryTask?.let { WorldTasks.remove(it) }
        expiryTask = null
        npcsInside().forEach { it.permanentlyDelete() }
        onlineMembers().forEach { it.controllerManager.forceStop() }
        members.clear()
        instance.destroy()
        BossInstances.forget(this)
    }

    companion object {
        const val LIFETIME_MILLIS: Long = 60L * 60L * 1000L
        private val WARN_MINUTES = setOf(30, 15, 5)
    }
}
