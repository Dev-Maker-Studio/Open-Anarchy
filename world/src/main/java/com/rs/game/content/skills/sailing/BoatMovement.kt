package com.rs.game.content.skills.sailing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val FULL_TURN_DEGREES = 360
private const val TURN_DEGREES_PER_TICK = 15
private const val BURST_SPEED = 0.5
private const val BURST_DECAY_TICKS = 4
private const val REVERSE_FRACTION = 0.5
private const val HALF_TURN_DEGREES = 180

enum class SpeedSetting(val fraction: Double) {
    STOP(0.0), SLOW(0.5), FULL(1.0), REVERSE(-REVERSE_FRACTION)
}

class BoatMovement(
    private val boat: Boat,
    var x: Double,
    var y: Double,
    var heading: Int = 0
) {
    var speed = 0.0
        private set
    var sailsSet = false
    var anchored = false
    var setting = SpeedSetting.FULL
    var targetHeading = heading
    var slow = 1.0
    private var bursts = 0
    private var burstTicksLeft = 0
    private var windBoost = false

    val boosted: Boolean get() = bursts > 0

    val boostTicksLeft: Int get() = burstTicksLeft

    val topSpeed: Double get() {
        val capped = (boat.baseSpeed + bursts * BURST_SPEED).coerceAtMost(boat.speedCap)
        return if (windBoost) capped + BURST_SPEED else capped
    }

    fun addBurst(ticks: Int) {
        bursts++
        burstTicksLeft = ticks
    }

    fun setWind(boost: Boolean) {
        windBoost = boost
    }

    fun place(newX: Double, newY: Double, newHeading: Int) {
        x = newX
        y = newY
        heading = newHeading
        targetHeading = newHeading
        speed = 0.0
    }

    fun dropSpeed() {
        speed = 0.0
    }

    fun halt() {
        speed = 0.0
        sailsSet = false
    }

    fun tick(isWater: (Int, Int) -> Boolean) {
        turn()
        updateBursts()
        val goal = if (!sailsSet || anchored) 0.0 else topSpeed * setting.fraction * slow
        speed = approach(speed, goal, boat.sail.acceleration)
        if (speed == 0.0) return
        val radians = Math.toRadians(heading.toDouble())
        val nx = x + sin(radians) * speed
        val ny = y + cos(radians) * speed
        if (isWater(nx.toInt(), ny.toInt()) && boat.footprintClear(nx.toInt(), ny.toInt(), heading, isWater)) {
            x = nx
            y = ny
        } else {
            speed = 0.0
        }
    }

    private fun turn() {
        if (heading == targetHeading) return
        val diff = ((targetHeading - heading + FULL_TURN_DEGREES + HALF_TURN_DEGREES) % FULL_TURN_DEGREES) - HALF_TURN_DEGREES
        val step = diff.coerceIn(-TURN_DEGREES_PER_TICK, TURN_DEGREES_PER_TICK)
        heading = (heading + step + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
    }

    private fun updateBursts() {
        if (bursts == 0) return
        if (burstTicksLeft > 0) {
            burstTicksLeft--
            return
        }
        burstTicksLeft = BURST_DECAY_TICKS
        bursts--
    }

    private fun approach(current: Double, goal: Double, step: Double): Double =
        if (abs(goal - current) <= step) goal else current + if (goal > current) step else -step
}
