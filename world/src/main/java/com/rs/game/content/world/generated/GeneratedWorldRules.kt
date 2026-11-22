package com.rs.game.content.world.generated

import com.rs.Settings
import com.rs.game.World
import com.rs.game.content.claims.BedRespawn
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars

private const val PVP_REFRESH_TICKS = 5
private const val DEATH_MESSAGE = "Oh dear, you are dead!"

@ServerStartupEvent
fun startGeneratedWorldRules() {
    if (!EnvVars.generateMaps) return
    WorldTasks.scheduleTimer(PVP_REFRESH_TICKS, PVP_REFRESH_TICKS) {
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished() && player.isCanPvp) player.setCanPvp(false)
        }
        true
    }
}

fun sendGeneratedWorldDeath(player: Player, source: Entity?): Boolean {
    if (!EnvVars.generateMaps) return false
    player.incrementCount("Deaths")
    player.safeDeath(source, Settings.getConfig().playerRespawnTile, DEATH_MESSAGE, BedRespawn::wake)
    return true
}
