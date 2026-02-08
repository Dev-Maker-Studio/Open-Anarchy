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
     * A terrain batch for a single texture layer rendered with per-vertex alpha.
     * Used in multi-pass alpha rendering where each non-winning texture is rendered
     * as a separate pass on top of the opaque base layer. The mesh contains a
     * per-vertex alpha attribute (location 4) that the terrain_alpha shader uses
     * to blend this layer over the base via standard alpha blending.
     */
    data class AlphaTerrainBatch(
        val mesh: Mesh,
        val textureId: Int  // Single texture for this alpha layer
    )

    /**
     * Container for terrain meshes with separate batches for textured, alpha-blended, and untextured tiles.
     * The rendering order is: colorMesh (opaque) -> texturedBatches (opaque, includes winning textures)
     * -> alphaBatches (rendered with GL alpha blending on top).
     */
    data class TerrainMeshes(
        val colorMesh: Mesh?,
        val texturedBatches: List<TerrainBatch>,
        val alphaBatches: List<AlphaTerrainBatch> = emptyList()
    ) {
        fun cleanup() {
            colorMesh?.cleanup()
            texturedBatches.forEach { it.mesh.cleanup() }
            alphaBatches.forEach { it.mesh.cleanup() }
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
    internal fun computeBlendedUnderlayColors(region: RegionData, plane: Int): Array<IntArray> {
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
     * Compute per-vertex normals for a 64x64 tile region on a specific plane.
     * Returns Triple of (normalsX, normalsY, normalsZ) arrays, each 65x65 (one per vertex).
     * This matches the RS client's GroundGL.kt normal computation from height gradients.
     *
     * @param tiles The 3D tile array [plane][x][y]
     * @param plane The plane level to compute normals for
     * @return Triple of (normalsX, normalsY, normalsZ) arrays
     */
    internal fun computeVertexNormals(tiles: Array<Array<Array<TileData>>>, plane: Int): Triple<Array<FloatArray>, Array<FloatArray>, Array<FloatArray>> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
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

                val heightWest = tiles[plane][xMinus][yCur].height * HEIGHT_SCALE
                val heightEast = tiles[plane][xPlus][yCur].height * HEIGHT_SCALE
                val heightSouth = tiles[plane][xCur][yMinus].height * HEIGHT_SCALE
                val heightNorth = tiles[plane][xCur][yPlus].height * HEIGHT_SCALE

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

    /**
     * Generate vertices for a single tile, running the full blending analysis pipeline.
     * Returns a list of ShapedVertex objects for all triangles of this tile.
     *
     * @param region The region data
     * @param plane The plane level
     * @param x Tile X coordinate within region
     * @param y Tile Y coordinate within region
     * @param blendedColors Pre-computed blended underlay colors for this plane
     * @param normalsX Pre-computed vertex normals X components
     * @param normalsY Pre-computed vertex normals Y components
     * @param normalsZ Pre-computed vertex normals Z components
     * @return List of ShapedVertex objects for all triangles of this tile
     */
    internal fun generateTileVertices(
        region: RegionData, plane: Int,
        x: Int, y: Int,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>
    ): List<ShapedVertex> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val tile = region.tiles[plane][x][y]

        // Skip tiles with no overlay and no underlay
        if (tile.overlayId == 0 && tile.underlayId == 0) return emptyList()

        // Run full blending analysis for this tile
        val blendState = analyzeTileBlending(region, plane, x, y, blendedColors)

        // Calculate next coordinates with clamping for edge tiles
        val nextX = if (x < 63) x + 1 else x
        val nextY = if (y < 63) y + 1 else y

        // Calculate heights for this tile's corners using the source plane's heights
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
        val underlayCornerHsl = getCornerHslColors(blendedColors, x, y)
        val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else Vector3f(0f, 0f, 0f)

        // Determine texture IDs
        val overlayTextureId = overlayDef?.texture ?: -1
        val underlayTextureId = underlayDef?.texture ?: -1

        // Determine UV scales
        val overlayUvScale = if (overlayDef != null && overlayDef.texture >= 0 && overlayDef.scale > 0) {
            1f / overlayDef.scale.toFloat()
        } else {
            1f / 512f
        }
        val underlayUvScale = if (underlayDef != null && underlayDef.texture >= 0 && underlayDef.scale > 0) {
            1f / underlayDef.scale.toFloat()
        } else {
            1f / 512f
        }

        // Get corner underlay definitions for per-vertex texture selection
        // (matching client's addUnderlayTiles: each corner uses the underlay from its adjacent tile)
        // SW corner = this tile, SE corner = east neighbor, NW corner = north neighbor, NE corner = NE neighbor
        val cornerUnderlayTextures: IntArray
        val cornerUnderlayScales: FloatArray
        if (underlayDef != null) {
            val tiles = region.tiles[plane]
            val nextXClamped = if (x < 63) x + 1 else x
            val nextYClamped = if (y < 63) y + 1 else y

            // Corner underlay IDs (client: underlayA=this, underlayB=north, underlayC=NE, underlayD=east)
            val swUnderlayId = tile.underlayId  // This tile
            val seUnderlayId = tiles[nextXClamped][y].underlayId.let { if (it == 0) swUnderlayId else it }
            val nwUnderlayId = tiles[x][nextYClamped].underlayId.let { if (it == 0) swUnderlayId else it }
            val neUnderlayId = tiles[nextXClamped][nextYClamped].underlayId.let { if (it == 0) swUnderlayId else it }

            val swDef = getUnderlayDefinition(swUnderlayId)
            val seDef = getUnderlayDefinition(seUnderlayId)
            val nwDef = getUnderlayDefinition(nwUnderlayId)
            val neDef = getUnderlayDefinition(neUnderlayId)

            cornerUnderlayTextures = intArrayOf(
                swDef?.texture ?: -1,
                seDef?.texture ?: -1,
                nwDef?.texture ?: -1,
                neDef?.texture ?: -1
            )
            cornerUnderlayScales = floatArrayOf(
                if (swDef != null && swDef.texture >= 0 && swDef.scale > 0) 1f / swDef.scale.toFloat() else 1f / 512f,
                if (seDef != null && seDef.texture >= 0 && seDef.scale > 0) 1f / seDef.scale.toFloat() else 1f / 512f,
                if (nwDef != null && nwDef.texture >= 0 && nwDef.scale > 0) 1f / nwDef.scale.toFloat() else 1f / 512f,
                if (neDef != null && neDef.texture >= 0 && neDef.scale > 0) 1f / neDef.scale.toFloat() else 1f / 512f
            )
        } else {
            cornerUnderlayTextures = intArrayOf(-1, -1, -1, -1)
            cornerUnderlayScales = floatArrayOf(1f/512f, 1f/512f, 1f/512f, 1f/512f)
        }

        // Adjust colors for textured tiles
        val finalOverlayColor = if (overlayTextureId >= 0) {
            if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
                ColorUtil.hslToRgb(overlayDef.colour)
            } else {
                Vector3f(1f, 1f, 1f)
            }
        } else {
            overlayColor
        }

        // Get overlay's packed HSL color
        val overlayHsl = if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
            overlayDef.colour
        } else {
            0
        }

        // Determine the effective shape and rotation from blend analysis
        val effectiveShape = blendState?.effectiveShape ?: run {
            var s = tile.overlayShape
            if (s == 0 && tile.overlayId == 0) s = 12
            s
        }
        val effectiveRotation = blendState?.effectiveRotation ?: tile.overlayRotation

        // Get pre-computed normals for this tile's 4 corners
        val cornerNormals = arrayOf(
            Vector3f(normalsX[x][y], normalsY[x][y], normalsZ[x][y]),
            Vector3f(normalsX[nextX][y], normalsY[nextX][y], normalsZ[nextX][y]),
            Vector3f(normalsX[x][nextY], normalsY[x][nextY], normalsZ[x][nextY]),
            Vector3f(normalsX[nextX][nextY], normalsY[nextX][nextY], normalsZ[nextX][nextY])
        )

        // Determine if overlay/underlay exist
        val hasValidOverlay = if (overlayDef != null) {
            overlayDef.texture >= 0 || !(overlayDef.colour == -1 && overlayDef.blendColour == -1)
        } else false
        val hasOverlay = tile.overlayId > 0 && hasValidOverlay
        val hasValidUnderlayColors = underlayCornerHsl.all { it != 0 }
        val hasUnderlay = tile.underlayId > 0 && underlayDef != null && hasValidUnderlayColors

        // Generate shaped vertices for this tile using the blend state
        return generateShapedTile(
            x, y,
            effectiveShape, effectiveRotation,
            tileSize,
            heights,
            finalOverlayColor,
            underlayCornerHsl,
            overlayTextureId,
            underlayTextureId,
            overlayUvScale,
            underlayUvScale,
            overlayDef?.underlayOverrides ?: false,
            overlayHsl,
            cornerNormals,
            hasOverlay = hasOverlay,
            hasUnderlay = hasUnderlay,
            regionId = region.regionId,
            blendState = blendState,
            cornerUnderlayTextures = cornerUnderlayTextures,
            cornerUnderlayScales = cornerUnderlayScales
        )
    }

    /**
     * Create separate mesh batches for textured and untextured terrain tiles.
     * Uses proper tile shape tessellation with the RS client's shape lookup tables.
     * Shapes create paths, edges, diagonal cuts, etc. by tessellating tiles into
     * multiple triangles with some belonging to overlay and others to underlay.
     */
    private fun createBatchedTerrainMeshes(region: RegionData, plane: Int): TerrainMeshes {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Pre-compute per-vertex normals for the main plane
        // This matches the RS client's GroundGL.kt lines 98-106 which computes normals
        // from height differences between neighboring tiles, creating smooth per-vertex
        // lighting variation even in homogeneous areas.
        val (vertexNormalsX, vertexNormalsY, vertexNormalsZ) = computeVertexNormals(region.tiles, plane)

        // Pre-compute blended underlay colors for the main plane
        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane)

        // Collect vertices grouped by texture ID
        val textureGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

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
        // already skips tiles with no underlay/overlay data.
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
                val vertices = generateTileVertices(
                    region, plane,
                    x, y,
                    blendedUnderlayColors,
                    vertexNormalsX,
                    vertexNormalsY,
                    vertexNormalsZ
                )
                for (vertex in vertices) {
                    val group = textureGroups.getOrPut(vertex.textureId) { mutableListOf() }
                    group.add(vertex)
                }
            }
        }

        // Separate vertices into opaque (alpha == 1.0) and alpha (alpha < 1.0) groups.
        // Opaque vertices go into standard textured/color batches.
        // Alpha vertices go into alpha batches rendered with the terrain_alpha shader.
        //
        // Vertices are processed in groups of 3 (triangles). If ALL vertices in a
        // triangle have alpha == 1.0, it goes to the opaque group. Otherwise it goes
        // to an alpha group keyed by texture ID.
        val opaqueGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()
        val alphaGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        for ((texId, verts) in textureGroups) {
            // Process in groups of 3 (triangles)
            var i = 0
            while (i + 2 < verts.size) {
                val v0 = verts[i]
                val v1 = verts[i + 1]
                val v2 = verts[i + 2]

                // Check if any vertex in this triangle has alpha < 1.0
                val needsAlpha = v0.alpha < 1f || v1.alpha < 1f || v2.alpha < 1f

                if (needsAlpha && v0.textureId >= 0) {
                    // Alpha layer - group by texture ID
                    val group = alphaGroups.getOrPut(v0.textureId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                } else {
                    // Opaque - add to normal group
                    val group = opaqueGroups.getOrPut(texId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                }
                i += 3
            }
            // Handle remaining vertices (should be 0 if all triangles are complete)
            while (i < verts.size) {
                opaqueGroups.getOrPut(texId) { mutableListOf() }.add(verts[i])
                i++
            }
        }

        // Build color mesh (textureId == -1) with RGB colors
        val colorMesh = opaqueGroups[-1]?.let { vertices ->
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

        // Build opaque textured batches with RGB colors
        val texturedBatches = mutableListOf<TerrainBatch>()
        for ((texId, vertices) in opaqueGroups) {
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

        // Build alpha terrain batches (per-vertex alpha for multi-pass blending)
        val alphaBatches = mutableListOf<AlphaTerrainBatch>()
        for ((texId, vertices) in alphaGroups) {
            if (vertices.isEmpty()) continue

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val texCoords = FloatArray(vertices.size * 2)
            val normals = FloatArray(vertices.size * 3)
            val alphas = FloatArray(vertices.size)
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
                alphas[i] = v.alpha
                indices[i] = i
            }

            val mesh = Mesh.createWithAlpha(
                positions, colors, texCoords, normals,
                alphas, indices
            )
            alphaBatches.add(AlphaTerrainBatch(mesh, texId))
        }

        // Debug: Log which textures are included in this terrain mesh
        if (texturedBatches.isNotEmpty()) {
            val textureList = texturedBatches.map { it.textureId }.sorted().joinToString(", ")
            println("[TerrainLoader] Region ${region.regionId} plane $plane: textured batches with textures [$textureList]")
        }
        if (alphaBatches.isNotEmpty()) {
            val alphaList = alphaBatches.map { it.textureId }.sorted().joinToString(", ")
            println("[TerrainLoader] Region ${region.regionId} plane $plane: alpha batches with textures [$alphaList]")
        }

        return TerrainMeshes(colorMesh, texturedBatches, alphaBatches)
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

    // ==================== NEIGHBOR-DRIVEN BLENDING MODE TABLES ====================
    //
    // Used when !overlaySupportsBlending && any hasFacesOn[0..3] is true.
    // This mode creates blended transitions between neighboring overlay tiles
    // by inserting additional edge triangles at tile boundaries.

    /** Neighbor-driven mode: vertex A indices (client: anIntArrayArray3774) */
    private val NEIGHBOR_BLEND_A = arrayOf(
        intArrayOf(0, 2, 4, 6), intArrayOf(6, 0, 2, 4), intArrayOf(6, 0, 2),
        intArrayOf(2, 6, 0), intArrayOf(0, 2, 6), intArrayOf(6, 0, 2),
        intArrayOf(5, 6, 0, 1, 2, 4), intArrayOf(7, 2, 4, 4), intArrayOf(2, 4, 4, 7),
        intArrayOf(6, 6, 4, 0, 2, 2), intArrayOf(0, 2, 2, 6, 6, 4),
        intArrayOf(0, 2, 2, 4, 6, 6), intArrayOf(0, 2, 4, 6)
    )

    /** Neighbor-driven mode: vertex B indices (client: anIntArrayArray3830) */
    private val NEIGHBOR_BLEND_B = arrayOf(
        intArrayOf(2, 4, 6, 0), intArrayOf(0, 2, 4, 6), intArrayOf(0, 2, 4),
        intArrayOf(4, 0, 2), intArrayOf(2, 4, 0), intArrayOf(0, 2, 4),
        intArrayOf(6, 0, 1, 2, 4, 5), intArrayOf(0, 4, 7, 6), intArrayOf(4, 7, 6, 0),
        intArrayOf(0, 8, 6, 2, 9, 4), intArrayOf(2, 9, 4, 0, 8, 6),
        intArrayOf(2, 11, 4, 6, 10, 0), intArrayOf(2, 4, 6, 0)
    )

    /** Neighbor-driven mode: vertex C indices (client: anIntArrayArray3831) */
    private val NEIGHBOR_BLEND_C = arrayOf(
        intArrayOf(12, 12, 12, 12), intArrayOf(12, 12, 12, 12), intArrayOf(5, 5, 5),
        intArrayOf(5, 5, 5), intArrayOf(5, 5, 5), intArrayOf(5, 5, 5),
        intArrayOf(12, 12, 12, 12, 12, 12), intArrayOf(1, 1, 1, 7), intArrayOf(1, 1, 7, 1),
        intArrayOf(8, 9, 9, 8, 8, 9), intArrayOf(8, 8, 9, 8, 9, 9),
        intArrayOf(10, 10, 11, 11, 11, 10), intArrayOf(12, 12, 12, 12)
    )

    /** Neighbor-driven mode: overlay face counts per shape (client: anIntArray3826) */
    private val NEIGHBOR_BLEND_OVERLAY_COUNT = intArrayOf(4, 2, 1, 1, 2, 2, 3, 1, 3, 3, 3, 2, 0)

    /** Neighbor-driven mode: underlay face counts per shape (client: anIntArray3847) */
    private val NEIGHBOR_BLEND_UNDERLAY_COUNT = intArrayOf(0, 2, 2, 2, 1, 1, 3, 3, 1, 3, 3, 4, 4)

    /** Neighbor-driven mode: face counts for edge insertion (client: anIntArrayArray3828) */
    private val NEIGHBOR_BLEND_FACE_COUNTS = arrayOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, 2, 3, 0), intArrayOf(1, 2, -1, 0),
        intArrayOf(2, 0, -1, 1), intArrayOf(0, 1, -1, 2), intArrayOf(1, 2, -1, 0),
        intArrayOf(-1, 4, -1, 1), intArrayOf(-1, 1, 3, -1), intArrayOf(-1, 0, 2, -1),
        intArrayOf(3, 5, 2, 0), intArrayOf(0, 2, 5, 3), intArrayOf(0, 2, 3, 5),
        intArrayOf(0, 1, 2, 3)
    )

    // ==================== OVERLAY-SUPPORTS-BLENDING MODE TABLES ====================
    //
    // Used when the overlay has blendsWithUnderlay=true AND there is an underlay.
    // This mode creates smooth blending between overlay and underlay colors at tile
    // boundaries, with additional vertex positions for smoother gradients.

    /** Overlay-supports-blending mode: vertex A indices (client: anIntArrayArray3775) */
    private val OVERLAY_BLEND_A = arrayOf(
        intArrayOf(0, 2, 4, 6), intArrayOf(6, 0, 2, 3, 5, 3), intArrayOf(6, 0, 2, 4),
        intArrayOf(2, 5, 6, 1), intArrayOf(0, 2, 6), intArrayOf(6, 0, 2),
        intArrayOf(5, 6, 0, 1, 2, 4), intArrayOf(7, 7, 1, 2, 4, 6), intArrayOf(2, 4, 4, 7),
        intArrayOf(6, 6, 4, 0, 1, 1, 3, 3), intArrayOf(0, 2, 2, 6, 6, 4),
        intArrayOf(0, 2, 2, 3, 7, 0, 4, 3), intArrayOf(0, 2, 4, 6)
    )

    /** Overlay-supports-blending mode: vertex B indices (client: anIntArrayArray3821) */
    private val OVERLAY_BLEND_B = arrayOf(
        intArrayOf(2, 4, 6, 0), intArrayOf(0, 2, 3, 5, 6, 4), intArrayOf(0, 1, 4, 5),
        intArrayOf(4, 6, 0, 2), intArrayOf(2, 4, 0), intArrayOf(0, 2, 4),
        intArrayOf(6, 0, 1, 2, 4, 5), intArrayOf(0, 1, 2, 4, 6, 7), intArrayOf(4, 7, 6, 0),
        intArrayOf(0, 8, 6, 1, 9, 2, 9, 4), intArrayOf(2, 9, 4, 0, 8, 6),
        intArrayOf(2, 11, 3, 7, 10, 10, 6, 6), intArrayOf(2, 4, 6, 0)
    )

    /** Overlay-supports-blending mode: vertex C indices (client: anIntArrayArray3836) */
    private val OVERLAY_BLEND_C = arrayOf(
        intArrayOf(12, 12, 12, 12), intArrayOf(12, 12, 12, 12, 12, 5), intArrayOf(5, 5, 1, 1),
        intArrayOf(5, 1, 1, 5), intArrayOf(5, 5, 5), intArrayOf(5, 5, 5),
        intArrayOf(12, 12, 12, 12, 12, 12), intArrayOf(1, 12, 12, 12, 12, 12), intArrayOf(1, 1, 7, 1),
        intArrayOf(8, 9, 9, 8, 8, 3, 1, 9), intArrayOf(8, 8, 9, 8, 9, 9),
        intArrayOf(10, 10, 11, 11, 11, 7, 3, 7), intArrayOf(12, 12, 12, 12)
    )

    /** Overlay-supports-blending mode: overlay face counts (client: OVERLAY_FACE_COUNT_BLENDED) */
    private val OVERLAY_BLEND_OVERLAY_COUNT = intArrayOf(4, 2, 1, 1, 2, 2, 3, 1, 3, 3, 3, 2, 0)

    /** Overlay-supports-blending mode: underlay face counts (client: UNDERLAY_FACE_COUNT_BLENDED) */
    private val OVERLAY_BLEND_UNDERLAY_COUNT = intArrayOf(0, 4, 3, 3, 1, 1, 3, 5, 1, 5, 3, 6, 4)

    /** Overlay-supports-blending mode: face counts for edge insertion (client: anIntArrayArray3833) */
    private val OVERLAY_BLEND_FACE_COUNTS = arrayOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, -1, -1, 0), intArrayOf(-1, 2, -1, 0),
        intArrayOf(-1, 0, -1, 2), intArrayOf(0, 1, -1, 2), intArrayOf(1, 2, -1, 0),
        intArrayOf(-1, 4, -1, 1), intArrayOf(-1, 3, 4, -1), intArrayOf(-1, 0, 2, -1),
        intArrayOf(-1, -1, 2, 0), intArrayOf(0, 2, 5, 3), intArrayOf(0, -1, 6, -1),
        intArrayOf(0, 1, 2, 3)
    )

    // ==================== SHAPE SUPPORTS HEIGHT TABLE ====================
    //
    // For each shape (0-14), for each vertex (0-12), whether that vertex
    // belongs to the overlay (true) or underlay (false). Used to determine
    // which vertices get overlay colors vs underlay colors.
    // (Already exists in the file as OVERLAY_SHAPE_SUPPORTS_HEIGHT in TRIANGLE_A comments,
    // but used directly from the client's static arrays in the blending system.)

    /** pathSquares: edge collision check for non-blending overlays (client: pathSquares) */
    private val PATH_SQUARES = arrayOf(
        BooleanArray(4), BooleanArray(4),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, false, true),
        booleanArrayOf(true, false, false, true), BooleanArray(4), BooleanArray(4),
        BooleanArray(4), BooleanArray(4)
    )

    /** pathBlendings: edge collision check for blending overlays (client: pathBlendings) */
    private val PATH_BLENDINGS = arrayOf(
        BooleanArray(4), booleanArrayOf(false, true, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, true, false),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, false, true),
        booleanArrayOf(true, false, false, true), booleanArrayOf(true, true, false, false),
        BooleanArray(4), booleanArrayOf(false, true, false, true), BooleanArray(4)
    )

    /**
     * OVERLAY_SHAPE_SUPPORTS_HEIGHT table from the client.
     * For each shape (0-14) and each vertex index (0-12), indicates whether that
     * vertex position belongs to the overlay region of the tile shape.
     * Used for neighbor blending analysis and vertex color assignment.
     */
    private val SHAPE_VERTEX_IS_OVERLAY = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, true, false, false, false, true, true, false, false, false, false, true),
        booleanArrayOf(true, false, false, false, false, true, true, true, false, false, false, false, false),
        booleanArrayOf(false, false, true, true, true, true, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, true, true, true, true, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, true, false, false, true, true, true, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, true, true, true, false, false, false, false, true),
        booleanArrayOf(true, true, false, false, false, false, false, true, false, false, false, false, false),
        booleanArrayOf(false, true, true, true, true, true, true, true, false, false, false, false, false),
        booleanArrayOf(true, false, false, false, true, true, true, true, true, true, false, false, false),
        booleanArrayOf(true, true, true, true, true, false, false, false, true, true, false, false, false),
        booleanArrayOf(true, true, true, false, false, false, false, false, false, false, true, true, false),
        BooleanArray(13),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true, true),
        BooleanArray(13)
    )

    // ==================== BLENDING STATE ====================
    //
    // Per-tile blending state computed by the analysis methods.
    // This captures the client's mutable state (pathShape, tileRotation, hasFacesOn,
    // waterSlots, overlayFunctionMask, etc.) in an immutable data class so tiles
    // can be generated independently after analysis.

    /**
     * Per-tile blending analysis result from the client's addBlendedTiles() pipeline.
     * Captures all the state needed to select face tables and generate vertices.
     */
    internal data class TileBlendState(
        /** The effective overlay shape after potential promotion to 13/14 */
        val effectiveShape: Int,
        /** The effective tile rotation after diagonal selection */
        val effectiveRotation: Int,
        /** Whether overlay supports blending (blendsWithUnderlay AND has underlay AND shape != 0) */
        val overlaySupportsBlending: Boolean,
        /** Whether the tile is unblendable (no neighbor blending and no overlay blending) */
        val unblendable: Boolean,
        /** Edge blending flags: [south, east, north, west] */
        val hasFacesOn: BooleanArray,
        /** Per-vertex slot priority (-1 = no neighbor overlay) for vertices 0-12 */
        val waterSlots: IntArray,
        /** Per-vertex overlay HSL colors from neighbors (valid when waterSlots[i] >= 0) */
        val overlayTileColors: IntArray,
        /** Per-vertex overlay texture IDs from neighbors */
        val waterTextureIds: IntArray,
        /** Per-vertex overlay texture scales from neighbors */
        val waterTextureScaling: IntArray,
        /** The overlay's own HSL color */
        val overlayHslColor: Int,
        /** The overlay's own texture ID */
        val overlayTextureIdBlend: Int,
        /** The overlay's own texture scale */
        val overlayTextureScale: Int,
        /** Whether the overlay casts shadows */
        val hasShadows: Boolean,
        /** Selected face table A indices for this tile */
        val triA: IntArray,
        /** Selected face table B indices for this tile */
        val triB: IntArray,
        /** Selected face table C indices for this tile */
        val triC: IntArray,
        /** Overlay face count for this tile from the selected table */
        val overlayFaceCount: Int,
        /** Underlay face count for this tile from the selected table */
        val underlayFaceCount: Int,
        /** Face counts for edge insertion (null for unblendable mode) */
        val edgeFaceCounts: IntArray?,
        /** Total overlay faces processed (for face index tracking) */
        val overlayTotalFaces: Int,
        /** Per-corner underlay texture IDs: [SW, SE, NW, NE] for per-vertex texture selection */
        val cornerUnderlayTextures: IntArray = intArrayOf(-1, -1, -1, -1),
        /** Per-corner underlay UV scales: [SW, SE, NW, NE] for per-vertex texture selection */
        val cornerUnderlayScales: FloatArray = floatArrayOf(1f/512f, 1f/512f, 1f/512f, 1f/512f)
    )

    /**
     * Analyze blending for a single tile, implementing the client's full
     * calculateUnderlayRotation -> calculateOverlayDisplay -> calculateOverlayRotation
     * -> calculateFaceCounts pipeline.
     *
     * @param region The region data
     * @param plane The plane level
     * @param x Tile X coordinate
     * @param y Tile Y coordinate
     * @return TileBlendState with all blending decisions, or null if tile has no overlay/underlay
     */
    internal fun analyzeTileBlending(
        region: RegionData,
        plane: Int,
        x: Int, y: Int,
        blendedColors: Array<IntArray>
    ): TileBlendState? {
        val tile = region.tiles[plane][x][y]
        val overlayId = tile.overlayId
        val underlayId = tile.underlayId
        if (overlayId == 0 && underlayId == 0) return null

        val overlayDefs = overlays ?: return null
        val underlayDefs = underlays ?: return null

        // Get definitions
        var overlayDef = if (overlayId > 0 && overlayId - 1 < overlayDefs.size) overlayDefs[overlayId - 1] else null
        val underlayDef = if (underlayId > 0 && underlayId - 1 < underlayDefs.size) underlayDefs[underlayId - 1] else null

        // Determine initial shape
        var pathShape = tile.overlayShape
        if (pathShape == 0 && overlayDef == null) {
            pathShape = 12
        }

        // Check if overlay is valid (matching client's null-setting logic)
        val originalOverlay = overlayDef
        var hasShadows = false
        var overlayBlends = false
        val hasFacesOn = BooleanArray(4)

        if (overlayDef != null) {
            if (overlayDef.colour == -1 && overlayDef.blendColour == -1) {
                overlayDef = null
            } else if (underlayDef != null && pathShape != 0) {
                overlayBlends = overlayDef.underlayOverrides
            }
        }

        // Calculate underlay rotation (diagonal selection)
        val nextX = if (x < 63) x + 1 else x
        val nextY = if (y < 63) y + 1 else y
        var tileRotation = tile.overlayRotation

        tileRotation = calculateUnderlayRotationLocal(
            underlayId.toByte(), x, y, nextX, nextY, region, plane, pathShape, tileRotation
        )

        // Initialize per-vertex blending state
        val waterSlots = IntArray(13) { -1 }
        val overlayFunctionMask = IntArray(13) { 1 }
        val overlayTileColors = IntArray(13)
        val waterTextureIds = IntArray(13)
        val waterTextureScaling = IntArray(13)

        // Run neighbor overlay analysis and rotation promotion
        calculateOverlayDisplayLocal(
            overlayDef, x, y, region, plane, pathShape, tileRotation, overlayBlends,
            hasFacesOn, waterSlots, overlayFunctionMask, overlayTileColors,
            waterTextureIds, waterTextureScaling
        )

        // Determine multi-color state (not critical for our rendering, but affects data flow)
        val blendings = if (overlayDef != null && overlayDef.underlayOverrides) {
            PATH_BLENDINGS.getOrNull(pathShape) ?: BooleanArray(4)
        } else {
            PATH_SQUARES.getOrNull(pathShape) ?: BooleanArray(4)
        }

        // Check for additional edge promotion based on overlayFunctionMask
        if (pathShape < blendings.size) {
            if (!blendings[(tileRotation + 1) and 0x3]) {
                hasFacesOn[1] = hasFacesOn[1] || ((overlayFunctionMask[2] and overlayFunctionMask[4]) == 0)
            }
            if (!blendings[(tileRotation + 3) and 0x3]) {
                hasFacesOn[3] = hasFacesOn[3] || ((overlayFunctionMask[6] and overlayFunctionMask[0]) == 0)
            }
            if (!blendings[tileRotation and 0x3]) {
                hasFacesOn[0] = hasFacesOn[0] || ((overlayFunctionMask[0] and overlayFunctionMask[2]) == 0)
            }
            if (!blendings[(tileRotation + 2) and 0x3]) {
                hasFacesOn[2] = hasFacesOn[2] || ((overlayFunctionMask[4] and overlayFunctionMask[6]) == 0)
            }
        }

        // Diagonal shape promotion (shape 0/12 -> 13/14)
        if (!overlayBlends && (pathShape == 0 || pathShape == 12)) {
            if (hasFacesOn[0] && !hasFacesOn[1] && !hasFacesOn[2] && hasFacesOn[3]) {
                hasFacesOn[3] = false; hasFacesOn[0] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 0
            } else if (hasFacesOn[0] && hasFacesOn[1] && !hasFacesOn[2] && !hasFacesOn[3]) {
                hasFacesOn[1] = false; hasFacesOn[0] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 3
            } else if (!hasFacesOn[0] && hasFacesOn[1] && hasFacesOn[2] && !hasFacesOn[3]) {
                hasFacesOn[2] = false; hasFacesOn[1] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 2
            } else if (!hasFacesOn[0] && !hasFacesOn[1] && hasFacesOn[2] && hasFacesOn[3]) {
                hasFacesOn[3] = false; hasFacesOn[2] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 1
            }
        }

        // Determine blending mode
        val unblendable = !overlayBlends && !hasFacesOn[0] && !hasFacesOn[1] && !hasFacesOn[2] && !hasFacesOn[3]

        // Select face tables based on blending mode
        val triA: IntArray
        val triB: IntArray
        val triC: IntArray
        val overlayFaces: Int
        val underlayFaces: Int
        val edgeFaceCounts: IntArray?

        if (unblendable) {
            // Use standard unblendable tables (shapes 0-14)
            triA = TRIANGLE_A.getOrNull(pathShape) ?: intArrayOf()
            triB = TRIANGLE_B.getOrNull(pathShape) ?: intArrayOf()
            triC = TRIANGLE_C.getOrNull(pathShape) ?: intArrayOf()
            overlayFaces = if (overlayDef != null) OVERLAY_FACE_COUNT.getOrElse(pathShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) UNDERLAY_FACE_COUNT.getOrElse(pathShape) { 0 } else 0
            edgeFaceCounts = null
        } else if (overlayBlends) {
            // Use overlay-supports-blending tables (shapes 0-12)
            val safeShape = pathShape.coerceIn(0, OVERLAY_BLEND_A.size - 1)
            triA = OVERLAY_BLEND_A[safeShape]
            triB = OVERLAY_BLEND_B[safeShape]
            triC = OVERLAY_BLEND_C[safeShape]
            overlayFaces = if (overlayDef != null) OVERLAY_BLEND_OVERLAY_COUNT.getOrElse(safeShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) OVERLAY_BLEND_UNDERLAY_COUNT.getOrElse(safeShape) { 0 } else 0
            edgeFaceCounts = OVERLAY_BLEND_FACE_COUNTS.getOrNull(safeShape)
        } else {
            // Use neighbor-driven blending tables (shapes 0-12)
            val safeShape = pathShape.coerceIn(0, NEIGHBOR_BLEND_A.size - 1)
            triA = NEIGHBOR_BLEND_A[safeShape]
            triB = NEIGHBOR_BLEND_B[safeShape]
            triC = NEIGHBOR_BLEND_C[safeShape]
            overlayFaces = if (overlayDef != null) NEIGHBOR_BLEND_OVERLAY_COUNT.getOrElse(safeShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) NEIGHBOR_BLEND_UNDERLAY_COUNT.getOrElse(safeShape) { 0 } else 0
            edgeFaceCounts = NEIGHBOR_BLEND_FACE_COUNTS.getOrNull(safeShape)
        }

        // Overlay color and texture
        val overlayHsl = if (overlayDef != null && overlayDef.colour != -1) overlayDef.colour else -1
        val overlayTexId = overlayDef?.texture ?: -1
        val overlayTexScale = overlayDef?.scale ?: 256

        // Shadow check
        if (overlayDef != null && pathShape != 12 && overlayDef.colour != -1 && overlayDef.blockShadow) {
            hasShadows = true
        }

        return TileBlendState(
            effectiveShape = pathShape,
            effectiveRotation = tileRotation,
            overlaySupportsBlending = overlayBlends,
            unblendable = unblendable,
            hasFacesOn = hasFacesOn,
            waterSlots = waterSlots,
            overlayTileColors = overlayTileColors,
            waterTextureIds = waterTextureIds,
            waterTextureScaling = waterTextureScaling,
            overlayHslColor = overlayHsl,
            overlayTextureIdBlend = overlayTexId,
            overlayTextureScale = overlayTexScale,
            hasShadows = hasShadows,
            triA = triA,
            triB = triB,
            triC = triC,
            overlayFaceCount = overlayFaces,
            underlayFaceCount = underlayFaces,
            edgeFaceCounts = edgeFaceCounts,
            overlayTotalFaces = 0
        )
    }

    /**
     * Local implementation of calculateUnderlayRotation matching the client.
     * Determines which diagonal to use for plain tiles (shape 0/12) based on
     * underlay continuity with neighbors.
     */
    private fun calculateUnderlayRotationLocal(
        underlayId: Byte, x: Int, y: Int, nextX: Int, nextY: Int,
        region: RegionData, plane: Int, pathShape: Int, currentRotation: Int
    ): Int {
        if ((pathShape == 0 || pathShape == 12) && x > 0 && y > 0 && x < 64 && y < 64) {
            val tiles = region.tiles[plane]
            val uid = underlayId.toInt()
            var swScore = if (tiles[x - 1][y - 1].underlayId.toByte().toInt() == uid) 1 else -1
            var seScore = if (tiles[nextX][y - 1].underlayId.toByte().toInt() == uid) 1 else -1
            var neScore = if (tiles[nextX][nextY].underlayId.toByte().toInt() == uid) 1 else -1
            var nwScore = if (tiles[x - 1][nextY].underlayId.toByte().toInt() == uid) 1 else -1

            if (tiles[x][y - 1].underlayId.toByte().toInt() == uid) { swScore++; seScore++ } else { swScore--; seScore-- }
            if (tiles[nextX][y].underlayId.toByte().toInt() == uid) { seScore++; neScore++ } else { seScore--; neScore-- }
            if (tiles[x][nextY].underlayId.toByte().toInt() == uid) { neScore++; nwScore++ } else { neScore--; nwScore-- }
            if (tiles[x - 1][y].underlayId.toByte().toInt() == uid) { nwScore++; swScore++ } else { nwScore--; swScore-- }

            var swNeDelta = Math.abs(swScore - neScore)
            var seNwDelta = Math.abs(seScore - nwScore)

            if (swNeDelta == seNwDelta) {
                // Tie-break with height difference
                swNeDelta = Math.abs(tiles[x][y].height - tiles[nextX][nextY].height)
                seNwDelta = Math.abs(tiles[nextX][y].height - tiles[x][nextY].height)
            }

            return if (swNeDelta < seNwDelta) 1 else 0
        }
        return currentRotation
    }

    /**
     * Local implementation of calculateOverlayDisplay matching the client.
     * Examines all 8 neighboring tiles to find overlays with blendsWithUnderlay=true
     * that should blend their colors into this tile's vertices.
     */
    private fun calculateOverlayDisplayLocal(
        overlayType: OverlayDefinition?,
        x: Int, y: Int,
        region: RegionData, plane: Int,
        pathShape: Int, tileRotation: Int,
        overlayBlends: Boolean,
        hasFacesOn: BooleanArray,
        waterSlots: IntArray,
        overlayFunctionMask: IntArray,
        overlayTileColors: IntArray,
        waterTextureIds: IntArray,
        waterTextureScaling: IntArray
    ) {
        val overlayDefs = overlays ?: return
        val tiles = region.tiles[plane]

        val blendingAttributes = if (overlayType != null && overlayType.underlayOverrides) {
            PATH_BLENDINGS.getOrNull(pathShape) ?: BooleanArray(4)
        } else {
            PATH_SQUARES.getOrNull(pathShape) ?: BooleanArray(4)
        }

        // Check diagonal neighbors (corners)
        // SW neighbor: tiles[x-1][y-1], affects vertex slot 0
        if (y > 0 && x > 0) {
            val nOverlayId = tiles[x - 1][y - 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x - 1][y - 1].overlayShape
                    val rotation = (tiles[x - 1][y - 1].overlayRotation * 2 + 4) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[0] = overlay.colour
                        waterTextureIds[0] = overlay.texture
                        waterTextureScaling[0] = overlay.scale
                        waterSlots[0] = overlay.anInt3633
                        overlayFunctionMask[0] = 256
                    }
                }
            }
        }

        // SE neighbor: tiles[x+1][y-1], affects vertex slot 2
        if (y > 0 && x < 63) {
            val nOverlayId = tiles[x + 1][y - 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x + 1][y - 1].overlayShape
                    val rotation = (tiles[x + 1][y - 1].overlayRotation * 2 + 6) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[2] = overlay.colour
                        waterTextureIds[2] = overlay.texture
                        waterTextureScaling[2] = overlay.scale
                        waterSlots[2] = overlay.anInt3633
                        overlayFunctionMask[2] = 512
                    }
                }
            }
        }

        // NW neighbor: tiles[x-1][y+1], affects vertex slot 6
        if (y < 63 && x > 0) {
            val nOverlayId = tiles[x - 1][y + 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x - 1][y + 1].overlayShape
                    val rotation = (tiles[x - 1][y + 1].overlayRotation * 2 + 2) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[6] = overlay.colour
                        waterTextureIds[6] = overlay.texture
                        waterTextureScaling[6] = overlay.scale
                        waterSlots[6] = overlay.anInt3633
                        overlayFunctionMask[6] = 64
                    }
                }
            }
        }

        // NE neighbor: tiles[x+1][y+1], affects vertex slot 4
        if (y < 63 && x < 63) {
            val nOverlayId = tiles[x + 1][y + 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x + 1][y + 1].overlayShape
                    val rotation = (tiles[x + 1][y + 1].overlayRotation * 2) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[4] = overlay.colour
                        waterTextureIds[4] = overlay.texture
                        waterTextureScaling[4] = overlay.scale
                        waterSlots[4] = overlay.anInt3633
                        overlayFunctionMask[4] = 128
                    }
                }
            }
        }

        // Cardinal neighbors: South (y-1), North (y+1), West (x-1), East (x+1)
        // These affect 3 vertex slots each and set hasFacesOn flags

        // South neighbor: tiles[x][y-1]
        if (y > 0) {
            val nOverlayId = tiles[x][y - 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x][y - 1].overlayShape
                    val rotation = tiles[x][y - 1].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotA = 2
                        var slotB = (rotation * 2 + 4)
                        for (i in 0..2) {
                            slotB = slotB and 0x7
                            slotA = slotA and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotB < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotB] && waterSlots[slotA] <= overlay.anInt3633) {
                                overlayTileColors[slotA] = overlay.colour
                                waterTextureIds[slotA] = overlay.texture
                                waterTextureScaling[slotA] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotA]) {
                                    overlayFunctionMask[slotA] = overlayFunctionMask[slotA] or 0x20
                                } else {
                                    overlayFunctionMask[slotA] = 32
                                }
                                waterSlots[slotA] = overlay.anInt3633
                            }
                            slotB++
                            slotA--
                        }
                        if (pathShape < blendingAttributes.size && !blendingAttributes[tileRotation and 0x3]) {
                            hasFacesOn[0] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 2) and 0x3] else false
                        }
                    } else if (pathShape < blendingAttributes.size && !blendingAttributes[tileRotation and 0x3]) {
                        hasFacesOn[0] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 2) and 0x3] else false
                    }
                }
            }
        }

        // North neighbor: tiles[x][y+1]
        if (y < 63) {
            val nOverlayId = tiles[x][y + 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x][y + 1].overlayShape
                    val rotation = tiles[x][y + 1].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotA = 4
                        var slotB = (rotation * 2 + 2)
                        for (i in 0..2) {
                            slotB = slotB and 0x7
                            slotA = slotA and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotB < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotB] && waterSlots[slotA] <= overlay.anInt3633) {
                                overlayTileColors[slotA] = overlay.colour
                                waterTextureIds[slotA] = overlay.texture
                                waterTextureScaling[slotA] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotA]) {
                                    overlayFunctionMask[slotA] = overlayFunctionMask[slotA] or 0x10
                                } else {
                                    overlayFunctionMask[slotA] = 16
                                }
                                waterSlots[slotA] = overlay.anInt3633
                            }
                            slotB--
                            slotA++
                        }
                        if (pathShape < blendingAttributes.size && !blendingAttributes[(tileRotation + 2) and 0x3]) {
                            hasFacesOn[2] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][rotation and 0x3] else false
                        }
                    } else if (pathShape < blendingAttributes.size && !blendingAttributes[(tileRotation + 2) and 0x3]) {
                        hasFacesOn[2] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][rotation and 0x3] else false
                    }
                }
            }
        }

        // West neighbor: tiles[x-1][y]
        if (x > 0) {
            val nOverlayId = tiles[x - 1][y].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x - 1][y].overlayShape
                    val rotation = tiles[x - 1][y].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotA = 6
                        var slotB = (rotation * 2 + 4)
                        for (i in 0..2) {
                            slotB = slotB and 0x7
                            slotA = slotA and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotB < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotB] && waterSlots[slotA] <= overlay.anInt3633) {
                                overlayTileColors[slotA] = overlay.colour
                                waterTextureIds[slotA] = overlay.texture
                                waterTextureScaling[slotA] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotA]) {
                                    overlayFunctionMask[slotA] = overlayFunctionMask[slotA] or 0x8
                                } else {
                                    overlayFunctionMask[slotA] = 8
                                }
                                waterSlots[slotA] = overlay.anInt3633
                            }
                            slotB--
                            slotA++
                        }
                        if (pathShape < blendingAttributes.size && !blendingAttributes[(tileRotation + 3) and 0x3]) {
                            hasFacesOn[3] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 1) and 0x3] else false
                        }
                    } else if (pathShape < blendingAttributes.size && !blendingAttributes[(tileRotation + 3) and 0x3]) {
                        hasFacesOn[3] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 1) and 0x3] else false
                    }
                }
            }
        }

        // East neighbor: tiles[x+1][y]
        if (x < 63) {
            val nOverlayId = tiles[x + 1][y].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x + 1][y].overlayShape
                    val rotation = tiles[x + 1][y].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotB = 4
                        var slotA = (rotation * 2 + 6)
                        for (i in 0..2) {
                            slotA = slotA and 0x7
                            slotB = slotB and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotA < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotA] && waterSlots[slotB] <= overlay.anInt3633) {
                                overlayTileColors[slotB] = overlay.colour
                                waterTextureIds[slotB] = overlay.texture
                                waterTextureScaling[slotB] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotB]) {
                                    overlayFunctionMask[slotB] = overlayFunctionMask[slotB] or 0x4
                                } else {
                                    overlayFunctionMask[slotB] = 4
                                }
                                waterSlots[slotB] = overlay.anInt3633
                            }
                            slotA++
                            slotB--
                        }
                        if (pathShape < blendingAttributes.size && !blendingAttributes[(tileRotation + 1) and 0x3]) {
                            hasFacesOn[1] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 3) and 0x3] else false
                        }
                    } else if (pathShape < blendingAttributes.size && !blendingAttributes[(tileRotation + 1) and 0x3]) {
                        hasFacesOn[1] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 3) and 0x3] else false
                    }
                }
            }
        }

        // Self-overlay blending: if this tile's overlay blends with underlay,
        // set its own color into vertex slots where SHAPE_VERTEX_IS_OVERLAY is true
        if (overlayType != null && overlayType.underlayOverrides) {
            for (index in 0..7) {
                val signedRotation = (index - tileRotation * 2) and 0x7
                if (pathShape < SHAPE_VERTEX_IS_OVERLAY.size && index < SHAPE_VERTEX_IS_OVERLAY[pathShape].size
                    && SHAPE_VERTEX_IS_OVERLAY[pathShape][index] && waterSlots[signedRotation] <= overlayType.anInt3633) {
                    overlayTileColors[signedRotation] = overlayType.colour
                    waterTextureIds[signedRotation] = overlayType.texture
                    waterTextureScaling[signedRotation] = overlayType.scale
                    if (overlayType.anInt3633 == waterSlots[signedRotation]) {
                        overlayFunctionMask[signedRotation] = overlayFunctionMask[signedRotation] or 0x2
                    } else {
                        overlayFunctionMask[signedRotation] = 2
                    }
                    waterSlots[signedRotation] = overlayType.anInt3633
                }
            }
        }
    }

    /**
     * Data class to hold vertex data for shaped tile generation.
     * Supports multi-pass alpha rendering for smooth underlay transitions.
     *
     * The alpha field controls per-vertex opacity for multi-pass blending.
     * Opaque vertices (alpha=1.0) go into standard textured batches.
     * Translucent vertices (alpha<1.0) go into alpha batches that are rendered
     * on top of the opaque base layer with GL alpha blending. The GPU interpolates
     * alpha across the triangle for smooth per-fragment transitions between textures.
     */
    internal data class ShapedVertex(
        val x: Float, val y: Float, val z: Float,
        val r: Float, val g: Float, val b: Float,
        val u: Float, val v: Float,
        val nx: Float, val ny: Float, val nz: Float,
        val textureId: Int,
        /** Per-vertex alpha for multi-pass blending (1.0 = fully opaque, 0.0 = fully transparent) */
        val alpha: Float = 1f
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
    internal fun generateShapedTile(
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
        regionId: Int = 0,
        blendState: TileBlendState? = null,
        cornerUnderlayTextures: IntArray = intArrayOf(-1, -1, -1, -1),
        cornerUnderlayScales: FloatArray = floatArrayOf(1f/512f, 1f/512f, 1f/512f, 1f/512f)
    ): List<ShapedVertex> {
        val vertices = mutableListOf<ShapedVertex>()

        val safeShape = shape.coerceIn(0, TRIANGLE_A.size - 1)

        // Determine face tables and counts based on whether we have blend state
        val triA: IntArray
        val triB: IntArray
        val triC: IntArray
        val overlayFaces: Int
        val underlayFaces: Int

        if (blendState != null) {
            // For unblendable tiles with shape 0/12, prefer enhanced tessellation for smoother gradients
            val useEnhanced = blendState.unblendable && enableEnhancedTessellation && (safeShape == 0 || safeShape == 12)
            if (useEnhanced) {
                triA = TRIANGLE_A_ENHANCED[safeShape]
                triB = TRIANGLE_B_ENHANCED[safeShape]
                triC = TRIANGLE_C_ENHANCED[safeShape]
                overlayFaces = if (hasOverlay) OVERLAY_FACE_COUNT_ENHANCED[safeShape] else 0
                underlayFaces = if (hasUnderlay) UNDERLAY_FACE_COUNT_ENHANCED[safeShape] else 0
            } else {
                triA = blendState.triA
                triB = blendState.triB
                triC = blendState.triC
                overlayFaces = if (hasOverlay) blendState.overlayFaceCount else 0
                underlayFaces = if (hasUnderlay) blendState.underlayFaceCount else 0
            }
        } else {
            // Fallback: use enhanced tessellation for shapes 0 and 12 if enabled
            val useEnhanced = enableEnhancedTessellation && (safeShape == 0 || safeShape == 12)
            triA = if (useEnhanced) TRIANGLE_A_ENHANCED[safeShape] else TRIANGLE_A[safeShape]
            triB = if (useEnhanced) TRIANGLE_B_ENHANCED[safeShape] else TRIANGLE_B[safeShape]
            triC = if (useEnhanced) TRIANGLE_C_ENHANCED[safeShape] else TRIANGLE_C[safeShape]
            overlayFaces = if (hasOverlay) {
                if (useEnhanced) OVERLAY_FACE_COUNT_ENHANCED[safeShape] else OVERLAY_FACE_COUNT[safeShape]
            } else 0
            underlayFaces = if (hasUnderlay) {
                if (useEnhanced) UNDERLAY_FACE_COUNT_ENHANCED[safeShape] else UNDERLAY_FACE_COUNT[safeShape]
            } else 0
        }

        val totalFaces = overlayFaces + underlayFaces
        if (totalFaces == 0) return vertices

        // Base position for this tile
        val baseX = x * tileSize
        val baseZ = -y * tileSize  // Flip Z for OpenGL

        // Generate rotated vertex positions for this tile
        val vertexX = FloatArray(13)
        val vertexZ = FloatArray(13)
        val vertexY = FloatArray(13)
        val vertexLocalX = IntArray(13)
        val vertexLocalY = IntArray(13)

        for (i in 0 until 13) {
            var vx = TILE_VERTEX_X[i]
            var vy = TILE_VERTEX_Y[i]

            when (rotation) {
                1 -> { val temp = vx; vx = vy; vy = 512 - temp }
                2 -> { vx = 512 - vx; vy = 512 - vy }
                3 -> { val temp = vx; vx = 512 - vy; vy = temp }
            }

            vertexLocalX[i] = vx
            vertexLocalY[i] = vy
            vertexX[i] = baseX + (vx.toFloat() / 512f) * tileSize
            vertexZ[i] = baseZ - (vy.toFloat() / 512f) * tileSize

            val fx = vx.toFloat() / 512f
            val fy = vy.toFloat() / 512f
            vertexY[i] = (1 - fx) * (1 - fy) * heights[0] +
                         fx * (1 - fy) * heights[1] +
                         (1 - fx) * fy * heights[2] +
                         fx * fy * heights[3]
        }

        // Compute per-vertex quadrant for underlay texture selection
        // (matching client's addUnderlayTiles: SW=0, SE=1, NW=2, NE=3)
        fun getVertexQuadrant(vIdx: Int): Int {
            val lx = vertexLocalX[vIdx]
            val ly = vertexLocalY[vIdx]
            return if (lx < 256) {
                if (ly < 256) 0 else 2  // SW or NW
            } else {
                if (ly < 256) 1 else 3  // SE or NE
            }
        }

        // Interpolate normals from corner normals
        val (nSW, nSE, nNW, nNE) = when (rotation) {
            0 -> arrayOf(cornerNormals[0], cornerNormals[1], cornerNormals[2], cornerNormals[3])
            1 -> arrayOf(cornerNormals[1], cornerNormals[3], cornerNormals[0], cornerNormals[2])
            2 -> arrayOf(cornerNormals[3], cornerNormals[2], cornerNormals[1], cornerNormals[0])
            3 -> arrayOf(cornerNormals[2], cornerNormals[0], cornerNormals[3], cornerNormals[1])
            else -> arrayOf(cornerNormals[0], cornerNormals[1], cornerNormals[2], cornerNormals[3])
        }

        val vertexNormals = Array(13) { i ->
            val fx = vertexLocalX[i].toFloat() / 512f
            val fy = vertexLocalY[i].toFloat() / 512f
            val southNormal = Vector3f(nSW).lerp(nSE, fx)
            val northNormal = Vector3f(nNW).lerp(nNE, fx)
            val interpolated = Vector3f(southNormal).lerp(northNormal, fy)
            val len = interpolated.length()
            if (len > 0.0001f) interpolated.div(len) else Vector3f(0f, 1f, 0f)
        }

        // Rotate underlay corner colors to match rotation
        val (hslSW, hslSE, hslNW, hslNE) = when (rotation) {
            0 -> arrayOf(underlayCornerHsl[0], underlayCornerHsl[1], underlayCornerHsl[2], underlayCornerHsl[3])
            1 -> arrayOf(underlayCornerHsl[1], underlayCornerHsl[3], underlayCornerHsl[0], underlayCornerHsl[2])
            2 -> arrayOf(underlayCornerHsl[3], underlayCornerHsl[2], underlayCornerHsl[1], underlayCornerHsl[0])
            3 -> arrayOf(underlayCornerHsl[2], underlayCornerHsl[0], underlayCornerHsl[3], underlayCornerHsl[1])
            else -> arrayOf(underlayCornerHsl[0], underlayCornerHsl[1], underlayCornerHsl[2], underlayCornerHsl[3])
        }

        // Helper: apply lighting and noise to an HSL color and convert to RGB
        fun applyEffectsAndConvert(hsl: Int, vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            var result = hsl
            if (result == 0) return Vector3f(0f, 0f, 0f)
            if (enableColorNoise) {
                result = ColorUtil.applyBrightnessNoise(result, ColorUtil.getVertexNoise(worldPosX, worldPosZ, colorNoiseAmount))
            }
            if (enableLighting) {
                val normal = vertexNormals[vIdx]
                val lightFactor = ColorUtil.calculateTerrainLightFactor(
                    normal.x, normal.y, normal.z,
                    LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                    SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                )
                result = ColorUtil.applyLightToHsl(result, lightFactor)
            }
            return ColorUtil.hslToRgb(result)
        }

        // Helper: get underlay color for a vertex, considering neighbor overlay blending
        fun getUnderlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            // Check if this vertex has a neighbor overlay color from the blend analysis
            if (blendState != null && vIdx < 8) {
                val slot = (vIdx - rotation * 2) and 0x7
                if (blendState.waterSlots[slot] >= 0) {
                    // Use the neighbor overlay's color at this vertex
                    val neighborHsl = blendState.overlayTileColors[slot]
                    if (neighborHsl != 0) {
                        return applyEffectsAndConvert(neighborHsl, vIdx, worldPosX, worldPosZ)
                    }
                }
            }

            // Check if this vertex should use overlay color (for overlaySupportsBlending mode)
            if (blendState != null && blendState.overlaySupportsBlending && vIdx < 13) {
                val unrotatedIdx = if (vIdx < 8) vIdx else vIdx  // Interior vertices don't rotate
                if (blendState.effectiveShape < SHAPE_VERTEX_IS_OVERLAY.size
                    && unrotatedIdx < SHAPE_VERTEX_IS_OVERLAY[blendState.effectiveShape].size
                    && SHAPE_VERTEX_IS_OVERLAY[blendState.effectiveShape][unrotatedIdx]) {
                    // This vertex belongs to the overlay region - use overlay color
                    val oHsl = blendState.overlayHslColor
                    if (oHsl > 0) {
                        return applyEffectsAndConvert(oHsl, vIdx, worldPosX, worldPosZ)
                    }
                }
            }

            // Standard underlay bilinear interpolation in HSL space
            val localX = vertexLocalX[vIdx]
            val localY = vertexLocalY[vIdx]
            val fx = localX.toFloat() / 512f
            val fy = localY.toFloat() / 512f

            // Bilinear blend corner colors
            var blendedHsl: Int
            if (localX == 0 && localY == 0) {
                blendedHsl = hslSW
            } else if (localX == 0 && localY == 512) {
                blendedHsl = hslNW
            } else if (localX == 512 && localY == 512) {
                blendedHsl = hslNE
            } else if (localX == 512 && localY == 0) {
                blendedHsl = hslSE
            } else {
                // Interior point: bilinear interpolation using RS blend()
                // Blend factor is (localPos << 7) >> 9 = localPos / 4
                // But the client uses: sizeX shl 7 shr 9 which is localX / 4 (0-128 range for 0-512)
                val factorX = (localX shl 7) shr 9  // = localX / 4, range 0-128
                val factorY = (localY shl 7) shr 9  // = localY / 4, range 0-128
                val south = ColorUtil.blendHsl(hslSW, hslSE, factorX)
                val north = ColorUtil.blendHsl(hslNW, hslNE, factorX)
                blendedHsl = ColorUtil.blendHsl(south, north, factorY)
            }

            if (blendedHsl == 0) return Vector3f(0f, 0f, 0f)
            return applyEffectsAndConvert(blendedHsl, vIdx, worldPosX, worldPosZ)
        }

        // Helper: get overlay color for a vertex, checking neighbor overrides
        fun getOverlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            // Check if this vertex has a higher-priority neighbor overlay
            if (blendState != null && vIdx < 8) {
                val slot = (vIdx - rotation * 2) and 0x7
                if (blendState.waterSlots[slot] > (blendState.overlayHslColor shr 8)) {
                    // Higher priority neighbor wins
                    val neighborHsl = blendState.overlayTileColors[slot]
                    if (neighborHsl != 0) {
                        return applyEffectsAndConvert(neighborHsl, vIdx, worldPosX, worldPosZ)
                    }
                }
            }

            // Use the tile's own overlay color
            if (overlayHsl != 0) {
                return applyEffectsAndConvert(overlayHsl, vIdx, worldPosX, worldPosZ)
            }

            // For textured overlays without color, apply lighting in RGB space
            val lightFactor = if (enableLighting) {
                val normal = vertexNormals[vIdx]
                ColorUtil.calculateTerrainLightFactor(
                    normal.x, normal.y, normal.z,
                    LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                    SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                )
            } else 1.0f
            val noiseFactor = if (enableColorNoise) {
                ColorUtil.getVertexNoise(worldPosX, worldPosZ, colorNoiseAmount)
            } else 1.0f
            val combinedFactor = lightFactor * noiseFactor
            return Vector3f(
                (overlayColor.x * combinedFactor).coerceIn(0f, 1f),
                (overlayColor.y * combinedFactor).coerceIn(0f, 1f),
                (overlayColor.z * combinedFactor).coerceIn(0f, 1f)
            )
        }

        // Process the face tables using the client's addOverlayTiles + addUnderlayTiles pattern.
        // The client iterates overlay faces first, then underlay faces, using a shared index
        // (overlayTotalFaces) that tracks progress through the combined face table.
        // When hasFacesOn flags are set and the current face matches an edgeFaceCounts entry,
        // edge triangles are inserted that bridge between the main tile and the neighbor.

        var overlayTotalFaces = 0
        var skippedOverlayFaces = 0

        // Helper to emit a single triangle using multi-pass alpha rendering.
        // For underlay triangles where vertices span different textures:
        //   1. The "winning" texture (most common corner texture) is emitted as a standard
        //      opaque triangle into the regular textured batch.
        //   2. Each non-winning texture is emitted AGAIN as an alpha-blended layer with
        //      per-vertex alpha derived from bilinear weights of corners using that texture.
        // This handles 2, 3, and 4 texture junctions correctly.
        fun emitTriangle(ia: Int, ib: Int, ic: Int, isOverlay: Boolean, textureId: Int, uvScale: Float) {
            val vertexIndicesArr = intArrayOf(ia, ib, ic)

            // Check if this is an underlay triangle with texture variation across corners
            val tileHasTextureVariation = !isOverlay && run {
                val validTextures = cornerUnderlayTextures.filter { it >= 0 }
                validTextures.distinct().size >= 2
            }

            // Helper to create a vertex with a given texture, UV scale, and alpha
            fun createVertex(vIdx: Int, texId: Int, vUvScale: Float, vAlpha: Float): ShapedVertex {
                val px = vertexX[vIdx]
                val py = vertexY[vIdx]
                val pz = vertexZ[vIdx]
                val color = if (isOverlay) getOverlayVertexColor(vIdx, px, pz)
                            else getUnderlayVertexColor(vIdx, px, pz)

                val localXVal = vertexLocalX[vIdx]
                val localYVal = vertexLocalY[vIdx]
                val sceneX = (x * 512 + localXVal).toFloat()
                val sceneY = (y * 512 + localYVal).toFloat()
                val smoothNormal = vertexNormals[vIdx]

                val u = sceneX * vUvScale
                val v = sceneY * vUvScale

                return ShapedVertex(
                    px, py, pz,
                    color.x, color.y, color.z,
                    u, v,
                    smoothNormal.x, smoothNormal.y, smoothNormal.z,
                    texId,
                    alpha = vAlpha
                )
            }

            if (tileHasTextureVariation) {
                // Multi-pass alpha rendering for texture variation tiles.
                // Determine "winning" texture = most common corner texture (tie-break: lower texture ID)
                val cornerTexCounts = cornerUnderlayTextures.filter { it >= 0 }
                    .groupBy { it }.mapValues { it.value.size }
                val maxCount = cornerTexCounts.values.maxOrNull() ?: 0
                val winningTexId = cornerTexCounts.entries
                    .filter { it.value == maxCount }
                    .minByOrNull { it.key }?.key ?: textureId

                // Look up UV scale for the winning texture
                var winningUvScale = uvScale
                for (ci in 0..3) {
                    if (cornerUnderlayTextures[ci] == winningTexId) {
                        winningUvScale = cornerUnderlayScales[ci]
                        break
                    }
                }

                // 1. Emit the triangle into the REGULAR textured batch with the winning texture (full opacity)
                for (vIdx in vertexIndicesArr) {
                    vertices.add(createVertex(vIdx, winningTexId, winningUvScale, 1f))
                }

                // 2. For each non-winning distinct corner texture, emit the triangle AGAIN
                //    into an alpha batch with per-vertex alpha from bilinear weights
                val distinctTextures = cornerUnderlayTextures.filter { it >= 0 }.distinct()
                for (layerTexId in distinctTextures) {
                    if (layerTexId == winningTexId) continue

                    // Look up UV scale for this layer texture
                    var layerUvScale = uvScale
                    for (ci in 0..3) {
                        if (cornerUnderlayTextures[ci] == layerTexId) {
                            layerUvScale = cornerUnderlayScales[ci]
                            break
                        }
                    }

                    // Emit triangle with per-vertex alpha
                    for (vIdx in vertexIndicesArr) {
                        val localXVal = vertexLocalX[vIdx]
                        val localYVal = vertexLocalY[vIdx]
                        val fx = localXVal.toFloat() / 512f
                        val fy = localYVal.toFloat() / 512f
                        val wSW = (1f - fx) * (1f - fy)  // corner 0
                        val wSE = fx * (1f - fy)          // corner 1
                        val wNW = (1f - fx) * fy           // corner 2
                        val wNE = fx * fy                  // corner 3

                        // Alpha = sum of bilinear weights for corners that have this texture
                        val cornerWeights = floatArrayOf(wSW, wSE, wNW, wNE)
                        var alpha = 0f
                        for (ci in 0..3) {
                            if (cornerUnderlayTextures[ci] == layerTexId) {
                                alpha += cornerWeights[ci]
                            }
                        }

                        vertices.add(createVertex(vIdx, layerTexId, layerUvScale, alpha))
                    }
                }
            } else {
                // No texture variation - standard single-texture path (full opacity)
                for (vi in 0..2) {
                    val vIdx = vertexIndicesArr[vi]
                    val q = getVertexQuadrant(vIdx)
                    val myTexId = if (!isOverlay && cornerUnderlayTextures[q] >= 0) cornerUnderlayTextures[q] else textureId
                    val myUvScale = if (!isOverlay && cornerUnderlayTextures[q] >= 0) cornerUnderlayScales[q] else uvScale
                    vertices.add(createVertex(vIdx, myTexId, myUvScale, 1f))
                }
            }
        }

        // Helper to emit a triangle from face table indices with edge triangle expansion
        fun emitFace(isOverlay: Boolean, faceIndex: Int) {
            if (isOverlay && !enableOverlays) {
                skippedOverlayFaces++
                overlayTotalFaces++
                return
            }

            // Determine texture and UV scale for this face
            val faceTextureId: Int
            val faceUvScale: Float

            if (isOverlay) {
                faceTextureId = overlayTextureId
                faceUvScale = overlayUvScale
            } else {
                // Per-face underlay texture from corner quadrant selection
                // (matching client's addUnderlayTiles per-vertex texture assignment)
                val faceVertexA = if (overlayTotalFaces < triA.size) triA[overlayTotalFaces] else 0
                val quadrant = getVertexQuadrant(faceVertexA)
                faceTextureId = cornerUnderlayTextures[quadrant]
                faceUvScale = cornerUnderlayScales[quadrant]
            }

            // Determine if this face triggers edge triangle insertion
            // The client checks: hasFacesOn[edge] && overlayTotalFaces == edgeFaceCounts[edge]
            // Edge mapping: 0=south (vertex 1), 1=east (vertex 3), 2=north (vertex 5), 3=west (vertex 7)
            val edgeFaceCounts = blendState?.edgeFaceCounts
            val hasFacesOn = blendState?.hasFacesOn

            // Check each edge direction for insertion
            // Client checks edges in order S(0), N(2), E(1), W(3) - order matters for if-else-if
            val edgeCheckOrder = intArrayOf(0, 2, 1, 3)  // S, N, E, W (matching client)
            val edgeVertices = intArrayOf(1, 3, 5, 7)    // S, E, N, W edge midpoint vertices
            var edgeInserted = false

            if (edgeFaceCounts != null && hasFacesOn != null) {
                for (checkIdx in 0..3) {
                    val edge = edgeCheckOrder[checkIdx]
                    val rotatedEdge = (edge - rotation) and 0x3
                    if (hasFacesOn[rotatedEdge] && edgeFaceCounts[edge] == overlayTotalFaces) {
                        edgeInserted = true
                        val edgeVertex = edgeVertices[edge]

                        // For underlay edge triangles, use the edge vertex's quadrant texture
                        val edgeTextureId: Int
                        val edgeUvScale: Float
                        if (!isOverlay) {
                            val edgeQuadrant = getVertexQuadrant(edgeVertex)
                            edgeTextureId = cornerUnderlayTextures[edgeQuadrant]
                            edgeUvScale = cornerUnderlayScales[edgeQuadrant]
                        } else {
                            edgeTextureId = faceTextureId
                            edgeUvScale = faceUvScale
                        }

                        // Emit 2 triangles: original triangle split by the edge midpoint
                        // Triangle 1: triA[faceIndex], edgeVertex, triC[faceIndex]
                        // Triangle 2: triA[faceIndex], triB[faceIndex], edgeVertex
                        // (matching client's 6-vertex expansion)
                        val ia1 = triA[overlayTotalFaces]
                        val ic1 = triC[overlayTotalFaces]
                        val ib2 = triB[overlayTotalFaces]

                        // Triangle 1: A -> edgeVertex -> C
                        emitTriangle(ia1, edgeVertex, ic1, isOverlay, edgeTextureId, edgeUvScale)
                        // Triangle 2: edgeVertex -> B -> C
                        emitTriangle(edgeVertex, ib2, ic1, isOverlay, edgeTextureId, edgeUvScale)
                        break
                    }
                }
            }

            if (!edgeInserted) {
                // Standard triangle: A, B, C from face tables
                if (overlayTotalFaces < triA.size) {
                    val ia = triA[overlayTotalFaces]
                    val ib = triB[overlayTotalFaces]
                    val ic = triC[overlayTotalFaces]
                    emitTriangle(ia, ib, ic, isOverlay, faceTextureId, faceUvScale)
                }
            }

            overlayTotalFaces++
        }

        // Emit overlay faces first, then underlay faces
        for (face in 0 until overlayFaces) {
            emitFace(true, face)
        }

        // If overlay faces were skipped (no visible overlay), advance the counter
        // to match the client's behavior where overlayTotalFaces is always advanced
        // past overlay entries before underlay faces begin
        if (overlayFaces == 0 && blendState != null) {
            overlayTotalFaces = blendState.overlayFaceCount
        }

        for (face in 0 until underlayFaces) {
            emitFace(false, face)
        }

        return vertices
    }
}
