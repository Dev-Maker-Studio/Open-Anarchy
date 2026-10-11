package com.rs.game.content.skills.sailing

import org.darkan.core.map.WorldWindow

private const val LETTERS = 26
private const val FIRST_LETTER = 'A'

fun sectorName(sectorX: Int, sectorY: Int): String {
    val column = FIRST_LETTER + sectorX.coerceIn(0, LETTERS - 1)
    val row = FIRST_LETTER + (WorldWindow.GRID_WINDOWS - 1 - sectorY).coerceIn(0, LETTERS - 1)
    return "$row$column"
}

fun sectorCoordinates(name: String): Pair<Int, Int>? {
    if (name.length != 2) return null
    val row = name[0] - FIRST_LETTER
    val column = name[1] - FIRST_LETTER
    if (row !in 0 until LETTERS || column !in 0 until LETTERS) return null
    return column to (WorldWindow.GRID_WINDOWS - 1 - row)
}
