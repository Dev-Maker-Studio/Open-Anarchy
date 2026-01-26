package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Loads terrain data from the cache and creates renderable meshes.
 */
object TerrainLoader {
    private val regionCache = mutableMapOf<Int, RegionData?>()
    private val meshCache = mutableMapOf<Int, Mesh?>()

    data class RegionData(
        val regionId: Int,
        val tiles: Array<Array<Array<TileData>>> // [plane][x][y]
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RegionData
            return regionId == other.regionId
        }

        override fun hashCode(): Int = regionId
    }

    data class TileData(
        val height: Int,
        val overlayId: Int,
        val overlayShape: Int,
        val overlayRotation: Int,
        val underlayId: Int,
        val flags: Int
    )

    fun loadRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY
        return regionCache.getOrPut(regionId) {
            decodeRegion(cache, regionX, regionY)
        }
    }

    fun loadRegionMesh(cache: Cache, regionX: Int, regionY: Int, plane: Int = 0): Mesh? {
        val regionId = (regionX shl 8) or regionY
        val cacheKey = regionId * 4 + plane
        return meshCache.getOrPut(cacheKey) {
            val region = loadRegion(cache, regionX, regionY) ?: return@getOrPut null
            createTerrainMesh(region, plane)
        }
    }

    private fun decodeRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val mapArchive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (mapArchive == -1) return null

        val data = cache.data(Index.MAPS, mapArchive) ?: return null
        val buffer = BufferReader(data)

        val tiles = Array(4) { Array(64) { Array(64) { TileData(0, 0, 0, 0, 0, 0) } } }

        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    tiles[plane][x][y] = decodeTile(buffer)
                }
            }
        }

        return RegionData((regionX shl 8) or regionY, tiles)
    }

    private fun decodeTile(buffer: BufferReader): TileData {
        var height = 0
        var overlayId = 0
        var overlayShape = 0
        var overlayRotation = 0
        var underlayId = 0
        var flags = 0

        while (true) {
            val opcode = buffer.readUnsignedByte()
            if (opcode == 0) {
                break
            } else if (opcode == 1) {
                height = buffer.readUnsignedByte()
                break
            } else if (opcode <= 49) {
                overlayId = buffer.readUnsignedByte()
                overlayShape = (opcode - 2) / 4
                overlayRotation = (opcode - 2) and 3
            } else if (opcode <= 81) {
                flags = opcode - 49
            } else {
                underlayId = opcode - 81
            }
        }

        return TileData(height, overlayId, overlayShape, overlayRotation, underlayId, flags)
    }

    private fun createTerrainMesh(region: RegionData, plane: Int): Mesh {
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()

        var vertexIndex = 0
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        for (x in 0 until 63) {
            for (y in 0 until 63) {
                val tile = region.tiles[plane][x][y]

                // Calculate heights for this tile's corners
                val h00 = region.tiles[plane][x][y].height * 8f * CoordinateUtil.DEFAULT_SCALE
                val h10 = region.tiles[plane][x + 1][y].height * 8f * CoordinateUtil.DEFAULT_SCALE
                val h01 = region.tiles[plane][x][y + 1].height * 8f * CoordinateUtil.DEFAULT_SCALE
                val h11 = region.tiles[plane][x + 1][y + 1].height * 8f * CoordinateUtil.DEFAULT_SCALE

                // Vertex positions
                val x0 = x * tileSize
                val x1 = (x + 1) * tileSize
                val z0 = -y * tileSize
                val z1 = -(y + 1) * tileSize

                // Get color based on underlay
                val color = getUnderlayColor(tile.underlayId)

                // First triangle
                positions.addAll(listOf(x0, h00, z0))
                positions.addAll(listOf(x1, h10, z0))
                positions.addAll(listOf(x0, h01, z1))

                colors.addAll(listOf(color.x, color.y, color.z))
                colors.addAll(listOf(color.x, color.y, color.z))
                colors.addAll(listOf(color.x, color.y, color.z))

                // Calculate normal for first triangle
                val v0 = Vector3f(x0, h00, z0)
                val v1 = Vector3f(x1, h10, z0)
                val v2 = Vector3f(x0, h01, z1)
                val n1 = CoordinateUtil.calculateNormal(v0, v1, v2)

                normals.addAll(listOf(n1.x, n1.y, n1.z))
                normals.addAll(listOf(n1.x, n1.y, n1.z))
                normals.addAll(listOf(n1.x, n1.y, n1.z))

                indices.add(vertexIndex)
                indices.add(vertexIndex + 1)
                indices.add(vertexIndex + 2)
                vertexIndex += 3

                // Second triangle
                positions.addAll(listOf(x1, h10, z0))
                positions.addAll(listOf(x1, h11, z1))
                positions.addAll(listOf(x0, h01, z1))

                colors.addAll(listOf(color.x, color.y, color.z))
                colors.addAll(listOf(color.x, color.y, color.z))
                colors.addAll(listOf(color.x, color.y, color.z))

                // Calculate normal for second triangle
                val v3 = Vector3f(x1, h10, z0)
                val v4 = Vector3f(x1, h11, z1)
                val v5 = Vector3f(x0, h01, z1)
                val n2 = CoordinateUtil.calculateNormal(v3, v4, v5)

                normals.addAll(listOf(n2.x, n2.y, n2.z))
                normals.addAll(listOf(n2.x, n2.y, n2.z))
                normals.addAll(listOf(n2.x, n2.y, n2.z))

                indices.add(vertexIndex)
                indices.add(vertexIndex + 1)
                indices.add(vertexIndex + 2)
                vertexIndex += 3
            }
        }

        return Mesh(
            positions.toFloatArray(),
            colors.toFloatArray(),
            null,
            normals.toFloatArray(),
            indices.toIntArray()
        )
    }

    private fun getUnderlayColor(underlayId: Int): Vector3f {
        // Basic underlay colors - in full implementation, load from cache
        return when (underlayId) {
            0 -> Vector3f(0.2f, 0.5f, 0.2f) // Grass
            1 -> Vector3f(0.6f, 0.5f, 0.3f) // Dirt
            2 -> Vector3f(0.3f, 0.3f, 0.35f) // Stone
            3 -> Vector3f(0.2f, 0.3f, 0.6f) // Water
            4 -> Vector3f(0.9f, 0.85f, 0.7f) // Sand
            else -> Vector3f(0.3f, 0.6f, 0.3f) // Default grass
        }
    }

    fun clearCache() {
        meshCache.values.forEach { it?.cleanup() }
        meshCache.clear()
        regionCache.clear()
    }

    /**
     * Get a list of valid region IDs in the cache.
     */
    fun getAvailableRegions(cache: Cache): List<Pair<Int, Int>> {
        val regions = mutableListOf<Pair<Int, Int>>()
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                val archiveId = cache.archiveId(Index.MAPS, "m${x}_$y")
                if (archiveId != -1) {
                    regions.add(Pair(x, y))
                }
            }
        }
        return regions
    }
}
