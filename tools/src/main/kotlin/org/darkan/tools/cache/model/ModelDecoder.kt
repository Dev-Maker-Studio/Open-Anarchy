package org.darkan.tools.cache.model

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Decoder for RS model binary format (revision 727).
 */
object ModelDecoder {
    fun decode(cache: Cache, modelId: Int): ModelDefinition? {
        val data = cache.data(Index.MODELS, modelId) ?: return null
        return decode(modelId, data)
    }

    fun decode(modelId: Int, data: ByteArray): ModelDefinition {
        val buffer = BufferReader(data)

        // Read footer to determine format
        buffer.position(data.size - 2)
        val formatFlag = buffer.readShort()

        return if (formatFlag == -1) {
            decodeNewFormat(modelId, data)
        } else {
            decodeOldFormat(modelId, data)
        }
    }

    private fun decodeNewFormat(modelId: Int, data: ByteArray): ModelDefinition {
        val buffer = BufferReader(data)

        // Read header from end of file
        buffer.position(data.size - 23)
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
        val textureLength = buffer.readUnsignedShort()

        // Calculate offsets
        var offset = 0
        val vertexFlagsOffset = offset
        offset += vertexCount

        val faceTypesOffset = offset
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

        val faceTexturesOffset = offset
        if (hasRenderTypes == 1) {
            offset += faceCount * 2
        }

        val faceColorsOffset = offset
        offset += faceCount * 2

        val vertexXOffset = offset
        offset += vertexXLength

        val vertexYOffset = offset
        offset += vertexYLength

        val vertexZOffset = offset
        offset += vertexZLength

        val textureTrianglesOffset = offset
        offset += texturedFaceCount * 6

        val textureCoordOffset = offset

        // Decode vertices
        val vertexX = IntArray(vertexCount)
        val vertexY = IntArray(vertexCount)
        val vertexZ = IntArray(vertexCount)

        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        buffer.readBytes(vertexFlags)

        buffer.position(vertexXOffset)
        var vX = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if (flag and 1 != 0) {
                vX += readSignedSmart(buffer)
            }
            vertexX[i] = vX
        }

        buffer.position(vertexYOffset)
        var vY = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if (flag and 2 != 0) {
                vY += readSignedSmart(buffer)
            }
            vertexY[i] = vY
        }

        buffer.position(vertexZOffset)
        var vZ = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if (flag and 4 != 0) {
                vZ += readSignedSmart(buffer)
            }
            vertexZ[i] = vZ
        }

        // Decode faces
        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)

        buffer.position(faceTypesOffset)
        val faceTypes = ByteArray(faceCount)
        buffer.readBytes(faceTypes)

        buffer.position(faceIndicesOffset)
        var a = 0
        var b = 0
        var c = 0
        var lastIndex = 0
        for (i in 0 until faceCount) {
            val type = faceTypes[i].toInt() and 0xff
            when (type) {
                1 -> {
                    a = readSignedSmart(buffer) + lastIndex
                    lastIndex = a
                    b = readSignedSmart(buffer) + lastIndex
                    lastIndex = b
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
                2 -> {
                    b = c
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
                3 -> {
                    a = c
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
                4 -> {
                    val temp = a
                    a = b
                    b = temp
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
            }
            faceA[i] = a
            faceB[i] = b
            faceC[i] = c
        }

        // Decode colors
        val faceColors = ShortArray(faceCount)
        buffer.position(faceColorsOffset)
        for (i in 0 until faceCount) {
            faceColors[i] = buffer.readUnsignedShort().toShort()
        }

        // Decode priorities
        val facePriorities: ByteArray? = if (hasPriorities == 255) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount).also { buffer.readBytes(it) }
        } else {
            null
        }

        // Decode alphas
        val faceAlphas: ByteArray? = if (hasAlphas == 1) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount).also { buffer.readBytes(it) }
        } else {
            null
        }

        // Decode render types
        val faceRenderTypes: ByteArray? = if (hasRenderTypes == 1) {
            buffer.position(faceRenderTypesOffset)
            ByteArray(faceCount).also { buffer.readBytes(it) }
        } else {
            null
        }

        // Decode face textures
        val faceTextures: ShortArray? = if (hasRenderTypes == 1) {
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

    private fun decodeOldFormat(modelId: Int, data: ByteArray): ModelDefinition {
        val buffer = BufferReader(data)

        // Read header from end of file
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

        // Calculate offsets
        var offset = 0
        val vertexFlagsOffset = offset
        offset += vertexCount

        val faceTypesOffset = offset
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

        // Decode vertices
        val vertexX = IntArray(vertexCount)
        val vertexY = IntArray(vertexCount)
        val vertexZ = IntArray(vertexCount)

        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        buffer.readBytes(vertexFlags)

        buffer.position(vertexXOffset)
        var vX = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if (flag and 1 != 0) {
                vX += readSignedSmart(buffer)
            }
            vertexX[i] = vX
        }

        buffer.position(vertexYOffset)
        var vY = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if (flag and 2 != 0) {
                vY += readSignedSmart(buffer)
            }
            vertexY[i] = vY
        }

        buffer.position(vertexZOffset)
        var vZ = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if (flag and 4 != 0) {
                vZ += readSignedSmart(buffer)
            }
            vertexZ[i] = vZ
        }

        // Decode faces
        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)

        buffer.position(faceTypesOffset)
        val faceTypes = ByteArray(faceCount)
        buffer.readBytes(faceTypes)

        buffer.position(faceIndicesOffset)
        var a = 0
        var b = 0
        var c = 0
        var lastIndex = 0
        for (i in 0 until faceCount) {
            val type = faceTypes[i].toInt() and 0xff
            when (type) {
                1 -> {
                    a = readSignedSmart(buffer) + lastIndex
                    lastIndex = a
                    b = readSignedSmart(buffer) + lastIndex
                    lastIndex = b
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
                2 -> {
                    b = c
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
                3 -> {
                    a = c
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
                4 -> {
                    val temp = a
                    a = b
                    b = temp
                    c = readSignedSmart(buffer) + lastIndex
                    lastIndex = c
                }
            }
            faceA[i] = a
            faceB[i] = b
            faceC[i] = c
        }

        // Decode colors
        val faceColors = ShortArray(faceCount)
        buffer.position(faceColorsOffset)
        for (i in 0 until faceCount) {
            faceColors[i] = buffer.readUnsignedShort().toShort()
        }

        // Decode priorities
        val facePriorities: ByteArray? = if (hasPriorities == 255) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount).also { buffer.readBytes(it) }
        } else {
            null
        }

        // Decode alphas
        val faceAlphas: ByteArray? = if (hasAlphas == 1) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount).also { buffer.readBytes(it) }
        } else {
            null
        }

        // Decode render types
        val faceRenderTypes: ByteArray? = if (hasRenderTypes == 1) {
            buffer.position(faceRenderTypesOffset)
            ByteArray(faceCount).also { buffer.readBytes(it) }
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

    private fun readSignedSmart(buffer: BufferReader): Int {
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
