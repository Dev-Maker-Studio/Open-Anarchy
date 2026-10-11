package com.rs.game.content.world

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onChunkEnter
import com.rs.plugin.kts.onLogin
import org.darkan.core.gameval.gen.Varp

private const val FOG_AREA_TILES = 4096
private const val FOG_CELL_TILES = 256
private const val FOG_CELLS_PER_ROW = FOG_AREA_TILES / FOG_CELL_TILES
private const val FOG_REVEAL_RADIUS_TILES = 100
private const val VARP_BITS = 32

private val FOG_VARPS = intArrayOf(
    Varp.worldmap_fog_0, Varp.worldmap_fog_1, Varp.worldmap_fog_2, Varp.worldmap_fog_3,
    Varp.worldmap_fog_4, Varp.worldmap_fog_5, Varp.worldmap_fog_6, Varp.worldmap_fog_7,
)

@ServerStartupEvent
fun mapWorldMapFog() {
    onLogin { (player) -> revealFog(player) }
    onChunkEnter { e -> e.player?.let(::revealFog) }
}

private fun revealFog(player: Player) = revealWorldMapFog(player, player.x, player.y, FOG_REVEAL_RADIUS_TILES)

fun revealWorldMapFog(player: Player, centreX: Int, centreY: Int, radius: Int) {
    if (centreX !in 0 until FOG_AREA_TILES || centreY !in 0 until FOG_AREA_TILES)
        return
    val minCellX = fogCell(centreX - radius)
    val maxCellX = fogCell(centreX + radius)
    val minCellY = fogCell(centreY - radius)
    val maxCellY = fogCell(centreY + radius)
    for (cellY in minCellY..maxCellY) {
        for (cellX in minCellX..maxCellX) {
            val cell = cellY * FOG_CELLS_PER_ROW + cellX
            val varp = FOG_VARPS[cell / VARP_BITS]
            val bit = 1 shl (cell % VARP_BITS)
            val value = player.vars.getVar(varp)
            if (value and bit == 0)
                player.vars.saveVar(varp, value or bit)
        }
    }
}

private fun fogCell(tile: Int) = (tile / FOG_CELL_TILES).coerceIn(0, FOG_CELLS_PER_ROW - 1)
