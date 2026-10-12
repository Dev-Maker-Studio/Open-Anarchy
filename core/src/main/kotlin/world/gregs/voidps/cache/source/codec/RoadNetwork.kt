package world.gregs.voidps.cache.source.codec

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RoadNetwork(
    private val sectorX: Int,
    private val sectorY: Int,
    private val sectorTiles: Int,
    private val centreX: Int,
    private val centreY: Int,
    private val probe: (Int, Int) -> Int,
    private val wander: (Double, Double) -> Double
) {

    class Arm(val horizontal: Boolean, val direction: Int, val base: Int, val lateral: Int, val offsets: ShortArray) {

        val length get() = offsets.size

        fun along(x: Int, y: Int) = direction * ((if (horizontal) x else y) - base)

        fun across(x: Int, y: Int) = (if (horizontal) y else x) - lateral

        fun tile(along: Int, across: Int): Pair<Int, Int> =
            if (horizontal) base + direction * along to lateral + across else lateral + across to base + direction * along

        fun touches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean {
            val low = if (horizontal) minX else minY
            val high = if (horizontal) maxX else maxY
            val first = if (direction > 0) low - base else base - high
            val last = if (direction > 0) high - base else base - low
            val from = if (horizontal) minY - lateral else minX - lateral
            val to = if (horizontal) maxY - lateral else maxX - lateral
            for (along in max(first, 0)..min(last, length - 1)) {
                val offset = offsets[along]
                if (offset + WIDTH - 1 >= from && offset <= to) return true
            }
            return false
        }

        fun gap(x: Int, y: Int): Int {
            val along = along(x, y)
            if (along !in 0 until length) return Int.MAX_VALUE
            val across = across(x, y)
            val offset = offsets[along]
            return max(0, if (across < offset) offset - across else across - offset - WIDTH + 1)
        }
    }

    val arms: List<Arm> by lazy {
        if (centreX !in sectorX until sectorX + sectorTiles || centreY !in sectorY until sectorY + sectorTiles) emptyList()
        else {
            val result = arrayOfNulls<Arm>(ARMS)
            val workers = List(ARMS) { index -> Thread { result[index] = build(index) }.also { it.start() } }
            workers.forEach { it.join() }
            result.map { it!! }
        }
    }

    fun touches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean = arms.any { it.touches(minX, minY, maxX, maxY) }

    fun gap(x: Int, y: Int): Double = arms.minOfOrNull { it.gap(x, y) }?.takeIf { it != Int.MAX_VALUE }?.toDouble() ?: Double.MAX_VALUE

    private fun build(index: Int): Arm {
        val horizontal = index >= 2
        val direction = if (index % 2 == 0) 1 else -1
        val base = (if (horizontal) centreX else centreY) + if (direction < 0) 1 else 0
        val lateral = if (horizontal) centreY else centreX
        val start = if (horizontal) sectorX else sectorY
        val length = if (direction > 0) start + sectorTiles - base else base - start + 1
        val probeArm = Arm(horizontal, direction, base, lateral, ShortArray(length))
        val lanes = 2 * BAND + 2 * PAD + WIDTH
        val codes = ByteArray(length * lanes)
        val workers = List(THREADS) { worker ->
            Thread {
                for (along in worker * SAMPLE until length step THREADS * SAMPLE) {
                    for (lane in 0 until lanes step SAMPLE) {
                        val (x, y) = probeArm.tile(along, lane - BAND - PAD)
                        val code = probe(x, y).toByte()
                        for (row in along until min(along + SAMPLE, length)) {
                            for (column in lane until lane + SAMPLE) codes[row * lanes + column] = code
                        }
                    }
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        spread(codes, length, lanes)
        val offsets = solve(index, codes, length, lanes)
        return Arm(horizontal, direction, base, lateral, offsets)
    }

    private fun spread(codes: ByteArray, length: Int, lanes: Int) {
        for (along in 0 until length) {
            sweep(codes, along * lanes, 1, lanes, HAZARD, LATERAL)
        }
        for (lane in 0 until lanes) {
            sweep(codes, lane, lanes, length, LATERAL, FULL)
        }
    }

    private fun sweep(codes: ByteArray, first: Int, stride: Int, count: Int, source: Int, mark: Int) {
        var last = -SAFE - 1
        for (step in 0 until count) {
            if (codes[first + step * stride].toInt() and source != 0) last = step
            if (step - last <= SAFE) codes[first + step * stride] = (codes[first + step * stride].toInt() or mark).toByte()
        }
        last = count + SAFE
        for (step in count - 1 downTo 0) {
            if (codes[first + step * stride].toInt() and source != 0) last = step
            if (last - step <= SAFE) codes[first + step * stride] = (codes[first + step * stride].toInt() or mark).toByte()
        }
    }

    private fun solve(index: Int, codes: ByteArray, length: Int, lanes: Int): ShortArray {
        val positions = 2 * BAND + 1
        val states = positions * TURNS
        val back = ByteArray(length * states)
        var previous = DoubleArray(states) { Double.MAX_VALUE }
        var current = DoubleArray(states)
        previous[BAND * TURNS + 1] = 0.0
        for (along in 1 until length) {
            current.fill(Double.MAX_VALUE)
            val reach = min(BAND, along * WEDGE / 4)
            for (position in BAND - reach..BAND + reach) {
                val lane = position + PAD
                val here = along * lanes
                val cost = tileCost(index, codes, here + lane, along, position - BAND)
                for (turn in 0 until TURNS) {
                    val step = turn - 1
                    val origin = position - step
                    if (origin !in 0 until positions || along < STRAIGHT && step != 0) continue
                    var best = Double.MAX_VALUE
                    var from = 0
                    for (before in 0 until TURNS) {
                        val value = previous[origin * TURNS + before]
                        if (value == Double.MAX_VALUE) continue
                        val total = value + if (before != turn) TURN_COST else 0.0
                        if (total < best) {
                            best = total
                            from = before
                        }
                    }
                    if (best == Double.MAX_VALUE) continue
                    current[position * TURNS + turn] = best + cost + abs(step) * SLOPE_COST
                    back[along * states + position * TURNS + turn] = from.toByte()
                }
            }
            val swap = previous
            previous = current
            current = swap
        }
        var state = previous.indices.minBy { previous[it] }
        val offsets = ShortArray(length)
        for (along in length - 1 downTo 1) {
            val position = state / TURNS
            val turn = state % TURNS
            offsets[along] = (position - BAND).toShort()
            state = (position - (turn - 1)) * TURNS + back[along * states + state].toInt()
        }
        return offsets
    }

    private fun tileCost(index: Int, codes: ByteArray, cell: Int, along: Int, offset: Int): Double {
        val here = codes[cell].toInt() and 0xFF
        val next = codes[cell + WIDTH - 1].toInt() and 0xFF
        if ((here or next) and FULL != 0) return HAZARD_COST
        val left = codes[cell - REACH].toInt() and BIOME_MASK
        val right = codes[cell + WIDTH - 1 + REACH].toInt() and BIOME_MASK
        val terrain = when {
            (here or next) and ROUGH != 0 -> ROUGH_COST
            left != right -> BORDER_COST
            else -> INTERIOR_COST
        }
        return terrain + wander(along / WANDER_WAVELENGTH + index * WANDER_STRIDE, offset / WANDER_WAVELENGTH) * WANDER_WEIGHT + abs(offset) * CENTRE_PULL
    }

    companion object {
        const val WIDTH = 2
        const val HAZARD = 0x10
        const val ROUGH = 0x20
        const val BIOME_MASK = 0x0F
        private const val LATERAL = 0x40
        private const val FULL = 0x80
        private const val ARMS = 4
        private const val THREADS = 4
        private const val TURNS = 3
        private const val BAND = 400
        private const val SAFE = 5
        private const val SAMPLE = 2
        private const val REACH = 6
        private const val PAD = 6
        private const val WEDGE = 3
        private const val STRAIGHT = 16
        private const val HAZARD_COST = 1.0e4
        private const val ROUGH_COST = 12.0
        private const val INTERIOR_COST = 3.0
        private const val BORDER_COST = 0.0
        private const val SLOPE_COST = 0.15
        private const val TURN_COST = 0.6
        private const val CENTRE_PULL = 0.0012
        private const val WANDER_WEIGHT = 0.5
        private const val WANDER_WAVELENGTH = 60.0
        private const val WANDER_STRIDE = 997.0
    }
}
