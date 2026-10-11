package com.rs.game.content.skills.sailing

import java.util.Random

enum class ChartKind(val sailingLevel: Int, val xp: Int) {
    GENERIC(1, 35),
    SPYGLASS(1, 50),
    CRATE(12, 75),
    CURRENT(22, 125),
    DIVING(38, 175),
    WEATHER(57, 250)
}

class ChartPoint(val sea: String, val kind: ChartKind, val x: Int, val y: Int)

object ChartField {
    const val CELL = 64
    private const val POINTS_PERCENT = 40

    fun pointIn(seed: Long, sea: String, cellX: Int, cellY: Int, threat: Int): ChartPoint? {
        val random = Random(seed xor sea.hashCode().toLong() xor (cellX.toLong() shl 32) xor cellY.toLong())
        if (random.nextInt(100) >= POINTS_PERCENT) return null
        val kinds = ChartKind.entries.filter { it.sailingLevel <= threat * LEVEL_PER_THREAT }
        return ChartPoint(sea, kinds[random.nextInt(kinds.size)], cellX * CELL + random.nextInt(CELL), cellY * CELL + random.nextInt(CELL))
    }

    private const val LEVEL_PER_THREAT = 8
}

class CaptainsLog {
    val charted = mutableSetOf<String>()

    fun record(point: ChartPoint): Int {
        val key = "${point.sea}:${point.x}:${point.y}"
        return if (charted.add(key)) point.kind.xp else 0
    }
}
