package org.darkan.tools.cache.map

import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.render.MeshData
import java.util.concurrent.ConcurrentHashMap

object VertexDisplacer {

    private const val TILE_UNITS = 512

    private const val TILE_SCALE = 9

    private const val TILE_MASK = TILE_UNITS - 1

    private const val INV_SCALE = 128f

    private const val BOUNDS_CACHE_LIMIT = 65536

    class HeightField private constructor(
        val width: Int,
        val depth: Int,
        val pad: Int,
        private val planes: Array<IntArray>
    ) {
        val rsOrigin: Int = pad * TILE_UNITS

        fun heights(plane: Int): IntArray = planes[plane]

        companion object {
            private val EMPTY = IntArray(0)

            const val PAD_TILES = 8

            fun from(
                tiles: Array<Array<Array<TerrainLoader.TileData>>>,
                neighbors: TerrainLoader.NeighborData? = null
            ): HeightField {
                val regionWidth = if (tiles.isEmpty()) 0 else tiles[0].size
                val regionDepth = if (regionWidth > 0) tiles[0][0].size else 0
                if (regionWidth <= 0 || regionDepth <= 0) {
                    return HeightField(0, 0, 0, Array(tiles.size) { EMPTY })
                }

                val pad = PAD_TILES
                val width = regionWidth + 2 * pad
                val depth = regionDepth + 2 * pad
                val planes = Array(tiles.size) { plane ->
                    val planeTiles = tiles[plane]
                    if (planeTiles.size != regionWidth) return@Array EMPTY
                    val out = IntArray(width * depth)
                    for (x in 0 until width) {
                        val tileX = x - pad
                        val base = x * depth
                        if (tileX in 0 until regionWidth) {
                            val column = planeTiles[tileX]
                            for (z in 0 until depth) {
                                val tileZ = z - pad
                                out[base + z] = if (tileZ in 0 until regionDepth) {
                                    column[tileZ].height
                                } else {
                                    TerrainLoader.getExtendedTileHeight(tiles, neighbors, plane, tileX, tileZ)
                                }
                            }
                        } else {
                            for (z in 0 until depth) {
                                out[base + z] =
                                    TerrainLoader.getExtendedTileHeight(tiles, neighbors, plane, tileX, z - pad)
                            }
                        }
                    }
                    out
                }
                return HeightField(width, depth, pad, planes)
            }
        }
    }

    private val boundsCache = ConcurrentHashMap<Int, IntArray>()

    fun clearCaches() {
        boundsCache.clear()
    }

    fun modelBounds(combinedModelId: Int, meshes: List<MeshData>): IntArray? {
        boundsCache[combinedModelId]?.let { return if (it.isEmpty()) null else it }
        val computed = computeBounds(meshes)
        if (boundsCache.size < BOUNDS_CACHE_LIMIT) {
            val stored = boundsCache.putIfAbsent(combinedModelId, computed ?: IntArray(0))
            if (stored != null) return if (stored.isEmpty()) null else stored
        }
        return computed
    }

    private fun computeBounds(meshes: List<MeshData>): IntArray? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (mesh in meshes) {
            val positions = mesh.positions
            var idx = 0
            while (idx < positions.size) {
                val rsX = (positions[idx] * INV_SCALE).toInt()
                val rsZ = (-positions[idx + 2] * INV_SCALE).toInt()
                if (rsX < minX) minX = rsX
                if (rsX > maxX) maxX = rsX
                if (rsZ < minZ) minZ = rsZ
                if (rsZ > maxZ) maxZ = rsZ
                idx += 3
            }
        }
        if (minX > maxX) return null
        return intArrayOf(minX, maxX, minZ, maxZ)
    }

    fun footprintIsFlat(
        bounds: IntArray,
        sceneX: Int,
        sceneY: Int,
        averageHeight: Int,
        plane: Int,
        field: HeightField,
        rsRotation: Int,
        mirrored: Boolean,
        resizeX: Int,
        resizeY: Int,
        resizeZ: Int,
        rsOffsetX: Int,
        rsOffsetZ: Int
    ): Boolean {
        if (resizeX < 0 || resizeZ < 0) return false
        if (resizeY == 0) return false

        val width = field.width
        val depth = field.depth
        if (width <= 0 || depth <= 0) return false
        val heights = field.heights(plane)
        if (heights.isEmpty()) return false

        var minZ = bounds[2]
        var maxZ = bounds[3]
        if (mirrored) {
            val swapped = -maxZ
            maxZ = -minZ
            minZ = swapped
        }
        val minX = bounds[0]
        val maxX = bounds[1]

        val loX: Int
        val hiX: Int
        val loZ: Int
        val hiZ: Int
        when (rsRotation and 3) {
            1 -> { loX = minZ; hiX = maxZ; loZ = -maxX; hiZ = -minX }
            2 -> { loX = -maxX; hiX = -minX; loZ = -maxZ; hiZ = -minZ }
            3 -> { loX = -maxZ; hiX = -minZ; loZ = minX; hiZ = maxX }
            else -> { loX = minX; hiX = maxX; loZ = minZ; hiZ = maxZ }
        }

        val padUnits = field.rsOrigin
        val localLoX = ((resizeX * loX) shr 7) + rsOffsetX + sceneX + padUnits
        val localHiX = ((resizeX * hiX) shr 7) + rsOffsetX + sceneX + padUnits
        val localLoZ = ((resizeZ * loZ) shr 7) + rsOffsetZ + sceneY + padUnits
        val localHiZ = ((resizeZ * hiZ) shr 7) + rsOffsetZ + sceneY + padUnits

        var tx0 = localLoX shr TILE_SCALE
        var tx1 = (localHiX shr TILE_SCALE) + 1
        var tz0 = localLoZ shr TILE_SCALE
        var tz1 = (localHiZ shr TILE_SCALE) + 1
        if (tx0 < 0) tx0 = 0
        if (tz0 < 0) tz0 = 0
        if (tx1 > width - 1) tx1 = width - 1
        if (tz1 > depth - 1) tz1 = depth - 1
        if (tx0 > tx1 || tz0 > tz1) return true

        for (tx in tx0..tx1) {
            val base = tx * depth
            for (tz in tz0..tz1) {
                if (heights[base + tz] != averageHeight) return false
            }
        }
        return true
    }

    fun displaceVertices(
        meshData: MeshData,
        sceneX: Int,
        sceneY: Int,
        averageHeight: Int,
        plane: Int,
        field: HeightField,
        rsRotation: Int = 0,
        mirrored: Boolean = false,
        resizeX: Int = 128,
        resizeY: Int = 128,
        resizeZ: Int = 128,
        rsOffsetX: Int = 0,
        rsOffsetZ: Int = 0
    ): MeshData? {
        val positions = meshData.positions
        if (positions.isEmpty()) return null

        val width = field.width
        val depth = field.depth
        if (width <= 0 || depth <= 0) return null
        val heights = field.heights(plane)
        if (heights.isEmpty()) return null

        val mirrorSign = if (mirrored) -1 else 1
        val factorXX: Int
        val factorXZ: Int
        val factorZX: Int
        val factorZZ: Int
        when (rsRotation and 3) {
            1 -> { factorXX = 0; factorXZ = resizeX * mirrorSign; factorZX = -resizeZ; factorZZ = 0 }
            2 -> { factorXX = -resizeX; factorXZ = 0; factorZX = 0; factorZZ = -resizeZ * mirrorSign }
            3 -> { factorXX = 0; factorXZ = -resizeX * mirrorSign; factorZX = resizeZ; factorZZ = 0 }
            else -> { factorXX = resizeX; factorXZ = 0; factorZX = 0; factorZZ = resizeZ * mirrorSign }
        }
        val padUnits = field.rsOrigin
        val baseX = rsOffsetX + sceneX + padUnits
        val baseZ = rsOffsetZ + sceneY + padUnits
        val maxTileX = width - 1
        val maxTileZ = depth - 1
        val resizeYf = resizeY.toFloat()
        val zeroDeltaIsNoop = resizeY != 0

        var newPositions: FloatArray? = null

        var idx = 0
        val limit = positions.size
        while (idx < limit) {
            val oglX = positions[idx]
            val oglZ = positions[idx + 2]

            val rsX = (oglX * INV_SCALE).toInt()
            val rsZ = (-oglZ * INV_SCALE).toInt()

            val localX = (((factorXX * rsX) + (factorXZ * rsZ)) shr 7) + baseX
            val localZ = (((factorZX * rsX) + (factorZZ * rsZ)) shr 7) + baseZ

            val tileX = localX shr TILE_SCALE
            val tileZ = localZ shr TILE_SCALE
            if (tileX < 0 || tileX >= maxTileX || tileZ < 0 || tileZ >= maxTileZ) {
                idx += 3
                continue
            }

            val corner = tileX * depth + tileZ
            val h00 = heights[corner]
            val h01 = heights[corner + 1]
            val h10 = heights[corner + depth]
            val h11 = heights[corner + depth + 1]

            val interpolatedHeight: Int
            if (h00 == h01 && h00 == h10 && h00 == h11) {
                interpolatedHeight = h00
            } else {
                val remainderX = localX and TILE_MASK
                val remainderZ = localZ and TILE_MASK

                val height1 = (h00 * (TILE_UNITS - remainderX) + remainderX * h10) shr TILE_SCALE
                val height2 = ((TILE_UNITS - remainderX) * h01 + remainderX * h11) shr TILE_SCALE
                interpolatedHeight =
                    (height1 * (TILE_UNITS - remainderZ) + height2 * remainderZ) shr TILE_SCALE
            }

            if (interpolatedHeight == averageHeight && zeroDeltaIsNoop) {
                idx += 3
                continue
            }

            val oglY = positions[idx + 1]
            val newOglY = oglY + (interpolatedHeight - averageHeight).toFloat() / resizeYf

            if (newOglY != oglY) {
                val target = newPositions ?: positions.copyOf().also { newPositions = it }
                target[idx + 1] = newOglY
            }
            idx += 3
        }

        val displaced = newPositions ?: return null

        return MeshData(
            positions = displaced,
            colors = meshData.colors,
            textureCoords = meshData.textureCoords,
            normals = meshData.normals,
            indices = meshData.indices,
            textureId = meshData.textureId
        )
    }

    fun needsDisplacement(groundContourType: Int, groundContourModifier: Int): Boolean {
        return groundContourType == 1 && groundContourModifier < 16384
    }
}
