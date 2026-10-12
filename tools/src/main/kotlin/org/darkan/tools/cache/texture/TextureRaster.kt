package org.darkan.tools.cache.texture

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class RasterContext(
    @JvmField val currentWidth: Int,
    @JvmField val currentHeight: Int
) {
    @JvmField
    val horizontalTable: IntArray = IntArray(currentWidth) { (it shl 12) / currentWidth }

    @JvmField
    val verticalTable: IntArray = if (currentHeight == currentWidth) {
        horizontalTable
    } else {
        IntArray(currentHeight) { (it shl 12) / currentHeight }
    }

    @JvmField
    val rowEnd: Int = currentWidth - 1

    @JvmField
    val columnEnd: Int = currentHeight - 1

    @JvmField
    val widthScale: Int = currentWidth * 32
}

object TextureRaster {
    private const val TWO_PI = 6.283185307179586

    @JvmField
    val PALETTE_SIN: IntArray = IntArray(256) { (sin(TWO_PI * (it / 255.0)) * 4096.0).toInt() }

    @JvmField
    val PALETTE_COS: IntArray = IntArray(256) { (cos(TWO_PI * (it / 255.0)) * 4096.0).toInt() }

    const val MAX_GENERATION_NANOS: Long = 5_000_000_000L

    private val contexts = ConcurrentHashMap<Long, RasterContext>()

    fun context(width: Int, height: Int): RasterContext {
        val key = (width.toLong() shl 32) or (height.toLong() and 0xFFFFFFFFL)
        contexts[key]?.let { return it }
        val created = RasterContext(width, height)
        return contexts.putIfAbsent(key, created) ?: created
    }

    fun calculateTable() {
    }
}
