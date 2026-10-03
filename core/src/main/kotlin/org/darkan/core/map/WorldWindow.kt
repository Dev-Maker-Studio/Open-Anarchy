package org.darkan.core.map

import org.darkan.core.EnvVars

object WorldWindow {

    const val WORLD_TILES = 1_000_000
    const val WINDOW_TILES = 16384
    const val OVERLAP_TILES = 2048
    const val STRIDE_TILES = WINDOW_TILES - OVERLAP_TILES
    const val HANDOVER_MARGIN = OVERLAP_TILES / 2
    const val GRID_WINDOWS = 26
    const val GRID_TILES = (GRID_WINDOWS - 1) * STRIDE_TILES + WINDOW_TILES
    const val WINDOWS_PER_AXIS = (WORLD_TILES - WINDOW_TILES + STRIDE_TILES - 1) / STRIDE_TILES + 1

    fun origin(window: Int): Int = minOf(window * STRIDE_TILES, WORLD_TILES - WINDOW_TILES)

    fun owner(global: Int): Int {
        if (global < STRIDE_TILES + HANDOVER_MARGIN) return 0
        return ((global - HANDOVER_MARGIN) / STRIDE_TILES).coerceAtMost(WINDOWS_PER_AXIS - 1)
    }

    fun worldId(windowX: Int, windowY: Int): Int =
        if (windowX == 0 && windowY == 0 && EnvVars.windowHomeWorldId > 0) EnvVars.windowHomeWorldId
        else EnvVars.windowWorldIdBase + windowY * WINDOWS_PER_AXIS + windowX

    fun port(windowX: Int, windowY: Int): Int = EnvVars.windowPortBase + windowY * WINDOWS_PER_AXIS + windowX

    fun toLocal(global: Int, window: Int): Int = global - origin(window)

    fun toGlobal(local: Int, window: Int): Int = local + origin(window)

    fun contains(window: Int, global: Int): Boolean = global >= origin(window) && global < origin(window) + WINDOW_TILES
}
