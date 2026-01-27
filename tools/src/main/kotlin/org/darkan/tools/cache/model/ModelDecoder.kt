package org.darkan.tools.cache.model

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Decoder for RS model binary format (revision 727).
 * Based on reference implementation from darkan-bot.
 */
object ModelDecoder {
    fun decode(cache: Cache, modelId: Int): ModelDefinition? {
        val data = cache.data(Index.MODELS, modelId) ?: return null
        return try {
            decode(modelId, data)
        } catch (e: Exception) {
            System.err.println("Failed to decode model $modelId: ${e.message}")
            null
        }
    }

    fun decode(modelId: Int, data: ByteArray): ModelDefinition? {
        if (data.size < 20) {
            return null
        }

        // Check format - both last two bytes must be -1 (0xFF) for new format
        val isNewFormat = data[data.size - 1] == (-1).toByte() && data[data.size - 2] == (-1).toByte()

        return if (isNewFormat) {
            decodeNewFormat(modelId, data)
        } else {
            decodeOldFormat(modelId, data)
        }
    }

    private fun decodeNewFormat(modelId: Int, data: ByteArray): ModelDefinition? {
        val buffer = BufferReader(data)

        // Read header from end of file (23 bytes before end)
        buffer.position(data.size - 23)
        val vertexCount = buffer.readUnsignedShort()
        val faceCount = buffer.readUnsignedShort()
        val texturedFaceCount = buffer.readUnsignedByte()
        val footerFlags = buffer.readUnsignedByte()

        val hasFaceTypes = (footerFlags and 0x1) == 1
        val modelPriority = buffer.readUnsignedByte()
        val hasFaceAlpha = buffer.readUnsignedByte()
        val hasFaceSkins = buffer.readUnsignedByte()
        val hasFaceTextures = buffer.readUnsignedByte()
        val hasVertexSkins = buffer.readUnsignedByte()

        val vertexXLength = buffer.readUnsignedShort()
        val vertexYLength = buffer.readUnsignedShort()
        val vertexZLength = buffer.readUnsignedShort()
        val faceIndicesLength = buffer.readUnsignedShort()
        val textureIndicesLength = buffer.readUnsignedShort()

        if (vertexCount == 0 || faceCount == 0) {
            return null
        }

        // Calculate offsets (order matters!)
        // Data starts after textured face count (reference uses texturedFaceCount directly as starting index)
        var offset = texturedFaceCount
        val vertexFlagsOffset = offset
        offset += vertexCount

        val faceTypeOffset = offset
        if (hasFaceTypes) {
            offset += faceCount
        }

        val faceIndexTypesOffset = offset
        offset += faceCount

        val facePrioritiesOffset = offset
        if (modelPriority == 255) {
            offset += faceCount
        }

        val faceSkinOffset = offset
        if (hasFaceSkins == 1) {
            offset += faceCount
        }

        val vertexSkinOffset = offset
        if (hasVertexSkins == 1) {
            offset += vertexCount
        }

        val faceAlphasOffset = offset
        if (hasFaceAlpha == 1) {
            offset += faceCount
        }

        val faceIndicesOffset = offset
        offset += faceIndicesLength

        val faceTexturesOffset = offset
        if (hasFaceTextures == 1) {
            offset += faceCount * 2
        }

        // TEXTURE INDICES - always present, size is textureIndicesLength from footer
        val textureIndicesOffset = offset
        offset += textureIndicesLength

        val faceColorsOffset = offset
        offset += faceCount * 2

        val vertexXOffset = offset
        offset += vertexXLength

        val vertexYOffset = offset
        offset += vertexYLength

        val vertexZOffset = offset
        offset += vertexZLength

        val textureTrianglesOffset = offset


        // Validate data fits
        val minRequired = vertexZOffset + vertexZLength
        if (minRequired > data.size) {
            return null
        }

        // Decode vertex flags
        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        for (i in 0 until vertexCount) {
            vertexFlags[i] = buffer.readByte().toByte()
        }

        // Decode vertices (delta encoded)
        val vertexX = IntArray(vertexCount)
        val vertexY = IntArray(vertexCount)
        val vertexZ = IntArray(vertexCount)

        buffer.position(vertexXOffset)
        var vX = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x1) != 0) {
                vX += readSmart(buffer)
            }
            vertexX[i] = vX
        }

        buffer.position(vertexYOffset)
        var vY = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x2) != 0) {
                vY += readSmart(buffer)
            }
            vertexY[i] = vY
        }

        buffer.position(vertexZOffset)
        var vZ = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x4) != 0) {
                vZ += readSmart(buffer)
            }
            vertexZ[i] = vZ
        }

        // Decode face index types
        buffer.position(faceIndexTypesOffset)
        val faceIndexTypes = ByteArray(faceCount)
        for (i in 0 until faceCount) {
            faceIndexTypes[i] = buffer.readByte().toByte()
        }

        // Decode face indices
        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)

        buffer.position(faceIndicesOffset)
        var a = 0
        var b = 0
        var c = 0
        var lastIndex = 0

        for (i in 0 until faceCount) {
            val type = faceIndexTypes[i].toInt() and 0xff
            when (type) {
                1 -> {
                    a = readSmart(buffer) + lastIndex
                    lastIndex = a
                    b = readSmart(buffer) + lastIndex
                    lastIndex = b
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                2 -> {
                    b = c
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                3 -> {
                    a = c
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                4 -> {
                    val temp = a
                    a = b
                    b = temp
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
            }
            faceA[i] = a
            faceB[i] = b
            faceC[i] = c
        }

        // Decode colors
        val faceColors = ShortArray(faceCount)
        if (faceColorsOffset + faceCount * 2 <= data.size) {
            buffer.position(faceColorsOffset)
            for (i in 0 until faceCount) {
                faceColors[i] = buffer.readUnsignedShort().toShort()
            }
        }

        // Decode priorities
        val facePriorities: ByteArray? = if (modelPriority == 255 && facePrioritiesOffset + faceCount <= data.size) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode alphas
        val faceAlphas: ByteArray? = if (hasFaceAlpha == 1 && faceAlphasOffset + faceCount <= data.size) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode face types (render types)
        val faceRenderTypes: ByteArray? = if (hasFaceTypes && faceTypeOffset + faceCount <= data.size) {
            buffer.position(faceTypeOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode face textures
        val faceTextures: ShortArray? = if (hasFaceTextures == 1 && faceTexturesOffset + faceCount * 2 <= data.size) {
            buffer.position(faceTexturesOffset)
            ShortArray(faceCount) { buffer.readUnsignedShort().toShort() }
        } else {
            null
        }

        return ModelDefinition(
            id = modelId,
            vertexCount = vertexCount,
            faceCount = faceCount,
            vertexX = vertexX,
            vertexY = vertexY,
            vertexZ = vertexZ,
            faceA = faceA,
            faceB = faceB,
            faceC = faceC,
            faceColors = faceColors,
            faceAlphas = faceAlphas,
            faceRenderTypes = faceRenderTypes,
            facePriorities = facePriorities,
            faceTextures = faceTextures,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null
        )
    }

    private fun decodeOldFormat(modelId: Int, data: ByteArray): ModelDefinition? {
        val buffer = BufferReader(data)

        // Read header from end of file (18 bytes before end)
        buffer.position(data.size - 18)
        val vertexCount = buffer.readUnsignedShort()
        val faceCount = buffer.readUnsignedShort()
        val texturedFaceCount = buffer.readUnsignedByte()

        val hasRenderTypes = buffer.readUnsignedByte()
        val hasPriorities = buffer.readUnsignedByte()
        val hasAlphas = buffer.readUnsignedByte()
        val hasFaceSkins = buffer.readUnsignedByte()
        val hasVertexSkins = buffer.readUnsignedByte()

        val vertexXLength = buffer.readUnsignedShort()
        val vertexYLength = buffer.readUnsignedShort()
        val vertexZLength = buffer.readUnsignedShort()
        val faceIndicesLength = buffer.readUnsignedShort()

        if (vertexCount == 0 || faceCount == 0) {
            return null
        }

        // Calculate offsets (starting after textured face count)
        var offset = texturedFaceCount
        val vertexFlagsOffset = offset
        offset += vertexCount

        val faceIndexTypesOffset = offset
        offset += faceCount

        val facePrioritiesOffset = offset
        if (hasPriorities == 255) {
            offset += faceCount
        }

        val faceSkinOffset = offset
        if (hasFaceSkins == 1) {
            offset += faceCount
        }

        val faceRenderTypesOffset = offset
        if (hasRenderTypes == 1) {
            offset += faceCount
        }

        val vertexSkinOffset = offset
        if (hasVertexSkins == 1) {
            offset += vertexCount
        }

        val faceAlphasOffset = offset
        if (hasAlphas == 1) {
            offset += faceCount
        }

        val faceIndicesOffset = offset
        offset += faceIndicesLength

        val faceColorsOffset = offset
        offset += faceCount * 2

        val vertexXOffset = offset
        offset += vertexXLength

        val vertexYOffset = offset
        offset += vertexYLength

        val vertexZOffset = offset
        offset += vertexZLength

        val textureTrianglesOffset = offset

        // Validate
        val minRequired = vertexZOffset + vertexZLength
        if (minRequired > data.size) {
            return null
        }

        // Decode vertex flags
        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        for (i in 0 until vertexCount) {
            vertexFlags[i] = buffer.readByte().toByte()
        }

        // Decode vertices (delta encoded)
        val vertexX = IntArray(vertexCount)
        val vertexY = IntArray(vertexCount)
        val vertexZ = IntArray(vertexCount)

        buffer.position(vertexXOffset)
        var vX = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x1) != 0) {
                vX += readSmart(buffer)
            }
            vertexX[i] = vX
        }

        buffer.position(vertexYOffset)
        var vY = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x2) != 0) {
                vY += readSmart(buffer)
            }
            vertexY[i] = vY
        }

        buffer.position(vertexZOffset)
        var vZ = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x4) != 0) {
                vZ += readSmart(buffer)
            }
            vertexZ[i] = vZ
        }

        // Decode face index types
        buffer.position(faceIndexTypesOffset)
        val faceIndexTypes = ByteArray(faceCount)
        for (i in 0 until faceCount) {
            faceIndexTypes[i] = buffer.readByte().toByte()
        }

        // Decode face indices
        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)

        buffer.position(faceIndicesOffset)
        var a = 0
        var b = 0
        var c = 0
        var lastIndex = 0

        for (i in 0 until faceCount) {
            val type = faceIndexTypes[i].toInt() and 0xff
            when (type) {
                1 -> {
                    a = readSmart(buffer) + lastIndex
                    lastIndex = a
                    b = readSmart(buffer) + lastIndex
                    lastIndex = b
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                2 -> {
                    b = c
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                3 -> {
                    a = c
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                4 -> {
                    val temp = a
                    a = b
                    b = temp
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
            }
            faceA[i] = a
            faceB[i] = b
            faceC[i] = c
        }

        // Decode colors
        val faceColors = ShortArray(faceCount)
        if (faceColorsOffset + faceCount * 2 <= data.size) {
            buffer.position(faceColorsOffset)
            for (i in 0 until faceCount) {
                faceColors[i] = buffer.readUnsignedShort().toShort()
            }
        }

        // Decode priorities
        val facePriorities: ByteArray? = if (hasPriorities == 255 && facePrioritiesOffset + faceCount <= data.size) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode alphas
        val faceAlphas: ByteArray? = if (hasAlphas == 1 && faceAlphasOffset + faceCount <= data.size) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode render types
        val faceRenderTypes: ByteArray? = if (hasRenderTypes == 1 && faceRenderTypesOffset + faceCount <= data.size) {
            buffer.position(faceRenderTypesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        return ModelDefinition(
            id = modelId,
            vertexCount = vertexCount,
            faceCount = faceCount,
            vertexX = vertexX,
            vertexY = vertexY,
            vertexZ = vertexZ,
            faceA = faceA,
            faceB = faceB,
            faceC = faceC,
            faceColors = faceColors,
            faceAlphas = faceAlphas,
            faceRenderTypes = faceRenderTypes,
            facePriorities = facePriorities,
            faceTextures = null,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null
        )
    }

    /**
     * Read a smart-encoded value.
     * If first byte < 128: single byte, value range -64 to +63
     * If first byte >= 128: two bytes, value range -49152 to +16383
     */
    private fun readSmart(buffer: BufferReader): Int {
        val peek = buffer.readUnsignedByte()
        return if (peek < 128) {
            peek - 64
        } else {
            ((peek shl 8) or buffer.readUnsignedByte()) - 49152
        }
    }

    fun getModelCount(cache: Cache): Int {
        return cache.lastArchiveId(Index.MODELS) + 1
    }
}
