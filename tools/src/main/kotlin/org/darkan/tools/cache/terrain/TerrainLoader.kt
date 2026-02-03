package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.darkan.tools.util.TileUtils
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

    /**
     * Tile function mask flags from the RS client (SettingsBits.kt / MapLoader.kt).
     * These flags control collision, visibility, and height behavior.
     *
     * From SettingsBits.java in darkan-bot client:
     * - isVisible(collisionPlane, z, x, y): returns true if:
     *     (flags[0][x][y] & 0x2) != 0, OR
     *     ((flags[z][x][y] & 0x10) == 0 AND getCollisionPlane(z,x,y) == collisionPlane)
     * - getCollisionPlane(z, x, y): returns:
     *     0 if (flags[z][x][y] & 0x8) != 0
     *     z - 1 if z > 0 AND (flags[1][x][y] & 0x2) != 0
     *     z otherwise
     */
    object TileFlags {
        /** Tile is blocked for walking (adds collision flag) */
        const val BLOCK_WALK = 0x1

        /**
         * This tile is a bridge - affects height calculation and visibility.
         *
         * When set on plane 0: Forces tile to always be visible (overrides INVISIBLE)
         * When set on plane 1:
         *   - Collision plane for planes > 0 is reduced by 1
         *   - Objects use height from plane+1
         *   - Terrain below bridges is linked for proper rendering
         */
        const val BRIDGE = 0x2

        /**
         * Force collision plane to 0 regardless of actual plane.
         * Used for areas that should always use ground-level collision.
         * In getCollisionPlane(): if set, returns 0.
         */
        const val FORCE_COLLISION_PLANE_0 = 0x8

        /**
         * Tile is not visible (used for hidden areas, interiors, etc).
         * When set, the tile should not be rendered UNLESS:
         * - The bridge flag (0x2) is set on plane 0 at this location
         * - The collision plane doesn't match the viewing plane
         */
        const val INVISIBLE = 0x10
    }

    data class TileData(
        val height: Int,
        val overlayId: Int,
        val overlayShape: Int,
        val overlayRotation: Int,
        val underlayId: Int,
        val flags: Int
    ) {
        /** Check if tile has a specific flag set */
        fun hasFlag(flag: Int): Boolean = (flags and flag) == flag

        /** Check if this tile is marked as a bridge */
        val isBridge: Boolean get() = hasFlag(TileFlags.BRIDGE)

        /** Check if this tile should block walking */
        val blocksWalk: Boolean get() = hasFlag(TileFlags.BLOCK_WALK)

        /** Check if this tile is invisible (should not be rendered) */
        val isInvisible: Boolean get() = hasFlag(TileFlags.INVISIBLE)

        /** Check if this tile forces collision to plane 0 */
        val forcesCollisionPlane0: Boolean get() = hasFlag(TileFlags.FORCE_COLLISION_PLANE_0)
    }

    /**
     * Calculate the collision plane for a tile, matching the RS client's SettingsBits.getCollisionPlane().
     *
     * The collision plane determines which plane's collision data is used and affects visibility.
     *
     * @param tiles The 4-plane tile array for the region
     * @param z The current plane level
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return The collision plane (0-3)
     */
    fun getCollisionPlane(tiles: Array<Array<Array<TileData>>>, z: Int, x: Int, y: Int): Int {
        // If FORCE_COLLISION_PLANE_0 flag is set on this tile, always return 0
        if (tiles[z][x][y].forcesCollisionPlane0) {
            return 0
        }
        // If we're on a plane > 0 AND the bridge flag is set on plane 1 at this position,
        // the collision plane is reduced by 1 (allowing walking under bridges)
        if (z > 0 && tiles[1][x][y].isBridge) {
            return z - 1
        }
        return z
    }

    /**
     * Check if a tile at the given position should be visible for a specific viewing plane.
     *
     * This matches the RS client's SettingsBits.isVisible() logic:
     * - If bridge flag (0x2) is set on plane 0, the tile is ALWAYS visible
     * - Otherwise, visible if NOT invisible AND collision plane matches viewing plane
     *
     * @param tiles The 4-plane tile array for the region
     * @param viewingPlane The plane we're viewing (typically the player's plane)
     * @param z The tile's plane level
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return True if the tile should be rendered
     */
    fun isTileVisible(tiles: Array<Array<Array<TileData>>>, viewingPlane: Int, z: Int, x: Int, y: Int): Boolean {
        // Bridge flag on plane 0 makes tile always visible (used for bridges/overhangs)
        if (tiles[0][x][y].isBridge) {
            return true
        }
        // Otherwise, check: not invisible AND collision plane matches viewing plane
        return !tiles[z][x][y].isInvisible && getCollisionPlane(tiles, z, x, y) == viewingPlane
    }

    /**
     * Check if the bridge flag is set on plane 1 at this position.
     * When set, objects should use height from the plane above their current plane.
     *
     * @param tiles The 4-plane tile array for the region
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return True if bridge flag is set on plane 1
     */
    fun isBridgeTile(tiles: Array<Array<Array<TileData>>>, x: Int, y: Int): Boolean {
        return tiles[1][x][y].isBridge
    }

    /**
     * Get the height plane to use for an object at the given position.
     * Following the RS client (MapRegion.tileheightForAbsolutePosition):
     * - Check if bridge flag (0x2) is set on level 1 at this position
     * - If so, and current plane < 3, use height from plane + 1
     * - Otherwise use the current plane's height
     *
     * @param tiles The 4-plane tile array for the region
     * @param plane The object's plane level
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return The plane to use for height lookup
     */
    fun getHeightPlane(tiles: Array<Array<Array<TileData>>>, plane: Int, x: Int, y: Int): Int {
        if (plane < 3 && isBridgeTile(tiles, x, y)) {
            return plane + 1
        }
        return plane
    }

    /**
     * Check if terrain from a higher plane should be rendered due to bridge linkBelow.
     *
     * In the RS client, linkBelow() shifts terrain from higher planes down to lower planes
     * at bridge locations. This function determines if terrain from sourcePlane should
     * be visible when rendering targetPlane due to this bridge shifting.
     *
     * The logic mirrors Scene.linkBelow():
     * - When bridge flag (0x2) is set on plane 1, terrain is shifted down
     * - Plane 1 terrain appears at plane 0 (ground under bridge)
     * - Plane 2 terrain appears at plane 1
     * - Plane 3 terrain appears at plane 2
     *
     * @param tiles The 4-plane tile array for the region
     * @param sourcePlane The plane whose terrain data we're considering
     * @param targetPlane The plane we're rendering/viewing
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return True if sourcePlane terrain should appear at targetPlane due to bridge
     */
    fun isBridgeLinkedPlane(tiles: Array<Array<Array<TileData>>>, sourcePlane: Int, targetPlane: Int, x: Int, y: Int): Boolean {
        // Bridge linking only occurs when bridge flag is set on plane 1
        if (!isBridgeTile(tiles, x, y)) {
            return false
        }
        // linkBelow shifts terrain down by 1 plane: sourcePlane appears at (sourcePlane - 1)
        // So terrain from plane N appears at plane (N-1) after linkBelow
        return sourcePlane > 0 && sourcePlane - 1 == targetPlane
    }

    /**
     * Get the raw (unscaled) height value at a tile position.
     *
     * The height values are computed during region decode using the RS client's exact algorithm:
     * - Plane 0: procedural noise * 32 or heightByte * 32
     * - Plane 1+: previousHeight + 960 or previousHeight + heightByte * 32
     *
     * Heights are stored as positive values (higher terrain = larger value).
     *
     * @param tiles The 4-plane tile array from RegionData
     * @param localX Local X coordinate (0-63)
     * @param localY Local Y coordinate (0-63)
     * @param plane Plane level (0-3)
     * @return The height in RS units (typically 960-1920+ for ground level)
     */
    fun getTileHeight(tiles: Array<Array<Array<TileData>>>, localX: Int, localY: Int, plane: Int): Int {
        val x = localX.coerceIn(0, 63)
        val y = localY.coerceIn(0, 63)
        val p = plane.coerceIn(0, 3)
        return tiles[p][x][y].height
    }

    /**
     * Get the scaled height value at a tile position in OpenGL world units.
     *
     * This applies HEIGHT_SCALE to convert RS height units to OpenGL coordinates.
     *
     * @param tiles The 4-plane tile array from RegionData
     * @param localX Local X coordinate (0-63)
     * @param localY Local Y coordinate (0-63)
     * @param plane Plane level (0-3)
     * @return The height in OpenGL world units
     */
    fun getTileHeightScaled(tiles: Array<Array<Array<TileData>>>, localX: Int, localY: Int, plane: Int): Float {
        return getTileHeight(tiles, localX, localY, plane) * HEIGHT_SCALE
    }

    /**
     * Get the height scale factor used to convert RS heights to OpenGL coordinates.
     * This is exposed so other components (like MapViewer) can use the same scale.
     */
    fun getHeightScale(): Float = HEIGHT_SCALE

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
    }

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

        return blendedColors
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

        return intArrayOf(swHsl, seHsl, nwHsl, neHsl)
    }

    /**
     * Convert packed HSL corner colors to RGB Vector3f array.
     * Used for overlay colors that don't need HSL blending.
     * Falls back to black (not green) for invalid HSL = 0 values.
     */
    private fun hslCornersToRgb(hslCorners: IntArray): Array<Vector3f> {
        val black = Vector3f(0f, 0f, 0f)
        return arrayOf(
            if (hslCorners[0] != 0) ColorUtil.hslToRgb(hslCorners[0]) else black,
            if (hslCorners[1] != 0) ColorUtil.hslToRgb(hslCorners[1]) else black,
            if (hslCorners[2] != 0) ColorUtil.hslToRgb(hslCorners[2]) else black,
            if (hslCorners[3] != 0) ColorUtil.hslToRgb(hslCorners[3]) else black
        )
    }

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
     * Uses the RS client's visibility logic to determine which tiles to render:
     * - Bridge flag on plane 0 makes tiles always visible
     * - Invisible flag (0x10) hides tiles unless overridden by bridge
     * - Collision plane calculation affects visibility determination
     *
     * @param cache The game cache
     * @param regionX Region X coordinate
     * @param regionY Region Y coordinate
     * @param plane Plane level (0-3) - this is both the tile plane AND the viewing plane
     * @return TerrainMeshes containing color mesh and textured batches, or null if region not found
     */
    fun loadRegionMeshes(cache: Cache, regionX: Int, regionY: Int, plane: Int = 0): TerrainMeshes? {
        // Ensure definitions are loaded
        loadDefinitions(cache)

        val regionId = (regionX shl 8) or regionY
        val cacheKey = regionId * 4 + plane

        // Check if we need to regenerate (not in cache)
        val cached = batchedMeshCache[cacheKey]
        if (cached != null) {
            return cached
        }

        // Generate new meshes - log overlay state for debugging
        println("[TerrainLoader] Generating meshes for region ($regionX, $regionY) plane $plane - enableOverlays=$enableOverlays")

        val region = loadRegion(cache, regionX, regionY) ?: return null
        val meshes = createBatchedTerrainMeshes(region, plane)
        batchedMeshCache[cacheKey] = meshes
        return meshes
    }

    /**
     * Create separate mesh batches for textured and untextured terrain tiles.
     * Uses proper tile shape tessellation with the RS client's shape lookup tables.
     * Shapes create paths, edges, diagonal cuts, etc. by tessellating tiles into
     * multiple triangles with some belonging to overlay and others to underlay.
     */
    private fun createBatchedTerrainMeshes(region: RegionData, plane: Int): TerrainMeshes {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Helper function to compute normals for a specific plane
        fun computeVertexNormals(p: Int): Triple<Array<FloatArray>, Array<FloatArray>, Array<FloatArray>> {
            val normalsX = Array(65) { FloatArray(65) }
            val normalsY = Array(65) { FloatArray(65) }
            val normalsZ = Array(65) { FloatArray(65) }

            for (vx in 0 until 65) {
                for (vy in 0 until 65) {
                    val xMinus = (vx - 1).coerceIn(0, 63)
                    val xPlus = (vx + 1).coerceIn(0, 63)
                    val yMinus = (vy - 1).coerceIn(0, 63)
                    val yPlus = (vy + 1).coerceIn(0, 63)
                    val xCur = vx.coerceIn(0, 63)
                    val yCur = vy.coerceIn(0, 63)

                    val heightWest = region.tiles[p][xMinus][yCur].height * HEIGHT_SCALE
                    val heightEast = region.tiles[p][xPlus][yCur].height * HEIGHT_SCALE
                    val heightSouth = region.tiles[p][xCur][yMinus].height * HEIGHT_SCALE
                    val heightNorth = region.tiles[p][xCur][yPlus].height * HEIGHT_SCALE

                    val heightDeltaX = heightEast - heightWest
                    val heightDeltaY = heightNorth - heightSouth

                    val nx = heightDeltaX
                    val ny = tileSize * 2f
                    val nz = heightDeltaY

                    val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                    if (len > 0.0001f) {
                        normalsX[vx][vy] = nx / len
                        normalsY[vx][vy] = ny / len
                        normalsZ[vx][vy] = nz / len
                    } else {
                        normalsX[vx][vy] = 0f
                        normalsY[vx][vy] = 1f
                        normalsZ[vx][vy] = 0f
                    }
                }
            }
            return Triple(normalsX, normalsY, normalsZ)
        }

        // Pre-compute per-vertex normals for the main plane
        // This matches the RS client's GroundGL.kt lines 98-106 which computes normals
        // from height differences between neighboring tiles, creating smooth per-vertex
        // lighting variation even in homogeneous areas.
        val (vertexNormalsX, vertexNormalsY, vertexNormalsZ) = computeVertexNormals(plane)

        // Pre-compute blended underlay colors for the main plane
        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane)

        // Collect vertices grouped by texture ID
        val textureGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        // Helper function to generate terrain for a tile at a specific plane
        // using the provided normals and blended colors
        fun generateTileVertices(
            x: Int, y: Int,
            sourcePlane: Int,
            normalsX: Array<FloatArray>,
            normalsY: Array<FloatArray>,
            normalsZ: Array<FloatArray>,
            blendedColors: Array<IntArray>
        ) {
            val tile = region.tiles[sourcePlane][x][y]

            // Skip tiles with no overlay and no underlay
            if (tile.overlayId == 0 && tile.underlayId == 0) return

            // Calculate next coordinates with clamping for edge tiles
            val nextX = if (x < 63) x + 1 else x
            val nextY = if (y < 63) y + 1 else y

            // Calculate heights for this tile's corners using the source plane's heights
            // heights array: [SW, SE, NW, NE]
            val heights = floatArrayOf(
                region.tiles[sourcePlane][x][y].height * HEIGHT_SCALE,             // SW (0,0)
                region.tiles[sourcePlane][nextX][y].height * HEIGHT_SCALE,         // SE (1,0)
                region.tiles[sourcePlane][x][nextY].height * HEIGHT_SCALE,         // NW (0,1)
                region.tiles[sourcePlane][nextX][nextY].height * HEIGHT_SCALE      // NE (1,1)
            )

            // Get overlay and underlay definitions
            val overlayDef = if (tile.overlayId > 0) getOverlayDefinition(tile.overlayId) else null
            val underlayDef = if (tile.underlayId > 0) getUnderlayDefinition(tile.underlayId) else null

            // Get blended corner HSL colors for underlay (SW, SE, NW, NE order)
            val underlayCornerHsl = getCornerHslColors(blendedColors, x, y)
            val underlayCornerColors = hslCornersToRgb(underlayCornerHsl)
            val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else underlayCornerColors[0]

            // Determine texture IDs
            val overlayTextureId = overlayDef?.texture ?: -1
            val underlayTextureId = underlayDef?.texture ?: -1

            // Determine UV scales - RS client uses SCENE coordinates for texture mapping.
            //
            // RS client (GroundGL.resetLight lines 506-514, GroundMaterialGL.method12085 line 195):
            // 1. Stores raw scene coordinates as texture coordinates: UV = (sceneX, sceneY)
            // 2. Applies texture matrix scaling: glScalef(1/textureScale, 1/textureScale, 1)
            //
            // Scene coordinates are: sceneX = tileX * 512 + localOffset (where localOffset is 0-512)
            // This means textures tile CONTINUOUSLY across the entire terrain, not per-tile.
            //
            // With textureScale=560 (texture 928): UV = sceneCoord / 560
            // At tile (39,25): sceneX = 39*512 + offset = 19968 to 20480
            // UV range: 35.66 to 36.57 (texture covers ~0.91 tiles, appearing roughly once per tile)
            //
            // We store 1/scale as uvScale and compute: u = sceneCoord * uvScale
            val overlayUvScale = if (overlayDef != null && overlayDef.texture >= 0 && overlayDef.scale > 0) {
                1f / overlayDef.scale.toFloat()
            } else {
                1f / 512f  // Default scale
            }
            val underlayUvScale = if (underlayDef != null && underlayDef.texture >= 0 && underlayDef.scale > 0) {
                1f / underlayDef.scale.toFloat()
            } else {
                1f / 512f  // Default scale
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
            val overlayBlendsWithUnderlay = overlayDef?.underlayOverrides ?: false

            // Get overlay's packed HSL color for blending overlay edges
            val overlayHsl = if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
                overlayDef.colour
            } else {
                0
            }

            // Determine the shape to use
            var shape = tile.overlayShape
            if (shape == 0 && tile.overlayId == 0) {
                shape = 12  // Shape 12 = full underlay (0 overlay faces, 2 underlay faces)
            }

            // Get pre-computed normals for this tile's 4 corners
            val cornerNormals = arrayOf(
                Vector3f(normalsX[x][y], normalsY[x][y], normalsZ[x][y]),           // SW
                Vector3f(normalsX[nextX][y], normalsY[nextX][y], normalsZ[nextX][y]), // SE
                Vector3f(normalsX[x][nextY], normalsY[x][nextY], normalsZ[x][nextY]), // NW
                Vector3f(normalsX[nextX][nextY], normalsY[nextX][nextY], normalsZ[nextX][nextY]) // NE
            )

            // Determine if overlay/underlay exist (matching RS client's null checks)
            // This is used to skip rendering faces for non-existent layers
            //
            // RS client (MapLoader.kt lines 574-576) treats overlays with tileColorHsl == -1 as null
            // ONLY when BOTH color fields are -1:
            //   if (initialOverlay.tileColorHsl == -1 && initialOverlay.minimapColorHsl == -1) {
            //       initialOverlay = null
            //   }
            //
            // Additionally, overlays with a valid texture should always be rendered since the
            // texture provides visual content even without a tileColor (getOverlayColorHsl uses
            // the material's color when tileColorHsl is -1 but texture is valid).
            //
            // Our fields: colour = tileColorHsl, blendColour = minimapColorHsl, texture = texture
            val hasValidOverlay = if (overlayDef != null) {
                // Overlay is valid if it has a texture OR if not both color fields are -1
                overlayDef.texture >= 0 || !(overlayDef.colour == -1 && overlayDef.blendColour == -1)
            } else false
            val hasOverlay = tile.overlayId > 0 && hasValidOverlay
            // Check if underlay has valid color data at ALL 4 corners.
            // If ANY corner has HSL = 0 (no surrounding underlay data on this plane), the vertex
            // at that corner would get the green fallback color, creating visible green patches.
            // We require ALL corners to have valid data to ensure proper color at every vertex.
            val hasValidUnderlayColors = underlayCornerHsl.all { it != 0 }
            val hasUnderlay = tile.underlayId > 0 && underlayDef != null && hasValidUnderlayColors

            // Generate shaped vertices for this tile
            val vertices = generateShapedTile(
                x, y,
                shape, tile.overlayRotation,
                tileSize,
                heights,
                finalOverlayColor,
                underlayCornerHsl,
                overlayTextureId,
                underlayTextureId,
                overlayUvScale,
                underlayUvScale,
                overlayBlendsWithUnderlay,
                overlayHsl,
                cornerNormals,
                hasOverlay = hasOverlay,
                hasUnderlay = hasUnderlay,
                regionId = region.regionId
            )

            // Add vertices to appropriate texture groups
            for (vertex in vertices) {
                val group = textureGroups.getOrPut(vertex.textureId) { mutableListOf() }
                group.add(vertex)
            }
        }

        // Iterate through all 64x64 tiles
        //
        // TERRAIN GENERATION (based on RS client analysis of MapLoader.kt addBlendedTiles):
        // The RS client generates terrain for ALL tiles where overlayId != 0 || underlayId != 0.
        // There is NO special handling for bridge flags in terrain generation.
        //
        // The bridge flag (tileFunctionMasks[1][x][y] & 0x2) affects:
        // - linkBelow(): shifts Scene Tile OBJECTS down one plane (called AFTER terrain generation)
        // - isVisible(): determines Scene Tile visibility for objects, not terrain
        // - getCollisionPlane(): adjusts collision detection
        //
        // None of these affect terrain mesh generation. The generateTileVertices function
        // already skips tiles with no underlay/overlay data (line 991 check).
        //
        // IMPORTANT: The RS client does NOT use visibility checks for terrain generation.
        // addBlendedTiles() in MapLoader.kt simply iterates all tiles and generates terrain
        // for any tile with overlayId != 0 || underlayId != 0.
        //
        // The isVisible() function is used for OBJECTS (entities), not terrain.
        // Terrain is always generated regardless of the INVISIBLE flag or bridge flags.
        //
        // The generateTileVertices function already skips tiles with no overlay/underlay data.
        // The hasOverlay/hasUnderlay checks handle the RS client's null-setting logic for
        // overlays with colour == -1 (matching RS client behavior).
        for (x in 0 until 64) {
            for (y in 0 until 64) {
                generateTileVertices(
                    x, y,
                    plane,
                    vertexNormalsX,
                    vertexNormalsY,
                    vertexNormalsZ,
                    blendedUnderlayColors
                )
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

        // Debug: Log which textures are included in this terrain mesh
        if (texturedBatches.isNotEmpty()) {
            val textureList = texturedBatches.map { it.textureId }.sorted().joinToString(", ")
            println("[TerrainLoader] Region ${region.regionId} plane $plane: textured batches with textures [$textureList]")
        }

        return TerrainMeshes(colorMesh, texturedBatches)
    }

    /**
     * Temporary tile data used during decoding, before height computation.
     * Stores raw height byte and whether explicit height was provided (opcode 1).
     */
    private data class RawTileData(
        val heightByte: Int,        // Raw height byte (0-255), or 0 if opcode 0
        val hasExplicitHeight: Boolean,  // True if opcode 1 was used (explicit height)
        val overlayId: Int,
        val overlayShape: Int,
        val overlayRotation: Int,
        val underlayId: Int,
        val flags: Int
    )

    /**
     * Decode a region from cache with authentic RS client height computation.
     *
     * The RS client computes tile heights cumulatively:
     * - Plane 0: Either procedural noise or explicit height * 32 (negated)
     * - Plane 1+: Previous plane height minus offset (960 default or explicit * 32)
     *
     * Heights in RS are NEGATIVE (higher elevation = more negative value).
     * We store them as positive values for easier use in OpenGL (negate RS convention).
     *
     * @param cache The game cache
     * @param regionX Region X coordinate
     * @param regionY Region Y coordinate
     * @return RegionData with computed absolute heights, or null if region doesn't exist
     */
    private fun decodeRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val mapArchive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (mapArchive == -1) return null

        val data = cache.data(Index.MAPS, mapArchive) ?: return null
        val buffer = BufferReader(data)

        // First pass: decode raw tile data for all planes
        val rawTiles = Array(4) { Array(64) { Array(64) { RawTileData(0, false, 0, 0, 0, 0, 0) } } }

        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    rawTiles[plane][x][y] = decodeRawTile(buffer)
                }
            }
        }

        // Second pass: compute cumulative heights plane by plane
        // Heights are stored as positive values (negated from RS convention)
        // This is the EXACT algorithm from MapLoader.decodeTile in the RS client
        val tiles = Array(4) { Array(64) { Array(64) { TileData(0, 0, 0, 0, 0, 0) } } }

        // Absolute tile coordinates for procedural height generation
        val baseAbsX = regionX * 64
        val baseAbsY = regionY * 64

        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val raw = rawTiles[plane][x][y]
                    val absX = baseAbsX + x
                    val absY = baseAbsY + y

                    // Compute height following RS client logic from MapLoader.decodeTile
                    val height: Int = if (plane == 0) {
                        if (!raw.hasExplicitHeight) {
                            // Opcode 0 on plane 0: use procedural noise
                            // RS: tileHeights[0][x][y] = -TileUtils.calculateTileheight(absX + 932731, absY + 556238) * 8 shl 2
                            // The result is negative in RS (higher = more negative), we store positive
                            TileUtils.calculateTileheight(
                                absX + TileUtils.MAGIC_X_OFFSET,
                                absY + TileUtils.MAGIC_Y_OFFSET
                            ) * TileUtils.HEIGHT_MULTIPLIER
                        } else {
                            // Opcode 1 on plane 0: use explicit height
                            // RS: tileHeights[0][x][y] = -heightByte * 8 shl 2 = -heightByte * 32
                            // We store positive (negate RS convention)
                            raw.heightByte * TileUtils.HEIGHT_MULTIPLIER
                        }
                    } else {
                        // Planes 1, 2, 3: cumulative from previous plane
                        val prevHeight = tiles[plane - 1][x][y].height

                        if (!raw.hasExplicitHeight) {
                            // Opcode 0 on plane > 0: use default floor-to-floor offset
                            // RS: tileHeights[plane][x][y] = tileHeights[plane-1][x][y] - 960
                            // In RS, heights are negative, so subtracting makes them more negative (higher)
                            // We store positive, so we ADD the offset to go higher
                            prevHeight + TileUtils.DEFAULT_PLANE_HEIGHT_OFFSET
                        } else {
                            // Opcode 1 on plane > 0: use explicit offset from previous plane
                            // RS: tileHeights[plane][x][y] = tileHeights[plane-1][x][y] - (heightByte * 8 shl 2)
                            // We store positive, so we ADD
                            prevHeight + raw.heightByte * TileUtils.HEIGHT_MULTIPLIER
                        }
                    }

                    tiles[plane][x][y] = TileData(
                        height,
                        raw.overlayId,
                        raw.overlayShape,
                        raw.overlayRotation,
                        raw.underlayId,
                        raw.flags
                    )
                }
            }
        }

        return RegionData((regionX shl 8) or regionY, tiles)
    }

    /**
     * Decode raw tile data from the buffer.
     * This extracts the raw bytes without computing cumulative heights.
     */
    private fun decodeRawTile(buffer: BufferReader): RawTileData {
        var heightByte = 0
        var hasExplicitHeight = false
        var overlayId = 0
        var overlayShape = 0
        var overlayRotation = 0
        var underlayId = 0
        var flags = 0

        while (true) {
            val opcode = buffer.readUnsignedByte()
            when {
                opcode == 0 -> {
                    // No explicit height - use procedural noise or default plane offset
                    hasExplicitHeight = false
                    break
                }
                opcode == 1 -> {
                    // Explicit height byte provided
                    heightByte = buffer.readUnsignedByte()
                    hasExplicitHeight = true
                    break
                }
                opcode <= 49 -> {
                    overlayId = buffer.readUnsignedByte()
                    overlayShape = (opcode - 2) / 4
                    overlayRotation = (opcode - 2) and 3
                }
                opcode <= 81 -> {
                    flags = opcode - 49
                }
                else -> {
                    underlayId = opcode - 81
                }
            }
        }

        return RawTileData(heightByte, hasExplicitHeight, overlayId, overlayShape, overlayRotation, underlayId, flags)
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
        println("[TerrainLoader] clearCache() called - clearing ${batchedMeshCache.size} cached meshes")
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
     * Height scaling factor for converting RS heights to OpenGL world coordinates.
     *
     * Heights in TileData are now stored as absolute computed values in RS units,
     * matching exactly what the RS client computes:
     * - Plane 0: procedural noise * 32 or heightByte * 32
     * - Plane 1+: previousHeight + offset (960 default or heightByte * 32)
     *
     * We store heights as POSITIVE values (opposite of RS convention where heights are negative).
     * This means higher terrain has larger height values.
     *
     * To convert to OpenGL world coordinates, we just apply the standard scale factor.
     * DEFAULT_SCALE = 1/128, so HEIGHT_SCALE = 1/128.
     *
     * Example: A tile at ground level might have height = 1920 (60 * 32)
     * OpenGL Y = 1920 * (1/128) = 15.0 units
     */
    private const val HEIGHT_SCALE = CoordinateUtil.DEFAULT_SCALE  // = 1/128

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
        ),
        hasOverlay: Boolean = true,
        hasUnderlay: Boolean = true,
        regionId: Int = 0
    ): List<ShapedVertex> {
        val vertices = mutableListOf<ShapedVertex>()

        // Clamp shape to valid range
        val safeShape = shape.coerceIn(0, TRIANGLE_A.size - 1)

        // Use enhanced tessellation for shapes 0 and 12 if enabled
        // This creates 4 triangles using a center vertex for proper HSL interpolation
        val useEnhanced = enableEnhancedTessellation && (safeShape == 0 || safeShape == 12)

        // Get face counts from shape tables, but only if the layer actually exists
        // This matches the RS client which skips overlay/underlay faces if the definition is null
        val overlayFaces = if (hasOverlay) {
            if (useEnhanced) OVERLAY_FACE_COUNT_ENHANCED[safeShape] else OVERLAY_FACE_COUNT[safeShape]
        } else 0
        val underlayFaces = if (hasUnderlay) {
            if (useEnhanced) UNDERLAY_FACE_COUNT_ENHANCED[safeShape] else UNDERLAY_FACE_COUNT[safeShape]
        } else 0
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

        // Track skipped overlay faces for debugging
        var skippedOverlayFaces = 0

        // Generate triangles
        for (face in 0 until totalFaces) {
            if (face >= triA.size) break

            // Determine if this is overlay or underlay
            val isOverlay = face < overlayFaces

            // Skip overlay faces if overlays are disabled
            if (isOverlay && !enableOverlays) {
                skippedOverlayFaces++
                continue
            }

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
                // This should never happen if hasValidUnderlayColors check is correct (all corners non-zero).
                // If it does, return black instead of green to make any bugs less visually jarring.
                if (blendedHsl == 0) return Vector3f(0f, 0f, 0f)

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

                // UV coordinates using RS client's SCENE coordinate system.
                //
                // RS client (GroundGL.resetLight): stores sceneX/sceneY as texture coordinates
                // where sceneX = (tileX << tileScale) + localOffset = tileX * 512 + localOffset
                //
                // The RS client then applies glScalef(1/textureScale, 1/textureScale, 1) in the
                // texture matrix before rendering (GroundMaterialGL.method12085).
                //
                // This creates CONTINUOUS tiling across the entire terrain rather than per-tile.
                // For texture 928 (scale=560): texture repeats every 560 units, so roughly once
                // per tile (512 units), matching the visual appearance in the RS client.
                val localX = vertexLocalX[vIdx]
                val localY = vertexLocalY[vIdx]

                // Scene coordinates = tile position * 512 + local offset within tile
                val sceneX = (x * 512 + localX).toFloat()
                val sceneY = (y * 512 + localY).toFloat()

                // Apply UV scale (= 1 / textureScale)
                val u = sceneX * uvScale
                val v = sceneY * uvScale

                // Use the pre-computed smooth normal for this vertex
                val smoothNormal = vertexNormals[vIdx]

                return ShapedVertex(
                    px, py, pz,
                    color.x, color.y, color.z,
                    u,
                    v,
                    smoothNormal.x, smoothNormal.y, smoothNormal.z,
                    textureId
                )
            }

            val va = createVertex(ax, ay, az, ia)
            val vb = createVertex(bx, by, bz, ib)
            val vc = createVertex(cx, cy, cz, ic)

            vertices.add(va)
            vertices.add(vb)
            vertices.add(vc)
        }

        // Debug: Report when overlay faces are being skipped (only once per region to avoid spam)
        if (skippedOverlayFaces > 0 && x == 0 && y == 0) {
            println("[TerrainLoader] Overlays disabled: skipping overlay faces (enableOverlays=$enableOverlays)")
        }

        return vertices
    }
}
