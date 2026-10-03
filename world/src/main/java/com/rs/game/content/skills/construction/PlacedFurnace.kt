package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj

const val PICK_UP_OPTION = "Pick Up"

fun pickUpFurnace(player: Player, furnace: GameObject) {
    if (furnace.id != Loc.built_furnace || World.getObject(furnace.tile, furnace.type)?.id != furnace.id) return
    if (!ClaimManager.canConstruct(player, furnace.tile)) return
    if (!player.inventory.hasFreeSlots()) {
        player.sendMessage("You don't have enough inventory space to pick that up.")
        return
    }
    World.removeObject(furnace)
    player.inventory.addItem(Obj.furnace_item, 1)
    player.sendMessage("You pick up the furnace.")
}
