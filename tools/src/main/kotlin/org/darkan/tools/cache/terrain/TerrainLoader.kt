package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.decoder.UnderlayDecoder

/**
 * Loads terrain data from the cache and creates renderable meshes.
 * Uses actual underlay and overlay definitions for accurate colors.
 */
object TerrainLoader {
    private val regionCache = mutableMapOf<Int, RegionData?>()
    private val meshCache = mutableMapOf<Int, Mesh?>()

    // Lazy-loaded definitions from cache
    private var underlays: Array<UnderlayDefinition>? = null
    private var overlays: Array<OverlayDefinition>? = null
    private var definitionsLoaded = false

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

    /**
     * A terrain batch for a specific texture.
     */
    data class TerrainBatch(
        val mesh: Mesh,
        val textureId: Int  // -1 for untextured/color-only
    )

    /**
     * Container for terrain meshes with separate batches for textured and untextured tiles.
     */
    data class TerrainMeshes(
        val colorMesh: Mesh?,
        val texturedBatches: List<TerrainBatch>
    ) {
        fun cleanup() {
            colorMesh?.cleanup()
            texturedBatches.forEach { it.mesh.cleanup() }
        }
    }

    // Cache for batched terrain meshes
    private val batchedMeshCache = mutableMapOf<Int, TerrainMeshes?>()

    /**
     * Load floor definitions (underlays and overlays) from the cache.
     * Call this once before using getUnderlayColor/getOverlayColor.
     */
    fun loadDefinitions(cache: Cache) {
        if (definitionsLoaded) return

        underlays = UnderlayDecoder().load(cache)
        overlays = OverlayDecoder().load(cache)
        definitionsLoaded = true
    }

    /**
     * Compute blended underlay colors for a region plane using the client's 11x11 averaging algorithm.
     *
     * The client averages underlay HSL components over an 11x11 area (5 tiles in each direction)
     * centered on each tile. This creates smooth color transitions between different underlay types.
     *
     * @param region The region data containing tile information
     * @param plane The plane level to compute colors for
     * @return A 64x64 array of packed HSL color values, one for each tile
     */
    private fun computeBlendedUnderlayColors(region: RegionData, plane: Int): Array<IntArray> {
        val blendedColors = Array(64) { IntArray(64) }
        val underlayDefs = underlays ?: return blendedColors

        // Temporary arrays for accumulating HSL values across rows (same pattern as client)
        // These maintain a sliding window across X - initialized to 0 for the plane
        val underlayHue = IntArray(64)
        val underlaySaturation = IntArray(64)
        val underlayLightness = IntArray(64)
        val underlayDivisor = IntArray(64)
        val underlayCount = IntArray(64)

        for (x in -5 until 64) {
            // Update column accumulators with sliding window (add at x+5, subtract at x-5)
            for (y in 0 until 64) {
                // Add tile at x+5 (forward edge of window)
                val forwardX = x + 5
                if (forwardX in 0 until 64) {
                    val underlayId = region.tiles[plane][forwardX][y].underlayId
                    // RS client uses (underlayId - 1) for definition lookup
                    val defIndex = underlayId - 1
                    if (defIndex >= 0 && defIndex < underlayDefs.size) {
                        val def = underlayDefs[defIndex]
                        underlayHue[y] += def.hue
                        underlaySaturation[y] += def.saturation
                        underlayLightness[y] += def.lightness
                        underlayDivisor[y] += def.chroma
                        underlayCount[y]++
                    }
                }

                // Subtract tile at x-5 (back edge of window)
                val backwardX = x - 5
                if (backwardX in 0 until 64) {
                    val underlayId = region.tiles[plane][backwardX][y].underlayId
                    // RS client uses (underlayId - 1) for definition lookup
                    val defIndex = underlayId - 1
                    if (defIndex >= 0 && defIndex < underlayDefs.size) {
                        val def = underlayDefs[defIndex]
                        underlayHue[y] -= def.hue
                        underlaySaturation[y] -= def.saturation
                        underlayLightness[y] -= def.lightness
                        underlayDivisor[y] -= def.chroma
                        underlayCount[y]--
                    }
                }
            }

            // Now compute blended colors for column x by averaging over Y window
            if (x >= 0) {
                var hueSum = 0
                var saturationSum = 0
                var lightnessSum = 0
                var divisorSum = 0
                var depth = 0

                for (y in -5 until 64) {
                    // Add row at y+5 (forward edge of Y window)
                    val forwardY = y + 5
                    if (forwardY in 0 until 64) {
                        hueSum += underlayHue[forwardY]
                        saturationSum += underlaySaturation[forwardY]
                        lightnessSum += underlayLightness[forwardY]
                        divisorSum += underlayDivisor[forwardY]
                        depth += underlayCount[forwardY]
                    }

                    // Subtract row at y-5 (back edge of Y window)
                    val backwardY = y - 5
                    if (backwardY in 0 until 64) {
                        hueSum -= underlayHue[backwardY]
                        saturationSum -= underlaySaturation[backwardY]
                        lightnessSum -= underlayLightness[backwardY]
                        divisorSum -= underlayDivisor[backwardY]
                        depth -= underlayCount[backwardY]
                    }

                    // Compute blended color for tile (x, y)
                    if (y >= 0 && divisorSum > 0 && depth > 0) {
                        // Use client's hsl16to24 formula
                        val hue = hueSum * 256 / divisorSum
                        val saturation = saturationSum / depth
                        val lightness = lightnessSum / depth
                        blendedColors[x][y] = ColorUtil.packHsl16(hue, saturation, lightness)
                    }
                }
            }
        }

        return blendedColors
    }

    /**
     * Get blended underlay color for a specific tile from pre-computed blended colors.
     */
    private fun getBlendedUnderlayColor(blendedColors: Array<IntArray>, x: Int, y: Int): Vector3f {
        if (x !in 0 until 64 || y !in 0 until 64) return DEFAULT_GRASS_COLOR
        val packedHsl = blendedColors[x][y]
        return if (packedHsl != 0) ColorUtil.hslToRgb(packedHsl) else DEFAULT_GRASS_COLOR
    }

    fun loadRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY
        return regionCache.getOrPut(regionId) {
            decodeRegion(cache, regionX, regionY)
        }
    }

    fun loadRegionMesh(cache: Cache, regionX: Int, regionY: Int, plane: Int = 0): Mesh? {
        // Ensure definitions are loaded
        loadDefinitions(cache)

        val regionId = (regionX shl 8) or regionY
        val cacheKey = regionId * 4 + plane
        return meshCache.getOrPut(cacheKey) {
            val region = loadRegion(cache, regionX, regionY) ?: return@getOrPut null
            createTerrainMesh(region, plane)
        }
    }

    /**
     * Load terrain meshes with separate batches for textured and untextured tiles.
     * Tiles with texture IDs from underlay/overlay definitions go into textured batches,
     * while tiles with only color information go into the color mesh.
     *
     * @param cache The game cache
     * @param regionX Region X coordinate
     * @param regionY Region Y coordinate
     * @param plane Plane level (0-3)
     * @return TerrainMeshes containing color mesh and textured batches, or null if region not found
     */
    fun loadRegionMeshes(cache: Cache, regionX: Int, regionY: Int, plane: Int = 0): TerrainMeshes? {
        // Ensure definitions are loaded
        loadDefinitions(cache)

        val regionId = (regionX shl 8) or regionY
        val cacheKey = regionId * 4 + plane
        return batchedMeshCache.getOrPut(cacheKey) {
            val region = loadRegion(cache, regionX, regionY) ?: return@getOrPut null
            createBatchedTerrainMeshes(region, plane)
        }
    }

    /**
     * Create separate mesh batches for textured and untextured terrain tiles.
     * Uses proper tile shape tessellation with the RS client's shape lookup tables.
     * Shapes create paths, edges, diagonal cuts, etc. by tessellating tiles into
     * multiple triangles with some belonging to overlay and others to underlay.
     */
    private fun createBatchedTerrainMeshes(region: RegionData, plane: Int): TerrainMeshes {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Pre-compute blended underlay colors for the entire region
        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane)

        // Collect vertices grouped by texture ID
        val textureGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        for (x in 0 until 63) {
            for (y in 0 until 63) {
                val tile = region.tiles[plane][x][y]

                // Skip tiles with no overlay and no underlay
                if (tile.overlayId == 0 && tile.underlayId == 0) continue

                // Calculate heights for this tile's corners
                // heights array: [SW, SE, NW, NE]
                val heights = floatArrayOf(
                    region.tiles[plane][x][y].height * HEIGHT_SCALE,         // SW (0,0)
                    region.tiles[plane][x + 1][y].height * HEIGHT_SCALE,     // SE (1,0)
                    region.tiles[plane][x][y + 1].height * HEIGHT_SCALE,     // NW (0,1)
                    region.tiles[plane][x + 1][y + 1].height * HEIGHT_SCALE  // NE (1,1)
                )

                // Get overlay and underlay definitions
                val overlayDef = if (tile.overlayId > 0) getOverlayDefinition(tile.overlayId) else null
                val underlayDef = if (tile.underlayId > 0) getUnderlayDefinition(tile.underlayId) else null

                // Determine colors and textures for overlay and underlay
                val underlayColor = getBlendedUnderlayColor(blendedUnderlayColors, x, y)
                val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else underlayColor

                // Determine texture IDs
                val overlayTextureId = overlayDef?.texture ?: -1
                val underlayTextureId = underlayDef?.texture ?: -1

                // Determine UV scales
                val overlayUvScale = if (overlayDef != null && overlayDef.texture >= 0) {
                    overlayDef.scale.toFloat() / 512f
                } else {
                    1f
                }
                val underlayUvScale = if (underlayDef != null && underlayDef.texture >= 0) {
                    underlayDef.scale.toFloat() / 512f
                } else {
                    1f
                }

                // Adjust colors for textured tiles (use white or tint color)
                val finalOverlayColor = if (overlayTextureId >= 0) {
                    if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
                        ColorUtil.hslToRgb(overlayDef.colour)
                    } else {
                        Vector3f(1f, 1f, 1f)
                    }
                } else {
                    overlayColor
                }

                val finalUnderlayColor = if (underlayTextureId >= 0) {
                    underlayColor
                } else {
                    underlayColor
                }

                // Determine the shape to use
                // If shape is 0 and no overlay, treat as full underlay (shape 12)
                var shape = tile.overlayShape
                if (shape == 0 && tile.overlayId == 0) {
                    shape = 12  // Shape 12 = full underlay (0 overlay faces, 2 underlay faces)
                }

                // Generate shaped vertices for this tile
                val vertices = generateShapedTile(
                    x, y,
                    shape, tile.overlayRotation,
                    tileSize,
                    heights,
                    finalOverlayColor,
                    finalUnderlayColor,
                    overlayTextureId,
                    underlayTextureId,
                    overlayUvScale,
                    underlayUvScale
                )

                // Add vertices to appropriate texture groups
                for (vertex in vertices) {
                    val group = textureGroups.getOrPut(vertex.textureId) { mutableListOf() }
                    group.add(vertex)
                }
            }
        }

        // Build color mesh (textureId == -1)
        val colorMesh = textureGroups[-1]?.let { vertices ->
            if (vertices.isEmpty()) return@let null

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val normals = FloatArray(vertices.size * 3)
            val indices = IntArray(vertices.size)

            vertices.forEachIndexed { i, v ->
                positions[i * 3] = v.x
                positions[i * 3 + 1] = v.y
                positions[i * 3 + 2] = v.z
                colors[i * 3] = v.r
                colors[i * 3 + 1] = v.g
                colors[i * 3 + 2] = v.b
                normals[i * 3] = v.nx
                normals[i * 3 + 1] = v.ny
                normals[i * 3 + 2] = v.nz
                indices[i] = i
            }

            Mesh(positions, colors, null, normals, indices)
        }

        // Build textured batches
        val texturedBatches = mutableListOf<TerrainBatch>()
        for ((texId, vertices) in textureGroups) {
            if (texId == -1) continue // Skip color group
            if (vertices.isEmpty()) continue

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val texCoords = FloatArray(vertices.size * 2)
            val normals = FloatArray(vertices.size * 3)
            val indices = IntArray(vertices.size)

            vertices.forEachIndexed { i, v ->
                positions[i * 3] = v.x
                positions[i * 3 + 1] = v.y
                positions[i * 3 + 2] = v.z
                colors[i * 3] = v.r
                colors[i * 3 + 1] = v.g
                colors[i * 3 + 2] = v.b
                texCoords[i * 2] = v.u
                texCoords[i * 2 + 1] = v.v
                normals[i * 3] = v.nx
                normals[i * 3 + 1] = v.ny
                normals[i * 3 + 2] = v.nz
                indices[i] = i
            }

            val mesh = Mesh(positions, colors, texCoords, normals, indices)
            texturedBatches.add(TerrainBatch(mesh, texId))
        }

        return TerrainMeshes(colorMesh, texturedBatches)
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

    /**
     * Create a single terrain mesh with proper tile shape tessellation.
     * This is the legacy non-batched path for simple rendering without textures.
     */
    private fun createTerrainMesh(region: RegionData, plane: Int): Mesh {
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()

        // Pre-compute blended underlay colors for the entire region
        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane)

        var vertexIndex = 0
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        for (x in 0 until 63) {
            for (y in 0 until 63) {
                val tile = region.tiles[plane][x][y]

                // Skip tiles with no overlay and no underlay
                if (tile.overlayId == 0 && tile.underlayId == 0) continue

                // Calculate heights for this tile's corners
                // heights array: [SW, SE, NW, NE]
                val heights = floatArrayOf(
                    region.tiles[plane][x][y].height * HEIGHT_SCALE,         // SW (0,0)
                    region.tiles[plane][x + 1][y].height * HEIGHT_SCALE,     // SE (1,0)
                    region.tiles[plane][x][y + 1].height * HEIGHT_SCALE,     // NW (0,1)
                    region.tiles[plane][x + 1][y + 1].height * HEIGHT_SCALE  // NE (1,1)
                )

                // Get blended underlay color for this tile
                val underlayColor = getBlendedUnderlayColor(blendedUnderlayColors, x, y)
                val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else underlayColor

                // Determine the shape to use
                // If shape is 0 and no overlay, treat as full underlay (shape 12)
                var shape = tile.overlayShape
                if (shape == 0 && tile.overlayId == 0) {
                    shape = 12  // Shape 12 = full underlay (0 overlay faces, 2 underlay faces)
                }

                // Generate shaped vertices for this tile (no textures in legacy path)
                val vertices = generateShapedTile(
                    x, y,
                    shape, tile.overlayRotation,
                    tileSize,
                    heights,
                    overlayColor,
                    underlayColor,
                    -1,  // No overlay texture
                    -1,  // No underlay texture
                    1f,
                    1f
                )

                // Add vertices to arrays
                for (vertex in vertices) {
                    positions.add(vertex.x)
                    positions.add(vertex.y)
                    positions.add(vertex.z)
                    colors.add(vertex.r)
                    colors.add(vertex.g)
                    colors.add(vertex.b)
                    normals.add(vertex.nx)
                    normals.add(vertex.ny)
                    normals.add(vertex.nz)
                    indices.add(vertexIndex)
                    vertexIndex++
                }
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

    /**
     * Get the color for an underlay ID using actual definition data.
     *
     * Underlay colors in the client use pre-computed HSL components that are
     * designed for blending across tiles. We convert these back to a packed
     * HSL format and then to RGB.
     *
     * Client stores: hue (pre-multiplied by chroma/divisor), saturation (0-255),
     * lightness (0-255), chroma (divisor for averaging).
     */
    fun getUnderlayColor(underlayId: Int): Vector3f {
        if (underlayId <= 0) return DEFAULT_GRASS_COLOR

        val defs = underlays
        // RS client uses (underlayId - 1) for definition lookup
        val defIndex = underlayId - 1
        if (defs != null && defIndex >= 0 && defIndex < defs.size) {
            val def = defs[defIndex]

            // Use the pre-computed HSL values from UnderlayDecoder
            // The client's calculateHsl16 computes these for blending purposes
            if (def.chroma > 0) {
                // Reconstruct the packed HSL using the same formula as client's hsl16to24
                // hue needs to be divided by chroma to get the actual hue value
                // Then we pack it as: ((hue * 256 / chroma) & 0xff >> 2) << 10 | sat >> 5 << 7 | lightness >> 1
                val hue = def.hue * 256 / def.chroma
                val packedHsl = ColorUtil.packHsl16(hue, def.saturation, def.lightness)
                return ColorUtil.hslToRgb(packedHsl)
            }

            // Fallback to direct RGB conversion if no HSL data
            if (def.colour != 0) {
                return ColorUtil.rgbToVector(def.colour)
            }
        }

        // Fallback colors for when definitions aren't loaded
        return when (underlayId) {
            1 -> Vector3f(0.6f, 0.5f, 0.3f)   // Dirt
            2 -> Vector3f(0.3f, 0.3f, 0.35f)  // Stone
            3 -> Vector3f(0.2f, 0.3f, 0.6f)   // Water
            4 -> Vector3f(0.9f, 0.85f, 0.7f)  // Sand
            else -> DEFAULT_GRASS_COLOR
        }
    }

    /**
     * Get the color for an overlay ID using actual definition data.
     *
     * Overlay colors in the client use `tileColorHsl` for terrain rendering.
     * The color is pre-converted to packed HSL format by OverlayDecoder.shiftRGBColours().
     *
     * Note: `blendColour` (minimapColorHsl) is for minimap rendering, not terrain.
     */
    fun getOverlayColor(overlayId: Int): Vector3f {
        if (overlayId <= 0) return DEFAULT_GRASS_COLOR

        val defs = overlays
        // RS client uses (overlayId - 1) for definition lookup
        val defIndex = overlayId - 1
        if (defs != null && defIndex >= 0 && defIndex < defs.size) {
            val def = defs[defIndex]
            // Use colour (tileColorHsl) for terrain rendering
            // blendColour is for minimap display, not terrain
            val color = def.colour
            if (color != 0 && color != -1) {
                // Overlay colors are stored as packed HSL by shiftRGBColours in OverlayDecoder
                // Format: hue (6 bits @ 10-15) | sat (3 bits @ 7-9) | lightness (7 bits @ 0-6)
                return ColorUtil.hslToRgb(color)
            }
        }

        // Fallback - return default color
        return DEFAULT_GRASS_COLOR
    }

    /**
     * Get underlay definition by ID.
     * Note: RS client uses (id - 1) for definition lookup.
     */
    fun getUnderlayDefinition(underlayId: Int): UnderlayDefinition? {
        val defs = underlays ?: return null
        val defIndex = underlayId - 1
        return if (defIndex >= 0 && defIndex < defs.size) defs[defIndex] else null
    }

    /**
     * Get overlay definition by ID.
     * Note: RS client uses (id - 1) for definition lookup.
     */
    fun getOverlayDefinition(overlayId: Int): OverlayDefinition? {
        val defs = overlays ?: return null
        val defIndex = overlayId - 1
        return if (defIndex >= 0 && defIndex < defs.size) defs[defIndex] else null
    }

    fun clearCache() {
        meshCache.values.forEach { it?.cleanup() }
        meshCache.clear()
        batchedMeshCache.values.forEach { it?.cleanup() }
        batchedMeshCache.clear()
        regionCache.clear()
    }

    /**
     * Clear all caches including definitions.
     */
    fun clearAll() {
        clearCache()
        underlays = null
        overlays = null
        definitionsLoaded = false
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

    private val DEFAULT_GRASS_COLOR = Vector3f(0.3f, 0.55f, 0.25f)

    /**
     * Height scaling factor to match client.
     * Client stores: -rawHeight * 8 * 4 = -rawHeight * 32 (negative = positive elevation)
     * For OpenGL (Y-up): rawHeight * 32 / 128 = rawHeight / 4
     * This matches the visual scale when combined with tile size.
     */
    private const val HEIGHT_SCALE = 32f * CoordinateUtil.DEFAULT_SCALE  // = 32 / 128 = 0.25

    // ==================== TILE SHAPE TESSELLATION SYSTEM ====================
    //
    // RS tiles use a sophisticated tessellation system where each tile can have
    // different shapes (not just squares). The shape determines how the tile is
    // divided into triangles, with some triangles belonging to the overlay and
    // others to the underlay.
    //
    // There are 13 predefined vertex positions within each tile:
    // Index 0: SW corner (0, 0)
    // Index 1: South midpoint (256, 0)
    // Index 2: SE corner (512, 0)
    // Index 3: East midpoint (512, 256)
    // Index 4: NE corner (512, 512)
    // Index 5: North midpoint (256, 512)
    // Index 6: NW corner (0, 512)
    // Index 7: West midpoint (0, 256)
    // Index 8: Interior (128, 256)
    // Index 9: Interior (256, 384)
    // Index 10: Interior (128, 128)
    // Index 11: Interior (384, 128)
    // Index 12: Center (256, 256)
    //
    // Tile unit is 512, so these define positions as fractions of the tile.

    /**
     * X offsets for the 13 vertex positions within a tile (0-512 scale).
     * Indexed by vertex index 0-12.
     */
    private val TILE_VERTEX_X = intArrayOf(0, 256, 512, 512, 512, 256, 0, 0, 128, 256, 128, 384, 256)

    /**
     * Y offsets for the 13 vertex positions within a tile (0-512 scale).
     * Indexed by vertex index 0-12.
     */
    private val TILE_VERTEX_Y = intArrayOf(0, 0, 0, 256, 512, 512, 512, 256, 256, 384, 128, 128, 256)

    /**
     * Number of triangles belonging to the overlay for each shape (0-14).
     * Shape 12 has 0 overlay faces (empty/special), shape 13 has 4 (full tile).
     */
    private val OVERLAY_FACE_COUNT = intArrayOf(2, 1, 1, 1, 2, 2, 2, 1, 3, 3, 3, 2, 0, 4, 0)

    /**
     * Number of triangles belonging to the underlay for each shape (0-14).
     */
    private val UNDERLAY_FACE_COUNT = intArrayOf(0, 1, 2, 2, 1, 1, 2, 3, 1, 3, 3, 4, 2, 0, 4)

    /**
     * Triangle vertex A indices for each shape.
     * First OVERLAY_FACE_COUNT[shape] entries are overlay triangles,
     * remaining entries are underlay triangles.
     */
    private val TRIANGLE_A = arrayOf(
        intArrayOf(0, 2),           // Shape 0: 2 overlay faces
        intArrayOf(0, 2),           // Shape 1: 1 overlay, 1 underlay
        intArrayOf(0, 0, 2),        // Shape 2: 1 overlay, 2 underlay
        intArrayOf(2, 0, 0),        // Shape 3: 1 overlay, 2 underlay
        intArrayOf(0, 2, 0),        // Shape 4: 2 overlay, 1 underlay
        intArrayOf(0, 0, 2),        // Shape 5: 2 overlay, 1 underlay
        intArrayOf(0, 5, 1, 4),     // Shape 6: 2 overlay, 2 underlay
        intArrayOf(0, 4, 4, 4),     // Shape 7: 1 overlay, 3 underlay
        intArrayOf(4, 4, 4, 0),     // Shape 8: 3 overlay, 1 underlay
        intArrayOf(6, 6, 6, 2, 2, 2), // Shape 9: 3 overlay, 3 underlay
        intArrayOf(2, 2, 2, 6, 6, 6), // Shape 10: 3 overlay, 3 underlay
        intArrayOf(0, 11, 6, 6, 6, 4), // Shape 11: 2 overlay, 4 underlay
        intArrayOf(0, 2),           // Shape 12: 0 overlay, 2 underlay
        intArrayOf(0, 4, 4, 4),     // Shape 13: 4 overlay, 0 underlay
        intArrayOf(0, 4, 4, 4)      // Shape 14: 0 overlay, 4 underlay
    )

    /**
     * Triangle vertex B indices for each shape.
     */
    private val TRIANGLE_B = arrayOf(
        intArrayOf(2, 4),
        intArrayOf(2, 4),
        intArrayOf(5, 2, 4),
        intArrayOf(4, 5, 2),
        intArrayOf(2, 4, 5),
        intArrayOf(5, 2, 4),
        intArrayOf(1, 6, 2, 5),
        intArrayOf(1, 6, 7, 1),
        intArrayOf(6, 7, 1, 1),
        intArrayOf(0, 8, 9, 8, 9, 4),
        intArrayOf(8, 9, 4, 0, 8, 9),
        intArrayOf(2, 10, 0, 10, 11, 11),
        intArrayOf(2, 4),
        intArrayOf(1, 6, 7, 1),
        intArrayOf(1, 6, 7, 1)
    )

    /**
     * Triangle vertex C indices for each shape.
     */
    private val TRIANGLE_C = arrayOf(
        intArrayOf(6, 6),
        intArrayOf(6, 6),
        intArrayOf(6, 5, 5),
        intArrayOf(5, 6, 5),
        intArrayOf(5, 5, 6),
        intArrayOf(6, 5, 5),
        intArrayOf(5, 0, 4, 1),
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 1, 2, 7),
        intArrayOf(8, 9, 4, 0, 8, 9),
        intArrayOf(0, 8, 9, 8, 9, 4),
        intArrayOf(11, 0, 10, 11, 4, 2),
        intArrayOf(6, 6),
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 7, 1, 2)
    )

    /**
     * Data class to hold vertex data for shaped tile generation.
     */
    private data class ShapedVertex(
        val x: Float, val y: Float, val z: Float,
        val r: Float, val g: Float, val b: Float,
        val u: Float, val v: Float,
        val nx: Float, val ny: Float, val nz: Float,
        val textureId: Int
    )

    /**
     * Generates shaped triangles for a tile, returning separate lists for overlay and underlay triangles.
     *
     * @param x Tile X coordinate within region
     * @param y Tile Y coordinate within region
     * @param shape The tile shape (0-11 typically used)
     * @param rotation The shape rotation (0-3)
     * @param tileSize The size of each tile in world units
     * @param heights Array of corner heights [SW, SE, NW, NE]
     * @param overlayColor Color for overlay triangles
     * @param underlayColor Color for underlay triangles
     * @param overlayTextureId Texture ID for overlay (-1 for color only)
     * @param underlayTextureId Texture ID for underlay (-1 for color only)
     * @param overlayUvScale UV scale for overlay texture
     * @param underlayUvScale UV scale for underlay texture
     * @return List of ShapedVertex objects for all triangles
     */
    private fun generateShapedTile(
        x: Int, y: Int,
        shape: Int, rotation: Int,
        tileSize: Float,
        heights: FloatArray, // [h00, h10, h01, h11] = [SW, SE, NW, NE]
        overlayColor: Vector3f,
        underlayColor: Vector3f,
        overlayTextureId: Int,
        underlayTextureId: Int,
        overlayUvScale: Float,
        underlayUvScale: Float
    ): List<ShapedVertex> {
        val vertices = mutableListOf<ShapedVertex>()

        // Clamp shape to valid range
        val safeShape = shape.coerceIn(0, TRIANGLE_A.size - 1)

        val overlayFaces = OVERLAY_FACE_COUNT[safeShape]
        val underlayFaces = UNDERLAY_FACE_COUNT[safeShape]
        val totalFaces = overlayFaces + underlayFaces

        if (totalFaces == 0) return vertices

        val triA = TRIANGLE_A[safeShape]
        val triB = TRIANGLE_B[safeShape]
        val triC = TRIANGLE_C[safeShape]

        // Base position for this tile
        val baseX = x * tileSize
        val baseZ = -y * tileSize  // Flip Z for OpenGL

        // Generate rotated vertex positions for this tile
        // RS rotates UVs and vertex positions based on rotation
        val vertexX = FloatArray(13)
        val vertexZ = FloatArray(13)
        val vertexY = FloatArray(13)  // Heights

        for (i in 0 until 13) {
            // Get base vertex offset (0-512 range)
            var vx = TILE_VERTEX_X[i]
            var vy = TILE_VERTEX_Y[i]

            // Apply rotation (each rotation is 90 degrees CW)
            when (rotation) {
                1 -> {
                    val temp = vx
                    vx = vy
                    vy = 512 - temp
                }
                2 -> {
                    vx = 512 - vx
                    vy = 512 - vy
                }
                3 -> {
                    val temp = vx
                    vx = 512 - vy
                    vy = temp
                }
            }

            // Convert to world coordinates
            vertexX[i] = baseX + (vx.toFloat() / 512f) * tileSize
            vertexZ[i] = baseZ - (vy.toFloat() / 512f) * tileSize  // Flip for OpenGL

            // Interpolate height based on position within tile
            val fx = vx.toFloat() / 512f
            val fy = vy.toFloat() / 512f

            // Bilinear interpolation of heights
            // heights = [SW (0,0), SE (1,0), NW (0,1), NE (1,1)]
            val h00 = heights[0]  // SW
            val h10 = heights[1]  // SE
            val h01 = heights[2]  // NW
            val h11 = heights[3]  // NE

            vertexY[i] = (1 - fx) * (1 - fy) * h00 +
                         fx * (1 - fy) * h10 +
                         (1 - fx) * fy * h01 +
                         fx * fy * h11
        }

        // Generate triangles
        for (face in 0 until totalFaces) {
            if (face >= triA.size) break

            val ia = triA[face]
            val ib = triB[face]
            val ic = triC[face]

            // Get vertex positions
            val ax = vertexX[ia]
            val ay = vertexY[ia]
            val az = vertexZ[ia]
            val bx = vertexX[ib]
            val by = vertexY[ib]
            val bz = vertexZ[ib]
            val cx = vertexX[ic]
            val cy = vertexY[ic]
            val cz = vertexZ[ic]

            // Calculate normal for this triangle
            val v1 = Vector3f(bx - ax, by - ay, bz - az)
            val v2 = Vector3f(cx - ax, cy - ay, cz - az)
            val normal = Vector3f()
            v1.cross(v2, normal).normalize()

            // Determine if this is overlay or underlay
            val isOverlay = face < overlayFaces
            val color = if (isOverlay) overlayColor else underlayColor
            val textureId = if (isOverlay) overlayTextureId else underlayTextureId
            val uvScale = if (isOverlay) overlayUvScale else underlayUvScale

            // Helper to create vertex
            fun createVertex(px: Float, py: Float, pz: Float, vIdx: Int): ShapedVertex {
                // UV coordinates based on position within tile
                val baseVx = TILE_VERTEX_X[vIdx]
                val baseVy = TILE_VERTEX_Y[vIdx]
                var uvx = baseVx
                var uvy = baseVy

                // Apply same rotation to UVs
                when (rotation) {
                    1 -> {
                        val temp = uvx
                        uvx = uvy
                        uvy = 512 - temp
                    }
                    2 -> {
                        uvx = 512 - uvx
                        uvy = 512 - uvy
                    }
                    3 -> {
                        val temp = uvx
                        uvx = 512 - uvy
                        uvy = temp
                    }
                }

                return ShapedVertex(
                    px, py, pz,
                    color.x, color.y, color.z,
                    uvx.toFloat() / 512f * uvScale,
                    uvy.toFloat() / 512f * uvScale,
                    normal.x, normal.y, normal.z,
                    textureId
                )
            }

            vertices.add(createVertex(ax, ay, az, ia))
            vertices.add(createVertex(bx, by, bz, ib))
            vertices.add(createVertex(cx, cy, cz, ic))
        }

        return vertices
    }
}
