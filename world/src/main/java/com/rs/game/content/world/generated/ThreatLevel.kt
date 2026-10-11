package com.rs.game.content.world.generated

import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile

private const val TILES_PER_LEVEL = 200
private const val FIRST_LEVEL = 1

fun threatLevel(x: Int, y: Int): Int {
    val dx = WorldWindow.origin(GeneratedWorld.windowX) + x - WorldGenerator.ORIGIN_X
    val dy = WorldWindow.origin(GeneratedWorld.windowY) + y - WorldGenerator.ORIGIN_Y
    return FIRST_LEVEL + maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) / TILES_PER_LEVEL
}

fun threatLevel(tile: Tile): Int = threatLevel(tile.x, tile.y)
