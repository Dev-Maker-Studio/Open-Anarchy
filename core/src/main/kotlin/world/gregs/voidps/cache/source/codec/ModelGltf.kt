package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.BillboardData
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.data.ParticleEffectorAttachment
import world.gregs.voidps.cache.definition.data.ParticleEmitterAttachment
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One model as a glTF 2.0 binary file - a `.glb`, which Blender opens and saves natively.
 *
 * The geometry is real glTF: the vertices are a `POSITION` accessor and the faces an `indices`
 * accessor, so any glTF tool draws the model without knowing anything about RuneScape. Everything
 * else a 727 model file carries has no glTF equivalent - a colour per *face* rather than per
 * vertex, a render type, a priority, a bone label, a texture projection, the particle and
 * billboard attachments, the footer's own bytes - and that rides in `extras` on the node, as
 * compact JSON arrays named after what they are. **The extras are what the re-encoder reads**;
 * nothing in the glTF proper is a second copy of them.
 *
 * ```
 * glb
 *   header      "glTF", version 2, total length
 *   JSON chunk  the glTF asset, compact, fixed key order, padded with spaces
 *   BIN chunk   the vertex positions then the face indices, padded with zeroes
 * ```
 *
 * Rules:
 *
 * - **Positions are the RS integers, unscaled.** They are written as `FLOAT` because that is what
 *   the glTF core specification allows for `POSITION`, and a float32 holds every integer up to
 *   2^24 exactly, so the ints come back as themselves. Nothing is divided, transformed or
 *   re-oriented: what the cache holds is what the file holds.
 * - **Indices are `UNSIGNED_SHORT` while every index fits**, and `UNSIGNED_INT` otherwise, which is
 *   also how an index a malformed model puts out of range survives.
 * - **Deterministic to the byte.** The JSON is written by hand in a fixed key order with no
 *   whitespace, the chunks are padded the way the specification requires (spaces after the JSON,
 *   zeroes after the binary), and every number in it is an integer, so re-unpacking an unchanged
 *   cache rewrites nothing.
 * - **A model with no geometry has no mesh.** Eight archives in 727 declare no vertices and no
 *   faces; glTF has no way to spell an empty primitive, so those files carry the node and its
 *   extras and nothing else.
 * - **Layout fidelity is absent when it is derivable.** `vertexFlags` and `faceIndexTypes` are the
 *   file's own per element arrays, and every model in the 727 cache has exactly the ones
 *   [ModelEncoder] derives, so no file carries either.
 *
 * Reading goes through the accessors properly - byte offsets, strides and component types - rather
 * than assuming this writer's layout, so a `.glb` that has been through another glTF tool still
 * reads. The one thing it insists on is a single primitive, because the extras are indexed by the
 * model's own face order and a split into several primitives loses it.
 */
internal object ModelGltf {

    /** `glTF`, little endian. */
    private const val MAGIC = 0x46546C67

    private const val VERSION = 2

    /** `JSON`, little endian. */
    private const val CHUNK_JSON = 0x4E4F534A

    /** `BIN ` , little endian. */
    private const val CHUNK_BIN = 0x004E4942

    private const val HEADER_BYTES = 12

    private const val CHUNK_HEADER_BYTES = 8

    private const val FLOAT = 5126
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val BYTE = 5120
    private const val UNSIGNED_BYTE = 5121
    private const val SHORT = 5122

    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963

    /** Triangles; the mode a model with faces is drawn in. */
    private const val TRIANGLES = 4

    /** Points; the mode a model with vertices but no faces is drawn in. */
    private const val POINTS = 0

    /** The largest index `UNSIGNED_SHORT` may carry: 65535 is reserved for primitive restart. */
    private const val MAX_SHORT_INDEX = 65534

    /** The JSON chunk is padded with spaces; the binary chunk with [ZERO]. */
    private const val SPACE: Byte = 0x20

    private const val ZERO: Byte = 0

    private val json = Json { ignoreUnknownKeys = true }

    /** [model] as the exact bytes of its `.glb`. */
    fun write(model: ModelDefinition): ByteArray {
        val vertices = model.vertexCount
        val faces = model.faceCount
        check(vertices > 0 || faces == 0) {
            "Model ${model.id} has $faces faces and no vertices, which has no glTF spelling."
        }
        var largest = 0
        var negative = false
        for (face in 0 until faces) {
            largest = widest(model.faceA[face], largest)
            largest = widest(model.faceB[face], largest)
            largest = widest(model.faceC[face], largest)
            if (model.faceA[face] < 0 || model.faceB[face] < 0 || model.faceC[face] < 0) {
                negative = true
            }
        }
        // An index out of an unsigned short's range - or a negative one, which only a malformed
        // model has - goes in the wider accessor so that it comes back as itself.
        val wide = negative || largest > MAX_SHORT_INDEX
        val indexBytes = if (wide) 4 else 2
        val binary = ByteBuffer.allocate(vertices * 12 + faces * 3 * indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (vertex in 0 until vertices) {
            binary.putFloat(model.vertexX[vertex].toFloat())
            binary.putFloat(model.vertexY[vertex].toFloat())
            binary.putFloat(model.vertexZ[vertex].toFloat())
        }
        for (face in 0 until faces) {
            index(binary, model.faceA[face], wide)
            index(binary, model.faceB[face], wide)
            index(binary, model.faceC[face], wide)
        }
        return assemble(asset(model, vertices, faces, wide), binary.array())
    }

    private fun widest(index: Int, largest: Int): Int = if (index > largest) index else largest

    private fun index(binary: ByteBuffer, value: Int, wide: Boolean) {
        if (wide) {
            binary.putInt(value)
        } else {
            binary.putShort(value.toShort())
        }
    }

    /**
     * Read a `.glb` back into a definition of [id].
     *
     * The geometry comes from the accessors and everything else from `extras.rs`, which is where
     * the encoder's answers live: a file whose extras say a section exists gets that section back
     * whatever the geometry looks like.
     */
    fun read(bytes: ByteArray, id: Int): ModelDefinition {
        val chunks = chunks(bytes)
        val root = json.parseToJsonElement(String(chunks.first, Charsets.UTF_8)).jsonObject
        val node = root["nodes"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("Model $id has no glTF node.")
        val rs = node["extras"]?.jsonObject?.get("rs")?.jsonObject
            ?: throw IllegalArgumentException("Model $id carries no rs extras; it is not a cache model.")
        val binary = chunks.second
        var positions = IntArray(0)
        var indices = IntArray(0)
        val mesh = node["mesh"]?.jsonPrimitive?.int
        if (mesh != null) {
            val primitives = root["meshes"]!!.jsonArray[mesh].jsonObject["primitives"]!!.jsonArray
            require(primitives.size == 1) {
                "Model $id has ${primitives.size} primitives; the face data is indexed by one face " +
                    "order, so the mesh has to stay a single primitive."
            }
            val primitive = primitives[0].jsonObject
            val position = primitive["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int
            positions = accessor(root, binary, position, 3)
            primitive["indices"]?.let { indices = accessor(root, binary, it.jsonPrimitive.int, 1) }
        }
        val vertices = positions.size / 3
        val faces = indices.size / 3
        val mappings = bytes(rs["textureMappings"])
        val triangles = tuples(rs["textureTriangles"])
        val scales = tuples(rs["textureScales"])
        val translations = tuples(rs["cubeTranslations"])
        val emitters = tuples(rs["particleEmitters"])
        val effectors = tuples(rs["particleEffectors"])
        val billboards = tuples(rs["billboards"])
        val footer = rs["footer"]?.jsonObject ?: JsonObject(emptyMap())
        return ModelDefinition(
            id = id,
            version = rs["version"]?.jsonPrimitive?.int ?: ModelDecoder.DEFAULT_VERSION,
            vertexCount = vertices,
            faceCount = faces,
            vertexX = IntArray(vertices) { positions[it * 3] },
            vertexY = IntArray(vertices) { positions[it * 3 + 1] },
            vertexZ = IntArray(vertices) { positions[it * 3 + 2] },
            faceA = IntArray(faces) { indices[it * 3] },
            faceB = IntArray(faces) { indices[it * 3 + 1] },
            faceC = IntArray(faces) { indices[it * 3 + 2] },
            faceColors = shorts(rs["colours"]) ?: ShortArray(faces),
            faceAlphas = bytes(rs["alphas"]),
            faceRenderTypes = bytes(rs["renderTypes"]),
            facePriorities = bytes(rs["priorities"]),
            faceTextures = shorts(rs["materials"]),
            faceTexturePointers = bytes(rs["texturePointers"]),
            textureTriangleCount = mappings?.size ?: triangles.size,
            texturesMapping = mappings,
            textureFaceX = if (rs["textureTriangles"] == null) null else shortColumn(triangles, 0),
            textureFaceY = if (rs["textureTriangles"] == null) null else shortColumn(triangles, 1),
            textureFaceZ = if (rs["textureTriangles"] == null) null else shortColumn(triangles, 2),
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null,
            unusedTexturePointers = rs["unusedTexturePointers"]?.let { hex(it.jsonPrimitive.content) },
            texturesScaleX = if (rs["textureScales"] == null) null else column(scales, 0),
            texturesScaleY = if (rs["textureScales"] == null) null else column(scales, 1),
            texturesScaleZ = if (rs["textureScales"] == null) null else column(scales, 2),
            texturesRotation = bytes(rs["textureRotations"]),
            texturesDirection = bytes(rs["textureDirections"]),
            texturesSpeed = ints(rs["textureSpeeds"]),
            texturesUtrans = if (rs["cubeTranslations"] == null) null else column(translations, 0),
            texturesVtrans = if (rs["cubeTranslations"] == null) null else column(translations, 1),
            billboards = if (rs["billboards"] == null) {
                null
            } else {
                Array(billboards.size) {
                    BillboardData(billboards[it][0], billboards[it][1], billboards[it][2], billboards[it][3])
                }
            },
            newFormat = (rs["format"]?.jsonPrimitive?.content ?: "new") != "old",
            footerFlags = footer["flags"]?.jsonPrimitive?.int ?: 0,
            modelPriority = footer["priority"]?.jsonPrimitive?.int ?: 0,
            alphaFlag = footer["alphas"]?.jsonPrimitive?.int ?: 0,
            faceSkinFlag = footer["faceSkins"]?.jsonPrimitive?.int ?: 0,
            textureFlag = footer["materials"]?.jsonPrimitive?.int ?: 0,
            vertexSkinFlag = footer["vertexSkins"]?.jsonPrimitive?.int ?: 0,
            vertexFlags = bytes(rs["vertexFlags"]),
            faceIndexTypes = bytes(rs["faceIndexTypes"]),
            faceSkins = ints(rs["faceSkins"]),
            vertexSkins = ints(rs["vertexSkins"]),
            particleEmitters = if (rs["particleEmitters"] == null) {
                null
            } else {
                Array(emitters.size) { ParticleEmitterAttachment(emitters[it][0], emitters[it][1]) }
            },
            particleEffectors = if (rs["particleEffectors"] == null) {
                null
            } else {
                Array(effectors.size) { ParticleEffectorAttachment(effectors[it][0], effectors[it][1]) }
            },
            trailing = rs["trailing"]?.let { hex(it.jsonPrimitive.content) }
        )
    }

    /** The `.glb`'s JSON chunk, for a report or a test that wants to show what a file says. */
    fun json(bytes: ByteArray): String = String(chunks(bytes).first, Charsets.UTF_8)

    // --- writing -----------------------------------------------------------------------------

    /** The glTF asset, compact and in a fixed key order so the same model is the same bytes. */
    private fun asset(model: ModelDefinition, vertices: Int, faces: Int, wide: Boolean): String {
        val out = StringBuilder(1024 + model.faceCount * 8)
        val name = "model_" + model.id
        out.append("{\"asset\":{\"generator\":\"").append(GENERATOR).append("\",\"version\":\"2.0\"}")
        out.append(",\"scene\":0,\"scenes\":[{\"nodes\":[0]}]")
        out.append(",\"nodes\":[{")
        if (vertices > 0) {
            out.append("\"mesh\":0,")
        }
        out.append("\"name\":\"").append(name).append("\",\"extras\":{\"rs\":")
        extras(out, model)
        out.append("}}]")
        if (vertices > 0) {
            out.append(",\"meshes\":[{\"name\":\"").append(name).append("\",\"primitives\":[{")
            out.append("\"attributes\":{\"POSITION\":0}")
            if (faces > 0) {
                out.append(",\"indices\":1")
            }
            out.append(",\"mode\":").append(if (faces > 0) TRIANGLES else POINTS).append("}]}]")
            out.append(",\"accessors\":[{\"bufferView\":0,\"componentType\":").append(FLOAT)
            out.append(",\"count\":").append(vertices).append(",\"type\":\"VEC3\",\"min\":")
            bounds(out, model, vertices, least = true)
            out.append(",\"max\":")
            bounds(out, model, vertices, least = false)
            out.append('}')
            if (faces > 0) {
                out.append(",{\"bufferView\":1,\"componentType\":")
                    .append(if (wide) UNSIGNED_INT else UNSIGNED_SHORT)
                    .append(",\"count\":").append(faces * 3).append(",\"type\":\"SCALAR\"}")
            }
            out.append(']')
            val positionBytes = vertices * 12
            val indexBytes = faces * 3 * (if (wide) 4 else 2)
            out.append(",\"bufferViews\":[{\"buffer\":0,\"byteLength\":").append(positionBytes)
                .append(",\"byteOffset\":0,\"target\":").append(ARRAY_BUFFER).append('}')
            if (faces > 0) {
                out.append(",{\"buffer\":0,\"byteLength\":").append(indexBytes)
                    .append(",\"byteOffset\":").append(positionBytes)
                    .append(",\"target\":").append(ELEMENT_ARRAY_BUFFER).append('}')
            }
            out.append(']')
            out.append(",\"buffers\":[{\"byteLength\":").append(positionBytes + indexBytes).append("}]")
        }
        out.append('}')
        return out.toString()
    }

    /** The `POSITION` accessor's bounds, which glTF requires and every viewer uses to frame a model. */
    private fun bounds(out: StringBuilder, model: ModelDefinition, vertices: Int, least: Boolean) {
        var x = model.vertexX[0]
        var y = model.vertexY[0]
        var z = model.vertexZ[0]
        for (vertex in 1 until vertices) {
            val vertexX = model.vertexX[vertex]
            val vertexY = model.vertexY[vertex]
            val vertexZ = model.vertexZ[vertex]
            if (least) {
                if (vertexX < x) {
                    x = vertexX
                }
                if (vertexY < y) {
                    y = vertexY
                }
                if (vertexZ < z) {
                    z = vertexZ
                }
            } else {
                if (vertexX > x) {
                    x = vertexX
                }
                if (vertexY > y) {
                    y = vertexY
                }
                if (vertexZ > z) {
                    z = vertexZ
                }
            }
        }
        out.append('[').append(x).append(',').append(y).append(',').append(z).append(']')
    }

    /**
     * Everything a model file holds that glTF has no way to say, on the node as `extras.rs`.
     *
     * Signedness follows the field: colours, alphas, render types, priorities, mappings and skins
     * are unsigned; materials and texture pointers are signed, because -1 is their "none"; texture
     * rotations, directions, speeds and cube translations are signed bytes, which is what the
     * client reads them as.
     */
    private fun extras(out: StringBuilder, model: ModelDefinition) {
        out.append("{\"id\":").append(model.id)
        out.append(",\"format\":\"").append(if (model.newFormat) "new" else "old").append('"')
        out.append(",\"version\":").append(model.version)
        out.append(",\"footer\":{\"flags\":").append(model.footerFlags)
            .append(",\"priority\":").append(model.modelPriority)
            .append(",\"alphas\":").append(model.alphaFlag)
            .append(",\"faceSkins\":").append(model.faceSkinFlag)
            .append(",\"materials\":").append(model.textureFlag)
            .append(",\"vertexSkins\":").append(model.vertexSkinFlag)
            .append('}')
        scalars(out, "colours", model.faceCount) { model.faceColors[it].toInt() and 0xffff }
        model.faceRenderTypes?.let { values -> scalars(out, "renderTypes", values.size) { values[it].toInt() and 0xff } }
        model.facePriorities?.let { values -> scalars(out, "priorities", values.size) { values[it].toInt() and 0xff } }
        model.faceAlphas?.let { values -> scalars(out, "alphas", values.size) { values[it].toInt() and 0xff } }
        model.faceTextures?.let { values -> scalars(out, "materials", values.size) { values[it].toInt() } }
        model.faceTexturePointers?.let { values -> scalars(out, "texturePointers", values.size) { values[it].toInt() } }
        model.faceSkins?.let { values -> scalars(out, "faceSkins", values.size) { values[it] } }
        model.vertexSkins?.let { values -> scalars(out, "vertexSkins", values.size) { values[it] } }
        model.texturesMapping?.let { values ->
            scalars(out, "textureMappings", values.size) { values[it].toInt() and 0xff }
        }
        model.textureFaceX?.let { corners ->
            tuples(out, "textureTriangles", corners.size, 3) { index, part ->
                when (part) {
                    0 -> corners[index].toInt() and 0xffff
                    1 -> model.textureFaceY?.get(index)?.toInt()?.and(0xffff) ?: 0
                    else -> model.textureFaceZ?.get(index)?.toInt()?.and(0xffff) ?: 0
                }
            }
        }
        model.texturesScaleX?.let { scaleX ->
            tuples(out, "textureScales", scaleX.size, 3) { index, part ->
                when (part) {
                    0 -> scaleX[index]
                    1 -> model.texturesScaleY?.get(index) ?: 0
                    else -> model.texturesScaleZ?.get(index) ?: 0
                }
            }
        }
        model.texturesRotation?.let { values -> scalars(out, "textureRotations", values.size) { values[it].toInt() } }
        model.texturesDirection?.let { values -> scalars(out, "textureDirections", values.size) { values[it].toInt() } }
        model.texturesSpeed?.let { values -> scalars(out, "textureSpeeds", values.size) { values[it] } }
        model.texturesUtrans?.let { translationU ->
            tuples(out, "cubeTranslations", translationU.size, 2) { index, part ->
                if (part == 0) translationU[index] else model.texturesVtrans?.get(index) ?: 0
            }
        }
        model.particleEmitters?.let { emitters ->
            tuples(out, "particleEmitters", emitters.size, 2) { index, part ->
                if (part == 0) emitters[index].particleId else emitters[index].appliesOnFace
            }
        }
        model.particleEffectors?.let { effectors ->
            tuples(out, "particleEffectors", effectors.size, 2) { index, part ->
                if (part == 0) effectors[index].effectorId else effectors[index].appliesOnVertex
            }
        }
        model.billboards?.let { billboards ->
            tuples(out, "billboards", billboards.size, 4) { index, part ->
                when (part) {
                    0 -> billboards[index].billboardId
                    1 -> billboards[index].appliesOnFace
                    2 -> billboards[index].depth
                    else -> billboards[index].distance
                }
            }
        }
        // The two per element layout arrays, and only when the encoder would not derive them.
        model.vertexFlags?.let { flags ->
            if (!flags.contentEquals(ModelEncoder.deriveVertexFlags(model))) {
                scalars(out, "vertexFlags", flags.size) { flags[it].toInt() and 0xff }
            }
        }
        model.faceIndexTypes?.let { types ->
            if (!types.contentEquals(ModelEncoder.deriveFaceIndexTypes(model))) {
                scalars(out, "faceIndexTypes", types.size) { types[it].toInt() and 0xff }
            }
        }
        model.unusedTexturePointers?.let { bytes ->
            out.append(",\"unusedTexturePointers\":\"").append(hex(bytes)).append('"')
        }
        model.trailing?.let { bytes -> out.append(",\"trailing\":\"").append(hex(bytes)).append('"') }
        out.append('}')
    }

    private inline fun scalars(out: StringBuilder, name: String, count: Int, value: (Int) -> Int) {
        out.append(",\"").append(name).append("\":[")
        for (index in 0 until count) {
            if (index != 0) {
                out.append(',')
            }
            out.append(value(index))
        }
        out.append(']')
    }

    private inline fun tuples(out: StringBuilder, name: String, count: Int, arity: Int, value: (Int, Int) -> Int) {
        out.append(",\"").append(name).append("\":[")
        for (index in 0 until count) {
            if (index != 0) {
                out.append(',')
            }
            out.append('[')
            for (part in 0 until arity) {
                if (part != 0) {
                    out.append(',')
                }
                out.append(value(index, part))
            }
            out.append(']')
        }
        out.append(']')
    }

    /** The 12 byte header, the JSON chunk padded with spaces, and the binary chunk padded with zeroes. */
    private fun assemble(asset: String, binary: ByteArray): ByteArray {
        val text = asset.toByteArray(Charsets.UTF_8)
        val jsonPadding = padding(text.size)
        val binaryPadding = if (binary.isEmpty()) 0 else padding(binary.size)
        var length = HEADER_BYTES + CHUNK_HEADER_BYTES + text.size + jsonPadding
        if (binary.isNotEmpty()) {
            length += CHUNK_HEADER_BYTES + binary.size + binaryPadding
        }
        val out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(VERSION)
        out.putInt(length)
        out.putInt(text.size + jsonPadding)
        out.putInt(CHUNK_JSON)
        out.put(text)
        for (pad in 0 until jsonPadding) {
            out.put(SPACE)
        }
        if (binary.isNotEmpty()) {
            out.putInt(binary.size + binaryPadding)
            out.putInt(CHUNK_BIN)
            out.put(binary)
            for (pad in 0 until binaryPadding) {
                out.put(ZERO)
            }
        }
        return out.array()
    }

    private fun padding(size: Int): Int = (4 - (size and 3)) and 3

    // --- reading -----------------------------------------------------------------------------

    /** The JSON chunk and the binary chunk, which is empty when the file has none. */
    private fun chunks(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= HEADER_BYTES && buffer.getInt(0) == MAGIC) { "Not a glb: bad magic." }
        require(buffer.getInt(4) == VERSION) { "glb version ${buffer.getInt(4)}, expected $VERSION." }
        var offset = HEADER_BYTES
        var text: ByteArray? = null
        var binary = ByteArray(0)
        val end = minOf(buffer.getInt(8), bytes.size)
        while (offset + CHUNK_HEADER_BYTES <= end) {
            val length = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val start = offset + CHUNK_HEADER_BYTES
            require(start + length <= bytes.size) { "glb chunk at $offset runs past the file." }
            when (type) {
                CHUNK_JSON -> text = bytes.copyOfRange(start, start + length)
                CHUNK_BIN -> binary = bytes.copyOfRange(start, start + length)
            }
            offset = start + length
        }
        return (text ?: throw IllegalArgumentException("glb has no JSON chunk.")) to binary
    }

    /**
     * One accessor's values, [components] per element, read through its buffer view.
     *
     * General rather than an assumption about this writer's layout - byte offsets, strides and all
     * six integer and float component types - so a file that has been through another glTF tool
     * still reads. A sparse accessor is refused rather than silently read wrong.
     */
    private fun accessor(root: JsonObject, binary: ByteArray, index: Int, components: Int): IntArray {
        val accessor = root["accessors"]!!.jsonArray[index].jsonObject
        require(accessor["sparse"] == null) { "Sparse accessor $index is not supported." }
        val count = accessor["count"]!!.jsonPrimitive.int
        val componentType = accessor["componentType"]!!.jsonPrimitive.int
        val size = componentSize(componentType)
        val view = root["bufferViews"]!!.jsonArray[accessor["bufferView"]!!.jsonPrimitive.int].jsonObject
        val stride = view["byteStride"]?.jsonPrimitive?.int ?: (size * components)
        val start = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) +
            (accessor["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        val values = IntArray(count * components)
        for (element in 0 until count) {
            val offset = start + element * stride
            for (part in 0 until components) {
                values[element * components + part] = readComponent(buffer, offset + part * size, componentType)
            }
        }
        return values
    }

    private fun readComponent(buffer: ByteBuffer, offset: Int, componentType: Int): Int = when (componentType) {
        BYTE -> buffer.get(offset).toInt()
        UNSIGNED_BYTE -> buffer.get(offset).toInt() and 0xff
        SHORT -> buffer.getShort(offset).toInt()
        UNSIGNED_SHORT -> buffer.getShort(offset).toInt() and 0xffff
        UNSIGNED_INT -> buffer.getInt(offset)
        // Rounded rather than truncated: our own files hold exact integers, and one that has been
        // through another tool's float maths should not lose a unit to 82.99999.
        FLOAT -> Math.round(buffer.getFloat(offset))
        else -> throw IllegalArgumentException("Unknown glTF component type $componentType.")
    }

    private fun componentSize(componentType: Int): Int = when (componentType) {
        BYTE, UNSIGNED_BYTE -> 1
        SHORT, UNSIGNED_SHORT -> 2
        UNSIGNED_INT, FLOAT -> 4
        else -> throw IllegalArgumentException("Unknown glTF component type $componentType.")
    }

    private fun tuples(element: JsonElement?): List<IntArray> {
        if (element == null || element is JsonNull) {
            return emptyList()
        }
        return element.jsonArray.map { item ->
            val parts = item.jsonArray
            IntArray(parts.size) { parts[it].jsonPrimitive.int }
        }
    }

    private fun column(tuples: List<IntArray>, part: Int): IntArray =
        IntArray(tuples.size) { if (part < tuples[it].size) tuples[it][part] else 0 }

    private fun shortColumn(tuples: List<IntArray>, part: Int): ShortArray =
        ShortArray(tuples.size) { if (part < tuples[it].size) tuples[it][part].toShort() else 0 }

    private fun ints(element: JsonElement?): IntArray? {
        val array = numbers(element) ?: return null
        return IntArray(array.size) { array[it].jsonPrimitive.int }
    }

    private fun shorts(element: JsonElement?): ShortArray? {
        val array = numbers(element) ?: return null
        return ShortArray(array.size) { array[it].jsonPrimitive.int.toShort() }
    }

    private fun bytes(element: JsonElement?): ByteArray? {
        val array = numbers(element) ?: return null
        return ByteArray(array.size) { array[it].jsonPrimitive.int.toByte() }
    }

    private fun numbers(element: JsonElement?): JsonArray? {
        if (element == null || element is JsonNull) {
            return null
        }
        return element.jsonArray
    }

    private val digits = "0123456789abcdef".toCharArray()

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val byte = bytes[index].toInt() and 0xff
            out[index * 2] = digits[byte shr 4]
            out[index * 2 + 1] = digits[byte and 0xf]
        }
        return String(out)
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "'$text' is not a whole number of bytes." }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Fixed, because a generator string that moved would rewrite all 73,417 files. */
    private const val GENERATOR = "darkan cache-source"
}
