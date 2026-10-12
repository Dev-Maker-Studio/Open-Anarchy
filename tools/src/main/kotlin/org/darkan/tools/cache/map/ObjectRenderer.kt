package org.darkan.tools.cache.map

import org.darkan.core.model.ObjectShape
import org.darkan.core.model.Vars
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.model.ModelModifications
import org.darkan.tools.cache.model.TexturedModel
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition

data class ObjectInstance(
    val mapObject: MapObject,
    val definition: ObjectDefinition,
    val modelIds: List<Int>,
    var worldPosition: Vector3f,
    val rotation: Float,
    val scale: Vector3f,
    val offset: Vector3f = Vector3f(),
    var terrainAlignment: TerrainAligner.AlignmentResult = TerrainAligner.AlignmentResult.NONE,
    val mirrored: Boolean = false,
    var modifications: ModelModifications = ModelModifications.NONE,
    val groundContourType: Int = 0,
    val groundContourModifier: Int = -1,
    val rsSceneX: Int = 0,
    val rsSceneY: Int = 0,
    var rsAverageHeight: Int = 0,
    val rsRotation: Int = mapObject.rotation,
    var cachedCombinedModelId: Int = ModelLoader.getCombinedModelIdWithModifications(modelIds, modifications)
)

object ObjectRenderer {
    private val loadedModels = mutableSetOf<Int>()

    private lateinit var gameCache: Cache

    var groundDecorationEnabled: Boolean = true

    val vars: Vars = Vars().initWithoutSession()

    private val WALL_SHAPES = setOf(
        ObjectShape.WALL_STRAIGHT.id,
        ObjectShape.WALL_DIAGONAL_CORNER.id,
        ObjectShape.WALL_WHOLE_CORNER.id,
        ObjectShape.WALL_STRAIGHT_CORNER.id,
        ObjectShape.WALL_INTERACT.id
    )

    private val WALL_DECORATION_SHAPES = setOf(
        ObjectShape.STRAIGHT_INSIDE_WALL_DEC.id,
        ObjectShape.STRAIGHT_OUSIDE_WALL_DEC.id,
        ObjectShape.DIAGONAL_OUTSIDE_WALL_DEC.id,
        ObjectShape.DIAGONAL_INSIDE_WALL_DEC.id,
        ObjectShape.DIAGONAL_INWALL_DEC.id
    )

    fun loadDefinitions(cache: Cache) {
        gameCache = cache
        Cache.objects
    }

    fun getRawDefinition(objectId: Int): ObjectDefinition? {
        val defs = Cache.objects
        return if (objectId in defs.indices) defs[objectId] else null
    }

    fun getDefinition(objectId: Int): ObjectDefinition? {
        val def = getRawDefinition(objectId) ?: return null
        return resolveTransform(def)
    }

    fun resolveTransform(def: ObjectDefinition): ObjectDefinition? {
        val transforms = def.transforms
        if (transforms == null || transforms.isEmpty()) return def

        val varValue = when {
            def.varbit != -1 -> vars.getVarBit(def.varbit)
            def.varp != -1 -> vars.getVar(def.varp)
            else -> 0
        }

        val transformId = if (varValue >= 0 && varValue < transforms.size - 1) {
            transforms[varValue]
        } else {
            transforms[transforms.size - 1]
        }

        if (transformId == -1) return null

        return getRawDefinition(transformId)
    }

    fun getModelIds(def: ObjectDefinition, shape: Int): List<Int> {
        val modelIds = def.modelIds ?: return emptyList()
        val modelTypes = def.modelTypes

        if (modelTypes == null) return emptyList()

        val targetShapeId = shape.toByte()

        for (i in modelTypes.indices) {
            if (modelTypes[i] == targetShapeId && i < modelIds.size) {
                return modelIds[i].toList()
            }
        }

        if (shape == 10 || shape == 11) {
            for (i in modelTypes.indices) {
                if ((modelTypes[i] == ObjectShape.SCENERY_INTERACT.id.toByte() || modelTypes[i] == ObjectShape.GROUND_INTERACT.id.toByte())
                    && i < modelIds.size) {
                    return modelIds[i].toList()
                }
            }
        }

        return emptyList()
    }

    fun rotationToRadians(rotation: Int): Float {
        return when (rotation and 3) {
            0 -> 0f
            1 -> -Math.PI.toFloat() / 2
            2 -> -Math.PI.toFloat()
            3 -> -Math.PI.toFloat() * 3 / 2
            else -> 0f
        }
    }

    private fun getObjectPositionOffset(
        shape: Int,
        rotation: Int,
        tileSize: Float,
        sizeX: Int = 1,
        sizeY: Int = 1
    ): Pair<Float, Float> {
        return Pair(sizeX * tileSize * 0.5f, -sizeY * tileSize * 0.5f)
    }

    fun getWallDecorationOffset(shape: Int, rotation: Int, decorDisplacement: Int): Vector3f {
        return Vector3f(0f, 0f, 0f)
    }

    fun isWallShape(shape: Int): Boolean {
        return shape in WALL_SHAPES || shape in WALL_DECORATION_SHAPES
    }

    fun shouldRenderGroundDecoration(def: ObjectDefinition): Boolean {
        var hasActions = def.interactive
        if (hasActions == -1) {
            hasActions = 0
            val modelTypes = def.modelTypes
            if (modelTypes != null && modelTypes.size == 1 && modelTypes[0] == ObjectShape.SCENERY_INTERACT.id.toByte()) {
                hasActions = 1
            }
            if (hasActions == 0) {
                val options = def.options
                if (options != null) {
                    for (i in 0..minOf(4, options.size - 1)) {
                        if (options[i] != null) {
                            hasActions = 1
                            break
                        }
                    }
                }
            }
        }

        return hasActions != 0 || def.solid == 1 || def.blocksLand
    }

    fun isGroundDecorationVisible(cache: Cache, modelIds: List<Int>): Boolean {
        for (modelId in modelIds) {
            if (ModelLoader.modelHasTextures(cache, modelId)) return true
        }
        return false
    }

    fun createInstancesAllPlanes(
        objects: List<MapObject>,
        regionX: Int,
        regionY: Int,
        tileHeights: ((Int, Int, Int) -> Float)? = null,
        rsHeightSampler: ((Int, Int, Int) -> Int)? = null
    ): Map<Int, List<ObjectInstance>> {
        val instancesByPlane = mutableMapOf<Int, MutableList<ObjectInstance>>()
        val baseWorldX = regionX * 64
        val baseWorldY = regionY * 64
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        for (obj in objects) {
            val plane = obj.level
            val def = getDefinition(obj.id) ?: continue
            val modelIds = getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !groundDecorationEnabled && !shouldRenderGroundDecoration(def)) continue
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !isGroundDecorationVisible(gameCache, modelIds)) continue

            val localTileX = obj.x - baseWorldX
            val localTileY = obj.y - baseWorldY

            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            val xA: Int
            val xB: Int
            if (localTileX + sizeX <= 64) {
                xA = localTileX + (sizeX shr 1)
                xB = localTileX + ((sizeX + 1) shr 1)
            } else {
                xA = localTileX
                xB = localTileX + 1
            }

            val yA: Int
            val yB: Int
            if (localTileY + sizeY <= 64) {
                yA = localTileY + (sizeY shr 1)
                yB = localTileY + ((sizeY + 1) shr 1)
            } else {
                yA = localTileY
                yB = localTileY + 1
            }

            val x0 = xA
            val x1 = xB
            val y0 = yA
            val y1 = yB

            val terrainHeight = if (tileHeights != null) {
                val h00 = tileHeights.invoke(x0, y0, plane)
                val h10 = tileHeights.invoke(x1, y0, plane)
                val h01 = tileHeights.invoke(x0, y1, plane)
                val h11 = tileHeights.invoke(x1, y1, plane)
                (h00 + h10 + h01 + h11) / 4f
            } else {
                0f
            }

            val contourType = def.contouredGround.toInt()
            val contourMod = def.anInt3023
            val rsSceneX = (localTileX shl 9) + (sizeX shl 8)
            val rsSceneY = (localTileY shl 9) + (sizeY shl 8)
            val rsAvgHeight = if (rsHeightSampler != null) {
                (rsHeightSampler.invoke(x0, y0, plane) +
                    rsHeightSampler.invoke(x1, y0, plane) +
                    rsHeightSampler.invoke(x0, y1, plane) +
                    rsHeightSampler.invoke(x1, y1, plane)) shr 2
            } else {
                0
            }

            val worldTileX = regionX * 64 + localTileX
            val worldTileY = regionY * 64 + localTileY

            val basePosX = worldTileX * tileSize
            val basePosZ = -worldTileY * tileSize

            val (posOffsetX, posOffsetZ) = getObjectPositionOffset(obj.shape, obj.rotation, tileSize, sizeX, sizeY)
            val posX = basePosX + posOffsetX
            val posZ = basePosZ + posOffsetZ

            val posY = terrainHeight

            val position = Vector3f(posX, posY, posZ)

            val scaleX = def.modelSizeX / 128f
            val scaleY = def.modelSizeZ / 128f
            val scaleZ = def.modelSizeY / 128f

            val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
            val defOffsetY = def.offsetZ * CoordinateUtil.DEFAULT_SCALE
            val defOffsetZ = def.offsetY * CoordinateUtil.DEFAULT_SCALE

            val decorOffset = getWallDecorationOffset(obj.shape, obj.rotation, def.offsetMultiplier)

            val terrainAlignment = if (tileHeights != null) {
                TerrainAligner.calculateObjectTilt(
                    localTileX = localTileX,
                    localTileY = localTileY,
                    sizeX = sizeX,
                    sizeY = sizeY,
                    groundContourType = def.contouredGround.toInt(),
                    groundContourModifier = def.anInt3023,
                    averageHeight = terrainHeight,
                    heightSampler = { tx, ty ->
                        val clampedX = tx.coerceIn(0, 63)
                        val clampedY = ty.coerceIn(0, 63)
                        tileHeights.invoke(clampedX, clampedY, plane)
                    }
                )
            } else {
                TerrainAligner.AlignmentResult.NONE
            }

            val baseMirror = def.mirrored

            val modifications = getModifications(def)

            val isWallWholeCorner = obj.shape == ObjectShape.WALL_WHOLE_CORNER.id

            if (isWallWholeCorner) {
                val primaryInstance = ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = true,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                )
                instancesByPlane.getOrPut(plane) { mutableListOf() }.add(primaryInstance)

                val secondaryRotation = (obj.rotation + 1) and 3
                val secondaryInstance = ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(secondaryRotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight,
                    rsRotation = secondaryRotation
                )
                instancesByPlane.getOrPut(plane) { mutableListOf() }.add(secondaryInstance)
            } else {
                val instance = ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = position,
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                )
                instancesByPlane.getOrPut(plane) { mutableListOf() }.add(instance)
            }
        }

        NormalMerger.apply(gameCache, instancesByPlane.values.flatten())

        return instancesByPlane
    }

    fun createInstances(
        objects: List<MapObject>,
        regionX: Int,
        regionY: Int,
        plane: Int,
        tileHeights: ((Int, Int) -> Float)? = null,
        rsHeightSampler: ((Int, Int) -> Int)? = null
    ): List<ObjectInstance> {
        val instances = mutableListOf<ObjectInstance>()
        val baseWorldX = regionX * 64
        val baseWorldY = regionY * 64
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        for (obj in objects) {
            if (obj.level != plane) continue

            val def = getDefinition(obj.id) ?: continue
            val modelIds = getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !groundDecorationEnabled && !shouldRenderGroundDecoration(def)) continue
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !isGroundDecorationVisible(gameCache, modelIds)) continue

            val localTileX = obj.x - baseWorldX
            val localTileY = obj.y - baseWorldY

            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            val xA: Int
            val xB: Int
            if (localTileX + sizeX <= 64) {
                xA = localTileX + (sizeX shr 1)
                xB = localTileX + ((sizeX + 1) shr 1)
            } else {
                xA = localTileX
                xB = localTileX + 1
            }

            val yA: Int
            val yB: Int
            if (localTileY + sizeY <= 64) {
                yA = localTileY + (sizeY shr 1)
                yB = localTileY + ((sizeY + 1) shr 1)
            } else {
                yA = localTileY
                yB = localTileY + 1
            }

            val x0 = xA
            val x1 = xB
            val y0 = yA
            val y1 = yB

            val terrainHeight = if (tileHeights != null) {
                val h00 = tileHeights.invoke(x0, y0)
                val h10 = tileHeights.invoke(x1, y0)
                val h01 = tileHeights.invoke(x0, y1)
                val h11 = tileHeights.invoke(x1, y1)
                (h00 + h10 + h01 + h11) / 4f
            } else {
                0f
            }

            val contourType = def.contouredGround.toInt()
            val contourMod = def.anInt3023
            val rsSceneX = (localTileX shl 9) + (sizeX shl 8)
            val rsSceneY = (localTileY shl 9) + (sizeY shl 8)
            val rsAvgHeight = if (rsHeightSampler != null) {
                (rsHeightSampler.invoke(x0, y0) +
                    rsHeightSampler.invoke(x1, y0) +
                    rsHeightSampler.invoke(x0, y1) +
                    rsHeightSampler.invoke(x1, y1)) shr 2
            } else {
                0
            }

            val worldTileX = regionX * 64 + localTileX
            val worldTileY = regionY * 64 + localTileY

            val basePosX = worldTileX * tileSize
            val basePosZ = -worldTileY * tileSize

            val (posOffsetX, posOffsetZ) = getObjectPositionOffset(obj.shape, obj.rotation, tileSize, sizeX, sizeY)
            val posX = basePosX + posOffsetX
            val posZ = basePosZ + posOffsetZ

            val posY = terrainHeight

            val position = Vector3f(posX, posY, posZ)

            val scaleX = def.modelSizeX / 128f
            val scaleY = def.modelSizeZ / 128f
            val scaleZ = def.modelSizeY / 128f

            val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
            val defOffsetY = def.offsetZ * CoordinateUtil.DEFAULT_SCALE
            val defOffsetZ = def.offsetY * CoordinateUtil.DEFAULT_SCALE

            val decorOffset = getWallDecorationOffset(obj.shape, obj.rotation, def.offsetMultiplier)

            val terrainAlignment = if (tileHeights != null) {
                TerrainAligner.calculateObjectTilt(
                    localTileX = localTileX,
                    localTileY = localTileY,
                    sizeX = sizeX,
                    sizeY = sizeY,
                    groundContourType = def.contouredGround.toInt(),
                    groundContourModifier = def.anInt3023,
                    averageHeight = terrainHeight,
                    heightSampler = { tx, ty ->
                        val clampedX = tx.coerceIn(0, 63)
                        val clampedY = ty.coerceIn(0, 63)
                        tileHeights.invoke(clampedX, clampedY)
                    }
                )
            } else {
                TerrainAligner.AlignmentResult.NONE
            }

            val baseMirror = def.mirrored

            val modifications = getModifications(def)

            val isWallWholeCorner = obj.shape == ObjectShape.WALL_WHOLE_CORNER.id

            if (isWallWholeCorner) {
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = true,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                ))

                val secondaryRotation = (obj.rotation + 1) and 3
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(secondaryRotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight,
                    rsRotation = secondaryRotation
                ))
            } else {
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = position,
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                ))
            }
        }

        NormalMerger.apply(gameCache, instances)

        return instances
    }

    fun registerModels(
        cache: Cache,
        instanceManager: InstanceManager,
        modelIds: Set<Int>
    ) {
        for (modelId in modelIds) {
            if (loadedModels.contains(modelId)) continue

            val texturedModel = ModelLoader.loadTexturedModel(cache, modelId) ?: continue
            val meshDataList = texturedModelToMeshData(texturedModel)

            if (meshDataList.isNotEmpty()) {
                instanceManager.registerModel(modelId, meshDataList)
                loadedModels.add(modelId)
            }
        }
    }

    private fun texturedModelToMeshData(model: TexturedModel): List<MeshData> {
        val result = mutableListOf<MeshData>()

        model.colorBatch?.let { mesh ->
            result.add(MeshData(
                positions = floatArrayOf(),
                colors = floatArrayOf(),
                textureCoords = null,
                normals = floatArrayOf(),
                indices = intArrayOf(),
                textureId = -1
            ))
        }

        for (batch in model.textureBatches) {
            result.add(MeshData(
                positions = floatArrayOf(),
                colors = null,
                textureCoords = floatArrayOf(),
                normals = floatArrayOf(),
                indices = intArrayOf(),
                textureId = batch.textureId
            ))
        }

        return result
    }

    fun addInstances(
        instanceManager: InstanceManager,
        instances: List<ObjectInstance>
    ) {
        val modelMatrix = Matrix4f()

        for (instance in instances) {
            for (modelId in instance.modelIds) {
                if (!instanceManager.isModelRegistered(modelId)) continue

                modelMatrix.identity()
                    .translate(instance.worldPosition)

                if (instance.terrainAlignment.needsAlignment()) {
                    modelMatrix.translate(0f, instance.terrainAlignment.heightAdjustment, 0f)
                }

                modelMatrix
                    .translate(instance.offset)
                    .scale(instance.scale)

                if (instance.terrainAlignment.needsAlignment()) {
                    modelMatrix.rotateZ(instance.terrainAlignment.tiltZ)
                    modelMatrix.rotateX(instance.terrainAlignment.tiltX)
                }

                modelMatrix.rotateY(instance.rotation)

                if (instance.mirrored) {
                    modelMatrix.scale(1f, 1f, -1f)
                }

                instanceManager.addInstance(modelId, modelMatrix)
            }
        }
    }

    fun collectModelIds(objects: List<MapObject>): Set<Int> {
        val modelIds = mutableSetOf<Int>()

        for (obj in objects) {
            val def = getDefinition(obj.id) ?: continue
            val objModelIds = getModelIds(def, obj.shape)
            if (objModelIds.isEmpty()) continue

            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !groundDecorationEnabled && !shouldRenderGroundDecoration(def)) continue
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !isGroundDecorationVisible(gameCache, objModelIds)) continue

            modelIds.addAll(objModelIds)
        }

        return modelIds
    }

    fun clearAll() {
        loadedModels.clear()
    }

    fun getCombinedModelId(modelIds: List<Int>): Int {
        return ModelLoader.getCombinedModelId(modelIds)
    }

    fun getCombinedModelIdWithModifications(modelIds: List<Int>, modifications: ModelModifications): Int {
        return ModelLoader.getCombinedModelIdWithModifications(modelIds, modifications)
    }

    fun getModifications(def: ObjectDefinition): ModelModifications {
        val hasRetexture = def.originalTextureColours != null && def.modifiedTextureColours != null
        val hasRecolor = def.originalColours != null && def.modifiedColours != null
        val adjustedAmbient = ModelModifications.DEFAULT_AMBIENT + def.brightness

        if (!hasRetexture && !hasRecolor && adjustedAmbient == ModelModifications.DEFAULT_AMBIENT) {
            return ModelModifications.NONE
        }

        return ModelModifications(
            retextureSrc = def.originalTextureColours,
            retextureDst = def.modifiedTextureColours,
            recolorSrc = def.originalColours,
            recolorDst = def.modifiedColours,
            ambient = adjustedAmbient
        )
    }
}
