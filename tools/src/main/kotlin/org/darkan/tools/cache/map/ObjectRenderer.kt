package org.darkan.tools.cache.map

import org.darkan.core.model.ObjectShape
import org.darkan.core.model.Vars
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
 *
 * The offset field contains the definition's model offset (offsetX, offsetY, offsetZ)
 * converted to OpenGL coordinates. These offsets should be applied AFTER rotation
 * to match the client's behavior.
 *
 * Terrain alignment is applied for objects with groundContourType 1, 2, or 3:
 * - Type 1: Full vertex displacement (approximated with rotation)
 * - Type 2: Partial vertex displacement (approximated with rotation)
 * - Type 3: Hill change (exact rotation implementation)
 * All three types cause the object to tilt to match the terrain slope.
 */
data class ObjectInstance(
    val mapObject: MapObject,
    val definition: ObjectDefinition,
    val modelIds: List<Int>,
    val worldPosition: Vector3f,
    val rotation: Float,  // Y-axis rotation in radians
    val scale: Vector3f,
    val offset: Vector3f = Vector3f(),  // Model offset applied after rotation
    val terrainAlignment: TerrainAligner.AlignmentResult = TerrainAligner.AlignmentResult.NONE  // Terrain tilt
)

/**
 * Converts MapObjects into renderable instances with proper model selection
 * based on object shape and transforms.
 *
 * Following the RS client (SceneGraph.addObject), ALL objects are positioned
 * at the CENTER of their tile footprint. For walls (size 1x1), this is the
 * tile center. The wall model geometry extends from this center point to the
 * appropriate tile edge based on rotation.
 */
object ObjectRenderer {
    // Cached model meshes
    private val loadedModels = mutableSetOf<Int>()

    /**
     * Vars instance for object transform resolution.
     *
     * Uses the core engine's Vars class for var/varbit access. Initialized
     * without a session since the map viewer doesn't need network sync.
     *
     * For the map viewer, all vars default to 0, which shows the default
     * (first) transform variant. This can be modified via setVar/setVarBit
     * to see different object states.
     */
    val vars: Vars = Vars().initWithoutSession()

    // Wall shape IDs that need edge positioning
    private val WALL_SHAPES = setOf(
        ObjectShape.WALL_STRAIGHT.id,           // 0
        ObjectShape.WALL_DIAGONAL_CORNER.id,    // 1
        ObjectShape.WALL_WHOLE_CORNER.id,       // 2
        ObjectShape.WALL_STRAIGHT_CORNER.id,    // 3
        ObjectShape.WALL_INTERACT.id            // 9
    )

    // Wall decoration shapes that also use edge positioning
    private val WALL_DECORATION_SHAPES = setOf(
        ObjectShape.STRAIGHT_INSIDE_WALL_DEC.id,    // 4
        ObjectShape.STRAIGHT_OUSIDE_WALL_DEC.id,    // 5
        ObjectShape.DIAGONAL_OUTSIDE_WALL_DEC.id,   // 6
        ObjectShape.DIAGONAL_INSIDE_WALL_DEC.id,    // 7
        ObjectShape.DIAGONAL_INWALL_DEC.id          // 8
    )

    /**
     * Load object definitions from the cache.
     * Uses the pre-loaded Cache.objects for efficiency.
     */
    fun loadDefinitions(cache: Cache) {
        // Cache.objects is lazy-loaded, just trigger initialization
        Cache.objects
    }

    /**
     * Get object definition by ID (raw, without transform resolution).
     */
    fun getRawDefinition(objectId: Int): ObjectDefinition? {
        val defs = Cache.objects
        return if (objectId in defs.indices) defs[objectId] else null
    }

    /**
     * Get object definition by ID, resolving any transforms.
     *
     * Objects can transform based on player variables (varbits/varps).
     * This method resolves the transform using the core engine's var system.
     *
     * @param objectId The object ID to look up
     * @return The resolved ObjectDefinition, or null if the object should not be rendered
     */
    fun getDefinition(objectId: Int): ObjectDefinition? {
        val def = getRawDefinition(objectId) ?: return null
        return resolveTransform(def)
    }

    /**
     * Resolve transform for an object definition.
     *
     * Uses the core engine's varbit/varp system (Cache.varbits, Vars.BIT_MASKS)
     * to resolve object transforms based on variable values.
     *
     * Transform logic (from RS client ObjectType.getMultiLoc):
     * 1. If varbitId != -1, get var value from varbit
     * 2. Else if varpId != -1, get var value from varp
     * 3. If var value is a valid index into transforms array (excluding last element), use that transform ID
     * 4. Otherwise, use the last element of transforms array as fallback
     * 5. If transform ID is -1, the object should not be rendered
     *
     * @param def The object definition (may have transforms)
     * @return The resolved definition, or null if transform says don't render
     */
    fun resolveTransform(def: ObjectDefinition): ObjectDefinition? {
        val transforms = def.transforms
        // No transforms - return original definition
        if (transforms == null || transforms.isEmpty()) return def

        // Get var value using core engine's Vars class
        val varValue = when {
            def.varbit != -1 -> vars.getVarBit(def.varbit)
            def.varp != -1 -> vars.getVar(def.varp)
            else -> 0
        }

        // Resolve transform ID
        // transforms array has N+1 elements where the last is the fallback
        // Valid indices are 0 to transforms.size - 2
        val transformId = if (varValue >= 0 && varValue < transforms.size - 1) {
            transforms[varValue]
        } else {
            // Use last element as fallback
            transforms[transforms.size - 1]
        }

        // Transform ID of -1 means don't render
        if (transformId == -1) return null

        // Look up the transformed definition
        return getRawDefinition(transformId)
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
     *
     * RS rotation (when viewed from above +Y axis):
     * - 0 = facing south (toward RS Z-)
     * - 1 = facing west (toward RS X-)  - 90 degrees CCW from 0
     * - 2 = facing north (toward RS Z+) - 180 degrees from 0
     * - 3 = facing east (toward RS X+)  - 270 degrees CCW from 0
     *
     * Since model coordinates are converted with Z negated (rsToOpenGL),
     * the rotation direction is inverted. RS CCW becomes OpenGL CW.
     * Therefore we use NEGATIVE angles in OpenGL.
     *
     * JOML rotateY uses CCW rotation (looking down -Y toward origin),
     * so negative angles produce CW rotation (matching RS CCW with flipped Z).
     */
    fun rotationToRadians(rotation: Int): Float {
        return when (rotation and 3) {
            0 -> 0f                          // No rotation
            1 -> -Math.PI.toFloat() / 2      // -90 degrees (CW in OpenGL = CCW in RS)
            2 -> -Math.PI.toFloat()          // -180 degrees
            3 -> -Math.PI.toFloat() * 3 / 2  // -270 degrees (or +90 degrees)
            else -> 0f
        }
    }

    /**
     * Calculate the position offset for objects.
     *
     * In the RS client (see SceneGraph.addObject in darkan-bot), ALL objects are
     * positioned at the CENTER of their tile footprint using:
     *   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + locSizeX * 256
     *   sceneY = (baseY << 9) + (locSizeY << 8) = baseY * 512 + locSizeY * 256
     *
     * For walls (size 1x1), this equals: baseX * 512 + 256, baseY * 512 + 256
     * This places them at the CENTER of their tile, not at the corner.
     *
     * The wall model geometry is designed to extend from this center point to the
     * appropriate tile edge based on rotation.
     *
     * @param shape The object shape ID
     * @param rotation The rotation value (0-3)
     * @param tileSize The size of one tile in world units
     * @param sizeX Object size in X direction (tiles)
     * @param sizeY Object size in Y direction (tiles)
     * @return Pair of (offsetX, offsetZ) to add to the base tile position
     */
    private fun getObjectPositionOffset(
        shape: Int,
        rotation: Int,
        tileSize: Float,
        sizeX: Int = 1,
        sizeY: Int = 1
    ): Pair<Float, Float> {
        // ALL objects (including walls) are centered within their tile footprint
        // RS formula: sceneX = baseX * 512 + sizeX * 256
        // In our coords with Z negated: offsetX = sizeX * tileSize / 2, offsetZ = -sizeY * tileSize / 2
        return Pair(sizeX * tileSize * 0.5f, -sizeY * tileSize * 0.5f)
    }

    /**
     * Calculate the decoration displacement offset for wall decorations.
     *
     * NOTE: Wall decoration models in RS are already positioned correctly relative
     * to the tile origin. The decorDisplacement value is used by the client for
     * other purposes (like collision), not for rendering offset.
     *
     * Wall decorations are placed at the same tile position as walls, and the model
     * geometry itself handles the visual offset from the wall surface.
     *
     * @param shape The decoration shape (4-8)
     * @param rotation The rotation value (0-3)
     * @param decorDisplacement The displacement value from object definition (default 64) - currently unused
     * @return Vector3f offset (always zero - model handles its own positioning)
     */
    fun getWallDecorationOffset(shape: Int, rotation: Int, decorDisplacement: Int): org.joml.Vector3f {
        // Wall decoration models are already positioned correctly relative to tile origin
        // No additional offset needed - the model geometry handles visual placement
        return org.joml.Vector3f(0f, 0f, 0f)
    }

    /**
     * Check if an object shape is a wall that needs edge positioning.
     */
    fun isWallShape(shape: Int): Boolean {
        return shape in WALL_SHAPES || shape in WALL_DECORATION_SHAPES
    }

    /**
     * Create renderable instances from a list of MapObjects for ALL planes.
     *
     * The client places objects using the average height of 4 corner tiles
     * at the object's bounding box center (see SceneGraph.addObject in darkan-bot).
     * For objects spanning multiple tiles, it samples the center tiles.
     *
     * Positions are computed in WORLD coordinates (based on regionX, regionY).
     * The tileHeights callback receives LOCAL coordinates (0-63).
     *
     * @param objects List of map objects from RegionLoader
     * @param regionX Region X coordinate (for world position calculation)
     * @param regionY Region Y coordinate
     * @param tileHeights Optional height data callback (takes localX, localY, plane). Returns scaled height.
     * @return Map of plane -> list of ObjectInstance
     */
    fun createInstancesAllPlanes(
        objects: List<MapObject>,
        regionX: Int,
        regionY: Int,
        tileHeights: ((Int, Int, Int) -> Float)? = null
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

            // Convert to local tile coordinates
            val localTileX = obj.x - baseWorldX
            val localTileY = obj.y - baseWorldY

            // Skip if outside region bounds
            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Calculate the 4 corner tile coordinates for height sampling (in local coords)
            // Following client logic: sample center tiles of object bounding box
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

            // Clamp to valid tile range
            val x0 = xA.coerceIn(0, 63)
            val x1 = xB.coerceIn(0, 63)
            val y0 = yA.coerceIn(0, 63)
            val y1 = yB.coerceIn(0, 63)

            // Get average height of the 4 corners (matching client algorithm)
            val terrainHeight = if (tileHeights != null) {
                val h00 = tileHeights.invoke(x0, y0, plane)
                val h10 = tileHeights.invoke(x1, y0, plane)
                val h01 = tileHeights.invoke(x0, y1, plane)
                val h11 = tileHeights.invoke(x1, y1, plane)
                (h00 + h10 + h01 + h11) / 4f
            } else {
                0f
            }

            // Calculate WORLD position
            // World coords: starts at region origin, not (0,0)
            val worldTileX = regionX * 64 + localTileX
            val worldTileY = regionY * 64 + localTileY

            // Position in OpenGL coords
            // RS client places ALL objects at tile CENTER using:
            //   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + sizeX * 256
            val basePosX = worldTileX * tileSize
            val basePosZ = -worldTileY * tileSize

            // Get position offset (centers object within its tile footprint)
            val (posOffsetX, posOffsetZ) = getObjectPositionOffset(obj.shape, obj.rotation, tileSize, sizeX, sizeY)
            val posX = basePosX + posOffsetX
            val posZ = basePosZ + posOffsetZ

            // Y position: terrain height (definition offsets applied separately in transform)
            val posY = terrainHeight

            val position = Vector3f(posX, posY, posZ)

            // Calculate scale from definition (default is 128 = 1.0 scale)
            val scaleX = def.resizeX / 128f
            val scaleY = def.resizeY / 128f
            val scaleZ = def.resizeZ / 128f

            // Definition offsets (already in RS units * 4, convert to OpenGL units)
            // Applied AFTER rotation in the client (in world space)
            val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
            val defOffsetY = def.offsetY * CoordinateUtil.DEFAULT_SCALE
            val defOffsetZ = def.offsetZ * CoordinateUtil.DEFAULT_SCALE

            // Calculate wall decoration offset if applicable
            val decorOffset = getWallDecorationOffset(obj.shape, obj.rotation, def.decorDisplacement)

            // Calculate terrain alignment for objects with groundContourType 1, 2, or 3
            // Must pass the same averageHeight that the object is positioned at
            val terrainAlignment = if (tileHeights != null) {
                TerrainAligner.calculateObjectTilt(
                    localTileX = localTileX,
                    localTileY = localTileY,
                    sizeX = sizeX,
                    sizeY = sizeY,
                    groundContourType = def.groundContourType.toInt(),
                    groundContourModifier = def.groundContourModifier,
                    averageHeight = terrainHeight,  // Pass the same height object is positioned at
                    heightSampler = { tx, ty ->
                        val clampedX = tx.coerceIn(0, 63)
                        val clampedY = ty.coerceIn(0, 63)
                        tileHeights.invoke(clampedX, clampedY, plane)
                    }
                )
            } else {
                TerrainAligner.AlignmentResult.NONE
            }

            val instance = ObjectInstance(
                mapObject = obj,
                definition = def,
                modelIds = modelIds,
                worldPosition = position,
                rotation = rotationToRadians(obj.rotation),
                scale = Vector3f(scaleX, scaleY, scaleZ),
                // Combine definition offset with decoration offset
                offset = Vector3f(
                    defOffsetX + decorOffset.x,
                    -defOffsetY + decorOffset.y,
                    -defOffsetZ + decorOffset.z
                ),
                terrainAlignment = terrainAlignment
            )

            instancesByPlane.getOrPut(plane) { mutableListOf() }.add(instance)
        }

        return instancesByPlane
    }

    /**
     * Create renderable instances from a list of MapObjects for a specific plane.
     *
     * The client places objects using the average height of 4 corner tiles
     * at the object's bounding box center (see SceneGraph.addObject in darkan-bot).
     *
     * Positions are computed in WORLD coordinates (based on regionX, regionY).
     * The tileHeights callback receives LOCAL coordinates (0-63).
     *
     * @param objects List of map objects from RegionLoader
     * @param regionX Region X coordinate (for world position calculation)
     * @param regionY Region Y coordinate
     * @param plane The plane level
     * @param tileHeights Optional height data callback (takes localX, localY). Returns scaled height.
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
        val baseWorldX = regionX * 64
        val baseWorldY = regionY * 64
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        for (obj in objects) {
            if (obj.level != plane) continue

            val def = getDefinition(obj.id) ?: continue
            val modelIds = getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            // Convert to local tile coordinates
            val localTileX = obj.x - baseWorldX
            val localTileY = obj.y - baseWorldY

            // Skip if outside region bounds
            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Calculate the 4 corner tile coordinates for height sampling (in local coords)
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

            // Clamp to valid tile range
            val x0 = xA.coerceIn(0, 63)
            val x1 = xB.coerceIn(0, 63)
            val y0 = yA.coerceIn(0, 63)
            val y1 = yB.coerceIn(0, 63)

            // Get average height of the 4 corners (matching client algorithm)
            val terrainHeight = if (tileHeights != null) {
                val h00 = tileHeights.invoke(x0, y0)
                val h10 = tileHeights.invoke(x1, y0)
                val h01 = tileHeights.invoke(x0, y1)
                val h11 = tileHeights.invoke(x1, y1)
                (h00 + h10 + h01 + h11) / 4f
            } else {
                0f
            }

            // Calculate WORLD position
            val worldTileX = regionX * 64 + localTileX
            val worldTileY = regionY * 64 + localTileY

            // Position in OpenGL coords
            // RS client places ALL objects at tile CENTER using:
            //   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + sizeX * 256
            val basePosX = worldTileX * tileSize
            val basePosZ = -worldTileY * tileSize

            // Get position offset (centers object within its tile footprint)
            val (posOffsetX, posOffsetZ) = getObjectPositionOffset(obj.shape, obj.rotation, tileSize, sizeX, sizeY)
            val posX = basePosX + posOffsetX
            val posZ = basePosZ + posOffsetZ

            // Y position: terrain height (definition offsets applied separately in transform)
            val posY = terrainHeight

            val position = Vector3f(posX, posY, posZ)

            // Calculate scale from definition (default is 128 = 1.0 scale)
            val scaleX = def.resizeX / 128f
            val scaleY = def.resizeY / 128f
            val scaleZ = def.resizeZ / 128f

            // Definition offsets (already in RS units * 4, convert to OpenGL units)
            // Applied AFTER rotation in the client (in world space)
            val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
            val defOffsetY = def.offsetY * CoordinateUtil.DEFAULT_SCALE
            val defOffsetZ = def.offsetZ * CoordinateUtil.DEFAULT_SCALE

            // Calculate wall decoration offset if applicable
            val decorOffset = getWallDecorationOffset(obj.shape, obj.rotation, def.decorDisplacement)

            // Calculate terrain alignment for objects with groundContourType 1, 2, or 3
            // Must pass the same averageHeight that the object is positioned at
            val terrainAlignment = if (tileHeights != null) {
                TerrainAligner.calculateObjectTilt(
                    localTileX = localTileX,
                    localTileY = localTileY,
                    sizeX = sizeX,
                    sizeY = sizeY,
                    groundContourType = def.groundContourType.toInt(),
                    groundContourModifier = def.groundContourModifier,
                    averageHeight = terrainHeight,  // Pass the same height object is positioned at
                    heightSampler = { tx, ty ->
                        val clampedX = tx.coerceIn(0, 63)
                        val clampedY = ty.coerceIn(0, 63)
                        tileHeights.invoke(clampedX, clampedY)
                    }
                )
            } else {
                TerrainAligner.AlignmentResult.NONE
            }

            instances.add(ObjectInstance(
                mapObject = obj,
                definition = def,
                modelIds = modelIds,
                worldPosition = position,
                rotation = rotationToRadians(obj.rotation),
                scale = Vector3f(scaleX, scaleY, scaleZ),
                // Combine definition offset with decoration offset
                offset = Vector3f(
                    defOffsetX + decorOffset.x,
                    -defOffsetY + decorOffset.y,
                    -defOffsetZ + decorOffset.z
                ),
                terrainAlignment = terrainAlignment
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
     * Transform order matches the client:
     * 1. Rotate around Y axis (object orientation)
     * 2. Apply terrain tilt (X and Z rotations) for groundContourType=3 objects
     * 3. Scale by resizeX/Y/Z
     * 4. Apply definition offset (offsetX/Y/Z) AFTER rotation
     * 5. Apply terrain height adjustment
     * 6. Translate to world position
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

                // Build model matrix matching client order:
                // RS applies: RotateY -> TiltX -> TiltZ (in that order on vertices)
                // Matrix equivalent (right-to-left): TiltZ * TiltX * RotateY
                modelMatrix.identity()
                    .translate(instance.worldPosition)  // 6. Move to world position

                // Apply terrain alignment height adjustment
                if (instance.terrainAlignment.needsAlignment()) {
                    modelMatrix.translate(0f, instance.terrainAlignment.heightAdjustment, 0f)
                }

                modelMatrix
                    .translate(instance.offset)         // 5. Apply definition offset
                    .scale(instance.scale)              // 4. Scale

                // Apply terrain tilt rotations
                if (instance.terrainAlignment.needsAlignment()) {
                    modelMatrix.rotateZ(instance.terrainAlignment.tiltZ)  // 3. Roll tilt
                    modelMatrix.rotateX(instance.terrainAlignment.tiltX)  // 2. Pitch tilt
                }

                modelMatrix.rotateY(instance.rotation)  // 1. Object Y rotation

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

    /**
     * Get the combined model ID for an object's models.
     * For objects with multiple model IDs, this returns a unique ID that represents
     * all models combined into a single mesh (matching RS client behavior).
     *
     * @param modelIds The list of model IDs for the object
     * @return A unique ID for the combined model (negative for multi-model, positive for single)
     */
    fun getCombinedModelId(modelIds: List<Int>): Int {
        return ModelLoader.getCombinedModelId(modelIds)
    }
}
