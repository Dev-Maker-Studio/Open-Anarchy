package org.darkan.tools.engine.render

object IndirectReuseStats {

    val ENABLED: Boolean = System.getProperty("darkan.irReuseStats", "false").toBoolean()

    private class ModelSize(val vertices: Int, val indices: Int)

    private class Build {
        val models = HashMap<Int, ModelSize>()
        val materialSizes = HashMap<Int, Int>()
        var vertices = 0L
        var indices = 0L
    }

    private var current: Build? = null

    private var previous: Build? = null

    private val everModels = HashMap<Int, ModelSize>()

    private val everMaterials = HashMap<Int, Int>()

    private var buildIndex = 0

    var vertexBytes: Int = 36

    fun beginBuild() {
        if (!ENABLED) return
        current = Build()
    }

    fun recordModel(modelId: Int, vertices: Int, indices: Int) {
        val build = current ?: return
        val existing = build.models[modelId]
        build.models[modelId] = if (existing == null) ModelSize(vertices, indices)
        else ModelSize(existing.vertices + vertices, existing.indices + indices)
        build.vertices += vertices
        build.indices += indices
    }

    fun recordMaterial(materialId: Int, size: Int) {
        val build = current ?: return
        build.materialSizes[materialId] = size
    }

    fun finishBuild(
        drainMs: Double,
        geometryUploadMs: Double,
        registerMs: Double,
        texCpuMs: Double,
        texUploadMs: Double,
        texMipmapMs: Double,
        texGenMs: Double,
        registerCpuMs: Double,
        geometryUploadCpuMs: Double,
        texArrayCpuMs: Double
    ) {
        val build = current ?: return
        current = null
        buildIndex++

        val prev = previous
        var prevSharedModels = 0
        var prevSharedVertices = 0L
        for ((id, size) in build.models) {
            if (prev != null && prev.models.containsKey(id)) {
                prevSharedModels++
                prevSharedVertices += size.vertices
            }
        }

        var everSharedModels = 0
        var everSharedVertices = 0L
        for ((id, size) in build.models) {
            if (everModels.containsKey(id)) {
                everSharedModels++
                everSharedVertices += size.vertices
            }
        }

        var prevSharedMaterials = 0
        var everSharedMaterials = 0
        val newLayerBytesEver = HashMap<Int, Long>()
        var totalLayerBytes = 0L
        for ((id, size) in build.materialSizes) {
            val bytes = size.toLong() * size * 4
            totalLayerBytes += bytes
            if (prev != null && prev.materialSizes.containsKey(id)) prevSharedMaterials++
            if (everMaterials.containsKey(id)) everSharedMaterials++
            else newLayerBytesEver[size] = (newLayerBytesEver[size] ?: 0L) + bytes
        }

        val geometryBytes = build.vertices * vertexBytes + build.indices * Int.SIZE_BYTES
        val everNewVertices = build.vertices - everSharedVertices
        val everNewLayerBytes = newLayerBytesEver.values.sum()

        val geometryPhaseMs = geometryUploadCpuMs + registerCpuMs
        val geomCeilingMs =
            if (build.vertices > 0) geometryPhaseMs * everSharedVertices / build.vertices else 0.0
        val texPhaseMs = texArrayCpuMs
        val texCeilingMs =
            if (totalLayerBytes > 0) texPhaseMs * (totalLayerBytes - everNewLayerBytes) / totalLayerBytes
            else 0.0
        val geometryWallMs = geometryUploadMs + registerMs
        val texWallMs = texCpuMs + texUploadMs + texMipmapMs

        val sizeBreakdown = build.materialSizes.entries
            .groupingBy { it.value }.eachCount().entries
            .sortedBy { it.key }
            .joinToString { "${it.key}px x${it.value}" }

        println(
            "[IR-REUSE] build=%d models=%d verts=%d geomKiB=%d | vsPrev models=%d/%d (%.0f%%) verts %.0f%% mats=%d/%d | vsEver models=%d/%d (%.0f%%) verts %.0f%% mats=%d/%d"
                .format(
                    buildIndex, build.models.size, build.vertices, geometryBytes / 1024,
                    prevSharedModels, build.models.size,
                    pct(prevSharedModels.toLong(), build.models.size.toLong()),
                    pct(prevSharedVertices, build.vertices),
                    prevSharedMaterials, build.materialSizes.size,
                    everSharedModels, build.models.size,
                    pct(everSharedModels.toLong(), build.models.size.toLong()),
                    pct(everSharedVertices, build.vertices),
                    everSharedMaterials, build.materialSizes.size
                )
        )
        println(
            "[IR-REUSE] build=%d phases: register=%.1f upload=%.1f (%.0f MB/s) texCpu=%.1f texUpload=%.1f (%.0f MB/s) texMipmap=%.1f texGen=%.1f drain=%.1f ms; layers %s = %d KiB, new %d KiB"
                .format(
                    buildIndex, registerMs, geometryUploadMs,
                    rate(geometryBytes, geometryUploadMs),
                    texCpuMs, texUploadMs, rate(totalLayerBytes, texUploadMs),
                    texMipmapMs, texGenMs, drainMs,
                    sizeBreakdown, totalLayerBytes / 1024, everNewLayerBytes / 1024
                )
        )
        println(
            "[IR-REUSE] build=%d cpu: register=%.1f upload=%.1f texArray=%.1f ms (walls %.1f / %.1f)"
                .format(
                    buildIndex, registerCpuMs, geometryUploadCpuMs, texArrayCpuMs,
                    geometryWallMs, texWallMs
                )
        )
        println(
            "[IR-REUSE] build=%d ceiling(cpu): geometry %.1f of %.1f ms, arrayTex %.1f of %.1f ms, total %.1f ms avoidable; genuinely new %d verts / %d KiB layers"
                .format(
                    buildIndex, geomCeilingMs, geometryPhaseMs, texCeilingMs, texPhaseMs,
                    geomCeilingMs + texCeilingMs, everNewVertices, everNewLayerBytes / 1024
                )
        )

        for ((id, size) in build.models) {
            val existing = everModels[id]
            if (existing == null) everModels[id] = size
        }
        for ((id, size) in build.materialSizes) everMaterials.putIfAbsent(id, size)
        previous = build
    }

    private fun pct(part: Long, whole: Long): Double =
        if (whole > 0) 100.0 * part / whole else 0.0

    private fun rate(bytes: Long, ms: Double): Double =
        if (ms > 0) bytes / (ms / 1000.0) / 1e6 else 0.0
}
