package org.darkan.tools.cache.map

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import kotlin.math.abs

object TiltProbe {

    private const val CONTOUR_HILL_CHANGE = 3

    private const val CONTOUR_MODIFIER_EXEMPT_THRESHOLD = 16384

    private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

    private fun signedDegrees(radians: Float): Float {
        var degrees = radians * RAD_TO_DEG % 360f
        if (degrees > 180f) degrees -= 360f
        if (degrees <= -180f) degrees += 360f
        return degrees
    }

    private var transformFailures = 0L

    private fun resolveDefinition(objectId: Int): ObjectDefinition? = try {
        ObjectRenderer.getDefinition(objectId)
    } catch (e: IndexOutOfBoundsException) {
        transformFailures++
        ObjectRenderer.getRawDefinition(objectId)
    }

    private class Candidate(
        val obj: MapObject,
        val def: ObjectDefinition,
        val localX: Int,
        val localY: Int,
        val sizeX: Int,
        val sizeY: Int
    ) {
        val clampReaches: Boolean

        val highSampleX: Float
        val highSampleY: Float

        init {
            val width: Float
            val length: Float
            if (def.anInt3023 > 0) {
                width = ((def.anInt3023 and 0xFF) * 4).toFloat()
                length = (((def.anInt3023 shr 8) and 0xFF) * 4).toFloat()
            } else {
                width = (sizeX * 512).toFloat()
                length = (sizeY * 512).toFloat()
            }
            val maxSampleX = localX + sizeX * 0.5f + width / 1024f
            val maxSampleY = localY + sizeY * 0.5f + length / 1024f
            clampReaches = maxSampleX >= 64f || maxSampleY >= 64f
            highSampleX = maxSampleX
            highSampleY = maxSampleY
        }

        fun describeSampling(): String =
            "modifier=%d maxSample=(%.3f,%.3f) clampReaches=%b"
                .format(def.anInt3023, highSampleX, highSampleY, clampReaches)
    }


    fun scan(cache: Cache, maxRegions: Int?) {
        if (!MapXteas.isLoaded()) MapXteas.load()
        ObjectRenderer.loadDefinitions(cache)

        val regions = RegionLoader.getAvailableRegions(cache)
            .let { if (maxRegions != null) it.take(maxRegions) else it }
        println("[tiltprobe] sweeping ${regions.size} regions for contour-type-3 objects whose " +
                "tilt sampling reaches past the region edge")

        val contourHistogram = HashMap<Int, Long>()
        var regionsDecoded = 0
        var regionsEmpty = 0
        var regionsUnread = 0
        var regionsNoKeys = 0
        var objectsSeen = 0L
        var type3Total = 0L
        var type3Exempt = 0L
        var type3Live = 0L
        var type3Candidates = 0L
        var type3Seated = 0L
        var type3Edge = 0L
        val hitsByRegion = LinkedHashMap<Pair<Int, Int>, Int>()

        for ((regionX, regionY) in regions) {
            val region = RegionLoader.loadRegion(cache, regionX, regionY, useCache = false)
            if (region == null || region.objects.isEmpty()) {
                regionsEmpty++
                if (RegionLoader.regionExists(cache, regionX, regionY) &&
                    cache.archiveId(Index.MAPS, "l${regionX}_$regionY") != -1) {
                    regionsUnread++
                    if (!MapXteas.hasKeys(regionX, regionY)) regionsNoKeys++
                }
                continue
            }
            regionsDecoded++
            var regionHits = 0
            for (obj in region.objects) {
                val def = resolveDefinition(obj.id) ?: continue
                objectsSeen++
                val contour = def.contouredGround.toInt()
                contourHistogram[contour] = (contourHistogram[contour] ?: 0L) + 1L
                if (contour != CONTOUR_HILL_CHANGE) continue
                type3Total++
                if (def.anInt3023 >= CONTOUR_MODIFIER_EXEMPT_THRESHOLD) {
                    type3Exempt++
                    continue
                }
                type3Live++
            }
            for (c in collectCandidates(region.objects, regionX, regionY)) {
                type3Candidates++
                if (c.localX == 63 || c.localY == 63) {
                    type3Seated++
                    println("    seated on edge: region %d,%d id=%d tile=(%d,%d) plane=%d size=%dx%d %s"
                        .format(regionX, regionY, c.obj.id, c.localX, c.localY, c.obj.level,
                            c.sizeX, c.sizeY, c.describeSampling()))
                }
                if (!c.clampReaches) continue
                type3Edge++
                regionHits++
            }
            if (regionHits > 0) hitsByRegion[Pair(regionX, regionY)] = regionHits
        }

        println()
        println("  regions with objects: $regionsDecoded   regions yielding none: $regionsEmpty")
        println("  ...of which have an object archive that did not decode: $regionsUnread " +
                "($regionsNoKeys of them for want of XTEA keys)")
        println("  objects examined:     $objectsSeen")
        if (transformFailures > 0) {
            println("  varbit transforms that threw (see resolveDefinition): $transformFailures")
        }
        println()
        println("  contouredGround histogram over every object examined")
        for (key in contourHistogram.keys.sorted()) {
            println("    = %-3d -> %d".format(key, contourHistogram[key]))
        }
        println()
        println("  type 3 total:                                       $type3Total")
        println("  ...exempt (modifier >= $CONTOUR_MODIFIER_EXEMPT_THRESHOLD):                   $type3Exempt")
        println("  ...live, i.e. actually tilted:                       $type3Live")
        println("  ...with models, so reaching the tilt call at all:    $type3Candidates")
        println("  ...of those, seated on x==63 or y==63:               $type3Seated")
        println("  ...of those, whose sampler the clamp actually alters: $type3Edge")

        if (hitsByRegion.isEmpty()) {
            println()
            println("  UNREACHABLE  no type 3 object anywhere in the cache samples past its")
            println("               region's edge; the clamp in the tilt sampler is dead code.")
            return
        }
        println()
        println("  affected regions: ${hitsByRegion.size}")
        for ((key, count) in hitsByRegion.entries.sortedByDescending { it.value }.take(30)) {
            println("    region %d,%d  %d type 3 object(s) sampling past the edge".format(key.first, key.second, count))
        }
        if (hitsByRegion.size > 30) println("    ... and ${hitsByRegion.size - 30} more")
    }


    fun whatIf(cache: Cache, regionX: Int, regionY: Int) {
        if (!MapXteas.isLoaded()) MapXteas.load()
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)

        val terrain = TerrainLoader.loadRegion(cache, regionX, regionY)
        if (terrain == null) {
            println("[tiltprobe] region $regionX,$regionY has no terrain data")
            return
        }
        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val tiles = terrain.tiles
        if (neighbors == null) {
            println("[tiltprobe] region $regionX,$regionY has no neighbour data; the seam cannot")
            println("            be read and this comparison would be vacuous")
            return
        }

        println("[tiltprobe] region $regionX,$regionY: tilt a hypothetical 1x1 contour-type-3 object")
        println("            would lose to the 0..63 clamp, at each seam tile of plane 0")

        var eastLost = 0
        var northLost = 0
        var worstEast = 0f
        var worstNorth = 0f
        val examples = ArrayList<String>()

        for (i in 0..63) {
            val east = seamTilt(tiles, neighbors, localX = 63, localY = i, shiftX = 1, shiftY = 0)
            if (east.changed) {
                eastLost++
                val magnitude = maxOf(abs(east.dTiltX), abs(east.dTiltZ))
                if (magnitude > worstEast) worstEast = magnitude
                if (examples.size < 12) examples.add(
                    "    x=63 y=%-2d  tiltZ clamped %7.3f°  unclamped %7.3f°  (seam step %d RS units)"
                        .format(i, signedDegrees(east.clamped.tiltZ), signedDegrees(east.extended.tiltZ),
                            seamStep(tiles, neighbors, 63, i, 1, 0)))
            }
            val north = seamTilt(tiles, neighbors, localX = i, localY = 63, shiftX = 0, shiftY = 1)
            if (north.changed) {
                northLost++
                val magnitude = maxOf(abs(north.dTiltX), abs(north.dTiltZ))
                if (magnitude > worstNorth) worstNorth = magnitude
            }
        }

        println()
        println("  eastern seam (x==63):  %2d of 64 tiles would tilt differently, worst %.3f°"
            .format(eastLost, worstEast))
        println("  northern seam (y==63): %2d of 64 tiles would tilt differently, worst %.3f°"
            .format(northLost, worstNorth))
        if (examples.isNotEmpty()) {
            println()
            println("  sample of the eastern seam:")
            examples.forEach { println(it) }
        }
        if (eastLost == 0 && northLost == 0) {
            println()
            println("  this region's seams are level; the clamp would cost nothing here")
        }
    }

    private class Diff(
        val clamped: TerrainAligner.AlignmentResult,
        val extended: TerrainAligner.AlignmentResult
    ) {
        val dTiltX = signedDegrees(extended.tiltX) - signedDegrees(clamped.tiltX)
        val dTiltZ = signedDegrees(extended.tiltZ) - signedDegrees(clamped.tiltZ)
        val changed: Boolean get() = dTiltX != 0f || dTiltZ != 0f
    }

    private fun seamStep(
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        localX: Int,
        localY: Int,
        shiftX: Int,
        shiftY: Int
    ): Int = TerrainLoader.getExtendedTileHeight(tiles, neighbors, 0, localX + shiftX, localY + shiftY) -
            TerrainLoader.getExtendedTileHeight(tiles, neighbors, 0, localX, localY)

    private fun seamTilt(
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        localX: Int,
        localY: Int,
        shiftX: Int,
        shiftY: Int
    ): Diff {
        val average = TerrainLoader.getExtendedTileHeightScaled(tiles, neighbors, 0, localX, localY)
        val clamped = TerrainAligner.calculateObjectTilt(
            localTileX = localX,
            localTileY = localY,
            sizeX = 1,
            sizeY = 1,
            groundContourType = CONTOUR_HILL_CHANGE,
            groundContourModifier = 0,
            averageHeight = average,
            heightSampler = { tx, ty ->
                TerrainLoader.getExtendedTileHeightScaled(
                    tiles, neighbors, 0, tx.coerceIn(0, 63), ty.coerceIn(0, 63))
            }
        )
        val extended = TerrainAligner.calculateObjectTilt(
            localTileX = localX - shiftX,
            localTileY = localY - shiftY,
            sizeX = 1,
            sizeY = 1,
            groundContourType = CONTOUR_HILL_CHANGE,
            groundContourModifier = 0,
            averageHeight = average,
            heightSampler = { tx, ty ->
                TerrainLoader.getExtendedTileHeightScaled(
                    tiles, neighbors, 0, tx + shiftX, ty + shiftY)
            }
        )
        return Diff(clamped, extended)
    }

    private fun collectCandidates(objects: List<MapObject>, regionX: Int, regionY: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (obj in objects) {
            val def = resolveDefinition(obj.id) ?: continue
            if (def.contouredGround.toInt() != CONTOUR_HILL_CHANGE) continue
            if (def.anInt3023 >= CONTOUR_MODIFIER_EXEMPT_THRESHOLD) continue
            if (ObjectRenderer.getModelIds(def, obj.shape).isEmpty()) continue
            val localX = obj.x - regionX * 64
            val localY = obj.y - regionY * 64
            if (localX !in 0..63 || localY !in 0..63) continue
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }
            out.add(Candidate(obj, def, localX, localY, sizeX, sizeY))
        }
        return out
    }
}
