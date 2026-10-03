package com.rs.game.content.skills.fishing

import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player

fun depleteFishingSpot(player: Player, npc: NPC) {
    player.anim(-1)
    npc.finish()
    player.sendMessage("The fishing spot dries up.")
}
