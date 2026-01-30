package org.darkan.tools.cache.map

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.spatial.AABB
import org.darkan.tools.engine.spatial.CullResult
import org.darkan.tools.engine.spatial.CullingSystem
import org.darkan.tools.engine.spatial.Rect
import org.darkan.tools.engine.spatial.RegionChunk
import org.darkan.tools.engine.spatial.SceneObject
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapObject
import kotlin.math.floor

/**
 * Loaded region data with renderable meshes.
 */
data class LoadedRegion(
    val regionX: Int,
    val regionY: Int,
    val regionId: Int,
    val data: RegionData,
    val terrainMeshes: Array<Mesh?>,  // One per plane (0-3)
    val objectsByPlane: Map<Int, List<ObjectInstance>>,  // Objects grouped by plane
    val chunk: RegionChunk
) {
    // Legacy accessor for backwards compatibility
    val objects: List<ObjectInstance>
        get() = objectsByPlane.values.flatten()

    fun getObjectsForPlane(plane: Int): List<ObjectInstance> =
        objectsByPlane[plane] ?: emptyList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedRegion) return false
        return regionId == other.regionId
    }

    override fun hashCode(): Int = regionId

    fun cleanup() {
        terrainMeshes.forEach { it?.cleanup() }
    }
}

/**
 * Statistics about the map scene.
 */
data class MapSceneStats(
    val loadedRegions: Int,
    val visibleRegions: Int,
    val totalObjects: Int,
    val visibleObjects: Int,
    val loadedModels: Int,
    val cullingTimeMs: Float
)

/**
 * Manages loading and rendering of a multi-region map scene.
 *
 * Features:
 * - Region streaming based on camera position
 * - Frustum culling for regions and objects
 * - Terrain mesh generation
 * - Object instance batching
 *
 * Usage:
 * ```kotlin
 * val scene = MapScene(cache, viewRadius = 2)
 * scene.initialize()
 *
 * // In game loop:
 * scene.update(camera.position.x, camera.position.z)
 * scene.cull(camera, aspectRatio)
 * scene.render(colorShader, texturedShader, camera, bindTexture)
 * ```
 */
class MapScene(
    private val cache: Cache,
    private val viewRadius: Int = 2,  // Load (2*radius+1)^2 regions = 5x5 = 25 regions
    private val unloadRadius: Int = viewRadius + 1
) {
    // Currently loaded regions
    private val loadedRegions = mutableMapOf<Int, LoadedRegion>()

    // Spatial culling system
    private val cullingSystem = CullingSystem(
        worldBounds = Rect(-100000f, -100000f, 100000f, 100000f),
        worldYMin = -1000f,
        worldYMax = 1000f
    )

    // Instance manager for object batching
    private val instanceManager = InstanceManager(maxInstancesPerMesh = 50000)

    // Registered model IDs
    private val registeredModels = mutableSetOf<Int>()

    // Current center region
    private var centerRegionX = -1
    private var centerRegionY = -1

    // Current plane being rendered
    var currentPlane = 0
        private set

    // Last culling result
    private var lastCullResult: CullResult? = null

    // Tile size in world units
    private val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
    private val regionSize = 64 * tileSize

    /**
     * Initialize the map scene.
     * Loads XTEA keys and floor definitions.
     */
    fun initialize() {
        // Load XTEA keys if not already loaded
        if (!MapXteas.isLoaded()) {
            MapXteas.load()
        }

        // Load terrain and object definitions
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)
    }

    /**
     * Set the current plane (0-3) to render.
     */
    fun setPlane(plane: Int) {
        currentPlane = plane.coerceIn(0, 3)
    }

    /**
     * Update the scene based on camera position.
     * Loads new regions and unloads distant ones.
     *
     * @param cameraX Camera X position in world coordinates
     * @param cameraZ Camera Z position in world coordinates
     */
    fun update(cameraX: Float, cameraZ: Float) {
        // Convert world position to region coordinates
        val regionX = worldToRegionX(cameraX)
        val regionY = worldToRegionY(cameraZ)

        // Skip if center hasn't changed
        if (regionX == centerRegionX && regionY == centerRegionY) return

        centerRegionX = regionX
        centerRegionY = regionY

        // Determine which regions should be loaded
        val neededRegions = mutableSetOf<Int>()
        for (dx in -viewRadius..viewRadius) {
            for (dy in -viewRadius..viewRadius) {
                val rx = regionX + dx
                val ry = regionY + dy
                if (rx >= 0 && ry >= 0 && rx < 256 && ry < 256) {
                    neededRegions.add((rx shl 8) or ry)
                }
            }
        }

        // Unload distant regions
        val toUnload = loadedRegions.keys.filter { regionId ->
            val rx = regionId shr 8
            val ry = regionId and 0xff
            val dx = kotlin.math.abs(rx - regionX)
            val dy = kotlin.math.abs(ry - regionY)
            dx > unloadRadius || dy > unloadRadius
        }

        for (regionId in toUnload) {
            unloadRegion(regionId)
        }

        // Load new regions
        for (regionId in neededRegions) {
            if (!loadedRegions.containsKey(regionId)) {
                loadRegion(regionId)
            }
        }
    }

    /**
     * Perform frustum culling.
     */
    fun cull(camera: Camera, aspectRatio: Float): CullResult {
        lastCullResult = cullingSystem.cull(camera, aspectRatio)
        return lastCullResult!!
    }

    /**
     * Render objects only (terrain should be rendered separately with renderTerrain).
     *
     * @param instancedColorShader Shader for instanced objects without textures
     * @param instancedTexturedShader Shader for instanced objects with textures
     * @param bindTexture Callback to bind textures
     */
    fun renderObjects(
        instancedColorShader: ShaderProgram,
        instancedTexturedShader: ShaderProgram?,
        bindTexture: ((Int) -> Unit)? = null
    ) {
        val cullResult = lastCullResult ?: return
        renderObjectsInternal(instancedColorShader, instancedTexturedShader, cullResult, bindTexture)
    }

    // Debug flag - set to true for verbose logging
    private var debugRender = false
    private var debugFrameCount = 0

    /**
     * Render terrain only (for debugging or simple views).
     */
    fun renderTerrain(shader: ShaderProgram) {
        val cullResult = lastCullResult
        if (cullResult == null) {
            if (debugRender && debugFrameCount++ < 10) println("[Render] No cull result")
            return
        }

        if (debugRender && debugFrameCount < 10) {
            println("[Render] Visible regions: ${cullResult.visibleRegionCount}, plane: $currentPlane")
            debugFrameCount++
        }

        shader.bind()

        var rendered = 0
        for (regionChunk in cullResult.visibleRegions) {
            val loaded = loadedRegions[regionChunk.regionId] ?: continue
            val terrainMesh = loaded.terrainMeshes[currentPlane] ?: continue

            val model = Matrix4f().identity()
                .translate(regionChunk.bounds.minX, 0f, regionChunk.bounds.minZ)

            shader.setUniform("model", model)
            terrainMesh.render()
            rendered++
        }

        if (debugRender && debugFrameCount <= 10) {
            println("[Render] Rendered $rendered terrain meshes")
        }
    }

    /**
     * Internal method to render objects using instanced rendering.
     */
    private fun renderObjectsInternal(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram?,
        cullResult: CullResult,
        bindTexture: ((Int) -> Unit)?
    ) {
        // Prepare instance data
        instanceManager.prepareFrame()

        // Add visible objects to instance batches (filter by current plane)
        var addedInstances = 0
        for (obj in cullResult.visibleObjects) {
            val sceneObj = obj as? SceneObject ?: continue
            // Only render objects on the current plane
            if (sceneObj.plane != currentPlane) continue

            if (instanceManager.addInstance(
                sceneObj.modelId,
                sceneObj.position.x,
                sceneObj.position.y,
                sceneObj.position.z
            )) {
                addedInstances++
            }
        }

        // Upload and render
        instanceManager.uploadAll()

        if (texturedShader != null && bindTexture != null) {
            instanceManager.renderAll(colorShader, texturedShader, bindTexture)
        } else {
            instanceManager.renderUntextured(colorShader)
        }

        if (debugRender && debugFrameCount <= 10) {
            val stats = instanceManager.getStats()
            println("[Objects] Visible: ${cullResult.visibleObjects.size}, Added instances: $addedInstances, Draw calls: ${stats.drawCalls}, Registered models: ${stats.registeredModels}")
        }
    }

    /**
     * Load a region by ID.
     */
    private fun loadRegion(regionId: Int) {
        val regionX = regionId shr 8
        val regionY = regionId and 0xff

        // Load region data (terrain + objects)
        val data = RegionLoader.loadRegion(cache, regionX, regionY) ?: return

        // Create terrain meshes for all planes
        val terrainMeshes = arrayOfNulls<Mesh>(4)
        for (plane in 0 until 4) {
            terrainMeshes[plane] = TerrainLoader.loadRegionMesh(cache, regionX, regionY, plane)
        }

        // Create object instances for ALL planes
        val objectsByPlane = ObjectRenderer.createInstancesAllPlanes(
            data.objects,
            regionX,
            regionY
        ) { x, y, plane -> getTileHeight(data, x, y, plane) }

        // Register needed models for all planes
        for ((_, objects) in objectsByPlane) {
            registerObjectModels(objects)
        }

        // Calculate region bounds in world space
        val worldMinX = regionX * 64 * tileSize
        val worldMinZ = -regionY * 64 * tileSize - regionSize
        val bounds = Rect(worldMinX, worldMinZ, worldMinX + regionSize, worldMinZ + regionSize)

        // Create region chunk for culling
        val chunk = RegionChunk(
            regionId = regionId,
            regionX = regionX,
            regionY = regionY,
            bounds = bounds,
            yMin = -100f,
            yMax = 500f
        )

        // Register with culling system
        cullingSystem.registerRegion(chunk)

        // Register objects with culling system (all planes)
        for ((plane, objects) in objectsByPlane) {
            for (obj in objects) {
                val objBounds = AABB(
                    Vector3f(obj.worldPosition.x - 2f, obj.worldPosition.y - 2f, obj.worldPosition.z - 2f),
                    Vector3f(obj.worldPosition.x + 2f, obj.worldPosition.y + 2f, obj.worldPosition.z + 2f)
                )

                for (modelId in obj.modelIds) {
                    cullingSystem.registerObject(SceneObject(
                        objectId = obj.mapObject.id,
                        modelId = modelId,
                        bounds = objBounds,
                        position = obj.worldPosition,
                        regionId = regionId,
                        plane = plane
                    ))
                }
            }
        }

        loadedRegions[regionId] = LoadedRegion(
            regionX = regionX,
            regionY = regionY,
            regionId = regionId,
            data = data,
            terrainMeshes = terrainMeshes,
            objectsByPlane = objectsByPlane,
            chunk = chunk
        )
    }

    /**
     * Unload a region.
     */
    private fun unloadRegion(regionId: Int) {
        val loaded = loadedRegions.remove(regionId) ?: return

        // Cleanup terrain meshes
        loaded.cleanup()

        // Unregister from culling system
        cullingSystem.unregisterRegion(regionId)
    }

    /**
     * Register models needed for object instances.
     */
    private fun registerObjectModels(objects: List<ObjectInstance>) {
        for (obj in objects) {
            for (modelId in obj.modelIds) {
                if (registeredModels.contains(modelId)) continue

                // Use the new ModelLoader method that creates MeshData directly
                val meshDataList = ModelLoader.createMeshDataList(cache, modelId)
                if (meshDataList != null && meshDataList.isNotEmpty()) {
                    instanceManager.registerModel(modelId, meshDataList)
                    registeredModels.add(modelId)
                }
            }
        }
    }

    /**
     * Get tile height at world coordinates.
     */
    private fun getTileHeight(data: RegionData, worldX: Int, worldY: Int, plane: Int): Float {
        val localX = worldX and 63
        val localY = worldY and 63

        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return 0f

        val tileInfo = RegionLoader.getTileInfo(data.tiles, localX, localY, plane)
        return tileInfo.height * 8f * CoordinateUtil.DEFAULT_SCALE
    }

    /**
     * Convert world X coordinate to region X.
     */
    private fun worldToRegionX(worldX: Float): Int {
        return floor(worldX / regionSize).toInt().coerceIn(0, 255)
    }

    /**
     * Convert world Z coordinate to region Y.
     */
    private fun worldToRegionY(worldZ: Float): Int {
        return floor(-worldZ / regionSize).toInt().coerceIn(0, 255)
    }

    /**
     * Jump to a specific region.
     */
    fun jumpToRegion(regionX: Int, regionY: Int): Vector3f {
        val worldX = regionX * regionSize + regionSize / 2
        val worldZ = -(regionY * regionSize + regionSize / 2)
        update(worldX, worldZ)
        return Vector3f(worldX, 50f, worldZ)
    }

    /**
     * Get scene statistics.
     */
    fun getStats(): MapSceneStats {
        val cullResult = lastCullResult
        return MapSceneStats(
            loadedRegions = loadedRegions.size,
            visibleRegions = cullResult?.visibleRegionCount ?: 0,
            totalObjects = loadedRegions.values.sumOf { it.objects.size },
            visibleObjects = cullResult?.visibleObjectCount ?: 0,
            loadedModels = registeredModels.size,
            cullingTimeMs = (cullResult?.cullingTimeNanos ?: 0) / 1_000_000f
        )
    }

    /**
     * Get list of loaded region IDs.
     */
    fun getLoadedRegionIds(): List<Int> = loadedRegions.keys.toList()

    /**
     * Check if a region is loaded.
     */
    fun isRegionLoaded(regionX: Int, regionY: Int): Boolean {
        return loadedRegions.containsKey((regionX shl 8) or regionY)
    }

    /**
     * Clean up all resources.
     */
    fun cleanup() {
        for (region in loadedRegions.values) {
            region.cleanup()
        }
        loadedRegions.clear()
        cullingSystem.clear()
        instanceManager.cleanup()
        registeredModels.clear()
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
    }
}
