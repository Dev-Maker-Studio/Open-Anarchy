package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.ShaderProgram
import org.joml.Matrix4f
import world.gregs.voidps.cache.Cache

class TerrainEditManager(
    val regionX: Int,
    val regionY: Int,
    var plane: Int,
    private val cache: Cache
) {
    lateinit var mutableData: MutableRegionData
        private set

    var tileSnapshot: Array<Array<Array<TerrainLoader.TileData>>> = emptyArray()
        private set

    private lateinit var chunkedTerrain: ChunkedTerrain

    private var neighbors: TerrainLoader.NeighborData? = null

    private lateinit var blendedColors: Array<IntArray>
    private lateinit var normalsX: Array<FloatArray>
    private lateinit var normalsY: Array<FloatArray>
    private lateinit var normalsZ: Array<FloatArray>

    private var mergedColorMesh: Mesh? = null
    private var mergedTexturedBatches: List<TerrainLoader.TerrainBatch> = emptyList()
    private var mergedAlphaBatches: List<TerrainLoader.AlphaTerrainBatch> = emptyList()

    private var useMergedMeshes: Boolean = false

    fun init() {
        TerrainLoader.loadDefinitions(cache)

        val regionData = TerrainLoader.loadRegion(cache, regionX, regionY)
            ?: error("Failed to load region ($regionX, $regionY)")

        neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)

        mutableData = MutableRegionData(regionData)
        tileSnapshot = mutableData.toTileArray()
        chunkedTerrain = ChunkedTerrain(regionX, regionY, plane)

        recomputeBlendingAndNormals()

        chunkedTerrain.buildAll(mutableData.toRegionData(), blendedColors, normalsX, normalsY, normalsZ, neighbors)

        buildMergedMeshes()
    }

    fun changePlane(newPlane: Int) {
        plane = newPlane
        chunkedTerrain.cleanup()
        chunkedTerrain = ChunkedTerrain(regionX, regionY, plane)
        recomputeBlendingAndNormals()
        chunkedTerrain.buildAll(mutableData.toRegionData(), blendedColors, normalsX, normalsY, normalsZ, neighbors)
        mutableData.clearDirty()

        buildMergedMeshes()
    }

    fun processEdits(): EditResult {
        if (!mutableData.hasDirty()) return EditResult.NONE

        useMergedMeshes = false

        val regionSnapshot = mutableData.toRegionData()
        tileSnapshot = regionSnapshot.tiles

        if (mutableData.blendingDirty) {
            blendedColors = TerrainLoader.computeBlendedUnderlayColors(regionSnapshot, plane, neighbors)
        }

        if (mutableData.heightsDirty) {
            val (nx, ny, nz) = TerrainLoader.computeVertexNormals(regionSnapshot.tiles, plane, neighbors)
            normalsX = nx
            normalsY = ny
            normalsZ = nz
        }

        val dirtyChunks = mutableData.getDirtyChunks()
        chunkedTerrain.rebuildChunks(dirtyChunks, regionSnapshot, blendedColors, normalsX, normalsY, normalsZ, neighbors)

        val result = EditResult(dirtyChunks, heightsChanged = mutableData.heightsDirty)
        mutableData.clearDirty()

        buildMergedMeshes()

        return result
    }

    private fun buildMergedMeshes() {
        cleanupMergedMeshes()

        val regionSnapshot = mutableData.toRegionData()

        val textureGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()

        for (x in 0 until 64) {
            for (y in 0 until 64) {
                val vertices = TerrainLoader.generateTileVertices(
                    regionSnapshot, plane, x, y,
                    blendedColors, normalsX, normalsY, normalsZ,
                    neighbors
                )
                for (vertex in vertices) {
                    textureGroups.getOrPut(vertex.textureId) { mutableListOf() }.add(vertex)
                }
            }
        }

        val opaqueGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()
        val alphaGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()

        for ((texId, verts) in textureGroups) {
            var i = 0
            while (i + 2 < verts.size) {
                val v0 = verts[i]
                val v1 = verts[i + 1]
                val v2 = verts[i + 2]

                val needsAlpha = v0.alpha < 1f || v1.alpha < 1f || v2.alpha < 1f

                if (needsAlpha && v0.textureId >= 0) {
                    val group = alphaGroups.getOrPut(v0.textureId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                } else {
                    val group = opaqueGroups.getOrPut(texId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                }
                i += 3
            }
            while (i < verts.size) {
                opaqueGroups.getOrPut(texId) { mutableListOf() }.add(verts[i])
                i++
            }
        }

        mergedColorMesh = opaqueGroups[-1]?.let { vertices ->
            if (vertices.isEmpty()) return@let null
            buildColorMesh(vertices)
        }

        val texturedList = mutableListOf<TerrainLoader.TerrainBatch>()
        for ((texId, vertices) in opaqueGroups) {
            if (texId == -1 || vertices.isEmpty()) continue
            texturedList.add(TerrainLoader.TerrainBatch(buildTexturedMesh(vertices), texId))
        }
        mergedTexturedBatches = texturedList

        val alphaList = mutableListOf<TerrainLoader.AlphaTerrainBatch>()
        for ((texId, vertices) in alphaGroups) {
            if (vertices.isEmpty()) continue
            alphaList.add(TerrainLoader.AlphaTerrainBatch(buildAlphaMesh(vertices), texId))
        }
        mergedAlphaBatches = alphaList

        useMergedMeshes = true
    }

    private fun buildColorMesh(vertices: List<TerrainLoader.ShapedVertex>): Mesh {
        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        val indices = IntArray(count)

        for (i in vertices.indices) {
            val v = vertices[i]
            positions[i * 3]     = v.x
            positions[i * 3 + 1] = v.y
            positions[i * 3 + 2] = v.z
            colors[i * 3]     = v.r
            colors[i * 3 + 1] = v.g
            colors[i * 3 + 2] = v.b
            normals[i * 3]     = v.nx
            normals[i * 3 + 1] = v.ny
            normals[i * 3 + 2] = v.nz
            indices[i] = i
        }

        return Mesh(positions, colors, null, normals, indices, colorComponents = 3)
    }

    private fun buildTexturedMesh(vertices: List<TerrainLoader.ShapedVertex>): Mesh {
        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texCoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)
        val indices = IntArray(count)

        for (i in vertices.indices) {
            val v = vertices[i]
            positions[i * 3]     = v.x
            positions[i * 3 + 1] = v.y
            positions[i * 3 + 2] = v.z
            colors[i * 3]     = v.r
            colors[i * 3 + 1] = v.g
            colors[i * 3 + 2] = v.b
            texCoords[i * 2]     = v.u
            texCoords[i * 2 + 1] = v.v
            normals[i * 3]     = v.nx
            normals[i * 3 + 1] = v.ny
            normals[i * 3 + 2] = v.nz
            indices[i] = i
        }

        return Mesh(positions, colors, texCoords, normals, indices, colorComponents = 3)
    }

    private fun buildAlphaMesh(vertices: List<TerrainLoader.ShapedVertex>): Mesh {
        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texCoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)
        val alphas = FloatArray(count)
        val indices = IntArray(count)

        for (i in vertices.indices) {
            val v = vertices[i]
            positions[i * 3]     = v.x
            positions[i * 3 + 1] = v.y
            positions[i * 3 + 2] = v.z
            colors[i * 3]     = v.r
            colors[i * 3 + 1] = v.g
            colors[i * 3 + 2] = v.b
            texCoords[i * 2]     = v.u
            texCoords[i * 2 + 1] = v.v
            normals[i * 3]     = v.nx
            normals[i * 3 + 1] = v.ny
            normals[i * 3 + 2] = v.nz
            alphas[i] = v.alpha
            indices[i] = i
        }

        return Mesh.createWithAlpha(positions, colors, texCoords, normals, alphas, indices)
    }

    private fun recomputeBlendingAndNormals() {
        val regionSnapshot = mutableData.toRegionData()
        blendedColors = TerrainLoader.computeBlendedUnderlayColors(regionSnapshot, plane, neighbors)
        val (nx, ny, nz) = TerrainLoader.computeVertexNormals(mutableData.toTileArray(), plane, neighbors)
        normalsX = nx
        normalsY = ny
        normalsZ = nz
    }

    fun renderColor(shader: ShaderProgram, modelMatrix: Matrix4f): TerrainRenderStats {
        shader.setUniform("model", modelMatrix)
        if (!useMergedMeshes) return chunkedTerrain.renderColor(shader)
        val mesh = mergedColorMesh ?: return TerrainRenderStats.NONE
        mesh.render()
        return TerrainRenderStats(1, mesh.vertexCount / 3)
    }

    fun renderTextured(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit): TerrainRenderStats {
        shader.setUniform("model", modelMatrix)
        if (!useMergedMeshes) return chunkedTerrain.renderTextured(shader, bindTexture)
        var triangles = 0
        for (batch in mergedTexturedBatches) {
            bindTexture(batch.textureId)
            batch.mesh.render()
            triangles += batch.mesh.vertexCount / 3
        }
        return TerrainRenderStats(mergedTexturedBatches.size, triangles)
    }

    fun renderAlpha(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit): TerrainRenderStats {
        shader.setUniform("model", modelMatrix)
        if (!useMergedMeshes) return chunkedTerrain.renderAlpha(shader, bindTexture)
        var triangles = 0
        for (batch in mergedAlphaBatches) {
            bindTexture(batch.textureId)
            batch.mesh.render()
            triangles += batch.mesh.vertexCount / 3
        }
        return TerrainRenderStats(mergedAlphaBatches.size, triangles)
    }

    fun cleanup() {
        chunkedTerrain.cleanup()
        cleanupMergedMeshes()
    }

    private fun cleanupMergedMeshes() {
        mergedColorMesh?.cleanup()
        mergedColorMesh = null
        for (batch in mergedTexturedBatches) batch.mesh.cleanup()
        mergedTexturedBatches = emptyList()
        for (batch in mergedAlphaBatches) batch.mesh.cleanup()
        mergedAlphaBatches = emptyList()
        useMergedMeshes = false
    }
}

data class TerrainRenderStats(
    val drawCalls: Int,
    val triangles: Int
) {
    companion object {
        val NONE = TerrainRenderStats(0, 0)
    }
}

data class EditResult(
    val rebuiltChunks: Set<ChunkCoord>,
    val heightsChanged: Boolean = false
) {
    val hasChanges: Boolean get() = rebuiltChunks.isNotEmpty()

    companion object {
        val NONE = EditResult(emptySet())
    }
}
