package org.darkan.tools

import org.darkan.tools.cache.model.ModelLoader
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
            println("Starting Model Viewer...")
            ModelViewer(cache).run()
        }
        "map", "maps", "terrain" -> {
            println("Starting Map Viewer...")
            MapViewer(cache).run()
        }
        "interface", "interfaces", "ui" -> {
            println("Starting Interface Viewer...")
            InterfaceViewer(cache).run()
        }
        "fountain" -> {
            debugFountain(cache)
        }
        "contour", "contour3" -> {
            scanContourType3(cache)
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
 * Debug the fountain rendering issue by analyzing fountain objects and their models.
 */
private fun debugFountain(cache: Cache) {
    println("\n=== Fountain Debug Tool ===")

    // Load all object definitions
    val objects = Cache.objects
    println("Loaded ${objects.size} object definitions")

    // Search for fountain objects
    println("\n=== Searching for 'fountain' objects ===")
    val fountains = objects.filter { it.name.contains("fountain", ignoreCase = true) }

    for (obj in fountains) {
        println("\n--- Object ID: ${obj.id} ---")
        println("  Name: ${obj.name}")
        println("  Size: ${obj.sizeX}x${obj.sizeY}")
        println("  Shapes: ${obj.shapes?.contentToString()}")
        println("  ModelIds: ${obj.modelIds?.map { it.contentToString() }}")

        // Analyze each model
        obj.modelIds?.forEachIndexed { groupIdx, modelGroup ->
            println("  Model Group $groupIdx (shape=${obj.shapes?.getOrNull(groupIdx)}):")
            for (modelId in modelGroup) {
                val def = ModelLoader.loadDefinition(cache, modelId)
                if (def != null) {
                    println("    Model $modelId: ${def.vertexCount} verts, ${def.faceCount} faces")
                    analyzeModelFaces(def)
                } else {
                    println("    Model $modelId: FAILED TO LOAD")
                }
            }
        }
    }

    // Check specific object IDs that might be fountains
    println("\n=== Checking specific object IDs ===")
    val specificIds = listOf(24214, 24215, 24216, 24267, 878, 879, 880, 881, 882, 883, 884, 885,
                             36974, 36975, 36976, 37267)
    for (id in specificIds) {
        if (id < objects.size) {
            val obj = objects[id]
            if (obj.name.isNotEmpty() && obj.name != "null") {
                println("Object $id: ${obj.name}, models=${obj.modelIds?.map { it.contentToString() }}")
            }
        }
    }

    println("\n=== Debug complete ===")
}

/**
 * Analyze the faces of a model definition and print detailed information.
 */
private fun analyzeModelFaces(def: org.darkan.tools.cache.model.ModelDefinition) {
    var texturedFaces = 0
    var untexturedFaces = 0
    var opaqueFaces = 0      // alpha = 0 in RS means opaque
    var semiTransparentFaces = 0  // 0 < alpha < 255
    var fullyTransparentFaces = 0  // alpha = 255 means invisible
    val textureIds = mutableSetOf<Int>()
    val renderTypes = mutableMapOf<Int, Int>()
    val colors = mutableSetOf<Int>()

    for (i in 0 until def.faceCount) {
        // Check texture
        val texId = def.faceTextures?.get(i)?.toInt() ?: -1
        if (texId >= 0) {
            texturedFaces++
            textureIds.add(texId)
        } else {
            untexturedFaces++
        }

        // Track colors
        colors.add(def.faceColors[i].toInt() and 0xFFFF)

        // Check alpha - RS uses inverted alpha:
        // - alpha = 0 means OPAQUE (fully visible)
        // - alpha = 255 means FULLY TRANSPARENT (invisible)
        val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 0
        when {
            alpha == 0 -> opaqueFaces++
            alpha == 255 -> fullyTransparentFaces++
            else -> semiTransparentFaces++
        }

        // Track render types
        val rt = def.faceRenderTypes?.get(i)?.toInt()?.and(0xff) ?: 0
        renderTypes[rt] = (renderTypes[rt] ?: 0) + 1
    }

    println("      Textured faces: $texturedFaces, Untextured: $untexturedFaces")
    println("      Opaque (alpha=0): $opaqueFaces, Semi-transparent: $semiTransparentFaces, Invisible (alpha=255): $fullyTransparentFaces")
    if (textureIds.isNotEmpty()) {
        println("      Texture IDs used: $textureIds")
    }
    println("      Render types: $renderTypes")
    println("      Unique colors: ${colors.size}")

    // Show bounds
    if (def.vertexCount > 0) {
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        for (i in 0 until def.vertexCount) {
            minX = minOf(minX, def.vertexX[i]); maxX = maxOf(maxX, def.vertexX[i])
            minY = minOf(minY, def.vertexY[i]); maxY = maxOf(maxY, def.vertexY[i])
            minZ = minOf(minZ, def.vertexZ[i]); maxZ = maxOf(maxZ, def.vertexZ[i])
        }
        println("      Bounds X: [$minX, $maxX] Y: [$minY, $maxY] Z: [$minZ, $maxZ]")
    }
}

/**
 * Scan the cache for objects with groundContourType=3 (hillChange terrain alignment).
 */
private fun scanContourType3(cache: Cache) {
    println("\n=== Scanning for objects with groundContourType=3 (hillChange) ===")

    val objects = Cache.objects
    println("Total objects: ${objects.size}")

    // Count by groundContourType
    val countByType = mutableMapOf<Int, Int>()
    val type3ObjectIds = mutableSetOf<Int>()

    for (obj in objects) {
        val type = obj.groundContourType.toInt()
        countByType[type] = (countByType[type] ?: 0) + 1
        if (type == 3) {
            type3ObjectIds.add(obj.id)
        }
    }

    println("\ngroundContourType distribution:")
    for ((type, count) in countByType.toSortedMap()) {
        val typeName = when (type) {
            0 -> "none"
            1 -> "vertex deform"
            2 -> "partial"
            3 -> "hillChange (tilt)"
            4 -> "next level"
            5 -> "stretch"
            else -> "unknown"
        }
        println("  Type $type ($typeName): $count objects")
    }

    println("\n=== Objects with groundContourType=3 (first 20) ===")
    for (objId in type3ObjectIds.take(20)) {
        val obj = objects[objId]
        println("  ID ${obj.id}: ${obj.name}, modifier=${obj.groundContourModifier}")
    }

    if (type3ObjectIds.size > 20) {
        println("  ... and ${type3ObjectIds.size - 20} more")
    }

    // Now scan regions to find ones containing type 3 objects
    println("\n=== Scanning regions for groundContourType=3 objects ===")
    org.darkan.core.map.MapXteas.load()

    val regionsWithType3 = mutableMapOf<Int, Int>() // regionId -> count

    // Scan all valid regions
    for (regionX in 0 until 256) {
        for (regionY in 0 until 256) {
            val regionData = try {
                org.darkan.core.map.RegionLoader.loadRegion(cache, regionX, regionY)
            } catch (e: Exception) {
                null
            }
            if (regionData == null) continue

            val type3Count = regionData.objects.count { type3ObjectIds.contains(it.id) }
            if (type3Count > 0) {
                val regionId = (regionX shl 8) or regionY
                regionsWithType3[regionId] = type3Count
            }
        }
    }

    println("\nRegions containing groundContourType=3 objects (first 20):")
    for ((regionId, count) in regionsWithType3.entries.sortedByDescending { it.value }.take(20)) {
        val regionX = regionId shr 8
        val regionY = regionId and 0xFF
        println("  Region ($regionX, $regionY) ID=$regionId: $count type 3 objects")
    }

    if (regionsWithType3.size > 20) {
        println("  ... and ${regionsWithType3.size - 20} more regions")
    }

    println("\n=== Scan complete ===")
}

private fun printHelp() {
    println("""
        Darkan Tools - Game Asset Viewer

        Usage: ./gradlew :tools:run --args="<mode>"

        Modes:
          model      - View 3D models from the cache (default)
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
          ./gradlew :tools:run --args="map"
          ./gradlew :tools:run --args="interface"
    """.trimIndent())
}
