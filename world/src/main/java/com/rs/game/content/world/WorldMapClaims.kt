package com.rs.game.content.world

import com.rs.game.World
import com.rs.game.content.claims.Claim
import com.rs.game.content.claims.ClaimManager
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import org.darkan.core.gameval.gen.Varp

private const val MAP_AREA_TILES = 4096
private const val COORDINATE_BITS = 12
private const val COORDINATE_MASK = (1 shl COORDINATE_BITS) - 1
private const val PRESENT = 1 shl 24
private const val GROUND_PLANE = 0
private const val REFRESH_TICKS = 5

private val CLAIM_VARPS = intArrayOf(
    Varp.worldmap_claim_0, Varp.worldmap_claim_1, Varp.worldmap_claim_2, Varp.worldmap_claim_3,
    Varp.worldmap_claim_4, Varp.worldmap_claim_5, Varp.worldmap_claim_6, Varp.worldmap_claim_7,
    Varp.worldmap_claim_8, Varp.worldmap_claim_9, Varp.worldmap_claim_10, Varp.worldmap_claim_11,
    Varp.worldmap_claim_12, Varp.worldmap_claim_13, Varp.worldmap_claim_14, Varp.worldmap_claim_15,
)
private val SHOWN_CLAIMS = CLAIM_VARPS.size / 2

@ServerStartupEvent
fun mapWorldMapClaims() {
    onLogin { (player) -> pushClaims(player) }
    WorldTasks.scheduleTimer(REFRESH_TICKS, REFRESH_TICKS) {
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished()) pushClaims(player)
        }
        true
    }
}

private fun pushClaims(player: Player) {
    val nearest = ClaimManager.all()
        .filter { it.plane == GROUND_PLANE && it.minX < MAP_AREA_TILES && it.minY < MAP_AREA_TILES }
        .sortedBy { distance(it, player) }
        .take(SHOWN_CLAIMS)
    for (slot in 0 until SHOWN_CLAIMS) {
        val claim = nearest.getOrNull(slot)
        setIfChanged(player, CLAIM_VARPS[slot * 2], claim?.let { PRESENT or pack(it.minX, it.maxX) } ?: 0)
        setIfChanged(player, CLAIM_VARPS[slot * 2 + 1], claim?.let { pack(it.minY, it.maxY) } ?: 0)
    }
}

private fun setIfChanged(player: Player, varp: Int, value: Int) {
    if (player.vars.getVar(varp) != value)
        player.vars.setVar(varp, value)
}

private fun pack(low: Int, high: Int): Int =
    low.coerceIn(0, COORDINATE_MASK) or (high.coerceIn(0, COORDINATE_MASK) shl COORDINATE_BITS)

private fun distance(claim: Claim, player: Player): Long {
    val dx = ((claim.minX + claim.maxX) / 2 - player.x).toLong()
    val dy = ((claim.minY + claim.maxY) / 2 - player.y).toLong()
    return dx * dx + dy * dy
}
