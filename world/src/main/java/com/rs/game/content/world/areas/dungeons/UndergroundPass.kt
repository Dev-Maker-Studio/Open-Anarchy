package com.rs.game.content.world.areas.dungeons

import com.rs.plugin.annotations.PluginEventHandler
import com.rs.plugin.handlers.ObjectClickHandler
import world.gregs.voidps.type.Tile

@PluginEventHandler
class UndergroundPass {
    companion object {
        @JvmField
        val handleSkullDoorEnter = ObjectClickHandler(arrayOf<Any>(3220, 3221)) { e ->
            e.player.useStairs(Tile.of(2173, 4725, 1))
        }

        @JvmField
        val handleSkullDoorExit = ObjectClickHandler(arrayOf<Any>(34288, 34289)) { e ->
            e.player.useStairs(Tile.of(2369, 9718, 0))
        }

        @JvmField
        val handleWellDoorEnter = ObjectClickHandler(arrayOf<Any>(3333, 3334)) { e ->
            e.player.useStairs(
                if (e.player.x < e.obj.x) Tile.of(2145, 4648, 1)
                else Tile.of(2014, 4712, 1)
            )
        }
    }
}
