package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.region.RegionLoadProfiler
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
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object TerrainLoader {
    private sealed class CacheResult<out T> {
        data class Found<T>(val value: T) : CacheResult<T>()
        data object NotFound : CacheResult<Nothing>()
    }

    private val regionCache = ConcurrentHashMap<Int, CacheResult<RegionData>>()

    private val underwaterCache = ConcurrentHashMap<Int, CacheResult<RegionData>>()

    private val waterOverlayCache = ConcurrentHashMap<Int, Set<Int>>()

    private var underlays: Array<UnderlayDefinition>? = null
    private var overlays: Array<OverlayDefinition>? = null
    private var definitionsLoaded = false

    var enableBlending = true

    var enableEnhancedTessellation = true

    var enableOverlays = true


    var enableShoreline = System.getProperty("darkan.shoreline", "true").toBoolean()

    var enableLighting = true

    var bakeSunLighting = true

    const val GROUND_BASE_LIGHT_STRENGTH = 74

    const val GROUND_BASE_LIGHT_FACTOR = GROUND_BASE_LIGHT_STRENGTH / 128f

    var enableColorNoise = true

    var colorNoiseAmount = 0.03f


    var LIGHT_DIRECTION_X = 0.163f

    var LIGHT_DIRECTION_Y = 0.070f

    var LIGHT_DIRECTION_Z = 1.00f

    var SUN_BRIGHTNESS = 1.2f

    var BRIGHT_FACTOR = 0.865f

    var DARK_FACTOR = 0.814f

    var AMBIENT_LIGHT: Float
        get() = SUN_BRIGHTNESS
        set(value) { SUN_BRIGHTNESS = value }

    var DIFFUSE_STRENGTH: Float
        get() = BRIGHT_FACTOR
        set(value) { BRIGHT_FACTOR = value }

    data class RegionData(
        val regionId: Int,
        val tiles: Array<Array<Array<TileData>>>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RegionData
            return regionId == other.regionId
        }

        override fun hashCode(): Int = regionId
    }

    class NeighborData {
        var east: RegionData? = null
        var north: RegionData? = null
        var northeast: RegionData? = null
        var west: RegionData? = null
        var south: RegionData? = null
        var northwest: RegionData? = null
        var southeast: RegionData? = null
        var southwest: RegionData? = null
    }

    fun buildNeighborData(cache: Cache, rx: Int, ry: Int): NeighborData {
        val start = if (profileSubPhases) System.nanoTime() else 0L
        val nd = buildNeighborDataInternal(cache, rx, ry)
        if (profileSubPhases) RegionLoadProfiler.record("tm:neighbors", System.nanoTime() - start)
        return nd
    }

    private fun buildNeighborDataInternal(cache: Cache, rx: Int, ry: Int): NeighborData {
        val nd = NeighborData()
        if (rx + 1 <= 255) nd.east = loadRegion(cache, rx + 1, ry)
        if (ry + 1 <= 255) nd.north = loadRegion(cache, rx, ry + 1)
        if (rx + 1 <= 255 && ry + 1 <= 255) nd.northeast = loadRegion(cache, rx + 1, ry + 1)
        if (rx - 1 >= 0) nd.west = loadRegion(cache, rx - 1, ry)
        if (ry - 1 >= 0) nd.south = loadRegion(cache, rx, ry - 1)
        if (rx - 1 >= 0 && ry + 1 <= 255) nd.northwest = loadRegion(cache, rx - 1, ry + 1)
        if (rx + 1 <= 255 && ry - 1 >= 0) nd.southeast = loadRegion(cache, rx + 1, ry - 1)
        if (rx - 1 >= 0 && ry - 1 >= 0) nd.southwest = loadRegion(cache, rx - 1, ry - 1)
        return nd
    }

    private fun getExtendedHeight(region: RegionData, neighbors: NeighborData?, plane: Int, x: Int, y: Int): Float {
        if (x in 0..63 && y in 0..63) {
            return region.tiles[plane][x][y].height * HEIGHT_SCALE
        }
        if (neighbors == null) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return region.tiles[plane][cx][cy].height * HEIGHT_SCALE
        }
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63            -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0             -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63            -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            y < 0             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
            else              -> { return region.tiles[plane][x][y].height * HEIGHT_SCALE }
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return region.tiles[plane][cx][cy].height * HEIGHT_SCALE
        }
        return neighborRegion.tiles[plane][nx][ny].height * HEIGHT_SCALE
    }

    private fun getExtendedHeightRaw(tiles: Array<Array<Array<TileData>>>, neighbors: NeighborData?, neighborPlane: Int, x: Int, y: Int): Float {
        if (x in 0..63 && y in 0..63) {
            return tiles[neighborPlane][x][y].height * HEIGHT_SCALE
        }
        if (neighbors == null) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return tiles[neighborPlane][cx][cy].height * HEIGHT_SCALE
        }
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63            -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0             -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63            -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            y < 0             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
            else              -> { return tiles[neighborPlane][x][y].height * HEIGHT_SCALE }
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return tiles[neighborPlane][cx][cy].height * HEIGHT_SCALE
        }
        return neighborRegion.tiles[neighborPlane][nx][ny].height * HEIGHT_SCALE
    }

    fun getExtendedTileHeight(
        tiles: Array<Array<Array<TileData>>>,
        neighbors: NeighborData?,
        plane: Int,
        x: Int,
        y: Int
    ): Int {
        val p = plane.coerceIn(0, 3)
        if (x in 0..63 && y in 0..63) {
            return tiles[p][x][y].height
        }
        if (neighbors == null) {
            return tiles[p][x.coerceIn(0, 63)][y.coerceIn(0, 63)].height
        }
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63           -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0            -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63           -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            else             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) {
            return tiles[p][x.coerceIn(0, 63)][y.coerceIn(0, 63)].height
        }
        return neighborRegion.tiles[p][nx][ny].height
    }

    fun getExtendedTileHeightScaled(
        tiles: Array<Array<Array<TileData>>>,
        neighbors: NeighborData?,
        plane: Int,
        x: Int,
        y: Int
    ): Float = getExtendedTileHeight(tiles, neighbors, plane, x, y) * HEIGHT_SCALE

    private fun getExtendedUnderlayId(region: RegionData, neighbors: NeighborData?, plane: Int, x: Int, y: Int): Int {
        if (x in 0..63 && y in 0..63) {
            return region.tiles[plane][x][y].underlayId
        }
        if (neighbors == null) return 0
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63            -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0             -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63            -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            y < 0             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
            else              -> return region.tiles[plane][x][y].underlayId
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) return 0
        return neighborRegion.tiles[plane][nx][ny].underlayId
    }

    object TileFlags {
        const val BLOCK_WALK = 0x1

        const val BRIDGE = 0x2

        const val FORCE_COLLISION_PLANE_0 = 0x8

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
        fun hasFlag(flag: Int): Boolean = (flags and flag) == flag

        val isBridge: Boolean get() = hasFlag(TileFlags.BRIDGE)

        val blocksWalk: Boolean get() = hasFlag(TileFlags.BLOCK_WALK)

        val isInvisible: Boolean get() = hasFlag(TileFlags.INVISIBLE)

        val forcesCollisionPlane0: Boolean get() = hasFlag(TileFlags.FORCE_COLLISION_PLANE_0)
    }

    fun getCollisionPlane(tiles: Array<Array<Array<TileData>>>, z: Int, x: Int, y: Int): Int {
        if (tiles[z][x][y].forcesCollisionPlane0) {
            return 0
        }
        if (z > 0 && tiles[1][x][y].isBridge) {
            return z - 1
        }
        return z
    }

    fun isTileVisible(tiles: Array<Array<Array<TileData>>>, viewingPlane: Int, z: Int, x: Int, y: Int): Boolean {
        if (tiles[0][x][y].isBridge) {
            return true
        }
        return !tiles[z][x][y].isInvisible && getCollisionPlane(tiles, z, x, y) == viewingPlane
    }

    fun isBridgeTile(tiles: Array<Array<Array<TileData>>>, x: Int, y: Int): Boolean {
        return tiles[1][x][y].isBridge
    }

    fun getHeightPlane(tiles: Array<Array<Array<TileData>>>, plane: Int, x: Int, y: Int): Int {
        if (plane < 3 && isBridgeTile(tiles, x, y)) {
            return plane + 1
        }
        return plane
    }

    fun isBridgeLinkedPlane(tiles: Array<Array<Array<TileData>>>, sourcePlane: Int, targetPlane: Int, x: Int, y: Int): Boolean {
        if (!isBridgeTile(tiles, x, y)) {
            return false
        }
        return sourcePlane > 0 && sourcePlane - 1 == targetPlane
    }

    fun getTileHeight(tiles: Array<Array<Array<TileData>>>, localX: Int, localY: Int, plane: Int): Int {
        val x = localX.coerceIn(0, 63)
        val y = localY.coerceIn(0, 63)
        val p = plane.coerceIn(0, 3)
        return tiles[p][x][y].height
    }

    fun getTileHeightScaled(tiles: Array<Array<Array<TileData>>>, localX: Int, localY: Int, plane: Int): Float {
        return getTileHeight(tiles, localX, localY, plane) * HEIGHT_SCALE
    }

    fun getHeightScale(): Float = HEIGHT_SCALE

    data class TerrainBatch(
        val mesh: Mesh,
        val textureId: Int,
        val inAtlas: Boolean = false
    )

    data class AlphaTerrainBatch(
        val mesh: Mesh,
        val textureId: Int,
        val inAtlas: Boolean = false
    )

    data class TerrainMeshes(
        val colorMesh: Mesh?,
        val texturedBatches: List<TerrainBatch>,
        val alphaBatches: List<AlphaTerrainBatch> = emptyList(),
        val colorInAtlas: Boolean = false
    ) {
        fun cleanup() {
            colorMesh?.cleanup()
            texturedBatches.forEach { it.mesh.cleanup() }
            alphaBatches.forEach { it.mesh.cleanup() }
        }
    }


    var atlasLookup: ((textureId: Int) -> FloatArray?)? = null

    var sentinelAtlasTransform: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)


    data class ColorMeshArrays(
        val positions: FloatArray,
        val colors: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val texCoords: FloatArray? = null,
        val atlasTransforms: FloatArray? = null
    )

    data class TexturedMeshArrays(
        val positions: FloatArray,
        val colors: FloatArray,
        val texCoords: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val textureId: Int,
        val atlasTransforms: FloatArray? = null
    )

    data class AlphaMeshArrays(
        val positions: FloatArray,
        val colors: FloatArray,
        val texCoords: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val alphas: FloatArray,
        val textureId: Int,
        val atlasTransforms: FloatArray? = null
    )

    data class TerrainMeshData(
        val colorArrays: ColorMeshArrays?,
        val texturedArrays: List<TexturedMeshArrays>,
        val alphaArrays: List<AlphaMeshArrays>
    )

    private val batchedMeshCache = ConcurrentHashMap<Int, CacheResult<TerrainMeshes>>()


    internal val profileSubPhases: Boolean = System.getProperty("darkan.profileTerrain") == "true"

    private val tileSubPhaseNanos: ThreadLocal<LongArray> = ThreadLocal.withInitial { LongArray(3) }

    @Volatile
    internal var fastTessellation: Boolean = System.getProperty("darkan.fastTerrain", "true").toBoolean()

    fun loadDefinitions(cache: Cache) {
        if (definitionsLoaded) return

        underlays = UnderlayDecoder().load(cache)
        overlays = OverlayDecoder().load(cache)
        definitionsLoaded = true
    }

    internal fun computeBlendedUnderlayColors(region: RegionData, plane: Int, neighbors: NeighborData? = null): Array<IntArray> {
        val blendedColors = Array(65) { IntArray(65) }
        val underlayDefs = underlays ?: return blendedColors

        fun underlayAt(tx: Int, ty: Int): Int = getExtendedUnderlayId(region, neighbors, plane, tx, ty)

        if (!enableBlending) {
            for (x in 0 until 65) {
                for (y in 0 until 65) {
                    val underlayId = underlayAt(x, y)
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

        var nonZeroCount = 0

        val extYSize = 75
        val underlayHue = IntArray(extYSize)
        val underlaySaturation = IntArray(extYSize)
        val underlayLightness = IntArray(extYSize)
        val underlayDivisor = IntArray(extYSize)
        val underlayCount = IntArray(extYSize)

        for (x in -10..64) {
            for (tileY in -5..69) {
                val accIdx = tileY + 5

                val forwardX = x + 5
                if (forwardX in -5..69) {
                    val fwdUnderlayId = underlayAt(forwardX, tileY)
                    if (fwdUnderlayId > 0) {
                        val def = underlayDefs.getOrNull(fwdUnderlayId - 1)
                        if (def != null) {
                            underlayHue[accIdx] += def.hue
                            underlaySaturation[accIdx] += def.saturation
                            underlayLightness[accIdx] += def.lightness
                            underlayDivisor[accIdx] += def.chroma
                            underlayCount[accIdx]++
                        }
                    }
                }

                val backwardX = x - 5
                if (backwardX in -5..69) {
                    val bkwdUnderlayId = underlayAt(backwardX, tileY)
                    if (bkwdUnderlayId > 0) {
                        val def = underlayDefs.getOrNull(bkwdUnderlayId - 1)
                        if (def != null) {
                            underlayHue[accIdx] -= def.hue
                            underlaySaturation[accIdx] -= def.saturation
                            underlayLightness[accIdx] -= def.lightness
                            underlayDivisor[accIdx] -= def.chroma
                            underlayCount[accIdx]--
                        }
                    }
                }
            }

            if (x >= 0) {
                var hueSum = 0
                var saturationSum = 0
                var lightnessSum = 0
                var divisorSum = 0
                var depth = 0

                for (y in -10..64) {
                    val forwardY = y + 5
                    if (forwardY in -5..69) {
                        val accIdx = forwardY + 5
                        hueSum += underlayHue[accIdx]
                        saturationSum += underlaySaturation[accIdx]
                        lightnessSum += underlayLightness[accIdx]
                        divisorSum += underlayDivisor[accIdx]
                        depth += underlayCount[accIdx]
                    }

                    val backwardY = y - 5
                    if (backwardY in -5..69) {
                        val accIdx = backwardY + 5
                        hueSum -= underlayHue[accIdx]
                        saturationSum -= underlaySaturation[accIdx]
                        lightnessSum -= underlayLightness[accIdx]
                        divisorSum -= underlayDivisor[accIdx]
                        depth -= underlayCount[accIdx]
                    }

                    if (y >= 0 && divisorSum > 0 && depth > 0) {
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

    private fun getBlendedUnderlayColorHsl(blendedColors: Array<IntArray>, x: Int, y: Int): Int {
        if (x !in 0..64 || y !in 0..64) return 0
        return blendedColors[x][y]
    }

    private fun getCornerHslColors(blendedColors: Array<IntArray>, x: Int, y: Int): IntArray {
        val swHsl = getBlendedUnderlayColorHsl(blendedColors, x, y)
        val seHsl = getBlendedUnderlayColorHsl(blendedColors, x + 1, y)
        val nwHsl = getBlendedUnderlayColorHsl(blendedColors, x, y + 1)
        val neHsl = getBlendedUnderlayColorHsl(blendedColors, x + 1, y + 1)

        return intArrayOf(swHsl, seHsl, nwHsl, neHsl)
    }

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
        val cached = regionCache[regionId]
        if (cached != null) return (cached as? CacheResult.Found)?.value

        val decodeStart = if (profileSubPhases) System.nanoTime() else 0L
        val data = decodeRegion(cache, regionX, regionY) ?: return null
        if (profileSubPhases) RegionLoadProfiler.record("tm:decode", System.nanoTime() - decodeStart)
        regionCache[regionId] = CacheResult.Found(data)
        underwaterCache.computeIfAbsent(regionId) {
            val bed = decodeUnderwaterRegion(cache, regionX, regionY, data)
            if (bed == null) CacheResult.NotFound else CacheResult.Found(bed)
        }
        return data
    }

    internal fun underwaterFor(regionId: Int): RegionData? =
        (underwaterCache[regionId] as? CacheResult.Found)?.value

    internal fun waterOverlayIdsFor(regionId: Int): Set<Int> =
        waterOverlayCache[regionId] ?: emptySet()

    fun loadUnderwaterRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY
        val cached = underwaterCache[regionId]
        if (cached != null) return (cached as? CacheResult.Found)?.value

        val surface = loadRegion(cache, regionX, regionY)
        val data = if (surface == null) null else decodeUnderwaterRegion(cache, regionX, regionY, surface)
        underwaterCache[regionId] = if (data == null) CacheResult.NotFound else CacheResult.Found(data)
        return data
    }

    fun loadRegionMeshes(cache: Cache, regionX: Int, regionY: Int, plane: Int = 0, neighbors: NeighborData? = null): TerrainMeshes? {
        loadDefinitions(cache)

        val regionId = (regionX shl 8) or regionY
        val cacheKey = regionId * 4 + plane

        if (neighbors == null) {
            val cached = batchedMeshCache[cacheKey]
            if (cached is CacheResult.Found) {
                return cached.value
            }
        }

        println("[TerrainLoader] Generating meshes for region ($regionX, $regionY) plane $plane - enableOverlays=$enableOverlays")

        val region = loadRegion(cache, regionX, regionY) ?: return null
        val meshes = createBatchedTerrainMeshes(region, plane, neighbors)
        if (neighbors == null) {
            batchedMeshCache[cacheKey] = CacheResult.Found(meshes)
        }
        return meshes
    }

    internal fun computeVertexNormals(tiles: Array<Array<Array<TileData>>>, plane: Int, neighbors: NeighborData? = null): Triple<Array<FloatArray>, Array<FloatArray>, Array<FloatArray>> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val normalsX = Array(65) { FloatArray(65) }
        val normalsY = Array(65) { FloatArray(65) }
        val normalsZ = Array(65) { FloatArray(65) }

        for (vx in 0 until 65) {
            for (vy in 0 until 65) {
                val heightWest  = getExtendedHeightRaw(tiles, neighbors, plane, vx - 1, vy)
                val heightEast  = getExtendedHeightRaw(tiles, neighbors, plane, vx + 1, vy)
                val heightSouth = getExtendedHeightRaw(tiles, neighbors, plane, vx, vy - 1)
                val heightNorth = getExtendedHeightRaw(tiles, neighbors, plane, vx, vy + 1)

                val heightDeltaX = heightEast - heightWest
                val heightDeltaY = heightNorth - heightSouth

                val nx = heightDeltaX
                val ny = tileSize * 2f
                val nz = heightDeltaY

                val len = sqrt(nx * nx + ny * ny + nz * nz)
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

    private fun cornerWaterDepths(region: RegionData, plane: Int, x: Int, y: Int): FloatArray {
        val bed = underwaterFor(region.regionId) ?: return NO_WATER_DEPTH
        val bedTiles = bed.tiles[plane]
        val surfaceTiles = region.tiles[plane]
        val x1 = if (x < 63) x + 1 else 63
        val y1 = if (y < 63) y + 1 else 63
        val depths = floatArrayOf(
            (surfaceTiles[x][y].height - bedTiles[x][y].height).toFloat(),
            (surfaceTiles[x1][y].height - bedTiles[x1][y].height).toFloat(),
            (surfaceTiles[x][y1].height - bedTiles[x][y1].height).toFloat(),
            (surfaceTiles[x1][y1].height - bedTiles[x1][y1].height).toFloat()
        )
        for (i in 0 until 4) if (depths[i] < 0f) depths[i] = 0f
        return depths
    }

    private val NO_WATER_DEPTH = FloatArray(4)

    private const val WATER_SURFACE_OPACITY = 155f / 255f

    private const val SHORE_FOG_SPREAD = 4f

    internal fun generateTileVertices(
        region: RegionData, plane: Int,
        x: Int, y: Int,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: NeighborData? = null
    ): List<ShapedVertex> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val tile = region.tiles[plane][x][y]

        if (tile.overlayId == 0 && tile.underlayId == 0) return emptyList()

        val profile = profileSubPhases
        val subPhase = if (profile) tileSubPhaseNanos.get() else null
        var mark = if (profile) System.nanoTime() else 0L
        val blendState = analyzeTileBlending(region, plane, x, y, blendedColors)
        if (subPhase != null) {
            val now = System.nanoTime()
            subPhase[0] += now - mark
            mark = now
        }

        val heights = floatArrayOf(
            getExtendedHeight(region, neighbors, plane, x, y),
            getExtendedHeight(region, neighbors, plane, x + 1, y),
            getExtendedHeight(region, neighbors, plane, x, y + 1),
            getExtendedHeight(region, neighbors, plane, x + 1, y + 1)
        )

        val cornerDepths = cornerWaterDepths(region, plane, x, y)

        val overlayDef = if (tile.overlayId > 0) getOverlayDefinition(tile.overlayId) else null
        val underlayDef = if (tile.underlayId > 0) getUnderlayDefinition(tile.underlayId) else null

        val underlayCornerHsl = getCornerHslColors(blendedColors, x, y)
        val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else Vector3f(0f, 0f, 0f)

        val overlayTextureId = overlayDef?.texture ?: -1
        val underlayTextureId = underlayDef?.texture ?: -1

        val overlayScaleRaw = if (overlayDef != null && overlayDef.texture >= 0 && overlayDef.scale > 0) {
            overlayDef.scale
        } else {
            512
        }
        val underlayScaleRaw = if (underlayDef != null && underlayDef.texture >= 0 && underlayDef.scale > 0) {
            underlayDef.scale
        } else {
            512
        }

        val cornerUnderlayTextures: IntArray
        val cornerUnderlayScalesRaw: IntArray
        if (underlayDef != null) {
            val swUnderlayId = tile.underlayId
            val seUnderlayId = getExtendedUnderlayId(region, neighbors, plane, x + 1, y).let { if (it == 0) swUnderlayId else it }
            val nwUnderlayId = getExtendedUnderlayId(region, neighbors, plane, x, y + 1).let { if (it == 0) swUnderlayId else it }
            val neUnderlayId = getExtendedUnderlayId(region, neighbors, plane, x + 1, y + 1).let { if (it == 0) swUnderlayId else it }

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
            cornerUnderlayScalesRaw = intArrayOf(
                if (swDef != null && swDef.texture >= 0 && swDef.scale > 0) swDef.scale else 512,
                if (seDef != null && seDef.texture >= 0 && seDef.scale > 0) seDef.scale else 512,
                if (nwDef != null && nwDef.texture >= 0 && nwDef.scale > 0) nwDef.scale else 512,
                if (neDef != null && neDef.texture >= 0 && neDef.scale > 0) neDef.scale else 512
            )
        } else {
            cornerUnderlayTextures = intArrayOf(-1, -1, -1, -1)
            cornerUnderlayScalesRaw = intArrayOf(512, 512, 512, 512)
        }

        val finalOverlayColor = if (overlayTextureId >= 0) {
            if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
                ColorUtil.hslToRgb(overlayDef.colour)
            } else {
                Vector3f(1f, 1f, 1f)
            }
        } else {
            overlayColor
        }

        val overlayHsl = if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
            overlayDef.colour
        } else {
            0
        }

        val effectiveShape = blendState?.effectiveShape ?: run {
            var s = tile.overlayShape
            if (s == 0 && tile.overlayId == 0) s = 12
            s
        }
        val effectiveRotation = blendState?.effectiveRotation ?: tile.overlayRotation

        val cornerNormals = arrayOf(
            Vector3f(normalsX[x][y], normalsY[x][y], normalsZ[x][y]),
            Vector3f(normalsX[x + 1][y], normalsY[x + 1][y], normalsZ[x + 1][y]),
            Vector3f(normalsX[x][y + 1], normalsY[x][y + 1], normalsZ[x][y + 1]),
            Vector3f(normalsX[x + 1][y + 1], normalsY[x + 1][y + 1], normalsZ[x + 1][y + 1])
        )

        val hasValidOverlay = if (overlayDef != null) {
            overlayDef.texture >= 0 || !(overlayDef.colour == -1 && overlayDef.blendColour == -1)
        } else false
        val hasOverlay = tile.overlayId > 0 && hasValidOverlay
        val hasValidUnderlayColors = underlayCornerHsl.all { it != 0 }
        val hasUnderlay = tile.underlayId > 0 && underlayDef != null && hasValidUnderlayColors

        if (subPhase != null) {
            val now = System.nanoTime()
            subPhase[2] += now - mark
            mark = now
        }

        val shaped = generateShapedTile(
            x, y,
            effectiveShape, effectiveRotation,
            tileSize,
            heights,
            finalOverlayColor,
            underlayCornerHsl,
            overlayTextureId,
            underlayTextureId,
            overlayScaleRaw,
            underlayScaleRaw,
            overlayDef?.underlayOverrides ?: false,
            overlayHsl,
            cornerNormals,
            hasOverlay = hasOverlay,
            hasUnderlay = hasUnderlay,
            regionId = region.regionId,
            blendState = blendState,
            cornerUnderlayTextures = cornerUnderlayTextures,
            cornerUnderlayScalesRaw = cornerUnderlayScalesRaw,
            cornerDepths = cornerDepths,
            waterFogDepth = overlayDef?.waterScale ?: 0,
            isWater = tile.overlayId > 0 &&
                waterOverlayCache[region.regionId]?.contains(tile.overlayId) == true,
            waterColor = packedRgbToVector(overlayDef?.waterColour ?: 0)
        )
        if (subPhase != null) subPhase[1] += System.nanoTime() - mark
        return shaped
    }

    private fun packedRgbToVector(packed: Int): Vector3f = Vector3f(
        ((packed shr 16) and 0xFF) / 255f,
        ((packed shr 8) and 0xFF) / 255f,
        (packed and 0xFF) / 255f
    )

    private fun createBatchedTerrainMeshes(region: RegionData, plane: Int, neighbors: NeighborData? = null): TerrainMeshes {
        val meshData = computeTerrainMeshData(region, plane, neighbors)
        return uploadTerrainMeshData(meshData)
    }

    fun computeTerrainMeshData(region: RegionData, plane: Int, neighbors: NeighborData? = null): TerrainMeshData {
        if (!profileSubPhases) return computeTerrainMeshDataInternal(region, plane, neighbors)

        val threads = threadMxBean
        val cpuStart = if (threads != null && threads.isCurrentThreadCpuTimeSupported) {
            threads.currentThreadCpuTime
        } else -1L
        val result = computeTerrainMeshDataInternal(region, plane, neighbors)
        if (cpuStart >= 0 && threads != null) {
            RegionLoadProfiler.record("tm:threadCpu", threads.currentThreadCpuTime - cpuStart)
        }
        return result
    }

    private val threadMxBean: ThreadMXBean? = try {
        ManagementFactory.getThreadMXBean()
    } catch (e: Exception) {
        null
    }

    private fun computeTerrainMeshDataInternal(region: RegionData, plane: Int, neighbors: NeighborData? = null): TerrainMeshData {
        val profile = profileSubPhases
        val subPhase = if (profile) tileSubPhaseNanos.get().also { it.fill(0L) } else null
        var mark = if (profile) System.nanoTime() else 0L

        val (vertexNormalsX, vertexNormalsY, vertexNormalsZ) = computeVertexNormals(region.tiles, plane, neighbors)
        if (profile) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:normals", now - mark)
            mark = now
        }

        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane, neighbors)
        if (profile) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:blend", now - mark)
            mark = now
        }

        val textureGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        val groupMemo = fastTessellation
        var lastTexId = Int.MIN_VALUE
        var lastGroup: MutableList<ShapedVertex>? = null
        for (x in 0 until 64) {
            for (y in 0 until 64) {
                val vertices = generateTileVertices(
                    region, plane,
                    x, y,
                    blendedUnderlayColors,
                    vertexNormalsX,
                    vertexNormalsY,
                    vertexNormalsZ,
                    neighbors
                )
                for (vertex in vertices) {
                    val texId = vertex.textureId
                    var group = lastGroup
                    if (!groupMemo || group == null || texId != lastTexId) {
                        group = textureGroups.getOrPut(texId) { mutableListOf() }
                        lastGroup = group
                        lastTexId = texId
                    }
                    group.add(vertex)
                }
            }
        }
        if (profile && subPhase != null) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:tilesTotal", now - mark)
            RegionLoadProfiler.record("tm:tileAnalyze", subPhase[0])
            RegionLoadProfiler.record("tm:tileShape", subPhase[1])
            RegionLoadProfiler.record("tm:tilePrep", subPhase[2])
            mark = now
        }

        val opaqueGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()
        val alphaGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        for ((texId, verts) in textureGroups) {
            var i = 0
            while (i + 2 < verts.size) {
                val v0 = verts[i]
                val v1 = verts[i + 1]
                val v2 = verts[i + 2]

                val needsAlpha = v0.alpha < 1f || v1.alpha < 1f || v2.alpha < 1f

                if (needsAlpha && v0.textureId >= 0) {
                    val group = alphaGroups.getOrPut(v0.textureId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                } else {
                    val group = opaqueGroups.getOrPut(texId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                }
                i += 3
            }
            while (i < verts.size) {
                opaqueGroups.getOrPut(texId) { mutableListOf() }.add(verts[i])
                i++
            }
        }
        if (profile) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:split", now - mark)
            mark = now
        }

        val currentAtlasLookup = atlasLookup
        val currentSentinelTransform = sentinelAtlasTransform

        val colorArrays = opaqueGroups[-1]?.let { vertices ->
            if (vertices.isEmpty()) return@let null

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val normals = FloatArray(vertices.size * 3)
            val indices = IntArray(vertices.size)
            val texCoords = if (currentAtlasLookup != null) FloatArray(vertices.size * 2) else null
            val atlasTransforms = if (currentAtlasLookup != null) FloatArray(vertices.size * 4) else null

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
                if (texCoords != null) {
                    texCoords[i * 2] = 0f
                    texCoords[i * 2 + 1] = 0f
                }
                if (atlasTransforms != null) {
                    atlasTransforms[i * 4] = currentSentinelTransform[0]
                    atlasTransforms[i * 4 + 1] = currentSentinelTransform[1]
                    atlasTransforms[i * 4 + 2] = currentSentinelTransform[2]
                    atlasTransforms[i * 4 + 3] = currentSentinelTransform[3]
                }
            }

            ColorMeshArrays(positions, colors, normals, indices, texCoords, atlasTransforms)
        }

        val texturedArrays = mutableListOf<TexturedMeshArrays>()
        for ((texId, vertices) in opaqueGroups) {
            if (texId == -1) continue
            if (vertices.isEmpty()) continue

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val texCoords = FloatArray(vertices.size * 2)
            val normals = FloatArray(vertices.size * 3)
            val indices = IntArray(vertices.size)
            val batchAtlasTransform = currentAtlasLookup?.invoke(texId)
            val atlasTransforms = if (batchAtlasTransform != null) FloatArray(vertices.size * 4) else null

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
                if (atlasTransforms != null && batchAtlasTransform != null) {
                    atlasTransforms[i * 4] = batchAtlasTransform[0]
                    atlasTransforms[i * 4 + 1] = batchAtlasTransform[1]
                    atlasTransforms[i * 4 + 2] = batchAtlasTransform[2]
                    atlasTransforms[i * 4 + 3] = batchAtlasTransform[3]
                }
            }

            texturedArrays.add(TexturedMeshArrays(positions, colors, texCoords, normals, indices, texId, atlasTransforms))
        }

        val alphaArrays = mutableListOf<AlphaMeshArrays>()
        for ((texId, vertices) in alphaGroups) {
            if (vertices.isEmpty()) continue

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val texCoords = FloatArray(vertices.size * 2)
            val normals = FloatArray(vertices.size * 3)
            val alphas = FloatArray(vertices.size)
            val indices = IntArray(vertices.size)
            val batchAtlasTransform = currentAtlasLookup?.invoke(texId)
            val atlasTransforms = if (batchAtlasTransform != null) FloatArray(vertices.size * 4) else null

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
                if (atlasTransforms != null && batchAtlasTransform != null) {
                    atlasTransforms[i * 4] = batchAtlasTransform[0]
                    atlasTransforms[i * 4 + 1] = batchAtlasTransform[1]
                    atlasTransforms[i * 4 + 2] = batchAtlasTransform[2]
                    atlasTransforms[i * 4 + 3] = batchAtlasTransform[3]
                }
            }

            alphaArrays.add(AlphaMeshArrays(positions, colors, texCoords, normals, indices, alphas, texId, atlasTransforms))
        }
        if (profile) RegionLoadProfiler.record("tm:pack", System.nanoTime() - mark)

        if (texturedArrays.isNotEmpty()) {
            val textureList = texturedArrays.map { it.textureId }.sorted().joinToString(", ")
            println("[TerrainLoader] Region ${region.regionId} plane $plane: textured batches with textures [$textureList]")
        }
        if (alphaArrays.isNotEmpty()) {
            val alphaList = alphaArrays.map { it.textureId }.sorted().joinToString(", ")
            println("[TerrainLoader] Region ${region.regionId} plane $plane: alpha batches with textures [$alphaList]")
        }

        return TerrainMeshData(colorArrays, texturedArrays, alphaArrays)
    }

    fun uploadTerrainMeshData(data: TerrainMeshData): TerrainMeshes {
        val colorMesh = data.colorArrays?.let { arrays ->
            if (arrays.atlasTransforms != null && arrays.texCoords != null) {
                Mesh.createWithAtlasTransform(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.atlasTransforms, arrays.indices
                )
            } else {
                Mesh(arrays.positions, arrays.colors, null, arrays.normals, arrays.indices, colorComponents = 3)
            }
        }

        val texturedBatches = data.texturedArrays.map { arrays ->
            val mesh = if (arrays.atlasTransforms != null) {
                Mesh.createWithAtlasTransform(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.atlasTransforms, arrays.indices
                )
            } else {
                Mesh(arrays.positions, arrays.colors, arrays.texCoords, arrays.normals, arrays.indices, colorComponents = 3)
            }
            TerrainBatch(mesh, arrays.textureId, inAtlas = arrays.atlasTransforms != null)
        }

        val alphaBatches = data.alphaArrays.map { arrays ->
            val mesh = if (arrays.atlasTransforms != null) {
                Mesh.createWithAtlasAndAlpha(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.alphas, arrays.atlasTransforms, arrays.indices
                )
            } else {
                Mesh.createWithAlpha(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.alphas, arrays.indices
                )
            }
            AlphaTerrainBatch(mesh, arrays.textureId, inAtlas = arrays.atlasTransforms != null)
        }

        val colorInAtlas = data.colorArrays?.let { it.atlasTransforms != null && it.texCoords != null } ?: false
        return TerrainMeshes(colorMesh, texturedBatches, alphaBatches, colorInAtlas)
    }

    private data class RawTileData(
        val heightByte: Int,
        val hasExplicitHeight: Boolean,
        val overlayId: Int,
        val overlayShape: Int,
        val overlayRotation: Int,
        val underlayId: Int,
        val flags: Int
    )

    private fun decodeRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val mapArchive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (mapArchive == -1) return null

        val data = cache.data(Index.MAPS, mapArchive) ?: return null
        val buffer = BufferReader(data)

        val rawTiles = Array(4) { Array(64) { Array(64) { RawTileData(0, false, 0, 0, 0, 0, 0) } } }

        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    rawTiles[plane][x][y] = decodeRawTile(buffer)
                }
            }
        }

        val tiles = Array(4) { Array(64) { Array(64) { TileData(0, 0, 0, 0, 0, 0) } } }

        val baseAbsX = regionX * 64
        val baseAbsY = regionY * 64

        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val raw = rawTiles[plane][x][y]
                    val absX = baseAbsX + x
                    val absY = baseAbsY + y

                    val height: Int = if (plane == 0) {
                        if (!raw.hasExplicitHeight) {
                            TileUtils.calculateTileheight(
                                absX + TileUtils.MAGIC_X_OFFSET,
                                absY + TileUtils.MAGIC_Y_OFFSET
                            ) * TileUtils.HEIGHT_MULTIPLIER
                        } else {
                            raw.heightByte * TileUtils.HEIGHT_MULTIPLIER
                        }
                    } else {
                        val prevHeight = tiles[plane - 1][x][y].height

                        if (!raw.hasExplicitHeight) {
                            prevHeight + TileUtils.DEFAULT_PLANE_HEIGHT_OFFSET
                        } else {
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

    private fun decodeUnderwaterRegion(
        cache: Cache,
        regionX: Int,
        regionY: Int,
        surface: RegionData
    ): RegionData? {
        val mapArchive = cache.archiveId(Index.MAPS, "um${regionX}_$regionY")
        if (mapArchive == -1) return null

        val data = cache.data(Index.MAPS, mapArchive) ?: return null
        val buffer = BufferReader(data)

        val tiles = Array(4) { plane ->
            Array(64) { x ->
                Array(64) { y -> TileData(surface.tiles[plane][x][y].height, 0, 0, 0, 0, 0) }
            }
        }
        decode@ for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val raw = try {
                        decodeRawTile(buffer)
                    } catch (e: Exception) {
                        break@decode
                    }
                    val surfaceHeight = surface.tiles[plane][x][y].height
                    val height = if (raw.hasExplicitHeight) {
                        surfaceHeight - raw.heightByte * TileUtils.HEIGHT_MULTIPLIER
                    } else {
                        surfaceHeight
                    }
                    tiles[plane][x][y] = TileData(
                        height, raw.overlayId, raw.overlayShape,
                        raw.overlayRotation, raw.underlayId, raw.flags
                    )
                }
            }
        }
        val result = RegionData((regionX shl 8) or regionY, tiles)
        waterOverlayCache[result.regionId] = waterOverlayIdsOf(surface, result)
        return result
    }

    private fun waterOverlayIdsOf(surface: RegionData, bed: RegionData): Set<Int> {
        val ids = HashSet<Int>()
        for (plane in 0 until 4) {
            val s = surface.tiles[plane]
            val b = bed.tiles[plane]
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val overlayId = s[x][y].overlayId
                    if (overlayId > 0 && s[x][y].height > b[x][y].height) ids.add(overlayId)
                }
            }
        }
        return ids
    }

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
                    hasExplicitHeight = false
                    break
                }
                opcode == 1 -> {
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

    fun getUnderlayColor(underlayId: Int): Vector3f {
        if (underlayId <= 0) return DEFAULT_GRASS_COLOR

        val defs = underlays
        val defIndex = underlayId - 1
        if (defs != null && defIndex >= 0 && defIndex < defs.size) {
            val def = defs[defIndex]

            if (def.chroma > 0) {
                val hue = def.hue * 256 / def.chroma
                val packedHsl = ColorUtil.packHsl16(hue, def.saturation, def.lightness)
                return ColorUtil.hslToRgb(packedHsl)
            }

            if (def.colour != 0) {
                return ColorUtil.rgbToVector(def.colour)
            }
        }

        return when (underlayId) {
            1 -> Vector3f(0.6f, 0.5f, 0.3f)
            2 -> Vector3f(0.3f, 0.3f, 0.35f)
            3 -> Vector3f(0.2f, 0.3f, 0.6f)
            4 -> Vector3f(0.9f, 0.85f, 0.7f)
            else -> DEFAULT_GRASS_COLOR
        }
    }

    fun getOverlayColor(overlayId: Int): Vector3f {
        if (overlayId <= 0) return DEFAULT_GRASS_COLOR

        val defs = overlays
        val defIndex = overlayId - 1
        if (defs != null && defIndex >= 0 && defIndex < defs.size) {
            val def = defs[defIndex]
            val color = def.colour
            if (color != 0 && color != -1) {
                return ColorUtil.hslToRgb(color)
            }
        }

        return DEFAULT_GRASS_COLOR
    }

    fun getUnderlayDefinition(underlayId: Int): UnderlayDefinition? {
        val defs = underlays ?: return null
        val defIndex = underlayId - 1
        return if (defIndex >= 0 && defIndex < defs.size) defs[defIndex] else null
    }

    fun getOverlayDefinition(overlayId: Int): OverlayDefinition? {
        val defs = overlays ?: return null
        val defIndex = overlayId - 1
        return if (defIndex >= 0 && defIndex < defs.size) defs[defIndex] else null
    }

    fun getUniqueTextureIds(): Set<Int> {
        val textureIds = mutableSetOf<Int>()
        overlays?.forEach { def ->
            if (def.texture >= 0) textureIds.add(def.texture)
        }
        underlays?.forEach { def ->
            if (def.texture >= 0) textureIds.add(def.texture)
        }
        return textureIds
    }

    fun clearCache() {
        println("[TerrainLoader] clearCache() called - clearing ${batchedMeshCache.size} cached meshes")
        batchedMeshCache.values.forEach { (it as? CacheResult.Found)?.value?.cleanup() }
        batchedMeshCache.clear()
        regionCache.clear()
    }

    fun clearBatchedMeshCache() {
        batchedMeshCache.values.forEach { (it as? CacheResult.Found)?.value?.cleanup() }
        batchedMeshCache.clear()
    }

    fun clearAll() {
        clearCache()
        underlays = null
        overlays = null
        definitionsLoaded = false
    }

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

    private const val HEIGHT_SCALE = CoordinateUtil.DEFAULT_SCALE


    private val TILE_VERTEX_X = intArrayOf(0, 256, 512, 512, 512, 256, 0, 0, 128, 256, 128, 384, 256)

    private val TILE_VERTEX_Y = intArrayOf(0, 0, 0, 256, 512, 512, 512, 256, 256, 384, 128, 128, 256)

    private val OVERLAY_FACE_COUNT = intArrayOf(2, 1, 1, 1, 2, 2, 2, 1, 3, 3, 3, 2, 0, 4, 0)

    private val UNDERLAY_FACE_COUNT = intArrayOf(0, 1, 2, 2, 1, 1, 2, 3, 1, 3, 3, 4, 2, 0, 4)

    private val OVERLAY_FACE_COUNT_ENHANCED = intArrayOf(4, 1, 1, 1, 2, 2, 2, 1, 3, 3, 3, 2, 0, 4, 0)

    private val UNDERLAY_FACE_COUNT_ENHANCED = intArrayOf(0, 1, 2, 2, 1, 1, 2, 3, 1, 3, 3, 4, 4, 0, 4)

    private val TRIANGLE_A = arrayOf(
        intArrayOf(0, 2),
        intArrayOf(0, 2),
        intArrayOf(0, 0, 2),
        intArrayOf(2, 0, 0),
        intArrayOf(0, 2, 0),
        intArrayOf(0, 0, 2),
        intArrayOf(0, 5, 1, 4),
        intArrayOf(0, 4, 4, 4),
        intArrayOf(4, 4, 4, 0),
        intArrayOf(6, 6, 6, 2, 2, 2),
        intArrayOf(2, 2, 2, 6, 6, 6),
        intArrayOf(0, 11, 6, 6, 6, 4),
        intArrayOf(0, 2),
        intArrayOf(0, 4, 4, 4),
        intArrayOf(0, 4, 4, 4)
    )

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

    private val TRIANGLE_A_ENHANCED = arrayOf(
        intArrayOf(0, 2, 4, 6),
        intArrayOf(0, 2),
        intArrayOf(0, 0, 2),
        intArrayOf(2, 0, 0),
        intArrayOf(0, 2, 0),
        intArrayOf(0, 0, 2),
        intArrayOf(0, 5, 1, 4),
        intArrayOf(0, 4, 4, 4),
        intArrayOf(4, 4, 4, 0),
        intArrayOf(6, 6, 6, 2, 2, 2),
        intArrayOf(2, 2, 2, 6, 6, 6),
        intArrayOf(0, 11, 6, 6, 6, 4),
        intArrayOf(0, 2, 4, 6),
        intArrayOf(0, 4, 4, 4),
        intArrayOf(0, 4, 4, 4)
    )

    private val TRIANGLE_B_ENHANCED = arrayOf(
        intArrayOf(2, 4, 6, 0),
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
        intArrayOf(2, 4, 6, 0),
        intArrayOf(1, 6, 7, 1),
        intArrayOf(1, 6, 7, 1)
    )

    private val TRIANGLE_C_ENHANCED = arrayOf(
        intArrayOf(12, 12, 12, 12),
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
        intArrayOf(12, 12, 12, 12),
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 7, 1, 2)
    )


    private val NEIGHBOR_BLEND_A = arrayOf(
        intArrayOf(0, 2, 4, 6), intArrayOf(6, 0, 2, 4), intArrayOf(6, 0, 2),
        intArrayOf(2, 6, 0), intArrayOf(0, 2, 6), intArrayOf(6, 0, 2),
        intArrayOf(5, 6, 0, 1, 2, 4), intArrayOf(7, 2, 4, 4), intArrayOf(2, 4, 4, 7),
        intArrayOf(6, 6, 4, 0, 2, 2), intArrayOf(0, 2, 2, 6, 6, 4),
        intArrayOf(0, 2, 2, 4, 6, 6), intArrayOf(0, 2, 4, 6)
    )

    private val NEIGHBOR_BLEND_B = arrayOf(
        intArrayOf(2, 4, 6, 0), intArrayOf(0, 2, 4, 6), intArrayOf(0, 2, 4),
        intArrayOf(4, 0, 2), intArrayOf(2, 4, 0), intArrayOf(0, 2, 4),
        intArrayOf(6, 0, 1, 2, 4, 5), intArrayOf(0, 4, 7, 6), intArrayOf(4, 7, 6, 0),
        intArrayOf(0, 8, 6, 2, 9, 4), intArrayOf(2, 9, 4, 0, 8, 6),
        intArrayOf(2, 11, 4, 6, 10, 0), intArrayOf(2, 4, 6, 0)
    )

    private val NEIGHBOR_BLEND_C = arrayOf(
        intArrayOf(12, 12, 12, 12), intArrayOf(12, 12, 12, 12), intArrayOf(5, 5, 5),
        intArrayOf(5, 5, 5), intArrayOf(5, 5, 5), intArrayOf(5, 5, 5),
        intArrayOf(12, 12, 12, 12, 12, 12), intArrayOf(1, 1, 1, 7), intArrayOf(1, 1, 7, 1),
        intArrayOf(8, 9, 9, 8, 8, 9), intArrayOf(8, 8, 9, 8, 9, 9),
        intArrayOf(10, 10, 11, 11, 11, 10), intArrayOf(12, 12, 12, 12)
    )

    private val NEIGHBOR_BLEND_OVERLAY_COUNT = intArrayOf(4, 2, 1, 1, 2, 2, 3, 1, 3, 3, 3, 2, 0)

    private val NEIGHBOR_BLEND_UNDERLAY_COUNT = intArrayOf(0, 2, 2, 2, 1, 1, 3, 3, 1, 3, 3, 4, 4)

    private val NEIGHBOR_BLEND_FACE_COUNTS = arrayOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, 2, 3, 0), intArrayOf(1, 2, -1, 0),
        intArrayOf(2, 0, -1, 1), intArrayOf(0, 1, -1, 2), intArrayOf(1, 2, -1, 0),
        intArrayOf(-1, 4, -1, 1), intArrayOf(-1, 1, 3, -1), intArrayOf(-1, 0, 2, -1),
        intArrayOf(3, 5, 2, 0), intArrayOf(0, 2, 5, 3), intArrayOf(0, 2, 3, 5),
        intArrayOf(0, 1, 2, 3)
    )


    private val OVERLAY_BLEND_A = arrayOf(
        intArrayOf(0, 2, 4, 6), intArrayOf(6, 0, 2, 3, 5, 3), intArrayOf(6, 0, 2, 4),
        intArrayOf(2, 5, 6, 1), intArrayOf(0, 2, 6), intArrayOf(6, 0, 2),
        intArrayOf(5, 6, 0, 1, 2, 4), intArrayOf(7, 7, 1, 2, 4, 6), intArrayOf(2, 4, 4, 7),
        intArrayOf(6, 6, 4, 0, 1, 1, 3, 3), intArrayOf(0, 2, 2, 6, 6, 4),
        intArrayOf(0, 2, 2, 3, 7, 0, 4, 3), intArrayOf(0, 2, 4, 6)
    )

    private val OVERLAY_BLEND_B = arrayOf(
        intArrayOf(2, 4, 6, 0), intArrayOf(0, 2, 3, 5, 6, 4), intArrayOf(0, 1, 4, 5),
        intArrayOf(4, 6, 0, 2), intArrayOf(2, 4, 0), intArrayOf(0, 2, 4),
        intArrayOf(6, 0, 1, 2, 4, 5), intArrayOf(0, 1, 2, 4, 6, 7), intArrayOf(4, 7, 6, 0),
        intArrayOf(0, 8, 6, 1, 9, 2, 9, 4), intArrayOf(2, 9, 4, 0, 8, 6),
        intArrayOf(2, 11, 3, 7, 10, 10, 6, 6), intArrayOf(2, 4, 6, 0)
    )

    private val OVERLAY_BLEND_C = arrayOf(
        intArrayOf(12, 12, 12, 12), intArrayOf(12, 12, 12, 12, 12, 5), intArrayOf(5, 5, 1, 1),
        intArrayOf(5, 1, 1, 5), intArrayOf(5, 5, 5), intArrayOf(5, 5, 5),
        intArrayOf(12, 12, 12, 12, 12, 12), intArrayOf(1, 12, 12, 12, 12, 12), intArrayOf(1, 1, 7, 1),
        intArrayOf(8, 9, 9, 8, 8, 3, 1, 9), intArrayOf(8, 8, 9, 8, 9, 9),
        intArrayOf(10, 10, 11, 11, 11, 7, 3, 7), intArrayOf(12, 12, 12, 12)
    )

    private val OVERLAY_BLEND_OVERLAY_COUNT = intArrayOf(4, 2, 1, 1, 2, 2, 3, 1, 3, 3, 3, 2, 0)

    private val OVERLAY_BLEND_UNDERLAY_COUNT = intArrayOf(0, 4, 3, 3, 1, 1, 3, 5, 1, 5, 3, 6, 4)

    private val OVERLAY_BLEND_FACE_COUNTS = arrayOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, -1, -1, 0), intArrayOf(-1, 2, -1, 0),
        intArrayOf(-1, 0, -1, 2), intArrayOf(0, 1, -1, 2), intArrayOf(1, 2, -1, 0),
        intArrayOf(-1, 4, -1, 1), intArrayOf(-1, 3, 4, -1), intArrayOf(-1, 0, 2, -1),
        intArrayOf(-1, -1, 2, 0), intArrayOf(0, 2, 5, 3), intArrayOf(0, -1, 6, -1),
        intArrayOf(0, 1, 2, 3)
    )


    private val PATH_SQUARES = arrayOf(
        BooleanArray(4), BooleanArray(4),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, false, true),
        booleanArrayOf(true, false, false, true), BooleanArray(4), BooleanArray(4),
        BooleanArray(4), BooleanArray(4)
    )

    private val PATH_BLENDINGS = arrayOf(
        BooleanArray(4), booleanArrayOf(false, true, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, true, false),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, false, true),
        booleanArrayOf(true, false, false, true), booleanArrayOf(true, true, false, false),
        BooleanArray(4), booleanArrayOf(false, true, false, true), BooleanArray(4)
    )

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


    internal data class TileBlendState(
        val effectiveShape: Int,
        val effectiveRotation: Int,
        val overlaySupportsBlending: Boolean,
        val unblendable: Boolean,
        val hasFacesOn: BooleanArray,
        val waterSlots: IntArray,
        val overlayTileColors: IntArray,
        val waterTextureIds: IntArray,
        val waterTextureScaling: IntArray,
        val overlayHslColor: Int,
        val overlaySlot: Int,
        val overlayTextureIdBlend: Int,
        val overlayTextureScale: Int,
        val hasShadows: Boolean,
        val triA: IntArray,
        val triB: IntArray,
        val triC: IntArray,
        val overlayFaceCount: Int,
        val underlayFaceCount: Int,
        val edgeFaceCounts: IntArray?,
        val overlayTotalFaces: Int,
        val cornerUnderlayTextures: IntArray = intArrayOf(-1, -1, -1, -1),
        val cornerUnderlayScales: FloatArray = floatArrayOf(1f/512f, 1f/512f, 1f/512f, 1f/512f)
    )

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

        var overlayDef = if (overlayId > 0 && overlayId - 1 < overlayDefs.size) overlayDefs[overlayId - 1] else null
        val underlayDef = if (underlayId > 0 && underlayId - 1 < underlayDefs.size) underlayDefs[underlayId - 1] else null

        var pathShape = tile.overlayShape
        if (pathShape == 0 && overlayDef == null) {
            pathShape = 12
        }

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

        val nextX = if (x < 63) x + 1 else x
        val nextY = if (y < 63) y + 1 else y
        var tileRotation = tile.overlayRotation

        tileRotation = calculateUnderlayRotationLocal(
            underlayId.toByte(), x, y, nextX, nextY, region, plane, pathShape, tileRotation
        )

        val waterSlots = IntArray(13) { -1 }
        val overlayFunctionMask = IntArray(13) { 1 }
        val overlayTileColors = IntArray(13)
        val waterTextureIds = IntArray(13)
        val waterTextureScaling = IntArray(13)

        calculateOverlayDisplayLocal(
            overlayDef, x, y, region, plane, pathShape, tileRotation, overlayBlends,
            hasFacesOn, waterSlots, overlayFunctionMask, overlayTileColors,
            waterTextureIds, waterTextureScaling
        )

        val blendings = if (overlayDef != null && overlayDef.underlayOverrides) {
            PATH_BLENDINGS.getOrNull(pathShape) ?: BooleanArray(4)
        } else {
            PATH_SQUARES.getOrNull(pathShape) ?: BooleanArray(4)
        }

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

        val unblendable = !overlayBlends && !hasFacesOn[0] && !hasFacesOn[1] && !hasFacesOn[2] && !hasFacesOn[3]

        val triA: IntArray
        val triB: IntArray
        val triC: IntArray
        val overlayFaces: Int
        val underlayFaces: Int
        val edgeFaceCounts: IntArray?

        if (unblendable) {
            triA = TRIANGLE_A.getOrNull(pathShape) ?: intArrayOf()
            triB = TRIANGLE_B.getOrNull(pathShape) ?: intArrayOf()
            triC = TRIANGLE_C.getOrNull(pathShape) ?: intArrayOf()
            overlayFaces = if (overlayDef != null) OVERLAY_FACE_COUNT.getOrElse(pathShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) UNDERLAY_FACE_COUNT.getOrElse(pathShape) { 0 } else 0
            edgeFaceCounts = null
        } else if (overlayBlends) {
            checkBlendableShape(pathShape, OVERLAY_BLEND_A.size)
            triA = OVERLAY_BLEND_A[pathShape]
            triB = OVERLAY_BLEND_B[pathShape]
            triC = OVERLAY_BLEND_C[pathShape]
            overlayFaces = if (overlayDef != null) OVERLAY_BLEND_OVERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) OVERLAY_BLEND_UNDERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            edgeFaceCounts = OVERLAY_BLEND_FACE_COUNTS.getOrNull(pathShape)
        } else {
            checkBlendableShape(pathShape, NEIGHBOR_BLEND_A.size)
            triA = NEIGHBOR_BLEND_A[pathShape]
            triB = NEIGHBOR_BLEND_B[pathShape]
            triC = NEIGHBOR_BLEND_C[pathShape]
            overlayFaces = if (overlayDef != null) NEIGHBOR_BLEND_OVERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) NEIGHBOR_BLEND_UNDERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            edgeFaceCounts = NEIGHBOR_BLEND_FACE_COUNTS.getOrNull(pathShape)
        }

        val overlayHsl = if (overlayDef != null && overlayDef.colour != -1) overlayDef.colour else -1
        val overlayTexId = overlayDef?.texture ?: -1
        val overlayTexScale = overlayDef?.scale ?: 256

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
            overlaySlot = overlayDef?.anInt3633 ?: 8,
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

    private fun checkBlendableShape(pathShape: Int, tableSize: Int) {
        check(pathShape in 0 until tableSize) {
            "Tile shape $pathShape has no row in the blend tables (0..${tableSize - 1}). " +
                "Shapes 13/14 must reach the unblendable TRIANGLE_* tables; the promotion " +
                "in analyzeTileBlending clears every hasFacesOn flag it sets, so a blended " +
                "tile can never carry them. Blending mode selection has diverged."
        }
    }

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
                swNeDelta = Math.abs(tiles[x][y].height - tiles[nextX][nextY].height)
                seNwDelta = Math.abs(tiles[nextX][y].height - tiles[x][nextY].height)
            }

            return if (swNeDelta < seNwDelta) 1 else 0
        }
        return currentRotation
    }

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
                        if (!blendingAttributes[tileRotation and 0x3]) {
                            hasFacesOn[0] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 2) and 0x3] else false
                        }
                    } else if (!blendingAttributes[tileRotation and 0x3]) {
                        hasFacesOn[0] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 2) and 0x3] else false
                    }
                }
            }
        }

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
                        if (!blendingAttributes[(tileRotation + 2) and 0x3]) {
                            hasFacesOn[2] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][rotation and 0x3] else false
                        }
                    } else if (!blendingAttributes[(tileRotation + 2) and 0x3]) {
                        hasFacesOn[2] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][rotation and 0x3] else false
                    }
                }
            }
        }

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
                        if (!blendingAttributes[(tileRotation + 3) and 0x3]) {
                            hasFacesOn[3] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 1) and 0x3] else false
                        }
                    } else if (!blendingAttributes[(tileRotation + 3) and 0x3]) {
                        hasFacesOn[3] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 1) and 0x3] else false
                    }
                }
            }
        }

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
                        if (!blendingAttributes[(tileRotation + 1) and 0x3]) {
                            hasFacesOn[1] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 3) and 0x3] else false
                        }
                    } else if (!blendingAttributes[(tileRotation + 1) and 0x3]) {
                        hasFacesOn[1] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 3) and 0x3] else false
                    }
                }
            }
        }

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

    internal data class ShapedVertex(
        val x: Float, val y: Float, val z: Float,
        val r: Float, val g: Float, val b: Float,
        val u: Float, val v: Float,
        val nx: Float, val ny: Float, val nz: Float,
        val textureId: Int,
        val alpha: Float = 1f
    )

    internal fun generateShapedTile(
        x: Int, y: Int,
        shape: Int, rotation: Int,
        tileSize: Float,
        heights: FloatArray,
        overlayColor: Vector3f,
        underlayCornerHsl: IntArray,
        overlayTextureId: Int,
        underlayTextureId: Int,
        overlayScaleRaw: Int,
        underlayScaleRaw: Int,
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
        cornerUnderlayScalesRaw: IntArray = intArrayOf(512, 512, 512, 512),
        cornerDepths: FloatArray = NO_WATER_DEPTH,
        waterFogDepth: Int = 0,
        isWater: Boolean = false,
        waterColor: Vector3f = Vector3f()
    ): List<ShapedVertex> {
        val safeShape = shape.coerceIn(0, TRIANGLE_A.size - 1)

        val triA: IntArray
        val triB: IntArray
        val triC: IntArray
        val overlayFaces: Int
        val underlayFaces: Int

        if (blendState != null) {
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
        if (totalFaces == 0) return emptyList()

        val fast = fastTessellation

        val vertices = if (fast) ArrayList<ShapedVertex>(totalFaces * 3) else mutableListOf()

        val baseX = x * tileSize
        val baseZ = -y * tileSize

        val vertexX = FloatArray(13)
        val vertexZ = FloatArray(13)
        val vertexY = FloatArray(13)
        val vertexDepth = FloatArray(13)
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

            vertexDepth[i] = (1 - fx) * (1 - fy) * cornerDepths[0] +
                             fx * (1 - fy) * cornerDepths[1] +
                             (1 - fx) * fy * cornerDepths[2] +
                             fx * fy * cornerDepths[3]
        }

        fun getVertexQuadrant(vIdx: Int): Int {
            val lx = vertexLocalX[vIdx]
            val ly = vertexLocalY[vIdx]
            return if (lx < 256) {
                if (ly < 256) 0 else 2
            } else {
                if (ly < 256) 1 else 3
            }
        }

        val nSW = cornerNormals[0]
        val nSE = cornerNormals[1]
        val nNW = cornerNormals[2]
        val nNE = cornerNormals[3]

        val vertexNormalX = FloatArray(13)
        val vertexNormalY = FloatArray(13)
        val vertexNormalZ = FloatArray(13)
        val southScratch = if (fast) Vector3f() else null
        val northScratch = if (fast) Vector3f() else null
        val normalScratch = if (fast) Vector3f() else null
        for (i in 0 until 13) {
            val fx = vertexLocalX[i].toFloat() / 512f
            val fy = vertexLocalY[i].toFloat() / 512f
            val interpolated: Vector3f
            if (southScratch != null && northScratch != null && normalScratch != null) {
                southScratch.set(nSW).lerp(nSE, fx)
                northScratch.set(nNW).lerp(nNE, fx)
                interpolated = normalScratch.set(southScratch).lerp(northScratch, fy)
            } else {
                val southNormal = Vector3f(nSW).lerp(nSE, fx)
                val northNormal = Vector3f(nNW).lerp(nNE, fx)
                interpolated = Vector3f(southNormal).lerp(northNormal, fy)
            }
            val len = interpolated.length()
            if (len > 0.0001f) {
                interpolated.div(len)
            } else {
                interpolated.set(0f, 1f, 0f)
            }
            vertexNormalX[i] = interpolated.x
            vertexNormalY[i] = interpolated.y
            vertexNormalZ[i] = interpolated.z
        }

        val hslSW = underlayCornerHsl[0]
        val hslSE = underlayCornerHsl[1]
        val hslNW = underlayCornerHsl[2]
        val hslNE = underlayCornerHsl[3]

        fun applyEffectsAndConvert(hsl: Int, vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            var result = hsl
            if (result == 0) return Vector3f(0f, 0f, 0f)
            if (enableColorNoise) {
                result = ColorUtil.applyBrightnessNoise(result, ColorUtil.getVertexNoise(worldPosX, worldPosZ, colorNoiseAmount))
            }
            if (enableLighting) {
                val lightFactor = if (bakeSunLighting) {
                    ColorUtil.calculateTerrainLightFactor(
                        vertexNormalX[vIdx], vertexNormalY[vIdx], vertexNormalZ[vIdx],
                        LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                        SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                    )
                } else {
                    GROUND_BASE_LIGHT_FACTOR
                }
                result = ColorUtil.applyLightToHsl(result, lightFactor)
            }
            return if (fast) ColorUtil.hslToRgb(result) else ColorUtil.hslToRgbUncached(result)
        }

        fun underlayVertexSource(vIdx: Int): Int {
            if (blendState != null && vIdx < 8) {
                val slot = (vIdx - rotation * 2) and 0x7
                if (blendState.waterSlots[slot] >= 0 && blendState.overlayTileColors[slot] != 0) {
                    return 0
                }
            }
            if (blendState != null && blendState.overlaySupportsBlending
                && blendState.effectiveShape < SHAPE_VERTEX_IS_OVERLAY.size
                && vIdx < SHAPE_VERTEX_IS_OVERLAY[blendState.effectiveShape].size
                && SHAPE_VERTEX_IS_OVERLAY[blendState.effectiveShape][vIdx]
                && blendState.overlayHslColor > 0
            ) {
                return 1
            }
            return 2
        }

        fun overlayVertexOverridden(vIdx: Int): Boolean {
            if (blendState == null || vIdx >= 8) return false
            val slot = (vIdx - rotation * 2) and 0x7
            return blendState.waterSlots[slot] > blendState.overlaySlot &&
                blendState.overlayTileColors[slot] != 0
        }

        fun uvScaleOf(rawScale: Int): Float = if (rawScale > 0) 1f / rawScale.toFloat() else 1f / 512f

        val underlayVertexTexture = IntArray(13)
        val underlayVertexScale = IntArray(13)
        val overlayVertexTexture = IntArray(13)
        val overlayVertexScale = IntArray(13)
        for (i in 0 until 13) {
            val slot = (i - rotation * 2) and 0x7
            when (underlayVertexSource(i)) {
                0 -> {
                    underlayVertexTexture[i] = blendState!!.waterTextureIds[slot]
                    underlayVertexScale[i] = blendState.waterTextureScaling[slot]
                }
                1 -> {
                    underlayVertexTexture[i] = blendState!!.overlayTextureIdBlend
                    underlayVertexScale[i] = blendState.overlayTextureScale
                }
                else -> {
                    val q = getVertexQuadrant(i)
                    val cornerTex = cornerUnderlayTextures[q]
                    underlayVertexTexture[i] = if (cornerTex >= 0) cornerTex else underlayTextureId
                    underlayVertexScale[i] = if (cornerTex >= 0) cornerUnderlayScalesRaw[q] else underlayScaleRaw
                }
            }
            if (overlayVertexOverridden(i)) {
                overlayVertexTexture[i] = blendState!!.waterTextureIds[slot]
                overlayVertexScale[i] = blendState.waterTextureScaling[slot]
            } else {
                overlayVertexTexture[i] = overlayTextureId
                overlayVertexScale[i] = overlayScaleRaw
            }
        }

        fun plainUnderlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            val localX = vertexLocalX[vIdx]
            val localY = vertexLocalY[vIdx]
            val fx = localX.toFloat() / 512f
            val fy = localY.toFloat() / 512f

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
                val factorX = (localX shl 7) shr 9
                val factorY = (localY shl 7) shr 9
                val south = ColorUtil.blendHsl(hslSW, hslSE, factorX)
                val north = ColorUtil.blendHsl(hslNW, hslNE, factorX)
                blendedHsl = ColorUtil.blendHsl(south, north, factorY)
            }

            if (blendedHsl == 0) return Vector3f(0f, 0f, 0f)
            return applyEffectsAndConvert(blendedHsl, vIdx, worldPosX, worldPosZ)
        }

        fun getUnderlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            when (underlayVertexSource(vIdx)) {
                0 -> {
                    val slot = (vIdx - rotation * 2) and 0x7
                    return applyEffectsAndConvert(
                        blendState!!.overlayTileColors[slot], vIdx, worldPosX, worldPosZ
                    )
                }
                1 -> return applyEffectsAndConvert(
                    blendState!!.overlayHslColor, vIdx, worldPosX, worldPosZ
                )
            }
            return plainUnderlayVertexColor(vIdx, worldPosX, worldPosZ)
        }

        val shoreDepth = waterFogDepth.toFloat() * SHORE_FOG_SPREAD
        val isWaterOverlay = enableShoreline && shoreDepth > 0f && hasUnderlay && isWater

        fun waterFogAt(vIdx: Int): Float =
            (vertexDepth[vIdx] * 255f / shoreDepth).coerceIn(0f, 255f) / 255f

        fun overlayVertexColorNoShore(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            if (overlayVertexOverridden(vIdx)) {
                val slot = (vIdx - rotation * 2) and 0x7
                return applyEffectsAndConvert(
                    blendState!!.overlayTileColors[slot], vIdx, worldPosX, worldPosZ
                )
            }

            if (overlayHsl != 0) {
                return applyEffectsAndConvert(overlayHsl, vIdx, worldPosX, worldPosZ)
            }

            val lightFactor = if (!enableLighting) {
                1.0f
            } else if (bakeSunLighting) {
                ColorUtil.calculateTerrainLightFactor(
                    vertexNormalX[vIdx], vertexNormalY[vIdx], vertexNormalZ[vIdx],
                    LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                    SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                )
            } else {
                GROUND_BASE_LIGHT_FACTOR
            }
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

        fun getOverlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            val surface = overlayVertexColorNoShore(vIdx, worldPosX, worldPosZ)
            if (!isWaterOverlay) return surface
            val ground = plainUnderlayVertexColor(vIdx, worldPosX, worldPosZ)
            if (ground.x == 0f && ground.y == 0f && ground.z == 0f) return surface
            val fog = waterFogAt(vIdx)
            return ground.lerp(waterColor, fog).lerp(surface, WATER_SURFACE_OPACITY * fog)
        }

        val underlayColorCache = FloatArray(39)
        val underlayColorReady = BooleanArray(13)
        val overlayColorCache = FloatArray(39)
        val overlayColorReady = BooleanArray(13)


        var overlayTotalFaces = 0

        fun materialKey(texId: Int, rawScale: Int): Long =
            if (texId < 0) -1L else ((rawScale.toLong() shl 14) or texId.toLong())

        val triangleScratch = IntArray(3)

        fun emitTriangle(ia: Int, ib: Int, ic: Int, isOverlay: Boolean) {
            val vertexIndicesArr = if (fast) triangleScratch else IntArray(3)
            vertexIndicesArr[0] = ia
            vertexIndicesArr[1] = ib
            vertexIndicesArr[2] = ic
            val vertexTexture = if (isOverlay) overlayVertexTexture else underlayVertexTexture
            val vertexScale = if (isOverlay) overlayVertexScale else underlayVertexScale
            val colorCache = if (isOverlay) overlayColorCache else underlayColorCache
            val colorReady = if (isOverlay) overlayColorReady else underlayColorReady

            fun createVertex(vIdx: Int, texId: Int, vUvScale: Float, vAlpha: Float): ShapedVertex {
                val px = vertexX[vIdx]
                val py = vertexY[vIdx]
                val pz = vertexZ[vIdx]
                val colorBase = vIdx * 3
                if (!fast || !colorReady[vIdx]) {
                    val color = if (isOverlay) getOverlayVertexColor(vIdx, px, pz)
                                else getUnderlayVertexColor(vIdx, px, pz)
                    colorCache[colorBase] = color.x
                    colorCache[colorBase + 1] = color.y
                    colorCache[colorBase + 2] = color.z
                    colorReady[vIdx] = true
                }

                val localXVal = vertexLocalX[vIdx]
                val localYVal = vertexLocalY[vIdx]
                val sceneX = (x * 512 + localXVal).toFloat()
                val sceneY = (y * 512 + localYVal).toFloat()

                val u = sceneX * vUvScale
                val v = sceneY * vUvScale

                return ShapedVertex(
                    px, py, pz,
                    colorCache[colorBase], colorCache[colorBase + 1], colorCache[colorBase + 2],
                    u, v,
                    vertexNormalX[vIdx], vertexNormalY[vIdx], vertexNormalZ[vIdx],
                    texId,
                    alpha = vAlpha
                )
            }

            var baseVertex = vertexIndicesArr[0]
            for (vi in 1..2) {
                val candidate = vertexIndicesArr[vi]
                if (materialKey(vertexTexture[candidate], vertexScale[candidate]) <
                    materialKey(vertexTexture[baseVertex], vertexScale[baseVertex])
                ) {
                    baseVertex = candidate
                }
            }
            val baseKey = materialKey(vertexTexture[baseVertex], vertexScale[baseVertex])
            val baseUvScale = uvScaleOf(vertexScale[baseVertex])
            for (vIdx in vertexIndicesArr) {
                vertices.add(createVertex(vIdx, vertexTexture[baseVertex], baseUvScale, 1f))
            }

            for (vi in 0..2) {
                val layerVertex = vertexIndicesArr[vi]
                val layerKey = materialKey(vertexTexture[layerVertex], vertexScale[layerVertex])
                if (layerKey == baseKey) continue
                var alreadyEmitted = false
                for (prev in 0 until vi) {
                    val prevVertex = vertexIndicesArr[prev]
                    if (materialKey(vertexTexture[prevVertex], vertexScale[prevVertex]) == layerKey) {
                        alreadyEmitted = true
                    }
                }
                if (alreadyEmitted) continue

                val layerUvScale = uvScaleOf(vertexScale[layerVertex])
                for (vIdx in vertexIndicesArr) {
                    val ownsLayer = materialKey(vertexTexture[vIdx], vertexScale[vIdx]) == layerKey
                    vertices.add(
                        createVertex(vIdx, vertexTexture[layerVertex], layerUvScale, if (ownsLayer) 1f else 0f)
                    )
                }
            }
        }

        fun emitFace(isOverlay: Boolean, faceIndex: Int) {
            if (isOverlay && !enableOverlays) {
                overlayTotalFaces++
                return
            }


            val edgeFaceCounts = blendState?.edgeFaceCounts
            val hasFacesOn = blendState?.hasFacesOn

            val edgeCheckOrder = intArrayOf(0, 2, 1, 3)
            val edgeVertices = intArrayOf(1, 3, 5, 7)
            var edgeInserted = false

            if (edgeFaceCounts != null && hasFacesOn != null) {
                for (checkIdx in 0..3) {
                    val edge = edgeCheckOrder[checkIdx]
                    val rotatedEdge = (edge - rotation) and 0x3
                    if (hasFacesOn[rotatedEdge] && edgeFaceCounts[edge] == overlayTotalFaces) {
                        edgeInserted = true
                        val edgeVertex = edgeVertices[edge]

                        val ia1 = triA[overlayTotalFaces]
                        val ic1 = triC[overlayTotalFaces]
                        val ib2 = triB[overlayTotalFaces]

                        emitTriangle(ia1, edgeVertex, ic1, isOverlay)
                        emitTriangle(edgeVertex, ib2, ic1, isOverlay)
                        break
                    }
                }
            }

            if (!edgeInserted) {
                if (overlayTotalFaces < triA.size) {
                    val ia = triA[overlayTotalFaces]
                    val ib = triB[overlayTotalFaces]
                    val ic = triC[overlayTotalFaces]
                    emitTriangle(ia, ib, ic, isOverlay)
                }
            }

            overlayTotalFaces++
        }

        for (face in 0 until overlayFaces) {
            emitFace(true, face)
        }

        if (overlayFaces == 0 && blendState != null) {
            overlayTotalFaces = blendState.overlayFaceCount
        }

        for (face in 0 until underlayFaces) {
            emitFace(false, face)
        }

        return vertices
    }
}
