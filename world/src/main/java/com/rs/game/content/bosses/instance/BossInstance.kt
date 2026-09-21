package com.rs.game.content.bosses.instance

import com.rs.game.World
import com.rs.game.map.instance.Instance
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.TaskInformation
import com.rs.game.tasks.WorldTasks
import com.rs.utils.Ticks
import world.gregs.voidps.type.Tile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BossInstance(val def: BossInstanceDef, val ownerUsername: String, var settings: InstanceSettings) {
    val id: UUID = UUID.randomUUID()
    val instance: Instance = Instance.of(def.returnTo, def.widthRegions * 8, def.heightRegions * 8, true)
    private val createdAt: Long = System.currentTimeMillis()
    private val members = ConcurrentHashMap.newKeySet<String>()
    private var built = false
    private var expiryTask: TaskInformation? = null

    val memberCount: Int get() = members.size
    val millisLeft: Long get() = LIFETIME_MILLIS - (System.currentTimeMillis() - createdAt)
    val expired: Boolean get() = millisLeft <= 0

    fun build(onReady: () -> Unit) {
        if (built) {
            onReady()
            return
        }
        built = true
        instance.copyMap(0, 0, PLANES, def.originChunkX, def.originChunkY, PLANES, def.widthRegions * 8, def.heightRegions * 8)
            .thenAccept {
                applySpawnSpeed()
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

    private fun scheduleExpiry() {
        expiryTask = WorldTasks.scheduleLooping(Ticks.fromMinutes(1), Ticks.fromMinutes(1)) {
            val minutesLeft = (millisLeft / 60000L).toInt()
            if (minutesLeft in WARN_MINUTES) broadcast("Your boss instance expires in $minutesLeft minutes.")
            if (expired) destroy()
        }
    }

    private fun broadcast(message: String) = onlineMembers().forEach { it.sendMessage(message) }

    private fun onlineMembers(): List<Player> = members.mapNotNull { World.getPlayerByUsername(it) }

    fun destroy() {
        expiryTask?.let { WorldTasks.remove(it) }
        expiryTask = null
        onlineMembers().forEach { it.controllerManager.forceStop() }
        members.clear()
        instance.destroy()
        BossInstances.forget(this)
    }

    companion object {
        const val LIFETIME_MILLIS: Long = 60L * 60L * 1000L
        private val WARN_MINUTES = setOf(30, 15, 5)
        private val PLANES = intArrayOf(0, 1, 2, 3)
    }
}
