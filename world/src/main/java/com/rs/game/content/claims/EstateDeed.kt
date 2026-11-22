package com.rs.game.content.claims

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.gameval.gen.Obj

private const val CLAIM_OPTION = "Claim"

@ServerStartupEvent
fun mapEstateDeed() {
    onItemClick(Obj.estate_deed, options = arrayOf(CLAIM_OPTION)) { e ->
        val player = e.player
        player.sendMessage("Land claims no longer have a tile limit, so the deed isn't needed.")
    }
}
