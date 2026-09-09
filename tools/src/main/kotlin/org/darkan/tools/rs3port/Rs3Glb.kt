package org.darkan.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.source.codec.Gltf
import java.nio.file.Files
import java.nio.file.Path

class Rs3Submesh(val flags: Int, val priority: Int, val material: Int, val indices: IntArray)

/**
 * A revision 949 `models_rt7/<id>.glb`, read with nothing but the glTF container.
 *
 * RS3 keeps its own per model data on the first node as `extras.rs`, like the 727 source tree,
 * but every entry there is an *accessor index* rather than the values, and the faces are a list
 * of submeshes rather than one face order. What the streams hold, measured against the models
 * that exist in both trees:
 *
 * - `positions` are the RS integers with **y negated** - the whole of the coordinate difference.
 *   Vertices are split per normal and per UV, so several carry the same position.
 * - `streamB` is the 727 HSL16 face colour, on every vertex of the face.
 * - `streamA` is the 727 vertex skin label and `streamC` the face skin label; both are absent
 *   unless the model is worn. `bones` and `weights` are the RT7 skeleton and have no 727 form.
 * - `submeshes` are `[flags, priority, material, unused, indicesAccessor]`, where `priority` is
 *   the 727 face priority and `material` an RS3 material id that needs the crosswalk.
 */
class Rs3Model(val id: Int, private val root: JsonObject, private val binary: ByteArray) {

    private val rs: JsonObject = root["nodes"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("extras")?.jsonObject?.get("rs")?.jsonObject
        ?: throw IllegalArgumentException("RS3 model $id carries no rs extras.")

    val positions: IntArray = run {
        val raw = stream("positions") ?: IntArray(0)
        IntArray(raw.size) { if (it % 3 == 1) -raw[it] else raw[it] }
    }

    val vertexCount: Int = positions.size / 3

    val colours: IntArray? = stream("streamB")

    val vertexSkins: IntArray? = stream("streamA")

    val faceSkins: IntArray? = stream("streamC")

    val textureCoordinates: IntArray? = stream("textures")

    val submeshes: List<Rs3Submesh> = rs["submeshes"]?.jsonArray.orEmpty().map { entry ->
        val parts = entry.jsonArray
        Rs3Submesh(
            flags = parts[0].jsonPrimitive.int,
            priority = parts[1].jsonPrimitive.int,
            material = parts[2].jsonPrimitive.int,
            indices = accessor(parts[4].jsonPrimitive.int)
        )
    }

    val faceCount: Int = submeshes.sumOf { it.indices.size } / 3

    fun vertexX(vertex: Int): Int = positions[vertex * 3]

    fun vertexY(vertex: Int): Int = positions[vertex * 3 + 1]

    fun vertexZ(vertex: Int): Int = positions[vertex * 3 + 2]

    private fun stream(name: String): IntArray? {
        val index = rs[name]?.jsonPrimitive?.int ?: return null
        return accessor(index)
    }

    private fun accessor(index: Int): IntArray {
        val type = root["accessors"]!!.jsonArray[index].jsonObject["type"]!!.jsonPrimitive.content
        return Gltf.accessor(root, binary, index, components(type))
    }

    private fun components(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        else -> throw IllegalArgumentException("RS3 model $id has an accessor of type $type.")
    }

    companion object {

        fun path(tree: Path, id: Int): Path = tree.resolve("models_rt7").resolve("$id.glb")

        fun exists(tree: Path, id: Int): Boolean = Files.exists(path(tree, id))

        fun read(tree: Path, id: Int): Rs3Model {
            val bytes = Files.readAllBytes(path(tree, id))
            val chunks = Gltf.chunks(bytes)
            return Rs3Model(id, Gltf.root(chunks.first), chunks.second)
        }
    }
}
