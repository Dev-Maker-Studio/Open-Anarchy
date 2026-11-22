package com.rs.game.content.claims

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.gameval.gen.Obj

private const val CLAIM_OPTION = "Claim"
private const val TILES_PER_DEED = 1

@ServerStartupEvent
fun mapEstateDeed() {
    onItemClick(Obj.estate_deed, options = arrayOf(CLAIM_OPTION)) { e ->
        val player = e.player
        player.inventory.deleteItem(Obj.estate_deed, 1)
        player.claimTileAllowance += TILES_PER_DEED
        player.sendMessage("Your land claim allowance grows to ${player.claimTileAllowance} tiles.")
    }
}
