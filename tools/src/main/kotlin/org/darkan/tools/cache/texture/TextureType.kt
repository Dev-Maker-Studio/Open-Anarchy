package org.darkan.tools.cache.texture

import org.darkan.tools.cache.texture.ops.*
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.min
import kotlin.math.pow

/**
 * Represents a texture definition with its operation DAG.
 *
 * Textures in RS are procedurally generated using a directed acyclic graph
 * of operations. Each operation can reference other operations as inputs,
 * creating complex textures from simple building blocks.
 *
 * @param data The raw texture data from the TEXTURES cache index
 */
class TextureType(data: ByteArray) {
    /** All operations in this texture */
    val textureOperations: Array<TextureOperation>

    /** Sprite IDs required by TextureOpSprite operations */
    val textureFileIds: IntArray

    /** Material IDs required by TextureOpTexture operations */
    val materialFileIds: IntArray

    /** The operation that produces the opaque RGB output */
    val opaqueOperation: TextureOperation

    /** The operation that produces the opacity/alpha output */
    val opacityOperation: TextureOperation

    /** The operation that produces HDR intensity output */
    val hdrOperation: TextureOperation

    init {
        val buffer = BufferReader(data)
        val totalOperations = buffer.readUnsignedByte()

        var textureFileCount = 0
        var materialFileCount = 0
        val operationIndices = Array(totalOperations) { IntArray(0) }

        @Suppress("UNCHECKED_CAST")
        textureOperations = arrayOfNulls<TextureOperation>(totalOperations) as Array<TextureOperation>

        // Decode all operations
        for (index in 0 until totalOperations) {
            val textureOperation = decodeOperation(buffer)

            if (textureOperation.getTextureId() >= 0) {
                textureFileCount++
            }
            if (textureOperation.getMaterialId() >= 0) {
                materialFileCount++
            }

            // Read child operation indices
            val operationCount = textureOperation.operations.size
            operationIndices[index] = IntArray(operationCount)
            for (sub in 0 until operationCount) {
                operationIndices[index][sub] = buffer.readUnsignedByte()
            }

            textureOperations[index] = textureOperation
        }

        // Collect file IDs and wire up operation references
        textureFileIds = IntArray(textureFileCount)
        textureFileCount = 0
        materialFileIds = IntArray(materialFileCount)
        materialFileCount = 0

        for (operationIndex in 0 until totalOperations) {
            val textureOperation = textureOperations[operationIndex]
            val operationCount = textureOperation.operations.size

            // Wire up child operation references
            for (sub in 0 until operationCount) {
                textureOperation.operations[sub] = textureOperations[operationIndices[operationIndex][sub]]
            }

            // Collect file IDs
            val textureFileId = textureOperation.getTextureId()
            val materialFileId = textureOperation.getMaterialId()
            if (textureFileId > 0) {
                textureFileIds[textureFileCount++] = textureFileId
            }
            if (materialFileId > 0) {
                materialFileIds[materialFileCount++] = materialFileId
            }
        }

        // Read output operation indices
        opaqueOperation = textureOperations[buffer.readUnsignedByte()]
        opacityOperation = textureOperations[buffer.readUnsignedByte()]
        hdrOperation = textureOperations[buffer.readUnsignedByte()]
    }

    /**
     * Decode a single operation from the buffer.
     */
    private fun decodeOperation(buffer: BufferReader): TextureOperation {
        buffer.readUnsignedByte() // padding
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

    /**
     * Generate ARGB pixel data for this texture.
     *
     * @param width The output width
     * @param height The output height
     * @param spriteLoader Function to load sprite pixels by ID
     * @param textureLoader Optional function to load nested texture pixels by material ID
     * @return ARGB pixel array (width * height)
     */
    fun getPixelsArgb(
        width: Int,
        height: Int,
        spriteLoader: (Int) -> SpriteData?,
        textureLoader: ((Int, Int, Int) -> IntArray?)? = null
    ): IntArray {
        // Load required sprites
        for (operation in textureOperations) {
            if (operation is TextureOpSprite && operation.spriteId >= 0) {
                val sprite = spriteLoader(operation.spriteId)
                if (sprite != null) {
                    operation.loadSprite(sprite.pixels, sprite.width, sprite.height)
                }
            }
        }

        // Load required nested textures
        if (textureLoader != null) {
            for (operation in textureOperations) {
                if (operation is TextureOpTexture && operation.getMaterialId() >= 0) {
                    val pixels = textureLoader(operation.getMaterialId(), width, height)
                    if (pixels != null) {
                        operation.texturePixels = pixels
                        operation.textureWidth = width
                        operation.textureHeight = height
                    }
                }
            }
        }

        // Create caches for all operations
        for (operation in textureOperations) {
            operation.createImageCache(width, height)
        }

        // Set up brightness and dimensions
        setBrightness(0.7)
        TextureRaster.setDimensions(width, height)

        val pixels = IntArray(width * height)
        var pixelIndex = 0

        // Generate row by row
        for (row in 0 until height) {
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

            // Get opacity from the dedicated opacity operation (for proper texture transparency)
            // Only use the opacity operation if it's different from the opaque operation.
            // When they're the same, the texture has no dedicated opacity channel and should
            // be treated as fully opaque for non-black pixels.
            val hasOpacityChannel = opacityOperation !== opaqueOperation
            val opacityBuffer: IntArray? = if (hasOpacityChannel) {
                if (opacityOperation.monochrome) {
                    opacityOperation.getMonochromeOutput(row)
                } else {
                    opacityOperation.getColorOutput(row)[0]
                }
            } else {
                null
            }

            // Process pixels from right to left (matching RS client behavior)
            // This horizontally flips the texture as the client expects
            for (x in width - 1 downTo 0) {
                var red = (redBuffer[x] shr 4).coerceIn(0, 255)
                var green = (greenBuffer[x] shr 4).coerceIn(0, 255)
                var blue = (blueBuffer[x] shr 4).coerceIn(0, 255)

                // Apply brightness curve
                red = BRIGHTNESS_TABLE[red]
                green = BRIGHTNESS_TABLE[green]
                blue = BRIGHTNESS_TABLE[blue]

                // Get alpha: black pixels are always transparent.
                // If texture has a dedicated opacity channel, use it.
                // Otherwise, treat non-black pixels as fully opaque.
                val alpha: Int = if (red == 0 && green == 0 && blue == 0) {
                    0
                } else if (opacityBuffer != null) {
                    // Scale opacity value the same way as color values (shift right 4 bits)
                    (opacityBuffer[x] shr 4).coerceIn(0, 255)
                } else {
                    255
                }

                pixels[pixelIndex++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        // Cleanup caches
        for (operation in textureOperations) {
            operation.clearImageCache()
        }

        return pixels
    }

    companion object {
        /** Brightness lookup table for gamma correction */
        val BRIGHTNESS_TABLE = IntArray(256)

        private var currentBrightness = -1.0

        /**
         * Set the brightness/gamma correction value.
         *
         * @param brightness The brightness value (typically 0.7)
         */
        fun setBrightness(brightness: Double) {
            if (brightness != currentBrightness) {
                for (grade in 0..255) {
                    val value = ((grade / 255.0).pow(brightness) * 255.0).toInt()
                    BRIGHTNESS_TABLE[grade] = min(255, value)
                }
                currentBrightness = brightness
            }
        }

        /**
         * Create a texture operation by type ID.
         *
         * @param typeId The operation type (0-39)
         * @return The created operation
         */
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
                18 -> TextureOpTiledSprite()
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
                39 -> TextureOpSprite()
                else -> throw IllegalArgumentException("Unknown texture operation type: $typeId")
            }
        }
    }
}

/**
 * Container for sprite data.
 */
data class SpriteData(
    val pixels: IntArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpriteData) return false
        return pixels.contentEquals(other.pixels) && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
