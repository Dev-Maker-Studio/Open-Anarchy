package org.darkan.tools.engine.input

import org.darkan.tools.cache.map.ObjectRenderer
import world.gregs.voidps.cache.definition.data.ModelDefinition
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import kotlin.math.floor

class TilePicker {

    data class SelectedTile(
        val regionX: Int,
        val regionY: Int,
        val localX: Int,
        val localY: Int,
        val plane: Int,
        val worldX: Float,
        val worldY: Float,
        val worldZ: Float
    )

    data class TileObject(
        val mapObject: MapObject,
        val definition: ObjectDefinition,
        val modelIds: List<Int>,
        val textureIds: Set<Int>
    )

    data class TileDetails(
        val tile: SelectedTile,
        val tileData: TerrainLoader.TileData?,
        val underlayDef: UnderlayDefinition?,
        val overlayDef: OverlayDefinition?,
        val cornerHeights: FloatArray,
        val cornerUVsOverlay: Array<FloatArray>?,
        val cornerUVsUnderlay: Array<FloatArray>?,
        val objects: List<TileObject> = emptyList()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TileDetails
            return tile == other.tile
        }

        override fun hashCode(): Int = tile.hashCode()
    }

    var selectedTile: SelectedTile? = null
        private set

    var selectedTileDetails: TileDetails? = null
        private set

    fun interface TerrainProvider {
        fun tiles(regionX: Int, regionY: Int): Array<Array<Array<TerrainLoader.TileData>>>?
    }

    private val rayOrigin = Vector3f()
    private val rayDirection = Vector3f()

    private val inverseViewProj = Matrix4f()
    private val nearPoint = Vector4f()
    private val farPoint = Vector4f()

    fun screenToWorldRay(
        mouseX: Float,
        mouseY: Float,
        screenWidth: Int,
        screenHeight: Int,
        camera: Camera,
        aspectRatio: Float
    ) {
        val ndcX = (2f * mouseX / screenWidth) - 1f
        val ndcY = 1f - (2f * mouseY / screenHeight)

        val viewMatrix = camera.getViewMatrix()
        val projMatrix = camera.getProjectionMatrix(aspectRatio)
        projMatrix.mul(viewMatrix, inverseViewProj)
        inverseViewProj.invert()

        nearPoint.set(ndcX, ndcY, -1f, 1f)
        inverseViewProj.transform(nearPoint)
        nearPoint.div(nearPoint.w)

        farPoint.set(ndcX, ndcY, 1f, 1f)
        inverseViewProj.transform(farPoint)
        farPoint.div(farPoint.w)

        rayOrigin.set(nearPoint.x, nearPoint.y, nearPoint.z)

        rayDirection.set(
            farPoint.x - nearPoint.x,
            farPoint.y - nearPoint.y,
            farPoint.z - nearPoint.z
        ).normalize()
    }

    fun pickTile(provider: TerrainProvider, plane: Int): SelectedTile? {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()

        val maxDistance = 4000f
        val stepSize = tileSize * 0.5f

        val currentPoint = Vector3f(rayOrigin)
        val step = Vector3f(rayDirection).mul(stepSize)

        var hasPrevSample = false
        var lastAbove = false
        val lastPoint = Vector3f(rayOrigin)

        var distance = 0f
        while (distance < maxDistance) {
            currentPoint.add(step)
            distance += stepSize

            val terrainHeight = heightAt(
                provider, plane, currentPoint.x, currentPoint.z, tileSize, heightScale
            )

            if (terrainHeight == null) {
                hasPrevSample = false
                lastPoint.set(currentPoint)
                continue
            }

            val isAbove = currentPoint.y > terrainHeight

            if (hasPrevSample && lastAbove && !isAbove) {
                val intersection = binarySearchIntersection(
                    lastPoint, currentPoint, provider, plane, tileSize, heightScale
                )

                val globalX = floor(intersection.x / tileSize).toInt()
                val globalY = floor(-intersection.z / tileSize).toInt()
                if (globalX < 0 || globalY < 0) return null

                selectedTile = SelectedTile(
                    regionX = globalX / TILES_PER_REGION,
                    regionY = globalY / TILES_PER_REGION,
                    localX = globalX % TILES_PER_REGION,
                    localY = globalY % TILES_PER_REGION,
                    plane = plane,
                    worldX = intersection.x,
                    worldY = intersection.y,
                    worldZ = intersection.z
                )
                return selectedTile
            }

            hasPrevSample = true
            lastAbove = isAbove
            lastPoint.set(currentPoint)
        }

        return null
    }

    private fun vertexHeight(
        provider: TerrainProvider,
        plane: Int,
        globalX: Int,
        globalY: Int,
        heightScale: Float
    ): Float? {
        if (globalX < 0 || globalY < 0) return null
        val tiles = provider.tiles(globalX / TILES_PER_REGION, globalY / TILES_PER_REGION) ?: return null
        if (plane < 0 || plane >= tiles.size) return null
        return tiles[plane][globalX % TILES_PER_REGION][globalY % TILES_PER_REGION].height * heightScale
    }

    private fun heightAt(
        provider: TerrainProvider,
        plane: Int,
        worldX: Float,
        worldZ: Float,
        tileSize: Float,
        heightScale: Float
    ): Float? {
        val gridX = worldX / tileSize
        val gridY = -worldZ / tileSize
        val globalX = floor(gridX).toInt()
        val globalY = floor(gridY).toInt()

        val h00 = vertexHeight(provider, plane, globalX, globalY, heightScale) ?: return null
        val h10 = vertexHeight(provider, plane, globalX + 1, globalY, heightScale) ?: h00
        val h01 = vertexHeight(provider, plane, globalX, globalY + 1, heightScale) ?: h00
        val h11 = vertexHeight(provider, plane, globalX + 1, globalY + 1, heightScale) ?: h10

        val fx = (gridX - globalX).coerceIn(0f, 1f)
        val fz = (gridY - globalY).coerceIn(0f, 1f)

        val h0 = h00 + (h10 - h00) * fx
        val h1 = h01 + (h11 - h01) * fx

        return h0 + (h1 - h0) * fz
    }

    private fun binarySearchIntersection(
        above: Vector3f,
        below: Vector3f,
        provider: TerrainProvider,
        plane: Int,
        tileSize: Float,
        heightScale: Float
    ): Vector3f {
        val mid = Vector3f()
        val a = Vector3f(above)
        val b = Vector3f(below)

        for (i in 0 until 10) {
            mid.set(a).add(b).mul(0.5f)

            val terrainHeight = heightAt(provider, plane, mid.x, mid.z, tileSize, heightScale)
                ?: return mid

            if (mid.y > terrainHeight) {
                a.set(mid)
            } else {
                b.set(mid)
            }
        }

        return mid
    }

    fun updateTileDetails(
        terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>?,
        cache: Cache,
        regionObjects: List<MapObject>? = null,
        baseWorldX: Int = 0,
        baseWorldY: Int = 0
    ) {
        val tile = selectedTile ?: run {
            selectedTileDetails = null
            return
        }

        val tileData = terrainTiles?.getOrNull(tile.plane)
            ?.getOrNull(tile.localX)
            ?.getOrNull(tile.localY)

        if (tileData == null) {
            selectedTileDetails = null
            return
        }

        val heightScale = TerrainLoader.getHeightScale()

        val nextX = (tile.localX + 1).coerceAtMost(63)
        val nextY = (tile.localY + 1).coerceAtMost(63)

        val cornerHeights = floatArrayOf(
            terrainTiles[tile.plane][tile.localX][tile.localY].height * heightScale,
            terrainTiles[tile.plane][nextX][tile.localY].height * heightScale,
            terrainTiles[tile.plane][tile.localX][nextY].height * heightScale,
            terrainTiles[tile.plane][nextX][nextY].height * heightScale
        )

        val underlayDef = if (tileData.underlayId > 0) {
            TerrainLoader.getUnderlayDefinition(tileData.underlayId)
        } else null

        val overlayDef = if (tileData.overlayId > 0) {
            TerrainLoader.getOverlayDefinition(tileData.overlayId)
        } else null

        val overlayUVs = if (overlayDef != null && overlayDef.texture >= 0) {
            val scale = if (overlayDef.scale > 0) overlayDef.scale.toFloat() else 512f
            val uvScale = 1f / scale

            arrayOf(
                floatArrayOf(0f, 0f),
                floatArrayOf(512f * uvScale, 0f),
                floatArrayOf(0f, 512f * uvScale),
                floatArrayOf(512f * uvScale, 512f * uvScale)
            )
        } else null

        val underlayUVs = if (underlayDef != null && underlayDef.texture >= 0) {
            val scale = if (underlayDef.scale > 0) underlayDef.scale.toFloat() else 512f
            val uvScale = 1f / scale

            arrayOf(
                floatArrayOf(0f, 0f),
                floatArrayOf(512f * uvScale, 0f),
                floatArrayOf(0f, 512f * uvScale),
                floatArrayOf(512f * uvScale, 512f * uvScale)
            )
        } else null

        val tileObjects = collectObjectsAtTile(
            cache, regionObjects, baseWorldX, baseWorldY,
            tile.localX, tile.localY, tile.plane
        )

        selectedTileDetails = TileDetails(
            tile = tile,
            tileData = tileData,
            underlayDef = underlayDef,
            overlayDef = overlayDef,
            cornerHeights = cornerHeights,
            cornerUVsOverlay = overlayUVs,
            cornerUVsUnderlay = underlayUVs,
            objects = tileObjects
        )
    }

    private fun collectObjectsAtTile(
        cache: Cache,
        regionObjects: List<MapObject>?,
        baseWorldX: Int,
        baseWorldY: Int,
        localX: Int,
        localY: Int,
        plane: Int
    ): List<TileObject> {
        if (regionObjects == null) return emptyList()

        val result = mutableListOf<TileObject>()
        val worldX = baseWorldX + localX
        val worldY = baseWorldY + localY

        for (obj in regionObjects) {
            val def = ObjectRenderer.getDefinition(obj.id) ?: continue

            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            val objMinX = obj.x
            val objMaxX = obj.x + sizeX - 1
            val objMinY = obj.y
            val objMaxY = obj.y + sizeY - 1

            if (worldX in objMinX..objMaxX && worldY in objMinY..objMaxY && obj.level == plane) {
                val modelIds = ObjectRenderer.getModelIds(def, obj.shape)
                if (modelIds.isEmpty()) continue

                val textureIds = mutableSetOf<Int>()
                for (modelId in modelIds) {
                    val modelDef = ModelLoader.loadDefinition(cache, modelId)
                    if (modelDef != null) {
                        collectTextureIds(modelDef, textureIds)
                    }
                }

                result.add(TileObject(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    textureIds = textureIds
                ))
            }
        }

        return result
    }

    private fun collectTextureIds(def: ModelDefinition, textureIds: MutableSet<Int>) {
        val faceTextures = def.faceTextures ?: return
        for (i in 0 until def.faceCount) {
            val textureIdSigned = faceTextures[i].toInt()
            val textureId = if (textureIdSigned == -1) -1 else (textureIdSigned and 0xffff)
            if (textureId >= 0) {
                textureIds.add(textureId)
            }
        }
    }

    fun clearSelection() {
        selectedTile = null
        selectedTileDetails = null
    }

    fun getRayOrigin(): Vector3f = rayOrigin

    fun getRayDirection(): Vector3f = rayDirection

    companion object {
        private const val TILES_PER_REGION = 64
    }
}
