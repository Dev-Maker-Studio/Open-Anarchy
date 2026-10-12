package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.ChunkMesh
import org.darkan.tools.engine.render.ShaderProgram

class ChunkedTerrain(val regionX: Int, val regionY: Int, val plane: Int) {

    companion object {
        const val CHUNK_SIZE = 8
        const val CHUNKS_PER_AXIS = 8
        const val MAX_VERTICES_PER_CHUNK = 16384
    }

    private val chunks = Array(CHUNKS_PER_AXIS) { Array(CHUNKS_PER_AXIS) { ChunkData() } }

    class ChunkData {
        var colorMesh: ChunkMesh? = null
        val texturedMeshes = mutableMapOf<Int, ChunkMesh>()
        val alphaMeshes = mutableMapOf<Int, ChunkMesh>()

        fun cleanup() {
            colorMesh?.cleanup()
            colorMesh = null
            texturedMeshes.values.forEach { it.cleanup() }
            texturedMeshes.clear()
            alphaMeshes.values.forEach { it.cleanup() }
            alphaMeshes.clear()
        }
    }

    fun buildAll(
        region: TerrainLoader.RegionData,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: TerrainLoader.NeighborData? = null
    ) {
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                rebuildChunk(cx, cy, region, blendedColors, normalsX, normalsY, normalsZ, neighbors)
            }
        }
    }

    fun rebuildChunks(
        dirtyChunks: Set<ChunkCoord>,
        region: TerrainLoader.RegionData,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: TerrainLoader.NeighborData? = null
    ) {
        for (coord in dirtyChunks) {
            rebuildChunk(coord.chunkX, coord.chunkY, region, blendedColors, normalsX, normalsY, normalsZ, neighbors)
        }
    }

    private fun rebuildChunk(
        cx: Int, cy: Int,
        region: TerrainLoader.RegionData,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: TerrainLoader.NeighborData? = null
    ) {
        val chunk = chunks[cx][cy]

        val allVertices = mutableListOf<TerrainLoader.ShapedVertex>()

        val startX = cx * CHUNK_SIZE
        val startY = cy * CHUNK_SIZE
        for (dx in 0 until CHUNK_SIZE) {
            for (dy in 0 until CHUNK_SIZE) {
                val tileX = startX + dx
                val tileY = startY + dy
                val vertices = TerrainLoader.generateTileVertices(
                    region, plane, tileX, tileY, blendedColors, normalsX, normalsY, normalsZ,
                    neighbors
                )
                allVertices.addAll(vertices)
            }
        }

        val opaqueGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()
        val alphaGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()

        var i = 0
        while (i + 2 < allVertices.size) {
            val v0 = allVertices[i]
            val v1 = allVertices[i + 1]
            val v2 = allVertices[i + 2]

            val needsAlpha = v0.alpha < 1f || v1.alpha < 1f || v2.alpha < 1f

            if (needsAlpha && v0.textureId >= 0) {
                val group = alphaGroups.getOrPut(v0.textureId) { mutableListOf() }
                group.add(v0); group.add(v1); group.add(v2)
            } else {
                val group = opaqueGroups.getOrPut(v0.textureId) { mutableListOf() }
                group.add(v0); group.add(v1); group.add(v2)
            }
            i += 3
        }
        while (i < allVertices.size) {
            opaqueGroups.getOrPut(allVertices[i].textureId) { mutableListOf() }.add(allVertices[i])
            i++
        }

        val colorVerts = opaqueGroups[-1]
        uploadColorMesh(chunk, colorVerts)

        val newTexturedIds = mutableSetOf<Int>()
        for ((texId, verts) in opaqueGroups) {
            if (texId == -1) continue
            newTexturedIds.add(texId)
            uploadTexturedMesh(chunk, texId, verts)
        }
        val staleTexturedIds = chunk.texturedMeshes.keys - newTexturedIds
        for (id in staleTexturedIds) {
            chunk.texturedMeshes.remove(id)?.cleanup()
        }

        val newAlphaIds = mutableSetOf<Int>()
        for ((texId, verts) in alphaGroups) {
            newAlphaIds.add(texId)
            uploadAlphaMesh(chunk, texId, verts)
        }
        val staleAlphaIds = chunk.alphaMeshes.keys - newAlphaIds
        for (id in staleAlphaIds) {
            chunk.alphaMeshes.remove(id)?.cleanup()
        }
    }


    private fun uploadColorMesh(chunk: ChunkData, vertices: List<TerrainLoader.ShapedVertex>?) {
        if (vertices == null || vertices.isEmpty()) {
            chunk.colorMesh?.upload(FloatArray(0), FloatArray(0), null, FloatArray(0), null, 0)
            return
        }

        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val normals = FloatArray(count * 3)

        for (idx in vertices.indices) {
            val v = vertices[idx]
            positions[idx * 3]     = v.x
            positions[idx * 3 + 1] = v.y
            positions[idx * 3 + 2] = v.z
            colors[idx * 3]     = v.r
            colors[idx * 3 + 1] = v.g
            colors[idx * 3 + 2] = v.b
            normals[idx * 3]     = v.nx
            normals[idx * 3 + 1] = v.ny
            normals[idx * 3 + 2] = v.nz
        }

        if (chunk.colorMesh == null) {
            chunk.colorMesh = ChunkMesh.createColor(MAX_VERTICES_PER_CHUNK)
        }
        chunk.colorMesh!!.upload(positions, colors, null, normals, null, count)
    }

    private fun uploadTexturedMesh(chunk: ChunkData, textureId: Int, vertices: List<TerrainLoader.ShapedVertex>) {
        if (vertices.isEmpty()) {
            chunk.texturedMeshes[textureId]?.upload(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), null, 0
            )
            return
        }

        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texcoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)

        for (idx in vertices.indices) {
            val v = vertices[idx]
            positions[idx * 3]     = v.x
            positions[idx * 3 + 1] = v.y
            positions[idx * 3 + 2] = v.z
            colors[idx * 3]     = v.r
            colors[idx * 3 + 1] = v.g
            colors[idx * 3 + 2] = v.b
            texcoords[idx * 2]     = v.u
            texcoords[idx * 2 + 1] = v.v
            normals[idx * 3]     = v.nx
            normals[idx * 3 + 1] = v.ny
            normals[idx * 3 + 2] = v.nz
        }

        if (chunk.texturedMeshes[textureId] == null) {
            chunk.texturedMeshes[textureId] = ChunkMesh.createTextured(MAX_VERTICES_PER_CHUNK)
        }
        chunk.texturedMeshes[textureId]!!.upload(positions, colors, texcoords, normals, null, count)
    }

    private fun uploadAlphaMesh(chunk: ChunkData, textureId: Int, vertices: List<TerrainLoader.ShapedVertex>) {
        if (vertices.isEmpty()) {
            chunk.alphaMeshes[textureId]?.upload(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), 0
            )
            return
        }

        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texcoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)
        val alphas = FloatArray(count)

        for (idx in vertices.indices) {
            val v = vertices[idx]
            positions[idx * 3]     = v.x
            positions[idx * 3 + 1] = v.y
            positions[idx * 3 + 2] = v.z
            colors[idx * 3]     = v.r
            colors[idx * 3 + 1] = v.g
            colors[idx * 3 + 2] = v.b
            texcoords[idx * 2]     = v.u
            texcoords[idx * 2 + 1] = v.v
            normals[idx * 3]     = v.nx
            normals[idx * 3 + 1] = v.ny
            normals[idx * 3 + 2] = v.nz
            alphas[idx] = v.alpha
        }

        if (chunk.alphaMeshes[textureId] == null) {
            chunk.alphaMeshes[textureId] = ChunkMesh.createAlpha(MAX_VERTICES_PER_CHUNK)
        }
        chunk.alphaMeshes[textureId]!!.upload(positions, colors, texcoords, normals, alphas, count)
    }


    fun renderColor(shader: ShaderProgram): TerrainRenderStats {
        var drawCalls = 0
        var triangles = 0
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                val mesh = chunks[cx][cy].colorMesh ?: continue
                if (mesh.isEmpty()) continue
                mesh.render()
                drawCalls++
                triangles += mesh.vertexCount / 3
            }
        }
        return TerrainRenderStats(drawCalls, triangles)
    }

    fun renderTextured(shader: ShaderProgram, bindTexture: (Int) -> Unit): TerrainRenderStats {
        var drawCalls = 0
        var triangles = 0
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                for ((texId, mesh) in chunks[cx][cy].texturedMeshes) {
                    if (!mesh.isEmpty()) {
                        bindTexture(texId)
                        mesh.render()
                        drawCalls++
                        triangles += mesh.vertexCount / 3
                    }
                }
            }
        }
        return TerrainRenderStats(drawCalls, triangles)
    }

    fun renderAlpha(shader: ShaderProgram, bindTexture: (Int) -> Unit): TerrainRenderStats {
        var drawCalls = 0
        var triangles = 0
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                for ((texId, mesh) in chunks[cx][cy].alphaMeshes) {
                    if (!mesh.isEmpty()) {
                        bindTexture(texId)
                        mesh.render()
                        drawCalls++
                        triangles += mesh.vertexCount / 3
                    }
                }
            }
        }
        return TerrainRenderStats(drawCalls, triangles)
    }

    fun cleanup() {
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                chunks[cx][cy].cleanup()
            }
        }
    }
}
