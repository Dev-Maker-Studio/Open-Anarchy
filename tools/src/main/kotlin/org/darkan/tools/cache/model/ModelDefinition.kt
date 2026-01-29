package org.darkan.tools.cache.model

/**
 * Decoded model data from the RS cache.
 */
data class ModelDefinition(
    val id: Int,
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
    }
}
