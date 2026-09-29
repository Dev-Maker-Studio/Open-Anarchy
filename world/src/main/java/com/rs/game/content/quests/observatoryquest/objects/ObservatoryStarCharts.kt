package com.rs.game.content.quests.observatoryquest

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick

private const val STAR_CHART_INTERFACE = 104

private const val STAR_CHART_25578 = 25578
private const val STAR_CHART_25579 = 25579
private const val STAR_CHART_25580 = 25580
private const val STAR_CHART_25581 = 25581
private const val STAR_CHART_25582 = 25582
private const val STAR_CHART_25583 = 25583

@ServerStartupEvent
fun mapObservatoryStarCharts() {

    onObjectClick(
        STAR_CHART_25578,
        STAR_CHART_25579,
        STAR_CHART_25580,
        STAR_CHART_25581,
        STAR_CHART_25582,
        STAR_CHART_25583
    ) { (player) ->

        player.interfaceManager.sendInterface(
            STAR_CHART_INTERFACE
        )
    }
}