package org.darkan.tools.engine.render

object RenderMetrics {


    const val GPU_PASS_CULL = 0
    const val GPU_PASS_TERRAIN_OPAQUE = 1
    const val GPU_PASS_TERRAIN_ALPHA = 2
    const val GPU_PASS_OBJECTS = 3
    const val GPU_PASS_STATIC_BATCH = 4
    const val GPU_PASS_SKY = 5
    const val GPU_PASS_UI = 6

    val GPU_PASS_NAMES = listOf(
        "Cull (compute)",
        "Terrain opaque",
        "Terrain alpha",
        "Objects (MDI)",
        "Static batch",
        "Sky",
        "UI"
    )


    var terrainOpaqueDrawCalls = 0

    var terrainAlphaDrawCalls = 0

    var instancedDrawCalls = 0

    var indirectDrawCalls = 0

    var indirectCommands = 0

    var staticBatchDrawCalls = 0

    var skyDrawCalls = 0

    val totalDrawCalls: Int
        get() = terrainOpaqueDrawCalls + terrainAlphaDrawCalls + instancedDrawCalls + indirectDrawCalls +
                staticBatchDrawCalls + skyDrawCalls


    var trianglesSubmitted = 0

    var instanceCount = 0

    var staticBatchMeshCount = 0

    var droppedInstances = 0

    var droppedModelIds = 0

    var droppedModelsUnavailable = 0

    var staticBatchTriangles = 0


    var cullItemsTotal = 0

    var cullVisibleItemsStale = -1

    var cullVisibleTrianglesStale = -1

    var cullStatsAgeFrames = 0

    var cullActive = false

    var batchesConsidered = 0
    var batchesCulled = 0

    var batchRangesConsidered = 0
    var batchRangesCulled = 0
    var staticBatchRanges = 0

    var batchRegionsConsidered = 0
    var batchRegionsCulled = 0

    var contextRegionsConsidered = 0
    var contextRegionsCulled = 0

    var terrainMergedRegionsTotal = 0
    var terrainMergedRegionsDrawn = 0

    var terrainAtlasOverflowDrawCalls = 0

    var cullCpuTimeMs = 0.0


    var probeObjectsTotal = 0
    var probeObjectsVisible = 0
    var probeTrianglesTotal = 0L
    var probeTrianglesVisible = 0L

    var probeCpuMs = 0.0

    val PROBE_DISTANCE_EDGES_TILES = intArrayOf(16, 32, 48, 64, 96, 128, 192, Int.MAX_VALUE)

    val probeTrianglesByDistance = LongArray(PROBE_DISTANCE_EDGES_TILES.size)

    val probeObjectsByDistance = IntArray(PROBE_DISTANCE_EDGES_TILES.size)

    val PROBE_PIXEL_EDGES = intArrayOf(1, 2, 4, 8, 16, 32, 64, Int.MAX_VALUE)

    val probeTrianglesByPixels = LongArray(PROBE_PIXEL_EDGES.size)

    val probeObjectsByPixels = IntArray(PROBE_PIXEL_EDGES.size)

    val PROBE_SIZE_EDGES = intArrayOf(16, 64, 256, 1024, 4096, Int.MAX_VALUE)

    val probeObjectsBySize = IntArray(PROBE_SIZE_EDGES.size)

    val probeTrianglesBySize = LongArray(PROBE_SIZE_EDGES.size)


    var transformTimeMs = 0.0

    var uploadTimeMs = 0.0

    var renderTimeMs = 0.0

    var skyTimeMs = 0.0

    var presentTimeMs = 0.0

    var gpuSyncWaitMs = 0.0


    const val CPU_PHASE_UNIFORMS = 0
    const val CPU_PHASE_CULL = 1
    const val CPU_PHASE_OBJECT_SCENE = 2
    const val CPU_PHASE_TERRAIN_MERGED = 3
    const val CPU_PHASE_TERRAIN_REGIONS = 4
    const val CPU_PHASE_OVERLAYS = 5
    const val CPU_PHASE_OBJECTS = 6
    const val CPU_PHASE_STATIC_BATCH = 7
    const val CPU_PHASE_SKY = 8

    val CPU_PHASE_NAMES = listOf(
        "uniforms",
        "cull",
        "objectScene",
        "terrainMerged",
        "terrainRegions",
        "overlays",
        "objects",
        "staticBatch",
        "sky"
    )

    val cpuPhaseMs = DoubleArray(CPU_PHASE_NAMES.size)

    fun endPhase(phase: Int, startNanos: Long) {
        cpuPhaseMs[phase] += (System.nanoTime() - startNanos) / 1_000_000.0
    }


    val gpuPassMs = DoubleArray(GPU_PASS_NAMES.size)

    val gpuTotalMs: Double get() = gpuPassMs.sum()

    var gpuTimingActive = false


    var terrainLoadTimeMs = 0.0

    var objectLoadTimeMs = 0.0


    var terrainAtlasTextures = 0

    var terrainAtlasPages = 0

    var terrainAtlasUtilization = 0f

    var objectAtlasTextures = 0

    var objectAtlasPages = 0

    var objectAtlasUtilization = 0f


    var gpuUploadDrainTimeMs = 0.0

    var gpuUploadTasksProcessed = 0

    var pendingGpuTasks = 0

    var regionsLoading = 0

    var regionsReady = 0


    fun resetFrame() {
        terrainOpaqueDrawCalls = 0
        terrainAlphaDrawCalls = 0
        instancedDrawCalls = 0
        indirectDrawCalls = 0
        indirectCommands = 0
        staticBatchDrawCalls = 0
        skyDrawCalls = 0
        trianglesSubmitted = 0
        instanceCount = 0
        staticBatchMeshCount = 0
        staticBatchTriangles = 0
        batchesConsidered = 0
        batchesCulled = 0
        batchRangesConsidered = 0
        batchRangesCulled = 0
        staticBatchRanges = 0
        batchRegionsConsidered = 0
        batchRegionsCulled = 0
        contextRegionsConsidered = 0
        contextRegionsCulled = 0
        terrainMergedRegionsTotal = 0
        terrainMergedRegionsDrawn = 0
        terrainAtlasOverflowDrawCalls = 0
        cullCpuTimeMs = 0.0
        probeObjectsTotal = 0
        probeObjectsVisible = 0
        probeTrianglesTotal = 0L
        probeTrianglesVisible = 0L
        probeCpuMs = 0.0
        probeTrianglesByDistance.fill(0L)
        probeObjectsByDistance.fill(0)
        probeObjectsBySize.fill(0)
        probeTrianglesBySize.fill(0L)
        probeObjectsByPixels.fill(0)
        probeTrianglesByPixels.fill(0L)
        transformTimeMs = 0.0
        uploadTimeMs = 0.0
        renderTimeMs = 0.0
        skyTimeMs = 0.0
        gpuUploadDrainTimeMs = 0.0
        gpuUploadTasksProcessed = 0
        cpuPhaseMs.fill(0.0)
    }

    fun summary(fps: Int): String = buildString {
        appendLine("---- RenderMetrics ----")
        appendLine("fps=$fps  cpuRender=%.3fms cpuTransform=%.3fms cpuUpload=%.3fms cpuCull=%.3fms present=%.3fms gpuSyncWait=%.3fms"
            .format(renderTimeMs, transformTimeMs, uploadTimeMs, cullCpuTimeMs, presentTimeMs, gpuSyncWaitMs))
        appendLine("draws: terrainOpaque=$terrainOpaqueDrawCalls terrainAlpha=$terrainAlphaDrawCalls " +
                "indirect=$indirectDrawCalls (cmds=$indirectCommands) instanced=$instancedDrawCalls " +
                "staticBatch=$staticBatchDrawCalls sky=$skyDrawCalls total=$totalDrawCalls")
        appendLine("geometry: triangles=$trianglesSubmitted batchTriangles=$staticBatchTriangles " +
                "batchRanges=$staticBatchRanges instances=$instanceCount batchMeshes=$staticBatchMeshCount")
        appendLine("terrain: mergedRegions=$terrainMergedRegionsDrawn/$terrainMergedRegionsTotal " +
                "atlasOverflowDraws=$terrainAtlasOverflowDrawCalls")
        appendLine("cull: active=$cullActive items=$cullVisibleItemsStale/$cullItemsTotal " +
                "visibleTris=$cullVisibleTrianglesStale (stale ${cullStatsAgeFrames}f) " +
                "regions=${contextRegionsConsidered - contextRegionsCulled}/$contextRegionsConsidered " +
                "batches=${batchesConsidered - batchesCulled}/$batchesConsidered " +
                "ranges=${batchRangesConsidered - batchRangesCulled}/$batchRangesConsidered " +
                "batchRegions=${batchRegionsConsidered - batchRegionsCulled}/$batchRegionsConsidered")
        append("cpu: ")
        for (phase in CPU_PHASE_NAMES.indices) {
            append("%s=%.3f  ".format(CPU_PHASE_NAMES[phase], cpuPhaseMs[phase]))
        }
        appendLine("SUM=%.3f ms".format(cpuPhaseMs.sum()))
        if (gpuTimingActive) {
            append("gpu: ")
            for (pass in GPU_PASS_NAMES.indices) {
                append("%s=%.3f  ".format(GPU_PASS_NAMES[pass], gpuPassMs[pass]))
            }
            appendLine("TOTAL=%.3f ms".format(gpuTotalMs))
        } else {
            appendLine("gpu: timing unavailable")
        }
    }

    fun startTiming(): Long = System.nanoTime()

    fun endTiming(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0
}
