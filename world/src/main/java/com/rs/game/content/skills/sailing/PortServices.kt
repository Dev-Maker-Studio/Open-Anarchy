package com.rs.game.content.skills.sailing

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.interceptNpcClick
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Loc

private const val TALK = "Talk-to"

@ServerStartupEvent
fun mapPortServices() {
    if (!EnvVars.generateMaps) return
    interceptNpcClick(TALK) { player, npc ->
        val staff = npc as? PortStaffNPC ?: return@interceptNpcClick false
        when (staff.role) {
            PortRole.SHIPWRIGHT -> Shipwright.talk(player, staff)
            PortRole.PORT_MASTER -> PortMaster.talk(player, staff)
        }
        true
    }
    onObjectClick(Loc.sailing_shipyard_shipplank_on, Loc.acs_battleships_playerpost) { (player, obj) ->
        val site = PortSites.nearest(obj.tile.x, obj.tile.y, PortSites.ZONE_RADIUS) ?: return@onObjectClick
        Voyages.board(player, site)
    }
    onObjectClick(Loc.fai_falador_noticeboard2) { (player, obj) ->
        val site = PortSites.nearest(obj.tile.x, obj.tile.y, PortSites.ZONE_RADIUS) ?: return@onObjectClick
        Courier.open(player, site)
    }
    onObjectClick(*WreckField.LOCS.toTypedArray(), checkDistance = false) { (player, obj) ->
        val spot = SeaContent.wreckAt(obj.tile.x, obj.tile.y) ?: return@onObjectClick
        Voyages.startSalvage(player, spot)
    }
}
