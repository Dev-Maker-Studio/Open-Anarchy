package org.darkan.tools.engine.region

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import org.darkan.tools.engine.spatial.AABB
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

enum class RegionState {
    NOT_LOADED,
    CPU_LOADING,
    GPU_PENDING,
    READY,
    UNLOADING
}

class AsyncRegionData(
    val key: RegionKey,
    @Volatile var state: RegionState = RegionState.NOT_LOADED,
    var terrainMeshData: TerrainLoader.TerrainMeshData? = null,
    var objectInstances: List<ObjectInstance> = emptyList(),
    var modelMeshData: Map<Int, List<MeshData>> = emptyMap(),
    var displacedMeshData: Map<Int, List<MeshData>> = emptyMap(),
    var terrainMeshes: TerrainLoader.TerrainMeshes? = null,
    var batchMeshes: Map<BatchKey, Mesh>? = null,
    var batches: RegionBatches = RegionBatches.EMPTY,
    var objectGeometry: RegionObjectGeometry? = null,
    var batchSimulations: List<BatchGranularitySimulation> = emptyList(),
    var pendingGpuTasks: AtomicInteger = AtomicInteger(0),
    var loadJob: Job? = null
)

class RegionObjectGeometry(
    val bounds: FloatArray,
    val triangles: IntArray,
    val drawableCount: Int,
    val totalTriangles: Long
) {
    val count: Int get() = triangles.size

    companion object {
        val EMPTY = RegionObjectGeometry(FloatArray(0), IntArray(0), 0, 0L)
    }
}

class BatchGranularitySimulation(
    val gridTiles: Int,
    val bounds: FloatArray,
    val triangles: IntArray
)

class BatchDraw(
    val key: BatchKey,
    val mesh: Mesh,
    val bounds: AABB,
    val rangeFirst: IntArray = intArrayOf(0),
    val rangeCount: IntArray = intArrayOf(mesh.vertexCount),
    val rangeBounds: FloatArray = FloatArray(6) { Float.NaN }
) {
    val rangeCullable: BooleanArray = BooleanArray(rangeFirst.size) { i ->
        val o = i * 6
        rangeBounds[o].isFinite() && rangeBounds[o + 1].isFinite() && rangeBounds[o + 2].isFinite() &&
            rangeBounds[o + 3].isFinite() && rangeBounds[o + 4].isFinite() && rangeBounds[o + 5].isFinite() &&
            rangeBounds[o] <= rangeBounds[o + 3] && rangeBounds[o + 1] <= rangeBounds[o + 4] &&
            rangeBounds[o + 2] <= rangeBounds[o + 5]
    }

    val rangesCullable: Boolean = rangeFirst.size > 1 && rangeCullable.all { it }

    val cullable: Boolean = bounds.isValid() &&
            bounds.min.x.isFinite() && bounds.min.y.isFinite() && bounds.min.z.isFinite() &&
            bounds.max.x.isFinite() && bounds.max.y.isFinite() && bounds.max.z.isFinite()
}

class RegionBatches(val draws: List<BatchDraw>) {

    val bounds: FloatArray = FloatArray(draws.size * 6)

    val cullable: BooleanArray = BooleanArray(draws.size)

    val union: AABB?

    init {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var allCullable = draws.isNotEmpty()
        for (i in draws.indices) {
            val draw = draws[i]
            val b = draw.bounds
            val o = i * 6
            bounds[o] = b.min.x
            bounds[o + 1] = b.min.y
            bounds[o + 2] = b.min.z
            bounds[o + 3] = b.max.x
            bounds[o + 4] = b.max.y
            bounds[o + 5] = b.max.z
            cullable[i] = draw.cullable
            if (!draw.cullable) {
                allCullable = false
            } else {
                if (b.min.x < minX) minX = b.min.x
                if (b.min.y < minY) minY = b.min.y
                if (b.min.z < minZ) minZ = b.min.z
                if (b.max.x > maxX) maxX = b.max.x
                if (b.max.y > maxY) maxY = b.max.y
                if (b.max.z > maxZ) maxZ = b.max.z
            }
        }
        union = if (allCullable) AABB(Vector3f(minX, minY, minZ), Vector3f(maxX, maxY, maxZ)) else null
    }

    companion object {
        val EMPTY = RegionBatches(emptyList())
    }
}

class MergedTerrainRange(
    val key: RegionKey,
    val bounds: AABB,
    val opaqueFirstIndex: Int,
    val opaqueIndexCount: Int,
    val alphaFirstIndex: Int,
    val alphaIndexCount: Int
)

class AsyncRegionManager(
    private val cache: Cache
) {
    private val gpuUploadQueue = GpuUploadQueue()
    private val loadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val regions = ConcurrentHashMap<RegionKey, AsyncRegionData>()

    var renderDistance: Int = 1
    var showRoofs: Boolean = true
    var objectAtlas: TextureAtlas? = null
    var terrainAtlas: TextureAtlas? = null

    private var centerKey: RegionKey? = null
    private var centerPlane: Int = 0

    private lateinit var syncRegionManager: RegionManager

    private val registeredModels = ConcurrentHashMap.newKeySet<Int>()

    private val simulateBatchGranularity: Boolean =
        System.getProperty("darkan.cullProbe", "false").toBoolean()

    private val displaceAudit: Boolean =
        System.getProperty("darkan.displaceAudit", "false").toBoolean()

    private val batchCellTiles: Int =
        (System.getProperty("darkan.batchCell")?.toIntOrNull() ?: DEFAULT_BATCH_CELL_TILES)
            .coerceAtLeast(1)

    private val batchCellWorldSize: Float = batchCellTiles * TILE_WORLD_SIZE

    private fun cellKeyFor(worldX: Float, worldZ: Float): Long {
        if (batchCellTiles >= 64) return 0L
        val cellX = floor(worldX / batchCellWorldSize).toLong()
        val cellZ = floor(worldZ / batchCellWorldSize).toLong()
        return ((cellX and 0x1FFFFFL) shl 21) or (cellZ and 0x1FFFFFL)
    }

    private val regionLoadSemaphore = Semaphore(
        (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    )

    private val sharedModelMeshData = ConcurrentHashMap<Int, List<MeshData>>()

    private val sharedModelCacheLimit = if (ParallelChunks.WORKER_COUNT <= 1) 0 else 16384

    var instanceManager: InstanceManager? = null
        private set

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

    var mergedTerrainRanges: List<MergedTerrainRange> = emptyList()
        private set

    var mergedTerrainRegions: Set<RegionKey> = emptySet()
        private set

    private var mergedEligibleSnapshot: Set<RegionKey> = emptySet()

    private var mergedTerrainSuppressed = false

    private var centerMergedMeshData: TerrainLoader.TerrainMeshData? = null
    private var centerMergedMeshKey: Pair<RegionKey, Int>? = null

    private val earlyContextKickoff: Boolean =
        System.getProperty("darkan.earlyContext", "false").toBoolean()


    fun initialize() {
        syncRegionManager = RegionManager(cache)
        syncRegionManager.initialize()
    }


    fun loadRegionSync(regionX: Int, regionY: Int, plane: Int) {
        cancelAllLoading()

        centerKey = RegionKey(regionX, regionY)
        centerPlane = plane

        RegionLoadProfiler.beginWave("loadRegionSync $regionX,$regionY plane=$plane rd=$renderDistance",
            (renderDistance * 2 + 1) * (renderDistance * 2 + 1))

        resetMergedTerrain()

        syncRegionManager.renderDistance = 0
        syncRegionManager.showRoofs = showRoofs
        syncRegionManager.objectAtlas = objectAtlas
        syncRegionManager.terrainAtlas = terrainAtlas
        syncRegionManager.loadRegion(regionX, regionY, plane)
        RegionLoadProfiler.markRegionCpuDone()

        centerObjects = syncRegionManager.centerObjects
        centerTerrainData = syncRegionManager.centerTerrainData
        centerMapObjects = syncRegionManager.centerMapObjects
        centerRegionData = syncRegionManager.centerRegionData
        instanceManager = syncRegionManager.instanceManager
        centerModelMatrix = syncRegionManager.centerModelMatrix
        registeredModels.clear()

        val centerData = AsyncRegionData(RegionKey(regionX, regionY), RegionState.READY)
        regions[centerData.key] = centerData

        if (renderDistance > 0) {
            loadContextRegionsAsync(regionX, regionY, plane)
        }
    }

    fun navigateToRegion(regionX: Int, regionY: Int, plane: Int) {
        val newCenter = RegionKey(regionX, regionY)
        val oldCenter = centerKey

        if (oldCenter == newCenter && plane == centerPlane) return

        val inProgressKeys = regions.entries
            .filter { it.value.state == RegionState.CPU_LOADING || it.value.state == RegionState.GPU_PENDING }
            .map { it.key }
        for (key in inProgressKeys) {
            val data = regions.remove(key)
            data?.loadJob?.cancel()
            gpuUploadQueue.cancelRegion(key.toLong())
            data?.terrainMeshes?.cleanup()
            data?.batchMeshes?.values?.forEach { it.cleanup() }
            data?.batches = RegionBatches.EMPTY
            data?.objectGeometry = null
        }

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

        val toRemove = regions.keys.filter { it !in desiredRegions }
        for (key in toRemove) {
            unloadRegion(key)
        }

        centerKey = newCenter
        centerPlane = plane

        RegionLoadProfiler.beginWave("navigateToRegion $regionX,$regionY plane=$plane rd=$renderDistance",
            (renderDistance * 2 + 1) * (renderDistance * 2 + 1))

        if (earlyContextKickoff) {
            registeredModels.clear()
            if (renderDistance > 0) {
                loadContextRegionsAsync(regionX, regionY, plane)
            }
        }

        syncRegionManager.renderDistance = 0
        syncRegionManager.showRoofs = showRoofs
        syncRegionManager.objectAtlas = objectAtlas
        syncRegionManager.terrainAtlas = terrainAtlas
        syncRegionManager.loadRegion(regionX, regionY, plane)
        RegionLoadProfiler.markRegionCpuDone()

        centerObjects = syncRegionManager.centerObjects
        centerTerrainData = syncRegionManager.centerTerrainData
        centerMapObjects = syncRegionManager.centerMapObjects
        centerRegionData = syncRegionManager.centerRegionData
        instanceManager = syncRegionManager.instanceManager
        centerModelMatrix = syncRegionManager.centerModelMatrix

        if (!earlyContextKickoff) {
            registeredModels.clear()
        }

        val centerData = AsyncRegionData(newCenter, RegionState.READY)
        regions[newCenter] = centerData

        resetMergedTerrain()

        buildMergedAtlasTerrain()

        if (!earlyContextKickoff && renderDistance > 0) {
            loadContextRegionsAsync(regionX, regionY, plane)
        }
    }

    private fun loadContextRegionsAsync(centerRegionX: Int, centerRegionY: Int, plane: Int) {
        val offsets = mutableListOf<RegionKey>()
        for (dx in -renderDistance..renderDistance) {
            for (dy in -renderDistance..renderDistance) {
                if (dx == 0 && dy == 0) continue
                val rx = centerRegionX + dx
                val ry = centerRegionY + dy
                if (rx in 0..255 && ry in 0..255) {
                    offsets.add(RegionKey(rx, ry))
                }
            }
        }

        offsets.sortBy { (it.regionX - centerRegionX) * (it.regionX - centerRegionX) +
                         (it.regionY - centerRegionY) * (it.regionY - centerRegionY) }

        for (key in offsets) {
            if (regions.containsKey(key) && regions[key]!!.state == RegionState.READY) {
                continue
            }
            loadRegionAsync(key, plane)
        }
    }

    private fun loadRegionAsync(key: RegionKey, plane: Int) {
        val regionData = AsyncRegionData(key, RegionState.CPU_LOADING)
        regions[key] = regionData

        val showRoofsLocal = showRoofs
        val priority = computeRegionPriority(key)
        val regionKeyLong = key.toLong()

        regionData.loadJob = loadingScope.launch {
            regionLoadSemaphore.withPermit {
                try {
                    runRegionPipeline(key, plane, regionData, showRoofsLocal, priority, regionKeyLong)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("[AsyncRegionManager] Error loading region ${key.regionX},${key.regionY}: ${e.message}")
                    e.printStackTrace()
                    RegionLoadProfiler.markRegionCpuDone()
                    regionData.pendingGpuTasks.set(0)
                    regionData.state = RegionState.READY
                }
            }
        }
    }

    private suspend fun runRegionPipeline(
        key: RegionKey,
        plane: Int,
        regionData: AsyncRegionData,
        showRoofsLocal: Boolean,
        priority: Int,
        regionKeyLong: Long
    ) {
        val terrainPhaseStart = System.nanoTime()
        val terrainData = TerrainLoader.loadRegion(cache, key.regionX, key.regionY)
        if (terrainData == null) {
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_TERRAIN, System.nanoTime() - terrainPhaseStart)
            RegionLoadProfiler.markRegionCpuDone()
            regionData.state = RegionState.READY
            return
        }

        val neighbors = TerrainLoader.buildNeighborData(cache, key.regionX, key.regionY)

        coroutineScope {
            launch {
                val terrainMeshData = TerrainLoader.computeTerrainMeshData(terrainData, plane, neighbors)
                regionData.terrainMeshData = terrainMeshData
                RegionLoadProfiler.record(RegionLoadProfiler.PHASE_TERRAIN, System.nanoTime() - terrainPhaseStart)

                regionData.pendingGpuTasks.incrementAndGet()
                gpuUploadQueue.enqueue(UploadTerrainMesh(
                    regionKey = regionKeyLong,
                    priority = priority,
                    meshData = terrainMeshData,
                    plane = plane
                ))
            }

            val objectPhaseStart = System.nanoTime()
            val ctxRegionData = RegionLoader.loadRegion(cache, key.regionX, key.regionY, useCache = false)
                ?: return@coroutineScope
            val ctxTiles = terrainData.tiles
            val allPlaneInstances = ObjectRenderer.createInstancesAllPlanes(
                objects = ctxRegionData.objects,
                regionX = key.regionX,
                regionY = key.regionY,
                tileHeights = { localX, localY, p ->
                    TerrainLoader.getExtendedTileHeightScaled(ctxTiles, neighbors, p, localX, localY)
                },
                rsHeightSampler = { localX, localY, p ->
                    TerrainLoader.getExtendedTileHeight(ctxTiles, neighbors, p, localX, localY)
                }
            )

            val instances = allPlaneInstances.values.flatten().let { insts ->
                if (!showRoofsLocal) insts.filter { it.mapObject.shape !in 12..21 }
                else insts
            }
            regionData.objectInstances = instances
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_OBJECTS, System.nanoTime() - objectPhaseStart)
            RegionLoadProfiler.noteObjects(instances.size)

            val modelPhaseStart = System.nanoTime()
            val modelMeshData = decodeModelMeshData(instances)
            regionData.modelMeshData = modelMeshData
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_MODELS, System.nanoTime() - modelPhaseStart)

            for ((combinedId, meshDataList) in modelMeshData) {
                if (registeredModels.add(combinedId)) {
                    regionData.pendingGpuTasks.incrementAndGet()
                    gpuUploadQueue.enqueue(RegisterModel(
                        regionKey = regionKeyLong,
                        priority = priority + 1,
                        modelId = combinedId,
                        meshDataList = meshDataList
                    ))
                }
            }

            val displacePhaseStart = System.nanoTime()
            val displacedMeshData = computeDisplacedMeshData(instances, modelMeshData, ctxTiles, neighbors, key.regionX, key.regionY)
            regionData.displacedMeshData = displacedMeshData
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_DISPLACE, System.nanoTime() - displacePhaseStart)

            val batchPhaseStart = System.nanoTime()
            val batchGeometry = buildBatchGeometry(instances, modelMeshData, displacedMeshData)
            val batcherData = batchGeometry.batches
            regionData.objectGeometry = batchGeometry.objects
            regionData.batchSimulations = batchGeometry.simulations
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_BATCH, System.nanoTime() - batchPhaseStart)

            val mergeStart = System.nanoTime()
            val batchEntries = batcherData.entries.toList()
            val mergedBatches = arrayOfNulls<MergedGeometry>(batchEntries.size)
            ParallelChunks.forEachChunk(batchEntries.size, minItemsPerChunk = 1) { from, to ->
                for (i in from until to) {
                    mergedBatches[i] = StaticBatcher.mergeGeometryData(batchEntries[i].value)
                }
            }
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_BATCH_MERGE, System.nanoTime() - mergeStart)

            for (i in batchEntries.indices) {
                val merged = mergedBatches[i] ?: continue
                regionData.pendingGpuTasks.incrementAndGet()
                gpuUploadQueue.enqueue(BuildRegionBatchMeshes(
                    regionKey = regionKeyLong,
                    priority = priority + 2,
                    batchKey = batchEntries[i].key,
                    merged = merged
                ))
            }
        }

        RegionLoadProfiler.markRegionCpuDone()
        regionData.state = RegionState.GPU_PENDING
    }


    fun drainGpuUploads(budgetNanos: Long) {
        val processed = gpuUploadQueue.drain(budgetNanos, processor = { task ->
            when (task) {
                is UploadTerrainMesh -> processTerrainUpload(task)
                is RegisterModel -> processModelRegistration(task)
                is BuildRegionBatchMeshes -> processBatchBuild(task)
                is UploadAtlasPage -> { }
                is BuildMergedTerrain -> processMergedTerrainBuild(task)
            }
        }, onCancelled = { task ->
            val key = regionKeyFromLong(task.regionKey)
            regions[key]?.pendingGpuTasks?.decrementAndGet()
        })

        RenderMetrics.gpuUploadTasksProcessed = processed
        RenderMetrics.pendingGpuTasks = gpuUploadQueue.pendingCount()

        for ((_, data) in regions) {
            if (data.state == RegionState.GPU_PENDING && data.pendingGpuTasks.get() <= 0) {
                data.state = RegionState.READY
            }
        }

        RenderMetrics.regionsLoading = regions.values.count {
            it.state == RegionState.CPU_LOADING || it.state == RegionState.GPU_PENDING
        }
        RenderMetrics.regionsReady = regions.values.count { it.state == RegionState.READY }
        RegionLoadProfiler.noteRegionsReady(RenderMetrics.regionsReady)
    }

    private fun processTerrainUpload(task: UploadTerrainMesh) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return

        val meshData = task.meshData as? TerrainLoader.TerrainMeshData
        if (meshData != null) {
            val start = System.nanoTime()
            val terrainMeshes = TerrainLoader.uploadTerrainMeshData(meshData)
            regionData.terrainMeshes = terrainMeshes
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_GL_TERRAIN, System.nanoTime() - start)
        }
        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processModelRegistration(task: RegisterModel) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return

        val im = instanceManager
        if (im != null) {
            val start = System.nanoTime()
            im.registerModel(task.modelId, task.meshDataList, task.maxInstances)
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_GL_REGISTER, System.nanoTime() - start)
        }
        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processBatchBuild(task: BuildRegionBatchMeshes) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return

        val batchKey = task.batchKey
        val merged = task.merged

        val start = System.nanoTime()
        val mesh = Mesh(merged.pack())
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_GL_BATCH, System.nanoTime() - start)
        val existing = regionData.batchMeshes?.toMutableMap() ?: mutableMapOf()
        existing[batchKey]?.cleanup()
        existing[batchKey] = mesh
        regionData.batchMeshes = existing

        val draws = regionData.batches.draws.filter { it.key != batchKey }.toMutableList()
        draws.add(
            BatchDraw(
                batchKey, mesh, merged.bounds,
                rangeFirst = merged.rangeFirst,
                rangeCount = merged.rangeCount,
                rangeBounds = merged.rangeBounds
            )
        )
        draws.sortWith(compareBy({ it.key != BatchKey.UNTEXTURED }, { it.key.atlasPageIndex }))
        regionData.batches = RegionBatches(draws)

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

    fun getReadyContextRegions(): List<AsyncRegionData> {
        val center = centerKey ?: return emptyList()
        return regions.values.filter { it.state == RegionState.READY && it.key != center }
    }

    fun getReadyRegion(regionX: Int, regionY: Int): AsyncRegionData? =
        regions[RegionKey(regionX, regionY)]?.takeIf { it.state == RegionState.READY }

    fun getRegionBounds(key: RegionKey): AABB = terrainBounds(key)

    private fun terrainBounds(key: RegionKey): AABB {
        val span = 64 * RegionManager.TILE_SIZE
        val minX = key.regionX * span
        val maxZ = -key.regionY * span
        return AABB(
            Vector3f(minX, -TERRAIN_Y_SPAN, maxZ - span),
            Vector3f(minX + span, TERRAIN_Y_SPAN, maxZ)
        )
    }

    fun getRegionModelMatrix(key: RegionKey): Matrix4f {
        val tileSize = RegionManager.TILE_SIZE
        return Matrix4f().identity().translate(
            key.regionX * 64 * tileSize, 0f, -key.regionY * 64 * tileSize
        )
    }


    fun setRenderDistanceAsync(newDistance: Int) {
        val center = centerKey ?: return
        val oldDistance = renderDistance
        renderDistance = newDistance.coerceIn(0, RegionManager.MAX_RENDER_DISTANCE)
        if (renderDistance == oldDistance) return

        val currentKeys = regions.keys.toSet()
        val plan = RegionTransitionPlan.compute(currentKeys, center.regionX, center.regionY, renderDistance)

        for (key in plan.regionsToUnload) {
            unloadRegion(key)
        }

        for (key in plan.regionsToLoad) {
            if (key != center) {
                loadRegionAsync(key, centerPlane)
            }
        }
    }


    private fun unloadRegion(key: RegionKey) {
        val data = regions.remove(key) ?: return
        data.loadJob?.cancel()
        gpuUploadQueue.cancelRegion(key.toLong())

        data.terrainMeshes?.cleanup()
        data.batchMeshes?.values?.forEach { it.cleanup() }
        data.batches = RegionBatches.EMPTY
        data.objectGeometry = null
        data.state = RegionState.UNLOADING
    }

    private fun cancelAllLoading() {
        loadingScope.coroutineContext.cancelChildren()
        gpuUploadQueue.cancelAll()

        for ((_, data) in regions) {
            data.loadJob?.cancel()
            data.terrainMeshes?.cleanup()
            data.batchMeshes?.values?.forEach { it.cleanup() }
            data.batches = RegionBatches.EMPTY
            data.objectGeometry = null
        }
        regions.clear()
        registeredModels.clear()
        sharedModelMeshData.clear()
        VertexDisplacer.clearCaches()
        RegionLoadProfiler.abortWave()
    }

    fun cleanup() {
        cancelAllLoading()

        resetMergedTerrain()

        syncRegionManager.cleanup()
        instanceManager = null
        centerObjects = emptyList()
        centerTerrainData = null
        centerMapObjects = emptyList()
        centerRegionData = null
    }

    fun destroy() {
        cleanup()
        loadingScope.cancel()
    }


    companion object {
        private const val TILE_WORLD_SIZE = 512f * CoordinateUtil.DEFAULT_SCALE

        private const val DEFAULT_BATCH_CELL_TILES = 32

        private val SIMULATED_GRID_TILES = intArrayOf(64, 32, 16, 8)

        private const val TERRAIN_Y_SPAN = 300f
    }

    fun getCurrentRegionX(): Int = centerKey?.regionX ?: -1
    fun getCurrentRegionY(): Int = centerKey?.regionY ?: -1
    fun getCurrentPlane(): Int = centerPlane

    fun hasBridgeFlag(localX: Int, localY: Int): Boolean = syncRegionManager.hasBridgeFlag(localX, localY)
    fun getModelIdForInstance(obj: ObjectInstance): Int = syncRegionManager.getModelIdForInstance(obj)

    val centerModelMeshData: Map<Int, List<MeshData>> get() = syncRegionManager.centerModelMeshData

    val unavailableModels: Set<Int> get() = syncRegionManager.unavailableModels

    val objectsByTile get() = syncRegionManager.objectsByTile

    private fun resetMergedTerrain() {
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null
        mergedTerrainRanges = emptyList()
        mergedTerrainRegions = emptySet()
        mergedEligibleSnapshot = emptySet()
        centerMergedMeshData = null
        centerMergedMeshKey = null
        mergedTerrainSuppressed = false
    }

    fun invalidateMergedTerrain() {
        resetMergedTerrain()
        mergedTerrainSuppressed = true
    }

    private fun mergedEligibleRegions(): Set<RegionKey> {
        val center = centerKey ?: return emptySet()
        val eligible = HashSet<RegionKey>()
        eligible.add(center)
        for ((key, data) in regions) {
            if (key == center) continue
            if (data.state != RegionState.READY) continue
            if (data.terrainMeshData == null) continue
            eligible.add(key)
        }
        return eligible
    }

    fun mergedTerrainNeedsRebuild(): Boolean {
        if (terrainAtlas == null || mergedTerrainSuppressed) return false
        return mergedEligibleSnapshot != mergedEligibleRegions()
    }

    fun buildMergedAtlasTerrain() {
        val mergedStart = System.nanoTime()
        try {
            buildMergedAtlasTerrainInternal()
        } finally {
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_MERGED_TERRAIN, System.nanoTime() - mergedStart)
        }
    }

    private fun buildMergedAtlasTerrainInternal() {
        if (terrainAtlas == null) return

        data class RegionMeshEntry(
            val data: TerrainLoader.TerrainMeshData,
            val key: RegionKey
        )

        val entries = mutableListOf<RegionMeshEntry>()

        val center = centerKey ?: return
        val eligible = mergedEligibleRegions()

        val centerCacheKey = Pair(center, centerPlane)
        var centerMeshData = if (centerMergedMeshKey == centerCacheKey) centerMergedMeshData else null
        if (centerMeshData == null) {
            val centerTd = TerrainLoader.loadRegion(cache, center.regionX, center.regionY)
            if (centerTd != null) {
                val neighbors = TerrainLoader.buildNeighborData(cache, center.regionX, center.regionY)
                centerMeshData = TerrainLoader.computeTerrainMeshData(centerTd, centerPlane, neighbors)
                centerMergedMeshData = centerMeshData
                centerMergedMeshKey = centerCacheKey
            }
        }
        if (centerMeshData != null) {
            entries.add(RegionMeshEntry(centerMeshData, center))
        }

        for ((key, data) in regions) {
            if (key == center) continue
            if (key !in eligible) continue
            val meshData = data.terrainMeshData ?: continue
            entries.add(RegionMeshEntry(meshData, key))
        }

        if (entries.isEmpty()) {
            mergedEligibleSnapshot = eligible
            return
        }

        val tileSize = RegionManager.TILE_SIZE

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

        val ranges = ArrayList<MergedTerrainRange>(entries.size)
        var opaqueIndexCursor = 0
        var alphaIndexCursor = 0

        for (entry in entries) {
            val offsetX = entry.key.regionX * 64 * tileSize
            val offsetZ = -entry.key.regionY * 64 * tileSize
            val opaqueRangeStart = opaqueIndexCursor
            val alphaRangeStart = alphaIndexCursor

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
                    opaqueIndexCursor += c.indices.size
                }
            }

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
                    opaqueIndexCursor += t.indices.size
                }
            }

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
                    alphaIndexCursor += a.indices.size
                }
            }

            ranges.add(MergedTerrainRange(
                key = entry.key,
                bounds = terrainBounds(entry.key),
                opaqueFirstIndex = opaqueRangeStart,
                opaqueIndexCount = opaqueIndexCursor - opaqueRangeStart,
                alphaFirstIndex = alphaRangeStart,
                alphaIndexCount = alphaIndexCursor - alphaRangeStart
            ))
        }

        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null

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

        mergedTerrainRanges = ranges
        mergedTerrainRegions = ranges.mapTo(HashSet(ranges.size * 2)) { it.key }
        mergedEligibleSnapshot = eligible
    }


    private suspend fun decodeModelMeshData(instances: List<ObjectInstance>): Map<Int, List<MeshData>> {
        if (instances.isEmpty()) return emptyMap()

        val uniqueIds = ArrayList<Int>()
        val uniqueOwners = ArrayList<ObjectInstance>()
        val seen = HashSet<Int>()
        for (obj in instances) {
            if (seen.add(obj.cachedCombinedModelId)) {
                uniqueIds.add(obj.cachedCombinedModelId)
                uniqueOwners.add(obj)
            }
        }

        val decoded = arrayOfNulls<List<MeshData>>(uniqueIds.size)
        val decodeCount = AtomicInteger(0)
        val hitCount = AtomicInteger(0)

        ParallelChunks.forEachChunk(uniqueIds.size) { from, to ->
            for (i in from until to) {
                val combinedId = uniqueIds[i]
                val cached = sharedModelMeshData[combinedId]
                if (cached != null) {
                    decoded[i] = cached
                    hitCount.incrementAndGet()
                    continue
                }

                val obj = uniqueOwners[i]
                val meshDataList = if (obj.modifications.hasModifications()) {
                    ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                } else {
                    ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                }
                decodeCount.incrementAndGet()

                val resolved = meshDataList ?: emptyList()
                if (sharedModelMeshData.size < sharedModelCacheLimit) {
                    decoded[i] = sharedModelMeshData.putIfAbsent(combinedId, resolved) ?: resolved
                } else {
                    decoded[i] = resolved
                }
            }
        }

        RegionLoadProfiler.noteModelDecode(decodeCount.get(), hitCount.get())

        val result = LinkedHashMap<Int, List<MeshData>>(uniqueIds.size * 2)
        for (i in uniqueIds.indices) {
            val meshDataList = decoded[i]
            if (meshDataList.isNullOrEmpty()) continue
            result[uniqueIds[i]] = meshDataList
        }
        return result
    }

    private suspend fun computeDisplacedMeshData(
        instances: List<ObjectInstance>,
        modelMeshData: Map<Int, List<MeshData>>,
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        regionX: Int,
        regionY: Int
    ): Map<Int, List<MeshData>> {
        if (instances.isEmpty()) return emptyMap()

        val field = VertexDisplacer.HeightField.from(tiles, neighbors)

        val auditObjects = AtomicLong(0)
        val auditFlatSkips = AtomicLong(0)
        val auditZeroMoved = AtomicLong(0)

        val chunkResults = ParallelChunks.mapChunks(instances.size) { from, to ->
            val local = LinkedHashMap<Int, List<MeshData>>()
            var objCount = 0L
            var flatSkips = 0L
            var meshCalls = 0L
            var vertices = 0L
            var zeroMoved = 0L

            for (objIndex in from until to) {
                val obj = instances[objIndex]
                if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) continue

                val baseMeshDataList = modelMeshData[obj.cachedCombinedModelId] ?: continue
                objCount++

                val definition = obj.definition
                val bounds = VertexDisplacer.modelBounds(obj.cachedCombinedModelId, baseMeshDataList)
                if (bounds == null || VertexDisplacer.footprintIsFlat(
                        bounds = bounds,
                        sceneX = obj.rsSceneX,
                        sceneY = obj.rsSceneY,
                        averageHeight = obj.rsAverageHeight,
                        plane = obj.mapObject.level,
                        field = field,
                        rsRotation = obj.rsRotation,
                        mirrored = obj.mirrored,
                        resizeX = definition.modelSizeX,
                        resizeY = definition.modelSizeZ,
                        resizeZ = definition.modelSizeY,
                        rsOffsetX = definition.offsetX,
                        rsOffsetZ = definition.offsetY
                    )
                ) {
                    flatSkips++
                    continue
                }

                val displaced = ArrayList<MeshData>(baseMeshDataList.size)
                var anyDisplaced = false

                for (meshData in baseMeshDataList) {
                    meshCalls++
                    vertices += meshData.positions.size / 3
                    val displacedMesh = VertexDisplacer.displaceVertices(
                        meshData = meshData,
                        sceneX = obj.rsSceneX,
                        sceneY = obj.rsSceneY,
                        averageHeight = obj.rsAverageHeight,
                        plane = obj.mapObject.level,
                        field = field,
                        rsRotation = obj.rsRotation,
                        mirrored = obj.mirrored,
                        resizeX = definition.modelSizeX,
                        resizeY = definition.modelSizeZ,
                        resizeZ = definition.modelSizeY,
                        rsOffsetX = definition.offsetX,
                        rsOffsetZ = definition.offsetY
                    )

                    if (displacedMesh != null) {
                        displaced.add(displacedMesh)
                        anyDisplaced = true
                    } else {
                        displaced.add(meshData)
                    }
                }

                if (anyDisplaced) {
                    local[objIndex] = displaced
                } else {
                    zeroMoved++
                }
            }
            RegionLoadProfiler.noteDisplace(objCount, flatSkips, meshCalls, vertices, zeroMoved)
            auditObjects.addAndGet(objCount)
            auditFlatSkips.addAndGet(flatSkips)
            auditZeroMoved.addAndGet(zeroMoved)
            local
        }

        if (displaceAudit) {
            val zero = auditZeroMoved.get()
            println("[displace] region $regionX,$regionY considered=${auditObjects.get()} " +
                    "flat-skipped=${auditFlatSkips.get()} moved-nothing=$zero")
        }

        if (chunkResults.size == 1) return chunkResults[0]

        val merged = LinkedHashMap<Int, List<MeshData>>()
        for (chunk in chunkResults) {
            merged.putAll(chunk)
        }
        return merged
    }

    private suspend fun buildBatchGeometry(
        instances: List<ObjectInstance>,
        modelMeshData: Map<Int, List<MeshData>>,
        displacedMeshData: Map<Int, List<MeshData>>
    ): BatchGeometryResult {
        if (instances.isEmpty()) {
            return BatchGeometryResult(emptyMap(), RegionObjectGeometry.EMPTY, emptyList())
        }

        val atlas = objectAtlas

        val objectBounds = FloatArray(instances.size * 6)
        val objectTriangles = IntArray(instances.size)

        val objectMaterials: Array<HashMap<BatchKey, Int>?>? =
            if (simulateBatchGranularity) arrayOfNulls(instances.size) else null

        val chunkResults = ParallelChunks.mapChunks(instances.size) { from, to ->
            val transformer = GeometryTransformer()
            val tempMatrix = Matrix4f()
            val local = LinkedHashMap<BatchKey, MutableList<TransformedGeometry>>()

            for (objIndex in from until to) {
                val boundsOffset = objIndex * 6
                objectBounds[boundsOffset] = Float.POSITIVE_INFINITY
                objectBounds[boundsOffset + 1] = Float.POSITIVE_INFINITY
                objectBounds[boundsOffset + 2] = Float.POSITIVE_INFINITY
                objectBounds[boundsOffset + 3] = Float.NEGATIVE_INFINITY
                objectBounds[boundsOffset + 4] = Float.NEGATIVE_INFINITY
                objectBounds[boundsOffset + 5] = Float.NEGATIVE_INFINITY

                val obj = instances[objIndex]
                val meshDataList = displacedMeshData[objIndex]
                    ?: modelMeshData[obj.cachedCombinedModelId]
                if (meshDataList == null || meshDataList.isEmpty()) continue

                val cellKey = cellKeyFor(obj.worldPosition.x, obj.worldPosition.z)

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

                    val positions = transformed.positions
                    var minX = Float.POSITIVE_INFINITY
                    var minY = Float.POSITIVE_INFINITY
                    var minZ = Float.POSITIVE_INFINITY
                    var maxX = Float.NEGATIVE_INFINITY
                    var maxY = Float.NEGATIVE_INFINITY
                    var maxZ = Float.NEGATIVE_INFINITY
                    var p = 0
                    while (p < positions.size) {
                        val x = positions[p]
                        val y = positions[p + 1]
                        val z = positions[p + 2]
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (z < minZ) minZ = z
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                        if (z > maxZ) maxZ = z
                        p += 3
                    }
                    if (minX < objectBounds[boundsOffset]) objectBounds[boundsOffset] = minX
                    if (minY < objectBounds[boundsOffset + 1]) objectBounds[boundsOffset + 1] = minY
                    if (minZ < objectBounds[boundsOffset + 2]) objectBounds[boundsOffset + 2] = minZ
                    if (maxX > objectBounds[boundsOffset + 3]) objectBounds[boundsOffset + 3] = maxX
                    if (maxY > objectBounds[boundsOffset + 4]) objectBounds[boundsOffset + 4] = maxY
                    if (maxZ > objectBounds[boundsOffset + 5]) objectBounds[boundsOffset + 5] = maxZ

                    local.getOrPut(batchKey) { mutableListOf() }.add(
                        transformed.copy(
                            cellKey = cellKey,
                            bounds = floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
                        )
                    )

                    val pieceTriangles = transformed.indices.size / 3
                    objectTriangles[objIndex] += pieceTriangles
                    if (objectMaterials != null) {
                        val materials = objectMaterials[objIndex] ?: HashMap<BatchKey, Int>(4).also {
                            objectMaterials[objIndex] = it
                        }
                        materials[batchKey] = (materials[batchKey] ?: 0) + pieceTriangles
                    }
                }
            }
            local
        }

        var drawable = 0
        var total = 0L
        for (count in objectTriangles) {
            if (count > 0) {
                drawable++
                total += count
            }
        }
        val objects = RegionObjectGeometry(objectBounds, objectTriangles, drawable, total)
        val simulations = if (objectMaterials == null) emptyList() else {
            SIMULATED_GRID_TILES.map { simulateGranularity(it, objectBounds, objectTriangles, objectMaterials) }
        }

        if (chunkResults.size == 1) return BatchGeometryResult(chunkResults[0], objects, simulations)

        val merged = LinkedHashMap<BatchKey, MutableList<TransformedGeometry>>()
        for (chunk in chunkResults) {
            for ((batchKey, geometries) in chunk) {
                merged.getOrPut(batchKey) { mutableListOf() }.addAll(geometries)
            }
        }
        return BatchGeometryResult(merged, objects, simulations)
    }

    private class BatchGeometryResult(
        val batches: Map<BatchKey, MutableList<TransformedGeometry>>,
        val objects: RegionObjectGeometry,
        val simulations: List<BatchGranularitySimulation>
    )

    private fun simulateGranularity(
        gridTiles: Int,
        objectBounds: FloatArray,
        objectTriangles: IntArray,
        objectMaterials: Array<HashMap<BatchKey, Int>?>
    ): BatchGranularitySimulation {
        val cellWorldSize = gridTiles * TILE_WORLD_SIZE
        val boundsByKey = LinkedHashMap<Long, FloatArray>()
        val trianglesByKey = LinkedHashMap<Long, Int>()

        for (objIndex in objectTriangles.indices) {
            if (objectTriangles[objIndex] == 0) continue
            val materials = objectMaterials[objIndex] ?: continue
            val offset = objIndex * 6
            val centerX = (objectBounds[offset] + objectBounds[offset + 3]) * 0.5f
            val centerZ = (objectBounds[offset + 2] + objectBounds[offset + 5]) * 0.5f
            val cellX = floor(centerX / cellWorldSize).toInt()
            val cellZ = floor(centerZ / cellWorldSize).toInt()

            for ((batchKey, triangles) in materials) {
                val key = ((cellX.toLong() and 0xFFFFL) shl 48) or
                        ((cellZ.toLong() and 0xFFFFL) shl 32) or
                        ((batchKey.textureId.toLong() and 0xFFFFL) shl 16) or
                        (batchKey.atlasPageIndex.toLong() and 0xFFFFL)
                val box = boundsByKey.getOrPut(key) {
                    floatArrayOf(
                        Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
                    )
                }
                for (axis in 0..2) {
                    if (objectBounds[offset + axis] < box[axis]) box[axis] = objectBounds[offset + axis]
                    if (objectBounds[offset + 3 + axis] > box[3 + axis]) box[3 + axis] = objectBounds[offset + 3 + axis]
                }
                trianglesByKey[key] = (trianglesByKey[key] ?: 0) + triangles
            }
        }

        val count = boundsByKey.size
        val bounds = FloatArray(count * 6)
        val triangles = IntArray(count)
        var i = 0
        for ((key, box) in boundsByKey) {
            System.arraycopy(box, 0, bounds, i * 6, 6)
            triangles[i] = trianglesByKey[key] ?: 0
            i++
        }
        return BatchGranularitySimulation(gridTiles, bounds, triangles)
    }


    private fun computeRegionPriority(key: RegionKey): Int {
        val center = centerKey ?: return Int.MAX_VALUE
        val dx = key.regionX - center.regionX
        val dy = key.regionY - center.regionY
        return dx * dx + dy * dy
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
