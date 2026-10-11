package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.map.GeneratedWorld

private const val CHECK_TICKS = 2
private const val NEARBY_RADIUS = 14

@ServerStartupEvent
fun startRoadSafeHaven() {
    if (!EnvVars.generateMaps) return
    WorldTasks.scheduleTimer(CHECK_TICKS, CHECK_TICKS) {
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished()) calmRoad(player)
        }
        true
    }
}

private fun calmRoad(player: Player) {
    val onRoad = GeneratedWorld.onRoad(player.x, player.y)
    if (onRoad && player.inCombat()) {
        player.actionManager.forceStop()
        player.resetCombat()
    }
    player.queryNearbyNPCsByTileRange(NEARBY_RADIUS) { npc ->
        npc.combat.target === player && (onRoad || GeneratedWorld.onRoad(npc.x, npc.y))
    }.forEach { npc ->
        npc.combat.removeTarget()
        npc.combat.reset()
        player.resetCombat()
    }
}
