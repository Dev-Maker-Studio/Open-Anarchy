package com.rs.game.content.skills.fishing

import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player

fun depleteFishingSpot(player: Player, npc: NPC): Boolean {
    if (npc is FixedFishingSpot && !npc.caught()) return false
    player.anim(-1)
    if (npc is FixedFishingSpot) FixedFishingSpots.deplete(npc) else npc.finish()
    player.sendMessage("The fishing spot dries up.")
    return true
}
