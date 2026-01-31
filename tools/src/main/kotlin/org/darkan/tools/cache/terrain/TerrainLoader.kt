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
     * Uses blended underlay colors that match the client's 11x11 averaging algorithm.
     */
    private fun createBatchedTerrainMeshes(region: RegionData, plane: Int): TerrainMeshes {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Pre-compute blended underlay colors for the entire region
        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane)

        // Group tiles by texture ID (-1 for color-only)
        data class TileVertex(
            val x: Float, val y: Float, val z: Float,
            val r: Float, val g: Float, val b: Float,
            val u: Float, val v: Float,
            val nx: Float, val ny: Float, val nz: Float
        )

        // Collect vertices grouped by texture ID
        val textureGroups = mutableMapOf<Int, MutableList<TileVertex>>()

        for (x in 0 until 63) {
            for (y in 0 until 63) {
                val tile = region.tiles[plane][x][y]

                // Calculate heights for this tile's corners
                // Client formula: -rawHeight * 8 * 4 = -rawHeight * 32 (stored as negative, Y-down)
                // For OpenGL (Y-up), we use positive heights for raised terrain
                // Scale: height * 32 / 128 = height / 4
                val h00 = region.tiles[plane][x][y].height * HEIGHT_SCALE
                val h10 = region.tiles[plane][x + 1][y].height * HEIGHT_SCALE
                val h01 = region.tiles[plane][x][y + 1].height * HEIGHT_SCALE
                val h11 = region.tiles[plane][x + 1][y + 1].height * HEIGHT_SCALE

                // Vertex positions
                val x0 = x * tileSize
                val x1 = (x + 1) * tileSize
                val z0 = -y * tileSize
                val z1 = -(y + 1) * tileSize

                // Determine texture ID and colors
                val textureId: Int
                val cornerColors: Array<Vector3f>
                val uvScale: Float

                // Check if overlay has texture
                val overlayDef = if (tile.overlayId > 0) getOverlayDefinition(tile.overlayId) else null
                val underlayDef = if (tile.underlayId > 0) getUnderlayDefinition(tile.underlayId) else null

                if (overlayDef != null && overlayDef.texture >= 0) {
                    // Overlay has texture - tint with overlay color
                    textureId = overlayDef.texture
                    uvScale = overlayDef.scale.toFloat() / 512f
                    // Use overlay color for tinting (texture color * vertex color)
                    // If colour is 0 or -1, use white (no tinting)
                    val tintColor = if (overlayDef.colour > 0 && overlayDef.colour != -1) {
                        ColorUtil.hslToRgb(overlayDef.colour)
                    } else {
                        Vector3f(1f, 1f, 1f)
                    }
                    cornerColors = arrayOf(tintColor, tintColor, tintColor, tintColor)
                } else if (underlayDef != null && underlayDef.texture >= 0) {
                    // Underlay has texture - tint with underlay color
                    textureId = underlayDef.texture
                    uvScale = underlayDef.scale.toFloat() / 512f
                    // Use blended underlay color for tinting
                    val tintColor = getBlendedUnderlayColor(blendedUnderlayColors, x, y)
                    cornerColors = arrayOf(tintColor, tintColor, tintColor, tintColor)
                } else {
                    // Color-only tile - use blended underlay colors
                    textureId = -1
                    uvScale = 1f

                    // Use pre-computed blended underlay color for this tile
                    val underlayColor = getBlendedUnderlayColor(blendedUnderlayColors, x, y)
                    val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else underlayColor

                    if (tile.overlayId > 0 && tile.overlayShape < OVERLAY_SHAPES.size) {
                        val shape = OVERLAY_SHAPES[tile.overlayShape]
                        val rotatedShape = rotateShape(shape, tile.overlayRotation)
                        cornerColors = arrayOf(
                            if (rotatedShape[0]) overlayColor else underlayColor,
                            if (rotatedShape[1]) overlayColor else underlayColor,
                            if (rotatedShape[2]) overlayColor else underlayColor,
                            if (rotatedShape[3]) overlayColor else underlayColor
                        )
                    } else {
                        cornerColors = arrayOf(underlayColor, underlayColor, underlayColor, underlayColor)
                    }
                }

                // Calculate normals
                val v0 = Vector3f(x0, h00, z0)
                val v1 = Vector3f(x1, h10, z0)
                val v2 = Vector3f(x0, h01, z1)
                val v3 = Vector3f(x1, h11, z1)
                val n1 = CoordinateUtil.calculateNormal(v0, v1, v2)
                val n2 = CoordinateUtil.calculateNormal(v1, v3, v2)

                // UV coordinates - apply rotation based on overlay rotation (0-3)
                // RS client transforms UVs based on overlayRotation (see MapLoader.kt):
                // Rotation 0: normal
                // Rotation 1: 90 degrees clockwise
                // Rotation 2: 180 degrees
                // Rotation 3: 270 degrees clockwise (90 CCW)
                //
                // Also apply V-flip to correct OpenGL coordinate system
                // (OpenGL has origin at bottom-left, RS has it at top-left for textures)
                val uvSW: Pair<Float, Float>
                val uvSE: Pair<Float, Float>
                val uvNE: Pair<Float, Float>
                val uvNW: Pair<Float, Float>

                when (tile.overlayRotation) {
                    0 -> {
                        // No rotation, but flip V for OpenGL
                        // RS: SW=(0,0), SE=(512,0), NE=(512,512), NW=(0,512)
                        // After V-flip: SW=(0,scale), SE=(scale,scale), NE=(scale,0), NW=(0,0)
                        uvSW = Pair(0f, uvScale)
                        uvSE = Pair(uvScale, uvScale)
                        uvNE = Pair(uvScale, 0f)
                        uvNW = Pair(0f, 0f)
                    }
                    1 -> {
                        // 90 CW rotation + V flip
                        // RS formula: sizesX=deltaY, sizesY=512-deltaX
                        // RS: SW=(0,512), SE=(0,0), NE=(512,0), NW=(512,512)
                        // After V-flip: SW=(0,0), SE=(0,scale), NE=(scale,scale), NW=(scale,0)
                        uvSW = Pair(0f, 0f)
                        uvSE = Pair(0f, uvScale)
                        uvNE = Pair(uvScale, uvScale)
                        uvNW = Pair(uvScale, 0f)
                    }
                    2 -> {
                        // 180 rotation + V flip
                        // RS formula: sizesX=512-deltaX, sizesY=512-deltaY
                        // RS: SW=(512,512), SE=(0,512), NE=(0,0), NW=(512,0)
                        // After V-flip: SW=(scale,0), SE=(0,0), NE=(0,scale), NW=(scale,scale)
                        uvSW = Pair(uvScale, 0f)
                        uvSE = Pair(0f, 0f)
                        uvNE = Pair(0f, uvScale)
                        uvNW = Pair(uvScale, uvScale)
                    }
                    3 -> {
                        // 270 CW (90 CCW) rotation + V flip
                        // RS formula: sizesX=512-deltaY, sizesY=deltaX
                        // RS: SW=(512,0), SE=(512,512), NE=(0,512), NW=(0,0)
                        // After V-flip: SW=(scale,scale), SE=(scale,0), NE=(0,0), NW=(0,scale)
                        uvSW = Pair(uvScale, uvScale)
                        uvSE = Pair(uvScale, 0f)
                        uvNE = Pair(0f, 0f)
                        uvNW = Pair(0f, uvScale)
                    }
                    else -> {
                        // Default: same as rotation 0
                        uvSW = Pair(0f, uvScale)
                        uvSE = Pair(uvScale, uvScale)
                        uvNE = Pair(uvScale, 0f)
                        uvNW = Pair(0f, 0f)
                    }
                }

                val group = textureGroups.getOrPut(textureId) { mutableListOf() }

                // First triangle: SW, SE, NW (corners 0, 1, 3)
                group.add(TileVertex(x0, h00, z0, cornerColors[0].x, cornerColors[0].y, cornerColors[0].z, uvSW.first, uvSW.second, n1.x, n1.y, n1.z))
                group.add(TileVertex(x1, h10, z0, cornerColors[1].x, cornerColors[1].y, cornerColors[1].z, uvSE.first, uvSE.second, n1.x, n1.y, n1.z))
                group.add(TileVertex(x0, h01, z1, cornerColors[3].x, cornerColors[3].y, cornerColors[3].z, uvNW.first, uvNW.second, n1.x, n1.y, n1.z))

                // Second triangle: SE, NE, NW (corners 1, 2, 3)
                group.add(TileVertex(x1, h10, z0, cornerColors[1].x, cornerColors[1].y, cornerColors[1].z, uvSE.first, uvSE.second, n2.x, n2.y, n2.z))
                group.add(TileVertex(x1, h11, z1, cornerColors[2].x, cornerColors[2].y, cornerColors[2].z, uvNE.first, uvNE.second, n2.x, n2.y, n2.z))
                group.add(TileVertex(x0, h01, z1, cornerColors[3].x, cornerColors[3].y, cornerColors[3].z, uvNW.first, uvNW.second, n2.x, n2.y, n2.z))
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

                // Calculate heights for this tile's corners
                // Client formula: -rawHeight * 8 * 4 = -rawHeight * 32 (stored as negative, Y-down)
                // For OpenGL (Y-up), we use positive heights for raised terrain
                // Scale: height * 32 / 128 = height / 4
                val h00 = region.tiles[plane][x][y].height * HEIGHT_SCALE
                val h10 = region.tiles[plane][x + 1][y].height * HEIGHT_SCALE
                val h01 = region.tiles[plane][x][y + 1].height * HEIGHT_SCALE
                val h11 = region.tiles[plane][x + 1][y + 1].height * HEIGHT_SCALE

                // Vertex positions
                val x0 = x * tileSize
                val x1 = (x + 1) * tileSize
                val z0 = -y * tileSize
                val z1 = -(y + 1) * tileSize

                // Get blended underlay color for this tile
                val underlayColor = getBlendedUnderlayColor(blendedUnderlayColors, x, y)
                val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else underlayColor

                // Determine corner colors based on overlay shape
                // Corner order: [SW, SE, NE, NW] = [(x, y), (x+1, y), (x+1, y+1), (x, y+1)]
                val cornerColors: Array<Vector3f>
                if (tile.overlayId > 0 && tile.overlayShape < OVERLAY_SHAPES.size) {
                    val shape = OVERLAY_SHAPES[tile.overlayShape]
                    val rotatedShape = rotateShape(shape, tile.overlayRotation)
                    cornerColors = arrayOf(
                        if (rotatedShape[0]) overlayColor else underlayColor, // SW corner (x, y)
                        if (rotatedShape[1]) overlayColor else underlayColor, // SE corner (x+1, y)
                        if (rotatedShape[2]) overlayColor else underlayColor, // NE corner (x+1, y+1)
                        if (rotatedShape[3]) overlayColor else underlayColor  // NW corner (x, y+1)
                    )
                } else {
                    // No overlay or unknown shape - use underlay for all corners
                    cornerColors = arrayOf(underlayColor, underlayColor, underlayColor, underlayColor)
                }

                // First triangle: SW, SE, NW (corners 0, 1, 3)
                positions.addAll(listOf(x0, h00, z0))  // SW
                positions.addAll(listOf(x1, h10, z0))  // SE
                positions.addAll(listOf(x0, h01, z1))  // NW

                colors.addAll(listOf(cornerColors[0].x, cornerColors[0].y, cornerColors[0].z))  // SW
                colors.addAll(listOf(cornerColors[1].x, cornerColors[1].y, cornerColors[1].z))  // SE
                colors.addAll(listOf(cornerColors[3].x, cornerColors[3].y, cornerColors[3].z))  // NW

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

                // Second triangle: SE, NE, NW (corners 1, 2, 3)
                positions.addAll(listOf(x1, h10, z0))  // SE
                positions.addAll(listOf(x1, h11, z1))  // NE
                positions.addAll(listOf(x0, h01, z1))  // NW

                colors.addAll(listOf(cornerColors[1].x, cornerColors[1].y, cornerColors[1].z))  // SE
                colors.addAll(listOf(cornerColors[2].x, cornerColors[2].y, cornerColors[2].z))  // NE
                colors.addAll(listOf(cornerColors[3].x, cornerColors[3].y, cornerColors[3].z))  // NW

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

    /**
     * Overlay shapes define which corners of a tile use overlay vs underlay color.
     * Each shape is an array of 4 booleans [SW, SE, NE, NW] indicating overlay corners.
     * RS uses 23 predefined shapes (0-22).
     *
     * Shapes are:
     * 0: Full tile (all overlay)
     * 1-8: Half tiles with various orientations
     * 9-16: Diagonal and corner shapes
     * 17-22: Detailed corner shapes
     */
    private val OVERLAY_SHAPES: Array<BooleanArray> = arrayOf(
        // Shape 0: Full tile - all overlay
        booleanArrayOf(true, true, true, true),

        // Shape 1-4: Half tiles (two adjacent corners)
        booleanArrayOf(true, true, false, false),  // 1: South half (SW, SE)
        booleanArrayOf(false, true, true, false),  // 2: East half (SE, NE)
        booleanArrayOf(false, false, true, true),  // 3: North half (NE, NW)
        booleanArrayOf(true, false, false, true),  // 4: West half (SW, NW)

        // Shape 5-8: Diagonal half tiles
        booleanArrayOf(true, false, true, false),  // 5: Diagonal SW-NE
        booleanArrayOf(false, true, false, true),  // 6: Diagonal SE-NW
        booleanArrayOf(true, true, false, true),   // 7: All except NE
        booleanArrayOf(true, true, true, false),   // 8: All except NW

        // Shape 9-12: Single corner
        booleanArrayOf(true, false, false, false), // 9: Only SW
        booleanArrayOf(false, true, false, false), // 10: Only SE
        booleanArrayOf(false, false, true, false), // 11: Only NE
        booleanArrayOf(false, false, false, true), // 12: Only NW

        // Shape 13-16: Three corners (L-shapes)
        booleanArrayOf(true, true, false, true),   // 13: All except NE (alternative)
        booleanArrayOf(true, true, true, false),   // 14: All except NW (alternative)
        booleanArrayOf(false, true, true, true),   // 15: All except SW
        booleanArrayOf(true, false, true, true),   // 16: All except SE

        // Shape 17-22: Detailed corner and edge shapes
        booleanArrayOf(true, false, false, true),  // 17: SW and NW (west edge)
        booleanArrayOf(true, true, false, false),  // 18: SW and SE (south edge)
        booleanArrayOf(false, true, true, false),  // 19: SE and NE (east edge)
        booleanArrayOf(false, false, true, true),  // 20: NE and NW (north edge)
        booleanArrayOf(true, false, true, false),  // 21: Diagonal (SW, NE)
        booleanArrayOf(false, true, false, true)   // 22: Diagonal (SE, NW)
    )

    /**
     * Rotate a shape by the given number of 90-degree clockwise rotations.
     * The corners are ordered [SW, SE, NE, NW], so rotating CW shifts the indices.
     */
    private fun rotateShape(shape: BooleanArray, rotation: Int): BooleanArray {
        if (rotation == 0) return shape

        val result = BooleanArray(4)
        val rot = rotation and 3  // Ensure rotation is 0-3

        // Clockwise rotation: SW -> SE -> NE -> NW -> SW
        // Each rotation shifts indices by 1
        for (i in 0 until 4) {
            result[(i + rot) and 3] = shape[i]
        }
        return result
    }
}
