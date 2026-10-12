package world.gregs.voidps.type

import world.gregs.voidps.type.area.Rectangle
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

object Distance {

    fun getNearest(tile: Tile, width: Int, height: Int, target: Tile) = tile.copy(
        x = getNearest(tile.x, width, target.x),
        y = getNearest(tile.y, height, target.y)
    )

    fun Rectangle.nearestTo(tile: Tile) = Tile(
        x = getNearest(minX, width, tile.x),
        y = getNearest(minY, height, tile.y)
    )

    fun getNearest(source: Int, size: Int, target: Int): Int {
        val max = source + size - 1
        return when {
            target > max -> max
            target < source -> source
            else -> target
        }
    }

    fun within(x1: Int, y1: Int, level1: Int, x2: Int, y2: Int, level2: Int, radius: Int): Boolean {
        return level1 == level2 && within(x1, y1, x2, y2, radius)
    }

    fun within(x1: Int, y1: Int, x2: Int, y2: Int, radius: Int): Boolean {
        return abs(x1 - x2) <= radius && abs(y1 - y2) <= radius
    }

    fun manhattan(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        return abs(x1 - x2) + abs(y1 - y2)
    }

    fun chebyshev(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        return abs(x1 - x2).coerceAtLeast(abs(y1 - y2))
    }

    fun euclidean(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        return sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2).toDouble())
    }

    fun euclidean(first: Tile, second: Tile, level: Boolean = true): Double {
        if (!level) {
            return euclidean(first.x, first.y, second.x, second.y)
        }
        return euclidean(first.x, first.y, first.level, second.x, second.y, second.level)
    }

    fun euclidean(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Double {
        return sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2) + (z1 - z2) * (z1 - z2).toDouble())
    }

    fun hamming(first: String, second: String): Int {
        if (first.length != second.length) {
            return -1
        }
        return first.zip(second).count { (a, b) -> a != b }
    }

    fun levenshtein(first: String, second: String, max: Int = -1): Int {
        if (first == second) {
            return 0
        }
        var longestLength = first.length
        var shortestLength = second.length
        if (max >= 0 && abs(longestLength - shortestLength) > max) {
            return max + 1
        }
        if (longestLength == 0) {
            return shortestLength
        }
        if (shortestLength == 0) {
            return longestLength
        }
        var longest = first
        var shortest = second
        if (longestLength < shortestLength) {
            val temp = longestLength
            longestLength = shortestLength
            shortestLength = temp
            longest = second
            shortest = first
        }
        val cost = IntArray(shortestLength + 1)
        for (i in 0..shortestLength) {
            cost[i] = i
        }
        for (i in 0 until longestLength) {
            cost[0] = i + 1
            var previous = i
            var min = previous
            for (j in 0 until shortestLength) {
                val act = previous + if (longest[i] == shortest[j]) 0 else 1
                previous = cost[j + 1]
                cost[j + 1] = min(previous + 1, min(cost[j] + 1, act))
                if (previous < min) {
                    min = previous
                }
            }
            if (max in 0 until min) {
                return max + 1
            }
        }
        return if (max >= 0 && cost[shortestLength] > max) max + 1 else cost[shortestLength]
    }
}