package org.darkan.tools.cache.model

import world.gregs.voidps.buffer.read.BufferReader

/**
 * Configuration type for billboards (2D sprites attached to 3D model faces).
 *
 * Billboards define textured quads that are rendered at model face centroids.
 * The key property is [hasUid]: when true, the anchor face is hidden and replaced
 * by the billboard quad. This is used for signpost labels, particle effect anchors, etc.
 *
 * Decoded from cache Index 29 (BILLBOARDS), archive 0, file = billboardId.
 * Matches the client's BillboardType.kt decode format.
 */
data class BillboardType(
    /** Material/texture ID for the billboard quad (-1 = no material) */
    val materialId: Int = -1,
    /** Horizontal size (width) in RS model units */
    val size2d: Int = 64,
    /** Vertical size (height) in RS model units */
    val size3d: Int = 64,
    /** Shape: 0=rectangle, 1=circle, 2=brightened rectangle */
    val shape: Int = 2,
    /** Blend type: 0=color mix, 1=no color mix */
    val blendType: Int = 1,
    /** If true, billboard does not rotate to face camera (bloom-only effect) */
    val stationary: Boolean = false,
    /** If true, the anchor face is hidden and replaced by the billboard quad */
    val hasUid: Boolean = false
) {
    companion object {
        /**
         * Decode a BillboardType from binary data.
         *
         * @param data Raw bytes from the cache
         * @return Decoded BillboardType
         */
        fun decode(data: ByteArray): BillboardType {
            val buffer = BufferReader(data)
            var materialId = -1
            var size2d = 64
            var size3d = 64
            var shape = 2
            var blendType = 1
            var stationary = false
            var hasUid = false

            while (true) {
                val opcode = buffer.readUnsignedByte()
                if (opcode == 0) break
                when (opcode) {
                    1 -> {
                        materialId = buffer.readUnsignedShort()
                        if (materialId == 65535) materialId = -1
                    }
                    2 -> {
                        size2d = buffer.readUnsignedShort() + 1
                        size3d = buffer.readUnsignedShort() + 1
                    }
                    3 -> buffer.readByte()  // skip 1 byte (unknown purpose)
                    4 -> shape = buffer.readUnsignedByte()
                    5 -> blendType = buffer.readUnsignedByte()
                    6 -> stationary = true
                    7 -> hasUid = true
                }
            }

            return BillboardType(materialId, size2d, size3d, shape, blendType, stationary, hasUid)
        }
    }
}
