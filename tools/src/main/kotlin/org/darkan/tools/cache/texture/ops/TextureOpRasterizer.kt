package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import org.darkan.tools.cache.texture.TextureRaster
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 29: Shape rasterizer.
 * Draws primitive shapes (rectangles, ellipses, lines) onto the texture.
 */
class TextureOpRasterizer : TextureOperation(0, true) {
    private var shapes: Array<RasterizerShape?>? = null

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            rasterize(monochromeImageCache.getAllPaletteData())
        }
        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val width = TextureRaster.currentWidth
            val height = TextureRaster.currentHeight
            val monoBuffer = Array(height) { IntArray(width) }
            rasterize(monoBuffer)

            val allData = colorImageCache.getAllPaletteData()
            for (row in 0 until height) {
                val monoRow = monoBuffer[row]
                val rgbRow = allData[row]
                val redChannel = rgbRow[0]
                val greenChannel = rgbRow[1]
                val blueChannel = rgbRow[2]
                for (x in 0 until width) {
                    val value = monoRow[x]
                    blueChannel[x] = (value and 0xff) shl 4
                    greenChannel[x] = (value and 0xff00) shr 4
                    redChannel[x] = (value and 0xff0000) shr 12
                }
            }
        }
        return output
    }

    @Suppress("UNCHECKED_CAST")
    private fun rasterize(buffer: Array<out IntArray?>) {
        val width = TextureRaster.currentWidth
        val height = TextureRaster.currentHeight

        pixelBuffer = buffer as Array<IntArray>
        clipLeft = 0
        clipRight = TextureRaster.rowEnd
        clipTop = 0
        clipBottom = TextureRaster.columnEnd

        if (shapes != null) {
            for (shape in shapes!!) {
                shape?.draw(width, height)
            }
        }
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> {
                val count = buffer.readUnsignedByte()
                shapes = arrayOfNulls(count)
                for (i in 0 until count) {
                    val shapeType = buffer.readUnsignedByte()
                    shapes!![i] = when (shapeType) {
                        0 -> RasterizerRectangle.decode(buffer)
                        1 -> RasterizerEllipse.decode(buffer)
                        2 -> RasterizerLine.decode(buffer)
                        3 -> RasterizerGradientRect.decode(buffer)
                        else -> null
                    }
                }
            }
            1 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }

    companion object {
        var pixelBuffer: Array<IntArray>? = null
        var clipLeft: Int = 0
        var clipRight: Int = 100
        var clipTop: Int = 0
        var clipBottom: Int = 100
    }
}

/** Base class for rasterizer shapes */
sealed class RasterizerShape {
    abstract fun draw(width: Int, height: Int)
}

/** Filled rectangle shape */
class RasterizerRectangle(
    private val x: Int,
    private val y: Int,
    private val rectWidth: Int,
    private val rectHeight: Int,
    private val color: Int
) : RasterizerShape() {
    override fun draw(width: Int, height: Int) {
        val scaledX = x * width shr 12
        val scaledY = y * height shr 12
        val scaledWidth = rectWidth * width shr 12
        val scaledHeight = rectHeight * height shr 12

        val startX = scaledX.coerceIn(TextureOpRasterizer.clipLeft, TextureOpRasterizer.clipRight)
        val endX = (scaledX + scaledWidth).coerceIn(TextureOpRasterizer.clipLeft, TextureOpRasterizer.clipRight + 1)
        val startY = scaledY.coerceIn(TextureOpRasterizer.clipTop, TextureOpRasterizer.clipBottom)
        val endY = (scaledY + scaledHeight).coerceIn(TextureOpRasterizer.clipTop, TextureOpRasterizer.clipBottom + 1)

        for (py in startY until endY) {
            val row = TextureOpRasterizer.pixelBuffer!![py]
            for (px in startX until endX) {
                row[px] = color
            }
        }
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerRectangle {
            val x = buffer.readShort() and 0xFFFF
            val y = buffer.readShort() and 0xFFFF
            val w = buffer.readShort() and 0xFFFF
            val h = buffer.readShort() and 0xFFFF
            val color = buffer.readInt()
            return RasterizerRectangle(x, y, w, h, color)
        }
    }
}

/** Filled ellipse shape */
class RasterizerEllipse(
    private val centerX: Int,
    private val centerY: Int,
    private val radiusX: Int,
    private val radiusY: Int,
    private val color: Int
) : RasterizerShape() {
    override fun draw(width: Int, height: Int) {
        val cx = centerX * width shr 12
        val cy = centerY * height shr 12
        val rx = radiusX * width shr 12
        val ry = radiusY * height shr 12

        if (rx <= 0 || ry <= 0) return

        val rxSq = rx * rx
        val rySq = ry * ry

        for (dy in -ry..ry) {
            val py = cy + dy
            if (py < TextureOpRasterizer.clipTop || py > TextureOpRasterizer.clipBottom) continue

            val row = TextureOpRasterizer.pixelBuffer!![py]
            val dySq = dy * dy
            val xRange = kotlin.math.sqrt((1.0 - dySq.toDouble() / rySq) * rxSq).toInt()

            for (dx in -xRange..xRange) {
                val px = cx + dx
                if (px >= TextureOpRasterizer.clipLeft && px <= TextureOpRasterizer.clipRight) {
                    row[px] = color
                }
            }
        }
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerEllipse {
            val cx = buffer.readShort() and 0xFFFF
            val cy = buffer.readShort() and 0xFFFF
            val rx = buffer.readShort() and 0xFFFF
            val ry = buffer.readShort() and 0xFFFF
            val color = buffer.readInt()
            return RasterizerEllipse(cx, cy, rx, ry, color)
        }
    }
}

/** Line shape using Bresenham's algorithm */
class RasterizerLine(
    private val x1: Int,
    private val y1: Int,
    private val x2: Int,
    private val y2: Int,
    private val color: Int
) : RasterizerShape() {
    override fun draw(width: Int, height: Int) {
        var sx1 = x1 * width shr 12
        var sy1 = y1 * height shr 12
        var sx2 = x2 * width shr 12
        var sy2 = y2 * height shr 12

        val dx = kotlin.math.abs(sx2 - sx1)
        val dy = kotlin.math.abs(sy2 - sy1)
        val stepX = if (sx1 < sx2) 1 else -1
        val stepY = if (sy1 < sy2) 1 else -1
        var err = dx - dy

        while (true) {
            if (sx1 in TextureOpRasterizer.clipLeft..TextureOpRasterizer.clipRight &&
                sy1 in TextureOpRasterizer.clipTop..TextureOpRasterizer.clipBottom) {
                TextureOpRasterizer.pixelBuffer!![sy1][sx1] = color
            }

            if (sx1 == sx2 && sy1 == sy2) break

            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                sx1 += stepX
            }
            if (e2 < dx) {
                err += dx
                sy1 += stepY
            }
        }
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerLine {
            val x1 = buffer.readShort() and 0xFFFF
            val y1 = buffer.readShort() and 0xFFFF
            val x2 = buffer.readShort() and 0xFFFF
            val y2 = buffer.readShort() and 0xFFFF
            val color = buffer.readInt()
            return RasterizerLine(x1, y1, x2, y2, color)
        }
    }
}

/** Gradient-filled rectangle */
class RasterizerGradientRect(
    private val x: Int,
    private val y: Int,
    private val rectWidth: Int,
    private val rectHeight: Int,
    private val colorStart: Int,
    private val colorEnd: Int,
    private val horizontal: Boolean
) : RasterizerShape() {
    override fun draw(width: Int, height: Int) {
        val scaledX = x * width shr 12
        val scaledY = y * height shr 12
        val scaledWidth = rectWidth * width shr 12
        val scaledHeight = rectHeight * height shr 12

        val startX = scaledX.coerceIn(TextureOpRasterizer.clipLeft, TextureOpRasterizer.clipRight)
        val endX = (scaledX + scaledWidth).coerceIn(TextureOpRasterizer.clipLeft, TextureOpRasterizer.clipRight + 1)
        val startY = scaledY.coerceIn(TextureOpRasterizer.clipTop, TextureOpRasterizer.clipBottom)
        val endY = (scaledY + scaledHeight).coerceIn(TextureOpRasterizer.clipTop, TextureOpRasterizer.clipBottom + 1)

        val totalSteps = if (horizontal) endX - startX else endY - startY
        if (totalSteps <= 0) return

        for (py in startY until endY) {
            val row = TextureOpRasterizer.pixelBuffer!![py]
            for (px in startX until endX) {
                val progress = if (horizontal) px - startX else py - startY
                val t = progress * 4096 / totalSteps
                val invT = 4096 - t

                val r = ((colorStart shr 16 and 0xff) * invT + (colorEnd shr 16 and 0xff) * t) shr 12
                val g = ((colorStart shr 8 and 0xff) * invT + (colorEnd shr 8 and 0xff) * t) shr 12
                val b = ((colorStart and 0xff) * invT + (colorEnd and 0xff) * t) shr 12

                row[px] = (r shl 16) or (g shl 8) or b
            }
        }
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerGradientRect {
            val x = buffer.readShort() and 0xFFFF
            val y = buffer.readShort() and 0xFFFF
            val w = buffer.readShort() and 0xFFFF
            val h = buffer.readShort() and 0xFFFF
            val colorStart = buffer.readInt()
            val colorEnd = buffer.readInt()
            val horizontal = buffer.readUnsignedByte() == 1
            return RasterizerGradientRect(x, y, w, h, colorStart, colorEnd, horizontal)
        }
    }
}
