package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardData
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.data.ParticleEffectorAttachment
import world.gregs.voidps.cache.definition.data.ParticleEmitterAttachment

enum class ModelUnavailableReason {
    MISSING_ARCHIVE,

    EMPTY_PLACEHOLDER,

    TRUNCATED,

    DECODE_ERROR
}

data class ModelUnavailable(val reason: ModelUnavailableReason, val message: String)

object ModelDecoder {

    private const val MIN_MODEL_BYTES = 20

    const val NEW_FORMAT_FOOTER_BYTES = 23

    const val OLD_FORMAT_FOOTER_BYTES = 18

    const val FLAG_FACE_TYPES = 0x1

    const val FLAG_PARTICLES = 0x2

    const val FLAG_BILLBOARDS = 0x4

    const val FLAG_VERSION = 0x8

    const val DEFAULT_VERSION = 12

    fun decode(cache: Cache, modelId: Int): ModelDefinition? {
        val data = cache.data(Index.MODELS, modelId) ?: return null
        return try {
            decode(modelId, data)
        } catch (e: Exception) {
            System.err.println("Failed to decode model $modelId: ${e.message}")
            null
        }
    }

    fun describeUnavailable(cache: Cache, modelId: Int): ModelUnavailable? {
        val data = cache.data(Index.MODELS, modelId)
            ?: return ModelUnavailable(
                ModelUnavailableReason.MISSING_ARCHIVE,
                "no archive $modelId in index ${Index.MODELS}: this id is not in the cache"
            )

        if (data.size < MIN_MODEL_BYTES) {
            return ModelUnavailable(
                ModelUnavailableReason.TRUNCATED,
                "archive is only ${data.size} bytes, too short to hold a model header"
            )
        }

        val isNewFormat = newFormat(data)
        val footerOffset = data.size - if (isNewFormat) NEW_FORMAT_FOOTER_BYTES else OLD_FORMAT_FOOTER_BYTES
        if (footerOffset < 0) {
            return ModelUnavailable(
                ModelUnavailableReason.TRUNCATED,
                "archive is ${data.size} bytes, shorter than its own footer"
            )
        }

        val reader = BufferReader(data)
        reader.position(footerOffset)
        val vertexCount = reader.readUnsignedShort()
        val faceCount = reader.readUnsignedShort()
        if (vertexCount == 0 || faceCount == 0) {
            return ModelUnavailable(
                ModelUnavailableReason.EMPTY_PLACEHOLDER,
                "archive is present (${data.size} bytes) and decompresses cleanly, but declares " +
                        "$vertexCount vertices and $faceCount faces: an empty placeholder with no " +
                        "geometry. Nothing can draw it, and the reference client draws nothing either"
            )
        }

        return try {
            if (decode(modelId, data) == null) {
                ModelUnavailable(
                    ModelUnavailableReason.TRUNCATED,
                    "header declares $vertexCount vertices and $faceCount faces, but the sections " +
                            "they describe run past the end of the ${data.size}-byte archive"
                )
            } else {
                null
            }
        } catch (e: Exception) {
            ModelUnavailable(
                ModelUnavailableReason.DECODE_ERROR,
                "decoding threw ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun newFormat(data: ByteArray): Boolean =
        data.size >= 2 && data[data.size - 1] == (-1).toByte() && data[data.size - 2] == (-1).toByte()

    fun read(modelId: Int, data: ByteArray): ModelDefinition {
        check(data.size >= MIN_MODEL_BYTES) { "Model $modelId is ${data.size} bytes, too short for a footer." }
        val model = if (newFormat(data)) decodeNewFormat(modelId, data) else decodeOldFormat(modelId, data)
        return checkNotNull(model) { "Model $modelId declares sections that run past its ${data.size} bytes." }
    }

    fun decode(modelId: Int, data: ByteArray): ModelDefinition? {
        if (data.size < MIN_MODEL_BYTES) {
            return null
        }
        val model = if (newFormat(data)) {
            decodeNewFormat(modelId, data)
        } else {
            decodeOldFormat(modelId, data)
        }
        if (model == null || model.vertexCount == 0 || model.faceCount == 0) {
            return null
        }
        return model
    }

    private fun decodeNewFormat(modelId: Int, data: ByteArray): ModelDefinition? {
        val buffer = BufferReader(data)
        val buffer2 = BufferReader(data)

        buffer.position(data.size - NEW_FORMAT_FOOTER_BYTES)
        val vertexCount = buffer.readUnsignedShort()
        val faceCount = buffer.readUnsignedShort()
        val texturedFaceCount = buffer.readUnsignedByte()
        val footerFlags = buffer.readUnsignedByte()

        val hasFaceTypes = (footerFlags and FLAG_FACE_TYPES) != 0
        val hasParticleEffects = (footerFlags and FLAG_PARTICLES) != 0
        val hasBillboards = (footerFlags and FLAG_BILLBOARDS) != 0
        val hasVersion = (footerFlags and FLAG_VERSION) != 0

        var version = DEFAULT_VERSION
        if (hasVersion) {
            buffer.position(data.size - NEW_FORMAT_FOOTER_BYTES - 1)
            version = buffer.readUnsignedByte()
            buffer.position(data.size - NEW_FORMAT_FOOTER_BYTES + 6)
        }

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

        var simpleTextureFaceCount = 0
        var complexTextureFaceCount = 0
        var cubeTextureFaceCount = 0
        var texturesMapping: ByteArray? = null
        if (texturedFaceCount > 0) {
            texturesMapping = ByteArray(texturedFaceCount)
            buffer.position(0)
            for (i in 0 until texturedFaceCount) {
                val mapping = buffer.readByte().toByte()
                texturesMapping[i] = mapping
                if (mapping.toInt() == 0) {
                    simpleTextureFaceCount++
                }
                if (mapping.toInt() in 1..3) {
                    complexTextureFaceCount++
                }
                if (mapping.toInt() == 2) {
                    cubeTextureFaceCount++
                }
            }
        }

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
        if (modelPriority == PER_FACE_PRIORITY) {
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

        val simpleTexTriOffset = offset
        offset += simpleTextureFaceCount * 6

        val complexTexTriOffset = offset
        offset += complexTextureFaceCount * 6

        val complexTexScaleOffset = offset
        offset += complexTextureFaceCount * scaleBytes(version)

        val complexTexRotationOffset = offset
        offset += complexTextureFaceCount

        val complexTexDirectionOffset = offset
        offset += complexTextureFaceCount

        val complexTexSpeedOffset = offset
        offset += complexTextureFaceCount + cubeTextureFaceCount * 2

        val footerOffset = offset

        val minRequired = vertexZOffset + vertexZLength
        if (minRequired > data.size) {
            return null
        }

        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        for (i in 0 until vertexCount) {
            vertexFlags[i] = buffer.readByte().toByte()
        }

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

        val shouldUpscale = version < UPSCALE_BELOW_VERSION
        if (shouldUpscale) {
            for (i in 0 until vertexCount) {
                vertexX[i] = vertexX[i] shl 2
                vertexY[i] = vertexY[i] shl 2
                vertexZ[i] = vertexZ[i] shl 2
            }
        }

        val vertexSkins: IntArray? = if (hasVertexSkins == 1) {
            buffer.position(vertexSkinOffset)
            IntArray(vertexCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        buffer.position(faceIndexTypesOffset)
        val faceIndexTypes = ByteArray(faceCount)
        for (i in 0 until faceCount) {
            faceIndexTypes[i] = buffer.readByte().toByte()
        }

        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)
        buffer.position(faceIndicesOffset)
        readFaces(buffer, faceIndexTypes, faceA, faceB, faceC)

        val faceColors = ShortArray(faceCount)
        if (faceColorsOffset + faceCount * 2 <= data.size) {
            buffer.position(faceColorsOffset)
            for (i in 0 until faceCount) {
                faceColors[i] = buffer.readUnsignedShort().toShort()
            }
        }

        val facePriorities: ByteArray? = if (modelPriority == PER_FACE_PRIORITY && facePrioritiesOffset + faceCount <= data.size) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        val faceAlphas: ByteArray? = if (hasFaceAlpha == 1 && faceAlphasOffset + faceCount <= data.size) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        val faceSkins: IntArray? = if (hasFaceSkins == 1 && faceSkinOffset + faceCount <= data.size) {
            buffer.position(faceSkinOffset)
            IntArray(faceCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        val faceRenderTypes: ByteArray? = if (hasFaceTypes && faceTypeOffset + faceCount <= data.size) {
            buffer.position(faceTypeOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        val faceTextures: ShortArray? = if (hasFaceTextures == 1 && faceTexturesOffset + faceCount * 2 <= data.size) {
            buffer.position(faceTexturesOffset)
            ShortArray(faceCount) { (buffer.readUnsignedShort() - 1).toShort() }
        } else {
            null
        }

        val faceTexturePointers: ByteArray? = if (hasFaceTextures == 1 && texturedFaceCount > 0 && textureIndicesOffset + textureIndicesLength <= data.size) {
            buffer.position(textureIndicesOffset)
            ByteArray(faceCount) { faceIdx ->
                if (faceTextures != null && faceTextures[faceIdx].toInt() != -1) {
                    (buffer.readUnsignedByte() - 1).toByte()
                } else {
                    -1
                }
            }
        } else {
            null
        }

        val unusedTexturePointers: ByteArray? = if (faceTexturePointers == null && hasFaceTextures == 1 &&
            textureIndicesLength > 0 && textureIndicesOffset + textureIndicesLength <= data.size
        ) {
            buffer.position(textureIndicesOffset)
            ByteArray(textureIndicesLength) { buffer.readByte().toByte() }
        } else {
            null
        }

        var textureFaceX: ShortArray? = null
        var textureFaceY: ShortArray? = null
        var textureFaceZ: ShortArray? = null
        var texturesScaleX: IntArray? = null
        var texturesScaleY: IntArray? = null
        var texturesScaleZ: IntArray? = null
        var texturesRotation: ByteArray? = null
        var texturesDirection: ByteArray? = null
        var texturesSpeed: IntArray? = null
        var texturesUtrans: IntArray? = null
        var texturesVtrans: IntArray? = null

        if (texturedFaceCount > 0 && simpleTexTriOffset + simpleTextureFaceCount * 6 <= data.size) {
            textureFaceX = ShortArray(texturedFaceCount)
            textureFaceY = ShortArray(texturedFaceCount)
            textureFaceZ = ShortArray(texturedFaceCount)

            if (complexTextureFaceCount > 0) {
                texturesScaleX = IntArray(complexTextureFaceCount)
                texturesScaleY = IntArray(complexTextureFaceCount)
                texturesScaleZ = IntArray(complexTextureFaceCount)
                texturesRotation = ByteArray(complexTextureFaceCount)
                texturesDirection = ByteArray(complexTextureFaceCount)
                texturesSpeed = IntArray(complexTextureFaceCount)
            }
            if (cubeTextureFaceCount > 0) {
                texturesUtrans = IntArray(cubeTextureFaceCount)
                texturesVtrans = IntArray(cubeTextureFaceCount)
            }

            val scaleBuffer = BufferReader(data)
            val rotationBuffer = BufferReader(data)
            val directionBuffer = BufferReader(data)
            val speedBuffer = BufferReader(data)

            buffer.position(simpleTexTriOffset)
            buffer2.position(complexTexTriOffset)
            scaleBuffer.position(complexTexScaleOffset)
            rotationBuffer.position(complexTexRotationOffset)
            directionBuffer.position(complexTexDirectionOffset)
            speedBuffer.position(complexTexSpeedOffset)

            var complexIdx = 0
            var cubeIdx = 0

            for (i in 0 until texturedFaceCount) {
                val mappingType = texturesMapping?.get(i)?.toInt()?.and(0xff) ?: 0
                if (mappingType == 0) {
                    textureFaceX[i] = buffer.readUnsignedShort().toShort()
                    textureFaceY[i] = buffer.readUnsignedShort().toShort()
                    textureFaceZ[i] = buffer.readUnsignedShort().toShort()
                } else if (mappingType in 1..3) {
                    textureFaceX[i] = buffer2.readUnsignedShort().toShort()
                    textureFaceY[i] = buffer2.readUnsignedShort().toShort()
                    textureFaceZ[i] = buffer2.readUnsignedShort().toShort()

                    if (version < 15) {
                        texturesScaleX!![complexIdx] = scaleBuffer.readUnsignedShort()
                        if (version < 14) {
                            texturesScaleY!![complexIdx] = scaleBuffer.readUnsignedShort()
                        } else {
                            texturesScaleY!![complexIdx] = scaleBuffer.readUnsignedMedium()
                        }
                        texturesScaleZ!![complexIdx] = scaleBuffer.readUnsignedShort()
                    } else {
                        texturesScaleX!![complexIdx] = scaleBuffer.readUnsignedMedium()
                        texturesScaleY!![complexIdx] = scaleBuffer.readUnsignedMedium()
                        texturesScaleZ!![complexIdx] = scaleBuffer.readUnsignedMedium()
                    }

                    texturesRotation!![complexIdx] = rotationBuffer.readByte().toByte()
                    texturesDirection!![complexIdx] = directionBuffer.readByte().toByte()
                    texturesSpeed!![complexIdx] = speedBuffer.readByte()

                    if (mappingType == 2) {
                        texturesUtrans!![cubeIdx] = speedBuffer.readByte()
                        texturesVtrans!![cubeIdx] = speedBuffer.readByte()
                        cubeIdx++
                    }

                    complexIdx++
                }
            }

            if (shouldUpscale && complexTextureFaceCount > 0 && texturesScaleX != null) {
                val scaleX = texturesScaleX
                val scaleY = texturesScaleY!!
                val scaleZ = texturesScaleZ!!
                var scaleIdx = 0
                for (i in 0 until texturedFaceCount) {
                    val mappingType = texturesMapping?.get(i)?.toInt()?.and(0xff) ?: 0
                    if (mappingType in 1..3) {
                        scaleX[scaleIdx] = scaleX[scaleIdx] shl 2
                        scaleY[scaleIdx] = scaleY[scaleIdx] shl 2
                        if (mappingType != 1) {
                            scaleZ[scaleIdx] = scaleZ[scaleIdx] shl 2
                        }
                        scaleIdx++
                    }
                }
            }
        }

        var particleEmitters: Array<ParticleEmitterAttachment>? = null
        var particleEffectors: Array<ParticleEffectorAttachment>? = null
        var billboards: Array<BillboardData>? = null

        buffer.position(footerOffset)
        if (hasParticleEffects) {
            val emitterCount = buffer.readUnsignedByte()
            if (emitterCount > 0) {
                particleEmitters = Array(emitterCount) {
                    ParticleEmitterAttachment(buffer.readUnsignedShort(), buffer.readUnsignedShort())
                }
            }
            val effectorCount = buffer.readUnsignedByte()
            if (effectorCount > 0) {
                particleEffectors = Array(effectorCount) {
                    ParticleEffectorAttachment(buffer.readUnsignedShort(), buffer.readUnsignedShort())
                }
            }
        }

        if (hasBillboards) {
            val billboardCount = buffer.readUnsignedByte()
            if (billboardCount > 0) {
                billboards = Array(billboardCount) {
                    val id = buffer.readUnsignedShort()
                    val face = buffer.readUnsignedShort()
                    val depth = buffer.readUnsignedByte()
                    val distance = buffer.readByte()
                    BillboardData(id, face, depth, distance)
                }
            }
        }

        val end = data.size - NEW_FORMAT_FOOTER_BYTES - if (hasVersion) 1 else 0
        val trailing = trailing(data, buffer.position(), end)

        return ModelDefinition(
            id = modelId,
            version = version,
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
            faceTexturePointers = faceTexturePointers,
            textureTriangleCount = texturedFaceCount,
            texturesMapping = texturesMapping,
            textureFaceX = textureFaceX,
            textureFaceY = textureFaceY,
            textureFaceZ = textureFaceZ,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null,
            unusedTexturePointers = unusedTexturePointers,
            texturesScaleX = texturesScaleX,
            texturesScaleY = texturesScaleY,
            texturesScaleZ = texturesScaleZ,
            texturesRotation = texturesRotation,
            texturesDirection = texturesDirection,
            texturesSpeed = texturesSpeed,
            texturesUtrans = texturesUtrans,
            texturesVtrans = texturesVtrans,
            billboards = billboards,
            newFormat = true,
            footerFlags = footerFlags,
            modelPriority = modelPriority,
            alphaFlag = hasFaceAlpha,
            faceSkinFlag = hasFaceSkins,
            textureFlag = hasFaceTextures,
            vertexSkinFlag = hasVertexSkins,
            vertexFlags = vertexFlags,
            faceIndexTypes = faceIndexTypes,
            faceSkins = faceSkins,
            vertexSkins = vertexSkins,
            particleEmitters = particleEmitters,
            particleEffectors = particleEffectors,
            trailing = trailing
        )
    }

    private fun decodeOldFormat(modelId: Int, data: ByteArray): ModelDefinition? {
        val buffer = BufferReader(data)

        buffer.position(data.size - OLD_FORMAT_FOOTER_BYTES)
        val vertexCount = buffer.readUnsignedShort()
        val faceCount = buffer.readUnsignedShort()
        val texturedFaceCount = buffer.readUnsignedByte()

        val hasRenderTypes = buffer.readUnsignedByte()
        val modelPriority = buffer.readUnsignedByte()
        val hasAlphas = buffer.readUnsignedByte()
        val hasFaceSkins = buffer.readUnsignedByte()
        val hasVertexSkins = buffer.readUnsignedByte()

        val vertexXLength = buffer.readUnsignedShort()
        val vertexYLength = buffer.readUnsignedShort()
        val vertexZLength = buffer.readUnsignedShort()
        val faceIndicesLength = buffer.readUnsignedShort()

        var offset = 0
        val vertexFlagsOffset = offset
        offset += vertexCount

        val faceIndexTypesOffset = offset
        offset += faceCount

        val facePrioritiesOffset = offset
        if (modelPriority == PER_FACE_PRIORITY) {
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

        val textureTrianglesOffset = offset
        offset += texturedFaceCount * 6

        val vertexXOffset = offset
        offset += vertexXLength

        val vertexYOffset = offset
        offset += vertexYLength

        val vertexZOffset = offset
        offset += vertexZLength

        if (vertexZOffset + vertexZLength > data.size) {
            return null
        }

        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        for (i in 0 until vertexCount) {
            vertexFlags[i] = buffer.readByte().toByte()
        }

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

        for (i in 0 until vertexCount) {
            vertexX[i] = vertexX[i] shl 2
            vertexY[i] = vertexY[i] shl 2
            vertexZ[i] = vertexZ[i] shl 2
        }

        val vertexSkins: IntArray? = if (hasVertexSkins == 1) {
            buffer.position(vertexSkinOffset)
            IntArray(vertexCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        buffer.position(faceIndexTypesOffset)
        val faceIndexTypes = ByteArray(faceCount)
        for (i in 0 until faceCount) {
            faceIndexTypes[i] = buffer.readByte().toByte()
        }

        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)
        buffer.position(faceIndicesOffset)
        readFaces(buffer, faceIndexTypes, faceA, faceB, faceC)

        val faceColors = ShortArray(faceCount)
        if (faceColorsOffset + faceCount * 2 <= data.size) {
            buffer.position(faceColorsOffset)
            for (i in 0 until faceCount) {
                faceColors[i] = buffer.readUnsignedShort().toShort()
            }
        }

        val facePriorities: ByteArray? = if (modelPriority == PER_FACE_PRIORITY && facePrioritiesOffset + faceCount <= data.size) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        val faceAlphas: ByteArray? = if (hasAlphas == 1 && faceAlphasOffset + faceCount <= data.size) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        val faceSkins: IntArray? = if (hasFaceSkins == 1 && faceSkinOffset + faceCount <= data.size) {
            buffer.position(faceSkinOffset)
            IntArray(faceCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        val faceRenderTypes: ByteArray? = if (hasRenderTypes == 1 && faceRenderTypesOffset + faceCount <= data.size) {
            buffer.position(faceRenderTypesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        var texturesMapping: ByteArray? = null
        var textureFaceX: ShortArray? = null
        var textureFaceY: ShortArray? = null
        var textureFaceZ: ShortArray? = null
        if (texturedFaceCount > 0 && textureTrianglesOffset + texturedFaceCount * 6 <= data.size) {
            texturesMapping = ByteArray(texturedFaceCount)
            textureFaceX = ShortArray(texturedFaceCount)
            textureFaceY = ShortArray(texturedFaceCount)
            textureFaceZ = ShortArray(texturedFaceCount)
            buffer.position(textureTrianglesOffset)
            for (i in 0 until texturedFaceCount) {
                textureFaceX[i] = buffer.readUnsignedShort().toShort()
                textureFaceY[i] = buffer.readUnsignedShort().toShort()
                textureFaceZ[i] = buffer.readUnsignedShort().toShort()
            }
        }

        val trailing = trailing(data, vertexZOffset + vertexZLength, data.size - OLD_FORMAT_FOOTER_BYTES)

        return ModelDefinition(
            id = modelId,
            version = DEFAULT_VERSION,
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
            faceTexturePointers = null,
            textureTriangleCount = texturedFaceCount,
            texturesMapping = texturesMapping,
            textureFaceX = textureFaceX,
            textureFaceY = textureFaceY,
            textureFaceZ = textureFaceZ,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null,
            texturesScaleX = null,
            texturesScaleY = null,
            texturesScaleZ = null,
            texturesRotation = null,
            texturesDirection = null,
            texturesSpeed = null,
            texturesUtrans = null,
            texturesVtrans = null,
            billboards = null,
            newFormat = false,
            footerFlags = hasRenderTypes,
            modelPriority = modelPriority,
            alphaFlag = hasAlphas,
            faceSkinFlag = hasFaceSkins,
            textureFlag = 0,
            vertexSkinFlag = hasVertexSkins,
            vertexFlags = vertexFlags,
            faceIndexTypes = faceIndexTypes,
            faceSkins = faceSkins,
            vertexSkins = vertexSkins,
            trailing = trailing
        )
    }

    private fun readFaces(buffer: BufferReader, faceIndexTypes: ByteArray, faceA: IntArray, faceB: IntArray, faceC: IntArray) {
        var a = 0
        var b = 0
        var c = 0
        var lastIndex = 0
        for (i in faceIndexTypes.indices) {
            when (faceIndexTypes[i].toInt() and 0xff) {
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
    }

    private fun trailing(data: ByteArray, from: Int, to: Int): ByteArray? =
        if (to > from && to <= data.size && from >= 0) data.copyOfRange(from, to) else null

    fun scaleBytes(version: Int): Int = when {
        version < 14 -> 6
        version == 14 -> 7
        else -> 9
    }

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

    const val PER_FACE_PRIORITY = 255

    const val UPSCALE_BELOW_VERSION = 13
}
