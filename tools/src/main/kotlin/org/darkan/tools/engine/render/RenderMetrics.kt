package org.darkan.tools.engine.render

/**
 * Centralized performance counters for the rendering pipeline.
 *
 * Per-frame counters are reset at the start of each frame via [resetFrame].
 * Load-time counters (terrain load time, atlas stats) persist across frames
 * and are updated during region loading.
 *
 * All timing is done with [System.nanoTime] and stored in milliseconds.
 */
object RenderMetrics {

    // ====================================================================
    // Draw call counters (per frame)
    // ====================================================================

    /** Terrain opaque draw calls (atlas path = 1, legacy = many). */
    var terrainOpaqueDrawCalls = 0

    /** Terrain alpha draw calls (atlas path = 1, legacy = many). */
    var terrainAlphaDrawCalls = 0

    /** Object instanced draw calls (center region, via InstanceManager). */
    var instancedDrawCalls = 0

    /** Object multi-draw-indirect calls (center region, via IndirectRenderer). */
    var indirectDrawCalls = 0

    /** Object static batched draw calls (context regions, via StaticBatcher). */
    var staticBatchDrawCalls = 0

    /** Total draw calls for this frame (sum of the above). */
    val totalDrawCalls: Int
        get() = terrainOpaqueDrawCalls + terrainAlphaDrawCalls + instancedDrawCalls + indirectDrawCalls + staticBatchDrawCalls

    // ====================================================================
    // Geometry counters (per frame)
    // ====================================================================

    /** Total triangles submitted to the GPU this frame. */
    var trianglesSubmitted = 0

    /** Number of object instances submitted to the GPU this frame (center region). */
    var instanceCount = 0

    /** Number of static batch meshes rendered (context regions). */
    var staticBatchMeshCount = 0

    // ====================================================================
    // Timing counters (per frame, in milliseconds)
    // ====================================================================

    /** Time spent computing object transforms (ms), per frame. */
    var transformTimeMs = 0.0

    /** Time spent uploading instance data to the GPU (ms), per frame. */
    var uploadTimeMs = 0.0

    /** Total render time for the frame (ms). */
    var renderTimeMs = 0.0

    // ====================================================================
    // Load-time counters (persist across frames, updated on region load)
    // ====================================================================

    /** Time to generate terrain meshes during the most recent region load (ms). */
    var terrainLoadTimeMs = 0.0

    /** Time to load and register objects during the most recent region load (ms). */
    var objectLoadTimeMs = 0.0

    // ====================================================================
    // Atlas stats (persist across frames, updated on region load)
    // ====================================================================

    /** Terrain atlas: number of textures packed. */
    var terrainAtlasTextures = 0

    /** Terrain atlas: number of pages used. */
    var terrainAtlasPages = 0

    /** Terrain atlas: utilization percentage (0-100). */
    var terrainAtlasUtilization = 0f

    /** Object atlas: number of textures packed. */
    var objectAtlasTextures = 0

    /** Object atlas: number of pages used. */
    var objectAtlasPages = 0

    /** Object atlas: utilization percentage (0-100). */
    var objectAtlasUtilization = 0f

    // ====================================================================
    // Async loading counters (per frame)
    // ====================================================================

    /** Time spent draining the GPU upload queue this frame (ms). */
    var gpuUploadDrainTimeMs = 0.0

    /** Number of GPU upload tasks processed this frame. */
    var gpuUploadTasksProcessed = 0

    /** Number of pending GPU upload tasks (snapshot). */
    var pendingGpuTasks = 0

    /** Number of regions currently loading in background. */
    var regionsLoading = 0

    /** Number of regions fully loaded and ready for rendering. */
    var regionsReady = 0

    // ====================================================================
    // Methods
    // ====================================================================

    /**
     * Reset all per-frame counters. Call at the start of each frame.
     */
    fun resetFrame() {
        terrainOpaqueDrawCalls = 0
        terrainAlphaDrawCalls = 0
        instancedDrawCalls = 0
        indirectDrawCalls = 0
        staticBatchDrawCalls = 0
        trianglesSubmitted = 0
        instanceCount = 0
        staticBatchMeshCount = 0
        transformTimeMs = 0.0
        uploadTimeMs = 0.0
        renderTimeMs = 0.0
        gpuUploadDrainTimeMs = 0.0
        gpuUploadTasksProcessed = 0
    }

    /**
     * Convenience: start a timing section. Returns the nanoTime.
     */
    fun startTiming(): Long = System.nanoTime()

    /**
     * Convenience: end a timing section and return elapsed milliseconds.
     */
    fun endTiming(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0
}
