// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// Copyright (C) 2021 Trenton Kress
// This file is part of project: Darkan
//
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
