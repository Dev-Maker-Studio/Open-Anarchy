package org.darkan.tools.engine.async

import org.darkan.tools.engine.render.BatchKey
import org.darkan.tools.engine.render.MergedGeometry
import org.darkan.tools.engine.render.MeshData

sealed interface GpuUploadTask {
    val priority: Int
    val regionKey: Long
    var cancelled: Boolean
}

data class UploadTerrainMesh(
    override val regionKey: Long,
    override val priority: Int,
    val meshData: Any,
    val plane: Int,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class RegisterModel(
    override val regionKey: Long,
    override val priority: Int,
    val modelId: Int,
    val meshDataList: List<MeshData>,
    val maxInstances: Int = 50000,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class UploadAtlasPage(
    override val regionKey: Long,
    override val priority: Int,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class BuildRegionBatchMeshes(
    override val regionKey: Long,
    override val priority: Int,
    val batchKey: BatchKey,
    val merged: MergedGeometry,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class BuildMergedTerrain(
    override val regionKey: Long,
    override val priority: Int,
    val opaqueData: MergedTerrainData?,
    val alphaData: MergedTerrainData?,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class MergedTerrainData(
    val positions: FloatArray,
    val colors: FloatArray,
    val texCoords: FloatArray,
    val normals: FloatArray,
    val atlasTransforms: FloatArray,
    val indices: IntArray,
    val alphas: FloatArray? = null
)
