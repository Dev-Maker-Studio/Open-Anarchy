package org.darkan.tools.engine.input

import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition

/**
 * Handles tile selection via raycasting from mouse position to terrain.
 *
 * Uses ray-plane intersection to find which tile the user clicked on.
 * The terrain is treated as a heightmap, with each tile having height at its corners.
 */
class TilePicker {

    /**
     * Information about a selected tile.
     */
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

    /**
     * Information about an object on a tile, including its textures.
     */
    data class TileObject(
        val mapObject: MapObject,
        val definition: ObjectDefinition,
        val modelIds: List<Int>,
        val textureIds: Set<Int>  // Unique texture IDs used by all models
    )

    /**
     * Detailed tile data retrieved from TerrainLoader.
     */
    data class TileDetails(
        val tile: SelectedTile,
        val tileData: TerrainLoader.TileData?,
        val underlayDef: world.gregs.voidps.cache.config.data.UnderlayDefinition?,
        val overlayDef: world.gregs.voidps.cache.config.data.OverlayDefinition?,
        val cornerHeights: FloatArray,  // [SW, SE, NW, NE]
        val cornerUVsOverlay: Array<FloatArray>?,  // [[u, v], ...] for overlay
        val cornerUVsUnderlay: Array<FloatArray>?,  // [[u, v], ...] for underlay
        val objects: List<TileObject> = emptyList()  // Objects on this tile
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TileDetails
            return tile == other.tile
        }

        override fun hashCode(): Int = tile.hashCode()
    }

    // Currently selected tile
    var selectedTile: SelectedTile? = null
        private set

    var selectedTileDetails: TileDetails? = null
        private set

    // World offset for the region being picked.
    // Ray coordinates are in world space; subtract these offsets to get local-tile-space
    // coordinates for intersection testing against the local terrain grid.
    var worldOffsetX: Float = 0f
    var worldOffsetZ: Float = 0f

    // Ray for debugging
    private val rayOrigin = Vector3f()
    private val rayDirection = Vector3f()

    // Cached matrices for unprojection
    private val inverseViewProj = Matrix4f()
    private val nearPoint = Vector4f()
    private val farPoint = Vector4f()

    /**
     * Convert screen coordinates to a world ray.
     *
     * @param mouseX Mouse X in screen pixels
     * @param mouseY Mouse Y in screen pixels
     * @param screenWidth Window width
     * @param screenHeight Window height
     * @param camera The camera for view/projection matrices
     * @param aspectRatio Window aspect ratio
     */
    fun screenToWorldRay(
        mouseX: Float,
        mouseY: Float,
        screenWidth: Int,
        screenHeight: Int,
        camera: Camera,
        aspectRatio: Float
    ) {
        // Convert to normalized device coordinates [-1, 1]
        val ndcX = (2f * mouseX / screenWidth) - 1f
        val ndcY = 1f - (2f * mouseY / screenHeight)  // Y is inverted

        // Get view-projection matrix and invert it
        val viewMatrix = camera.getViewMatrix()
        val projMatrix = camera.getProjectionMatrix(aspectRatio)
        projMatrix.mul(viewMatrix, inverseViewProj)
        inverseViewProj.invert()

        // Unproject near and far points
        nearPoint.set(ndcX, ndcY, -1f, 1f)
        inverseViewProj.transform(nearPoint)
        nearPoint.div(nearPoint.w)

        farPoint.set(ndcX, ndcY, 1f, 1f)
        inverseViewProj.transform(farPoint)
        farPoint.div(farPoint.w)

        // Ray origin is camera position (or near point for orthographic)
        rayOrigin.set(nearPoint.x, nearPoint.y, nearPoint.z)

        // Ray direction
        rayDirection.set(
            farPoint.x - nearPoint.x,
            farPoint.y - nearPoint.y,
            farPoint.z - nearPoint.z
        ).normalize()
    }

    /**
     * Pick a tile from the current ray using terrain data.
     *
     * @param terrainTiles The terrain tile array from TerrainLoader.RegionData
     * @param regionX The region X coordinate
     * @param regionY The region Y coordinate
     * @param plane The current plane
     * @return The selected tile, or null if no intersection
     */
    fun pickTile(
        terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>?,
        regionX: Int,
        regionY: Int,
        plane: Int
    ): SelectedTile? {
        if (terrainTiles == null) return null

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()

        // March along the ray to find terrain intersection
        // Use binary search refinement for accuracy
        val maxDistance = 4000f
        val stepSize = tileSize * 0.5f  // Half tile per step

        var currentPoint = Vector3f(rayOrigin)
        val step = Vector3f(rayDirection).mul(stepSize)

        var lastAbove = true
        var lastPoint = Vector3f(rayOrigin)

        var distance = 0f
        while (distance < maxDistance) {
            currentPoint.add(step)
            distance += stepSize

            // Convert world position to local tile coordinates by subtracting the region offset
            val localX = ((currentPoint.x - worldOffsetX) / tileSize).toInt()
            val localY = (-(currentPoint.z - worldOffsetZ) / tileSize).toInt()  // Z is negated in OpenGL

            // Check if within region bounds
            if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) {
                lastPoint.set(currentPoint)
                continue
            }

            // Get terrain height at this position (interpolate between corners)
            // Pass LOCAL coordinates to height lookup (subtract world offset)
            val terrainHeight = getInterpolatedHeight(
                terrainTiles, localX, localY, plane,
                currentPoint.x - worldOffsetX, currentPoint.z - worldOffsetZ, tileSize, heightScale
            )

            val isAbove = currentPoint.y > terrainHeight

            // Check for intersection (ray crosses terrain surface)
            if (!isAbove && lastAbove) {
                // Binary search refinement
                val intersection = binarySearchIntersection(
                    lastPoint, currentPoint,
                    terrainTiles, plane, tileSize, heightScale
                )

                // Get final tile coordinates (in local space)
                val finalLocalX = ((intersection.x - worldOffsetX) / tileSize).toInt().coerceIn(0, 63)
                val finalLocalY = (-(intersection.z - worldOffsetZ) / tileSize).toInt().coerceIn(0, 63)

                selectedTile = SelectedTile(
                    regionX = regionX,
                    regionY = regionY,
                    localX = finalLocalX,
                    localY = finalLocalY,
                    plane = plane,
                    worldX = intersection.x,
                    worldY = intersection.y,
                    worldZ = intersection.z
                )
                return selectedTile
            }

            lastAbove = isAbove
            lastPoint.set(currentPoint)
        }

        return null
    }

    /**
     * Get interpolated terrain height at a world position.
     */
    private fun getInterpolatedHeight(
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        localX: Int,
        localY: Int,
        plane: Int,
        worldX: Float,
        worldZ: Float,
        tileSize: Float,
        heightScale: Float
    ): Float {
        if (localX < 0 || localX >= 63 || localY < 0 || localY >= 63) {
            val clampedX = localX.coerceIn(0, 63)
            val clampedY = localY.coerceIn(0, 63)
            return tiles[plane][clampedX][clampedY].height * heightScale
        }

        // Get corner heights
        val h00 = tiles[plane][localX][localY].height * heightScale         // SW
        val h10 = tiles[plane][localX + 1][localY].height * heightScale     // SE
        val h01 = tiles[plane][localX][localY + 1].height * heightScale     // NW
        val h11 = tiles[plane][localX + 1][localY + 1].height * heightScale // NE

        // Bilinear interpolation
        val tileStartX = localX * tileSize
        val tileStartZ = -localY * tileSize

        val fx = ((worldX - tileStartX) / tileSize).coerceIn(0f, 1f)
        val fz = ((-worldZ - localY * tileSize) / tileSize).coerceIn(0f, 1f)

        val h0 = h00 + (h10 - h00) * fx  // South edge
        val h1 = h01 + (h11 - h01) * fx  // North edge

        return h0 + (h1 - h0) * fz
    }

    /**
     * Binary search to refine intersection point.
     */
    private fun binarySearchIntersection(
        above: Vector3f,
        below: Vector3f,
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        plane: Int,
        tileSize: Float,
        heightScale: Float
    ): Vector3f {
        val mid = Vector3f()
        var a = Vector3f(above)
        var b = Vector3f(below)

        for (i in 0 until 10) {  // 10 iterations for good precision
            mid.set(a).add(b).mul(0.5f)

            val localX = ((mid.x - worldOffsetX) / tileSize).toInt()
            val localY = (-(mid.z - worldOffsetZ) / tileSize).toInt()

            if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) {
                // Out of bounds, move towards in-bounds point
                if (localX < 0 || localX >= 64) b.set(mid)
                else a.set(mid)
                continue
            }

            val terrainHeight = getInterpolatedHeight(
                tiles, localX, localY, plane,
                mid.x - worldOffsetX, mid.z - worldOffsetZ, tileSize, heightScale
            )

            if (mid.y > terrainHeight) {
                a.set(mid)
            } else {
                b.set(mid)
            }
        }

        return mid
    }

    /**
     * Update tile details from cache definitions.
     *
     * @param terrainTiles The terrain tile data array
     * @param cache The game cache for loading definitions
     * @param regionObjects Optional list of MapObjects in the region for object lookup
     * @param baseWorldX World X coordinate of region origin (regionX * 64)
     * @param baseWorldY World Y coordinate of region origin (regionY * 64)
     */
    fun updateTileDetails(
        terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>?,
        cache: world.gregs.voidps.cache.Cache,
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

        // Get corner heights
        val nextX = (tile.localX + 1).coerceAtMost(63)
        val nextY = (tile.localY + 1).coerceAtMost(63)

        val cornerHeights = floatArrayOf(
            terrainTiles[tile.plane][tile.localX][tile.localY].height * heightScale,
            terrainTiles[tile.plane][nextX][tile.localY].height * heightScale,
            terrainTiles[tile.plane][tile.localX][nextY].height * heightScale,
            terrainTiles[tile.plane][nextX][nextY].height * heightScale
        )

        // Get definitions
        val underlayDef = if (tileData.underlayId > 0) {
            TerrainLoader.getUnderlayDefinition(tileData.underlayId)
        } else null

        val overlayDef = if (tileData.overlayId > 0) {
            TerrainLoader.getOverlayDefinition(tileData.overlayId)
        } else null

        // Calculate UV coordinates for overlay
        val overlayUVs = if (overlayDef != null && overlayDef.texture >= 0) {
            val scale = if (overlayDef.scale > 0) overlayDef.scale.toFloat() else 512f
            val uvScale = 1f / scale

            // Local tile positions (0-512 within tile)
            // Corners: SW(0,0), SE(512,0), NW(0,512), NE(512,512)
            arrayOf(
                floatArrayOf(0f, 0f),                           // SW
                floatArrayOf(512f * uvScale, 0f),               // SE
                floatArrayOf(0f, 512f * uvScale),               // NW
                floatArrayOf(512f * uvScale, 512f * uvScale)    // NE
            )
        } else null

        // Calculate UV coordinates for underlay
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

        // Collect objects at this tile position
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

    /**
     * Collect all objects at the specified tile position.
     * Also includes objects that span multiple tiles (sizeX > 1 or sizeY > 1).
     */
    private fun collectObjectsAtTile(
        cache: world.gregs.voidps.cache.Cache,
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
            // Check if object is on this tile (considering object size)
            val def = ObjectRenderer.getDefinition(obj.id) ?: continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Check if the tile is within the object's footprint
            val objMinX = obj.x
            val objMaxX = obj.x + sizeX - 1
            val objMinY = obj.y
            val objMaxY = obj.y + sizeY - 1

            if (worldX in objMinX..objMaxX && worldY in objMinY..objMaxY && obj.level == plane) {
                val modelIds = ObjectRenderer.getModelIds(def, obj.shape)
                if (modelIds.isEmpty()) continue

                // Collect texture IDs from all models
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

    /**
     * Collect unique texture IDs from a model definition.
     */
    private fun collectTextureIds(def: org.darkan.tools.cache.model.ModelDefinition, textureIds: MutableSet<Int>) {
        val faceTextures = def.faceTextures ?: return
        for (i in 0 until def.faceCount) {
            val textureIdSigned = faceTextures[i].toInt()
            val textureId = if (textureIdSigned == -1) -1 else (textureIdSigned and 0xffff)
            if (textureId >= 0) {
                textureIds.add(textureId)
            }
        }
    }

    /**
     * Clear the selection.
     */
    fun clearSelection() {
        selectedTile = null
        selectedTileDetails = null
    }

    /**
     * Get the current ray origin for debugging.
     */
    fun getRayOrigin(): Vector3f = rayOrigin

    /**
     * Get the current ray direction for debugging.
     */
    fun getRayDirection(): Vector3f = rayDirection
}
