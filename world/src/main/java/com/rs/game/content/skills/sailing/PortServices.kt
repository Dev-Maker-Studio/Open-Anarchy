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
    onObjectClick(Loc.sailing_gangplank, Loc.sailing_mooring_post) { e ->
        val site = PortSites.nearest(e.obj.tile.x, e.obj.tile.y, PortSites.ZONE_RADIUS) ?: return@onObjectClick
        if (e.opNum == 2) e.player.sendInputName("Whose boat would you like to board?") { name -> Voyages.boardFriend(e.player, site, name) } else Voyages.board(e.player, site)
    }
    onObjectClick(Loc.sailing_notice_board) { (player, obj) ->
        val site = PortSites.nearest(obj.tile.x, obj.tile.y, PortSites.ZONE_RADIUS) ?: return@onObjectClick
        SailingScreens.openNoticeBoard(player, site)
    }
    onObjectClick(Loc.sailing_ledger_table) { e ->
        val site = PortSites.nearest(e.obj.tile.x, e.obj.tile.y, PortSites.ZONE_RADIUS) ?: return@onObjectClick
        if (e.opNum == 2) Courier.deliver(e.player, site) else Courier.collect(e.player, site)
    }
    onObjectClick(Loc.sailing_salvaging_station) { e ->
        val voyage = Voyages.of(e.player)
        if (voyage != null) return@onObjectClick Stations.sort(e.player, voyage)
        if (e.opNum == 2) return@onObjectClick RepairKitCrafting.menu(e.player)
        val site = PortSites.nearest(e.obj.tile.x, e.obj.tile.y, PortSites.ZONE_RADIUS) ?: return@onObjectClick
        Shipwright.unload(e.player, site)
    }
    onObjectClick(*WreckField.LOCS.toTypedArray(), checkDistance = false) { (player, obj) ->
        val spot = SeaContent.wreckAt(obj.tile.x, obj.tile.y) ?: return@onObjectClick
        Voyages.startSalvage(player, spot)
    }
    mapBoatObjects()
}

private fun mapBoatObjects() {
    onObjectClick(Loc.sailing_helm, checkDistance = false) { e ->
        val voyage = Voyages.of(e.player)
        if (voyage == null) {
            if (Voyages.guestOf(e.player) != null) Voyages.leaveGuest(e.player)
            return@onObjectClick
        }
        if (e.opNum == 2) Voyages.leave(e.player) else VoyageControls.toggleHelm(e.player, voyage)
    }
    onObjectClick(Loc.sailing_cargo_hold, checkDistance = false) { e ->
        val voyage = Voyages.of(e.player) ?: return@onObjectClick
        SailingScreens.openHold(e.player, voyage.boat)
    }
    onObjectClick(Loc.sailing_cannon, checkDistance = false) { e -> Voyages.of(e.player)?.let { VoyageControls.toggleCannon(e.player, it) } }
    onObjectClick(Loc.sailing_salvaging_hook, checkDistance = false) { e -> Voyages.of(e.player)?.let { Voyages.startSalvageNearest(e.player, it) } }
    onObjectClick(Loc.sailing_anchor, checkDistance = false) { e -> Voyages.of(e.player)?.let { VoyageControls.toggleAnchor(e.player, it) } }
    onObjectClick(Loc.sailing_wind_catcher, checkDistance = false) { e -> Voyages.of(e.player)?.let { VoyageControls.releaseMote(e.player, it) } }
    onObjectClick(Loc.sailing_trawling_net, checkDistance = false) { e ->
        val voyage = Voyages.of(e.player) ?: return@onObjectClick
        voyage.deck.netAt(e.obj.tile.x, e.obj.tile.y)?.let { Trawling.open(e.player, voyage, it) }
    }
    onObjectClick(Loc.sailing_crystal_extractor, checkDistance = false) { e -> Voyages.of(e.player)?.let { Stations.harvest(e.player, it) } }
}
