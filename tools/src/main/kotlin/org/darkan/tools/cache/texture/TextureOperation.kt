package org.darkan.tools.cache.texture

import org.darkan.tools.cache.texture.cache.ColorImageCache
import org.darkan.tools.cache.texture.cache.MonochromeImageCache
import world.gregs.voidps.buffer.read.BufferReader

abstract class TextureOperation(
    childCount: Int,
    var monochrome: Boolean
) {
    lateinit var colorImageCache: ColorImageCache

    lateinit var monochromeImageCache: MonochromeImageCache

    var imageCacheCapacity: Int = 0

    val operations: Array<TextureOperation?> = arrayOfNulls(childCount)


    @JvmField
    protected var currentWidth: Int = 0

    @JvmField
    protected var currentHeight: Int = 0

    @JvmField
    protected var rowEnd: Int = 0

    @JvmField
    protected var columnEnd: Int = 0

    @JvmField
    protected var widthScale: Int = 0

    @JvmField
    protected var horizontalTable: IntArray = EMPTY_TABLE

    @JvmField
    protected var verticalTable: IntArray = EMPTY_TABLE

    @JvmField
    protected var deadlineNanos: Long = 0L

    fun bindRaster(raster: RasterContext, deadlineNanos: Long) {
        currentWidth = raster.currentWidth
        currentHeight = raster.currentHeight
        rowEnd = raster.rowEnd
        columnEnd = raster.columnEnd
        widthScale = raster.widthScale
        horizontalTable = raster.horizontalTable
        verticalTable = raster.verticalTable
        this.deadlineNanos = deadlineNanos
    }

    open fun createImageCache(width: Int, height: Int) {
        val capacity = if (imageCacheCapacity == 255) height else imageCacheCapacity
        if (monochrome) {
            monochromeImageCache = MonochromeImageCache(capacity, height, width, deadlineNanos)
        } else {
            colorImageCache = ColorImageCache(capacity, height, width, deadlineNanos)
        }
    }

    open fun getMonochromeOutput(y: Int): IntArray {
        throw IllegalStateException("Operation does not support monochrome output")
    }

    open fun getColorOutput(y: Int): Array<IntArray> {
        throw IllegalStateException("Operation does not support color output")
    }

    protected fun getChildMonochromeOutput(operationIndex: Int, y: Int): IntArray {
        val child = operations[operationIndex]!!
        return if (!child.monochrome) {
            child.getColorOutput(y)[0]
        } else {
            child.getMonochromeOutput(y)
        }
    }

    protected fun getChildColorOutput(operationIndex: Int, y: Int): Array<IntArray> {
        val child = operations[operationIndex]!!
        return if (child.monochrome) {
            val monochromeRow = child.getMonochromeOutput(y)
            arrayOf(monochromeRow, monochromeRow, monochromeRow)
        } else {
            child.getColorOutput(y)
        }
    }

    open fun decodeOpcode(opcode: Int, buffer: BufferReader) {
    }

    open fun postDecode() {
    }

    open fun getTextureId(): Int = -1

    open fun getMaterialId(): Int = -1

    open fun clearImageCache() {
        if (monochrome) {
            monochromeImageCache.reset()
        } else {
            colorImageCache.clear()
        }
    }

    companion object {
        private val EMPTY_TABLE = IntArray(0)
    }
}

class TextureGenerationException(message: String) : RuntimeException(message)
