package com.rs.game.content.quests.observatoryquest

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Loc

@ServerStartupEvent
fun mapObservatoryStarCharts() {
    onObjectClick(Loc.qip_obs_star_chart1, Loc.qip_obs_star_chart2, Loc.qip_obs_star_chart3, Loc.qip_obs_star_chart4, Loc.qip_obs_star_chart5, Loc.qip_obs_star_chart6) { (player) ->
        player.interfaceManager.sendInterface(Interface.interface_104)
    }
}
