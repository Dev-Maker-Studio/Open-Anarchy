package org.darkan.tools.cache.map

import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import kotlin.math.atan2
import kotlin.math.min

object TerrainAligner {

    var enabled = true

    var negateXTilt = true

    var negateZTilt = true

    private const val RS_ROTATION_UNITS = 16384

    private const val RS_HALF_ROTATION = 8192

    private const val RS_TO_RADIANS = (Math.PI * 2.0 / RS_ROTATION_UNITS).toFloat()

    private const val RADIANS_TO_RS = (RS_ROTATION_UNITS / (Math.PI * 2.0)).toFloat()

    data class AlignmentResult(
        val tiltX: Float,
        val tiltZ: Float,
        val heightAdjustment: Float
    ) {
        companion object {
            val NONE = AlignmentResult(0f, 0f, 0f)
        }

        fun applyTo(matrix: Matrix4f): Matrix4f {
            if (heightAdjustment != 0f) {
                matrix.translate(0f, heightAdjustment, 0f)
            }
            if (tiltX != 0f) {
                matrix.rotateX(tiltX)
            }
            if (tiltZ != 0f) {
                matrix.rotateZ(tiltZ)
            }
            return matrix
        }

        fun needsAlignment(): Boolean = tiltX != 0f || tiltZ != 0f || heightAdjustment != 0f
    }

    fun calculateAlignment(
        centerX: Float,
        centerY: Float,
        width: Float,
        length: Float,
        maxTiltX: Int,
        maxTiltZ: Int,
        currentHeight: Float,
        heightSampler: (Float, Float) -> Float
    ): AlignmentResult {
        if (width == 0f && length == 0f) {
            return AlignmentResult.NONE
        }

        val halfWidth = width / 2f
        val halfLength = length / 2f

        val heightSW = heightSampler(centerX - halfWidth, centerY - halfLength)
        val heightSE = heightSampler(centerX + halfWidth, centerY - halfLength)
        val heightNW = heightSampler(centerX - halfWidth, centerY + halfLength)
        val heightNE = heightSampler(centerX + halfWidth, centerY + halfLength)

        val lowestSouth = min(heightSW, heightSE)
        val lowestNorth = min(heightNW, heightNE)
        val lowestEast = min(heightSE, heightNE)
        val lowestWest = min(heightSW, heightNW)

        var tiltXRs = 0
        if (length != 0f) {
            tiltXRs = (atan2((lowestSouth - lowestNorth).toDouble(), length.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            if (maxTiltX != 0 && tiltXRs != 0) {
                if (tiltXRs > RS_HALF_ROTATION) {
                    val minAngle = RS_ROTATION_UNITS - maxTiltX
                    if (tiltXRs < minAngle) {
                        tiltXRs = minAngle
                    }
                } else if (tiltXRs > maxTiltX) {
                    tiltXRs = maxTiltX
                }
            }
        }

        var tiltZRs = 0
        if (width != 0f) {
            tiltZRs = (atan2((lowestWest - lowestEast).toDouble(), width.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            if (maxTiltZ != 0 && tiltZRs != 0) {
                if (tiltZRs > RS_HALF_ROTATION) {
                    val minAngle = RS_ROTATION_UNITS - maxTiltZ
                    if (tiltZRs < minAngle) {
                        tiltZRs = minAngle
                    }
                } else if (tiltZRs > maxTiltZ) {
                    tiltZRs = maxTiltZ
                }
            }
        }

        var lowestAverage = heightSW + heightNE
        if (heightSE + heightNW < lowestAverage) {
            lowestAverage = heightSE + heightNW
        }
        val targetHeight = lowestAverage / 2f
        val heightAdjustment = targetHeight - currentHeight

        val tiltXRadians = tiltXRs * RS_TO_RADIANS
        val tiltZRadians = tiltZRs * RS_TO_RADIANS

        return AlignmentResult(tiltXRadians, tiltZRadians, heightAdjustment)
    }

    fun calculateAlignmentForObject(
        localTileX: Int,
        localTileY: Int,
        sizeX: Int,
        sizeY: Int,
        groundContourModifier: Int,
        tileSize: Float,
        heightSampler: (Float, Float) -> Float
    ): AlignmentResult {
        val width: Float
        val length: Float
        val maxTiltX: Int
        val maxTiltZ: Int

        if (groundContourModifier > 0) {
            width = ((groundContourModifier and 0xFF) * 4).toFloat()
            length = (((groundContourModifier shr 8) and 0xFF) * 4).toFloat()
            maxTiltX = ((groundContourModifier shr 16) and 0xFF) shl 6
            maxTiltZ = ((groundContourModifier shr 24) and 0xFF) shl 6
        } else {
            width = (sizeX * 512).toFloat()
            length = (sizeY * 512).toFloat()
            maxTiltX = 0
            maxTiltZ = 0
        }

        val scaledWidth = width * CoordinateUtil.DEFAULT_SCALE
        val scaledLength = length * CoordinateUtil.DEFAULT_SCALE

        val centerX = (localTileX + sizeX * 0.5f)
        val centerY = (localTileY + sizeY * 0.5f)

        val tileHeightSampler: (Float, Float) -> Float = { x, y ->
            val tileX = centerX + (x / tileSize)
            val tileY = centerY + (y / tileSize)
            heightSampler(tileX, tileY)
        }

        val currentHeight = heightSampler(centerX, centerY)

        return calculateAlignment(
            0f, 0f,
            scaledWidth, scaledLength,
            maxTiltX, maxTiltZ,
            currentHeight,
            tileHeightSampler
        )
    }

    private var debugEnabled = false
    private var alignedTypeCount = 0
    private var nonZeroTiltCount = 0
    private var totalProcessed = 0
    private var type1Count = 0
    private var type2Count = 0
    private var type3Count = 0
    private var exemptedCount = 0

    fun enableDebug(enable: Boolean) {
        debugEnabled = enable
        if (enable) {
            alignedTypeCount = 0
            nonZeroTiltCount = 0
            totalProcessed = 0
            type1Count = 0
            type2Count = 0
            type3Count = 0
            exemptedCount = 0
        }
    }

    fun getDebugStats(): String {
        return "Processed: $totalProcessed, Aligned: $alignedTypeCount (type1: $type1Count, type2: $type2Count, type3: $type3Count), Exempted: $exemptedCount, NonZeroTilt: $nonZeroTiltCount"
    }

    private const val CONTOUR_MODIFIER_EXEMPT_THRESHOLD = 16384

    fun calculateObjectTilt(
        localTileX: Int,
        localTileY: Int,
        sizeX: Int,
        sizeY: Int,
        groundContourType: Int,
        groundContourModifier: Int,
        averageHeight: Float,
        heightSampler: (Int, Int) -> Float
    ): AlignmentResult {
        totalProcessed++


        if (groundContourType != 3) {
            return AlignmentResult.NONE
        }

        if (groundContourModifier >= CONTOUR_MODIFIER_EXEMPT_THRESHOLD) {
            exemptedCount++
            return AlignmentResult.NONE
        }

        alignedTypeCount++
        type3Count++

        val width: Float
        val length: Float
        val maxTiltX: Int
        val maxTiltZ: Int

        if (groundContourModifier > 0) {
            width = ((groundContourModifier and 0xFF) * 4).toFloat()
            length = (((groundContourModifier shr 8) and 0xFF) * 4).toFloat()
            maxTiltX = ((groundContourModifier shr 16) and 0xFF) shl 6
            maxTiltZ = ((groundContourModifier shr 24) and 0xFF) shl 6
        } else {
            width = (sizeX * 512).toFloat()
            length = (sizeY * 512).toFloat()
            maxTiltX = 0
            maxTiltZ = 0
        }

        val halfWidthTiles = width / 1024f
        val halfLengthTiles = length / 1024f

        val centerX = localTileX + sizeX * 0.5f
        val centerY = localTileY + sizeY * 0.5f

        fun sampleHeight(offsetX: Float, offsetY: Float): Float {
            val tx = (centerX + offsetX).coerceIn(0f, 63f).toInt()
            val ty = (centerY + offsetY).coerceIn(0f, 63f).toInt()
            return heightSampler(tx, ty)
        }

        val heightSW = sampleHeight(-halfWidthTiles, -halfLengthTiles)
        val heightSE = sampleHeight(halfWidthTiles, -halfLengthTiles)
        val heightNW = sampleHeight(-halfWidthTiles, halfLengthTiles)
        val heightNE = sampleHeight(halfWidthTiles, halfLengthTiles)

        val lowestSouth = min(heightSW, heightSE)
        val lowestNorth = min(heightNW, heightNE)
        val lowestEast = min(heightSE, heightNE)
        val lowestWest = min(heightSW, heightNW)

        val scaledLength = length * CoordinateUtil.DEFAULT_SCALE
        val scaledWidth = width * CoordinateUtil.DEFAULT_SCALE

        var tiltXRs = 0
        if (scaledLength != 0f) {
            val heightDiff = lowestSouth - lowestNorth
            tiltXRs = (atan2(heightDiff.toDouble(), scaledLength.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            if (maxTiltX != 0 && tiltXRs != 0) {
                if (tiltXRs > RS_HALF_ROTATION) {
                    val minAngle = RS_ROTATION_UNITS - maxTiltX
                    if (tiltXRs < minAngle) tiltXRs = minAngle
                } else if (tiltXRs > maxTiltX) {
                    tiltXRs = maxTiltX
                }
            }
        }

        var tiltZRs = 0
        if (scaledWidth != 0f) {
            val heightDiff = lowestWest - lowestEast
            tiltZRs = (atan2(heightDiff.toDouble(), scaledWidth.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            if (maxTiltZ != 0 && tiltZRs != 0) {
                if (tiltZRs > RS_HALF_ROTATION) {
                    val minAngle = RS_ROTATION_UNITS - maxTiltZ
                    if (tiltZRs < minAngle) tiltZRs = minAngle
                } else if (tiltZRs > maxTiltZ) {
                    tiltZRs = maxTiltZ
                }
            }
        }

        val tiltXRadians = (if (negateXTilt) -tiltXRs else tiltXRs) * RS_TO_RADIANS
        val tiltZRadians = (if (negateZTilt) -tiltZRs else tiltZRs) * RS_TO_RADIANS

        var lowestAvg = heightSW + heightNE
        if (heightSE + heightNW < lowestAvg) {
            lowestAvg = heightSE + heightNW
        }
        val targetHeight = lowestAvg / 2f
        val heightAdjustment = targetHeight - averageHeight

        if (!enabled) {
            return AlignmentResult.NONE
        }

        val result = AlignmentResult(tiltXRadians, tiltZRadians, heightAdjustment)
        if (result.needsAlignment()) {
            nonZeroTiltCount++
        }
        return result
    }
}
