package org.darkan.tools.cache.map

import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.render.MeshData

/**
 * Implements per-vertex terrain displacement for ground contour type 1 objects.
 *
 * In the RS client (ModelSM.contourToGround, type 1), each vertex's Y coordinate
 * is individually adjusted to follow the terrain height at that vertex's XZ position.
 * This is different from type 3 (hillChange) which rotates the entire model.
 *
 * Type 1 displacement cannot be done via GPU instancing because each instance needs
 * unique vertex data. Instead, this class creates modified MeshData copies with
 * displaced vertices for each object instance.
 *
 * IMPORTANT: In the RS client, contourToGround is called AFTER the model has been
 * rotated, scaled, and translated in getStationaryModel. The order is:
 *   1. mirror (negate Z if inverted)
 *   2. rotateWithNormals (rotation 0-3 applied to X/Z vertex data)
 *   3. scale (resizeX/Y/Z applied to vertices, >> 7)
 *   4. translate (offsetX/Y/Z added to vertices)
 *   5. contourToGround (operates on fully-transformed vertices)
 *
 * Since our renderer stores raw model vertices and applies transforms via GPU matrix,
 * the VertexDisplacer must replicate the client's pre-displacement transforms on the
 * XZ coordinates for correct terrain height lookup. The Y displacement delta is
 * computed to account for the GPU scale factor.
 *
 * Algorithm (from ModelSM.contourToGround in darkan-bot):
 * 1. For each vertex, compute its absolute scene position: sceneX + transformedX, sceneY + transformedZ
 * 2. Find which tile the vertex is in and the sub-tile remainder
 * 3. Bilinearly interpolate terrain height at the vertex position
 * 4. New vertexY = interpolatedHeight + transformedY - averageHeight
 *
 * RS coordinate system: X = east, Y = height (positive DOWN), Z = north
 * RS tile: 512 units, tileScale = 9, tileMask = 511
 */
object VertexDisplacer {

    /** RS units per tile */
    private const val TILE_UNITS = 512

    /** Bit shift for tile scale (2^9 = 512) */
    private const val TILE_SCALE = 9

    /** Bitmask for sub-tile position (512 - 1 = 511) */
    private const val TILE_MASK = TILE_UNITS - 1

    /** Inverse scale for converting OpenGL coords back to RS */
    private const val INV_SCALE = 128f  // 1 / DEFAULT_SCALE

    /**
     * Create displaced MeshData for a contourType 1 object.
     *
     * Takes the original model MeshData (in OpenGL coordinates) and displaces
     * each vertex's Y coordinate based on bilinear interpolation of terrain
     * heights at the vertex's world XZ position.
     *
     * The vertex XZ positions are transformed by the object's mirror, rotation,
     * scale, and offset to match the RS client's behavior where contourToGround
     * operates on fully-transformed vertex data.
     *
     * The Y displacement delta is computed as (averageHeight - interpolatedHeight) / resizeY
     * to account for the GPU scale factor that will be applied during rendering.
     *
     * @param meshData The original model mesh data (OpenGL coordinates, model-local space)
     * @param sceneX Object center X position in region-local RS scene units
     *               (localTileX * 512 + sizeX * 256)
     * @param sceneY Object center Y position in region-local RS scene units
     *               (localTileY * 512 + sizeY * 256)
     * @param averageHeight Average terrain height at object center in RS units
     * @param plane Terrain plane (0-3)
     * @param tiles Terrain tile data array [plane][x][y] containing height values
     * @param regionBaseX Unused, kept for API clarity (scene coords are already region-local)
     * @param regionBaseY Unused, kept for API clarity (scene coords are already region-local)
     * @param rsRotation Object rotation (0-3) matching RS client rotation values.
     *                   Applied as Y-axis rotation to vertex X/Z before terrain lookup.
     *                   Rotation 0=none, 1=90deg CCW, 2=180deg, 3=270deg CCW (in RS coords).
     * @param mirrored Whether the model is mirrored (negates Z before rotation).
     *                 From ObjectDefinition.inverted or WALL_WHOLE_CORNER primary wall.
     * @param resizeX RS X scale factor (128 = 1.0). From ObjectDefinition.resizeX.
     * @param resizeY RS Y scale factor (128 = 1.0). From ObjectDefinition.resizeY.
     *                Used to compensate for GPU scale on the displacement delta.
     * @param resizeZ RS Z scale factor (128 = 1.0). From ObjectDefinition.resizeZ.
     * @param rsOffsetX RS X translation offset. From ObjectDefinition.offsetX.
     * @param rsOffsetZ RS Z translation offset. From ObjectDefinition.offsetZ.
     * @return New MeshData with displaced vertex Y positions, or null if displacement
     *         produces no change (flat terrain under object)
     */
    @Suppress("UNUSED_PARAMETER")
    fun displaceVertices(
        meshData: MeshData,
        sceneX: Int,
        sceneY: Int,
        averageHeight: Int,
        plane: Int,
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        regionBaseX: Int,
        regionBaseY: Int,
        rsRotation: Int = 0,
        mirrored: Boolean = false,
        resizeX: Int = 128,
        resizeY: Int = 128,
        resizeZ: Int = 128,
        rsOffsetX: Int = 0,
        rsOffsetZ: Int = 0
    ): MeshData? {
        val positions = meshData.positions
        if (positions.isEmpty()) return null

        val vertexCount = positions.size / 3
        val newPositions = positions.copyOf()
        var anyChanged = false

        // The sceneX/sceneY parameters are region-local RS scene coordinates:
        //   sceneX = (localTileX << 9) + (sizeX << 8)
        //   sceneY = (localTileY << 9) + (sizeY << 8)
        //
        // After applying mirror/rotate/scale/translate to model-local vertex coords
        // and adding to sceneX/sceneY, the result is region-local RS scene coords.
        // Shifting right by tileScale (9) gives the local tile index (0-63).

        // Width of tile height array
        val tileWidth = tiles[plane].size
        val tileHeight = if (tileWidth > 0) tiles[plane][0].size else 0

        // Pre-compute rotation index (mask to 0-3)
        val rot = rsRotation and 3

        for (v in 0 until vertexCount) {
            val idx = v * 3

            // Current OpenGL vertex position (model-local space, before any transforms)
            val oglX = newPositions[idx]      // OpenGL X = RS X * scale
            val oglY = newPositions[idx + 1]  // OpenGL Y = -RS Y * scale
            val oglZ = newPositions[idx + 2]  // OpenGL Z = -RS Z * scale

            // Convert OpenGL model-local coords back to RS model-local coords
            val rsX = (oglX * INV_SCALE).toInt()
            var rsZ = (-oglZ * INV_SCALE).toInt()  // Negate to reverse the Z flip

            // Apply the same transforms the RS client applies in getStationaryModel
            // before calling contourToGround. Order: mirror -> rotate -> scale -> translate.

            // 1. Mirror: negate Z if the model is inverted
            // RS client: model.mirror() negates verticesZ
            if (mirrored) {
                rsZ = -rsZ
            }

            // 2. Rotate: apply Y-axis rotation to X/Z
            // RS client rotate90DegWithNormals: newX = Z, newZ = -X
            // RS client rotate180DegWithNormals: newX = -X, newZ = -Z
            // RS client rotate270DegWithNormals: newX = -Z, newZ = X
            val rotatedX: Int
            val rotatedZ: Int
            when (rot) {
                1 -> { rotatedX = rsZ; rotatedZ = -rsX }
                2 -> { rotatedX = -rsX; rotatedZ = -rsZ }
                3 -> { rotatedX = -rsZ; rotatedZ = rsX }
                else -> { rotatedX = rsX; rotatedZ = rsZ }
            }

            // 3. Scale: apply resizeX/Z (128 = 1.0, uses >> 7)
            // RS client: verticesX[v] = resizeX * verticesX[v] >> 7
            val scaledX = (resizeX * rotatedX) shr 7
            val scaledZ = (resizeZ * rotatedZ) shr 7

            // 4. Translate: add offset
            // RS client: model.translate(offsetX, offsetY, offsetZ)
            val transformedX = scaledX + rsOffsetX
            val transformedZ = scaledZ + rsOffsetZ

            // Compute region-local scene position of this vertex using transformed coords
            // In RS client: x = sceneX + verticesX[v], z = sceneY + verticesZ[v]
            // At this point verticesX/Z are already rotated, scaled, and translated
            val localX = sceneX + transformedX
            val localZ = sceneY + transformedZ

            // Bounds check: skip vertices outside the terrain data range
            // The client also does this check for vertices beyond maxDepth (line 3320)
            val tileX = localX shr TILE_SCALE
            val tileZ = localZ shr TILE_SCALE

            if (tileX < 0 || tileX >= tileWidth - 1 || tileZ < 0 || tileZ >= tileHeight - 1) {
                // Vertex is outside terrain data, skip displacement
                continue
            }

            // Sub-tile remainders for interpolation
            val remainderX = localX and TILE_MASK
            val remainderZ = localZ and TILE_MASK

            // Get tile heights at the 4 surrounding tile corners (in RS height units)
            val h00 = tiles[plane][tileX][tileZ].height
            val h10 = tiles[plane][tileX + 1][tileZ].height
            val h01 = tiles[plane][tileX][tileZ + 1].height
            val h11 = tiles[plane][tileX + 1][tileZ + 1].height

            // Bilinear interpolation matching RS client exactly:
            // height1 = (h00 * (512 - remainderX) + remainderX * h10) >> 9
            // height2 = ((512 - remainderX) * h01 + remainderX * h11) >> 9
            // interpolated = (height1 * (512 - remainderZ) + height2 * remainderZ) >> 9
            //
            // Note: Kotlin shr/and are infix with lower precedence than +/-/*,
            // so parentheses are used for clarity and correctness.
            val height1 = (h00 * (TILE_UNITS - remainderX) + remainderX * h10) shr TILE_SCALE
            val height2 = ((TILE_UNITS - remainderX) * h01 + remainderX * h11) shr TILE_SCALE
            val interpolatedHeight = (height1 * (TILE_UNITS - remainderZ) + height2 * remainderZ) shr TILE_SCALE

            // Apply displacement delta to the OpenGL Y coordinate.
            //
            // In the RS client, the displacement is:
            //   newTransformedY = interpolatedHeight + transformedY - averageHeight
            //
            // Our terrain heights are stored POSITIVE (inverted from RS negative convention).
            // Higher terrain = larger positive value. Since OpenGL Y is up, the vertex
            // should move UP (more positive) when terrain is higher than average:
            //   delta_ogl = (interpolatedHeight - averageHeight) / resizeY
            //
            // The division by resizeY compensates for the GPU scaleY factor applied
            // during rendering: rendered_Y = posY + scaleY * (oglY + delta)
            val displacementDelta = (interpolatedHeight - averageHeight).toFloat() / resizeY.toFloat()
            val newOglY = oglY + displacementDelta

            if (newOglY != oglY) {
                newPositions[idx + 1] = newOglY
                anyChanged = true
            }
        }

        if (!anyChanged) return null

        return MeshData(
            positions = newPositions,
            colors = meshData.colors,
            textureCoords = meshData.textureCoords,
            normals = meshData.normals,
            indices = meshData.indices,
            textureId = meshData.textureId
        )
    }

    /**
     * Check if an object should have per-vertex displacement applied.
     *
     * This checks:
     * 1. groundContourType must be 1 (full vertex displacement)
     * 2. groundContourModifier must be < 16384 (RS client exemption check)
     *
     * From ObjectType.getPair() in darkan-bot:
     *   if (hasGroundContour && this.goundContourModifier < 16384)
     *
     * @param groundContourType The object's ground contour type
     * @param groundContourModifier The object's ground contour modifier (-1 is default, always < 16384)
     * @return true if per-vertex displacement should be applied
     */
    fun needsDisplacement(groundContourType: Int, groundContourModifier: Int): Boolean {
        return groundContourType == 1 && groundContourModifier < 16384
    }
}
