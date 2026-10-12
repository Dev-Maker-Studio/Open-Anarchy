package org.darkan.tools.engine.region

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.VertexDisplacer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.async.ParallelChunks
import org.darkan.tools.engine.render.BatchKey
import org.darkan.tools.engine.render.GeometryTransformer
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.StaticBatcher
import org.darkan.tools.engine.render.TextureAtlas
import org.darkan.tools.engine.render.TexturePixelProvider
import org.darkan.tools.engine.render.withReversedWinding
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject

class RegionManager(
    private val cache: Cache
) {

    var renderDistance: Int = 1
        set(value) {
            val clamped = value.coerceIn(0, MAX_RENDER_DISTANCE)
            if (field != clamped) {
                field = clamped
                needsReload = true
            }
        }

    var showRoofs: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                needsReload = true
            }
        }

    var debugContextInstanced: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                needsReload = true
            }
        }


    private var currentRegionX = -1
    private var currentRegionY = -1
    private var currentPlane = 0
    private var needsReload = false

    var centerObjects: List<ObjectInstance> = emptyList()
        private set

    var centerTerrainData: TerrainLoader.RegionData? = null
        private set

    var centerMapObjects: List<MapObject> = emptyList()
        private set

    var centerRegionData: RegionData? = null
        private set

    var instanceManager: InstanceManager? = null
        private set

    private val centerModelMeshDataInternal = mutableMapOf<Int, List<MeshData>>()

    val centerModelMeshData: Map<Int, List<MeshData>>
        get() = centerModelMeshDataInternal

    var contextObjectBatcher: StaticBatcher? = null
        private set

    var contextObjectInstances: List<ObjectInstance> = emptyList()
        private set

    val contextRegions = mutableListOf<LoadedContextRegion>()

    val centerModelMatrix = Matrix4f()
    val contextModelMatrices = mutableListOf<Matrix4f>()

    var mergedOpaqueTerrain: Mesh? = null
        private set
    var mergedAlphaTerrain: Mesh? = null
        private set

    private val registeredModels = mutableSetOf<Int>()

    private val displaceAudit: Boolean =
        System.getProperty("darkan.displaceAudit", "false").toBoolean()

    private val auditConsidered = AtomicLong(0)
    private val auditFlatSkips = AtomicLong(0)
    private val auditZeroMoved = AtomicLong(0)

    private val displacedModels = IdentityHashMap<ObjectInstance, Int>()
    private var nextDisplacedId = Int.MIN_VALUE / 2

    private val mirroredModels = mutableMapOf<Int, Int>()

    val unavailableModels = mutableSetOf<Int>()

    val objectsByTile = mutableMapOf<Long, MutableList<ObjectInstance>>()

    var objectAtlas: TextureAtlas? = null

    var terrainAtlas: TextureAtlas? = null


    companion object {
        const val MAX_RENDER_DISTANCE = 5

        val TILE_SIZE = 512f * CoordinateUtil.DEFAULT_SCALE

        private const val PARALLEL_CHUNK_SIZE = 512
    }


    fun initialize() {
        if (!MapXteas.isLoaded()) {
            MapXteas.load()
        }
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)
    }

    fun getCurrentRegionX(): Int = currentRegionX
    fun getCurrentRegionY(): Int = currentRegionY
    fun getCurrentPlane(): Int = currentPlane

    fun isReloadPending(): Boolean = needsReload

    fun loadRegion(regionX: Int, regionY: Int, plane: Int) {
        cleanup()
        needsReload = false
        currentRegionX = regionX
        currentRegionY = regionY
        currentPlane = plane

        val terrainStart = System.nanoTime()
        val td = TerrainLoader.loadRegion(cache, regionX, regionY)
        val regionData = RegionLoader.loadRegion(cache, regionX, regionY)
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_CENTER_TERRAIN, System.nanoTime() - terrainStart)

        centerTerrainData = td
        centerRegionData = regionData
        centerMapObjects = regionData?.objects ?: emptyList()

        if (regionData != null && td != null) {
            val atlas = objectAtlas
            instanceManager = InstanceManager(
                maxInstancesPerMesh = 50000,
                objectAtlas = atlas,
                texturePixelProvider = if (atlas != null) {
                    TexturePixelProvider { textureId ->
                        TextureLoader.getTexturePixelsForMaterial(cache, textureId)
                    }
                } else null
            )

            val instancesStart = System.nanoTime()
            val tiles = td.tiles
            val centerNeighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
            val allPlaneInstances = ObjectRenderer.createInstancesAllPlanes(
                objects = regionData.objects,
                regionX = regionX,
                regionY = regionY,
                tileHeights = { localX, localY, p ->
                    TerrainLoader.getExtendedTileHeightScaled(tiles, centerNeighbors, p, localX, localY)
                },
                rsHeightSampler = { localX, localY, p ->
                    TerrainLoader.getExtendedTileHeight(tiles, centerNeighbors, p, localX, localY)
                }
            )
            centerObjects = allPlaneInstances.values.flatten().let { instances ->
                if (!showRoofs) instances.filter { it.mapObject.shape !in 12..21 }
                else instances
            }
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_CENTER_OBJECTS, System.nanoTime() - instancesStart)
            RegionLoadProfiler.noteObjects(centerObjects.size)

            val centerMeshData = runBlocking { decodeCenterModels(centerObjects) }

            registerCenterModels(centerObjects, centerMeshData)

            registerDisplacedModels(centerObjects, centerMeshData, td.tiles, centerNeighbors)

            registerMirroredVariants(centerObjects, centerMeshData)

            populateObjectSpatialIndex()
        }

        centerModelMatrix.identity().translate(
            regionX * 64 * TILE_SIZE, 0f, -regionY * 64 * TILE_SIZE
        )

        if (renderDistance > 0) {
            loadContextRegions(regionX, regionY, plane)
        }

        val atlas = objectAtlas
        if (atlas != null && atlas.getTextureCount() > 0) {
            atlas.upload()
        }
    }

    fun reloadRegion() {
        if (currentRegionX >= 0 && currentRegionY >= 0) {
            loadRegion(currentRegionX, currentRegionY, currentPlane)
        }
    }

    fun setPlane(plane: Int) {
        currentPlane = plane.coerceIn(0, 3)
        reloadRegion()
    }

    fun hasBridgeFlag(localX: Int, localY: Int): Boolean {
        val rd = centerRegionData ?: return false
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return false
        val settingsIndex = MapDefinition.index(localX, localY, 1)
        return (rd.settings[settingsIndex].toInt() and 0x2) == 0x2
    }

    fun getModelIdForInstance(obj: ObjectInstance): Int {
        displacedModels[obj]?.let { return it }
        if (obj.mirrored) mirroredModels[obj.cachedCombinedModelId]?.let { return it }
        return obj.cachedCombinedModelId
    }

    fun getContextOffsets(): List<IntArray> {
        val offsets = mutableListOf<IntArray>()
        for (dx in -renderDistance..renderDistance) {
            for (dy in -renderDistance..renderDistance) {
                if (dx == 0 && dy == 0) continue
                offsets.add(intArrayOf(dx, dy))
            }
        }
        return offsets
    }

    fun invalidateMergedTerrain() {
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null
    }

    fun buildMergedAtlasTerrain() {
        val atlas = terrainAtlas ?: return

        data class RegionMeshEntry(
            val data: TerrainLoader.TerrainMeshData,
            val rx: Int,
            val ry: Int
        )

        val entries = mutableListOf<RegionMeshEntry>()

        val centerTd = TerrainLoader.loadRegion(cache, currentRegionX, currentRegionY)
        if (centerTd != null) {
            val neighbors = TerrainLoader.buildNeighborData(cache, currentRegionX, currentRegionY)
            val meshData = TerrainLoader.computeTerrainMeshData(centerTd, currentPlane, neighbors)
            entries.add(RegionMeshEntry(meshData, currentRegionX, currentRegionY))
        }

        for (ctx in contextRegions) {
            val td = TerrainLoader.loadRegion(cache, ctx.regionX, ctx.regionY) ?: continue
            val neighbors = TerrainLoader.buildNeighborData(cache, ctx.regionX, ctx.regionY)
            val meshData = TerrainLoader.computeTerrainMeshData(td, currentPlane, neighbors)
            entries.add(RegionMeshEntry(meshData, ctx.regionX, ctx.regionY))
        }

        if (entries.isEmpty()) return

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
            val offsetX = entry.rx * 64 * TILE_SIZE
            val offsetZ = -entry.ry * 64 * TILE_SIZE

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

            val data = entry.data

            data.colorArrays?.let { c ->
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

            for (t in data.texturedArrays) {
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

            for (a in data.alphaArrays) {
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
    }

    fun cleanup() {
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null

        centerTerrainData = null
        centerRegionData = null
        centerMapObjects = emptyList()
        centerObjects = emptyList()
        objectsByTile.clear()

        instanceManager?.cleanup()
        instanceManager = null

        contextObjectBatcher?.cleanup()
        contextObjectBatcher = null
        contextObjectInstances = emptyList()

        for (ctx in contextRegions) {
            ctx.cleanup()
        }
        contextRegions.clear()
        contextModelMatrices.clear()

        registeredModels.clear()
        displacedModels.clear()
        mirroredModels.clear()
        unavailableModels.clear()
        centerModelMeshDataInternal.clear()
        nextDisplacedId = Int.MIN_VALUE / 2

        TerrainLoader.clearBatchedMeshCache()
    }


    private suspend fun decodeCenterModels(objects: List<ObjectInstance>): Map<Int, List<MeshData>> {
        if (objects.isEmpty()) return emptyMap()
        val decodeStart = System.nanoTime()

        val uniqueIds = ArrayList<Int>()
        val uniqueOwners = ArrayList<ObjectInstance>()
        val seen = HashSet<Int>()
        for (obj in objects) {
            if (seen.add(obj.cachedCombinedModelId)) {
                uniqueIds.add(obj.cachedCombinedModelId)
                uniqueOwners.add(obj)
            }
        }

        val decoded = arrayOfNulls<List<MeshData>>(uniqueIds.size)
        ParallelChunks.forEachChunk(uniqueIds.size) { from, to ->
            for (i in from until to) {
                val obj = uniqueOwners[i]
                decoded[i] = if (obj.modifications.hasModifications()) {
                    ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                } else {
                    ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                }
            }
        }

        val result = LinkedHashMap<Int, List<MeshData>>(uniqueIds.size * 2)
        unavailableModels.clear()
        for (i in uniqueIds.indices) {
            val meshDataList = decoded[i]
            if (meshDataList.isNullOrEmpty()) {
                unavailableModels.add(uniqueIds[i])
                continue
            }
            result[uniqueIds[i]] = meshDataList
        }
        if (unavailableModels.isNotEmpty()) {
            System.err.println(
                "[RegionManager] ${unavailableModels.size} of ${uniqueIds.size} models used by this " +
                        "region decoded to no geometry (data gap, not a decoder failure — run " +
                        "'tools faces <id>' for the reason): ${unavailableModels.sorted().take(16)}"
            )
        }
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_CENTER_MODELS, System.nanoTime() - decodeStart)
        RegionLoadProfiler.noteModelDecode(uniqueIds.size, 0)
        return result
    }

    private fun registerCenterModels(
        objects: List<ObjectInstance>,
        meshDataById: Map<Int, List<MeshData>>
    ) {
        val im = instanceManager ?: return
        val registerStart = System.nanoTime()
        for (obj in objects) {
            val combinedId = obj.cachedCombinedModelId
            if (registeredModels.contains(combinedId)) continue

            val meshDataList = meshDataById[combinedId] ?: continue
            im.registerModel(combinedId, meshDataList)
            registeredModels.add(combinedId)
            centerModelMeshDataInternal[combinedId] = meshDataList
        }
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_CENTER_REGISTER, System.nanoTime() - registerStart)
    }

    private fun registerMirroredVariants(
        objects: List<ObjectInstance>,
        meshDataById: Map<Int, List<MeshData>>
    ) {
        val im = instanceManager ?: return
        val registerStart = System.nanoTime()
        for (obj in objects) {
            if (!obj.mirrored || displacedModels.containsKey(obj)) continue
            val baseId = obj.cachedCombinedModelId
            if (mirroredModels.containsKey(baseId)) continue

            val meshDataList = meshDataById[baseId] ?: continue
            val mirroredId = nextDisplacedId++
            val meshes = meshDataList.withReversedWinding()
            im.registerModel(mirroredId, meshes)
            registeredModels.add(mirroredId)
            mirroredModels[baseId] = mirroredId
            centerModelMeshDataInternal[mirroredId] = meshes
        }
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_CENTER_REGISTER, System.nanoTime() - registerStart)
    }

    private suspend fun displaceChunked(
        objects: List<ObjectInstance>,
        meshDataById: Map<Int, List<MeshData>>,
        terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?
    ): List<LinkedHashMap<Int, List<MeshData>>> {
        val field = VertexDisplacer.HeightField.from(terrainTiles, neighbors)
        val result = ParallelChunks.mapChunks(objects.size) { from, to ->
            val local = LinkedHashMap<Int, List<MeshData>>()
            val stats = LongArray(3)
            for (objIndex in from until to) {
                val displaced = displaceObject(objects[objIndex], meshDataById, field, stats)
                if (displaced != null) local[objIndex] = displaced
            }
            RegionLoadProfiler.noteDisplace(stats[0], stats[1], 0, 0, stats[2])
            auditConsidered.addAndGet(stats[0])
            auditFlatSkips.addAndGet(stats[1])
            auditZeroMoved.addAndGet(stats[2])
            local
        }
        if (displaceAudit) {
            println("[displace] region $currentRegionX,$currentRegionY (centre) " +
                    "considered=${auditConsidered.getAndSet(0)} " +
                    "flat-skipped=${auditFlatSkips.getAndSet(0)} " +
                    "moved-nothing=${auditZeroMoved.getAndSet(0)}")
        } else {
            auditConsidered.set(0)
            auditFlatSkips.set(0)
            auditZeroMoved.set(0)
        }
        return result
    }

    private fun displaceObject(
        obj: ObjectInstance,
        meshDataById: Map<Int, List<MeshData>>,
        field: VertexDisplacer.HeightField,
        stats: LongArray? = null
    ): List<MeshData>? {
        if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) return null
        val baseMeshDataList = meshDataById[obj.cachedCombinedModelId] ?: return null
        stats?.let { it[0]++ }

        val definition = obj.definition
        val bounds = VertexDisplacer.modelBounds(obj.cachedCombinedModelId, baseMeshDataList) ?: return null
        if (VertexDisplacer.footprintIsFlat(
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
            stats?.let { it[1]++ }
            return null
        }

        val displaced = ArrayList<MeshData>(baseMeshDataList.size)
        var anyDisplaced = false
        for (meshData in baseMeshDataList) {
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
        if (!anyDisplaced) {
            stats?.let { it[2]++ }
            return null
        }
        return displaced
    }

    private fun registerDisplacedModels(
        objects: List<ObjectInstance>,
        meshDataById: Map<Int, List<MeshData>>,
        terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?
    ) {
        val im = instanceManager ?: return
        if (objects.isEmpty()) return

        val displaceStart = System.nanoTime()

        val chunkResults = runBlocking {
            displaceChunked(objects, meshDataById, terrainTiles, neighbors)
        }
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_CENTER_DISPLACE, System.nanoTime() - displaceStart)

        val registerStart = System.nanoTime()
        for (chunk in chunkResults) {
            for ((objIndex, displacedMeshDataList) in chunk) {
                val obj = objects[objIndex]
                val displacedId = nextDisplacedId++
                val meshes = if (obj.mirrored) displacedMeshDataList.withReversedWinding()
                else displacedMeshDataList
                im.registerModel(displacedId, meshes, maxInstances = 4)
                registeredModels.add(displacedId)
                displacedModels[obj] = displacedId
                centerModelMeshDataInternal[displacedId] = meshes
            }
        }
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_CENTER_REGISTER, System.nanoTime() - registerStart)
    }


    private class ContextRegionCpuData(
        val index: Int,
        val rx: Int,
        val ry: Int,
        val terrainMeshData: TerrainLoader.TerrainMeshData,
        val objectInstances: List<ObjectInstance>,
        val newModelMeshData: Map<Int, List<MeshData>>,
        val allModelMeshData: Map<Int, List<MeshData>>,
        val displacedMeshData: Map<Int, List<MeshData>>
    )

    private fun loadContextRegions(centerRegionX: Int, centerRegionY: Int, plane: Int) {
        loadContextRegionsParallel(centerRegionX, centerRegionY, plane)
    }

    private fun loadContextRegionsParallel(centerRegionX: Int, centerRegionY: Int, plane: Int) {
        val showRoofsLocal = showRoofs
        val alreadyRegistered = registeredModels.toSet()
        val offsets = getContextOffsets()

        val cpuResults: List<ContextRegionCpuData?> = runBlocking {
            offsets.indices.map { i ->
                async(Dispatchers.Default) {
                    val rx = centerRegionX + offsets[i][0]
                    val ry = centerRegionY + offsets[i][1]

                    if (rx < 0 || rx > 255 || ry < 0 || ry > 255) return@async null

                    val terrainData = TerrainLoader.loadRegion(cache, rx, ry) ?: return@async null
                    val ctxNeighbors = TerrainLoader.buildNeighborData(cache, rx, ry)
                    TerrainLoader.loadDefinitions(cache)
                    val terrainMeshData = TerrainLoader.computeTerrainMeshData(terrainData, plane, ctxNeighbors)

                    val ctxRegionData = RegionLoader.loadRegion(cache, rx, ry, useCache = false)
                    val ctxTiles = terrainData.tiles
                    var ctxObjectInstances: List<ObjectInstance> = emptyList()
                    val newModelMeshData = mutableMapOf<Int, List<MeshData>>()
                    val allModelMeshData = mutableMapOf<Int, List<MeshData>>()
                    val displacedMeshData = mutableMapOf<Int, List<MeshData>>()

                    if (ctxRegionData != null) {
                        val allPlaneInstances = ObjectRenderer.createInstancesAllPlanes(
                            objects = ctxRegionData.objects,
                            regionX = rx,
                            regionY = ry,
                            tileHeights = { localX, localY, p ->
                                TerrainLoader.getExtendedTileHeightScaled(ctxTiles, ctxNeighbors, p, localX, localY)
                            },
                            rsHeightSampler = { localX, localY, p ->
                                TerrainLoader.getExtendedTileHeight(ctxTiles, ctxNeighbors, p, localX, localY)
                            }
                        )

                        ctxObjectInstances = allPlaneInstances.values.flatten().let { instances ->
                            if (!showRoofsLocal) instances.filter { it.mapObject.shape !in 12..21 }
                            else instances
                        }

                        val combinedIdToModelIds = mutableMapOf<Int, List<Int>>()
                        for (obj in ctxObjectInstances) {
                            val combinedId = obj.cachedCombinedModelId

                            val firstSeen = combinedIdToModelIds[combinedId]
                            if (firstSeen != null) {
                                if (firstSeen != obj.modelIds) {
                                    println("[WARNING] Hash collision in loadContextRegionsParallel: " +
                                            "combinedId=$combinedId, first modelIds=$firstSeen, " +
                                            "this modelIds=${obj.modelIds} (objectId=${obj.mapObject.id}, " +
                                            "region=$rx,$ry)")
                                }
                            } else {
                                combinedIdToModelIds[combinedId] = obj.modelIds
                            }

                            if (allModelMeshData.containsKey(combinedId)) continue

                            val meshDataList = if (obj.modifications.hasModifications()) {
                                ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                            } else {
                                ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                            }
                            if (meshDataList != null && meshDataList.isNotEmpty()) {
                                allModelMeshData[combinedId] = meshDataList
                                if (!alreadyRegistered.contains(combinedId)) {
                                    newModelMeshData[combinedId] = meshDataList
                                }
                            }
                        }

                        val ctxField = VertexDisplacer.HeightField.from(ctxTiles, ctxNeighbors)
                        for ((objIndex, obj) in ctxObjectInstances.withIndex()) {
                            val displaced = displaceObject(obj, allModelMeshData, ctxField) ?: continue
                            displacedMeshData[objIndex] = displaced
                        }
                    }

                    ContextRegionCpuData(i, rx, ry, terrainMeshData, ctxObjectInstances,
                        newModelMeshData, allModelMeshData, displacedMeshData)
                }
            }.map { it.await() }
        }

        val allContextInstancesForDebug = if (debugContextInstanced) mutableListOf<ObjectInstance>() else null

        for (cpuData in cpuResults) {
            if (cpuData == null) continue

            val rx = cpuData.rx
            val ry = cpuData.ry

            val modelMatrix = Matrix4f().identity().translate(
                rx * 64 * TILE_SIZE, 0f, -ry * 64 * TILE_SIZE
            )
            contextModelMatrices.add(modelMatrix)

            val terrainMeshes = TerrainLoader.uploadTerrainMeshData(cpuData.terrainMeshData)

            val im = instanceManager
            if (im != null) {
                if (debugContextInstanced) {
                    for ((combinedId, meshDataList) in cpuData.allModelMeshData) {
                        if (!registeredModels.contains(combinedId)) {
                            im.registerModel(combinedId, meshDataList)
                            registeredModels.add(combinedId)
                        }
                    }

                    for ((objIndex, displacedMeshDataList) in cpuData.displacedMeshData) {
                        val obj = cpuData.objectInstances[objIndex]
                        val displacedId = nextDisplacedId++
                        im.registerModel(displacedId, displacedMeshDataList, maxInstances = 4)
                        registeredModels.add(displacedId)
                        displacedModels[obj] = displacedId
                    }
                } else {
                    for ((combinedId, meshDataList) in cpuData.newModelMeshData) {
                        if (!registeredModels.contains(combinedId)) {
                            im.registerModel(combinedId, meshDataList)
                            registeredModels.add(combinedId)
                        }
                    }
                }
            }

            contextRegions.add(LoadedContextRegion(rx, ry, terrainMeshes, cpuData.objectInstances))
            allContextInstancesForDebug?.addAll(cpuData.objectInstances)
        }

        if (debugContextInstanced) {
            contextObjectInstances = allContextInstancesForDebug ?: emptyList()
            println("[RegionManager] debugContextInstanced: ${contextObjectInstances.size} context objects " +
                    "stored for instanced rendering (no static batching)")
        } else {
            buildContextObjectBatches(cpuResults)
        }
    }


    private data class ContextBatchInput(
        val instances: List<ObjectInstance>,
        val allModelMeshData: Map<Int, List<MeshData>>,
        val displacedMeshData: Map<Int, List<MeshData>>
    )

    private fun buildContextObjectBatches(cpuResults: List<ContextRegionCpuData?>) {
        val inputs = mutableListOf<ContextBatchInput>()
        for (cpuData in cpuResults) {
            if (cpuData == null) continue
            inputs.add(ContextBatchInput(
                cpuData.objectInstances,
                cpuData.allModelMeshData,
                cpuData.displacedMeshData
            ))
        }
        buildContextObjectBatchesFromData(inputs)
    }

    private fun buildContextObjectBatchesFromData(allContextData: List<ContextBatchInput>) {
        contextObjectBatcher?.cleanup()
        contextObjectBatcher = null

        var totalObjects = 0
        for (input in allContextData) {
            totalObjects += input.instances.size
        }
        if (totalObjects == 0) return

        val batcher = StaticBatcher()
        val transformer = GeometryTransformer()
        val tempMatrix = Matrix4f()
        val atlas = objectAtlas

        var batchedCount = 0
        var skippedCount = 0

        val batchCombinedIdToModelIds = mutableMapOf<Int, List<Int>>()

        for (input in allContextData) {
            for ((objIndex, obj) in input.instances.withIndex()) {
                val combinedId = obj.cachedCombinedModelId

                val firstSeen = batchCombinedIdToModelIds[combinedId]
                if (firstSeen != null) {
                    if (firstSeen != obj.modelIds) {
                        println("[WARNING] Hash collision in buildContextObjectBatchesFromData: " +
                                "combinedId=$combinedId, first modelIds=$firstSeen, " +
                                "this modelIds=${obj.modelIds} (objectId=${obj.mapObject.id})")
                    }
                } else {
                    batchCombinedIdToModelIds[combinedId] = obj.modelIds
                }

                val meshDataList = input.displacedMeshData[objIndex]
                    ?: input.allModelMeshData[combinedId]
                if (meshDataList == null || meshDataList.isEmpty()) {
                    skippedCount++
                    continue
                }

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
                    batcher.addGeometry(batchKey, transformed)
                }

                batchedCount++
            }
        }

        if (batchedCount > 0) {
            batcher.buildMeshes()
            contextObjectBatcher = batcher

            val stats = batcher.getStats()
            println("[RegionManager] Static batched $batchedCount context objects into ${stats.batchCount} batches " +
                    "(${stats.totalVertices} vertices, ${stats.totalTriangles} triangles), skipped $skippedCount")
        }
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


    private fun packXY(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun populateObjectSpatialIndex() {
        objectsByTile.clear()
        for (obj in centerObjects) {
            val localTileX = obj.mapObject.x - currentRegionX * 64
            val localTileY = obj.mapObject.y - currentRegionY * 64

            val sizeX: Int
            val sizeY: Int
            if (obj.mapObject.rotation == 1 || obj.mapObject.rotation == 3) {
                sizeX = obj.definition.sizeY
                sizeY = obj.definition.sizeX
            } else {
                sizeX = obj.definition.sizeX
                sizeY = obj.definition.sizeY
            }

            for (dx in 0 until sizeX) {
                for (dy in 0 until sizeY) {
                    val tx = localTileX + dx
                    val ty = localTileY + dy
                    if (tx in 0..63 && ty in 0..63) {
                        objectsByTile.getOrPut(packXY(tx, ty)) { mutableListOf() }.add(obj)
                    }
                }
            }
        }
    }


    private fun concatFloatArrays(arrays: List<FloatArray>): FloatArray {
        val totalSize = arrays.sumOf { it.size }
        val result = FloatArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            arr.copyInto(result, offset)
            offset += arr.size
        }
        return result
    }

    private fun concatIntArrays(arrays: List<IntArray>): IntArray {
        val totalSize = arrays.sumOf { it.size }
        val result = IntArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            arr.copyInto(result, offset)
            offset += arr.size
        }
        return result
    }
}

class LoadedContextRegion(
    val regionX: Int,
    val regionY: Int,
    val terrainMeshes: TerrainLoader.TerrainMeshes,
    val objectInstances: List<ObjectInstance>
) {
    fun cleanup() {
        terrainMeshes.cleanup()
    }
}
