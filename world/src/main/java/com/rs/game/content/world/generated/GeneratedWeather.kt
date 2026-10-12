package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.game.tasks.WorldTasks
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Spotanim
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome

private const val SNOW_TICKS = 4
private const val SNOW_PER_PULSE = 4
private const val SNOW_REACH = 7
private const val GROUND_LEVEL = 0

@ServerStartupEvent
fun startGeneratedWeather() {
    if (!EnvVars.generateMaps) return
    val generator = GeneratedWorld.generator
    WorldTasks.scheduleTimer(SNOW_TICKS, SNOW_TICKS) {
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished() && player.plane == GROUND_LEVEL && generator.biomeAt(player.x, player.y) == Biome.WINTER) {
                repeat(SNOW_PER_PULSE) {
                    World.sendSpotAnim(player.tile.transform(Utils.random(-SNOW_REACH, SNOW_REACH + 1), Utils.random(-SNOW_REACH, SNOW_REACH + 1)), Spotanim.xmas_07_player_snowfall)
                }
            }
        }
        true
    }
}
