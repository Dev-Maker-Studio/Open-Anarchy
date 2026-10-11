package com.rs.game.content.skills.sailing

import world.gregs.voidps.cache.source.codec.WorldGenerator

object Wind {
    private const val DIRECTIONS = 8
    private const val STEP_DEGREES = 45
    private const val DRIFT_TICKS = 40L
    private const val FULL_TURN = 360
    private const val HALF_TURN = 180
    private const val HELPFUL_ARC = 45

    fun directionAt(sector: String, ticks: Long): Int =
        Math.floorMod((Math.floorMod(WorldGenerator.DEFAULT_SEED xor sector.hashCode().toLong(), DIRECTIONS.toLong()) * STEP_DEGREES + ticks / DRIFT_TICKS), FULL_TURN.toLong()).toInt()

    fun helps(heading: Int, wind: Int): Boolean =
        Math.abs(Math.floorMod(heading - wind + HALF_TURN, FULL_TURN) - HALF_TURN) <= HELPFUL_ARC
}
