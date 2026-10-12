package org.darkan.tools.cache.texture

import org.darkan.tools.cache.texture.ops.*
import world.gregs.voidps.buffer.read.BufferReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class TextureType(data: ByteArray) {
    val textureOperations: Array<TextureOperation>

    val textureFileIds: IntArray

    val materialFileIds: IntArray

    val opaqueOperation: TextureOperation

    val opacityOperation: TextureOperation

    val hdrOperation: TextureOperation

    init {
        val buffer = BufferReader(data)
        val totalOperations = buffer.readUnsignedByte()

        var textureFileCount = 0
        var materialFileCount = 0
        val operationIndices = Array(totalOperations) { IntArray(0) }

        @Suppress("UNCHECKED_CAST")
        textureOperations = arrayOfNulls<TextureOperation>(totalOperations) as Array<TextureOperation>

        for (index in 0 until totalOperations) {
            val textureOperation = decodeOperation(buffer)

            if (textureOperation.getTextureId() >= 0) {
                textureFileCount++
            }
            if (textureOperation.getMaterialId() >= 0) {
                materialFileCount++
            }

            val operationCount = textureOperation.operations.size
            operationIndices[index] = IntArray(operationCount)
            for (sub in 0 until operationCount) {
                operationIndices[index][sub] = buffer.readUnsignedByte()
            }

            textureOperations[index] = textureOperation
        }

        textureFileIds = IntArray(textureFileCount)
        textureFileCount = 0
        materialFileIds = IntArray(materialFileCount)
        materialFileCount = 0

        for (operationIndex in 0 until totalOperations) {
            val textureOperation = textureOperations[operationIndex]
            val operationCount = textureOperation.operations.size

            for (sub in 0 until operationCount) {
                textureOperation.operations[sub] = textureOperations[operationIndices[operationIndex][sub]]
            }

            val textureFileId = textureOperation.getTextureId()
            val materialFileId = textureOperation.getMaterialId()
            if (textureFileId > 0) {
                textureFileIds[textureFileCount++] = textureFileId
            }
            if (materialFileId > 0) {
                materialFileIds[materialFileCount++] = materialFileId
            }
        }

        opaqueOperation = textureOperations[buffer.readUnsignedByte()]
        opacityOperation = textureOperations[buffer.readUnsignedByte()]
        hdrOperation = textureOperations[buffer.readUnsignedByte()]
    }

    private fun decodeOperation(buffer: BufferReader): TextureOperation {
        buffer.readUnsignedByte()
        val typeId = buffer.readUnsignedByte()
        val textureOperation = createOperation(typeId)
        textureOperation.imageCacheCapacity = buffer.readUnsignedByte()

        val opcodeCount = buffer.readUnsignedByte()
        for (i in 0 until opcodeCount) {
            val opcode = buffer.readUnsignedByte()
            textureOperation.decodeOpcode(opcode, buffer)
        }
        textureOperation.postDecode()
        return textureOperation
    }

    private fun prepareOperations(
        width: Int,
        height: Int,
        graphicLoader: (Int) -> GraphicData?,
        textureLoader: ((Int, Int, Int) -> IntArray?)? = null,
        deadlineNanos: Long = 0L
    ) {
        for (operation in textureOperations) {
            if (operation is TextureOpGraphic && operation.graphicId >= 0) {
                val graphic = graphicLoader(operation.graphicId)
                if (graphic != null) {
                    operation.loadGraphic(graphic.pixels, graphic.width, graphic.height)
                }
            }
        }

        if (textureLoader != null) {
            for (operation in textureOperations) {
                if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                    throw TextureGenerationException("Texture generation timeout during nested texture loading")
                }
                if (operation is TextureOpTexture && operation.getMaterialId() >= 0) {
                    val pixels = textureLoader(operation.getMaterialId(), width, height)
                    if (pixels != null) {
                        operation.texturePixels = pixels
                        val actualSize = sqrt(pixels.size.toDouble()).toInt()
                        operation.textureWidth = actualSize
                        operation.textureHeight = actualSize
                    }
                }
            }
        }

        val raster = TextureRaster.context(width, height)
        for (operation in textureOperations) {
            operation.bindRaster(raster, deadlineNanos)
            operation.createImageCache(width, height)
        }
    }

    private fun cleanupOperations() {
        for (operation in textureOperations) {
            operation.clearImageCache()
        }
    }

    fun getPixelsArgb(
        width: Int,
        height: Int,
        graphicLoader: (Int) -> GraphicData?,
        textureLoader: ((Int, Int, Int) -> IntArray?)? = null,
        brightness: Double = 0.7,
        deadlineNanos: Long = 0L
    ): IntArray {
        prepareOperations(width, height, graphicLoader, textureLoader, deadlineNanos)

        try {
            val pixels = IntArray(width * height)
            var pixelIndex = 0
            val brightnessTable = brightnessTable(brightness)

            for (row in 0 until height) {
                if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                    throw TextureGenerationException("Texture generation timeout at row $row")
                }

                val redBuffer: IntArray
                val greenBuffer: IntArray
                val blueBuffer: IntArray

                if (opaqueOperation.monochrome) {
                    val buffer = opaqueOperation.getMonochromeOutput(row)
                    redBuffer = buffer
                    greenBuffer = buffer
                    blueBuffer = buffer
                } else {
                    val palette = opaqueOperation.getColorOutput(row)
                    redBuffer = palette[0]
                    greenBuffer = palette[1]
                    blueBuffer = palette[2]
                }

                val opacityBuffer: IntArray = if (opacityOperation.monochrome) {
                    opacityOperation.getMonochromeOutput(row)
                } else {
                    opacityOperation.getColorOutput(row)[0]
                }

                for (x in width - 1 downTo 0) {
                    var red = (redBuffer[x] shr 4).coerceIn(0, 255)
                    var green = (greenBuffer[x] shr 4).coerceIn(0, 255)
                    var blue = (blueBuffer[x] shr 4).coerceIn(0, 255)

                    red = brightnessTable[red]
                    green = brightnessTable[green]
                    blue = brightnessTable[blue]

                    val alpha: Int = if (red == 0 && green == 0 && blue == 0) {
                        0
                    } else {
                        (opacityBuffer[x] shr 4).coerceIn(0, 255)
                    }

                    pixels[pixelIndex++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }

            return pixels
        } finally {
            cleanupOperations()
        }
    }

    fun getPixelsRgb(
        width: Int,
        height: Int,
        graphicLoader: (Int) -> GraphicData?,
        textureLoader: ((Int, Int, Int) -> IntArray?)? = null,
        brightness: Double = 0.7,
        deadlineNanos: Long = 0L
    ): IntArray {
        prepareOperations(width, height, graphicLoader, textureLoader, deadlineNanos)

        try {
            val pixels = IntArray(width * height)
            var pixelIndex = 0
            val brightnessTable = brightnessTable(brightness)

            for (row in 0 until height) {
                if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                    throw TextureGenerationException("Texture generation timeout at row $row")
                }

                val redBuffer: IntArray
                val greenBuffer: IntArray
                val blueBuffer: IntArray

                if (opaqueOperation.monochrome) {
                    val buffer = opaqueOperation.getMonochromeOutput(row)
                    redBuffer = buffer
                    greenBuffer = buffer
                    blueBuffer = buffer
                } else {
                    val palette = opaqueOperation.getColorOutput(row)
                    redBuffer = palette[0]
                    greenBuffer = palette[1]
                    blueBuffer = palette[2]
                }


                for (x in width - 1 downTo 0) {
                    var red = (redBuffer[x] shr 4).coerceIn(0, 255)
                    var green = (greenBuffer[x] shr 4).coerceIn(0, 255)
                    var blue = (blueBuffer[x] shr 4).coerceIn(0, 255)

                    red = brightnessTable[red]
                    green = brightnessTable[green]
                    blue = brightnessTable[blue]

                    val pixel = blue + (green shl 8) + (red shl 16)
                    if (pixel != 0) {
                        pixels[pixelIndex++] = pixel or 0xFF000000.toInt()
                    } else {
                        pixels[pixelIndex++] = 0
                    }
                }
            }

            return pixels
        } finally {
            cleanupOperations()
        }
    }

    companion object {
        private val brightnessTables = ConcurrentHashMap<Double, IntArray>()

        fun brightnessTable(brightness: Double): IntArray {
            brightnessTables[brightness]?.let { return it }
            val table = IntArray(256) { grade ->
                min(255, ((grade / 255.0).pow(brightness) * 255.0).toInt())
            }
            return brightnessTables.putIfAbsent(brightness, table) ?: table
        }

        fun createOperation(typeId: Int): TextureOperation {
            return when (typeId) {
                0 -> TextureOpMonochromeFill()
                1 -> TextureOpColorFill()
                2 -> TextureOpHorizontalGradient()
                3 -> TextureOpVerticalGradient()
                4 -> TextureOpBricks()
                5 -> TextureOpBoxBlur()
                6 -> TextureOpClamp()
                7 -> TextureOpCombine()
                8 -> TextureOpCurve()
                9 -> TextureOpFlip()
                10 -> TextureOpColorGradient()
                11 -> TextureOpColorize()
                12 -> TextureOpWaveform()
                13 -> TextureOpNoise()
                14 -> TextureOpWeave()
                15 -> TextureOpVoronoiNoise()
                16 -> TextureOpHerringbone()
                17 -> TextureOpHslAdjust()
                18 -> TextureOpTiledGraphic()
                19 -> TextureOpPolarDistortion()
                20 -> TextureOpTile()
                21 -> TextureOpInterpolate()
                22 -> TextureOpInvert()
                23 -> TextureOpKaleidoscope()
                24 -> TextureOpMonochrome()
                25 -> TextureOpBrightness()
                26 -> TextureOpBinary()
                27 -> TextureOpSquareWaveform()
                28 -> TextureOpIrregularBricks()
                29 -> TextureOpRasterizer()
                30 -> TextureOpRange()
                31 -> TextureOpMandelbrot()
                32 -> TextureOpEmboss()
                33 -> TextureOpColorEdgeDetector()
                34 -> TextureOpPerlinNoise()
                35 -> TextureOpMonochromeEdgeDetector()
                36 -> TextureOpTexture()
                37 -> TextureOp37()
                38 -> TextureOpLineNoise()
                39 -> TextureOpGraphic()
                else -> throw IllegalArgumentException("Unknown texture operation type: $typeId")
            }
        }
    }
}

data class GraphicData(
    val pixels: IntArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GraphicData) return false
        return pixels.contentEquals(other.pixels) && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
