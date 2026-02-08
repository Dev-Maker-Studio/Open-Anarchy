package org.darkan.tools.cache.texture

import kotlin.math.cos
import kotlin.math.sin

/**
 * Utility singleton for texture rasterization.
 * Contains precomputed sine/cosine lookup tables and dimension tracking.
 */
object TextureRaster {
    /** Sine lookup table (256 entries, values scaled to 12-bit fixed point) */
    var PALETTE_SIN: IntArray? = null
        private set

    /** Cosine lookup table (256 entries, values scaled to 12-bit fixed point) */
    var PALETTE_COS: IntArray? = null
        private set

    /** Precomputed horizontal texture coordinate mapping table */
    var horizontalTable: IntArray? = null
        private set

    /** Precomputed vertical texture coordinate mapping table */
    var verticalTable: IntArray? = null
        private set

    /** Current texture width */
    var currentWidth: Int = 0
        private set

    /** Current texture height */
    var currentHeight: Int = 0
        private set

    /** Width - 1, used for wrapping */
    var rowEnd: Int = 0
        private set

    /** Height - 1, used for wrapping */
    var columnEnd: Int = 0
        private set

    /** Width * 32, used for emboss calculations */
    var widthScale: Int = 0
        private set

    /** Wall-clock start time (nanos) for the current texture generation pass.
     *  Set before each top-level generation in TextureLoader.
     *  Checked in cache access (getPalette), row loops, and leaf operation loops;
     *  if it exceeds [MAX_GENERATION_NANOS] a [TextureGenerationException] is thrown. */
    var generationStartNanos: Long = 0L
    const val MAX_GENERATION_NANOS: Long = 5_000_000_000L // 5 seconds

    /**
     * Initialize the sine/cosine lookup tables.
     * Call this once at startup before using texture operations.
     */
    fun calculateTable() {
        if (PALETTE_SIN == null || PALETTE_COS == null) {
            PALETTE_SIN = IntArray(256)
            PALETTE_COS = IntArray(256)
            for (i in 0 until 256) {
                val angle = 6.283185307179586 * (i / 255.0)
                PALETTE_SIN!![i] = (sin(angle) * 4096.0).toInt()
                PALETTE_COS!![i] = (cos(angle) * 4096.0).toInt()
            }
        }
    }

    /**
     * Set the texture raster dimensions and compute coordinate mapping tables.
     *
     * @param width The texture width
     * @param height The texture height
     */
    fun setDimensions(width: Int, height: Int) {
        if (width != currentWidth) {
            horizontalTable = IntArray(width)
            for (idx in 0 until width) {
                horizontalTable!![idx] = (idx shl 12) / width
            }
            rowEnd = width - 1
            currentWidth = width
            widthScale = width * 32
        }

        if (currentHeight != height) {
            if (currentWidth != height) {
                verticalTable = IntArray(height)
                for (idx in 0 until height) {
                    verticalTable!![idx] = (idx shl 12) / height
                }
            } else {
                // Optimization: reuse horizontal table for square textures
                verticalTable = horizontalTable
            }
            columnEnd = height - 1
            currentHeight = height
        }
    }
}
