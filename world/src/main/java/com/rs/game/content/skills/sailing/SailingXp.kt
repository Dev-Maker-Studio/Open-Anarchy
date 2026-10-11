package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants

object SailingXp {
    private const val HORIZON_BONUS = 1.025

    fun multiplier(boat: Boat?): Double = if (boat?.activeKeg == KegKind.HORIZON) HORIZON_BONUS else 1.0

    fun grant(player: Player, boat: Boat?, xp: Double) {
        if (xp <= 0.0) return
        player.skills.addXp(Constants.SAILING, xp * multiplier(boat))
    }
}
