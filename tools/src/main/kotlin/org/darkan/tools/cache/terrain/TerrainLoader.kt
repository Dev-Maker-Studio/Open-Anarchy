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

    // Lazy-loaded definitions from cache
    private var underlays: Array<UnderlayDefinition>? = null
    private var overlays: Array<OverlayDefinition>? = null
    private var definitionsLoaded = false

    /** Enable/disable terrain color blending. When disabled, uses raw underlay colors. */
    var enableBlending = true

    /**
     * Enable/disable enhanced tile tessellation. When enabled, simple square tiles (shapes 0 and 12)
     * are subdivided into 4 triangles using a center vertex. This allows proper HSL-space color
     * interpolation instead of GPU RGB interpolation, resulting in smoother color gradients
     * across tiles with varying corner colors (e.g., at terrain type boundaries).
     *
     * When disabled, simple tiles use only 2 triangles with 4 corners, and the GPU performs
     * linear RGB interpolation which can produce slightly different results than HSL interpolation.
     */
    var enableEnhancedTessellation = true

    /** Enable/disable overlay rendering. When disabled, only underlay tiles are rendered. */
    var enableOverlays = true

    /**
     * Enable/disable terrain lighting. When enabled, applies per-vertex lighting based on
     * height gradients, matching the RS client's GroundGL lighting algorithm.
     * This creates visible color variation even on uniformly-colored terrain.
     */
    var enableLighting = true

    /**
     * Enable/disable per-vertex color noise. When enabled, adds subtle random brightness
     * variation to terrain colors, matching the RS client's HSL-to-RGB conversion which
     * adds +-1.5% random brightness (d_1 = 0.7 + Math.random() * 0.03 - 0.015).
     *
     * This breaks up the uniformity of large terrain areas. The noise is deterministic
     * based on vertex position to avoid shimmering.
     */
    var enableColorNoise = true

    /**
     * Amount of color noise variation (0.03 = +-1.5%, 0.06 = +-3%).
     * RS client uses approximately 0.03 (+-1.5%).
     */
    var colorNoiseAmount = 0.03f

    // Lighting configuration - matches RS client defaults from EnvironmentManager.kt
    // RS default sun position: (-200, -240, -200) -> normalized: (-0.53, -0.64, -0.53)
    // These can be adjusted via ImGui to match specific RS environments

    /** Light direction X component */
    var LIGHT_DIRECTION_X = 0.163f

    /** Light direction Y component */
    var LIGHT_DIRECTION_Y = 0.070f

    /** Light direction Z component */
    var LIGHT_DIRECTION_Z = 1.00f

    /** Sun brightness - base ambient factor */
    var SUN_BRIGHTNESS = 1.2f

    /** Bright factor - applied when surface faces light */
    var BRIGHT_FACTOR = 0.865f

    /** Dark factor - applied when surface faces away from light */
    var DARK_FACTOR = 0.814f

    // Legacy parameters for backward compatibility
    /** Ambient light factor (minimum brightness) - mapped to SUN_BRIGHTNESS */
    var AMBIENT_LIGHT: Float
        get() = SUN_BRIGHTNESS
        set(value) { SUN_BRIGHTNESS = value }

    /** Diffuse light strength - mapped to BRIGHT_FACTOR */
    var DIFFUSE_STRENGTH: Float
        get() = BRIGHT_FACTOR
        set(value) { BRIGHT_FACTOR = value }

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

        // Debug: Print some underlay definitions and color comparisons
        if (debugDefinitions) {
            debugDefinitions = false
            println("[TerrainLoader] Loaded ${underlays?.size ?: 0} underlay definitions")
            println("\n=== COLOR CONVERSION COMPARISON (HSL vs old HSV) ===")
            underlays?.take(10)?.forEachIndexed { idx, def ->
                println("  Underlay $idx: colour=0x${def.colour.toString(16)}, hue=${def.hue}, sat=${def.saturation}, light=${def.lightness}, chroma=${def.chroma}")
                // Pack and compare color conversion
                if (def.chroma > 0) {
                    val hue = def.hue * 256 / def.chroma
                    val packedHsl = ColorUtil.packHsl16(hue, def.saturation, def.lightness)
                    ColorUtil.debugColorComparison(packedHsl, "    Underlay $idx")
                }
            }
            println("=== END COLOR COMPARISON ===\n")

            // Trace through a typical grass color step by step
            println("\n=== DETAILED COLOR TRACE (typical grass) ===")
            ColorUtil.debugHslConversionTrace(0x3212)  // 12818 - typical grass green
            ColorUtil.debugHslConversionTrace(0x3210)  // 12816 - similar grass
        }
    }

    private var debugDefinitions = true

    /**
     * Compute blended underlay colors for a region plane using the RS client's EXACT algorithm.
     *
     * The client uses a sliding window approach with 5-tile radius (11x11 area) to blend
     * underlay colors smoothly across tile boundaries. This is the exact algorithm from
     * MapLoader.calculateUnderlayPalette() in the RS client.
     *
     * Key aspects of the RS algorithm:
     * 1. Maintains per-column accumulators (hue, saturation, lightness, divisor, count)
     * 2. Uses sliding window from x = -5 to regionSizeX
     * 3. For each column, accumulates row totals with y sliding window from -5 to regionSizeY
     * 4. Hue is weighted by chroma (divisor): finalHue = hueSum * 256 / divisorSum
     * 5. Saturation and lightness are simple averages: sat/depth, light/depth
     *
     * @param region The region data containing tile information
     * @param plane The plane level to compute colors for
     * @return A 64x64 array of packed HSL color values, one for each tile
     */
    private fun computeBlendedUnderlayColors(region: RegionData, plane: Int): Array<IntArray> {
        val blendedColors = Array(64) { IntArray(64) }
        val underlayDefs = underlays ?: return blendedColors

        // If blending is disabled, return raw underlay colors
        if (!enableBlending) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val underlayId = region.tiles[plane][x][y].underlayId
                    val defIndex = underlayId - 1
                    if (defIndex >= 0 && defIndex < underlayDefs.size) {
                        val def = underlayDefs[defIndex]
                        if (def.chroma > 0) {
                            val hue = def.hue * 256 / def.chroma
                            blendedColors[x][y] = ColorUtil.packHsl16(hue, def.saturation, def.lightness)
                        }
                    }
                }
            }
            return blendedColors
        }

        // Debug: Track how many tiles get blended colors
        var nonZeroCount = 0

        // Column accumulators for the sliding window (exactly matching RS client)
        // These accumulate values for all tiles in the current X-window for each Y row
        val underlayHue = IntArray(64)
        val underlaySaturation = IntArray(64)
        val underlayLightness = IntArray(64)
        val underlayDivisor = IntArray(64)  // Called "divisor" in RS client, same as "chroma"
        val underlayCount = IntArray(64)

        // X sliding window: iterate from -5 to include the leading edge properly
        for (x in -5 until 64) {
            // Update column accumulators: add at forward edge (x+5), subtract at back edge (x-5)
            for (y in 0 until 64) {
                // Add tile at x+5 (forward edge of X window)
                val forwardX = x + 5
                if (forwardX in 0 until 64) {
                    val underlayId = region.tiles[plane][forwardX][y].underlayId
                    if (underlayId > 0) {
                        val def = underlayDefs.getOrNull(underlayId - 1)
                        if (def != null) {
                            underlayHue[y] += def.hue
                            underlaySaturation[y] += def.saturation
                            underlayLightness[y] += def.lightness
                            underlayDivisor[y] += def.chroma
                            underlayCount[y]++
                        }
                    }
                }

                // Subtract tile at x-5 (back edge of X window)
                val backwardX = x - 5
                if (backwardX in 0 until 64) {
                    val underlayId = region.tiles[plane][backwardX][y].underlayId
                    if (underlayId > 0) {
                        val def = underlayDefs.getOrNull(underlayId - 1)
                        if (def != null) {
                            underlayHue[y] -= def.hue
                            underlaySaturation[y] -= def.saturation
                            underlayLightness[y] -= def.lightness
                            underlayDivisor[y] -= def.chroma
                            underlayCount[y]--
                        }
                    }
                }
            }

            // For x >= 0, compute blended colors by accumulating Y window
            if (x >= 0) {
                var hueSum = 0
                var saturationSum = 0
                var lightnessSum = 0
                var divisorSum = 0
                var depth = 0

                // Y sliding window
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

                    // Compute blended color for tile (x, y) - exactly matching RS client formula
                    if (y >= 0 && divisorSum > 0 && depth > 0) {
                        // RS client formula from MapLoader.kt line 167:
                        // paletteIndices[x][y] = ColorUtil.hsl16to24(hueSum * 256 / dividerSum, saturationSum / depth, lightnessSum / depth)
                        val hue = hueSum * 256 / divisorSum
                        val saturation = saturationSum / depth
                        val lightness = lightnessSum / depth
                        blendedColors[x][y] = ColorUtil.packHsl16(hue, saturation, lightness)
                        nonZeroCount++
                    }
                }
            }
        }

        // Debug output for tile (32, 32) to verify blending is working
        if (debugBlendingOnce) {
            debugBlendingOnce = false
            println("[TerrainLoader] === BLENDING DEBUG (enableBlending=$enableBlending) ===")
            println("[TerrainLoader] Blending stats: $nonZeroCount non-zero colors")

            // Print detailed info for tile (32, 32)
            val debugX = 32
            val debugY = 32
            val blendedHsl = blendedColors[debugX][debugY]
            val tile = region.tiles[plane][debugX][debugY]
            val rawId = tile.underlayId
            val rawDef = if (rawId > 0) underlayDefs.getOrNull(rawId - 1) else null

            println("[TerrainLoader] Tile ($debugX, $debugY) analysis:")
            println("  underlayId = $rawId")
            if (rawDef != null) {
                println("  Raw underlay definition:")
                println("    colour (RGB24) = 0x${rawDef.colour.toString(16)}")
                println("    hue = ${rawDef.hue}")
                println("    saturation = ${rawDef.saturation}")
                println("    lightness = ${rawDef.lightness}")
                println("    chroma = ${rawDef.chroma}")
                val rawHsl = if (rawDef.chroma > 0) {
                    ColorUtil.packHsl16(rawDef.hue * 256 / rawDef.chroma, rawDef.saturation, rawDef.lightness)
                } else 0
                val rawRgb = ColorUtil.hslToRgb(rawHsl)
                println("  Raw packed HSL = $rawHsl")
                println("  Raw RGB = (${rawRgb.x}, ${rawRgb.y}, ${rawRgb.z})")
            }

            val blendedRgb = ColorUtil.hslToRgb(blendedHsl)
            println("  Blended packed HSL = $blendedHsl")
            println("  Blended RGB = (${blendedRgb.x}, ${blendedRgb.y}, ${blendedRgb.z})")

            // Check neighbors to verify blending is affecting colors
            println("  Neighbor underlay IDs:")
            for (dy in -2..2) {
                val row = StringBuilder("    y=${debugY + dy}: ")
                for (dx in -2..2) {
                    val nx = (debugX + dx).coerceIn(0, 63)
                    val ny = (debugY + dy).coerceIn(0, 63)
                    row.append("${region.tiles[plane][nx][ny].underlayId} ")
                }
                println(row)
            }
        }

        return blendedColors
    }

    // Debug flags - only print once
    private var debugBlendingOnce = true
    private var debugVertexColorsOnce = true
    private var debugTileBoundaryOnce = true

    /**
     * Verify that corner colors are consistent between adjacent tiles.
     * For smooth rendering, tile A's SE corner must match tile B's SW corner (where B is to the right of A).
     * This function checks all adjacent tile pairs and reports any mismatches.
     */
    private fun verifyCornerColorConsistency(blendedColors: Array<IntArray>) {
        println("[TerrainLoader] === CORNER COLOR CONSISTENCY CHECK ===")
        var mismatches = 0

        // Check horizontal adjacencies (tile x's SE corner = tile x+1's SW corner)
        for (x in 0 until 62) {
            for (y in 0 until 63) {
                // Tile (x,y)'s SE corner uses blendedColors[x+1][y]
                // Tile (x+1,y)'s SW corner uses blendedColors[x+1][y]
                // These should be identical (same lookup)
                val tileASE = blendedColors[(x + 1).coerceAtMost(63)][y]
                val tileBSW = blendedColors[(x + 1).coerceAtMost(63)][y]
                if (tileASE != tileBSW) {
                    mismatches++
                    if (mismatches <= 5) {
                        println("  H-mismatch at ($x,$y)-(${x+1},$y): SE=$tileASE, SW=$tileBSW")
                    }
                }
            }
        }

        // Check vertical adjacencies (tile y's NW corner = tile y+1's SW corner)
        for (x in 0 until 63) {
            for (y in 0 until 62) {
                // Tile (x,y)'s NW corner uses blendedColors[x][y+1]
                // Tile (x,y+1)'s SW corner uses blendedColors[x][y+1]
                // These should be identical (same lookup)
                val tileANW = blendedColors[x][(y + 1).coerceAtMost(63)]
                val tileBSW = blendedColors[x][(y + 1).coerceAtMost(63)]
                if (tileANW != tileBSW) {
                    mismatches++
                    if (mismatches <= 5) {
                        println("  V-mismatch at ($x,$y)-(${x},$y+1): NW=$tileANW, SW=$tileBSW")
                    }
                }
            }
        }

        println("[TerrainLoader] Total corner color mismatches: $mismatches")

        // Print a few sample corner lookups to verify the algorithm
        println("[TerrainLoader] Sample corner color lookups (should show gradient):")
        for (testX in listOf(10, 30, 50)) {
            val testY = 30
            val sw = blendedColors[testX][testY]
            val se = blendedColors[(testX + 1).coerceAtMost(63)][testY]
            val nw = blendedColors[testX][(testY + 1).coerceAtMost(63)]
            val ne = blendedColors[(testX + 1).coerceAtMost(63)][(testY + 1).coerceAtMost(63)]
            println("  Tile ($testX, $testY): SW=0x${sw.toString(16)} SE=0x${se.toString(16)} NW=0x${nw.toString(16)} NE=0x${ne.toString(16)}")

            // Also show what the interpolated midpoint would be
            val midHsl = ColorUtil.bilinearBlendHsl(sw, se, nw, ne, 0.5f, 0.5f)
            println("    Center interpolation: 0x${midHsl.toString(16)}")
        }
    }

    /**
     * Get blended underlay color for a specific position from pre-computed blended colors.
     * Returns the packed HSL value for further processing.
     */
    private fun getBlendedUnderlayColorHsl(blendedColors: Array<IntArray>, x: Int, y: Int): Int {
        if (x !in 0 until 64 || y !in 0 until 64) return 0
        return blendedColors[x][y]
    }

    /**
     * Get the 4 corner HSL colors for a tile from the blended color array.
     * Returns packed HSL values in order: [SW, SE, NW, NE]
     *
     * RS client uses paletteIndices positions:
     * - SW corner (0,0): paletteIndices[x][y]
     * - NW corner (0,1): paletteIndices[x][y+1]
     * - NE corner (1,1): paletteIndices[x+1][y+1]
     * - SE corner (1,0): paletteIndices[x+1][y]
     *
     * Note: blendedColors is 64x64, so we must clamp indices to valid range.
     * The tile at (x,y) uses blended colors from tiles at its corners.
     */
    private fun getCornerHslColors(blendedColors: Array<IntArray>, x: Int, y: Int): IntArray {
        // Clamp indices to valid range (0-63) for the 64x64 array
        val seX = (x + 1).coerceAtMost(63)
        val nwY = (y + 1).coerceAtMost(63)
        val neX = (x + 1).coerceAtMost(63)
        val neY = (y + 1).coerceAtMost(63)

        val swHsl = getBlendedUnderlayColorHsl(blendedColors, x, y)
        val seHsl = getBlendedUnderlayColorHsl(blendedColors, seX, y)
        val nwHsl = getBlendedUnderlayColorHsl(blendedColors, x, nwY)
        val neHsl = getBlendedUnderlayColorHsl(blendedColors, neX, neY)

        // Debug: Log first few tiles with varying corner colors
        if (debugCornerColorsCount < 5 && (swHsl != seHsl || swHsl != nwHsl || swHsl != neHsl)) {
            debugCornerColorsCount++
            println("[TerrainLoader] Tile ($x, $y) has varying corner colors:")
            println("  SW=$swHsl SE=$seHsl NW=$nwHsl NE=$neHsl")
        }

        return intArrayOf(swHsl, seHsl, nwHsl, neHsl)
    }

    /**
     * Convert packed HSL corner colors to RGB Vector3f array.
     * Used for overlay colors that don't need HSL blending.
     */
    private fun hslCornersToRgb(hslCorners: IntArray): Array<Vector3f> {
        return arrayOf(
            if (hslCorners[0] != 0) ColorUtil.hslToRgb(hslCorners[0]) else DEFAULT_GRASS_COLOR,
            if (hslCorners[1] != 0) ColorUtil.hslToRgb(hslCorners[1]) else DEFAULT_GRASS_COLOR,
            if (hslCorners[2] != 0) ColorUtil.hslToRgb(hslCorners[2]) else DEFAULT_GRASS_COLOR,
            if (hslCorners[3] != 0) ColorUtil.hslToRgb(hslCorners[3]) else DEFAULT_GRASS_COLOR
        )
    }

    private var debugCornerColorsCount = 0
    private var debugShapedTileOnce = true

    fun loadRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY
        return regionCache.getOrPut(regionId) {
            decodeRegion(cache, regionX, regionY)
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

        // Debug lighting configuration
        if (debugLightingOnce) {
            debugLightingOnce = false
            println("\n=== TERRAIN LIGHTING DEBUG (RS-Style) ===")
            println("  enableLighting = $enableLighting")
            println("  lightDirection = ($LIGHT_DIRECTION_X, $LIGHT_DIRECTION_Y, $LIGHT_DIRECTION_Z)")
            println("  RS default sun position: (-200, -240, -200) -> normalized: (-0.53, -0.64, -0.53)")
            println("  sunBrightness = $SUN_BRIGHTNESS (RS default: ~0.9)")
            println("  brightFactor = $BRIGHT_FACTOR (RS default: 0.7, for lit surfaces)")
            println("  darkFactor = $DARK_FACTOR (RS default: 1.2, for shadowed surfaces)")
            println("  RS formula: factor = sunBrightness + dot * (dot > 0 ? brightFactor : darkFactor)")
            // Calculate expected range: when dot = 1 (fully lit) and dot = -1 (fully shadowed)
            val maxLit = SUN_BRIGHTNESS + 1.0f * BRIGHT_FACTOR
            val maxShadow = SUN_BRIGHTNESS + (-1.0f) * DARK_FACTOR
            println("  Expected factor range: $maxShadow (shadow) to $maxLit (lit)")
        }

        // Pre-compute per-vertex normals for the entire 65x65 grid of tile vertices
        // This matches the RS client's GroundGL.kt lines 98-106 which computes normals
        // from height differences between neighboring tiles, creating smooth per-vertex
        // lighting variation even in homogeneous areas.
        val vertexNormalsX = Array(65) { FloatArray(65) }
        val vertexNormalsY = Array(65) { FloatArray(65) }
        val vertexNormalsZ = Array(65) { FloatArray(65) }

        for (vx in 0 until 65) {
            for (vy in 0 until 65) {
                // Get heights at neighboring vertices (clamped to valid range)
                // RS client uses heights[x+1][y] - heights[x-1][y] for the gradient
                val xMinus = (vx - 1).coerceIn(0, 63)
                val xPlus = (vx + 1).coerceIn(0, 63)
                val yMinus = (vy - 1).coerceIn(0, 63)
                val yPlus = (vy + 1).coerceIn(0, 63)
                val xCur = vx.coerceIn(0, 63)
                val yCur = vy.coerceIn(0, 63)

                // Get heights at the neighboring vertices
                // Note: tile heights are stored at [plane][x][y], and the height at vertex (vx, vy)
                // is the height of tile (vx, vy) corner
                val heightWest = region.tiles[plane][xMinus][yCur].height * HEIGHT_SCALE
                val heightEast = region.tiles[plane][xPlus][yCur].height * HEIGHT_SCALE
                val heightSouth = region.tiles[plane][xCur][yMinus].height * HEIGHT_SCALE
                val heightNorth = region.tiles[plane][xCur][yPlus].height * HEIGHT_SCALE

                // Compute height gradients (matching RS client lines 100-101)
                // heightDeltaHorizontal = heights[x+1][y] - heights[x-1][y]
                // heightDeltaVertical = heights[x][y+1] - heights[x][y-1]
                val heightDeltaX = heightEast - heightWest
                val heightDeltaY = heightNorth - heightSouth

                // Compute normal from gradients (matching RS client lines 102-105)
                // The RS client uses: intensity = 1 / sqrt(512*512*4 + dX^2 + dY^2)
                // normalX = heightDeltaX * intensity
                // normalY = -512*2 * intensity (pointing up in RS coords)
                // normalZ = heightDeltaY * intensity
                // We simplify to normalized tangent cross product
                val tileScale = tileSize // One tile width
                val nx = heightDeltaX
                val ny = tileScale * 2f  // Corresponds to -512*2 in RS (Y-up in OpenGL)
                val nz = heightDeltaY

                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 0.0001f) {
                    vertexNormalsX[vx][vy] = nx / len
                    vertexNormalsY[vx][vy] = ny / len
                    vertexNormalsZ[vx][vy] = nz / len
                } else {
                    vertexNormalsX[vx][vy] = 0f
                    vertexNormalsY[vx][vy] = 1f
                    vertexNormalsZ[vx][vy] = 0f
                }
            }
        }

        // Pre-compute blended underlay colors for the entire region
        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane)

        // Debug: Verify corner color consistency between adjacent tiles
        if (debugVertexColorsOnce) {
            debugVertexColorsOnce = false
            verifyCornerColorConsistency(blendedUnderlayColors)
        }

        // Debug: Trace EXACT vertex colors at tile boundaries
        if (debugTileBoundaryOnce) {
            debugTileBoundaryOnce = false
            println("\n=== TILE BOUNDARY DEBUG ===")

            // Find adjacent tiles that are BOTH pure underlay (no overlay)
            var foundPureUnderlay = false
            var testX = -1
            var testY = -1
            for (x in 5 until 60) {
                for (y in 5 until 60) {
                    val tile1 = region.tiles[plane][x][y]
                    val tile2 = region.tiles[plane][x + 1][y]
                    // Both tiles must have underlay but no overlay, and different underlay IDs
                    if (tile1.underlayId > 0 && tile2.underlayId > 0 &&
                        tile1.overlayId == 0 && tile2.overlayId == 0 &&
                        tile1.underlayId != tile2.underlayId) {
                        testX = x
                        testY = y
                        foundPureUnderlay = true
                        break
                    }
                }
                if (foundPureUnderlay) break
            }

            if (!foundPureUnderlay) {
                // Find ANY adjacent pure underlay tiles
                for (x in 5 until 60) {
                    for (y in 5 until 60) {
                        val tile1 = region.tiles[plane][x][y]
                        val tile2 = region.tiles[plane][x + 1][y]
                        if (tile1.underlayId > 0 && tile2.underlayId > 0 &&
                            tile1.overlayId == 0 && tile2.overlayId == 0) {
                            testX = x
                            testY = y
                            foundPureUnderlay = true
                            break
                        }
                    }
                    if (foundPureUnderlay) break
                }
            }

            // Also find a tile with varying corner colors (where gradient should be visible)
            println("\n=== LOOKING FOR TILES WITH VISIBLE GRADIENT (varying corner colors) ===")
            var foundGradient = false
            for (x in 5 until 60) {
                for (y in 5 until 60) {
                    val tile = region.tiles[plane][x][y]
                    if (tile.underlayId > 0 && tile.overlayId == 0) {
                        val sw = blendedUnderlayColors[x][y]
                        val se = blendedUnderlayColors[(x + 1).coerceAtMost(63)][y]
                        val nw = blendedUnderlayColors[x][(y + 1).coerceAtMost(63)]
                        val ne = blendedUnderlayColors[(x + 1).coerceAtMost(63)][(y + 1).coerceAtMost(63)]
                        if (sw != se || sw != nw || sw != ne) {
                            println("  Tile ($x,$y) has gradient: SW=$sw SE=$se NW=$nw NE=$ne")
                            println("    underlayId=${tile.underlayId}")

                            // Check adjacent tile to see if colors match at boundary
                            val tile2 = region.tiles[plane][(x + 1).coerceAtMost(63)][y]
                            if (tile2.underlayId > 0 && tile2.overlayId == 0) {
                                val tile2_sw = blendedUnderlayColors[(x + 1).coerceAtMost(63)][y]
                                val tile2_nw = blendedUnderlayColors[(x + 1).coerceAtMost(63)][(y + 1).coerceAtMost(63)]
                                println("    Adjacent tile (${x+1},$y): SW=$tile2_sw NW=$tile2_nw")
                                println("    Boundary match? SE==SW:${se==tile2_sw}, NE==NW:${ne==tile2_nw}")
                            }
                            foundGradient = true
                            if (foundGradient) break
                        }
                    }
                }
                if (foundGradient) break
            }

            if (foundPureUnderlay) {
                println("Found PURE UNDERLAY adjacent tiles at ($testX,$testY) and (${testX+1},$testY)")
                val tile1 = region.tiles[plane][testX][testY]
                val tile2 = region.tiles[plane][testX + 1][testY]
                println("  Tile ($testX,$testY): underlayId=${tile1.underlayId}, overlayId=${tile1.overlayId}")
                println("  Tile (${testX+1},$testY): underlayId=${tile2.underlayId}, overlayId=${tile2.overlayId}")

                // Check the shared edge colors
                val tile1_SE = blendedUnderlayColors[(testX + 1).coerceAtMost(63)][testY]
                val tile2_SW = blendedUnderlayColors[(testX + 1).coerceAtMost(63)][testY]
                val tile1_NE = blendedUnderlayColors[(testX + 1).coerceAtMost(63)][(testY + 1).coerceAtMost(63)]
                val tile2_NW = blendedUnderlayColors[(testX + 1).coerceAtMost(63)][(testY + 1).coerceAtMost(63)]

                println("\n=== SHARED EDGE COLORS ===")
                println("  Tile ($testX,$testY) SE = blendedColors[${testX+1}][$testY] = $tile1_SE (0x${tile1_SE.toString(16)})")
                println("  Tile (${testX+1},$testY) SW = blendedColors[${testX+1}][$testY] = $tile2_SW (0x${tile2_SW.toString(16)})")
                println("  MATCH? ${tile1_SE == tile2_SW}")

                val rgb1 = ColorUtil.hslToRgb(tile1_SE)
                val rgb2 = ColorUtil.hslToRgb(tile2_SW)
                println("  RGB at shared edge: (${rgb1.x}, ${rgb1.y}, ${rgb1.z})")

                // Also check the 4 corners of tile1 to see the gradient
                val sw = blendedUnderlayColors[testX][testY]
                val se = blendedUnderlayColors[(testX + 1).coerceAtMost(63)][testY]
                val nw = blendedUnderlayColors[testX][(testY + 1).coerceAtMost(63)]
                val ne = blendedUnderlayColors[(testX + 1).coerceAtMost(63)][(testY + 1).coerceAtMost(63)]
                println("\n  Tile ($testX,$testY) corner HSLs: SW=$sw SE=$se NW=$nw NE=$ne")

                val rgbSW = ColorUtil.hslToRgb(sw)
                val rgbSE = ColorUtil.hslToRgb(se)
                val rgbNW = ColorUtil.hslToRgb(nw)
                val rgbNE = ColorUtil.hslToRgb(ne)
                println("  SW RGB: (${rgbSW.x}, ${rgbSW.y}, ${rgbSW.z})")
                println("  SE RGB: (${rgbSE.x}, ${rgbSE.y}, ${rgbSE.z})")
                println("  NW RGB: (${rgbNW.x}, ${rgbNW.y}, ${rgbNW.z})")
                println("  NE RGB: (${rgbNE.x}, ${rgbNE.y}, ${rgbNE.z})")

            } else {
                println("No adjacent pure underlay tiles found - all tiles have overlays")
                println("Overlay tiles intentionally create sharp color transitions")
            }

            // Check HSL to RGB conversion for typical neighboring values
            println("\n=== HSL QUANTIZATION TEST ===")
            for (hsl in listOf(12816, 12817, 12818, 12819, 12820, 12689)) {
                val rgb = ColorUtil.hslToRgb(hsl)
                println("  HSL $hsl (0x${hsl.toString(16)}): RGB=(${rgb.x}, ${rgb.y}, ${rgb.z})")
            }
            println("  Note: Adjacent HSL values should have similar RGB to avoid harsh edges")

            // Count how many tiles are pure underlay vs have overlay
            var pureUnderlayCount = 0
            var overlayCount = 0
            var partialOverlayCount = 0
            var fullOverlayCount = 0
            val shapeDistribution = mutableMapOf<Int, Int>()

            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val tile = region.tiles[plane][x][y]
                    if (tile.underlayId > 0 && tile.overlayId == 0) {
                        pureUnderlayCount++
                    } else if (tile.overlayId > 0) {
                        overlayCount++
                        val shape = tile.overlayShape
                        shapeDistribution[shape] = (shapeDistribution[shape] ?: 0) + 1
                        // Shape 0 = full overlay, shapes 1-11 = partial overlay
                        if (shape == 0) {
                            fullOverlayCount++
                        } else {
                            partialOverlayCount++
                        }
                    }
                }
            }
            println("\n=== TILE STATS ===")
            println("  Pure underlay tiles: $pureUnderlayCount")
            println("  Overlay tiles: $overlayCount")
            println("    Full overlay (shape 0): $fullOverlayCount")
            println("    Partial overlay (shapes 1-11): $partialOverlayCount")
            println("  Shape distribution: $shapeDistribution")

            // Find a partial overlay tile and show its color situation
            println("\n=== PARTIAL OVERLAY EXAMPLE ===")
            for (x in 5 until 60) {
                for (y in 5 until 60) {
                    val tile = region.tiles[plane][x][y]
                    if (tile.overlayId > 0 && tile.overlayShape in 1..11 && tile.underlayId > 0) {
                        val overlayDef = getOverlayDefinition(tile.overlayId)
                        val underlayDef = getUnderlayDefinition(tile.underlayId)
                        val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else null
                        val underlayHsl = getCornerHslColors(blendedUnderlayColors, x, y)
                        val underlayRgb = hslCornersToRgb(underlayHsl)

                        println("  Tile ($x,$y): shape=${tile.overlayShape}, rotation=${tile.overlayRotation}")
                        println("    overlayId=${tile.overlayId}, underlayId=${tile.underlayId}")
                        println("    Overlay color: $overlayColor")
                        println("    Underlay corner HSLs: ${underlayHsl.toList()}")
                        println("    Underlay corner RGBs: SW=${underlayRgb[0]} SE=${underlayRgb[1]}")

                        // Compare overlay and underlay colors
                        if (overlayColor != null) {
                            val diff = (overlayColor.x - underlayRgb[0].x).let { kotlin.math.abs(it) } +
                                       (overlayColor.y - underlayRgb[0].y).let { kotlin.math.abs(it) } +
                                       (overlayColor.z - underlayRgb[0].z).let { kotlin.math.abs(it) }
                            println("    Color difference (overlay vs underlay SW): $diff")
                            if (diff > 0.3f) {
                                println("    WARNING: Large color difference - this creates harsh edges")
                            }
                        }
                        break
                    }
                }
                break
            }

            println("=== END TILE BOUNDARY DEBUG ===\n")
        }

        // Collect vertices grouped by texture ID
        val textureGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        // Iterate through all 64x64 tiles, clamping height lookups for edge tiles
        // This matches the RS client's iteration pattern
        for (x in 0 until 64) {
            // Calculate next X with clamping for edge tiles
            val nextX = if (x < 63) x + 1 else x
            for (y in 0 until 64) {
                val tile = region.tiles[plane][x][y]

                // Skip tiles with no overlay and no underlay
                if (tile.overlayId == 0 && tile.underlayId == 0) continue

                // Calculate next Y with clamping for edge tiles
                val nextY = if (y < 63) y + 1 else y

                // Calculate heights for this tile's corners
                // heights array: [SW, SE, NW, NE]
                val heights = floatArrayOf(
                    region.tiles[plane][x][y].height * HEIGHT_SCALE,             // SW (0,0)
                    region.tiles[plane][nextX][y].height * HEIGHT_SCALE,         // SE (1,0)
                    region.tiles[plane][x][nextY].height * HEIGHT_SCALE,         // NW (0,1)
                    region.tiles[plane][nextX][nextY].height * HEIGHT_SCALE      // NE (1,1)
                )

                // Get overlay and underlay definitions
                val overlayDef = if (tile.overlayId > 0) getOverlayDefinition(tile.overlayId) else null
                val underlayDef = if (tile.underlayId > 0) getUnderlayDefinition(tile.underlayId) else null

                // Get blended corner HSL colors for underlay (SW, SE, NW, NE order)
                val underlayCornerHsl = getCornerHslColors(blendedUnderlayColors, x, y)
                val underlayCornerColors = hslCornersToRgb(underlayCornerHsl)
                val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else underlayCornerColors[0]

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

                // Check if overlay blends with underlay (opcode 12 in RS client)
                // This flag causes overlay edge vertices to use blended underlay colors
                // for smooth transitions between overlay and underlay areas
                val overlayBlendsWithUnderlay = overlayDef?.underlayOverrides ?: false

                // Get overlay's packed HSL color for blending overlay edges
                val overlayHsl = if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
                    overlayDef.colour
                } else {
                    0
                }

                // Determine the shape to use
                // If shape is 0 and no overlay, treat as full underlay (shape 12)
                var shape = tile.overlayShape
                if (shape == 0 && tile.overlayId == 0) {
                    shape = 12  // Shape 12 = full underlay (0 overlay faces, 2 underlay faces)
                }

                // Get pre-computed normals for this tile's 4 corners (SW, SE, NW, NE)
                // These are computed from neighboring tile heights for smooth lighting variation
                val cornerNormals = arrayOf(
                    Vector3f(vertexNormalsX[x][y], vertexNormalsY[x][y], vertexNormalsZ[x][y]),           // SW
                    Vector3f(vertexNormalsX[nextX][y], vertexNormalsY[nextX][y], vertexNormalsZ[nextX][y]), // SE
                    Vector3f(vertexNormalsX[x][nextY], vertexNormalsY[x][nextY], vertexNormalsZ[x][nextY]), // NW
                    Vector3f(vertexNormalsX[nextX][nextY], vertexNormalsY[nextX][nextY], vertexNormalsZ[nextX][nextY]) // NE
                )

                // Generate shaped vertices for this tile with per-vertex blended colors
                val vertices = generateShapedTile(
                    x, y,
                    shape, tile.overlayRotation,
                    tileSize,
                    heights,
                    finalOverlayColor,
                    underlayCornerHsl,  // Pass HSL for proper HSL-space blending
                    overlayTextureId,
                    underlayTextureId,
                    overlayUvScale,
                    underlayUvScale,
                    overlayBlendsWithUnderlay,
                    overlayHsl,
                    cornerNormals  // Pre-computed normals from neighboring tile heights
                )

                // Add vertices to appropriate texture groups
                for (vertex in vertices) {
                    val group = textureGroups.getOrPut(vertex.textureId) { mutableListOf() }
                    group.add(vertex)
                }
            }
        }

        // Build color mesh (textureId == -1) with RGB colors
        val colorMesh = textureGroups[-1]?.let { vertices ->
            if (vertices.isEmpty()) return@let null

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)  // RGB (3 components)
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

            Mesh(positions, colors, null, normals, indices, colorComponents = 3)
        }

        // Build textured batches with RGB colors
        val texturedBatches = mutableListOf<TerrainBatch>()
        for ((texId, vertices) in textureGroups) {
            if (texId == -1) continue // Skip color group
            if (vertices.isEmpty()) continue

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)  // RGB (3 components)
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

            val mesh = Mesh(positions, colors, texCoords, normals, indices, colorComponents = 3)
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
        batchedMeshCache.values.forEach { it?.cleanup() }
        batchedMeshCache.clear()
        regionCache.clear()
        // Reset debug flags so we can see new output
        debugBlendingOnce = true
        debugDefinitions = true
        debugCornerColorsCount = 0
        debugVertexColorsOnce = true
        debugLightingOnce = true
        debugTileBoundaryOnce = true
        debugShapedTileOnce = true
    }

    private var debugLightingOnce = true

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
     * Enhanced overlay face count when using enhanced tessellation.
     * Shapes 0 and 13 get 4 triangles instead of 2 (using center vertex).
     */
    private val OVERLAY_FACE_COUNT_ENHANCED = intArrayOf(4, 1, 1, 1, 2, 2, 2, 1, 3, 3, 3, 2, 0, 4, 0)

    /**
     * Enhanced underlay face count when using enhanced tessellation.
     * Shape 12 and 14 get 4 triangles instead of 2 (using center vertex).
     */
    private val UNDERLAY_FACE_COUNT_ENHANCED = intArrayOf(0, 1, 2, 2, 1, 1, 2, 3, 1, 3, 3, 4, 4, 0, 4)

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
     * Enhanced triangle vertex A indices for shapes 0, 12, 13, 14.
     * These use the center vertex (12) to create 4 triangles instead of 2,
     * which allows proper HSL-space color interpolation at the center.
     *
     * For shape 0 (full overlay): 4 triangles from center to each edge
     *   SW-SE-center, SE-NE-center, NE-NW-center, NW-SW-center
     *
     * For shape 12 (full underlay): same as shape 0 but all underlay
     */
    private val TRIANGLE_A_ENHANCED = arrayOf(
        intArrayOf(0, 2, 4, 6),     // Shape 0: 4 overlay faces (SW, SE, NE, NW corners)
        intArrayOf(0, 2),           // Shape 1: unchanged
        intArrayOf(0, 0, 2),        // Shape 2: unchanged
        intArrayOf(2, 0, 0),        // Shape 3: unchanged
        intArrayOf(0, 2, 0),        // Shape 4: unchanged
        intArrayOf(0, 0, 2),        // Shape 5: unchanged
        intArrayOf(0, 5, 1, 4),     // Shape 6: unchanged
        intArrayOf(0, 4, 4, 4),     // Shape 7: unchanged
        intArrayOf(4, 4, 4, 0),     // Shape 8: unchanged
        intArrayOf(6, 6, 6, 2, 2, 2), // Shape 9: unchanged
        intArrayOf(2, 2, 2, 6, 6, 6), // Shape 10: unchanged
        intArrayOf(0, 11, 6, 6, 6, 4), // Shape 11: unchanged
        intArrayOf(0, 2, 4, 6),     // Shape 12: 4 underlay faces (using center)
        intArrayOf(0, 4, 4, 4),     // Shape 13: unchanged
        intArrayOf(0, 4, 4, 4)      // Shape 14: unchanged
    )

    /**
     * Enhanced triangle vertex B indices.
     */
    private val TRIANGLE_B_ENHANCED = arrayOf(
        intArrayOf(2, 4, 6, 0),     // Shape 0: SW->SE, SE->NE, NE->NW, NW->SW
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
        intArrayOf(2, 4, 6, 0),     // Shape 12: same pattern
        intArrayOf(1, 6, 7, 1),
        intArrayOf(1, 6, 7, 1)
    )

    /**
     * Enhanced triangle vertex C indices.
     * All use center vertex (12) for shapes 0 and 12.
     */
    private val TRIANGLE_C_ENHANCED = arrayOf(
        intArrayOf(12, 12, 12, 12), // Shape 0: all triangles meet at center
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
        intArrayOf(12, 12, 12, 12), // Shape 12: all triangles meet at center
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 7, 1, 2)
    )

    /**
     * Data class to hold vertex data for shaped tile generation.
     * Uses RGB color only - smooth blending comes from matching vertex colors at boundaries.
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
     * @param overlayColor Color for overlay triangles (RGB) - used for interior vertices
     * @param underlayCornerHsl Array of 4 corner packed HSL colors for underlay [SW, SE, NW, NE]
     *                          Vertex colors are bilinearly interpolated in HSL space
     * @param overlayTextureId Texture ID for overlay (-1 for color only)
     * @param underlayTextureId Texture ID for underlay (-1 for color only)
     * @param overlayUvScale UV scale for overlay texture
     * @param underlayUvScale UV scale for underlay texture
     * @param overlayBlendsWithUnderlay If true, overlay edge vertices use blended underlay colors
     * @param overlayHsl The overlay's packed HSL color for blending calculations
     * @param cornerNormals Pre-computed normals for 4 corners [SW, SE, NW, NE] from neighboring heights
     * @return List of ShapedVertex objects for all triangles
     */
    private fun generateShapedTile(
        x: Int, y: Int,
        shape: Int, rotation: Int,
        tileSize: Float,
        heights: FloatArray, // [h00, h10, h01, h11] = [SW, SE, NW, NE]
        overlayColor: Vector3f,
        underlayCornerHsl: IntArray, // Packed HSL: [SW, SE, NW, NE]
        overlayTextureId: Int,
        underlayTextureId: Int,
        overlayUvScale: Float,
        underlayUvScale: Float,
        overlayBlendsWithUnderlay: Boolean = false,
        overlayHsl: Int = 0,
        cornerNormals: Array<Vector3f> = arrayOf(
            Vector3f(0f, 1f, 0f), Vector3f(0f, 1f, 0f),
            Vector3f(0f, 1f, 0f), Vector3f(0f, 1f, 0f)
        )
    ): List<ShapedVertex> {
        val vertices = mutableListOf<ShapedVertex>()

        // Debug is now handled elsewhere
        val debugThisTile = false

        // Clamp shape to valid range
        val safeShape = shape.coerceIn(0, TRIANGLE_A.size - 1)

        // Use enhanced tessellation for shapes 0 and 12 if enabled
        // This creates 4 triangles using a center vertex for proper HSL interpolation
        val useEnhanced = enableEnhancedTessellation && (safeShape == 0 || safeShape == 12)

        val overlayFaces = if (useEnhanced) OVERLAY_FACE_COUNT_ENHANCED[safeShape] else OVERLAY_FACE_COUNT[safeShape]
        val underlayFaces = if (useEnhanced) UNDERLAY_FACE_COUNT_ENHANCED[safeShape] else UNDERLAY_FACE_COUNT[safeShape]
        val totalFaces = overlayFaces + underlayFaces

        if (totalFaces == 0) return vertices

        val triA = if (useEnhanced) TRIANGLE_A_ENHANCED[safeShape] else TRIANGLE_A[safeShape]
        val triB = if (useEnhanced) TRIANGLE_B_ENHANCED[safeShape] else TRIANGLE_B[safeShape]
        val triC = if (useEnhanced) TRIANGLE_C_ENHANCED[safeShape] else TRIANGLE_C[safeShape]

        // Base position for this tile
        val baseX = x * tileSize
        val baseZ = -y * tileSize  // Flip Z for OpenGL

        // Generate rotated vertex positions for this tile
        // RS rotates UVs and vertex positions based on rotation
        val vertexX = FloatArray(13)
        val vertexZ = FloatArray(13)
        val vertexY = FloatArray(13)  // Heights
        // Store the rotated local positions for color interpolation (0-512 range)
        val vertexLocalX = IntArray(13)
        val vertexLocalY = IntArray(13)

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

            // Store rotated local position for color interpolation
            vertexLocalX[i] = vx
            vertexLocalY[i] = vy

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

        // Interpolate normals from pre-computed corner normals
        // The corner normals are computed from neighboring tile heights (like RS client's GroundGL)
        // This creates smooth lighting variation across tiles even in homogeneous areas.
        //
        // Corner normals are: [SW, SE, NW, NE]
        // But we need to rotate them to match the shape rotation, just like we rotate colors.
        val (nSW, nSE, nNW, nNE) = when (rotation) {
            0 -> arrayOf(cornerNormals[0], cornerNormals[1], cornerNormals[2], cornerNormals[3])
            // Rotation 1 (90 CW): rotate normals CCW to compensate
            1 -> arrayOf(cornerNormals[1], cornerNormals[3], cornerNormals[0], cornerNormals[2])
            // Rotation 2 (180)
            2 -> arrayOf(cornerNormals[3], cornerNormals[2], cornerNormals[1], cornerNormals[0])
            // Rotation 3 (270 CW = 90 CCW)
            3 -> arrayOf(cornerNormals[2], cornerNormals[0], cornerNormals[3], cornerNormals[1])
            else -> arrayOf(cornerNormals[0], cornerNormals[1], cornerNormals[2], cornerNormals[3])
        }

        val vertexNormals = Array(13) { i ->
            val fx = vertexLocalX[i].toFloat() / 512f
            val fy = vertexLocalY[i].toFloat() / 512f

            // Bilinear interpolation of normals from corner values
            // This creates smooth lighting variation across the tile
            val southNormal = Vector3f(nSW).lerp(nSE, fx)
            val northNormal = Vector3f(nNW).lerp(nNE, fx)
            val interpolated = Vector3f(southNormal).lerp(northNormal, fy)

            // Normalize the result (interpolated normals may not be unit length)
            val len = interpolated.length()
            if (len > 0.0001f) {
                interpolated.div(len)
            } else {
                Vector3f(0f, 1f, 0f)
            }
        }

        // Extract packed HSL corner colors: [SW, SE, NW, NE]
        // IMPORTANT: These must be rotated to match the rotated vertex positions.
        // The shape rotation rotates vertex positions but we need to interpolate
        // colors based on the ROTATED positions, so we rotate the corner colors
        // in the OPPOSITE direction to compensate.
        //
        // Original positions: SW(0,0), SE(512,0), NW(0,512), NE(512,512)
        // After rotation, vertices move but we still use fx,fy from rotated positions.
        // To get correct interpolation, we rotate corner colors to match.
        val (hslSW, hslSE, hslNW, hslNE) = when (rotation) {
            0 -> arrayOf(underlayCornerHsl[0], underlayCornerHsl[1], underlayCornerHsl[2], underlayCornerHsl[3])
            // Rotation 1 (90 CW): SW->NW, SE->SW, NW->NE, NE->SE (rotate colors CCW)
            1 -> arrayOf(underlayCornerHsl[1], underlayCornerHsl[3], underlayCornerHsl[0], underlayCornerHsl[2])
            // Rotation 2 (180): SW->NE, SE->NW, NW->SE, NE->SW
            2 -> arrayOf(underlayCornerHsl[3], underlayCornerHsl[2], underlayCornerHsl[1], underlayCornerHsl[0])
            // Rotation 3 (270 CW = 90 CCW): SW->SE, SE->NE, NW->SW, NE->NW
            3 -> arrayOf(underlayCornerHsl[2], underlayCornerHsl[0], underlayCornerHsl[3], underlayCornerHsl[1])
            else -> arrayOf(underlayCornerHsl[0], underlayCornerHsl[1], underlayCornerHsl[2], underlayCornerHsl[3])
        }

        // Debug: For test tiles, print the corner colors used for interpolation
        if (debugThisTile) {
            println("\n=== SHAPED TILE DEBUG: Tile ($x, $y) ===")
            println("  Shape: $safeShape, Rotation: $rotation")
            println("  Input underlayCornerHsl: SW=${underlayCornerHsl[0]}, SE=${underlayCornerHsl[1]}, NW=${underlayCornerHsl[2]}, NE=${underlayCornerHsl[3]}")
            println("  After rotation: hslSW=$hslSW, hslSE=$hslSE, hslNW=$hslNW, hslNE=$hslNE")

            // Print the positions of the 4 corner vertices (indices 0, 2, 4, 6)
            println("  Vertex positions (after rotation):")
            for (corner in listOf(0, 2, 4, 6)) {
                val cornerName = when (corner) {
                    0 -> "SW"
                    2 -> "SE"
                    4 -> "NE"
                    6 -> "NW"
                    else -> "?"
                }
                println("    $cornerName (idx $corner): local(${vertexLocalX[corner]}, ${vertexLocalY[corner]}) -> world(${vertexX[corner]}, ${vertexZ[corner]})")
            }

            // Calculate what color each corner vertex would get
            println("  Interpolated corner colors (what vertices will use):")
            for (corner in listOf(0, 2, 4, 6)) {
                val cornerName = when (corner) {
                    0 -> "SW"
                    2 -> "SE"
                    4 -> "NE"
                    6 -> "NW"
                    else -> "?"
                }
                val fx = vertexLocalX[corner].toFloat() / 512f
                val fy = vertexLocalY[corner].toFloat() / 512f
                val blendedHsl = ColorUtil.bilinearBlendHsl(hslSW, hslSE, hslNW, hslNE, fx, fy)
                val rgb = ColorUtil.hslToRgb(blendedHsl)
                println("    $cornerName: fx=$fx, fy=$fy -> HSL=$blendedHsl -> RGB=(${rgb.x}, ${rgb.y}, ${rgb.z})")
            }
        }

        // Generate triangles
        for (face in 0 until totalFaces) {
            if (face >= triA.size) break

            // Determine if this is overlay or underlay
            val isOverlay = face < overlayFaces

            // Skip overlay faces if overlays are disabled
            if (isOverlay && !enableOverlays) continue

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

            val textureId = if (isOverlay) overlayTextureId else underlayTextureId
            val uvScale = if (isOverlay) overlayUvScale else underlayUvScale

            // Helper to interpolate underlay color in HSL space (matching RS client)
            // Applies terrain lighting based on vertex normal BEFORE HSL->RGB conversion
            // Also applies deterministic per-vertex brightness noise like the RS client
            fun interpolateUnderlayColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
                val localX = vertexLocalX[vIdx]
                val localY = vertexLocalY[vIdx]

                // Bilinear interpolation in HSL space using RS blend function
                val fx = localX.toFloat() / 512f
                val fy = localY.toFloat() / 512f

                // Blend in HSL space
                var blendedHsl = ColorUtil.bilinearBlendHsl(hslSW, hslSE, hslNW, hslNE, fx, fy)
                if (blendedHsl == 0) return DEFAULT_GRASS_COLOR

                // Apply deterministic per-vertex brightness noise (like RS client's HSL-to-RGB)
                // RS client adds +-1.5% random brightness: d_1 = 0.7 + (Math.random() * 0.03 - 0.015)
                // We use world position for determinism to avoid shimmering
                if (enableColorNoise) {
                    val noiseFactor = ColorUtil.getVertexNoise(worldPosX, worldPosZ, colorNoiseAmount)
                    blendedHsl = ColorUtil.applyBrightnessNoise(blendedHsl, noiseFactor)
                }

                // Apply terrain lighting based on vertex normal
                // This matches the RS client's lighting algorithm from GroundGL.resetLight()
                // where lightness is modified based on height gradients before RGB conversion
                // RS formula: factor = sunBrightness + dot * (dot > 0 ? brightFactor : darkFactor)
                if (enableLighting) {
                    val normal = vertexNormals[vIdx]
                    val lightFactor = ColorUtil.calculateTerrainLightFactor(
                        normal.x, normal.y, normal.z,
                        LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                        SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                    )
                    blendedHsl = ColorUtil.applyLightToHsl(blendedHsl, lightFactor)
                }

                return ColorUtil.hslToRgb(blendedHsl)
            }

            // Helper to get overlay color with blending at tile boundary vertices
            // This creates smooth transitions between overlay and underlay areas by:
            // 1. For tiles with blendsWithUnderlay=true: blend overlay edge vertices with underlay
            // 2. For partial overlay tiles (shapes 1-11): blend at boundary edges to avoid harsh seams
            // Also applies terrain lighting and brightness noise for consistent shading
            fun getOverlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
                // Calculate lighting factor for this vertex (used for both blended and unblended cases)
                // RS formula: factor = sunBrightness + dot * (dot > 0 ? brightFactor : darkFactor)
                val lightFactor = if (enableLighting) {
                    val normal = vertexNormals[vIdx]
                    ColorUtil.calculateTerrainLightFactor(
                        normal.x, normal.y, normal.z,
                        LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                        SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                    )
                } else {
                    1.0f
                }

                // Calculate noise factor for this vertex position
                val noiseFactor = if (enableColorNoise) {
                    ColorUtil.getVertexNoise(worldPosX, worldPosZ, colorNoiseAmount)
                } else {
                    1.0f
                }

                // Interior vertices (8-12) always use flat overlay color without boundary blending
                if (vIdx >= 8) {
                    if (overlayHsl != 0) {
                        var modifiedHsl = overlayHsl
                        if (enableColorNoise) {
                            modifiedHsl = ColorUtil.applyBrightnessNoise(modifiedHsl, noiseFactor)
                        }
                        if (enableLighting) {
                            modifiedHsl = ColorUtil.applyLightToHsl(modifiedHsl, lightFactor)
                        }
                        return ColorUtil.hslToRgb(modifiedHsl)
                    }
                    // For textured overlays, apply noise and lighting in RGB space
                    val combinedFactor = if (enableLighting) lightFactor * noiseFactor else noiseFactor
                    return if (enableColorNoise || enableLighting) {
                        Vector3f(
                            (overlayColor.x * combinedFactor).coerceIn(0f, 1f),
                            (overlayColor.y * combinedFactor).coerceIn(0f, 1f),
                            (overlayColor.z * combinedFactor).coerceIn(0f, 1f)
                        )
                    } else {
                        overlayColor
                    }
                }

                // For edge/corner vertices (0-7), apply boundary blending
                // This creates smooth transitions at the tile boundary
                val localX = vertexLocalX[vIdx]
                val localY = vertexLocalY[vIdx]
                val fx = localX.toFloat() / 512f
                val fy = localY.toFloat() / 512f

                // Get the underlay blended color at this vertex position
                val underlayBlendedHsl = ColorUtil.bilinearBlendHsl(hslSW, hslSE, hslNW, hslNE, fx, fy)

                // For overlays that explicitly blend with underlay (blendsWithUnderlay=true),
                // or for partial overlay tiles, blend at boundary vertices
                // Full overlay tiles (shape 0) without blendsWithUnderlay don't blend
                val shouldBlendAtBoundary = overlayBlendsWithUnderlay || (safeShape != 0 && safeShape != 12)

                if (!shouldBlendAtBoundary || underlayBlendedHsl == 0 || overlayHsl == 0) {
                    // No blending - use pure overlay color
                    if (overlayHsl != 0) {
                        var modifiedHsl = overlayHsl
                        if (enableColorNoise) {
                            modifiedHsl = ColorUtil.applyBrightnessNoise(modifiedHsl, noiseFactor)
                        }
                        if (enableLighting) {
                            modifiedHsl = ColorUtil.applyLightToHsl(modifiedHsl, lightFactor)
                        }
                        return ColorUtil.hslToRgb(modifiedHsl)
                    }
                    val combinedFactor = if (enableLighting) lightFactor * noiseFactor else noiseFactor
                    return if (enableColorNoise || enableLighting) {
                        Vector3f(
                            (overlayColor.x * combinedFactor).coerceIn(0f, 1f),
                            (overlayColor.y * combinedFactor).coerceIn(0f, 1f),
                            (overlayColor.z * combinedFactor).coerceIn(0f, 1f)
                        )
                    } else {
                        overlayColor
                    }
                }

                // Blend overlay color with underlay color at boundary vertices
                // The blend factor is based on distance from tile center
                // Corners (dist=1) use more underlay, center (dist=0) uses pure overlay
                val distFromCenter = maxOf(
                    kotlin.math.abs(fx - 0.5f),
                    kotlin.math.abs(fy - 0.5f)
                ) * 2f  // 0 at center, 1 at edges

                // At corners (dist=1), use 50% underlay blend for blendsWithUnderlay overlays
                // For partial shapes without blendsWithUnderlay, use a smaller blend (32) for subtler transition
                val maxBlend = if (overlayBlendsWithUnderlay) 64 else 32
                val blendFactor = (distFromCenter * maxBlend).toInt().coerceIn(0, 128)

                if (blendFactor == 0) {
                    var modifiedHsl = overlayHsl
                    if (enableColorNoise) {
                        modifiedHsl = ColorUtil.applyBrightnessNoise(modifiedHsl, noiseFactor)
                    }
                    if (enableLighting) {
                        modifiedHsl = ColorUtil.applyLightToHsl(modifiedHsl, lightFactor)
                    }
                    return ColorUtil.hslToRgb(modifiedHsl)
                }

                // Blend overlay HSL with underlay HSL, then apply noise and lighting
                var blendedHsl = ColorUtil.blendHsl(overlayHsl, underlayBlendedHsl, blendFactor)
                if (enableColorNoise) {
                    blendedHsl = ColorUtil.applyBrightnessNoise(blendedHsl, noiseFactor)
                }
                if (enableLighting) {
                    blendedHsl = ColorUtil.applyLightToHsl(blendedHsl, lightFactor)
                }
                return ColorUtil.hslToRgb(blendedHsl)
            }

            // Helper to create vertex with smooth normal and RGB color
            fun createVertex(px: Float, py: Float, pz: Float, vIdx: Int): ShapedVertex {
                // Get color based on overlay/underlay, passing world position for noise calculation
                val color = if (isOverlay) getOverlayVertexColor(vIdx, px, pz) else interpolateUnderlayColor(vIdx, px, pz)

                // UV coordinates based on position within tile
                val uvx = vertexLocalX[vIdx]
                val uvy = vertexLocalY[vIdx]

                // Use the pre-computed smooth normal for this vertex
                val smoothNormal = vertexNormals[vIdx]

                return ShapedVertex(
                    px, py, pz,
                    color.x, color.y, color.z,
                    uvx.toFloat() / 512f * uvScale,
                    uvy.toFloat() / 512f * uvScale,
                    smoothNormal.x, smoothNormal.y, smoothNormal.z,
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
