package org.darkan.tools.rs3port

import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.decoder.ModelDecoder

/**
 * Negating y mirrors the model, so every face comes out wound the other way round and its two
 * trailing indices are swapped on the way through: the shipped 727 urns are the RS3 faces reversed
 * on every face of every model checked, and without the swap the client culls the front of the urn
 * and draws its inside.
 *
 * The texture projection is the one thing RS3 does not hold in a 727 form: it ships explicit UVs
 * per vertex, and 727 projects from a P/M/N triangle instead. The UVs of the shipped urns are an
 * atlas mapping and solving them back gives a different triangle per face, so the projection is
 * taken from the learned template for the material instead - which is exact, because the shipped
 * 727 urns all use the same block per material and role.
 */
object ModelConverter {

    private const val VERSION = 15

    private const val UNIFORM_PRIORITY_DEFAULT = 4

    fun convert(rs3: Rs3Model, id: Int, worn: Boolean, crosswalk: Crosswalk): ModelDefinition {
        val skins = rs3.vertexSkins
        val welded = HashMap<Long, Int>(rs3.vertexCount * 2)
        val order = ArrayList<Int>(rs3.vertexCount)
        val index = IntArray(rs3.vertexCount)
        for (vertex in 0 until rs3.vertexCount) {
            val key = weld(rs3.vertexX(vertex), rs3.vertexY(vertex), rs3.vertexZ(vertex), skins?.get(vertex) ?: 0)
            index[vertex] = welded.getOrPut(key) {
                order.add(vertex)
                order.size - 1
            }
        }

        val faces = rs3.faceCount
        val faceA = IntArray(faces)
        val faceB = IntArray(faces)
        val faceC = IntArray(faces)
        val colours = ShortArray(faces)
        val priorities = ByteArray(faces)
        val materials = ShortArray(faces)
        val faceSkins = IntArray(faces)
        var face = 0
        for (submesh in rs3.submeshes) {
            val material = crosswalk.material(submesh.material).toShort()
            for (corner in submesh.indices.indices step 3) {
                val a = submesh.indices[corner]
                faceA[face] = index[a]
                faceB[face] = index[submesh.indices[corner + 2]]
                faceC[face] = index[submesh.indices[corner + 1]]
                colours[face] = (rs3.colours?.get(a) ?: 0).toShort()
                priorities[face] = submesh.priority.toByte()
                materials[face] = material
                faceSkins[face] = rs3.faceSkins?.get(a) ?: 0
                face++
            }
        }

        val pointers = pointers(materials, order.size, worn, crosswalk)
        val texturePointers = ByteArray(faces) { pointers.forMaterial[materials[it].toInt()]?.toByte() ?: -1 }
        val textured = materials.any { it >= 0 }

        val corners = pointers.corners
        val vertices = order.size + corners.size / 3
        val vertexX = IntArray(vertices)
        val vertexY = IntArray(vertices)
        val vertexZ = IntArray(vertices)
        val vertexSkins = IntArray(vertices)
        for (vertex in order.indices) {
            val source = order[vertex]
            vertexX[vertex] = rs3.vertexX(source)
            vertexY[vertex] = rs3.vertexY(source)
            vertexZ[vertex] = rs3.vertexZ(source)
            vertexSkins[vertex] = skins?.get(source) ?: 0
        }
        for (corner in corners.indices step 3) {
            val vertex = order.size + corner / 3
            vertexX[vertex] = corners[corner]
            vertexY[vertex] = corners[corner + 1]
            vertexZ[vertex] = corners[corner + 2]
        }

        val uniform = priorities.all { it == priorities.firstOrNull() }
        val modelPriority = when {
            !uniform -> ModelDecoder.PER_FACE_PRIORITY
            priorities.isEmpty() -> 0
            priorities[0].toInt() == 0 -> UNIFORM_PRIORITY_DEFAULT
            else -> priorities[0].toInt()
        }

        val count = pointers.mappings.size
        return ModelDefinition(
            id = id,
            version = VERSION,
            vertexCount = vertices,
            faceCount = faces,
            vertexX = vertexX,
            vertexY = vertexY,
            vertexZ = vertexZ,
            faceA = faceA,
            faceB = faceB,
            faceC = faceC,
            faceColors = colours,
            faceAlphas = null,
            faceRenderTypes = null,
            facePriorities = if (uniform) null else priorities,
            faceTextures = if (textured) materials else null,
            faceTexturePointers = if (textured) texturePointers else null,
            textureTriangleCount = count,
            texturesMapping = if (count == 0) null else pointers.mappings,
            textureFaceX = if (count == 0) null else pointers.triangleX,
            textureFaceY = if (count == 0) null else pointers.triangleY,
            textureFaceZ = if (count == 0) null else pointers.triangleZ,
            texturesScaleX = if (pointers.cylindrical == 0) null else pointers.scaleX,
            texturesScaleY = if (pointers.cylindrical == 0) null else pointers.scaleY,
            texturesScaleZ = if (pointers.cylindrical == 0) null else pointers.scaleZ,
            texturesRotation = if (pointers.cylindrical == 0) null else pointers.rotations,
            texturesDirection = if (pointers.cylindrical == 0) null else pointers.directions,
            texturesSpeed = if (pointers.cylindrical == 0) null else pointers.speeds,
            texturesUtrans = null,
            texturesVtrans = null,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null,
            newFormat = true,
            footerFlags = ModelDecoder.FLAG_VERSION,
            modelPriority = modelPriority,
            alphaFlag = 0,
            faceSkinFlag = if (rs3.faceSkins == null) 0 else 1,
            textureFlag = if (textured) 1 else 0,
            vertexSkinFlag = if (skins == null) 0 else 1,
            faceSkins = if (rs3.faceSkins == null) null else faceSkins,
            vertexSkins = if (skins == null) null else vertexSkins
        )
    }

    private fun weld(x: Int, y: Int, z: Int, skin: Int): Long {
        val packed = ((x + 0x8000).toLong() shl 48) or ((y + 0x8000).toLong() shl 32)
        return packed or ((z + 0x8000).toLong() shl 16) or (skin.toLong() and 0xffff)
    }

    private class Pointers(
        val forMaterial: Map<Int, Int>,
        val mappings: ByteArray,
        val triangleX: ShortArray,
        val triangleY: ShortArray,
        val triangleZ: ShortArray,
        val scaleX: IntArray,
        val scaleY: IntArray,
        val scaleZ: IntArray,
        val rotations: ByteArray,
        val directions: ByteArray,
        val speeds: IntArray,
        val corners: IntArray,
        val cylindrical: Int
    )

    private fun pointers(materials: ShortArray, vertices: Int, worn: Boolean, crosswalk: Crosswalk): Pointers {
        val order = LinkedHashSet<Int>()
        for (material in materials) {
            if (material >= 0) {
                order.add(material.toInt())
            }
        }
        val templates = order.associateWith { crosswalk.projection(worn, it) }
        val sorted = order.sortedBy { if (templates.getValue(it).mapping == 0) 1 else 0 }
        val forMaterial = HashMap<Int, Int>()
        val mappings = ByteArray(sorted.size)
        val triangleX = ShortArray(sorted.size)
        val triangleY = ShortArray(sorted.size)
        val triangleZ = ShortArray(sorted.size)
        val cylindrical = sorted.count { templates.getValue(it).mapping != 0 }
        val scaleX = IntArray(cylindrical)
        val scaleY = IntArray(cylindrical)
        val scaleZ = IntArray(cylindrical)
        val rotations = ByteArray(cylindrical)
        val directions = ByteArray(cylindrical)
        val speeds = IntArray(cylindrical)
        val corners = ArrayList<Int>()
        for ((pointer, material) in sorted.withIndex()) {
            forMaterial[material] = pointer
            val template = templates.getValue(material)
            mappings[pointer] = template.mapping.toByte()
            if (template.mapping == 0) {
                val corner = vertices + corners.size / 3
                triangleX[pointer] = corner.toShort()
                triangleY[pointer] = (corner + 1).toShort()
                triangleZ[pointer] = (corner + 2).toShort()
                template.corners.forEach { corners.add(it) }
            } else {
                triangleX[pointer] = template.triangle[0].toShort()
                triangleY[pointer] = template.triangle[1].toShort()
                triangleZ[pointer] = template.triangle[2].toShort()
                scaleX[pointer] = template.scale[0]
                scaleY[pointer] = template.scale[1]
                scaleZ[pointer] = template.scale[2]
                rotations[pointer] = template.rotation.toByte()
                directions[pointer] = template.direction.toByte()
                speeds[pointer] = template.speed
            }
        }
        return Pointers(
            forMaterial, mappings, triangleX, triangleY, triangleZ,
            scaleX, scaleY, scaleZ, rotations, directions, speeds,
            corners.toIntArray(), cylindrical
        )
    }
}
