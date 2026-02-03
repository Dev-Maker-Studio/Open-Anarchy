package org.darkan.tools

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.viewer.InterfaceViewer
import org.darkan.tools.viewer.MapViewer
import org.darkan.tools.viewer.ModelViewer
import world.gregs.voidps.cache.Cache

/**
 * Main entry point for Darkan Tools.
 * Launches the selected viewer application.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "model"

    println("Darkan Tools - Loading cache...")
    val cache = Cache.get()
    println("Cache loaded successfully.")

    when (mode.lowercase()) {
        "model", "models" -> {
            val modelId = args.getOrNull(1)?.toIntOrNull()
            println("Starting Model Viewer..." + (modelId?.let { " with model ID $it" } ?: ""))
            ModelViewer(cache, modelId).run()
        }
        "map", "maps", "terrain" -> {
            println("Starting Map Viewer...")
            MapViewer(cache).run()
        }
        "interface", "interfaces", "ui" -> {
            println("Starting Interface Viewer...")
            InterfaceViewer(cache).run()
        }
        "diagnose", "debug" -> {
            // Diagnostic mode: diagnose <regionX> <regionY> <localX> <localY> [plane]
            val regionX = args.getOrNull(1)?.toIntOrNull() ?: 50
            val regionY = args.getOrNull(2)?.toIntOrNull() ?: 50
            val localX = args.getOrNull(3)?.toIntOrNull() ?: 39
            val localY = args.getOrNull(4)?.toIntOrNull() ?: 25
            val plane = args.getOrNull(5)?.toIntOrNull() ?: 0
            runDiagnostic(cache, regionX, regionY, localX, localY, plane)
        }
        "object", "obj" -> {
            val objectId = args.getOrNull(1)?.toIntOrNull() ?: 45149
            runObjectDiagnostic(cache, objectId)
        }
        "uv", "uvdebug" -> {
            val modelId = args.getOrNull(1)?.toIntOrNull() ?: 48861
            runUVDiagnostic(cache, modelId)
        }
        "help", "-h", "--help" -> {
            printHelp()
        }
        else -> {
            println("Unknown mode: $mode")
            printHelp()
        }
    }
}

/**
 * Run diagnostic on a specific tile to find what objects are there and what textures they use.
 */
private fun runDiagnostic(cache: Cache, regionX: Int, regionY: Int, localX: Int, localY: Int, plane: Int) {
    println("\n=== TILE DIAGNOSTIC ===")
    println("Region: ($regionX, $regionY)")
    println("Local: ($localX, $localY)")
    println("Plane: $plane")
    println()

    // Load definitions
    TerrainLoader.loadDefinitions(cache)
    ObjectRenderer.loadDefinitions(cache)

    // Load XTEA keys
    if (!MapXteas.isLoaded()) {
        MapXteas.load()
    }

    // Load terrain data
    val terrainData = TerrainLoader.loadRegion(cache, regionX, regionY)
    if (terrainData == null) {
        println("ERROR: Could not load terrain data for region ($regionX, $regionY)")
        return
    }

    // Get tile info
    val tile = terrainData.tiles[plane][localX][localY]
    println("--- TERRAIN TILE DATA ---")
    println("  Height: ${tile.height}")
    println("  Overlay ID: ${tile.overlayId}")
    println("  Overlay Shape: ${tile.overlayShape}")
    println("  Overlay Rotation: ${tile.overlayRotation}")
    println("  Underlay ID: ${tile.underlayId}")
    println("  Flags: 0x${tile.flags.toString(16)}")

    // Get overlay definition if present
    if (tile.overlayId > 0) {
        val overlayDef = TerrainLoader.getOverlayDefinition(tile.overlayId)
        if (overlayDef != null) {
            println("  Overlay Definition:")
            println("    Texture: ${overlayDef.texture}")
            println("    Scale: ${overlayDef.scale}")
            println("    Colour: ${overlayDef.colour}")
            println("    BlendColour: ${overlayDef.blendColour}")
            println("    UnderlayOverrides: ${overlayDef.underlayOverrides}")
        }
    }

    // Get underlay definition if present
    if (tile.underlayId > 0) {
        val underlayDef = TerrainLoader.getUnderlayDefinition(tile.underlayId)
        if (underlayDef != null) {
            println("  Underlay Definition:")
            println("    Texture: ${underlayDef.texture}")
            println("    Scale: ${underlayDef.scale}")
            println("    Colour: ${underlayDef.colour}")
        }
    }

    // Load region objects
    println("\n--- OBJECTS AT THIS TILE ---")
    val regionData = RegionLoader.loadRegion(cache, regionX, regionY)
    if (regionData == null) {
        println("ERROR: Could not load region data for ($regionX, $regionY)")
        return
    }

    val baseWorldX = regionX * 64
    val baseWorldY = regionY * 64
    val worldX = baseWorldX + localX
    val worldY = baseWorldY + localY

    // Find objects at or near this tile
    var foundObjects = 0
    for (obj in regionData.objects) {
        // Check if object is at or overlaps this tile
        val def = ObjectRenderer.getDefinition(obj.id)
        val sizeX = def?.sizeX ?: 1
        val sizeY = def?.sizeY ?: 1

        // Calculate object footprint
        val objMinX = obj.x
        val objMaxX = obj.x + sizeX - 1
        val objMinY = obj.y
        val objMaxY = obj.y + sizeY - 1

        // Check if our tile is within the object's footprint
        if (worldX in objMinX..objMaxX && worldY in objMinY..objMaxY) {
            foundObjects++
            println("\nObject #$foundObjects:")
            println("  ID: ${obj.id}")
            println("  Position: (${obj.x}, ${obj.y}) - Local: (${obj.x - baseWorldX}, ${obj.y - baseWorldY})")
            println("  Plane: ${obj.level}")
            println("  Shape: ${obj.shape}")
            println("  Rotation: ${obj.rotation}")

            if (def != null) {
                println("  Definition:")
                println("    Name: ${def.name}")
                println("    Size: ${def.sizeX} x ${def.sizeY}")
                println("    Options: ${def.options?.toList()}")

                // Get model IDs for this object
                val modelIds = ObjectRenderer.getModelIds(def, obj.shape)
                println("    Model IDs: $modelIds")

                // Check each model for textures
                for (modelId in modelIds) {
                    val modelDef = ModelLoader.loadDefinition(cache, modelId)
                    if (modelDef != null) {
                        println("\n    Model $modelId:")
                        println("      Vertices: ${modelDef.vertexCount}")
                        println("      Faces: ${modelDef.faceCount}")
                        println("      Texture Triangles: ${modelDef.textureTriangleCount}")

                        // Find all textures used by this model
                        val usedTextures = mutableSetOf<Int>()
                        if (modelDef.faceTextures != null) {
                            for (f in 0 until modelDef.faceCount) {
                                val texId = modelDef.faceTextures[f].toInt()
                                if (texId != -1) {
                                    usedTextures.add(texId and 0xffff)
                                }
                            }
                        }
                        if (usedTextures.isNotEmpty()) {
                            println("      Textures used: $usedTextures")

                            // Check if texture 928 is used
                            if (928 in usedTextures) {
                                println("      *** TEXTURE 928 FOUND IN THIS MODEL ***")

                                // Get detailed UV info for faces with texture 928
                                println("      Faces with texture 928:")
                                for (f in 0 until modelDef.faceCount) {
                                    val texId = modelDef.faceTextures?.get(f)?.toInt() ?: -1
                                    if ((texId and 0xffff) == 928) {
                                        val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt()?.and(0xff) ?: 255
                                        println("        Face $f: texturePointer=$texPointer")

                                        if (texPointer != 255 && texPointer < modelDef.textureTriangleCount) {
                                            val mapping = modelDef.texturesMapping?.get(texPointer)?.toInt()?.and(0xff) ?: 0
                                            println("          mappingType=$mapping")
                                            if (modelDef.textureFaceX != null) {
                                                val tA = modelDef.textureFaceX[texPointer].toInt()
                                                val tB = modelDef.textureFaceY?.get(texPointer)?.toInt() ?: 0
                                                val tC = modelDef.textureFaceZ?.get(texPointer)?.toInt() ?: 0
                                                println("          textureTriangle: ($tA, $tB, $tC)")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            println("      Textures: (none - vertex colors only)")
                        }
                    }
                }
            }
        }
    }

    if (foundObjects == 0) {
        println("  (No objects found at this tile)")
    }

    // Also check nearby tiles (within 3 tile radius) for large objects
    println("\n--- NEARBY OBJECTS (3 tile radius) USING TEXTURE 928 ---")
    var nearbyWithTexture928 = 0
    for (obj in regionData.objects) {
        val objLocalX = obj.x - baseWorldX
        val objLocalY = obj.y - baseWorldY

        // Check if object is within 3 tiles of our target
        if (kotlin.math.abs(objLocalX - localX) <= 3 && kotlin.math.abs(objLocalY - localY) <= 3) {
            val def = ObjectRenderer.getDefinition(obj.id) ?: continue
            val modelIds = ObjectRenderer.getModelIds(def, obj.shape)

            for (modelId in modelIds) {
                val modelDef = ModelLoader.loadDefinition(cache, modelId) ?: continue
                if (modelDef.faceTextures == null) continue

                var hasTexture928 = false
                for (f in 0 until modelDef.faceCount) {
                    val texId = modelDef.faceTextures[f].toInt() and 0xffff
                    if (texId == 928) {
                        hasTexture928 = true
                        break
                    }
                }

                if (hasTexture928) {
                    nearbyWithTexture928++
                    println("\nNearby Object #$nearbyWithTexture928:")
                    println("  ID: ${obj.id} (${def.name})")
                    println("  Position: Local (${objLocalX}, ${objLocalY})")
                    println("  Plane: ${obj.level}")
                    println("  Shape: ${obj.shape}")
                    println("  Model: $modelId")
                    break
                }
            }
        }
    }

    if (nearbyWithTexture928 == 0) {
        println("  (No nearby objects using texture 928)")
    }

    // Check ALL objects in the region for texture 928
    println("\n--- ALL OBJECTS IN REGION USING TEXTURE 928 ---")
    var allWithTexture928 = 0
    for (obj in regionData.objects) {
        val def = ObjectRenderer.getDefinition(obj.id) ?: continue
        val modelIds = ObjectRenderer.getModelIds(def, obj.shape)

        for (modelId in modelIds) {
            val modelDef = ModelLoader.loadDefinition(cache, modelId) ?: continue
            if (modelDef.faceTextures == null) continue

            var hasTexture928 = false
            for (f in 0 until modelDef.faceCount) {
                val texId = modelDef.faceTextures[f].toInt() and 0xffff
                if (texId == 928) {
                    hasTexture928 = true
                    break
                }
            }

            if (hasTexture928) {
                allWithTexture928++
                val objLocalX = obj.x - baseWorldX
                val objLocalY = obj.y - baseWorldY
                println("\n  Object #$allWithTexture928:")
                println("    ID: ${obj.id} (${def.name})")
                println("    Position: Local ($objLocalX, $objLocalY)")
                println("    Size: ${def.sizeX} x ${def.sizeY}")
                println("    Plane: ${obj.level}")
                println("    Shape: ${obj.shape}")
                println("    Model: $modelId")
                break
            }
        }
    }

    if (allWithTexture928 == 0) {
        println("  (No objects in this region use texture 928)")
    }

    // Print underlay/overlay info for nearby tiles to check texture continuity
    println("\n--- NEARBY TILE TEXTURES (3x3 around target) ---")
    for (dy in -1..1) {
        for (dx in -1..1) {
            val checkX = localX + dx
            val checkY = localY + dy
            if (checkX < 0 || checkX >= 64 || checkY < 0 || checkY >= 64) continue

            val checkTile = terrainData.tiles[plane][checkX][checkY]
            val overlayTex = if (checkTile.overlayId > 0) {
                TerrainLoader.getOverlayDefinition(checkTile.overlayId)?.texture ?: -1
            } else -1
            val underlayTex = if (checkTile.underlayId > 0) {
                TerrainLoader.getUnderlayDefinition(checkTile.underlayId)?.texture ?: -1
            } else -1

            println("  Tile ($checkX, $checkY): overlay=${checkTile.overlayId}(tex=$overlayTex), underlay=${checkTile.underlayId}(tex=$underlayTex)")
        }
    }

    println("\n=== DIAGNOSTIC COMPLETE ===")
}

/**
 * Run UV debugging on a specific model to trace through the UV calculation.
 */
private fun runUVDiagnostic(cache: Cache, modelId: Int) {
    println("\n=== UV DIAGNOSTIC: Model $modelId ===")

    // Enable debug output
    ModelLoader.setDebugCubeUv(true)

    // Clear cache to force recomputation
    ModelLoader.clearCache()

    // Load the model
    val meshDataList = ModelLoader.createMeshDataList(cache, modelId, debug = true)
    if (meshDataList == null) {
        println("ERROR: Could not load model $modelId")
        return
    }

    println("\nMeshData batches: ${meshDataList.size}")
    for ((i, meshData) in meshDataList.withIndex()) {
        println("  Batch $i: textureId=${meshData.textureId}, vertices=${meshData.positions.size / 3}, indices=${meshData.indices.size}")

        // Print first 9 vertices (3 faces) with their UVs
        if (meshData.textureCoords != null) {
            val numVerts = minOf(9, meshData.positions.size / 3)
            println("    First $numVerts vertices:")
            for (v in 0 until numVerts) {
                val px = meshData.positions[v * 3]
                val py = meshData.positions[v * 3 + 1]
                val pz = meshData.positions[v * 3 + 2]
                val u = meshData.textureCoords!![v * 2]
                val vCoord = meshData.textureCoords!![v * 2 + 1]
                println("      V$v: pos=($px, $py, $pz) uv=($u, $vCoord)")
            }
        }
    }

    // Disable debug output
    ModelLoader.setDebugCubeUv(false)

    println("\n=== UV DIAGNOSTIC COMPLETE ===")
}

/**
 * Diagnose a specific object ID to dump its definition and model texture info.
 */
private fun runObjectDiagnostic(cache: Cache, objectId: Int) {
    println("\n=== OBJECT DIAGNOSTIC: ID $objectId ===")

    // Load definitions
    ObjectRenderer.loadDefinitions(cache)

    val def = ObjectRenderer.getDefinition(objectId)
    if (def == null) {
        println("ERROR: Object $objectId not found")
        return
    }

    println("Name: ${def.name}")
    println("Size: ${def.sizeX} x ${def.sizeY}")
    println("Options: ${def.options?.toList()}")
    println("ModelIds: ${def.modelIds?.contentDeepToString()}")

    // Check for retexturing
    if (def.retextureSrc != null || def.retextureDst != null) {
        println("RETEXTURING: src=${def.retextureSrc?.map { it.toInt() and 0xffff }}, dst=${def.retextureDst?.map { it.toInt() and 0xffff }}")
    }
    if (def.recolorSrc != null || def.recolorDst != null) {
        println("RECOLORING: src=${def.recolorSrc?.map { it.toInt() and 0xffff }}, dst=${def.recolorDst?.map { it.toInt() and 0xffff }}")
    }

    // Get all model IDs for this object (all shapes combined)
    val allModelIds = mutableSetOf<Int>()
    def.modelIds?.forEach { modelIdArray ->
        modelIdArray.forEach { modelId -> allModelIds.add(modelId) }
    }

    println("\n--- MODELS ---")
    for (modelId in allModelIds) {
        println("\nModel $modelId:")
        val modelDef = ModelLoader.loadDefinition(cache, modelId)
        if (modelDef == null) {
            println("  ERROR: Could not load model")
            continue
        }

        println("  Version: ${modelDef.version}")
        println("  Vertices: ${modelDef.vertexCount}")
        println("  Faces: ${modelDef.faceCount}")
        println("  Texture Triangles: ${modelDef.textureTriangleCount}")

        // Count texture mapping types
        var type0Count = 0
        var type1Count = 0
        var type2Count = 0
        var type3Count = 0
        if (modelDef.texturesMapping != null) {
            for (i in 0 until modelDef.textureTriangleCount) {
                when (modelDef.texturesMapping[i].toInt() and 0xff) {
                    0 -> type0Count++
                    1 -> type1Count++
                    2 -> type2Count++
                    3 -> type3Count++
                }
            }
        }
        println("  Mapping types: type0(planar)=$type0Count, type1(cylindrical)=$type1Count, type2(cube)=$type2Count, type3(spherical)=$type3Count")

        // Show texture triangles details
        if (modelDef.textureTriangleCount > 0) {
            println("  Texture Triangles:")
            for (t in 0 until modelDef.textureTriangleCount) {
                val mapping = modelDef.texturesMapping?.get(t)?.toInt()?.and(0xff) ?: 0
                val tA = modelDef.textureFaceX?.get(t)?.toInt() ?: 0
                val tB = modelDef.textureFaceY?.get(t)?.toInt() ?: 0
                val tC = modelDef.textureFaceZ?.get(t)?.toInt() ?: 0
                println("    [$t] mapping=$mapping, vertices=($tA, $tB, $tC)")
            }
        }

        // Show complex texture data if present
        if (modelDef.texturesScaleX != null) {
            println("  Complex Texture Data (indexed by complex texture index):")
            for (i in modelDef.texturesScaleX.indices) {
                val scaleX = modelDef.texturesScaleX[i]
                val scaleY = modelDef.texturesScaleY?.get(i) ?: 0
                val scaleZ = modelDef.texturesScaleZ?.get(i) ?: 0
                val rotation = modelDef.texturesRotation?.get(i)?.toInt()?.and(0xff) ?: 0
                val direction = modelDef.texturesDirection?.get(i)?.toInt()?.and(0xff) ?: 0
                val speed = modelDef.texturesSpeed?.get(i) ?: 0
                println("    Complex[$i]: scale=($scaleX, $scaleY, $scaleZ), rot=$rotation, dir=$direction, speed=$speed")
            }
        }

        // Show cube texture data if present
        if (modelDef.texturesUtrans != null) {
            println("  Cube Texture Data (indexed by cube texture index):")
            for (i in modelDef.texturesUtrans.indices) {
                val uTrans = modelDef.texturesUtrans[i]
                val vTrans = modelDef.texturesVtrans?.get(i) ?: 0
                println("    Cube[$i]: uTrans=$uTrans, vTrans=$vTrans")
            }
        }

        // Find all textures used by faces
        val usedTextures = mutableMapOf<Int, Int>() // textureId -> count
        val textureMappings = mutableMapOf<Int, MutableSet<Int>>() // textureId -> set of mapping types used
        if (modelDef.faceTextures != null) {
            for (f in 0 until modelDef.faceCount) {
                val texId = modelDef.faceTextures[f].toInt()
                if (texId != -1) {
                    val actualTexId = texId and 0xffff
                    usedTextures[actualTexId] = (usedTextures[actualTexId] ?: 0) + 1

                    val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt()?.and(0xff) ?: 255
                    if (texPointer != 255 && texPointer < modelDef.textureTriangleCount) {
                        val mapping = modelDef.texturesMapping?.get(texPointer)?.toInt()?.and(0xff) ?: 0
                        textureMappings.getOrPut(actualTexId) { mutableSetOf() }.add(mapping)
                    }
                }
            }
        }

        if (usedTextures.isNotEmpty()) {
            println("  Face Textures Used:")
            for ((texId, count) in usedTextures.entries.sortedBy { it.key }) {
                val mappings = textureMappings[texId] ?: emptySet()
                println("    Texture $texId: $count faces, mappings=${mappings.sorted()}")
            }
        }

        // Detailed face analysis for first 10 textured faces
        println("  First 10 textured faces:")
        var texturedCount = 0
        for (f in 0 until modelDef.faceCount) {
            if (texturedCount >= 10) break

            val texId = modelDef.faceTextures?.get(f)?.toInt() ?: -1
            if (texId == -1) continue

            texturedCount++
            val actualTexId = texId and 0xffff
            val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt()?.and(0xff) ?: 255
            val aIdx = modelDef.faceA[f]
            val bIdx = modelDef.faceB[f]
            val cIdx = modelDef.faceC[f]

            println("    Face $f: tex=$actualTexId, pointer=$texPointer, verts=($aIdx, $bIdx, $cIdx)")

            if (texPointer != 255 && texPointer < modelDef.textureTriangleCount) {
                val mapping = modelDef.texturesMapping?.get(texPointer)?.toInt()?.and(0xff) ?: 0
                println("      mapping=$mapping")

                // Show vertex positions for the face
                if (aIdx < modelDef.vertexCount && bIdx < modelDef.vertexCount && cIdx < modelDef.vertexCount) {
                    println("      A: (${modelDef.vertexX[aIdx]}, ${modelDef.vertexY[aIdx]}, ${modelDef.vertexZ[aIdx]})")
                    println("      B: (${modelDef.vertexX[bIdx]}, ${modelDef.vertexY[bIdx]}, ${modelDef.vertexZ[bIdx]})")
                    println("      C: (${modelDef.vertexX[cIdx]}, ${modelDef.vertexY[cIdx]}, ${modelDef.vertexZ[cIdx]})")
                }
            }
        }
    }

    println("\n=== DIAGNOSTIC COMPLETE ===")
}

private fun printHelp() {
    println("""
        Darkan Tools - Game Asset Viewer

        Usage: ./gradlew :tools:run --args="<mode> [options]"

        Modes:
          model [id]  - View 3D models from the cache (default)
                        Optional: specify a model ID to load on startup
          map        - View terrain/map regions with full scene rendering
          interface  - View UI interfaces and components

        Controls (Model Viewer):
          WASD        - Move camera
          Space/Shift - Move up/down
          Right Click - Rotate camera
          Scroll      - Zoom
          R           - Toggle auto-rotate
          < >         - Previous/next model
          F1          - Toggle debug panel
          F2          - Toggle asset browser
          F3          - Toggle wireframe
          ESC         - Release mouse

        Controls (Map Viewer):
          WASD        - Move camera
          Space/Shift - Move up/down
          Right Click - Look around
          Scroll      - Zoom
          Ctrl        - Fast movement
          Arrow Keys  - Navigate regions
          PgUp/PgDn   - Change plane level
          1-4         - Select plane directly
          M           - Toggle multi/single region mode
          T           - Toggle terrain
          F1          - Toggle debug panel
          F2          - Toggle navigation panel
          F3          - Toggle wireframe

        Examples:
          ./gradlew :tools:run
          ./gradlew :tools:run --args="model"
          ./gradlew :tools:run --args="model 12345"
          ./gradlew :tools:run --args="map"
          ./gradlew :tools:run --args="interface"
    """.trimIndent())
}
