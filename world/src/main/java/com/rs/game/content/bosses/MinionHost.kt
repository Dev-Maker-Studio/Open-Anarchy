package com.rs.game.content.bosses

import com.rs.game.World
import com.rs.game.model.entity.async.schedule
import com.rs.game.model.entity.npc.NPC
import world.gregs.voidps.type.Tile

data class MinionSpawn(val npcId: Int, val offsetX: Int, val offsetY: Int, val offsetZ: Int = 0) {
    fun tileFrom(host: Tile): Tile = host.transform(offsetX, offsetY, offsetZ)
}

/**
 * A boss that brings its own minions rather than relying on world spawns: they are placed relative
 * to wherever the boss itself spawned, so the same boss arrives complete in its real arena, in an
 * instance copy of it, or dropped anywhere in the open world.
 */
open class MinionHost(id: Int, tile: Tile, spawned: Boolean, private val minionSpawns: List<MinionSpawn>) : NPC(id, tile, spawned) {
    private val minions = ArrayList<NPC>(minionSpawns.size)

    init {
        spawnMinions()
    }

    val liveMinions: List<NPC> get() = minions.filter { !it.hasFinished() && !it.isDead }

    override fun onRespawn() {
        respawnMinions()
    }

    override fun finish() {
        super.finish()
        minions.forEach { if (!it.hasFinished()) it.permanentlyDelete() }
        minions.clear()
    }

    fun respawnMinions(delay: Int = 2) {
        schedule {
            if (minions.isEmpty()) spawnMinions()
            else minions.forEach { if (it.hasFinished() || it.isDead) it.setRespawnTask(delay) }
        }
    }

    private fun spawnMinions() {
        minionSpawns.mapTo(minions) { World.spawnNPC(it.npcId, it.tileFrom(respawnTile), isSpawned, true) }
    }
}
