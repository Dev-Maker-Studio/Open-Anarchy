package com.rs.game.content.skills.sailing

import org.darkan.core.map.WorldWindow

fun sectorName(sectorX: Int, sectorY: Int): String = WorldWindow.sectorName(sectorX, sectorY)

fun sectorCoordinates(name: String): Pair<Int, Int>? = WorldWindow.sectorOf(name)
