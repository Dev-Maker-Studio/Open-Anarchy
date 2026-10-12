package world.gregs.voidps.cache.definition.data

data class BillboardData(
    val billboardId: Int,
    val appliesOnFace: Int,
    val depth: Int,
    val distance: Int
)

data class ParticleEmitterAttachment(
    val particleId: Int,
    val appliesOnFace: Int
)

data class ParticleEffectorAttachment(
    val effectorId: Int,
    val appliesOnVertex: Int
)

data class ModelDefinition(
    val id: Int,
    val version: Int,
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
    val faceTextures: ShortArray?,
    val faceTexturePointers: ByteArray?,
    val textureTriangleCount: Int,
    val texturesMapping: ByteArray?,
    val textureFaceX: ShortArray?,
    val textureFaceY: ShortArray?,
    val textureFaceZ: ShortArray?,
    val textureCoordU: FloatArray?,
    val textureCoordV: FloatArray?,
    val vertexNormalsX: IntArray?,
    val vertexNormalsY: IntArray?,
    val vertexNormalsZ: IntArray?,
    val texturesScaleX: IntArray?,
    val texturesScaleY: IntArray?,
    val texturesScaleZ: IntArray?,
    val texturesRotation: ByteArray?,
    val texturesDirection: ByteArray?,
    val texturesSpeed: IntArray?,
    val texturesUtrans: IntArray?,
    val texturesVtrans: IntArray?,
    val billboards: Array<BillboardData>? = null,


    val newFormat: Boolean = true,
    val footerFlags: Int = 0,
    val modelPriority: Int = 0,
    val alphaFlag: Int = 0,
    val faceSkinFlag: Int = 0,
    val textureFlag: Int = 0,
    val vertexSkinFlag: Int = 0,
    val vertexFlags: ByteArray? = null,
    val faceIndexTypes: ByteArray? = null,
    val faceSkins: IntArray? = null,
    val vertexSkins: IntArray? = null,
    val unusedTexturePointers: ByteArray? = null,
    val particleEmitters: Array<ParticleEmitterAttachment>? = null,
    val particleEffectors: Array<ParticleEffectorAttachment>? = null,
    val trailing: ByteArray? = null
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
            vertexNormalsZ = null,
            texturesScaleX = null,
            texturesScaleY = null,
            texturesScaleZ = null,
            texturesRotation = null,
            texturesDirection = null,
            texturesSpeed = null,
            texturesUtrans = null,
            texturesVtrans = null,
            billboards = null
        )

        fun combine(models: List<ModelDefinition>, combinedId: Int): ModelDefinition? {
            if (models.isEmpty()) return null
            if (models.size == 1) return models[0]

            var totalVertices = 0
            var totalFaces = 0
            var totalTextures = 0
            var totalComplexTextures = 0
            var totalCubeTextures = 0
            var hasAlpha = false
            var hasRenderTypes = false
            var hasPriorities = false
            var hasFaceTextures = false
            var hasFaceTexturePointers = false
            var hasTexturesMapping = false
            var hasComplexTextureData = false
            var hasCubeTextureData = false
            var totalBillboards = 0
            var maxVersion = 0

            for (model in models) {
                totalVertices += model.vertexCount
                totalFaces += model.faceCount
                totalTextures += model.textureTriangleCount
                if (model.billboards != null) totalBillboards += model.billboards.size
                hasAlpha = hasAlpha || model.faceAlphas != null
                hasRenderTypes = hasRenderTypes || model.faceRenderTypes != null
                hasPriorities = hasPriorities || model.facePriorities != null
                hasFaceTextures = hasFaceTextures || model.faceTextures != null
                hasFaceTexturePointers = hasFaceTexturePointers || model.faceTexturePointers != null
                hasTexturesMapping = hasTexturesMapping || model.texturesMapping != null
                hasComplexTextureData = hasComplexTextureData || model.texturesScaleX != null
                hasCubeTextureData = hasCubeTextureData || model.texturesUtrans != null
                if (model.version > maxVersion) maxVersion = model.version

                if (model.texturesMapping != null) {
                    for (t in 0 until model.textureTriangleCount) {
                        val mapping = model.texturesMapping[t].toInt() and 0xff
                        if (mapping in 1..3) totalComplexTextures++
                        if (mapping == 2) totalCubeTextures++
                    }
                }
            }

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
            val combinedTexturesScaleX = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            val combinedTexturesScaleY = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            val combinedTexturesScaleZ = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            val combinedTexturesRotation = if (hasComplexTextureData && totalComplexTextures > 0) ByteArray(totalComplexTextures) else null
            val combinedTexturesDirection = if (hasComplexTextureData && totalComplexTextures > 0) ByteArray(totalComplexTextures) else null
            val combinedTexturesSpeed = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            val combinedTexturesUtrans = if (hasCubeTextureData && totalCubeTextures > 0) IntArray(totalCubeTextures) else null
            val combinedTexturesVtrans = if (hasCubeTextureData && totalCubeTextures > 0) IntArray(totalCubeTextures) else null

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

            val combinedBillboards = if (totalBillboards > 0) {
                Array(totalBillboards) { BillboardData(0, 0, 0, 0) }
            } else null

            var faceOffset = 0
            var textureOffset = 0
            var complexTextureOffset = 0
            var cubeTextureOffset = 0
            var billboardOffset = 0

            for (model in models) {
                val vertexMap = IntArray(model.vertexCount)
                for (v in 0 until model.vertexCount) {
                    vertexMap[v] = weldVertex(model.vertexX[v], model.vertexY[v], model.vertexZ[v])
                }

                if (model.billboards != null) {
                    for (b in model.billboards.indices) {
                        val bb = model.billboards[b]
                        combinedBillboards!![billboardOffset + b] = BillboardData(
                            billboardId = bb.billboardId,
                            appliesOnFace = bb.appliesOnFace + faceOffset,
                            depth = bb.depth,
                            distance = bb.distance
                        )
                    }
                    billboardOffset += model.billboards.size
                }

                for (f in 0 until model.faceCount) {
                    val destIdx = faceOffset + f
                    combinedFaceA[destIdx] = vertexMap[model.faceA[f]]
                    combinedFaceB[destIdx] = vertexMap[model.faceB[f]]
                    combinedFaceC[destIdx] = vertexMap[model.faceC[f]]
                    combinedFaceColors[destIdx] = model.faceColors[f]

                    combinedFaceAlphas?.let { arr ->
                        arr[destIdx] = model.faceAlphas?.get(f) ?: 0
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
                        arr[destIdx] = if (pointer >= 0) (pointer + textureOffset).toByte() else -1
                    }
                }

                var modelComplexIdx = 0
                var modelCubeIdx = 0
                for (t in 0 until model.textureTriangleCount) {
                    val destIdx = textureOffset + t
                    val mapping = model.texturesMapping?.get(t)?.toInt()?.and(0xff) ?: 0
                    combinedTexturesMapping?.let { arr ->
                        arr[destIdx] = mapping.toByte()
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

                    if (mapping in 1..3) {
                        val srcComplexIdx = modelComplexIdx
                        val dstComplexIdx = complexTextureOffset + modelComplexIdx
                        combinedTexturesScaleX?.let { arr ->
                            arr[dstComplexIdx] = model.texturesScaleX?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesScaleY?.let { arr ->
                            arr[dstComplexIdx] = model.texturesScaleY?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesScaleZ?.let { arr ->
                            arr[dstComplexIdx] = model.texturesScaleZ?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesRotation?.let { arr ->
                            arr[dstComplexIdx] = model.texturesRotation?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesDirection?.let { arr ->
                            arr[dstComplexIdx] = model.texturesDirection?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesSpeed?.let { arr ->
                            arr[dstComplexIdx] = model.texturesSpeed?.get(srcComplexIdx) ?: 0
                        }
                        modelComplexIdx++

                        if (mapping == 2) {
                            val srcCubeIdx = modelCubeIdx
                            val dstCubeIdx = cubeTextureOffset + modelCubeIdx
                            combinedTexturesUtrans?.let { arr ->
                                arr[dstCubeIdx] = model.texturesUtrans?.get(srcCubeIdx) ?: 0
                            }
                            combinedTexturesVtrans?.let { arr ->
                                arr[dstCubeIdx] = model.texturesVtrans?.get(srcCubeIdx) ?: 0
                            }
                            modelCubeIdx++
                        }
                    }
                }

                faceOffset += model.faceCount
                textureOffset += model.textureTriangleCount
                complexTextureOffset += modelComplexIdx
                cubeTextureOffset += modelCubeIdx
            }

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
                vertexNormalsZ = null,
                texturesScaleX = combinedTexturesScaleX,
                texturesScaleY = combinedTexturesScaleY,
                texturesScaleZ = combinedTexturesScaleZ,
                texturesRotation = combinedTexturesRotation,
                texturesDirection = combinedTexturesDirection,
                texturesSpeed = combinedTexturesSpeed,
                texturesUtrans = combinedTexturesUtrans,
                texturesVtrans = combinedTexturesVtrans,
                billboards = combinedBillboards
            )
        }
    }
}
