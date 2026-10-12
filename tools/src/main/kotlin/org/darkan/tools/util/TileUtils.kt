package org.darkan.tools.util

import kotlin.math.cos

object TileUtils {

    private val COSINE = IntArray(16384)

    init {
        val step = 3.834951969714103E-4
        for (i in 0 until 16384) {
            COSINE[i] = (16384.0 * cos(i * step)).toInt()
        }
    }

    @JvmStatic
    fun calculateTileheight(x: Int, y: Int): Int {
        var height = perlinNoise(45365 + x, y + 91923, 4) - 128 +
                (perlinNoise(x + 10294, 37821 + y, 2) - 128 shr 1) +
                (perlinNoise(x, y, 1) - 128 shr 2)

        height = (height * 0.3).toInt() + 35

        return when {
            height < 10 -> 10
            height > 60 -> 60
            else -> height
        }
    }

    @JvmStatic
    fun perlinNoise(x: Int, y: Int, frequencyReciprocal: Int): Int {
        val adjX = x / frequencyReciprocal
        val angleX = x and (frequencyReciprocal - 1)
        val adjY = y / frequencyReciprocal
        val angleY = y and (frequencyReciprocal - 1)

        val noiseBase = randomNoiseWeighedSum(adjX, adjY)
        val noiseEast = randomNoiseWeighedSum(adjX + 1, adjY)
        val noiseSouth = randomNoiseWeighedSum(adjX, adjY + 1)
        val noiseSouthEast = randomNoiseWeighedSum(adjX + 1, adjY + 1)

        val interpolatedNoiseNorth = interpolate(noiseBase, noiseEast, angleX, frequencyReciprocal)
        val interpolatedNoiseSouth = interpolate(noiseSouth, noiseSouthEast, angleX, frequencyReciprocal)
        return interpolate(interpolatedNoiseNorth, interpolatedNoiseSouth, angleY, frequencyReciprocal)
    }

    @JvmStatic
    fun randomNoiseWeighedSum(x: Int, y: Int): Int {
        val corners = randomNoise(x - 1, y - 1) + randomNoise(1 + x, y - 1) +
                randomNoise(x - 1, 1 + y) + randomNoise(x + 1, 1 + y)
        val sides = randomNoise(x - 1, y) + randomNoise(1 + x, y) +
                randomNoise(x, y - 1) + randomNoise(x, y + 1)
        val center = randomNoise(x, y)
        return corners / 16 + sides / 8 + center / 4
    }

    @JvmStatic
    fun randomNoise(x: Int, y: Int): Int {
        var n = y * 57 + x
        n = n xor (n shl 13)
        val value = n * (n * n * 15731 + 789221) + 1376312589 and 0x7fffffff
        return value shr 19 and 0xff
    }

    @JvmStatic
    fun interpolate(a: Int, b: Int, angle: Int, frequencyReciprocal: Int): Int {
        val cosineIndex = angle * 8192 / frequencyReciprocal
        val cosine = (65536 - COSINE[cosineIndex]) shr 1
        return ((65536 - cosine) * a shr 16) + (cosine * b shr 16)
    }

    const val DEFAULT_PLANE_HEIGHT_OFFSET = 960

    const val HEIGHT_MULTIPLIER = 32

    const val MAGIC_X_OFFSET = 932731

    const val MAGIC_Y_OFFSET = 556238
}
