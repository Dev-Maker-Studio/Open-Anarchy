package com.rs.game.content

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin

private const val WORST_TIER = 1

private val withheld = setOf(
    Toolbelt.Tools.PICKAXE,
    Toolbelt.Tools.HATCHET,
    Toolbelt.Tools.DUNG_PICKAXE,
    Toolbelt.Tools.DUNG_HATCHET,
)

@ServerStartupEvent
fun mapFullToolbelt() {
    onLogin { (player) -> player.fillToolbelt() }
}

fun Player.fillToolbelt() {
    Toolbelt.Tools.entries.forEach { tool ->
        if (tool in withheld) toolbelt.remove(tool) else toolbelt[tool] = WORST_TIER
    }
    refreshToolbelt()
}
