package org.darkan.tools.cache.map

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.MeshData
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
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.floor

data class LoadedRegion(
    val regionX: Int,
    val regionY: Int,
    val regionId: Int,
    val data: RegionData,
    val terrainMeshesByPlane: Array<TerrainLoader.TerrainMeshes?>,
    val objectsByPlane: Map<Int, List<ObjectInstance>>,
    val chunk: RegionChunk,
    val displacedModelIds: MutableSet<Int> = mutableSetOf()
) {
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

data class MapSceneStats(
    val loadedRegions: Int,
    val visibleRegions: Int,
    val totalObjects: Int,
    val visibleObjects: Int,
    val loadedModels: Int,
    val cullingTimeMs: Float
)

class MapScene(
    private val cache: Cache,
    private val viewRadius: Int = 2,
    private val unloadRadius: Int = viewRadius + 1
) {
    private val loadedRegions = mutableMapOf<Int, LoadedRegion>()

    private val cullingSystem = CullingSystem(
        worldBounds = Rect(-100000f, -100000f, 100000f, 100000f),
        worldYMin = -1000f,
        worldYMax = 1000f
    )

    private val instanceManager = InstanceManager(maxInstancesPerMesh = 50000)

    private val registeredModels = mutableSetOf<Int>()

    private val displacedModelIds = IdentityHashMap<ObjectInstance, Int>()

    private var nextDisplacedModelId = Int.MIN_VALUE / 2

    private var centerRegionX = -1
    private var centerRegionY = -1

    var currentPlane = 0
        private set

    var showRoofs = true

    private var lastCullResult: CullResult? = null

    private val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
    private val regionSize = 64 * tileSize

    private fun hasBridgeFlag(regionData: RegionData, localX: Int, localY: Int): Boolean {
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return false
        val settingsIndex = MapDefinition.index(localX, localY, 1)
        return (regionData.settings[settingsIndex].toInt() and 0x2) == 0x2
    }

    private fun shouldRenderObject(sceneObj: SceneObject): Boolean {
        val isRoofShape = sceneObj.shape in 12..21
        if (!showRoofs && isRoofShape) return false

        return true
    }

    fun initialize() {
        if (!MapXteas.isLoaded()) {
            MapXteas.load()
        }

        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)
    }

    fun setPlane(plane: Int) {
        currentPlane = plane.coerceIn(0, 3)
    }

    fun update(cameraX: Float, cameraZ: Float) {
        val regionX = worldToRegionX(cameraX)
        val regionY = worldToRegionY(cameraZ)

        if (regionX == centerRegionX && regionY == centerRegionY) return

        centerRegionX = regionX
        centerRegionY = regionY

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

        val toUnload = loadedRegions.keys.filter { regionId ->
            val rx = regionId shr 8
            val ry = regionId and 0xff
            val dx = abs(rx - regionX)
            val dy = abs(ry - regionY)
            dx > unloadRadius || dy > unloadRadius
        }

        for (regionId in toUnload) {
            unloadRegion(regionId)
        }

        for (regionId in neededRegions) {
            if (!loadedRegions.containsKey(regionId)) {
                loadRegion(regionId)
            }
        }
    }

    fun cull(camera: Camera, aspectRatio: Float): CullResult {
        lastCullResult = cullingSystem.cull(camera, aspectRatio)
        return lastCullResult!!
    }

    fun renderObjects(
        instancedColorShader: ShaderProgram,
        instancedTexturedShader: ShaderProgram?,
        bindTexture: ((Int) -> Unit)? = null
    ) {
        val cullResult = lastCullResult ?: return
        renderObjectsInternal(instancedColorShader, instancedTexturedShader, cullResult, bindTexture)
    }

    fun renderTerrain(shader: ShaderProgram) {
        val cullResult = lastCullResult ?: return

        shader.bind()

        for (regionChunk in cullResult.visibleRegions) {
            val loaded = loadedRegions[regionChunk.regionId] ?: continue
            val terrainMeshes = loaded.terrainMeshesByPlane[currentPlane] ?: continue
            val colorMesh = terrainMeshes.colorMesh ?: continue

            val model = Matrix4f().identity()
                .translate(regionChunk.bounds.minX, 0f, regionChunk.bounds.minZ)

            shader.setUniform("model", model)
            colorMesh.render()
        }
    }

    private val tempModelMatrix = Matrix4f()

    private fun renderObjectsInternal(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram?,
        cullResult: CullResult,
        bindTexture: ((Int) -> Unit)?
    ) {
        instanceManager.prepareFrame()

        var addedInstances = 0
        for (obj in cullResult.visibleObjects) {
            val sceneObj = obj as? SceneObject ?: continue
            if (!shouldRenderObject(sceneObj)) continue

            tempModelMatrix.identity()
                .translate(sceneObj.position)

            if (sceneObj.heightAdjustment != 0f) {
                tempModelMatrix.translate(0f, sceneObj.heightAdjustment, 0f)
            }

            tempModelMatrix
                .translate(sceneObj.offset)
                .scale(sceneObj.scale)

            if (sceneObj.tiltX != 0f || sceneObj.tiltZ != 0f) {
                tempModelMatrix.rotateZ(sceneObj.tiltZ)
                tempModelMatrix.rotateX(sceneObj.tiltX)
            }

            tempModelMatrix.rotateY(sceneObj.rotation)

            if (sceneObj.mirrored) {
                tempModelMatrix.scale(1f, 1f, -1f)
            }

            if (instanceManager.addInstance(sceneObj.modelId, tempModelMatrix)) {
                addedInstances++
            }
        }

        instanceManager.uploadAll()

        if (texturedShader != null && bindTexture != null) {
            instanceManager.renderAll(colorShader, texturedShader) { materialId ->
                val textureId = TextureLoader.getTextureForMaterial(cache, materialId)
                bindTexture(textureId)
            }
        } else {
            instanceManager.renderUntextured(colorShader)
        }
    }

    private fun loadRegion(regionId: Int) {
        val regionX = regionId shr 8
        val regionY = regionId and 0xff

        val data = RegionLoader.loadRegion(cache, regionX, regionY) ?: return

        val terrainData = TerrainLoader.loadRegion(cache, regionX, regionY)

        val terrainMeshesByPlane = arrayOfNulls<TerrainLoader.TerrainMeshes>(4)
        for (plane in 0 until 4) {
            terrainMeshesByPlane[plane] = TerrainLoader.loadRegionMeshes(cache, regionX, regionY, plane)
        }

        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val objectsByPlane = ObjectRenderer.createInstancesAllPlanes(
            data.objects,
            regionX,
            regionY,
            tileHeights = { x, y, plane ->
                if (terrainData != null) {
                    TerrainLoader.getExtendedTileHeightScaled(terrainData.tiles, neighbors, plane, x, y)
                } else {
                    getTileHeight(data, x.coerceIn(0, 63), y.coerceIn(0, 63), plane)
                }
            },
            rsHeightSampler = if (terrainData != null) {
                { x, y, plane -> TerrainLoader.getExtendedTileHeight(terrainData.tiles, neighbors, plane, x, y) }
            } else {
                null
            }
        )

        for ((_, objects) in objectsByPlane) {
            registerObjectModels(objects)
        }

        val worldMinX = regionX * 64 * tileSize
        val worldMinZ = -regionY * 64 * tileSize - regionSize
        val bounds = Rect(worldMinX, worldMinZ, worldMinX + regionSize, worldMinZ + regionSize)

        val chunk = RegionChunk(
            regionId = regionId,
            regionX = regionX,
            regionY = regionY,
            bounds = bounds,
            yMin = -100f,
            yMax = 500f
        )

        val loaded = LoadedRegion(
            regionX = regionX,
            regionY = regionY,
            regionId = regionId,
            data = data,
            terrainMeshesByPlane = terrainMeshesByPlane,
            objectsByPlane = objectsByPlane,
            chunk = chunk
        )

        if (terrainData != null) {
            for ((plane, objects) in objectsByPlane) {
                registerDisplacedModels(objects, plane, terrainData, regionX, regionY, loaded)
            }
        }

        cullingSystem.registerRegion(chunk)

        for ((plane, objects) in objectsByPlane) {
            for (obj in objects) {
                val objBounds = AABB(
                    Vector3f(obj.worldPosition.x - 2f, obj.worldPosition.y - 2f, obj.worldPosition.z - 2f),
                    Vector3f(obj.worldPosition.x + 2f, obj.worldPosition.y + 2f, obj.worldPosition.z + 2f)
                )

                val combinedModelId = getModelIdForInstance(obj)
                cullingSystem.registerObject(SceneObject(
                    objectId = obj.mapObject.id,
                    modelId = combinedModelId,
                    bounds = objBounds,
                    position = obj.worldPosition,
                    regionId = regionId,
                    plane = plane,
                    shape = obj.mapObject.shape,
                    rotation = obj.rotation,
                    scale = obj.scale,
                    offset = obj.offset,
                    tiltX = obj.terrainAlignment.tiltX,
                    tiltZ = obj.terrainAlignment.tiltZ,
                    heightAdjustment = obj.terrainAlignment.heightAdjustment,
                    mirrored = obj.mirrored
                ))
            }
        }

        loadedRegions[regionId] = loaded
    }

    private fun unloadRegion(regionId: Int) {
        val loaded = loadedRegions.remove(regionId) ?: return

        for (displacedId in loaded.displacedModelIds) {
            instanceManager.unregisterModel(displacedId)
            registeredModels.remove(displacedId)
        }

        for ((_, objects) in loaded.objectsByPlane) {
            for (obj in objects) {
                displacedModelIds.remove(obj)
            }
        }

        loaded.cleanup()

        cullingSystem.unregisterRegion(regionId)
    }

    private fun registerObjectModels(objects: List<ObjectInstance>) {
        for (obj in objects) {
            val combinedId = ObjectRenderer.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)
            if (registeredModels.contains(combinedId)) continue

            val meshDataList = if (obj.modifications.hasModifications()) {
                ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
            } else {
                ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
            }
            if (meshDataList != null && meshDataList.isNotEmpty()) {
                instanceManager.registerModel(combinedId, meshDataList)
                registeredModels.add(combinedId)
            }
        }
    }

    private fun registerDisplacedModels(
        objects: List<ObjectInstance>,
        plane: Int,
        terrainData: TerrainLoader.RegionData,
        regionX: Int,
        regionY: Int,
        loadedRegion: LoadedRegion
    ) {
        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val field = VertexDisplacer.HeightField.from(terrainData.tiles, neighbors)

        for (obj in objects) {
            if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) {
                continue
            }

            val baseMeshDataList = if (obj.modifications.hasModifications()) {
                ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
            } else {
                ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
            }
            if (baseMeshDataList == null || baseMeshDataList.isEmpty()) continue

            val displacedMeshDataList = mutableListOf<MeshData>()
            var anyDisplaced = false

            for (meshData in baseMeshDataList) {
                val displaced = VertexDisplacer.displaceVertices(
                    meshData = meshData,
                    sceneX = obj.rsSceneX,
                    sceneY = obj.rsSceneY,
                    averageHeight = obj.rsAverageHeight,
                    plane = plane,
                    field = field,
                    rsRotation = obj.rsRotation,
                    mirrored = obj.mirrored,
                    resizeX = obj.definition.modelSizeX,
                    resizeY = obj.definition.modelSizeZ,
                    resizeZ = obj.definition.modelSizeY,
                    rsOffsetX = obj.definition.offsetX,
                    rsOffsetZ = obj.definition.offsetY
                )

                if (displaced != null) {
                    displacedMeshDataList.add(displaced)
                    anyDisplaced = true
                } else {
                    displacedMeshDataList.add(meshData)
                }
            }

            if (!anyDisplaced) {
                continue
            }

            val displacedId = nextDisplacedModelId++
            instanceManager.registerModel(displacedId, displacedMeshDataList)
            registeredModels.add(displacedId)
            loadedRegion.displacedModelIds.add(displacedId)

            displacedModelIds[obj] = displacedId
        }
    }

    private fun getModelIdForInstance(obj: ObjectInstance): Int {
        val displacedId = displacedModelIds[obj]
        if (displacedId != null) return displacedId
        return ObjectRenderer.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)
    }

    private fun getTileHeight(data: RegionData, localX: Int, localY: Int, plane: Int): Float {
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return 0f

        val tileInfo = RegionLoader.getTileInfo(data.tiles, localX, localY, plane)
        return tileInfo.height * 32f * CoordinateUtil.DEFAULT_SCALE
    }

    private fun worldToRegionX(worldX: Float): Int {
        return floor(worldX / regionSize).toInt().coerceIn(0, 255)
    }

    private fun worldToRegionY(worldZ: Float): Int {
        return floor(-worldZ / regionSize).toInt().coerceIn(0, 255)
    }

    fun jumpToRegion(regionX: Int, regionY: Int): Vector3f {
        val worldX = regionX * regionSize + regionSize / 2
        val worldZ = -(regionY * regionSize + regionSize / 2)
        update(worldX, worldZ)
        return Vector3f(worldX, 50f, worldZ)
    }

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

    fun getLoadedRegionIds(): List<Int> = loadedRegions.keys.toList()

    fun isRegionLoaded(regionX: Int, regionY: Int): Boolean {
        return loadedRegions.containsKey((regionX shl 8) or regionY)
    }

    fun cleanup() {
        for (region in loadedRegions.values) {
            region.cleanup()
        }
        loadedRegions.clear()
        cullingSystem.clear()
        instanceManager.cleanup()
        registeredModels.clear()
        displacedModelIds.clear()
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
    }
}
