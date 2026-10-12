package org.darkan.core.map

import org.darkan.core.EnvVars

object WorldWindow {

    const val REGION_TILES = 64
    const val WINDOW_TILES = 15_000
    const val OVERLAP_TILES = 0
    const val STRIDE_TILES = WINDOW_TILES - OVERLAP_TILES
    const val HANDOVER_MARGIN = 0
    const val GRID_WINDOWS = 26
    const val WINDOWS_PER_AXIS = GRID_WINDOWS
    const val GRID_TILES = GRID_WINDOWS * WINDOW_TILES
    const val WORLD_TILES = GRID_TILES
    const val FIRST_LETTER = 'A'
    const val CENTER_SECTOR_X = 12
    const val CENTER_SECTOR_Y = 13
    const val WORLD_ORIGIN_X = CENTER_SECTOR_X * STRIDE_TILES / REGION_TILES * REGION_TILES
    const val WORLD_ORIGIN_Y = CENTER_SECTOR_Y * STRIDE_TILES / REGION_TILES * REGION_TILES

    val generated: Set<Pair<Int, Int>> by lazy {
        EnvVars.generatedSectors.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.map {
            sectorOf(it) ?: error("GENERATED_SECTORS names '$it', which is outside AA..ZZ")
        }.toSet()
    }

    fun inGrid(sectorX: Int, sectorY: Int): Boolean = sectorX in 0 until GRID_WINDOWS && sectorY in 0 until GRID_WINDOWS

    fun isGenerated(sectorX: Int, sectorY: Int): Boolean = inGrid(sectorX, sectorY) && (sectorX to sectorY) in generated

    fun sectorName(sectorX: Int, sectorY: Int): String {
        val column = FIRST_LETTER + sectorX.coerceIn(0, GRID_WINDOWS - 1)
        val row = FIRST_LETTER + (GRID_WINDOWS - 1 - sectorY).coerceIn(0, GRID_WINDOWS - 1)
        return "$row$column"
    }

    fun sectorOf(name: String): Pair<Int, Int>? {
        if (name.length != 2) return null
        val row = name[0] - FIRST_LETTER
        val column = name[1] - FIRST_LETTER
        if (row !in 0 until GRID_WINDOWS || column !in 0 until GRID_WINDOWS) return null
        return column to (GRID_WINDOWS - 1 - row)
    }

    fun origin(window: Int): Int = window.coerceIn(0, GRID_WINDOWS - 1) * STRIDE_TILES / REGION_TILES * REGION_TILES

    fun owner(global: Int): Int {
        var window = (global / STRIDE_TILES).coerceIn(0, GRID_WINDOWS - 1)
        while (window < GRID_WINDOWS - 1 && origin(window + 1) <= global) window++
        while (window > 0 && origin(window) > global) window--
        return window
    }

    fun worldId(windowX: Int, windowY: Int): Int =
        if (windowX == CENTER_SECTOR_X && windowY == CENTER_SECTOR_Y && EnvVars.windowHomeWorldId > 0) EnvVars.windowHomeWorldId
        else EnvVars.windowWorldIdBase + windowY * WINDOWS_PER_AXIS + windowX

    fun port(windowX: Int, windowY: Int): Int = EnvVars.windowPortBase + windowY * WINDOWS_PER_AXIS + windowX

    fun toLocal(global: Int, window: Int): Int = global - origin(window)

    fun toGlobal(local: Int, window: Int): Int = local + origin(window)

    fun contains(window: Int, global: Int): Boolean = global >= origin(window) && global < origin(window) + WINDOW_TILES
}
