package org.darkan.tools

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.texture.*
import org.darkan.tools.cache.texture.ops.*
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.viewer.InterfaceViewer
import org.darkan.tools.viewer.MapViewer
import org.darkan.tools.viewer.ModelViewer
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

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
        "texture", "tex" -> {
            val texId = args.getOrNull(1)?.toIntOrNull() ?: 725
            runTextureDiagnostic(cache, texId)
        }
        "faces", "facegeom" -> {
            val modelId = args.getOrNull(1)?.toIntOrNull() ?: 68750
            runFaceGeometryDiagnostic(cache, modelId)
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
    println("Shapes: ${def.shapes?.map { it.name + "(${it.id})" }}")
    println("ModelIds: ${def.modelIds?.contentDeepToString()}")
    println("Interactive: ${def.interactive}")
    println("Solid: ${def.solid}")
    println("ForceDisplayDecoration: ${def.forceDisplayDecoration}")
    println("Ambient: ${def.ambient}")
    println("Contrast: ${def.contrast}")

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

        // Show ALL untextured faces and their details
        println("  Untextured faces (faceTextures == -1):")
        var untexturedCount = 0
        for (f in 0 until modelDef.faceCount) {
            val texId = modelDef.faceTextures?.get(f)?.toInt() ?: -1
            if (texId != -1) continue

            untexturedCount++
            val rawHsl = modelDef.faceColors[f].toInt() and 0xffff
            val renderTypeRaw = modelDef.faceRenderTypes?.get(f)
            val renderType = renderTypeRaw?.toInt()?.and(0xff) ?: 0
            val alpha = modelDef.faceAlphas?.get(f)?.toInt()?.and(0xff) ?: 0
            val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt() ?: -999
            val rawFaceColor = modelDef.faceColors[f]
            println("    Face $f: HSL=$rawHsl (raw=$rawFaceColor), renderType=$renderType (raw=$renderTypeRaw), alpha=$alpha, texPointer=$texPointer")
        }
        if (untexturedCount == 0) {
            println("    (None - all faces are textured)")
        } else {
            println("    Total untextured: $untexturedCount")
        }

        // Verify texture transition point - show faces around the textured/untextured boundary
        if (modelDef.faceTextures != null) {
            println("  Texture boundary analysis (faces 760-774):")
            for (f in 760 until minOf(774, modelDef.faceCount)) {
                val texIdSigned = modelDef.faceTextures[f].toInt()
                val texId = if (texIdSigned == -1) -1 else (texIdSigned and 0xffff)
                val rawHsl = modelDef.faceColors[f].toInt() and 0xffff
                println("    Face $f: texId=$texId, HSL=$rawHsl")
            }
        }

        // Check footer flags for billboard/particle effects data
        val rawModelData = cache.data(Index.MODELS, modelId)
        if (rawModelData != null && rawModelData.size >= 24) {
            val footerFlags = rawModelData[rawModelData.size - 18].toInt() and 0xff
            val hasFaceTypes = (footerFlags and 0x1) == 1
            val hasParticleEffects = (footerFlags and 0x2) == 2
            val hasBillboards = (footerFlags and 0x4) == 4
            val hasVersion = (footerFlags and 0x8) == 8
            println("  Footer flags: 0x${footerFlags.toString(16)}")
            println("    hasFaceTypes=$hasFaceTypes, hasParticleEffects=$hasParticleEffects, hasBillboards=$hasBillboards, hasVersion=$hasVersion")

            if (hasBillboards) {
                println("  MODEL HAS BILLBOARD DATA - these faces may be billboard anchors!")
            }

            // Print decoded billboard data from ModelDefinition
            if (modelDef.billboards != null) {
                println("  Decoded billboards (${modelDef.billboards.size}):")
                for ((bi, bb) in modelDef.billboards.withIndex()) {
                    println("    [$bi] id=${bb.billboardId}, face=${bb.appliesOnFace}, depth=${bb.depth}, distance=${bb.distance}")
                    // Load the BillboardType config
                    val bbType = org.darkan.tools.cache.model.BillboardTypeLoader.get(cache, bb.billboardId)
                    println("         type: materialId=${bbType.materialId}, size2d=${bbType.size2d}, size3d=${bbType.size3d}, shape=${bbType.shape}, blend=${bbType.blendType}, stationary=${bbType.stationary}, hasUid=${bbType.hasUid}")
                }
            } else {
                println("  Decoded billboards: null (no billboard data decoded)")
            }
        }

        // Specific face 769 analysis (if it exists)
        if (modelDef.faceCount > 769) {
            println("  === FACE 769 DETAILED ANALYSIS ===")
            val f = 769
            val rawHsl = modelDef.faceColors[f].toInt() and 0xffff
            val renderType = modelDef.faceRenderTypes?.get(f)?.toInt()?.and(0xff) ?: 0
            val alpha = modelDef.faceAlphas?.get(f)?.toInt()?.and(0xff) ?: 0
            val rawTexIdSigned = modelDef.faceTextures?.get(f)?.toInt() ?: -1
            val rawTexId = if (rawTexIdSigned == -1) -1 else (rawTexIdSigned and 0xffff)
            val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt() ?: -999
            val aIdx = modelDef.faceA[f]
            val bIdx = modelDef.faceB[f]
            val cIdx = modelDef.faceC[f]
            println("    HSL: $rawHsl (raw short: ${modelDef.faceColors[f]})")
            println("    RenderType: $renderType")
            println("    Alpha: $alpha")
            println("    TextureId (signed): $rawTexIdSigned")
            println("    TextureId (unsigned): $rawTexId")
            println("    TexturePointer: $texPointer")
            println("    Vertices: A=$aIdx, B=$bIdx, C=$cIdx")
            if (aIdx < modelDef.vertexCount && bIdx < modelDef.vertexCount && cIdx < modelDef.vertexCount) {
                println("    A pos: (${modelDef.vertexX[aIdx]}, ${modelDef.vertexY[aIdx]}, ${modelDef.vertexZ[aIdx]})")
                println("    B pos: (${modelDef.vertexX[bIdx]}, ${modelDef.vertexY[bIdx]}, ${modelDef.vertexZ[bIdx]})")
                println("    C pos: (${modelDef.vertexX[cIdx]}, ${modelDef.vertexY[cIdx]}, ${modelDef.vertexZ[cIdx]})")
            }
            // Check surrounding faces for context
            println("  === FACES 765-773 (around face 769) ===")
            for (nearby in 765..773) {
                if (nearby < 0 || nearby >= modelDef.faceCount) continue
                val nHsl = modelDef.faceColors[nearby].toInt() and 0xffff
                val nRender = modelDef.faceRenderTypes?.get(nearby)?.toInt()?.and(0xff) ?: 0
                val nTexSigned = modelDef.faceTextures?.get(nearby)?.toInt() ?: -1
                val nTex = if (nTexSigned == -1) -1 else (nTexSigned and 0xffff)
                val nPtr = modelDef.faceTexturePointers?.get(nearby)?.toInt() ?: -999
                val nAlpha = modelDef.faceAlphas?.get(nearby)?.toInt()?.and(0xff) ?: 0
                println("    Face $nearby: HSL=$nHsl, tex=$nTex, ptr=$nPtr, render=$nRender, alpha=$nAlpha")
            }
        }
    }

    println("\n=== DIAGNOSTIC COMPLETE ===")
}

/**
 * Run a texture diagnostic without requiring OpenGL.
 * Loads the texture type from cache, decodes its operations, loads sprites,
 * generates pixels, and prints detailed information about each step.
 */
private fun runTextureDiagnostic(cache: Cache, textureId: Int) {
    println("\n=== TEXTURE DIAGNOSTIC: ID $textureId ===\n")

    // Step 1: Check TEXTURES index structure
    val textureArchives = cache.archives(Index.TEXTURES)
    println("TEXTURES index (${Index.TEXTURES}):")
    println("  Total archives: ${textureArchives.size}")
    println("  Archive $textureId exists: ${textureId in textureArchives}")
    val filesInArchive = cache.files(Index.TEXTURES, textureId)
    println("  Files in archive $textureId: ${filesInArchive.toList()}")

    // Step 2: Load raw data
    val rawData = cache.data(Index.TEXTURES, textureId)
    if (rawData == null) {
        println("  ERROR: cache.data(TEXTURES, $textureId) returned NULL")
        // Try alternate addressing
        val altData = cache.data(Index.TEXTURES, 0, textureId)
        println("  Alternate: cache.data(TEXTURES, 0, $textureId) returned ${if (altData != null) "${altData.size} bytes" else "NULL"}")
        println("\n=== DIAGNOSTIC COMPLETE ===")
        return
    }
    println("  Raw data: ${rawData.size} bytes")
    println("  First 32 bytes: ${rawData.take(32).map { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }}")

    // Step 3: Decode TextureType
    val textureType: TextureType
    try {
        textureType = TextureType(rawData)
    } catch (e: Exception) {
        println("  ERROR decoding TextureType: ${e.message}")
        e.printStackTrace()
        println("\n=== DIAGNOSTIC COMPLETE ===")
        return
    }

    println("\n--- OPERATIONS (${textureType.textureOperations.size}) ---")
    for ((i, op) in textureType.textureOperations.withIndex()) {
        val labels = mutableListOf<String>()
        if (op === textureType.opaqueOperation) labels.add("OPAQUE")
        if (op === textureType.opacityOperation) labels.add("OPACITY")
        if (op === textureType.hdrOperation) labels.add("HDR")
        val labelStr = if (labels.isNotEmpty()) " <== ${labels.joinToString(", ")}" else ""

        val extra = when (op) {
            is TextureOpTiledSprite -> " (TiledSprite) spriteId=${op.spriteId}"
            is TextureOpSprite -> " spriteId=${op.spriteId}"
            is TextureOpTexture -> " materialId=${op.getMaterialId()}"
            else -> ""
        }
        val childIndices = op.operations.mapIndexed { ci, child ->
            if (child != null) {
                val childIdx = textureType.textureOperations.indexOf(child)
                "$ci->op[$childIdx]"
            } else "$ci->null"
        }
        val childStr = if (childIndices.isNotEmpty()) " children=[${childIndices.joinToString(", ")}]" else ""
        println("  [$i] ${op::class.simpleName} monochrome=${op.monochrome} cache=${op.imageCacheCapacity}$extra$childStr$labelStr")
    }

    println("\n  textureFileIds (sprites): ${textureType.textureFileIds.toList()}")
    println("  materialFileIds (nested textures): ${textureType.materialFileIds.toList()}")

    // Step 4: Load sprites
    println("\n--- SPRITE LOADING ---")
    for (op in textureType.textureOperations) {
        if (op is TextureOpSprite && op.spriteId >= 0) {
            val spriteId = op.spriteId
            val spriteData = cache.data(Index.SPRITES, spriteId)
            if (spriteData == null) {
                println("  Sprite $spriteId: cache.data(SPRITES, $spriteId) returned NULL")
                // Try alternate addressing
                val altData = cache.data(Index.SPRITES, 0, spriteId)
                println("  Sprite $spriteId: alternate cache.data(SPRITES, 0, $spriteId) returned ${if (altData != null) "${altData.size} bytes" else "NULL"}")
                continue
            }
            println("  Sprite $spriteId: ${spriteData.size} bytes raw data")

            // Decode sprite header
            try {
                val buf = BufferReader(spriteData)
                buf.position(spriteData.size - 2)
                val spriteCount = buf.readShort() and 0xFFFF
                println("    spriteCount: $spriteCount")

                if (spriteCount > 0) {
                    buf.position(spriteData.size - 7 - spriteCount * 8)
                    val biggestWidth = buf.readShort()
                    val biggestHeight = buf.readShort()
                    val paletteSize = buf.readUnsignedByte() + 1
                    println("    biggestWidth=$biggestWidth, biggestHeight=$biggestHeight, paletteSize=$paletteSize")

                    val minX = IntArray(spriteCount) { buf.readShort() }
                    // skip minY, widths, heights
                    buf.position(spriteData.size - 7 - spriteCount * 8 + 5 + spriteCount)
                    val minY = IntArray(spriteCount) { buf.readShort() }
                    val widths = IntArray(spriteCount) { buf.readShort() }
                    val heights = IntArray(spriteCount) { buf.readShort() }

                    // Re-read correctly: after palette size, we have minX[N], minY[N], widths[N], heights[N]
                    buf.position(spriteData.size - 7 - spriteCount * 8 + 5)
                    for (j in 0 until spriteCount) { minX[j] = buf.readShort() }
                    for (j in 0 until spriteCount) { minY[j] = buf.readShort() }
                    for (j in 0 until spriteCount) { widths[j] = buf.readShort() }
                    for (j in 0 until spriteCount) { heights[j] = buf.readShort() }

                    println("    sub[0]: minX=${minX[0]}, minY=${minY[0]}, width=${widths[0]}, height=${heights[0]}")

                    // Read pixel flags
                    buf.position(0)
                    val flags = buf.readUnsignedByte()
                    val hasAlpha = (flags and 0x2) != 0
                    val columnMajor = (flags and 0x1) != 0
                    println("    sub[0] flags: hasAlpha=$hasAlpha, columnMajor=$columnMajor")

                    val fullWidth = if (biggestWidth > 0) biggestWidth else widths[0]
                    val fullHeight = if (biggestHeight > 0) biggestHeight else heights[0]
                    println("    fullCanvas: ${fullWidth}x${fullHeight}")

                    // Try full decode
                    val decoded = TextureLoader.decodeSpriteToDataPublic(spriteData, 0)
                    if (decoded != null) {
                        val nonZero = decoded.pixels.count { it != 0 }
                        val nonBlackRgb = decoded.pixels.count { (it and 0xFFFFFF) != 0 }
                        println("    DECODED: ${decoded.width}x${decoded.height}, ${decoded.pixels.size} pixels, $nonZero non-zero, $nonBlackRgb non-black-RGB")
                        val samples = decoded.pixels.filter { (it and 0xFFFFFF) != 0 }.take(5)
                        println("    sample non-black pixels: ${samples.map { "0x${it.toUInt().toString(16)}" }}")
                    } else {
                        println("    DECODE RETURNED NULL")
                    }
                }
            } catch (e: Exception) {
                println("    ERROR parsing sprite header: ${e.message}")
            }
        }
    }

    // Step 5: Generate pixels without OpenGL
    println("\n--- PIXEL GENERATION ---")
    TextureRaster.calculateTable()

    // Load sprites into operations
    for (op in textureType.textureOperations) {
        if (op is TextureOpSprite && op.spriteId >= 0) {
            val spriteRawData = cache.data(Index.SPRITES, op.spriteId) ?: continue
            val decoded = TextureLoader.decodeSpriteToDataPublic(spriteRawData, 0)
            if (decoded != null) {
                op.loadSprite(decoded.pixels, decoded.width, decoded.height)
                println("  Loaded sprite ${op.spriteId} into ${op::class.simpleName}: ${decoded.width}x${decoded.height}")
            } else {
                println("  FAILED to decode sprite ${op.spriteId} for ${op::class.simpleName}")
            }
        }
    }

    // Load nested textures
    for (op in textureType.textureOperations) {
        if (op is TextureOpTexture && op.getMaterialId() >= 0) {
            val nestedId = op.getMaterialId()
            val nestedData = cache.data(Index.TEXTURES, nestedId)
            if (nestedData != null) {
                try {
                    val nestedType = TextureType(nestedData)
                    // Load sprites for nested texture
                    for (nestedOp in nestedType.textureOperations) {
                        if (nestedOp is TextureOpSprite && nestedOp.spriteId >= 0) {
                            val spriteRawData = cache.data(Index.SPRITES, nestedOp.spriteId) ?: continue
                            val decoded = TextureLoader.decodeSpriteToDataPublic(spriteRawData, 0)
                            if (decoded != null) {
                                nestedOp.loadSprite(decoded.pixels, decoded.width, decoded.height)
                            }
                        }
                    }
                    val nestedPixels = nestedType.getPixelsArgb(128, 128, { sid ->
                        val sData = cache.data(Index.SPRITES, sid) ?: return@getPixelsArgb null
                        TextureLoader.decodeSpriteToDataPublic(sData, 0)
                    })
                    op.texturePixels = nestedPixels
                    op.textureWidth = 128
                    op.textureHeight = 128
                    val nonZero = nestedPixels.count { it != 0 }
                    println("  Loaded nested texture $nestedId: $nonZero non-zero pixels")
                } catch (e: Exception) {
                    println("  FAILED to load nested texture $nestedId: ${e.message}")
                }
            } else {
                println("  Nested texture $nestedId: cache data is NULL")
            }
        }
    }

    // Generate pixels
    val w = 128
    val h = 128
    try {
        val pixels = textureType.getPixelsArgb(w, h, { sid ->
            val sData = cache.data(Index.SPRITES, sid) ?: return@getPixelsArgb null
            TextureLoader.decodeSpriteToDataPublic(sData, 0)
        })

        val nonTransparent = pixels.count { (it shr 24) and 0xFF > 0 }
        val nonBlack = pixels.count { (it and 0xFFFFFF) != 0 }
        println("\n  RESULT: ${pixels.size} pixels")
        println("  Non-transparent (alpha > 0): $nonTransparent")
        println("  Non-black (RGB != 0): $nonBlack")

        if (nonBlack > 0) {
            val samples = pixels.filter { (it and 0xFFFFFF) != 0 }.take(10)
            println("  Sample non-black: ${samples.map { "A=${(it shr 24) and 0xFF} R=${(it shr 16) and 0xFF} G=${(it shr 8) and 0xFF} B=${it and 0xFF}" }}")
        }

        if (nonTransparent == 0 && nonBlack == 0) {
            println("\n  *** ALL PIXELS ARE BLACK/TRANSPARENT ***")
            println("  This confirms the bug. Checking intermediate operations...")

            // Re-run with per-operation tracing
            for (op in textureType.textureOperations) {
                op.createImageCache(w, h)
            }
            TextureType.setBrightness(0.7)
            TextureRaster.setDimensions(w, h)

            // Check each operation's output for row 0
            for ((i, op) in textureType.textureOperations.withIndex()) {
                try {
                    val hasOutput: Boolean
                    if (op.monochrome) {
                        val row = op.getMonochromeOutput(0)
                        val nonZero = row.count { it != 0 }
                        hasOutput = nonZero > 0
                        println("    Op[$i] ${op::class.simpleName} (mono): $nonZero/${row.size} non-zero values. First 5: ${row.take(5).toList()}")
                    } else {
                        val palette = op.getColorOutput(0)
                        val nonZeroR = palette[0].count { it != 0 }
                        val nonZeroG = palette[1].count { it != 0 }
                        val nonZeroB = palette[2].count { it != 0 }
                        hasOutput = nonZeroR > 0 || nonZeroG > 0 || nonZeroB > 0
                        println("    Op[$i] ${op::class.simpleName} (color): R=$nonZeroR/$w G=$nonZeroG/$w B=$nonZeroB/$w non-zero. First 5 R: ${palette[0].take(5).toList()}")
                    }
                    if (!hasOutput) {
                        println("    *** Op[$i] ${op::class.simpleName} PRODUCES ALL ZEROS ***")
                    }
                } catch (e: Exception) {
                    println("    Op[$i] ${op::class.simpleName} THREW: ${e.message}")
                }
            }

            // Clean up
            for (op in textureType.textureOperations) {
                op.clearImageCache()
            }
        }
    } catch (e: Exception) {
        println("  ERROR generating pixels: ${e.message}")
        e.printStackTrace()
    }

    println("\n=== TEXTURE DIAGNOSTIC COMPLETE ===")
}

/**
 * Diagnostic to investigate face geometry for coplanarity and render-order occlusion.
 * Prints vertex positions for untextured faces and their nearby textured neighbors
 * to determine if untextured faces are hidden by depth test ordering.
 */
private fun runFaceGeometryDiagnostic(cache: Cache, modelId: Int) {
    println("\n=== FACE GEOMETRY DIAGNOSTIC: Model $modelId ===")

    val def = ModelLoader.loadDefinition(cache, modelId)
    if (def == null) {
        println("ERROR: Could not load model $modelId")
        return
    }

    println("Vertices: ${def.vertexCount}, Faces: ${def.faceCount}")
    println("Texture Triangles: ${def.textureTriangleCount}")

    // Find untextured faces
    println("\n--- UNTEXTURED FACES ---")
    val untexturedFaces = mutableListOf<Int>()
    for (f in 0 until def.faceCount) {
        val texId = def.faceTextures?.get(f)?.toInt() ?: -1
        if (texId == -1) {
            untexturedFaces.add(f)
        }
    }

    if (untexturedFaces.isEmpty()) {
        println("No untextured faces found.")
        println("\n=== DIAGNOSTIC COMPLETE ===")
        return
    }

    println("Found ${untexturedFaces.size} untextured faces: $untexturedFaces")

    for (f in untexturedFaces) {
        val aIdx = def.faceA[f]
        val bIdx = def.faceB[f]
        val cIdx = def.faceC[f]
        val hsl = def.faceColors[f].toInt() and 0xffff
        val renderType = def.faceRenderTypes?.get(f)?.toInt()?.and(0xff) ?: 0
        val alpha = def.faceAlphas?.get(f)?.toInt()?.and(0xff) ?: 0

        println("\n  Face $f: HSL=$hsl, renderType=$renderType, alpha=$alpha")
        println("    A[$aIdx]: (${def.vertexX[aIdx]}, ${def.vertexY[aIdx]}, ${def.vertexZ[aIdx]})")
        println("    B[$bIdx]: (${def.vertexX[bIdx]}, ${def.vertexY[bIdx]}, ${def.vertexZ[bIdx]})")
        println("    C[$cIdx]: (${def.vertexX[cIdx]}, ${def.vertexY[cIdx]}, ${def.vertexZ[cIdx]})")

        // Compute face normal
        val dx1 = (def.vertexX[bIdx] - def.vertexX[aIdx]).toFloat()
        val dy1 = (def.vertexY[bIdx] - def.vertexY[aIdx]).toFloat()
        val dz1 = (def.vertexZ[bIdx] - def.vertexZ[aIdx]).toFloat()
        val dx2 = (def.vertexX[cIdx] - def.vertexX[aIdx]).toFloat()
        val dy2 = (def.vertexY[cIdx] - def.vertexY[aIdx]).toFloat()
        val dz2 = (def.vertexZ[cIdx] - def.vertexZ[aIdx]).toFloat()
        val nx = dy1 * dz2 - dz1 * dy2
        val ny = dz1 * dx2 - dx1 * dz2
        val nz = dx1 * dy2 - dy1 * dx2
        val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0) {
            println("    Normal: (${nx/len}, ${ny/len}, ${nz/len})")
        }

        // Compute face area
        val area = len / 2f
        println("    Area: $area sq units")

        // Compute centroid
        val cx = (def.vertexX[aIdx] + def.vertexX[bIdx] + def.vertexX[cIdx]) / 3f
        val cy = (def.vertexY[aIdx] + def.vertexY[bIdx] + def.vertexY[cIdx]) / 3f
        val cz = (def.vertexZ[aIdx] + def.vertexZ[bIdx] + def.vertexZ[cIdx]) / 3f
        println("    Centroid: ($cx, $cy, $cz)")

        // Find nearby textured faces (sharing at least one vertex)
        val faceVertices = setOf(aIdx, bIdx, cIdx)
        println("    Nearby textured faces sharing vertices:")
        var nearbyCount = 0
        for (other in 0 until def.faceCount) {
            if (other == f) continue
            val otherTexId = def.faceTextures?.get(other)?.toInt() ?: -1
            if (otherTexId == -1) continue // Skip other untextured faces

            val otherVerts = setOf(def.faceA[other], def.faceB[other], def.faceC[other])
            val shared = faceVertices.intersect(otherVerts)
            if (shared.isNotEmpty()) {
                nearbyCount++
                val otherTex = otherTexId and 0xffff
                println("      Face $other (tex=$otherTex): shares vertices $shared")
                println("        A[${def.faceA[other]}]: (${def.vertexX[def.faceA[other]]}, ${def.vertexY[def.faceA[other]]}, ${def.vertexZ[def.faceA[other]]})")
                println("        B[${def.faceB[other]}]: (${def.vertexX[def.faceB[other]]}, ${def.vertexY[def.faceB[other]]}, ${def.vertexZ[def.faceB[other]]})")
                println("        C[${def.faceC[other]}]: (${def.vertexX[def.faceC[other]]}, ${def.vertexY[def.faceC[other]]}, ${def.vertexZ[def.faceC[other]]})")
            }
        }
        if (nearbyCount == 0) {
            println("      (none)")
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
          faces [id] - Print face geometry diagnostic for a model
                        Shows untextured face positions, normals, and
                        nearby textured faces for coplanarity analysis
                        Default model: 68750 (signpost)

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
