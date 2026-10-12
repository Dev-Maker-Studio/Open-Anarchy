package org.darkan.tools.engine.terrain

import org.darkan.tools.engine.render.BatchKey
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.render.StaticBatcher
import org.darkan.tools.engine.render.TransformedGeometry
import org.darkan.tools.engine.spatial.AABB
import org.darkan.tools.engine.spatial.Frustum
import org.darkan.tools.engine.spatial.Rect
import org.joml.Matrix4f
import org.joml.Vector3f

class TerrainChunk(
    val chunkX: Int,
    val chunkZ: Int,
    val bounds: AABB,
    val rect: Rect
) {
    private val meshes = mutableMapOf<BatchKey, Mesh>()

    private val lodMeshes = mutableMapOf<Int, Map<BatchKey, Mesh>>()

    var visible = true
    var currentLod = 0

    fun setMeshes(lod: Int, meshMap: Map<BatchKey, Mesh>) {
        lodMeshes[lod] = meshMap
        if (lod == 0) {
            meshes.clear()
            meshes.putAll(meshMap)
        }
    }

    fun getMeshes(): Map<BatchKey, Mesh> {
        return lodMeshes[currentLod] ?: meshes
    }

    fun renderUntextured(shader: ShaderProgram, modelMatrix: Matrix4f) {
        if (!visible) return

        val chunkMeshes = getMeshes()
        for ((key, mesh) in chunkMeshes) {
            if (key.textureId != -1) continue
            shader.setUniform("model", modelMatrix)
            mesh.render()
        }
    }

    fun renderTextured(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit) {
        if (!visible) return

        val chunkMeshes = getMeshes()
        for ((key, mesh) in chunkMeshes) {
            if (key.textureId == -1) continue
            shader.setUniform("model", modelMatrix)
            bindTexture(key.textureId)
            mesh.render()
        }
    }

    fun cleanup() {
        for (meshMap in lodMeshes.values) {
            for (mesh in meshMap.values) {
                mesh.cleanup()
            }
        }
        lodMeshes.clear()
        meshes.clear()
    }
}

class ChunkedTerrain(
    private val chunkSize: Int = 16,
    private val tileSize: Float = 1f
) {
    private val chunks = mutableMapOf<Long, TerrainChunk>()
    private val visibleChunks = mutableListOf<TerrainChunk>()

    private val identityMatrix = Matrix4f()

    private var totalChunks = 0
    private var visibleChunkCount = 0
    private var lastCullTimeNanos = 0L

    private fun chunkKey(chunkX: Int, chunkZ: Int): Long {
        return (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
    }

    fun getChunk(chunkX: Int, chunkZ: Int): TerrainChunk? {
        return chunks[chunkKey(chunkX, chunkZ)]
    }

    fun getOrCreateChunk(chunkX: Int, chunkZ: Int): TerrainChunk {
        val key = chunkKey(chunkX, chunkZ)
        return chunks.getOrPut(key) {
            val worldX = chunkX * chunkSize * tileSize
            val worldZ = chunkZ * chunkSize * tileSize
            val size = chunkSize * tileSize

            val bounds = AABB(
                Vector3f(worldX, -100f, worldZ),
                Vector3f(worldX + size, 500f, worldZ + size)
            )
            val rect = Rect(worldX, worldZ, worldX + size, worldZ + size)

            totalChunks++
            TerrainChunk(chunkX, chunkZ, bounds, rect)
        }
    }

    fun addGeometry(chunkX: Int, chunkZ: Int, key: BatchKey, geometry: TransformedGeometry) {
        val chunk = getOrCreateChunk(chunkX, chunkZ)
    }

    fun setChunkMeshes(chunkX: Int, chunkZ: Int, lod: Int, meshes: Map<BatchKey, Mesh>) {
        val chunk = getOrCreateChunk(chunkX, chunkZ)
        chunk.setMeshes(lod, meshes)
    }

    fun buildChunkFromBatcher(chunkX: Int, chunkZ: Int, batcher: StaticBatcher, lod: Int = 0) {
        val meshes = batcher.buildMeshes()
        setChunkMeshes(chunkX, chunkZ, lod, meshes)
    }

    fun updateVisibility(frustum: Frustum) {
        val startTime = System.nanoTime()

        visibleChunks.clear()

        for (chunk in chunks.values) {
            chunk.visible = frustum.isAABBVisible(chunk.bounds)
            if (chunk.visible) {
                visibleChunks.add(chunk)
            }
        }

        visibleChunkCount = visibleChunks.size
        lastCullTimeNanos = System.nanoTime() - startTime
    }

    fun updateLOD(cameraPos: Vector3f, lodDistances: FloatArray = floatArrayOf(100f, 300f, 600f, 1000f)) {
        for (chunk in visibleChunks) {
            val chunkCenter = chunk.bounds.center
            val dx = cameraPos.x - chunkCenter.x
            val dz = cameraPos.z - chunkCenter.z
            val distSq = dx * dx + dz * dz

            chunk.currentLod = 0
            for ((i, threshold) in lodDistances.withIndex()) {
                if (distSq > threshold * threshold) {
                    chunk.currentLod = i + 1
                }
            }
        }
    }

    fun render(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram,
        bindTexture: (Int) -> Unit
    ) {
        colorShader.bind()
        for (chunk in visibleChunks) {
            chunk.renderUntextured(colorShader, identityMatrix)
        }

        texturedShader.bind()
        for (chunk in visibleChunks) {
            chunk.renderTextured(texturedShader, identityMatrix, bindTexture)
        }
    }

    fun renderUntextured(shader: ShaderProgram) {
        shader.bind()
        for (chunk in visibleChunks) {
            chunk.renderUntextured(shader, identityMatrix)
        }
    }

    fun renderTextured(shader: ShaderProgram, bindTexture: (Int) -> Unit) {
        shader.bind()
        for (chunk in visibleChunks) {
            chunk.renderTextured(shader, identityMatrix, bindTexture)
        }
    }

    fun getStats(): ChunkedTerrainStats {
        return ChunkedTerrainStats(
            totalChunks = totalChunks,
            visibleChunks = visibleChunkCount,
            cullTimeNanos = lastCullTimeNanos,
            cullRatio = if (totalChunks > 0) 1f - (visibleChunkCount.toFloat() / totalChunks) else 0f
        )
    }

    fun getVisibleChunks(): List<TerrainChunk> = visibleChunks.toList()

    fun clear() {
        for (chunk in chunks.values) {
            chunk.cleanup()
        }
        chunks.clear()
        visibleChunks.clear()
        totalChunks = 0
        visibleChunkCount = 0
    }

    fun cleanup() = clear()
}

data class ChunkedTerrainStats(
    val totalChunks: Int,
    val visibleChunks: Int,
    val cullTimeNanos: Long,
    val cullRatio: Float
) {
    val cullTimeMs: Float get() = cullTimeNanos / 1_000_000f
}
