package org.darkan.tools.engine.scene

import org.darkan.tools.engine.render.*
import org.darkan.tools.engine.spatial.*
import org.darkan.tools.engine.terrain.ChunkedTerrain
import org.joml.Matrix4f
import org.joml.Vector3f

data class SpatialObject(
    val id: Int,
    override val modelId: Int,
    val transform: Matrix4f,
    override val bounds: AABB,
    override val position: Vector3f,
    override val regionId: Int = -1,
    override val plane: Int = 0,
    var visible: Boolean = true,
    var lodLevel: Int = 0
) : CullableObject {
    override val objectId: Int get() = id
}

data class SceneUpdateResult(
    val visibleObjects: Int,
    val totalObjects: Int,
    val visibleChunks: Int,
    val totalChunks: Int,
    val cullTimeMs: Float,
    val lodTimeMs: Float
)

class SpatialScene(
    worldBounds: Rect = Rect(-10000f, -10000f, 10000f, 10000f)
) {
    val cullingSystem = CullingSystem(worldBounds)
    val lodSystem = LODSystem()
    val instanceManager = InstanceManager()

    val terrain = ChunkedTerrain()

    val staticBatcher = StaticBatcher()

    private val objects = mutableMapOf<Int, SpatialObject>()
    private var nextObjectId = 1

    private val visibleObjects = mutableListOf<SpatialObject>()

    private val identityMatrix = Matrix4f()

    private var lastCameraPos = Vector3f()
    private var lastAspectRatio = 1f

    private var lastCullTimeNanos = 0L
    private var lastLodTimeNanos = 0L

    fun addObject(
        modelId: Int,
        transform: Matrix4f,
        bounds: AABB,
        regionId: Int = -1
    ): Int {
        val id = nextObjectId++
        val position = Vector3f()
        transform.getTranslation(position)

        val obj = SpatialObject(
            id = id,
            modelId = modelId,
            transform = transform,
            bounds = bounds,
            position = position,
            regionId = regionId
        )

        objects[id] = obj
        cullingSystem.registerObject(obj)

        return id
    }

    fun removeObject(id: Int) {
        val obj = objects.remove(id) ?: return
        cullingSystem.unregisterObject(obj)
    }

    fun updateObjectTransform(id: Int, newTransform: Matrix4f) {
        val obj = objects[id] ?: return

        newTransform.getTranslation(obj.position)

        cullingSystem.unregisterObject(obj)

        val newBounds = obj.bounds.transform(newTransform)
        val updated = obj.copy(
            transform = Matrix4f(newTransform),
            bounds = newBounds
        )
        objects[id] = updated
        cullingSystem.registerObject(updated)
    }

    fun registerTerrainRegion(regionId: Int, regionX: Int, regionY: Int, bounds: Rect) {
        val chunk = RegionChunk(
            regionId = regionId,
            regionX = regionX,
            regionY = regionY,
            bounds = bounds
        )
        cullingSystem.registerRegion(chunk)
    }

    fun registerInstancedModel(modelId: Int, meshDataList: List<MeshData>) {
        instanceManager.registerModel(modelId, meshDataList)
    }

    fun update(camera: Camera, aspectRatio: Float): SceneUpdateResult {
        lastCameraPos.set(camera.position)
        lastAspectRatio = aspectRatio

        val cullStart = System.nanoTime()
        val frustum = camera.getFrustum(aspectRatio)
        val cullResult = cullingSystem.cull(frustum)
        lastCullTimeNanos = System.nanoTime() - cullStart

        visibleObjects.clear()
        for (cullable in cullResult.visibleObjects) {
            if (cullable is SpatialObject) {
                cullable.visible = true
                visibleObjects.add(cullable)
            }
        }

        for (obj in objects.values) {
            if (obj !in visibleObjects) {
                obj.visible = false
            }
        }

        terrain.updateVisibility(frustum)

        val lodStart = System.nanoTime()
        terrain.updateLOD(camera.position, lodSystem.lodDistances)

        for (obj in visibleObjects) {
            val distSq = obj.position.distanceSquared(camera.position)
            obj.lodLevel = calculateLodLevel(distSq)
        }
        lastLodTimeNanos = System.nanoTime() - lodStart

        return SceneUpdateResult(
            visibleObjects = visibleObjects.size,
            totalObjects = objects.size,
            visibleChunks = terrain.getStats().visibleChunks,
            totalChunks = terrain.getStats().totalChunks,
            cullTimeMs = lastCullTimeNanos / 1_000_000f,
            lodTimeMs = lastLodTimeNanos / 1_000_000f
        )
    }

    private fun calculateLodLevel(distanceSquared: Float): Int {
        val distances = lodSystem.lodDistances
        for ((i, threshold) in distances.withIndex()) {
            if (distanceSquared < threshold * threshold) {
                return i
            }
        }
        return distances.size
    }

    fun prepareInstancedRendering() {
        instanceManager.prepareFrame()

        for (obj in visibleObjects) {
            if (instanceManager.isModelRegistered(obj.modelId)) {
                instanceManager.addInstance(obj.modelId, obj.transform)
            }
        }

        instanceManager.uploadAll()
    }

    fun render(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram,
        uniformBuffers: UniformBuffers? = null,
        bindTexture: (Int) -> Unit
    ) {
        terrain.render(colorShader, texturedShader, bindTexture)

        val batches = staticBatcher.getAllMeshes()
        for ((key, mesh) in batches) {
            if (key.textureId == -1) {
                colorShader.bind()
                colorShader.setUniform("model", identityMatrix)
                mesh.render()
            } else {
                texturedShader.bind()
                texturedShader.setUniform("model", identityMatrix)
                bindTexture(key.textureId)
                mesh.render()
            }
        }

        instanceManager.renderAll(colorShader, texturedShader, bindTexture)

        for (obj in visibleObjects) {
            if (!instanceManager.isModelRegistered(obj.modelId)) {
            }
        }
    }

    fun getVisibleObjects(): List<SpatialObject> = visibleObjects.toList()

    fun getAllObjects(): Collection<SpatialObject> = objects.values

    fun queryAABB(aabb: AABB): List<SpatialObject> {
        val cullables = cullingSystem.queryAABB(aabb)
        return cullables.filterIsInstance<SpatialObject>()
    }

    fun getStats(): SpatialSceneStats {
        val cullStats = cullingSystem.getStats()
        val terrainStats = terrain.getStats()
        val instanceStats = instanceManager.getStats()
        val batcherStats = staticBatcher.getStats()

        return SpatialSceneStats(
            totalObjects = objects.size,
            visibleObjects = visibleObjects.size,
            totalRegions = cullStats.totalRegions,
            totalChunks = terrainStats.totalChunks,
            visibleChunks = terrainStats.visibleChunks,
            instancedModels = instanceStats.registeredModels,
            instanceCount = instanceStats.totalInstances,
            drawCalls = instanceStats.drawCalls,
            staticBatches = batcherStats.batchCount,
            cullTimeMs = lastCullTimeNanos / 1_000_000f,
            lodTimeMs = lastLodTimeNanos / 1_000_000f
        )
    }

    fun clear() {
        objects.clear()
        visibleObjects.clear()
        cullingSystem.clear()
        terrain.clear()
        staticBatcher.clear()
        instanceManager.cleanup()
        nextObjectId = 1
    }

    fun cleanup() {
        terrain.cleanup()
        staticBatcher.cleanup()
        instanceManager.cleanup()
        clear()
    }
}

data class SpatialSceneStats(
    val totalObjects: Int,
    val visibleObjects: Int,
    val totalRegions: Int,
    val totalChunks: Int,
    val visibleChunks: Int,
    val instancedModels: Int,
    val instanceCount: Int,
    val drawCalls: Int,
    val staticBatches: Int,
    val cullTimeMs: Float,
    val lodTimeMs: Float
) {
    val objectCullRatio: Float
        get() = if (totalObjects > 0) 1f - (visibleObjects.toFloat() / totalObjects) else 0f

    val chunkCullRatio: Float
        get() = if (totalChunks > 0) 1f - (visibleChunks.toFloat() / totalChunks) else 0f
}
