package org.darkan.tools.cache.map

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.InstanceManager
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
    val terrainMeshesByPlane: Array<TerrainLoader.TerrainMeshes?>,  // Batched meshes per plane (0-3)
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
        terrainMeshesByPlane.forEach { it?.cleanup() }
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
    /**
     * Render terrain using color-only shader.
     * Note: Textured terrain batches are not rendered in multi-region mode.
     * For full textured terrain support, use single-region mode.
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
            val terrainMeshes = loaded.terrainMeshesByPlane[currentPlane] ?: continue
            val colorMesh = terrainMeshes.colorMesh ?: continue

            val model = Matrix4f().identity()
                .translate(regionChunk.bounds.minX, 0f, regionChunk.bounds.minZ)

            shader.setUniform("model", model)
            colorMesh.render()
            rendered++
        }

        if (debugRender && debugFrameCount <= 10) {
            println("[Render] Rendered $rendered terrain meshes")
        }
    }

    // Temporary matrix for building instance transforms
    private val tempModelMatrix = Matrix4f()

    /**
     * Internal method to render objects using instanced rendering.
     *
     * Uses full transform data from SceneObject including terrain alignment.
     * Transform order matches RS client (right-to-left matrix operations):
     * RotateY -> TiltX -> TiltZ -> Scale -> Offset -> HeightAdjust -> Translate
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

            // Build full transform matrix matching RS client order:
            // RS applies: RotateY -> TiltX -> TiltZ (in that order on vertices)
            // Matrix equivalent (right-to-left): TiltZ * TiltX * RotateY
            tempModelMatrix.identity()
                .translate(sceneObj.position)     // 6. Move to world position

            // Apply terrain alignment height adjustment
            if (sceneObj.heightAdjustment != 0f) {
                tempModelMatrix.translate(0f, sceneObj.heightAdjustment, 0f)  // 5. Height adjustment
            }

            tempModelMatrix
                .translate(sceneObj.offset)       // 4. Apply definition offset
                .scale(sceneObj.scale)            // 3. Scale

            // Apply terrain tilt rotations (for groundContourType=3 objects)
            if (sceneObj.tiltX != 0f || sceneObj.tiltZ != 0f) {
                tempModelMatrix.rotateZ(sceneObj.tiltZ)  // 2. Roll tilt
                tempModelMatrix.rotateX(sceneObj.tiltX)  // 1b. Pitch tilt
            }

            tempModelMatrix.rotateY(sceneObj.rotation)  // 1a. Object Y rotation

            if (instanceManager.addInstance(sceneObj.modelId, tempModelMatrix)) {
                addedInstances++
            }
        }

        // Upload and render
        instanceManager.uploadAll()

        if (texturedShader != null && bindTexture != null) {
            // Convert material IDs to OpenGL texture IDs before binding
            instanceManager.renderAll(colorShader, texturedShader) { materialId ->
                val textureId = TextureLoader.getTextureForMaterial(cache, materialId)
                bindTexture(textureId)
            }
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

        // Create batched terrain meshes for all planes
        val terrainMeshesByPlane = arrayOfNulls<TerrainLoader.TerrainMeshes>(4)
        for (plane in 0 until 4) {
            terrainMeshesByPlane[plane] = TerrainLoader.loadRegionMeshes(cache, regionX, regionY, plane)
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
        // Include full transform data (rotation, scale, offset, terrain alignment) for correct rendering
        // Use combined model ID for multi-model objects
        for ((plane, objects) in objectsByPlane) {
            for (obj in objects) {
                val objBounds = AABB(
                    Vector3f(obj.worldPosition.x - 2f, obj.worldPosition.y - 2f, obj.worldPosition.z - 2f),
                    Vector3f(obj.worldPosition.x + 2f, obj.worldPosition.y + 2f, obj.worldPosition.z + 2f)
                )

                // Use combined model ID instead of individual model IDs
                val combinedModelId = ObjectRenderer.getCombinedModelId(obj.modelIds)
                cullingSystem.registerObject(SceneObject(
                    objectId = obj.mapObject.id,
                    modelId = combinedModelId,
                    bounds = objBounds,
                    position = obj.worldPosition,
                    regionId = regionId,
                    plane = plane,
                    rotation = obj.rotation,
                    scale = obj.scale,
                    offset = obj.offset,
                    tiltX = obj.terrainAlignment.tiltX,
                    tiltZ = obj.terrainAlignment.tiltZ,
                    heightAdjustment = obj.terrainAlignment.heightAdjustment
                ))
            }
        }

        loadedRegions[regionId] = LoadedRegion(
            regionX = regionX,
            regionY = regionY,
            regionId = regionId,
            data = data,
            terrainMeshesByPlane = terrainMeshesByPlane,
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
     * Uses combined models for multi-model objects (matching RS client behavior).
     */
    private fun registerObjectModels(objects: List<ObjectInstance>) {
        for (obj in objects) {
            // Get the combined model ID for this object's models
            val combinedId = ObjectRenderer.getCombinedModelId(obj.modelIds)
            if (registeredModels.contains(combinedId)) continue

            // Load and combine all models for this object
            val meshDataList = ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
            if (meshDataList != null && meshDataList.isNotEmpty()) {
                instanceManager.registerModel(combinedId, meshDataList)
                registeredModels.add(combinedId)
            }
        }
    }

    /**
     * Get tile height at LOCAL coordinates (0-63).
     * Height scale must match TerrainLoader.HEIGHT_SCALE = 32 * DEFAULT_SCALE
     * to ensure objects sit correctly on the terrain surface.
     */
    private fun getTileHeight(data: RegionData, localX: Int, localY: Int, plane: Int): Float {
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return 0f

        val tileInfo = RegionLoader.getTileInfo(data.tiles, localX, localY, plane)
        // Use same height scale as TerrainLoader: rawHeight * 32 / 128 = rawHeight * 0.25
        return tileInfo.height * 32f * CoordinateUtil.DEFAULT_SCALE
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
