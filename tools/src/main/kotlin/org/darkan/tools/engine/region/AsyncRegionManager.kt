package org.darkan.tools.engine.region

import kotlinx.coroutines.*
import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.VertexDisplacer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.async.*
import org.darkan.tools.engine.render.*
import org.joml.Matrix4f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-region loading state.
 */
enum class RegionState {
    NOT_LOADED,
    CPU_LOADING,
    GPU_PENDING,
    READY,
    UNLOADING
}

/**
 * Holds per-region data through the loading pipeline.
 */
class AsyncRegionData(
    val key: RegionKey,
    @Volatile var state: RegionState = RegionState.NOT_LOADED,
    // CPU-side computed data (set when CPU_LOADING completes)
    var terrainMeshData: TerrainLoader.TerrainMeshData? = null,
    var objectInstances: List<ObjectInstance> = emptyList(),
    var modelMeshData: Map<Int, List<MeshData>> = emptyMap(),
    var displacedMeshData: Map<Int, List<MeshData>> = emptyMap(),
    // GPU-side resources (set when GPU tasks complete)
    var terrainMeshes: TerrainLoader.TerrainMeshes? = null,
    var batchMeshes: Map<BatchKey, Mesh>? = null,
    // Tracking
    var pendingGpuTasks: AtomicInteger = AtomicInteger(0),
    var loadJob: Job? = null
)

/**
 * Manages async loading of map regions with background CPU work
 * and time-budgeted GPU uploads.
 *
 * Uses a state machine per region:
 * ```
 * NOT_LOADED -> CPU_LOADING -> GPU_PENDING -> READY
 *                                              |
 *                                          UNLOADING -> removed
 * ```
 *
 * The center region is always loaded synchronously (for initial load / loading screen).
 * Context regions are loaded asynchronously in the background.
 */
class AsyncRegionManager(
    private val cache: Cache
) {
    private val gpuUploadQueue = GpuUploadQueue()
    private val loadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val regions = ConcurrentHashMap<RegionKey, AsyncRegionData>()

    // Configuration
    var renderDistance: Int = 1
    var showRoofs: Boolean = true
    var objectAtlas: TextureAtlas? = null
    var terrainAtlas: TextureAtlas? = null

    // Center region state (loaded synchronously, always available)
    private var centerKey: RegionKey? = null
    private var centerPlane: Int = 0

    // Synchronous RegionManager for the center region (keeps all its existing functionality)
    private lateinit var syncRegionManager: RegionManager

    // Shared registered model tracking
    private val registeredModels = ConcurrentHashMap.newKeySet<Int>()

    // Instance manager (shared across all regions, main-thread only)
    var instanceManager: InstanceManager? = null
        private set

    // Expose center region data for the viewer
    var centerObjects: List<ObjectInstance> = emptyList()
        private set
    var centerTerrainData: TerrainLoader.RegionData? = null
        private set
    var centerMapObjects: List<MapObject> = emptyList()
        private set
    var centerRegionData: RegionData? = null
        private set
    var centerModelMatrix: Matrix4f = Matrix4f()
        private set
    var mergedOpaqueTerrain: Mesh? = null
        private set
    var mergedAlphaTerrain: Mesh? = null
        private set

    // ========================================================================
    // Initialization
    // ========================================================================

    fun initialize() {
        syncRegionManager = RegionManager(cache)
        syncRegionManager.initialize()
    }

    // ========================================================================
    // Synchronous Center Region Loading
    // ========================================================================

    /**
     * Load the center region synchronously (blocks until complete).
     * This is used for the initial load and when navigating to a completely new area.
     * Context regions are kicked off as background jobs.
     */
    fun loadRegionSync(regionX: Int, regionY: Int, plane: Int) {
        // Cancel any in-progress background loading
        cancelAllLoading()

        centerKey = RegionKey(regionX, regionY)
        centerPlane = plane

        // Use the synchronous RegionManager for the center region
        syncRegionManager.renderDistance = 0  // Only load center
        syncRegionManager.showRoofs = showRoofs
        syncRegionManager.objectAtlas = objectAtlas
        syncRegionManager.terrainAtlas = terrainAtlas
        syncRegionManager.loadRegion(regionX, regionY, plane)

        // Copy out center region data
        centerObjects = syncRegionManager.centerObjects
        centerTerrainData = syncRegionManager.centerTerrainData
        centerMapObjects = syncRegionManager.centerMapObjects
        centerRegionData = syncRegionManager.centerRegionData
        instanceManager = syncRegionManager.instanceManager
        centerModelMatrix = syncRegionManager.centerModelMatrix
        registeredModels.clear()

        // Mark center as READY
        val centerData = AsyncRegionData(RegionKey(regionX, regionY), RegionState.READY)
        regions[centerData.key] = centerData

        // Kick off async loading for context regions
        if (renderDistance > 0) {
            loadContextRegionsAsync(regionX, regionY, plane)
        }
    }

    /**
     * Navigate to a new center region. Keeps context regions that are still in view,
     * only loads new ones. Center region is still loaded synchronously.
     */
    fun navigateToRegion(regionX: Int, regionY: Int, plane: Int) {
        val newCenter = RegionKey(regionX, regionY)
        val oldCenter = centerKey

        // If same region, same plane - just return
        if (oldCenter == newCenter && plane == centerPlane) return

        // Cancel and remove in-progress regions so they can be re-loaded fresh.
        // Only keep READY regions; cancelled coroutines leave stale state.
        val inProgressKeys = regions.entries
            .filter { it.value.state == RegionState.CPU_LOADING || it.value.state == RegionState.GPU_PENDING }
            .map { it.key }
        for (key in inProgressKeys) {
            val data = regions.remove(key)
            data?.loadJob?.cancel()
            gpuUploadQueue.cancelRegion(key.toLong())
            data?.terrainMeshes?.cleanup()
            data?.batchMeshes?.values?.forEach { it.cleanup() }
        }

        // Compute which regions to keep vs unload
        val desiredRegions = mutableSetOf<RegionKey>()
        desiredRegions.add(newCenter)
        for (dx in -renderDistance..renderDistance) {
            for (dy in -renderDistance..renderDistance) {
                val rx = regionX + dx
                val ry = regionY + dy
                if (rx in 0..255 && ry in 0..255) {
                    desiredRegions.add(RegionKey(rx, ry))
                }
            }
        }

        // Unload READY regions no longer in view
        val toRemove = regions.keys.filter { it !in desiredRegions }
        for (key in toRemove) {
            unloadRegion(key)
        }

        centerKey = newCenter
        centerPlane = plane

        // Sync load the new center
        syncRegionManager.renderDistance = 0
        syncRegionManager.showRoofs = showRoofs
        syncRegionManager.objectAtlas = objectAtlas
        syncRegionManager.terrainAtlas = terrainAtlas
        syncRegionManager.loadRegion(regionX, regionY, plane)

        // Copy out center region data
        centerObjects = syncRegionManager.centerObjects
        centerTerrainData = syncRegionManager.centerTerrainData
        centerMapObjects = syncRegionManager.centerMapObjects
        centerRegionData = syncRegionManager.centerRegionData
        instanceManager = syncRegionManager.instanceManager
        centerModelMatrix = syncRegionManager.centerModelMatrix

        // Clear registered models — the new instanceManager doesn't have them.
        // Async regions will re-register as needed.
        registeredModels.clear()

        // Mark center as READY
        val centerData = AsyncRegionData(newCenter, RegionState.READY)
        regions[newCenter] = centerData

        // Invalidate merged terrain (will be rebuilt when all context is ready)
        invalidateMergedTerrain()

        // Build merged terrain for just center immediately (so atlas path has something to render)
        buildMergedAtlasTerrain()

        // Kick off async loading for context regions that aren't already READY
        if (renderDistance > 0) {
            loadContextRegionsAsync(regionX, regionY, plane)
        }
    }

    /**
     * Start background loading of context regions around the center.
     */
    private fun loadContextRegionsAsync(centerRegionX: Int, centerRegionY: Int, plane: Int) {
        val offsets = mutableListOf<RegionKey>()
        for (dx in -renderDistance..renderDistance) {
            for (dy in -renderDistance..renderDistance) {
                if (dx == 0 && dy == 0) continue  // Skip center
                val rx = centerRegionX + dx
                val ry = centerRegionY + dy
                if (rx in 0..255 && ry in 0..255) {
                    offsets.add(RegionKey(rx, ry))
                }
            }
        }

        // Sort by distance from center (load nearest first)
        offsets.sortBy { (it.regionX - centerRegionX) * (it.regionX - centerRegionX) +
                         (it.regionY - centerRegionY) * (it.regionY - centerRegionY) }

        for (key in offsets) {
            if (regions.containsKey(key) && regions[key]!!.state == RegionState.READY) {
                continue  // Already loaded
            }
            loadRegionAsync(key, plane)
        }
    }

    /**
     * Start background loading of a single region.
     */
    private fun loadRegionAsync(key: RegionKey, plane: Int) {
        val regionData = AsyncRegionData(key, RegionState.CPU_LOADING)
        regions[key] = regionData

        val showRoofsLocal = showRoofs
        val priority = computeRegionPriority(key)
        val regionKeyLong = key.toLong()

        regionData.loadJob = loadingScope.launch {
            try {
                // Phase 1: Load terrain data (CPU)
                val terrainData = TerrainLoader.loadRegion(cache, key.regionX, key.regionY)
                if (terrainData == null) {
                    regionData.state = RegionState.READY  // Empty region, nothing to load
                    return@launch
                }
                val neighbors = TerrainLoader.buildNeighborData(cache, key.regionX, key.regionY)
                val terrainMeshData = TerrainLoader.computeTerrainMeshData(terrainData, plane, neighbors)
                regionData.terrainMeshData = terrainMeshData

                // Enqueue terrain GPU upload
                regionData.pendingGpuTasks.incrementAndGet()
                gpuUploadQueue.enqueue(UploadTerrainMesh(
                    regionKey = regionKeyLong,
                    priority = priority,
                    meshData = terrainMeshData,
                    plane = plane
                ))

                // Phase 2: Load objects (CPU)
                val ctxRegionData = RegionLoader.loadRegion(cache, key.regionX, key.regionY, useCache = false)
                if (ctxRegionData != null) {
                    val ctxTiles = terrainData.tiles
                    val allPlaneInstances = ObjectRenderer.createInstancesAllPlanes(
                        objects = ctxRegionData.objects,
                        regionX = key.regionX,
                        regionY = key.regionY,
                        tileHeights = { localX, localY, p ->
                            TerrainLoader.getTileHeightScaled(ctxTiles, localX, localY, p)
                        },
                        rsHeightSampler = { localX, localY, p ->
                            TerrainLoader.getTileHeight(ctxTiles, localX, localY, p)
                        }
                    )

                    val instances = allPlaneInstances.values.flatten().let { insts ->
                        if (!showRoofsLocal) insts.filter { it.mapObject.shape !in 12..21 }
                        else insts
                    }
                    regionData.objectInstances = instances

                    // Phase 3: Load model mesh data (CPU)
                    val modelMeshData = mutableMapOf<Int, List<MeshData>>()
                    for (obj in instances) {
                        val combinedId = obj.cachedCombinedModelId
                        if (modelMeshData.containsKey(combinedId)) continue

                        val meshDataList = if (obj.modifications.hasModifications()) {
                            ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                        } else {
                            ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                        }
                        if (meshDataList != null && meshDataList.isNotEmpty()) {
                            modelMeshData[combinedId] = meshDataList

                            // Enqueue model registration if not already registered
                            if (registeredModels.add(combinedId)) {
                                regionData.pendingGpuTasks.incrementAndGet()
                                gpuUploadQueue.enqueue(RegisterModel(
                                    regionKey = regionKeyLong,
                                    priority = priority + 1,  // Models after terrain
                                    modelId = combinedId,
                                    meshDataList = meshDataList
                                ))
                            }
                        }
                    }
                    regionData.modelMeshData = modelMeshData

                    // Phase 4: Compute vertex displacement (CPU)
                    val displacedMeshData = mutableMapOf<Int, List<MeshData>>()
                    for ((objIndex, obj) in instances.withIndex()) {
                        if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) continue

                        val baseMeshDataList = modelMeshData[obj.cachedCombinedModelId] ?: continue
                        val displaced = mutableListOf<MeshData>()
                        var anyDisplaced = false

                        for (meshData in baseMeshDataList) {
                            val displacedMesh = VertexDisplacer.displaceVertices(
                                meshData = meshData,
                                sceneX = obj.rsSceneX,
                                sceneY = obj.rsSceneY,
                                averageHeight = obj.rsAverageHeight,
                                plane = obj.mapObject.level,
                                tiles = ctxTiles,
                                regionBaseX = key.regionX * 64,
                                regionBaseY = key.regionY * 64,
                                rsRotation = obj.mapObject.rotation,
                                mirrored = obj.mirrored,
                                resizeX = obj.definition.resizeX,
                                resizeY = obj.definition.resizeY,
                                resizeZ = obj.definition.resizeZ,
                                rsOffsetX = obj.definition.offsetX,
                                rsOffsetZ = obj.definition.offsetZ
                            )

                            if (displacedMesh != null) {
                                displaced.add(displacedMesh)
                                anyDisplaced = true
                            } else {
                                displaced.add(meshData)
                            }
                        }

                        if (anyDisplaced) {
                            displacedMeshData[objIndex] = displaced
                        }
                    }
                    regionData.displacedMeshData = displacedMeshData

                    // Phase 5: Compute static batch geometry (CPU)
                    val tileSize = RegionManager.TILE_SIZE
                    val transformer = GeometryTransformer()
                    val tempMatrix = Matrix4f()
                    val atlas = objectAtlas
                    val batcherData = mutableMapOf<BatchKey, MutableList<TransformedGeometry>>()

                    for ((objIndex, obj) in instances.withIndex()) {
                        val combinedId = obj.cachedCombinedModelId
                        val meshDataList = displacedMeshData[objIndex]
                            ?: modelMeshData[combinedId]
                        if (meshDataList == null || meshDataList.isEmpty()) continue

                        tempMatrix.identity()
                            .translate(obj.worldPosition)

                        if (obj.terrainAlignment.needsAlignment()) {
                            tempMatrix.translate(0f, obj.terrainAlignment.heightAdjustment, 0f)
                        }

                        tempMatrix
                            .translate(obj.offset)
                            .scale(obj.scale)

                        if (obj.terrainAlignment.needsAlignment()) {
                            tempMatrix.rotateZ(obj.terrainAlignment.tiltZ)
                            tempMatrix.rotateX(obj.terrainAlignment.tiltX)
                        }

                        tempMatrix.rotateY(obj.rotation)

                        if (obj.mirrored) {
                            tempMatrix.scale(1f, 1f, -1f)
                        }

                        for (meshData in meshDataList) {
                            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

                            val batchKey = resolveBatchKey(meshData, atlas)

                            val texCoords = if (batchKey.atlasPageIndex >= 0 && atlas != null && meshData.textureId >= 0) {
                                remapTexCoordsToAtlas(meshData.textureCoords, meshData.textureId, atlas)
                            } else {
                                meshData.textureCoords
                            }

                            val transformed = transformer.transformMatchingInstanced(
                                meshData.positions, meshData.colors, texCoords,
                                meshData.normals, meshData.indices, tempMatrix
                            )
                            batcherData.getOrPut(batchKey) { mutableListOf() }.add(transformed)
                        }
                    }

                    // Enqueue batch mesh builds for GPU
                    for ((batchKey, geometries) in batcherData) {
                        regionData.pendingGpuTasks.incrementAndGet()
                        gpuUploadQueue.enqueue(BuildRegionBatchMeshes(
                            regionKey = regionKeyLong,
                            priority = priority + 2,
                            batchKey = batchKey,
                            geometries = geometries
                        ))
                    }
                }

                // Transition state
                regionData.state = RegionState.GPU_PENDING

            } catch (e: CancellationException) {
                throw e  // Propagate cancellation
            } catch (e: Exception) {
                println("[AsyncRegionManager] Error loading region ${key.regionX},${key.regionY}: ${e.message}")
                e.printStackTrace()
                // Mark as READY (empty) rather than removing — removal would cause
                // getLoadProgress() totalRegions to never match regionsReady.
                regionData.pendingGpuTasks.set(0)
                regionData.state = RegionState.READY
            }
        }
    }

    // ========================================================================
    // GPU Upload Processing (Main Thread)
    // ========================================================================

    /**
     * Process GPU upload tasks within the given time budget.
     * MUST be called on the main GL thread each frame.
     */
    fun drainGpuUploads(budgetNanos: Long) {
        val processed = gpuUploadQueue.drain(budgetNanos, processor = { task ->
            when (task) {
                is UploadTerrainMesh -> processTerrainUpload(task)
                is RegisterModel -> processModelRegistration(task)
                is BuildRegionBatchMeshes -> processBatchBuild(task)
                is UploadAtlasPage -> { /* handled elsewhere */ }
                is BuildMergedTerrain -> processMergedTerrainBuild(task)
            }
        }, onCancelled = { task ->
            // Decrement the owning region's pending counter so it isn't stuck forever
            val key = regionKeyFromLong(task.regionKey)
            regions[key]?.pendingGpuTasks?.decrementAndGet()
        })

        RenderMetrics.gpuUploadTasksProcessed = processed
        RenderMetrics.pendingGpuTasks = gpuUploadQueue.pendingCount()

        // Check for regions that have completed all GPU tasks
        for ((_, data) in regions) {
            if (data.state == RegionState.GPU_PENDING && data.pendingGpuTasks.get() <= 0) {
                data.state = RegionState.READY
            }
        }

        // Update metrics
        RenderMetrics.regionsLoading = regions.values.count {
            it.state == RegionState.CPU_LOADING || it.state == RegionState.GPU_PENDING
        }
        RenderMetrics.regionsReady = regions.values.count { it.state == RegionState.READY }
    }

    private fun processTerrainUpload(task: UploadTerrainMesh) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return  // Region removed, task irrelevant

        val meshData = task.meshData as? TerrainLoader.TerrainMeshData
        if (meshData != null) {
            val terrainMeshes = TerrainLoader.uploadTerrainMeshData(meshData)
            regionData.terrainMeshes = terrainMeshes
        }
        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processModelRegistration(task: RegisterModel) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return  // Region removed, task irrelevant

        val im = instanceManager
        if (im != null) {
            im.registerModel(task.modelId, task.meshDataList, task.maxInstances)
        }
        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processBatchBuild(task: BuildRegionBatchMeshes) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return  // Region removed, task irrelevant

        val batchKey = task.batchKey as? BatchKey
        @Suppress("UNCHECKED_CAST")
        val geometries = task.geometries as? List<TransformedGeometry>

        if (batchKey != null && geometries != null) {
            val batcher = StaticBatcher()
            for (geo in geometries) {
                batcher.addGeometry(batchKey, geo)
            }
            batcher.buildMeshes()

            // Merge into region's batch meshes
            val existing = regionData.batchMeshes?.toMutableMap() ?: mutableMapOf()
            existing.putAll(batcher.getAllMeshes())
            regionData.batchMeshes = existing
        }
        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processMergedTerrainBuild(task: BuildMergedTerrain) {
        mergedOpaqueTerrain?.cleanup()
        mergedAlphaTerrain?.cleanup()

        task.opaqueData?.let { data ->
            mergedOpaqueTerrain = Mesh.createWithAtlasTransform(
                data.positions, data.colors, data.texCoords, data.normals,
                data.atlasTransforms, data.indices
            )
        }

        task.alphaData?.let { data ->
            mergedAlphaTerrain = Mesh.createWithAtlasAndAlpha(
                data.positions, data.colors, data.texCoords, data.normals,
                data.alphas!!, data.atlasTransforms, data.indices
            )
        }
    }

    // ========================================================================
    // Query API
    // ========================================================================

    /**
     * Get loading progress snapshot.
     */
    fun getLoadProgress(): LoadProgress {
        val total = (renderDistance * 2 + 1) * (renderDistance * 2 + 1)
        val ready = regions.values.count { it.state == RegionState.READY }
        val cpuDone = regions.values.count { it.state == RegionState.GPU_PENDING || it.state == RegionState.READY }
        val pending = gpuUploadQueue.pendingCount()

        return LoadProgress(
            totalRegions = total,
            regionsReady = ready,
            regionsCpuDone = cpuDone,
            pendingGpuTasks = pending,
            isInitialLoad = ready == 0 && total > 0,
            phase = when {
                total == 0 -> LoadPhase.IDLE
                ready >= total -> LoadPhase.DONE
                pending > 0 -> LoadPhase.GPU_UPLOAD
                cpuDone < total -> LoadPhase.OBJECTS
                else -> LoadPhase.TERRAIN
            }
        )
    }

    fun isFullyLoaded(): Boolean = getLoadProgress().isComplete

    /**
     * Get all ready context regions for rendering.
     */
    fun getReadyContextRegions(): List<AsyncRegionData> {
        val center = centerKey ?: return emptyList()
        return regions.values.filter { it.state == RegionState.READY && it.key != center }
    }

    /**
     * Get the model matrix for a region.
     */
    fun getRegionModelMatrix(key: RegionKey): Matrix4f {
        val tileSize = RegionManager.TILE_SIZE
        return Matrix4f().identity().translate(
            key.regionX * 64 * tileSize, 0f, -key.regionY * 64 * tileSize
        )
    }

    // ========================================================================
    // Render Distance Changes
    // ========================================================================

    /**
     * Change render distance asynchronously.
     * Computes a transition plan: keeps existing regions, loads new ones, unloads far ones.
     */
    fun setRenderDistanceAsync(newDistance: Int) {
        val center = centerKey ?: return
        val oldDistance = renderDistance
        renderDistance = newDistance.coerceIn(0, RegionManager.MAX_RENDER_DISTANCE)
        if (renderDistance == oldDistance) return

        val currentKeys = regions.keys.toSet()
        val plan = RegionTransitionPlan.compute(currentKeys, center.regionX, center.regionY, renderDistance)

        // Unload regions no longer in view
        for (key in plan.regionsToUnload) {
            unloadRegion(key)
        }

        // Load new regions
        for (key in plan.regionsToLoad) {
            if (key != center) {
                loadRegionAsync(key, centerPlane)
            }
        }
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    private fun unloadRegion(key: RegionKey) {
        val data = regions.remove(key) ?: return
        data.loadJob?.cancel()
        gpuUploadQueue.cancelRegion(key.toLong())

        // Clean up GPU resources on main thread
        data.terrainMeshes?.cleanup()
        data.batchMeshes?.values?.forEach { it.cleanup() }
        data.state = RegionState.UNLOADING
    }

    private fun cancelAllLoading() {
        loadingScope.coroutineContext.cancelChildren()
        gpuUploadQueue.cancelAll()

        for ((_, data) in regions) {
            data.loadJob?.cancel()
            data.terrainMeshes?.cleanup()
            data.batchMeshes?.values?.forEach { it.cleanup() }
        }
        regions.clear()
        registeredModels.clear()
    }

    fun cleanup() {
        cancelAllLoading()

        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null

        syncRegionManager.cleanup()
        instanceManager = null
        centerObjects = emptyList()
        centerTerrainData = null
        centerMapObjects = emptyList()
        centerRegionData = null
    }

    /**
     * Full cleanup including coroutine scope cancellation.
     * Call when the viewer is being destroyed.
     */
    fun destroy() {
        cleanup()
        loadingScope.cancel()
    }

    // ========================================================================
    // Delegate Methods (from RegionManager)
    // ========================================================================

    fun getCurrentRegionX(): Int = centerKey?.regionX ?: -1
    fun getCurrentRegionY(): Int = centerKey?.regionY ?: -1
    fun getCurrentPlane(): Int = centerPlane

    fun hasBridgeFlag(localX: Int, localY: Int): Boolean = syncRegionManager.hasBridgeFlag(localX, localY)
    fun getModelIdForInstance(obj: ObjectInstance): Int = syncRegionManager.getModelIdForInstance(obj)

    val objectsByTile get() = syncRegionManager.objectsByTile

    fun invalidateMergedTerrain() {
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null
    }

    fun buildMergedAtlasTerrain() {
        val atlas = terrainAtlas ?: return

        // Collect terrain mesh data from center + all READY async regions
        data class RegionMeshEntry(
            val data: TerrainLoader.TerrainMeshData,
            val rx: Int,
            val ry: Int
        )

        val entries = mutableListOf<RegionMeshEntry>()

        // Center region - re-compute from cache (sync manager owns center data)
        val center = centerKey ?: return
        val centerTd = TerrainLoader.loadRegion(cache, center.regionX, center.regionY)
        if (centerTd != null) {
            val neighbors = TerrainLoader.buildNeighborData(cache, center.regionX, center.regionY)
            val meshData = TerrainLoader.computeTerrainMeshData(centerTd, centerPlane, neighbors)
            entries.add(RegionMeshEntry(meshData, center.regionX, center.regionY))
        }

        // Async context regions - use already-computed mesh data
        for ((key, data) in regions) {
            if (key == center) continue
            if (data.state != RegionState.READY) continue
            val meshData = data.terrainMeshData ?: continue
            entries.add(RegionMeshEntry(meshData, key.regionX, key.regionY))
        }

        if (entries.isEmpty()) return

        val tileSize = RegionManager.TILE_SIZE

        // Accumulate opaque and alpha geometry
        val allOpaquePositions = mutableListOf<FloatArray>()
        val allOpaqueColors = mutableListOf<FloatArray>()
        val allOpaqueTexCoords = mutableListOf<FloatArray>()
        val allOpaqueNormals = mutableListOf<FloatArray>()
        val allOpaqueAtlasTransforms = mutableListOf<FloatArray>()
        val allOpaqueIndices = mutableListOf<IntArray>()
        var opaqueVertexOffset = 0

        val allAlphaPositions = mutableListOf<FloatArray>()
        val allAlphaColors = mutableListOf<FloatArray>()
        val allAlphaTexCoords = mutableListOf<FloatArray>()
        val allAlphaNormals = mutableListOf<FloatArray>()
        val allAlphaAlphas = mutableListOf<FloatArray>()
        val allAlphaAtlasTransforms = mutableListOf<FloatArray>()
        val allAlphaIndices = mutableListOf<IntArray>()
        var alphaVertexOffset = 0

        for (entry in entries) {
            val offsetX = entry.rx * 64 * tileSize
            val offsetZ = -entry.ry * 64 * tileSize

            fun offsetPositions(positions: FloatArray): FloatArray {
                val result = positions.copyOf()
                var i = 0
                while (i < result.size) {
                    result[i] += offsetX
                    result[i + 2] += offsetZ
                    i += 3
                }
                return result
            }

            fun offsetIndices(indices: IntArray, offset: Int): IntArray {
                if (offset == 0) return indices
                val result = IntArray(indices.size)
                for (j in indices.indices) {
                    result[j] = indices[j] + offset
                }
                return result
            }

            val meshDataEntry = entry.data

            // Color mesh to opaque pool
            meshDataEntry.colorArrays?.let { c ->
                if (c.positions.isNotEmpty() && c.atlasTransforms != null && c.texCoords != null) {
                    val vertCount = c.positions.size / 3
                    allOpaquePositions.add(offsetPositions(c.positions))
                    allOpaqueColors.add(c.colors)
                    allOpaqueTexCoords.add(c.texCoords)
                    allOpaqueNormals.add(c.normals)
                    allOpaqueAtlasTransforms.add(c.atlasTransforms)
                    allOpaqueIndices.add(offsetIndices(c.indices, opaqueVertexOffset))
                    opaqueVertexOffset += vertCount
                }
            }

            // Textured meshes to opaque pool
            for (t in meshDataEntry.texturedArrays) {
                if (t.positions.isNotEmpty() && t.atlasTransforms != null) {
                    val vertCount = t.positions.size / 3
                    allOpaquePositions.add(offsetPositions(t.positions))
                    allOpaqueColors.add(t.colors)
                    allOpaqueTexCoords.add(t.texCoords)
                    allOpaqueNormals.add(t.normals)
                    allOpaqueAtlasTransforms.add(t.atlasTransforms)
                    allOpaqueIndices.add(offsetIndices(t.indices, opaqueVertexOffset))
                    opaqueVertexOffset += vertCount
                }
            }

            // Alpha meshes to alpha pool
            for (a in meshDataEntry.alphaArrays) {
                if (a.positions.isNotEmpty() && a.atlasTransforms != null) {
                    val vertCount = a.positions.size / 3
                    allAlphaPositions.add(offsetPositions(a.positions))
                    allAlphaColors.add(a.colors)
                    allAlphaTexCoords.add(a.texCoords)
                    allAlphaNormals.add(a.normals)
                    allAlphaAlphas.add(a.alphas)
                    allAlphaAtlasTransforms.add(a.atlasTransforms)
                    allAlphaIndices.add(offsetIndices(a.indices, alphaVertexOffset))
                    alphaVertexOffset += vertCount
                }
            }
        }

        // Clean up old merged meshes
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null

        // Build merged opaque mesh
        if (opaqueVertexOffset > 0) {
            val positions = concatFloatArrays(allOpaquePositions)
            val colors = concatFloatArrays(allOpaqueColors)
            val texCoords = concatFloatArrays(allOpaqueTexCoords)
            val normals = concatFloatArrays(allOpaqueNormals)
            val atlasTransforms = concatFloatArrays(allOpaqueAtlasTransforms)
            val indices = concatIntArrays(allOpaqueIndices)

            mergedOpaqueTerrain = Mesh.createWithAtlasTransform(
                positions, colors, texCoords, normals, atlasTransforms, indices
            )
        }

        // Build merged alpha mesh
        if (alphaVertexOffset > 0) {
            val positions = concatFloatArrays(allAlphaPositions)
            val colors = concatFloatArrays(allAlphaColors)
            val texCoords = concatFloatArrays(allAlphaTexCoords)
            val normals = concatFloatArrays(allAlphaNormals)
            val alphas = concatFloatArrays(allAlphaAlphas)
            val atlasTransforms = concatFloatArrays(allAlphaAtlasTransforms)
            val indices = concatIntArrays(allAlphaIndices)

            mergedAlphaTerrain = Mesh.createWithAtlasAndAlpha(
                positions, colors, texCoords, normals, alphas, atlasTransforms, indices
            )
        }
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    private fun computeRegionPriority(key: RegionKey): Int {
        val center = centerKey ?: return Int.MAX_VALUE
        val dx = key.regionX - center.regionX
        val dy = key.regionY - center.regionY
        return dx * dx + dy * dy  // Distance squared = priority
    }

    private fun regionKeyFromLong(regionKeyLong: Long): RegionKey {
        val rx = (regionKeyLong shr 32).toInt()
        val ry = (regionKeyLong and 0xFFFFFFFFL).toInt()
        return RegionKey(rx, ry)
    }

    private fun resolveBatchKey(meshData: MeshData, atlas: TextureAtlas?): BatchKey {
        if (meshData.textureId == -1) return BatchKey.UNTEXTURED
        if (atlas != null) {
            val region = atlas.getRegion(meshData.textureId)
            if (region != null) {
                return BatchKey(textureId = -1, atlasPageIndex = region.atlasPageIndex)
            }
        }
        return BatchKey(textureId = meshData.textureId, atlasPageIndex = -1)
    }

    private fun remapTexCoordsToAtlas(
        texCoords: FloatArray?,
        textureId: Int,
        atlas: TextureAtlas
    ): FloatArray? {
        if (texCoords == null) return null
        val region = atlas.getRegion(textureId) ?: return texCoords
        val remapped = FloatArray(texCoords.size)
        val uScale = region.u1 - region.u0
        val vScale = region.v1 - region.v0
        var i = 0
        while (i < texCoords.size - 1) {
            remapped[i] = region.u0 + texCoords[i] * uScale
            remapped[i + 1] = region.v0 + texCoords[i + 1] * vScale
            i += 2
        }
        return remapped
    }

    private fun concatFloatArrays(arrays: List<FloatArray>): FloatArray {
        val totalSize = arrays.sumOf { it.size }
        val result = FloatArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            System.arraycopy(arr, 0, result, offset, arr.size)
            offset += arr.size
        }
        return result
    }

    private fun concatIntArrays(arrays: List<IntArray>): IntArray {
        val totalSize = arrays.sumOf { it.size }
        val result = IntArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            System.arraycopy(arr, 0, result, offset, arr.size)
            offset += arr.size
        }
        return result
    }
}
