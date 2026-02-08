package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.ShaderProgram
import org.joml.Matrix4f
import world.gregs.voidps.cache.Cache

/**
 * Orchestrates dirty tracking, recomputation, and chunk rebuilding for real-time terrain editing.
 *
 * Ties together [MutableRegionData] (dirty tracking / tile mutation) and [ChunkedTerrain]
 * (chunk mesh management). Editor tools modify [mutableData] directly via its setHeight,
 * setUnderlayId, setOverlayId, and setFlags methods. Each frame, [processEdits] checks
 * for pending changes, recomputes region-wide blending and normals as needed, then
 * rebuilds only the dirty chunks.
 *
 * Typical usage:
 * ```
 * val editManager = TerrainEditManager(regionX, regionY, plane, cache)
 * editManager.init()
 *
 * // In edit tool:
 * editManager.mutableData.setHeight(plane, x, y, newHeight)
 *
 * // In frame loop (before render):
 * val result = editManager.processEdits()
 *
 * // In render pass:
 * editManager.renderColor(shader, modelMatrix)
 * editManager.renderTextured(shader, modelMatrix, ::bindTexture)
 * editManager.renderAlpha(shader, modelMatrix, ::bindTexture)
 * ```
 */
class TerrainEditManager(
    val regionX: Int,
    val regionY: Int,
    var plane: Int,
    private val cache: Cache
) {
    /** Mutable region data with dirty tracking. Editor tools modify this directly. */
    lateinit var mutableData: MutableRegionData
        private set

    private lateinit var chunkedTerrain: ChunkedTerrain

    // Pre-computed region-wide data (recomputed when dirty)
    private lateinit var blendedColors: Array<IntArray>
    private lateinit var normalsX: Array<FloatArray>
    private lateinit var normalsY: Array<FloatArray>
    private lateinit var normalsZ: Array<FloatArray>

    /**
     * Initialize from cache data. Loads the region, ensures underlay/overlay definitions
     * are available, creates a mutable copy, computes blending and normals, and builds
     * all 64 chunks.
     */
    fun init() {
        // Ensure underlay/overlay definitions are loaded for blending computation
        TerrainLoader.loadDefinitions(cache)

        val regionData = TerrainLoader.loadRegion(cache, regionX, regionY)
            ?: error("Failed to load region ($regionX, $regionY)")

        mutableData = MutableRegionData(regionData)
        chunkedTerrain = ChunkedTerrain(regionX, regionY, plane)

        // Compute initial blending and normals
        recomputeBlendingAndNormals()

        // Build all 64 chunks
        chunkedTerrain.buildAll(mutableData.toRegionData(), blendedColors, normalsX, normalsY, normalsZ)
    }

    /**
     * Change the active plane. Cleans up existing chunk meshes, recomputes
     * blending and normals for the new plane, and rebuilds all chunks.
     */
    fun changePlane(newPlane: Int) {
        plane = newPlane
        chunkedTerrain.cleanup()
        chunkedTerrain = ChunkedTerrain(regionX, regionY, plane)
        recomputeBlendingAndNormals()
        chunkedTerrain.buildAll(mutableData.toRegionData(), blendedColors, normalsX, normalsY, normalsZ)
        mutableData.clearDirty()
    }

    /**
     * Call once per frame before rendering. Processes any pending edits:
     * 1. Recomputes blended underlay colors if any underlay was changed
     * 2. Recomputes vertex normals if any height was changed
     * 3. Rebuilds only the dirty chunks
     *
     * @return [EditResult] describing which chunks were rebuilt, for downstream
     *         reactivity (e.g., repositioning objects on changed terrain).
     */
    fun processEdits(): EditResult {
        if (!mutableData.hasDirty()) return EditResult.NONE

        val regionSnapshot = mutableData.toRegionData()

        // 1. Recompute blended underlay colors if underlay changed
        if (mutableData.blendingDirty) {
            blendedColors = TerrainLoader.computeBlendedUnderlayColors(regionSnapshot, plane)
        }

        // 2. Recompute vertex normals if heights changed
        if (mutableData.heightsDirty) {
            val (nx, ny, nz) = TerrainLoader.computeVertexNormals(mutableData.toTileArray(), plane)
            normalsX = nx
            normalsY = ny
            normalsZ = nz
        }

        // 3. Rebuild only dirty chunks
        val dirtyChunks = mutableData.getDirtyChunks()
        chunkedTerrain.rebuildChunks(dirtyChunks, regionSnapshot, blendedColors, normalsX, normalsY, normalsZ)

        // 4. Collect result and reset dirty state
        val result = EditResult(dirtyChunks)
        mutableData.clearDirty()
        return result
    }

    /**
     * Recompute both blended underlay colors and vertex normals from current tile state.
     * Used during init and plane changes when everything must be recalculated.
     */
    private fun recomputeBlendingAndNormals() {
        val regionSnapshot = mutableData.toRegionData()
        blendedColors = TerrainLoader.computeBlendedUnderlayColors(regionSnapshot, plane)
        val (nx, ny, nz) = TerrainLoader.computeVertexNormals(mutableData.toTileArray(), plane)
        normalsX = nx
        normalsY = ny
        normalsZ = nz
    }

    /** Render all non-empty color chunks (untextured terrain). */
    fun renderColor(shader: ShaderProgram, modelMatrix: Matrix4f) {
        chunkedTerrain.renderColor(shader, modelMatrix)
    }

    /** Render all non-empty textured chunks (opaque textured terrain). */
    fun renderTextured(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit) {
        chunkedTerrain.renderTextured(shader, modelMatrix, bindTexture)
    }

    /** Render all non-empty alpha chunks (semi-transparent textured terrain). */
    fun renderAlpha(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit) {
        chunkedTerrain.renderAlpha(shader, modelMatrix, bindTexture)
    }

    /** Release all GPU resources held by chunk meshes. */
    fun cleanup() {
        chunkedTerrain.cleanup()
    }
}

/**
 * Result of processing terrain edits in a single frame.
 * Contains the set of chunk coordinates that were rebuilt, which downstream
 * systems can use to update dependent data (e.g., object Y-positions on
 * modified terrain).
 */
data class EditResult(
    val rebuiltChunks: Set<ChunkCoord>
) {
    /** True if any chunks were rebuilt this frame. */
    val hasChanges: Boolean get() = rebuiltChunks.isNotEmpty()

    companion object {
        /** Sentinel value representing no edits processed. */
        val NONE = EditResult(emptySet())
    }
}
