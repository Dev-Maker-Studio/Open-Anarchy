package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import java.nio.BufferOverflowException

object ModelEncoder {

    fun encode(model: ModelDefinition): ByteArray {
        var capacity = capacity(model)
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                writer.encode(model)
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "Model ${model.id} does not encode into $LIMIT bytes." }
            }
        }
    }

    fun Writer.encode(model: ModelDefinition) {
        if (model.newFormat) {
            writeNewFormat(model)
        } else {
            writeOldFormat(model)
        }
    }

    private fun Writer.writeNewFormat(model: ModelDefinition) {
        val textures = model.textureTriangleCount
        val faces = model.faceCount
        val vertices = model.vertexCount
        val flags = vertexFlags(model)
        val indexTypes = faceIndexTypes(model)
        val hasFaceTypes = (model.footerFlags and ModelDecoder.FLAG_FACE_TYPES) != 0
        val hasParticles = (model.footerFlags and ModelDecoder.FLAG_PARTICLES) != 0
        val hasBillboards = (model.footerFlags and ModelDecoder.FLAG_BILLBOARDS) != 0
        val hasVersion = (model.footerFlags and ModelDecoder.FLAG_VERSION) != 0

        for (index in 0 until textures) {
            writeByte(model.texturesMapping?.get(index)?.toInt() ?: 0)
        }
        writeBytes(flags)
        if (hasFaceTypes) {
            writeSection(model.faceRenderTypes, faces)
        }
        writeBytes(indexTypes)
        if (model.modelPriority == ModelDecoder.PER_FACE_PRIORITY) {
            writeSection(model.facePriorities, faces)
        }
        if (model.faceSkinFlag == 1) {
            writeUnsigned(model.faceSkins, faces)
        }
        if (model.vertexSkinFlag == 1) {
            writeUnsigned(model.vertexSkins, vertices)
        }
        if (model.alphaFlag == 1) {
            writeSection(model.faceAlphas, faces)
        }
        val faceIndicesStart = position()
        writeFaceIndices(model, indexTypes)
        val faceIndicesLength = position() - faceIndicesStart
        if (model.textureFlag == 1) {
            for (index in 0 until faces) {
                writeShort(((model.faceTextures?.get(index)?.toInt() ?: -1) + 1) and 0xffff)
            }
        }
        val textureIndicesStart = position()
        val pointers = model.faceTexturePointers
        if (pointers != null) {
            for (index in 0 until faces) {
                if ((model.faceTextures?.get(index)?.toInt() ?: -1) != -1) {
                    writeByte((pointers[index].toInt() + 1) and 0xff)
                }
            }
        } else {
            model.unusedTexturePointers?.let { writeBytes(it) }
        }
        val textureIndicesLength = position() - textureIndicesStart
        for (index in 0 until faces) {
            writeShort(model.faceColors[index].toInt() and 0xffff)
        }
        val scale = if (model.version < ModelDecoder.UPSCALE_BELOW_VERSION) 2 else 0
        val vertexXLength = writeAxis(model.vertexX, flags, 0x1, scale)
        val vertexYLength = writeAxis(model.vertexY, flags, 0x2, scale)
        val vertexZLength = writeAxis(model.vertexZ, flags, 0x4, scale)
        writeTextureTriangles(model)
        if (hasParticles) {
            writeByte(model.particleEmitters?.size ?: 0)
            for (emitter in model.particleEmitters ?: emptyArray()) {
                writeShort(emitter.particleId)
                writeShort(emitter.appliesOnFace)
            }
            writeByte(model.particleEffectors?.size ?: 0)
            for (effector in model.particleEffectors ?: emptyArray()) {
                writeShort(effector.effectorId)
                writeShort(effector.appliesOnVertex)
            }
        }
        if (hasBillboards) {
            writeByte(model.billboards?.size ?: 0)
            for (billboard in model.billboards ?: emptyArray()) {
                writeShort(billboard.billboardId)
                writeShort(billboard.appliesOnFace)
                writeByte(billboard.depth)
                writeByte(billboard.distance)
            }
        }
        model.trailing?.let { writeBytes(it) }
        if (hasVersion) {
            writeByte(model.version)
        }
        writeShort(vertices)
        writeShort(faces)
        writeByte(textures)
        writeByte(model.footerFlags)
        writeByte(model.modelPriority)
        writeByte(model.alphaFlag)
        writeByte(model.faceSkinFlag)
        writeByte(model.textureFlag)
        writeByte(model.vertexSkinFlag)
        writeShort(vertexXLength)
        writeShort(vertexYLength)
        writeShort(vertexZLength)
        writeShort(faceIndicesLength)
        writeShort(textureIndicesLength)
        writeShort(0xffff)
    }

    private fun Writer.writeOldFormat(model: ModelDefinition) {
        val faces = model.faceCount
        val vertices = model.vertexCount
        val flags = vertexFlags(model)
        val indexTypes = faceIndexTypes(model)

        writeBytes(flags)
        writeBytes(indexTypes)
        if (model.modelPriority == ModelDecoder.PER_FACE_PRIORITY) {
            writeSection(model.facePriorities, faces)
        }
        if (model.faceSkinFlag == 1) {
            writeUnsigned(model.faceSkins, faces)
        }
        if (model.footerFlags == 1) {
            writeSection(model.faceRenderTypes, faces)
        }
        if (model.vertexSkinFlag == 1) {
            writeUnsigned(model.vertexSkins, vertices)
        }
        if (model.alphaFlag == 1) {
            writeSection(model.faceAlphas, faces)
        }
        val faceIndicesStart = position()
        writeFaceIndices(model, indexTypes)
        val faceIndicesLength = position() - faceIndicesStart
        for (index in 0 until faces) {
            writeShort(model.faceColors[index].toInt() and 0xffff)
        }
        for (index in 0 until model.textureTriangleCount) {
            writeShort(model.textureFaceX?.get(index)?.toInt()?.and(0xffff) ?: 0)
            writeShort(model.textureFaceY?.get(index)?.toInt()?.and(0xffff) ?: 0)
            writeShort(model.textureFaceZ?.get(index)?.toInt()?.and(0xffff) ?: 0)
        }
        val vertexXLength = writeAxis(model.vertexX, flags, 0x1, 2)
        val vertexYLength = writeAxis(model.vertexY, flags, 0x2, 2)
        val vertexZLength = writeAxis(model.vertexZ, flags, 0x4, 2)
        model.trailing?.let { writeBytes(it) }
        writeShort(vertices)
        writeShort(faces)
        writeByte(model.textureTriangleCount)
        writeByte(model.footerFlags)
        writeByte(model.modelPriority)
        writeByte(model.alphaFlag)
        writeByte(model.faceSkinFlag)
        writeByte(model.vertexSkinFlag)
        writeShort(vertexXLength)
        writeShort(vertexYLength)
        writeShort(vertexZLength)
        writeShort(faceIndicesLength)
    }

    private fun Writer.writeTextureTriangles(model: ModelDefinition) {
        val textures = model.textureTriangleCount
        if (textures == 0) {
            return
        }
        for (index in 0 until textures) {
            if (mapping(model, index) == 0) {
                writeTriangle(model, index)
            }
        }
        for (index in 0 until textures) {
            if (mapping(model, index) in 1..3) {
                writeTriangle(model, index)
            }
        }
        val bytes = ModelDecoder.scaleBytes(model.version)
        val scaled = model.version < ModelDecoder.UPSCALE_BELOW_VERSION
        var complex = 0
        for (index in 0 until textures) {
            val mapping = mapping(model, index)
            if (mapping !in 1..3) {
                continue
            }
            var x = model.texturesScaleX?.get(complex) ?: 0
            var y = model.texturesScaleY?.get(complex) ?: 0
            var z = model.texturesScaleZ?.get(complex) ?: 0
            if (scaled) {
                x = x shr 2
                y = y shr 2
                if (mapping != 1) {
                    z = z shr 2
                }
            }
            when (bytes) {
                6 -> {
                    writeShort(x and 0xffff)
                    writeShort(y and 0xffff)
                    writeShort(z and 0xffff)
                }
                7 -> {
                    writeShort(x and 0xffff)
                    writeMedium(y and 0xffffff)
                    writeShort(z and 0xffff)
                }
                else -> {
                    writeMedium(x and 0xffffff)
                    writeMedium(y and 0xffffff)
                    writeMedium(z and 0xffffff)
                }
            }
            complex++
        }
        complex = 0
        for (index in 0 until textures) {
            if (mapping(model, index) in 1..3) {
                writeByte(model.texturesRotation?.get(complex)?.toInt() ?: 0)
                complex++
            }
        }
        complex = 0
        for (index in 0 until textures) {
            if (mapping(model, index) in 1..3) {
                writeByte(model.texturesDirection?.get(complex)?.toInt() ?: 0)
                complex++
            }
        }
        complex = 0
        var cube = 0
        for (index in 0 until textures) {
            val mapping = mapping(model, index)
            if (mapping !in 1..3) {
                continue
            }
            writeByte(model.texturesSpeed?.get(complex) ?: 0)
            if (mapping == 2) {
                writeByte(model.texturesUtrans?.get(cube) ?: 0)
                writeByte(model.texturesVtrans?.get(cube) ?: 0)
                cube++
            }
            complex++
        }
    }

    private fun Writer.writeTriangle(model: ModelDefinition, index: Int) {
        writeShort(model.textureFaceX?.get(index)?.toInt()?.and(0xffff) ?: 0)
        writeShort(model.textureFaceY?.get(index)?.toInt()?.and(0xffff) ?: 0)
        writeShort(model.textureFaceZ?.get(index)?.toInt()?.and(0xffff) ?: 0)
    }

    private fun mapping(model: ModelDefinition, index: Int): Int =
        model.texturesMapping?.get(index)?.toInt()?.and(0xff) ?: 0

    private fun Writer.writeAxis(values: IntArray, flags: ByteArray, bit: Int, shift: Int): Int {
        val start = position()
        var previous = 0
        for (index in values.indices) {
            val value = values[index] shr shift
            if ((flags[index].toInt() and bit) != 0) {
                writeModelSmart(value - previous)
            }
            previous = value
        }
        return position() - start
    }

    private fun Writer.writeFaceIndices(model: ModelDefinition, types: ByteArray) {
        var last = 0
        for (index in types.indices) {
            val faceA = model.faceA[index]
            val faceB = model.faceB[index]
            val faceC = model.faceC[index]
            when (types[index].toInt() and 0xff) {
                1 -> {
                    writeModelSmart(faceA - last)
                    writeModelSmart(faceB - faceA)
                    writeModelSmart(faceC - faceB)
                }
                2, 3, 4 -> writeModelSmart(faceC - last)
                else -> {}
            }
            last = faceC
        }
    }

    private fun Writer.writeModelSmart(value: Int) {
        if (value in -64..63) {
            writeByte(value + 64)
        } else {
            writeShort(value + 49152)
        }
    }

    private fun Writer.writeSection(values: ByteArray?, count: Int) {
        for (index in 0 until count) {
            writeByte(values?.get(index)?.toInt() ?: 0)
        }
    }

    private fun Writer.writeUnsigned(values: IntArray?, count: Int) {
        for (index in 0 until count) {
            writeByte(values?.get(index) ?: 0)
        }
    }

    fun vertexFlags(model: ModelDefinition): ByteArray = model.vertexFlags ?: deriveVertexFlags(model)

    fun deriveVertexFlags(model: ModelDefinition): ByteArray {
        val shift = if (model.version < ModelDecoder.UPSCALE_BELOW_VERSION) 2 else 0
        val flags = ByteArray(model.vertexCount)
        var x = 0
        var y = 0
        var z = 0
        for (index in 0 until model.vertexCount) {
            val vertexX = model.vertexX[index] shr shift
            val vertexY = model.vertexY[index] shr shift
            val vertexZ = model.vertexZ[index] shr shift
            var flag = 0
            if (vertexX != x) {
                flag = flag or 0x1
            }
            if (vertexY != y) {
                flag = flag or 0x2
            }
            if (vertexZ != z) {
                flag = flag or 0x4
            }
            flags[index] = flag.toByte()
            x = vertexX
            y = vertexY
            z = vertexZ
        }
        return flags
    }

    fun faceIndexTypes(model: ModelDefinition): ByteArray = model.faceIndexTypes ?: deriveFaceIndexTypes(model)

    fun deriveFaceIndexTypes(model: ModelDefinition): ByteArray {
        val types = ByteArray(model.faceCount)
        var a = 0
        var b = 0
        var c = 0
        for (index in 0 until model.faceCount) {
            val faceA = model.faceA[index]
            val faceB = model.faceB[index]
            val faceC = model.faceC[index]
            types[index] = when {
                index > 0 && faceA == a && faceB == c -> 2
                index > 0 && faceA == c && faceB == b -> 3
                index > 0 && faceA == b && faceB == a -> 4
                else -> 1
            }
            a = faceA
            b = faceB
            c = faceC
        }
        return types
    }

    private fun capacity(model: ModelDefinition): Int =
        HEADROOM + model.vertexCount * 9 + model.faceCount * 16 + model.textureTriangleCount * 22 +
            (model.particleEmitters?.size ?: 0) * 4 + (model.particleEffectors?.size ?: 0) * 4 +
            (model.billboards?.size ?: 0) * 6 + (model.trailing?.size ?: 0)

    private const val HEADROOM = 64

    private const val LIMIT = 8 * 1024 * 1024
}
