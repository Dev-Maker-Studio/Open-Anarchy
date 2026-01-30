package org.darkan.tools.cache.map

import org.darkan.core.model.ObjectShape
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.model.TexturedModel
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition

/**
 * Represents a renderable map object instance.
 */
data class ObjectInstance(
    val mapObject: MapObject,
    val definition: ObjectDefinition,
    val modelIds: List<Int>,
    val worldPosition: Vector3f,
    val rotation: Float,  // Radians
    val scale: Vector3f
)

/**
 * Converts MapObjects into renderable instances with proper model selection
 * based on object shape and transforms.
 */
object ObjectRenderer {
    // Cached model meshes
    private val loadedModels = mutableSetOf<Int>()

    /**
     * Load object definitions from the cache.
     * Uses the pre-loaded Cache.objects for efficiency.
     */
    fun loadDefinitions(cache: Cache) {
        // Cache.objects is lazy-loaded, just trigger initialization
        Cache.objects
    }

    /**
     * Get object definition by ID.
     */
    fun getDefinition(objectId: Int): ObjectDefinition? {
        val defs = Cache.objects
        return if (objectId in defs.indices) defs[objectId] else null
    }

    /**
     * Get model IDs for an object based on its shape.
     *
     * The shapes array in ObjectDefinition contains the ObjectShape for each model group.
     * modelIds is an array of IntArrays where each sub-array contains model IDs for a shape.
     *
     * @param def The object definition
     * @param shape The object shape (0-22)
     * @return List of model IDs to render for this object
     */
    fun getModelIds(def: ObjectDefinition, shape: Int): List<Int> {
        val modelIds = def.modelIds ?: return emptyList()
        val shapes = def.shapes

        if (shapes == null) {
            // No shapes array - all models apply to all shapes
            // Return first group's models
            return modelIds.firstOrNull()?.toList() ?: emptyList()
        }

        // Find model group matching this shape
        val targetShape = try {
            ObjectShape.forId(shape)
        } catch (e: IllegalArgumentException) {
            return modelIds.firstOrNull()?.toList() ?: emptyList()
        }

        for (i in shapes.indices) {
            if (shapes[i] == targetShape && i < modelIds.size) {
                return modelIds[i].toList()
            }
        }

        // Fallback: try to find scenery type for shapes 10-11
        if (shape == 10 || shape == 11) {
            for (i in shapes.indices) {
                if ((shapes[i] == ObjectShape.SCENERY_INTERACT || shapes[i] == ObjectShape.GROUND_INTERACT)
                    && i < modelIds.size) {
                    return modelIds[i].toList()
                }
            }
        }

        // Last resort: return first available models
        return modelIds.firstOrNull()?.toList() ?: emptyList()
    }

    /**
     * Convert rotation value (0-3) to radians.
     * RS rotation: 0=south, 1=west, 2=north, 3=east (counter-clockwise)
     */
    fun rotationToRadians(rotation: Int): Float {
        return when (rotation and 3) {
            0 -> 0f                      // South
            1 -> Math.PI.toFloat() / 2   // West (90 degrees CCW)
            2 -> Math.PI.toFloat()       // North (180 degrees)
            3 -> Math.PI.toFloat() * 3/2 // East (270 degrees CCW)
            else -> 0f
        }
    }

    /**
     * Create renderable instances from a list of MapObjects for ALL planes.
     *
     * @param objects List of map objects from RegionLoader
     * @param regionX Region X coordinate (for world position calculation)
     * @param regionY Region Y coordinate
     * @param tileHeights Optional height data for terrain conforming (takes x, y, plane)
     * @return Map of plane -> list of ObjectInstance
     */
    fun createInstancesAllPlanes(
        objects: List<MapObject>,
        regionX: Int,
        regionY: Int,
        tileHeights: ((Int, Int, Int) -> Float)? = null
    ): Map<Int, List<ObjectInstance>> {
        val instancesByPlane = mutableMapOf<Int, MutableList<ObjectInstance>>()

        for (obj in objects) {
            val plane = obj.level
            val def = getDefinition(obj.id) ?: continue
            val modelIds = getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            // Calculate world position
            // obj.x and obj.y are world tile coordinates
            val worldX = obj.x * 512f
            val worldY = obj.y * 512f

            // Get height at this position if available
            val height = tileHeights?.invoke(obj.x, obj.y, plane) ?: 0f

            // Apply definition offsets
            val offsetX = def.offsetX.toFloat()
            val offsetY = def.offsetY.toFloat()
            val offsetZ = def.offsetZ.toFloat()

            // Convert to OpenGL coordinates
            val position = CoordinateUtil.rsToOpenGL(
                (worldX + offsetX).toInt(),
                (height + offsetY).toInt(),
                (worldY + offsetZ).toInt()
            )

            // Calculate scale from definition
            val scaleX = def.resizeX / 128f
            val scaleY = def.resizeY / 128f
            val scaleZ = def.resizeZ / 128f

            val instance = ObjectInstance(
                mapObject = obj,
                definition = def,
                modelIds = modelIds,
                worldPosition = position,
                rotation = rotationToRadians(obj.rotation),
                scale = Vector3f(scaleX, scaleY, scaleZ)
            )

            instancesByPlane.getOrPut(plane) { mutableListOf() }.add(instance)
        }

        return instancesByPlane
    }

    /**
     * Create renderable instances from a list of MapObjects for a specific plane.
     *
     * @param objects List of map objects from RegionLoader
     * @param regionX Region X coordinate (for world position calculation)
     * @param regionY Region Y coordinate
     * @param plane The plane level
     * @param tileHeights Optional height data for terrain conforming
     * @return List of ObjectInstance ready for rendering
     */
    fun createInstances(
        objects: List<MapObject>,
        regionX: Int,
        regionY: Int,
        plane: Int,
        tileHeights: ((Int, Int) -> Float)? = null
    ): List<ObjectInstance> {
        val instances = mutableListOf<ObjectInstance>()

        for (obj in objects) {
            if (obj.level != plane) continue

            val def = getDefinition(obj.id) ?: continue
            val modelIds = getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            // Calculate world position
            // obj.x and obj.y are world tile coordinates
            val worldX = obj.x * 512f
            val worldY = obj.y * 512f

            // Get height at this position if available
            val height = tileHeights?.invoke(obj.x, obj.y) ?: 0f

            // Apply definition offsets
            val offsetX = def.offsetX.toFloat()
            val offsetY = def.offsetY.toFloat()
            val offsetZ = def.offsetZ.toFloat()

            // Convert to OpenGL coordinates
            val position = CoordinateUtil.rsToOpenGL(
                (worldX + offsetX).toInt(),
                (height + offsetY).toInt(),
                (worldY + offsetZ).toInt()
            )

            // Calculate scale from definition
            val scaleX = def.resizeX / 128f
            val scaleY = def.resizeY / 128f
            val scaleZ = def.resizeZ / 128f

            instances.add(ObjectInstance(
                mapObject = obj,
                definition = def,
                modelIds = modelIds,
                worldPosition = position,
                rotation = rotationToRadians(obj.rotation),
                scale = Vector3f(scaleX, scaleY, scaleZ)
            ))
        }

        return instances
    }

    /**
     * Register object models with an InstanceManager for instanced rendering.
     *
     * @param cache The game cache
     * @param instanceManager The instance manager to register with
     * @param modelIds Set of model IDs to register
     */
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

    /**
     * Convert a TexturedModel to MeshData list for instancing.
     */
    private fun texturedModelToMeshData(model: TexturedModel): List<MeshData> {
        val result = mutableListOf<MeshData>()

        // Add color batch if present
        model.colorBatch?.let { mesh ->
            // Extract data from mesh - we need to read it back
            // Since Mesh is a GPU resource, we'll track this differently
            // For now, we'll mark textureId as -1 for untextured
            result.add(MeshData(
                positions = floatArrayOf(), // Will be filled during actual registration
                colors = floatArrayOf(),
                textureCoords = null,
                normals = floatArrayOf(),
                indices = intArrayOf(),
                textureId = -1
            ))
        }

        // Add texture batches
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

    /**
     * Add object instances to an InstanceManager for rendering.
     *
     * @param instanceManager The instance manager
     * @param instances List of ObjectInstance from createInstances()
     */
    fun addInstances(
        instanceManager: InstanceManager,
        instances: List<ObjectInstance>
    ) {
        val modelMatrix = Matrix4f()

        for (instance in instances) {
            for (modelId in instance.modelIds) {
                if (!instanceManager.isModelRegistered(modelId)) continue

                // Build model matrix: translate, rotate, scale
                modelMatrix.identity()
                    .translate(instance.worldPosition)
                    .rotateY(instance.rotation)
                    .scale(instance.scale)

                instanceManager.addInstance(modelId, modelMatrix)
            }
        }
    }

    /**
     * Get all unique model IDs needed for a list of objects.
     */
    fun collectModelIds(objects: List<MapObject>): Set<Int> {
        val modelIds = mutableSetOf<Int>()

        for (obj in objects) {
            val def = getDefinition(obj.id) ?: continue
            modelIds.addAll(getModelIds(def, obj.shape))
        }

        return modelIds
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        loadedModels.clear()
    }
}
