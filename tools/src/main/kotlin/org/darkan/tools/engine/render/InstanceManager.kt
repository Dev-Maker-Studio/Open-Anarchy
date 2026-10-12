package org.darkan.tools.engine.render

import org.joml.Matrix4f

data class MeshData(
    val positions: FloatArray,
    val colors: FloatArray?,
    val textureCoords: FloatArray?,
    val normals: FloatArray?,
    val indices: IntArray,
    val textureId: Int = -1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshData) return false
        return positions.contentEquals(other.positions) &&
               indices.contentEquals(other.indices) &&
               textureId == other.textureId
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + textureId
        return result
    }

    fun withReversedWinding(): MeshData {
        val flipped = indices.copyOf()
        var t = 0
        while (t + 2 < flipped.size) {
            val tmp = flipped[t + 1]
            flipped[t + 1] = flipped[t + 2]
            flipped[t + 2] = tmp
            t += 3
        }
        return copy(indices = flipped)
    }
}

fun List<MeshData>.withReversedWinding(): List<MeshData> = map { it.withReversedWinding() }

data class InstanceBatchKey(
    val modelId: Int,
    val textureId: Int
)

fun interface TexturePixelProvider {
    fun getPixels(textureId: Int): Triple<IntArray, Int, Int>?
}

class InstanceManager(
    private val maxInstancesPerMesh: Int = 10000,
    val objectAtlas: TextureAtlas? = null,
    private val texturePixelProvider: TexturePixelProvider? = null
) {
    private class ResolvedBatch(val packedKey: Long)

    private val meshRegistry = mutableMapOf<Int, List<ResolvedBatch>>()

    private val instancedMeshes = HashMap<Long, InstancedMesh>()

    private var sortedTexturedEntries: List<Map.Entry<Long, InstancedMesh>> = emptyList()
    private var texturedEntriesDirty = true

    private var totalInstancesThisFrame = 0
    private var drawCallsThisFrame = 0

    companion object {
        const val UNTEXTURED_SENTINEL = -1
    }

    private fun packKey(modelId: Int, secondaryKey: Int): Long =
        (modelId.toLong() shl 32) or (secondaryKey.toLong() and 0xFFFFFFFFL)

    private fun unpackSecondaryKey(key: Long): Int = key.toInt()

    private fun unpackModelId(key: Long): Int = (key ushr 32).toInt()

    private fun isUntexturedKey(key: Long): Boolean = unpackSecondaryKey(key) == UNTEXTURED_SENTINEL

    fun registerModel(modelId: Int, meshDataList: List<MeshData>, maxInstances: Int = maxInstancesPerMesh) {
        val atlas = objectAtlas
        val pixelProvider = texturePixelProvider

        if (atlas != null && pixelProvider != null) {
            registerModelWithAtlas(modelId, meshDataList, maxInstances, atlas, pixelProvider)
        } else {
            registerModelDirect(modelId, meshDataList, maxInstances)
        }

        texturedEntriesDirty = true
    }

    private fun registerModelDirect(modelId: Int, meshDataList: List<MeshData>, maxInstances: Int) {
        val resolvedBatches = mutableListOf<ResolvedBatch>()

        for (meshData in meshDataList) {
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

            val key = packKey(modelId, meshData.textureId)
            if (!instancedMeshes.containsKey(key)) {
                instancedMeshes[key] = InstancedMesh(
                    positions = meshData.positions,
                    colors = meshData.colors,
                    textureCoords = meshData.textureCoords,
                    normals = meshData.normals,
                    indices = meshData.indices,
                    maxInstances = maxInstances
                )
            }
            resolvedBatches.add(ResolvedBatch(key))
        }

        meshRegistry[modelId] = resolvedBatches
    }

    private fun registerModelWithAtlas(
        modelId: Int,
        meshDataList: List<MeshData>,
        maxInstances: Int,
        atlas: TextureAtlas,
        pixelProvider: TexturePixelProvider
    ) {
        data class ResolvedMeshData(val key: Long, val meshData: MeshData)

        val resolvedEntries = mutableListOf<ResolvedMeshData>()

        for (meshData in meshDataList) {
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

            if (meshData.textureId == -1) {
                resolvedEntries.add(ResolvedMeshData(
                    packKey(modelId, UNTEXTURED_SENTINEL),
                    meshData
                ))
                continue
            }

            val region = getOrPackTexture(atlas, pixelProvider, meshData.textureId)

            if (region != null) {
                val remappedMeshData = remapUVsToAtlas(meshData, region)
                resolvedEntries.add(ResolvedMeshData(
                    packKey(modelId, region.atlasPageIndex),
                    remappedMeshData
                ))
            } else {
                val fallbackKey = -(meshData.textureId + 2)
                resolvedEntries.add(ResolvedMeshData(
                    packKey(modelId, fallbackKey),
                    meshData
                ))
            }
        }

        val grouped = resolvedEntries.groupBy { it.key }
        val resolvedBatches = mutableListOf<ResolvedBatch>()

        for ((key, entries) in grouped) {
            if (instancedMeshes.containsKey(key)) {
                resolvedBatches.add(ResolvedBatch(key))
                continue
            }

            val mergedMeshData = if (entries.size == 1) {
                entries[0].meshData
            } else {
                mergeMeshDataList(entries.map { it.meshData })
            }

            instancedMeshes[key] = InstancedMesh(
                positions = mergedMeshData.positions,
                colors = mergedMeshData.colors,
                textureCoords = mergedMeshData.textureCoords,
                normals = mergedMeshData.normals,
                indices = mergedMeshData.indices,
                maxInstances = maxInstances
            )
            resolvedBatches.add(ResolvedBatch(key))
        }

        meshRegistry[modelId] = resolvedBatches
    }

    private fun getOrPackTexture(
        atlas: TextureAtlas,
        pixelProvider: TexturePixelProvider,
        textureId: Int
    ): AtlasRegion? {
        atlas.getRegion(textureId)?.let { return it }

        val (pixels, width, height) = pixelProvider.getPixels(textureId) ?: return null

        return atlas.addTexture(textureId, pixels, width, height)
    }

    private fun remapUVsToAtlas(meshData: MeshData, region: AtlasRegion): MeshData {
        val texCoords = meshData.textureCoords ?: return meshData

        val remapped = FloatArray(texCoords.size)
        val uScale = region.u1 - region.u0
        val vScale = region.v1 - region.v0

        var i = 0
        while (i < texCoords.size - 1) {
            val u = texCoords[i]
            val v = texCoords[i + 1]
            remapped[i] = region.u0 + u * uScale
            remapped[i + 1] = region.v0 + v * vScale
            i += 2
        }

        return MeshData(
            positions = meshData.positions,
            colors = meshData.colors,
            textureCoords = remapped,
            normals = meshData.normals,
            indices = meshData.indices,
            textureId = meshData.textureId
        )
    }

    private fun mergeMeshDataList(entries: List<MeshData>): MeshData {
        if (entries.size == 1) return entries[0]

        var totalPositions = 0
        var totalIndices = 0
        var hasColors = false
        var hasTexCoords = false
        var hasNormals = false

        for (md in entries) {
            totalPositions += md.positions.size
            totalIndices += md.indices.size
            if (md.colors != null) hasColors = true
            if (md.textureCoords != null) hasTexCoords = true
            if (md.normals != null) hasNormals = true
        }

        val positions = FloatArray(totalPositions)
        val colors = if (hasColors) FloatArray(totalPositions) else null
        val texCoords = if (hasTexCoords) FloatArray(totalPositions / 3 * 2) else null
        val normals = if (hasNormals) FloatArray(totalPositions) else null
        val indices = IntArray(totalIndices)

        var posOffset = 0
        var colorOffset = 0
        var texOffset = 0
        var normalOffset = 0
        var indexOffset = 0
        var vertexOffset = 0

        for (md in entries) {
            System.arraycopy(md.positions, 0, positions, posOffset, md.positions.size)
            posOffset += md.positions.size

            if (hasColors) {
                if (md.colors != null) {
                    System.arraycopy(md.colors, 0, colors!!, colorOffset, md.colors.size)
                    colorOffset += md.colors.size
                } else {
                    val numFloats = md.positions.size
                    for (j in 0 until numFloats) {
                        colors!![colorOffset + j] = 1f
                    }
                    colorOffset += numFloats
                }
            }

            if (hasTexCoords) {
                val numTexFloats = md.positions.size / 3 * 2
                if (md.textureCoords != null) {
                    System.arraycopy(md.textureCoords, 0, texCoords!!, texOffset, md.textureCoords.size)
                    texOffset += md.textureCoords.size
                } else {
                    for (j in 0 until numTexFloats) {
                        texCoords!![texOffset + j] = 0f
                    }
                    texOffset += numTexFloats
                }
            }

            if (hasNormals) {
                if (md.normals != null) {
                    System.arraycopy(md.normals, 0, normals!!, normalOffset, md.normals.size)
                    normalOffset += md.normals.size
                } else {
                    val numFloats = md.positions.size
                    var j = 0
                    while (j < numFloats) {
                        normals!![normalOffset + j] = 0f
                        normals[normalOffset + j + 1] = 1f
                        normals[normalOffset + j + 2] = 0f
                        j += 3
                    }
                    normalOffset += numFloats
                }
            }

            val numVertices = md.positions.size / 3
            for (j in md.indices.indices) {
                indices[indexOffset + j] = md.indices[j] + vertexOffset
            }
            indexOffset += md.indices.size
            vertexOffset += numVertices
        }

        return MeshData(
            positions = positions,
            colors = colors,
            textureCoords = texCoords,
            normals = normals,
            indices = indices,
            textureId = entries[0].textureId
        )
    }

    fun isModelRegistered(modelId: Int): Boolean = meshRegistry.containsKey(modelId)

    fun prepareFrame() {
        totalInstancesThisFrame = 0
        drawCallsThisFrame = 0

        for (mesh in instancedMeshes.values) {
            mesh.beginBatch()
        }
    }

    fun addInstance(modelId: Int, modelMatrix: Matrix4f): Boolean {
        val batches = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (batch in batches) {
            val mesh = instancedMeshes[batch.packedKey] ?: continue
            if (!mesh.addInstance(modelMatrix)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    fun addInstanceRaw(modelId: Int, matrixData: FloatArray): Boolean {
        val batches = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (batch in batches) {
            val mesh = instancedMeshes[batch.packedKey] ?: continue
            if (!mesh.addInstanceRaw(matrixData)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    fun addInstance(modelId: Int, x: Float, y: Float, z: Float, scale: Float = 1f): Boolean {
        val batches = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (batch in batches) {
            val mesh = instancedMeshes[batch.packedKey] ?: continue
            if (!mesh.addInstance(x, y, z, scale)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    fun uploadAll() {
        for (mesh in instancedMeshes.values) {
            mesh.endBatch()
        }
    }

    fun renderUntextured(
        shader: ShaderProgram,
        setupCallback: ((InstanceBatchKey) -> Unit)? = null
    ) {
        shader.bind()

        for ((key, mesh) in instancedMeshes) {
            if (!isUntexturedKey(key)) continue
            if (mesh.getInstanceCount() == 0) continue

            setupCallback?.invoke(InstanceBatchKey(unpackModelId(key), UNTEXTURED_SENTINEL))
            mesh.render()
            drawCallsThisFrame++
        }
    }

    private fun ensureSortedTexturedEntries() {
        if (!texturedEntriesDirty) return
        sortedTexturedEntries = instancedMeshes.entries
            .filter { !isUntexturedKey(it.key) }
            .sortedBy { unpackSecondaryKey(it.key) }
        texturedEntriesDirty = false
    }

    fun renderTexturedWithAtlas(
        shader: ShaderProgram,
        fallbackBindTexture: (textureId: Int) -> Unit
    ) {
        val atlas = objectAtlas ?: return
        shader.bind()

        ensureSortedTexturedEntries()

        var lastBoundPage = -1
        for (entry in sortedTexturedEntries) {
            val mesh = entry.value
            if (mesh.getInstanceCount() == 0) continue

            val secondaryKey = unpackSecondaryKey(entry.key)

            if (secondaryKey < -1) {
                val originalTextureId = -(secondaryKey + 2)
                fallbackBindTexture(originalTextureId)
                lastBoundPage = -1
            } else {
                if (secondaryKey != lastBoundPage) {
                    atlas.bindPage(secondaryKey, 0)
                    lastBoundPage = secondaryKey
                }
            }

            mesh.render()
            drawCallsThisFrame++
        }
    }

    fun renderTextured(
        shader: ShaderProgram,
        bindTexture: (textureId: Int) -> Unit
    ) {
        shader.bind()

        ensureSortedTexturedEntries()

        var lastTextureId = -1
        for (entry in sortedTexturedEntries) {
            val mesh = entry.value
            if (mesh.getInstanceCount() == 0) continue

            val textureId = unpackSecondaryKey(entry.key)
            if (textureId != lastTextureId) {
                bindTexture(textureId)
                lastTextureId = textureId
            }
            mesh.render()
            drawCallsThisFrame++
        }
    }

    fun renderAll(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram,
        bindTexture: (textureId: Int) -> Unit
    ) {
        renderUntextured(colorShader)
        renderTextured(texturedShader, bindTexture)
    }

    fun getStats(): InstanceStats {
        val instanceCounts = mutableMapOf<InstanceBatchKey, Int>()
        for ((key, mesh) in instancedMeshes) {
            instanceCounts[InstanceBatchKey(unpackModelId(key), unpackSecondaryKey(key))] = mesh.getInstanceCount()
        }
        return InstanceStats(
            totalInstances = totalInstancesThisFrame,
            drawCalls = drawCallsThisFrame,
            registeredModels = meshRegistry.size,
            instancedMeshCount = instancedMeshes.size,
            instancesPerBatch = instanceCounts
        )
    }

    fun getTotalTrianglesSubmitted(): Int {
        var total = 0
        for (mesh in instancedMeshes.values) {
            val count = mesh.getInstanceCount()
            if (count > 0) {
                total += (mesh.vertexCount / 3) * count
            }
        }
        return total
    }

    fun cleanup() {
        for (mesh in instancedMeshes.values) {
            mesh.cleanup()
        }
        instancedMeshes.clear()
        meshRegistry.clear()
        sortedTexturedEntries = emptyList()
        texturedEntriesDirty = true
    }

    fun unregisterModel(modelId: Int) {
        val batches = meshRegistry.remove(modelId) ?: return

        for (batch in batches) {
            instancedMeshes.remove(batch.packedKey)?.cleanup()
        }

        texturedEntriesDirty = true
    }
}

data class InstanceStats(
    val totalInstances: Int,
    val drawCalls: Int,
    val registeredModels: Int,
    val instancedMeshCount: Int,
    val instancesPerBatch: Map<InstanceBatchKey, Int>
)
