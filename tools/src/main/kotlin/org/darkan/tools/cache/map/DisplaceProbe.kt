package org.darkan.tools.cache.map

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.render.MeshData
import world.gregs.voidps.cache.Cache

object DisplaceProbe {

    private const val TILE_SCALE = 9

    private const val INV_SCALE = 128f

    private const val RESIZE_UNIT = 128f

    private class Audit(
        val obj: ObjectInstance,
        val localX: Int,
        val localY: Int,
        val flat: Boolean,
        val totalVertices: Int,
        val movedVertices: Int,
        val rejectedByUnpadded: Int,
        val rejectedByPadded: Int,
        val maxDeltaWorld: Float,
        val footprintRelief: Int
    ) {
        val onEdge: Boolean get() = localX == 63 || localY == 63
    }

    fun run(cache: Cache, regionX: Int, regionY: Int, focusObjectId: Int?) {
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)

        val audits = auditRegion(cache, regionX, regionY)
        if (audits == null) {
            println("[displaceprobe] region $regionX,$regionY has no terrain or object data")
            return
        }

        println("[displaceprobe] region $regionX,$regionY: ${audits.size} contour-type-1 objects")
        println("[displaceprobe] height field: ${VertexDisplacer.HeightField.PAD_TILES} tiles of " +
                "neighbour padding per side")

        val edge = audits.filter { it.onEdge }
        val interior = audits.filter { !it.onEdge }
        println()
        println("  %-22s %6s %10s %10s".format("group", "objs", "moved/tot", "moved=0"))
        printGroup("on x==63 or y==63", edge)
        printGroup("interior", interior)

        val wouldHaveBeenDropped = audits.filter { it.rejectedByUnpadded == it.totalVertices && it.totalVertices > 0 }
        println()
        println("  objects whose every vertex the unpadded field rejected: ${wouldHaveBeenDropped.size}")
        val nowMoving = wouldHaveBeenDropped.count { it.movedVertices > 0 }
        println("  ...of which the padded field now displaces: $nowMoving")
        val worstStep = wouldHaveBeenDropped.maxOfOrNull { it.maxDeltaWorld } ?: 0f
        println("  largest displacement recovered: %.3f world units".format(worstStep))

        val outOfReach = audits.filter { it.rejectedByPadded > 0 }
        println()
        if (outOfReach.isEmpty()) {
            println("  PASS  every vertex of every contour-1 object is inside the height field; " +
                    "no displacement is silently dropped")
        } else {
            println("  FAIL  ${outOfReach.size} object(s) have vertices the field cannot reach:")
            for (a in outOfReach.take(20)) {
                println("        id=%d at (%d,%d) plane=%d unreachable=%d/%d"
                    .format(a.obj.mapObject.id, a.localX, a.localY,
                        a.obj.mapObject.level, a.rejectedByPadded, a.totalVertices))
            }
        }

        val movedNothing = audits.filter { !it.flat && it.movedVertices == 0 && it.totalVertices > 0 }
        println("  moved nothing despite failing the flat pre-test: ${movedNothing.size} " +
                "(bounding-box overshoot; all reachable)")

        if (focusObjectId != null) {
            println()
            println("[displaceprobe] object $focusObjectId:")
            val hits = audits.filter { it.obj.mapObject.id == focusObjectId }
            if (hits.isEmpty()) {
                println("  not present as a contour-type-1 object in this region")
            }
            for (a in hits.sortedWith(compareBy({ it.localY }, { it.localX }))) {
                println(("  (%2d,%2d) plane=%d  moved=%d/%d  unpadded-would-reject=%d  " +
                        "maxDelta=%.3f world units  2x2 relief=%d RS")
                    .format(a.localX, a.localY, a.obj.mapObject.level,
                        a.movedVertices, a.totalVertices, a.rejectedByUnpadded,
                        a.maxDeltaWorld, a.footprintRelief))
            }
        }
    }

    private fun printGroup(label: String, group: List<Audit>) {
        if (group.isEmpty()) {
            println("  %-22s %6d %10s %10s".format(label, 0, "-", "-"))
            return
        }
        val moved = group.sumOf { it.movedVertices.toLong() }
        val total = group.sumOf { it.totalVertices.toLong() }
        val zero = group.count { it.movedVertices == 0 }
        println("  %-22s %6d %10s %10d".format(
            label, group.size,
            if (total == 0L) "-" else "%.1f%%".format(100.0 * moved / total),
            zero))
    }

    fun runJoint(cache: Cache, regionX: Int, regionY: Int, objectId: Int) {
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)

        val south = auditRegion(cache, regionX, regionY) ?: emptyList()
        val north = auditRegion(cache, regionX, regionY + 1) ?: emptyList()

        val southEdge = south.filter { it.localY == 63 && it.obj.mapObject.id == objectId }
        val northEdge = north.filter { it.localY == 0 && it.obj.mapObject.id == objectId }

        println("[displaceprobe] joint across $regionX,$regionY | $regionX,${regionY + 1} for object $objectId")
        println("  %-28s %10s %12s".format("side", "moved/tot", "maxDelta(w)"))
        for (a in southEdge.sortedBy { it.localX }) {
            println("  region %d,%d y=63 x=%-8d %6d/%-4d %10.3f"
                .format(regionX, regionY, a.localX, a.movedVertices, a.totalVertices, a.maxDeltaWorld))
        }
        for (a in northEdge.sortedBy { it.localX }) {
            println("  region %d,%d y=0  x=%-8d %6d/%-4d %10.3f"
                .format(regionX, regionY + 1, a.localX, a.movedVertices, a.totalVertices, a.maxDeltaWorld))
        }

        val sRatio = ratio(southEdge)
        val nRatio = ratio(northEdge)
        println()
        println("  south edge displaced %.1f%%, north edge displaced %.1f%%, difference %.1f points"
            .format(sRatio * 100, nRatio * 100, kotlin.math.abs(sRatio - nRatio) * 100))
    }

    private fun ratio(group: List<Audit>): Double {
        val total = group.sumOf { it.totalVertices.toLong() }
        if (total == 0L) return 0.0
        return group.sumOf { it.movedVertices.toLong() }.toDouble() / total
    }

    private fun auditRegion(cache: Cache, regionX: Int, regionY: Int): List<Audit>? {
        if (!MapXteas.isLoaded()) MapXteas.load()
        val terrain = TerrainLoader.loadRegion(cache, regionX, regionY) ?: return null
        val region = RegionLoader.loadRegion(cache, regionX, regionY, useCache = false) ?: return null
        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val tiles = terrain.tiles

        val instances = ObjectRenderer.createInstancesAllPlanes(
            objects = region.objects,
            regionX = regionX,
            regionY = regionY,
            tileHeights = { x, y, p ->
                TerrainLoader.getExtendedTileHeightScaled(tiles, neighbors, p, x, y)
            },
            rsHeightSampler = { x, y, p ->
                TerrainLoader.getExtendedTileHeight(tiles, neighbors, p, x, y)
            }
        ).values.flatten()

        println("[displaceprobe] region $regionX,$regionY: ${region.objects.size} map objects, " +
                "${instances.size} instances")

        val field = VertexDisplacer.HeightField.from(tiles, neighbors)
        val out = ArrayList<Audit>()

        for (obj in instances) {
            if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) continue
            val meshes = loadMeshes(cache, obj) ?: continue
            if (meshes.isEmpty()) continue

            val def = obj.definition
            val bounds = VertexDisplacer.modelBounds(obj.cachedCombinedModelId, meshes) ?: continue
            val flat = VertexDisplacer.footprintIsFlat(
                bounds = bounds,
                sceneX = obj.rsSceneX,
                sceneY = obj.rsSceneY,
                averageHeight = obj.rsAverageHeight,
                plane = obj.mapObject.level,
                field = field,
                rsRotation = obj.rsRotation,
                mirrored = obj.mirrored,
                resizeX = def.modelSizeX,
                resizeY = def.modelSizeZ,
                resizeZ = def.modelSizeY,
                rsOffsetX = def.offsetX,
                rsOffsetZ = def.offsetY
            )

            var total = 0
            var moved = 0
            var maxDelta = 0f
            for (mesh in meshes) {
                total += mesh.positions.size / 3
                val displaced = VertexDisplacer.displaceVertices(
                    meshData = mesh,
                    sceneX = obj.rsSceneX,
                    sceneY = obj.rsSceneY,
                    averageHeight = obj.rsAverageHeight,
                    plane = obj.mapObject.level,
                    field = field,
                    rsRotation = obj.rsRotation,
                    mirrored = obj.mirrored,
                    resizeX = def.modelSizeX,
                    resizeY = def.modelSizeZ,
                    resizeZ = def.modelSizeY,
                    rsOffsetX = def.offsetX,
                    rsOffsetZ = def.offsetY
                ) ?: continue
                var i = 1
                while (i < mesh.positions.size) {
                    val delta = displaced.positions[i] - mesh.positions[i]
                    if (delta != 0f) {
                        moved++
                        val magnitude = if (delta < 0f) -delta else delta
                        if (magnitude > maxDelta) maxDelta = magnitude
                    }
                    i += 3
                }
            }

            val localX = obj.mapObject.x - regionX * 64
            val localY = obj.mapObject.y - regionY * 64
            val rejected = countRejectedVertices(obj, meshes, field, def.modelSizeX, def.modelSizeY,
                def.offsetX, def.offsetY)
            out.add(Audit(
                obj = obj,
                localX = localX,
                localY = localY,
                flat = flat,
                totalVertices = total,
                movedVertices = moved,
                rejectedByUnpadded = rejected.first,
                rejectedByPadded = rejected.second,
                maxDeltaWorld = maxDelta * def.modelSizeZ / RESIZE_UNIT,
                footprintRelief = footprintRelief(tiles, neighbors, obj.mapObject.level, localX, localY)
            ))
        }
        return out
    }

    private fun countRejectedVertices(
        obj: ObjectInstance,
        meshes: List<MeshData>,
        field: VertexDisplacer.HeightField,
        resizeX: Int,
        resizeZ: Int,
        rsOffsetX: Int,
        rsOffsetZ: Int
    ): Pair<Int, Int> {
        val mirrorSign = if (obj.mirrored) -1 else 1
        val factorXX: Int
        val factorXZ: Int
        val factorZX: Int
        val factorZZ: Int
        when (obj.rsRotation and 3) {
            1 -> { factorXX = 0; factorXZ = resizeX * mirrorSign; factorZX = -resizeZ; factorZZ = 0 }
            2 -> { factorXX = -resizeX; factorXZ = 0; factorZX = 0; factorZZ = -resizeZ * mirrorSign }
            3 -> { factorXX = 0; factorXZ = -resizeX * mirrorSign; factorZX = resizeZ; factorZZ = 0 }
            else -> { factorXX = resizeX; factorXZ = 0; factorZX = 0; factorZZ = resizeZ * mirrorSign }
        }
        val baseX = rsOffsetX + obj.rsSceneX
        val baseZ = rsOffsetZ + obj.rsSceneY

        val pad = field.rsOrigin
        val maxPaddedX = field.width - 1
        val maxPaddedZ = field.depth - 1

        var rejectedUnpadded = 0
        var rejectedPadded = 0
        for (mesh in meshes) {
            val positions = mesh.positions
            var idx = 0
            while (idx < positions.size) {
                val rsX = (positions[idx] * INV_SCALE).toInt()
                val rsZ = (-positions[idx + 2] * INV_SCALE).toInt()
                val localX = (((factorXX * rsX) + (factorXZ * rsZ)) shr 7) + baseX
                val localZ = (((factorZX * rsX) + (factorZZ * rsZ)) shr 7) + baseZ

                val tileX = localX shr TILE_SCALE
                val tileZ = localZ shr TILE_SCALE
                if (tileX < 0 || tileX >= 63 || tileZ < 0 || tileZ >= 63) rejectedUnpadded++

                val paddedX = (localX + pad) shr TILE_SCALE
                val paddedZ = (localZ + pad) shr TILE_SCALE
                if (paddedX < 0 || paddedX >= maxPaddedX || paddedZ < 0 || paddedZ >= maxPaddedZ) {
                    rejectedPadded++
                }
                idx += 3
            }
        }
        return Pair(rejectedUnpadded, rejectedPadded)
    }

    private fun footprintRelief(
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        plane: Int,
        localX: Int,
        localY: Int
    ): Int {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (dx in 0..1) {
            for (dy in 0..1) {
                val h = TerrainLoader.getExtendedTileHeight(tiles, neighbors, plane, localX + dx, localY + dy)
                if (h < min) min = h
                if (h > max) max = h
            }
        }
        return max - min
    }

    private fun loadMeshes(cache: Cache, obj: ObjectInstance): List<MeshData>? =
        if (obj.modifications.hasModifications()) {
            ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
        } else {
            ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
        }
}
