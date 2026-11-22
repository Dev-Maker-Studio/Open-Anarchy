package com.rs.game.content.world.generated

import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile
import kotlin.math.abs

const val TILES_PER_LEVEL = 94
const val FIRST_LEVEL = 1

fun threatDistance(x: Int, y: Int): Int {
    val dx = WorldWindow.origin(GeneratedWorld.windowX) + x - WorldGenerator.ORIGIN_X
    val dy = WorldWindow.origin(GeneratedWorld.windowY) + y - WorldGenerator.ORIGIN_Y
    return maxOf(abs(dx), abs(dy))
}

fun threatLevel(x: Int, y: Int): Int = FIRST_LEVEL + threatDistance(x, y) / TILES_PER_LEVEL

fun threatLevel(tile: Tile): Int = threatLevel(tile.x, tile.y)
