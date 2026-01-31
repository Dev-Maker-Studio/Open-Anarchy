package org.darkan.tools.cache.model

/**
 * Decoded model data from the RS cache.
 *
 * Note: RS models with version < 13 need their vertices upscaled by 4 to match
 * scene coordinates. The decoder handles this automatically.
 */
data class ModelDefinition(
    val id: Int,
    val version: Int,  // Model format version. Version < 13 vertices are pre-upscaled by decoder.
    val vertexCount: Int,
    val faceCount: Int,
    val vertexX: IntArray,
    val vertexY: IntArray,
    val vertexZ: IntArray,
    val faceA: IntArray,
    val faceB: IntArray,
    val faceC: IntArray,
    val faceColors: ShortArray,
    val faceAlphas: ByteArray?,
    val faceRenderTypes: ByteArray?,
    val facePriorities: ByteArray?,
    /** Material/texture ID for each face (0 = no texture, >0 = material ID + 1) */
    val faceTextures: ShortArray?,
    /** Index into texture triangles for UV projection (-1 = use default UVs) */
    val faceTexturePointers: ByteArray?,
    /** Number of texture triangles defined in this model */
    val textureTriangleCount: Int,
    /** Mapping type for each texture triangle (0=planar, 1-3=cylindrical) */
    val texturesMapping: ByteArray?,
    /** Vertex A index for each texture triangle */
    val textureFaceX: ShortArray?,
    /** Vertex B index for each texture triangle */
    val textureFaceY: ShortArray?,
    /** Vertex C index for each texture triangle */
    val textureFaceZ: ShortArray?,
    val textureCoordU: FloatArray?,
    val textureCoordV: FloatArray?,
    val vertexNormalsX: IntArray?,
    val vertexNormalsY: IntArray?,
    val vertexNormalsZ: IntArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelDefinition
        return id == other.id
    }

    override fun hashCode(): Int = id

    companion object {
        val EMPTY = ModelDefinition(
            id = -1,
            version = 12,
            vertexCount = 0,
            faceCount = 0,
            vertexX = intArrayOf(),
            vertexY = intArrayOf(),
            vertexZ = intArrayOf(),
            faceA = intArrayOf(),
            faceB = intArrayOf(),
            faceC = intArrayOf(),
            faceColors = shortArrayOf(),
            faceAlphas = null,
            faceRenderTypes = null,
            facePriorities = null,
            faceTextures = null,
            faceTexturePointers = null,
            textureTriangleCount = 0,
            texturesMapping = null,
            textureFaceX = null,
            textureFaceY = null,
            textureFaceZ = null,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null
        )

        /**
         * Combine multiple model definitions into a single definition.
         * This matches how the RS client combines multiple models for multi-model objects
         * (e.g., fountains with water + stone base).
         *
         * The combination:
         * - Welds vertices at identical positions (like RS Mesh constructor)
         * - Adjusts face indices to reference welded vertices
         * - Combines face data (colors, alphas, textures, etc.)
         * - Adjusts texture triangle indices
         *
         * @param models List of models to combine
         * @param combinedId ID for the combined model (typically first model's ID or a composite)
         * @return Combined model definition, or null if models list is empty
         */
        fun combine(models: List<ModelDefinition>, combinedId: Int): ModelDefinition? {
            if (models.isEmpty()) return null
            if (models.size == 1) return models[0]

            // First pass: count totals
            var totalVertices = 0
            var totalFaces = 0
            var totalTextures = 0
            var hasAlpha = false
            var hasRenderTypes = false
            var hasPriorities = false
            var hasFaceTextures = false
            var hasFaceTexturePointers = false
            var hasTexturesMapping = false
            var maxVersion = 0

            for (model in models) {
                totalVertices += model.vertexCount
                totalFaces += model.faceCount
                totalTextures += model.textureTriangleCount
                hasAlpha = hasAlpha || model.faceAlphas != null
                hasRenderTypes = hasRenderTypes || model.faceRenderTypes != null
                hasPriorities = hasPriorities || model.facePriorities != null
                hasFaceTextures = hasFaceTextures || model.faceTextures != null
                hasFaceTexturePointers = hasFaceTexturePointers || model.faceTexturePointers != null
                hasTexturesMapping = hasTexturesMapping || model.texturesMapping != null
                if (model.version > maxVersion) maxVersion = model.version
            }

            // Allocate combined arrays
            val combinedVertexX = IntArray(totalVertices)
            val combinedVertexY = IntArray(totalVertices)
            val combinedVertexZ = IntArray(totalVertices)
            val combinedFaceA = IntArray(totalFaces)
            val combinedFaceB = IntArray(totalFaces)
            val combinedFaceC = IntArray(totalFaces)
            val combinedFaceColors = ShortArray(totalFaces)
            val combinedFaceAlphas = if (hasAlpha) ByteArray(totalFaces) else null
            val combinedFaceRenderTypes = if (hasRenderTypes) ByteArray(totalFaces) else null
            val combinedFacePriorities = if (hasPriorities) ByteArray(totalFaces) else null
            val combinedFaceTextures = if (hasFaceTextures) ShortArray(totalFaces) else null
            val combinedFaceTexturePointers = if (hasFaceTexturePointers) ByteArray(totalFaces) else null
            val combinedTexturesMapping = if (hasTexturesMapping && totalTextures > 0) ByteArray(totalTextures) else null
            val combinedTextureFaceX = if (totalTextures > 0) ShortArray(totalTextures) else null
            val combinedTextureFaceY = if (totalTextures > 0) ShortArray(totalTextures) else null
            val combinedTextureFaceZ = if (totalTextures > 0) ShortArray(totalTextures) else null

            // Vertex welding map: (x, y, z) -> welded vertex index
            // This matches the RS client's Mesh.connect() method which welds vertices at identical positions
            val weldedVertices = mutableMapOf<Triple<Int, Int, Int>, Int>()
            val weldedVertexX = mutableListOf<Int>()
            val weldedVertexY = mutableListOf<Int>()
            val weldedVertexZ = mutableListOf<Int>()

            fun weldVertex(x: Int, y: Int, z: Int): Int {
                val key = Triple(x, y, z)
                return weldedVertices.getOrPut(key) {
                    val idx = weldedVertexX.size
                    weldedVertexX.add(x)
                    weldedVertexY.add(y)
                    weldedVertexZ.add(z)
                    idx
                }
            }

            // Second pass: combine data
            var faceOffset = 0
            var textureOffset = 0

            for (model in models) {
                // Build vertex index mapping for this model (old index -> welded index)
                val vertexMap = IntArray(model.vertexCount)
                for (v in 0 until model.vertexCount) {
                    vertexMap[v] = weldVertex(model.vertexX[v], model.vertexY[v], model.vertexZ[v])
                }

                // Copy faces with remapped vertex indices
                for (f in 0 until model.faceCount) {
                    val destIdx = faceOffset + f
                    combinedFaceA[destIdx] = vertexMap[model.faceA[f]]
                    combinedFaceB[destIdx] = vertexMap[model.faceB[f]]
                    combinedFaceC[destIdx] = vertexMap[model.faceC[f]]
                    combinedFaceColors[destIdx] = model.faceColors[f]

                    combinedFaceAlphas?.let { arr ->
                        arr[destIdx] = model.faceAlphas?.get(f) ?: -1
                    }
                    combinedFaceRenderTypes?.let { arr ->
                        arr[destIdx] = model.faceRenderTypes?.get(f) ?: 0
                    }
                    combinedFacePriorities?.let { arr ->
                        arr[destIdx] = model.facePriorities?.get(f) ?: 0
                    }
                    combinedFaceTextures?.let { arr ->
                        arr[destIdx] = model.faceTextures?.get(f) ?: -1
                    }
                    combinedFaceTexturePointers?.let { arr ->
                        val pointer = model.faceTexturePointers?.get(f)?.toInt() ?: -1
                        // Adjust texture pointer to account for combined texture triangle offset
                        arr[destIdx] = if (pointer >= 0) (pointer + textureOffset).toByte() else -1
                    }
                }

                // Copy texture triangles with remapped vertex indices
                // Note: Negative indices (like -32767) are sentinel values meaning "no texture vertex"
                // and should be preserved as-is rather than remapped
                for (t in 0 until model.textureTriangleCount) {
                    val destIdx = textureOffset + t
                    combinedTexturesMapping?.let { arr ->
                        arr[destIdx] = model.texturesMapping?.get(t) ?: 0
                    }
                    combinedTextureFaceX?.let { arr ->
                        val oldIdx = model.textureFaceX?.get(t)?.toInt() ?: 0
                        arr[destIdx] = if (oldIdx >= 0 && oldIdx < model.vertexCount) {
                            vertexMap[oldIdx].toShort()
                        } else {
                            model.textureFaceX?.get(t) ?: 0
                        }
                    }
                    combinedTextureFaceY?.let { arr ->
                        val oldIdx = model.textureFaceY?.get(t)?.toInt() ?: 0
                        arr[destIdx] = if (oldIdx >= 0 && oldIdx < model.vertexCount) {
                            vertexMap[oldIdx].toShort()
                        } else {
                            model.textureFaceY?.get(t) ?: 0
                        }
                    }
                    combinedTextureFaceZ?.let { arr ->
                        val oldIdx = model.textureFaceZ?.get(t)?.toInt() ?: 0
                        arr[destIdx] = if (oldIdx >= 0 && oldIdx < model.vertexCount) {
                            vertexMap[oldIdx].toShort()
                        } else {
                            model.textureFaceZ?.get(t) ?: 0
                        }
                    }
                }

                faceOffset += model.faceCount
                textureOffset += model.textureTriangleCount
            }

            // Create final welded vertex arrays
            val finalVertexX = weldedVertexX.toIntArray()
            val finalVertexY = weldedVertexY.toIntArray()
            val finalVertexZ = weldedVertexZ.toIntArray()

            return ModelDefinition(
                id = combinedId,
                version = maxVersion,
                vertexCount = finalVertexX.size,
                faceCount = totalFaces,
                vertexX = finalVertexX,
                vertexY = finalVertexY,
                vertexZ = finalVertexZ,
                faceA = combinedFaceA,
                faceB = combinedFaceB,
                faceC = combinedFaceC,
                faceColors = combinedFaceColors,
                faceAlphas = combinedFaceAlphas,
                faceRenderTypes = combinedFaceRenderTypes,
                facePriorities = combinedFacePriorities,
                faceTextures = combinedFaceTextures,
                faceTexturePointers = combinedFaceTexturePointers,
                textureTriangleCount = totalTextures,
                texturesMapping = combinedTexturesMapping,
                textureFaceX = combinedTextureFaceX,
                textureFaceY = combinedTextureFaceY,
                textureFaceZ = combinedTextureFaceZ,
                textureCoordU = null,
                textureCoordV = null,
                vertexNormalsX = null,
                vertexNormalsY = null,
                vertexNormalsZ = null
            )
        }
    }
}
