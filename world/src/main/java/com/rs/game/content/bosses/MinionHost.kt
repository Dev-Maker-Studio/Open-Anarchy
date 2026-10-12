package com.rs.game.content.bosses

import com.rs.game.World
import com.rs.game.model.entity.async.schedule
import com.rs.game.model.entity.npc.NPC
import world.gregs.voidps.type.Tile

data class MinionSpawn(val npcId: Int, val offsetX: Int, val offsetY: Int, val offsetZ: Int = 0) {
    fun tileFrom(host: Tile): Tile = host.transform(offsetX, offsetY, offsetZ)
}

enum class MinionPolicy { PERSIST, BOUND }

interface HostedMinion {
    var host: MinionHost?
}

open class MinionHost(
    id: Int,
    tile: Tile,
    spawned: Boolean,
    private val minionSpawns: List<MinionSpawn>,
    private val policy: MinionPolicy = MinionPolicy.PERSIST,
) : NPC(id, tile, spawned) {
    private val minions = ArrayList<NPC>(minionSpawns.size)

    init {
        spawnMinions()
    }

    val liveMinions: List<NPC> get() = minions.filter { !it.hasFinished() && !it.isDead }

    fun minion(npcId: Int): NPC? = minions.firstOrNull { it.id == npcId }

    override fun onRespawn() {
        reviveMinions()
    }

    override fun finish() {
        super.finish()
        if (policy != MinionPolicy.BOUND) return
        minions.forEach { it.permanentlyDelete() }
        minions.clear()
    }

    fun reviveMinions(delay: Int = 2) {
        schedule {
            if (minions.isEmpty()) spawnMinions()
            else minions.forEach { if (it.hasFinished() || it.isDead) it.setRespawnTask(delay) }
        }
    }

    private fun spawnMinions() {
        val ownLifecycle = policy == MinionPolicy.BOUND
        minionSpawns.mapTo(minions) { spawn ->
            World.spawnNPC(spawn.npcId, spawn.tileFrom(respawnTile), ownLifecycle, true).also {
                (it as? HostedMinion)?.host = this
            }
        }
    }
}
