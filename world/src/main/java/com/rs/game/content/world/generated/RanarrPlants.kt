package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import com.rs.utils.Ticks
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq

private val RESPAWN_TICKS = Ticks.fromMinutes(2)

@ServerStartupEvent
fun mapRanarrPlants() {
    onObjectClick(Loc.ranarr_plant) { (player, obj) ->
        if (!player.inventory.hasFreeSlots()) {
            player.sendMessage("You do not have enough inventory space to pick the ranarr weed.")
            return@onObjectClick
        }
        player.anim(Seq.seq_827)
        player.lock(1)
        player.inventory.addItem(Obj.unidentified_ranarr, 1)
        player.sendMessage("You pick some ranarr weed.", true)
        World.removeObjectTemporary(obj, RESPAWN_TICKS)
    }
}
