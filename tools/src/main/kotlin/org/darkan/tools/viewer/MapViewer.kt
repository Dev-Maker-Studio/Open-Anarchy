package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiHoveredFlags
import imgui.flag.ImGuiTreeNodeFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import imgui.type.ImString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.TerrainAligner
import org.darkan.tools.cache.map.VertexDisplacer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainEditManager
import org.darkan.tools.cache.terrain.EditResult
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.input.TilePicker
import org.darkan.tools.engine.render.BatchKey
import org.darkan.tools.engine.render.GeometryTransformer
import org.darkan.tools.engine.render.IndirectRenderer
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.engine.render.RenderMetrics
import org.darkan.tools.engine.render.StaticBatcher
import org.darkan.tools.engine.render.TexturePixelProvider
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.render.TextureAtlas
import org.darkan.tools.engine.render.UniformBuffers
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.ui.DebugPanel
import org.darkan.tools.ui.ImGuiLayer
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.MemoryCache
import world.gregs.voidps.cache.definition.data.MapDefinition

/**
 * Map/terrain viewer with full scene rendering support.
 *
 * Features:
 * - Single-region terrain rendering with actual underlay/overlay colors
 * - XTEA-encrypted object loading
 * - Multi-plane object rendering (renders ALL planes 0-3 simultaneously)
 * - Roof toggling (hide shapes 12-21 to see inside buildings)
 * - Plane selection (0-3) for terrain and bridge shifting
 * - Quick navigation to known locations
 * - Integrated map editor panel with inspection tools
 *
 * Controls:
 * - WASD: Move camera
 * - Space/Shift: Up/Down
 * - Right-click drag: Look around
 * - Scroll: Zoom
 * - Ctrl: Fast movement
 * - Arrow Keys: Navigate regions
 * - PgUp/PgDn: Change plane
 * - 1-4: Select plane directly
 * - F1: Toggle debug panel
 * - F3: Wireframe mode
 * - F5: Toggle map editor panel
 * - T: Toggle terrain
 * - R: Toggle roofs (hide to see inside buildings)
 * - G: Toggle grid
 */
class MapViewer(
    private val cache: Cache
) : Engine(WindowConfig(title = "Darkan Map Viewer", width = 1280, height = 720)) {

    // UBO-based shaders for efficient rendering
    private lateinit var uniformBuffers: UniformBuffers
    private lateinit var uboColorShader: ShaderProgram
    private lateinit var uboTexturedShader: ShaderProgram
    private lateinit var terrainAlphaShader: ShaderProgram

    // Atlas shaders for unified terrain rendering
    private var terrainAtlasShader: ShaderProgram? = null
    private var terrainAtlasAlphaShader: ShaderProgram? = null

    // Terrain texture atlas
    private var terrainAtlas: TextureAtlas? = null
    private val SENTINEL_TEXTURE_ID = Int.MAX_VALUE

    // Object texture atlas for collapsing texture-based draw call splits.
    // Built incrementally as models are registered with the InstanceManager.
    // Separate from the terrain atlas (different lifecycle and texture set).
    private var objectAtlas: TextureAtlas? = null

    // Merged atlas terrain meshes (all 9 regions merged into 2 draw calls)
    private var mergedOpaqueTerrain: Mesh? = null
    private var mergedAlphaTerrain: Mesh? = null

    // Instanced shaders for object rendering (center region)
    private lateinit var instancedColorShader: ShaderProgram
    private lateinit var instancedTexturedShader: ShaderProgram

    // Multi-Draw Indirect shaders and renderer for center region objects (GL 4.3).
    // When available, replaces per-mesh instanced draws with 1-3 MDI calls.
    // Falls back to InstanceManager if initialization fails.
    //
    // DISABLED: The IndirectRenderer causes a render loop hang on first frame after loading.
    // Suspected causes: persistent mapped buffer fence sync issues, glGetTexImage GPU readback
    // stalls during buildTextureArray(), or massive SSBO allocation (~86MB for triple-buffered
    // persistent mapping). The legacy InstanceManager path works correctly.
    // Set useIndirectRendering = true to re-enable for debugging.
    private val useIndirectRendering = false
    private var indirectColorShader: ShaderProgram? = null
    private var indirectTexturedShader: ShaderProgram? = null
    private var indirectRenderer: IndirectRenderer? = null

    // Non-instanced UBO shaders for static batched context objects
    private lateinit var staticColorShader: ShaderProgram
    private lateinit var staticTexturedShader: ShaderProgram

    // Static batcher for context region objects (replaces per-frame instancing)
    private var contextObjectBatcher: StaticBatcher? = null

    // UI
    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel

    // Current state
    private var currentRegionX = 50
    private var currentRegionY = 50
    private var currentPlane = 0

    // UI state
    private val regionXInput = ImInt(50)
    private val regionYInput = ImInt(50)
    private var showTerrain = true
    private var showRoofs = true  // Toggle roof visibility (shapes 12-21)

    // Lighting - brighter values for better model visibility
    private val ambientLight = Vector3f(0.6f, 0.6f, 0.6f)
    private val lightDirection = Vector3f(-0.5f, -1f, -0.5f).normalize()
    private val lightColor = Vector3f(0.8f, 0.8f, 0.8f)
    private val clearColor = Vector3f(0.4f, 0.6f, 0.8f)

    // Known regions for quick navigation
    private val knownRegions = listOf(
        "Lumbridge" to Pair(50, 50),
        "Varrock" to Pair(51, 51),
        "Falador" to Pair(46, 52),
        "Ardougne" to Pair(42, 51),
        "Camelot" to Pair(44, 55),
        "Al Kharid" to Pair(51, 49),
        "Draynor" to Pair(48, 50),
        "Edgeville" to Pair(48, 54),
        "Barbarian Village" to Pair(48, 53),
        "Wilderness" to Pair(48, 55)
    )

    // Region rendering state
    private var editManager: TerrainEditManager? = null
    private var instanceManager: InstanceManager? = null
    private var objectInstances: List<ObjectInstance> = emptyList()
    private val registeredModels = mutableSetOf<Int>()

    // Multi-region context: 8 surrounding regions rendered as static terrain + objects
    private var contextRegions: Array<ContextRegion?> = arrayOfNulls(8)
    private val CONTEXT_OFFSETS = arrayOf(
        intArrayOf(-1, 1),  intArrayOf(0, 1),  intArrayOf(1, 1),
        intArrayOf(-1, 0),                      intArrayOf(1, 0),
        intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1)
    )
    private val centerModelMatrix = Matrix4f()
    private val contextModelMatrices = Array(8) { Matrix4f() }

    // Displaced model tracking for contourType 1 objects.
    // Maps ObjectInstance identity hash -> displaced model ID.
    // Each contourType 1 instance gets unique geometry with per-vertex terrain displacement.
    private val displacedModels = mutableMapOf<Int, Int>()
    private var nextDisplacedId = Int.MIN_VALUE / 2

    // Spatial index for object-tile lookup (populated for future terrain reactivity)
    private val objectsByTile = mutableMapOf<Long, MutableList<ObjectInstance>>()

    // Pre-allocated texture binding callback to avoid per-frame lambda allocation
    private val terrainTextureBinding: (Int) -> Unit = { texId ->
        TextureLoader.bindTexture(TextureLoader.getTextureForMaterial(cache, texId))
    }

    // Tile picker for debugging UV issues
    private val tilePicker = TilePicker()
    private var terrainData: TerrainLoader.RegionData? = null
    private var mapObjects: List<MapObject> = emptyList()  // Raw MapObjects for tile picker
    private var tileHighlightMesh: Mesh? = null

    // Map editor panel state
    private var showEditorPanel = true
    private var editorPanelFirstFrame = true
    private var currentEditorTool = EditorTool.SELECT

    // Editor panel input state
    private val editorObjectSearchQuery = ImString(256)
    private val editorOverlayUnderlayId = ImInt(0)
    private var editorPaintMode = 0  // 0 = Overlay, 1 = Underlay
    private val editorBrushSize = intArrayOf(1)
    private val editorHeightBrushRadius = intArrayOf(1)
    private val editorHeightStrength = intArrayOf(5)
    private var editorHeightMode = 0  // 0 = Raise, 1 = Lower, 2 = Smooth

    override fun onInit() {
        // Initialize texture loader
        TextureLoader.init(cache)

        // Initialize ImGui
        imGui = ImGuiLayer(window)
        imGui.init()
        debugPanel = DebugPanel()

        // Initialize uniform buffers
        uniformBuffers = UniformBuffers()
        uniformBuffers.init()

        // Load UBO shaders
        loadUBOShaders()

        // Load floor and object definitions
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)

        // Build terrain texture atlas from all overlay/underlay texture definitions
        buildTerrainAtlas()

        // Create object texture atlas for collapsing texture-based draw call splits.
        // Textures are packed incrementally as models register with the InstanceManager.
        // Uses 128px texture slots with 8px border on a 4096x4096 atlas page,
        // giving 784 slots per page (typically sufficient for all object textures in a 3x3 view).
        objectAtlas = TextureAtlas(atlasSize = 4096, textureSize = 128, border = 8)

        // Setup the scene camera (will be repositioned when region loads)
        scene.camera.setPosition(0f, 50f, 50f)
        scene.camera.pitch = -45f
        scene.camera.yaw = -90f
        scene.camera.moveSpeed = 20f
        scene.camera.farPlane = 4000f

        // Enable depth testing and multisampling
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_MULTISAMPLE)

        // Load XTEA keys for object loading
        if (!MapXteas.isLoaded()) {
            MapXteas.load()
        }
        loadRegion(currentRegionX, currentRegionY, currentPlane)
    }

    /**
     * Build the terrain texture atlas containing all overlay/underlay textures.
     * Scans all overlay and underlay definitions for unique texture IDs, generates
     * each texture's pixels via the procedural texture system, and packs them into
     * a single atlas. Also adds a 1x1 white sentinel pixel for untextured (color-only)
     * tiles so they can share the same shader as textured tiles.
     *
     * After building, wires up [TerrainLoader.atlasLookup] and
     * [TerrainLoader.sentinelAtlasTransform] so that [TerrainLoader.computeTerrainMeshData]
     * produces per-vertex atlas UV transforms.
     */
    private fun buildTerrainAtlas() {
        val textureIds = TerrainLoader.getUniqueTextureIds()
        if (textureIds.isEmpty()) {
            println("[MapViewer] No terrain textures found in definitions, atlas disabled")
            return
        }

        println("[MapViewer] Building terrain atlas with ${textureIds.size} textures...")
        System.out.flush()
        val startTime = System.nanoTime()
        val atlas = TextureAtlas(atlasSize = 4096, textureSize = 128, border = 8)

        var loaded = 0
        var failed = 0
        val sortedIds = textureIds.sorted()
        for ((index, texId) in sortedIds.withIndex()) {
            print("[MapViewer]   Texture $texId (${index + 1}/${sortedIds.size})...")
            System.out.flush()
            try {
                val result = TextureLoader.getTexturePixelsForMaterial(cache, texId)
                if (result != null) {
                    val (pixels, width, height) = result
                    atlas.addTexture(texId, pixels, width, height)
                    loaded++
                    println(" OK")
                } else {
                    failed++
                    println(" null")
                }
            } catch (e: Throwable) {
                println(" FAILED: ${e.message}")
                failed++
            }
        }
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        println("[MapViewer]   Texture generation completed in ${elapsed}ms: $loaded loaded, $failed failed")

        // Add sentinel white pixel texture for color-only tiles.
        // A 1x1 pure white pixel means texColor.rgb = (1,1,1), so
        // finalColor = texColor * vertexColor = vertexColor (correct for untextured tiles).
        val whitePixel = intArrayOf(0xFFFFFFFF.toInt())
        atlas.addTexture(SENTINEL_TEXTURE_ID, whitePixel, 1, 1)

        // Upload to GPU
        atlas.upload()
        terrainAtlas = atlas

        val stats = atlas.getStats()
        println("[MapViewer] Terrain atlas built: ${stats.textureCount} textures on ${stats.pageCount} page(s), " +
                "$loaded loaded, $failed failed, utilization ${(stats.utilization * 100).toInt()}%")

        // Wire up TerrainLoader atlas lookup so computeTerrainMeshData produces
        // per-vertex atlas transform arrays.
        val sentinelRegion = atlas.getRegion(SENTINEL_TEXTURE_ID)!!
        TerrainLoader.atlasLookup = { textureId ->
            val region = atlas.getRegion(textureId) ?: sentinelRegion
            region.getTransformVec4()
        }
        TerrainLoader.sentinelAtlasTransform = sentinelRegion.getTransformVec4()
    }

    private fun loadUBOShaders() {
        // Load terrain color shader with RGB vertex colors
        val terrainColorVert = loadShaderSource("/shaders/terrain_color.vert")
        val terrainColorFrag = loadShaderSource("/shaders/terrain_color.frag")

        uboColorShader = ShaderProgram.createWithUniforms(
            terrainColorVert, terrainColorFrag,
            "model"
        )
        uniformBuffers.bindToShader(uboColorShader)

        // Load terrain textured shader with RGB vertex colors
        val terrainTexturedVert = loadShaderSource("/shaders/terrain_textured.vert")
        val terrainTexturedFrag = loadShaderSource("/shaders/terrain_textured.frag")

        uboTexturedShader = ShaderProgram.createWithUniforms(
            terrainTexturedVert, terrainTexturedFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(uboTexturedShader)

        // Load terrain alpha shader for multi-pass alpha blending of underlay textures
        val terrainAlphaVert = loadShaderSource("/shaders/terrain_alpha.vert")
        val terrainAlphaFrag = loadShaderSource("/shaders/terrain_alpha.frag")

        terrainAlphaShader = ShaderProgram.createWithUniforms(
            terrainAlphaVert, terrainAlphaFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(terrainAlphaShader)

        // Load instanced shaders (for center region objects - use standard RGB shaders)
        val instancedColorVert = loadShaderSource("/shaders/ubo_instanced.vert")
        val instancedColorFrag = loadShaderSource("/shaders/ubo_color.frag")
        instancedColorShader = ShaderProgram.create(instancedColorVert, instancedColorFrag)
        uniformBuffers.bindToShader(instancedColorShader)

        val instancedTexturedVert = loadShaderSource("/shaders/ubo_instanced_textured.vert")
        val instancedTexturedFrag = loadShaderSource("/shaders/ubo_textured.frag")
        instancedTexturedShader = ShaderProgram.createWithUniforms(
            instancedTexturedVert, instancedTexturedFrag,
            "textureSampler"
        )
        uniformBuffers.bindToShader(instancedTexturedShader)

        // Load Multi-Draw Indirect shaders (GL 4.3 SSBO-based).
        // These read instance matrices from an SSBO indexed by gl_BaseInstance + gl_InstanceID
        // instead of per-instance vertex attributes. Falls back gracefully if shaders fail.
        if (useIndirectRendering) {
            try {
                val indColorVert = loadShaderSource("/shaders/indirect_color.vert")
                val indColorFrag = loadShaderSource("/shaders/indirect_color.frag")
                indirectColorShader = ShaderProgram.create(indColorVert, indColorFrag)
                uniformBuffers.bindToShader(indirectColorShader!!)

                val indTexturedVert = loadShaderSource("/shaders/indirect_textured.vert")
                val indTexturedFrag = loadShaderSource("/shaders/indirect_textured.frag")
                indirectTexturedShader = ShaderProgram.createWithUniforms(
                    indTexturedVert, indTexturedFrag,
                    "textureArray"
                )
                uniformBuffers.bindToShader(indirectTexturedShader!!)

                println("[MapViewer] Indirect draw shaders loaded successfully")
            } catch (e: Exception) {
                System.err.println("[MapViewer] Failed to load indirect draw shaders, falling back to instanced rendering: ${e.message}")
                indirectColorShader?.cleanup()
                indirectColorShader = null
                indirectTexturedShader?.cleanup()
                indirectTexturedShader = null
            }
        } else {
            println("[MapViewer] Indirect rendering disabled, using legacy instanced path")
        }

        // Load non-instanced UBO shaders for static batched context region objects.
        // These use the same UBO blocks as instanced shaders but take a model uniform
        // instead of per-instance matrix attributes.
        val staticColorVert = loadShaderSource("/shaders/ubo_color.vert")
        val staticColorFrag = loadShaderSource("/shaders/ubo_color.frag")
        staticColorShader = ShaderProgram.createWithUniforms(
            staticColorVert, staticColorFrag,
            "model"
        )
        uniformBuffers.bindToShader(staticColorShader)

        val staticTexturedVert = loadShaderSource("/shaders/ubo_textured.vert")
        val staticTexturedFrag = loadShaderSource("/shaders/ubo_textured.frag")
        staticTexturedShader = ShaderProgram.createWithUniforms(
            staticTexturedVert, staticTexturedFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(staticTexturedShader)

        // Load atlas terrain shaders
        val atlasVert = loadShaderSource("/shaders/terrain_atlas.vert")
        val atlasFrag = loadShaderSource("/shaders/terrain_atlas.frag")
        terrainAtlasShader = ShaderProgram.createWithUniforms(
            atlasVert, atlasFrag,
            "model", "atlasTexture"
        )
        uniformBuffers.bindToShader(terrainAtlasShader!!)

        val atlasAlphaVert = loadShaderSource("/shaders/terrain_atlas_alpha.vert")
        val atlasAlphaFrag = loadShaderSource("/shaders/terrain_atlas_alpha.frag")
        terrainAtlasAlphaShader = ShaderProgram.createWithUniforms(
            atlasAlphaVert, atlasAlphaFrag,
            "model", "atlasTexture"
        )
        uniformBuffers.bindToShader(terrainAtlasAlphaShader!!)
    }

    override fun onInput() {
        if (imGui.wantCaptureKeyboard() || imGui.wantCaptureMouse()) {
            return
        }

        val cam = scene.camera

        // Speed multiplier
        val speedMult = if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_CONTROL)) 4f else 1f
        cam.moveSpeed = 20f * speedMult

        // Camera movement
        if (input.keyboard.isKeyDown(GLFW_KEY_W)) cam.moveForward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_S)) cam.moveBackward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_A)) cam.moveLeft(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_D)) cam.moveRight(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_SPACE)) cam.moveUp(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_SHIFT)) cam.moveDown(deltaTime)

        // Camera rotation
        if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_RIGHT)) {
            cam.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
        }

        // Scroll zoom
        if (input.mouse.scrollY != 0.0) {
            cam.zoom(input.mouse.scrollY.toFloat())
        }

        // Toggle keys
        if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) debugPanel.toggleVisibility()
        if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) renderSystem.toggleWireframe()
        if (input.keyboard.isKeyPressed(GLFW_KEY_T)) showTerrain = !showTerrain
        if (input.keyboard.isKeyPressed(GLFW_KEY_R)) {
            showRoofs = !showRoofs
            reloadRegion()
        }

        // Plane selection (1-4 keys)
        if (input.keyboard.isKeyPressed(GLFW_KEY_1)) setPlane(0)
        if (input.keyboard.isKeyPressed(GLFW_KEY_2)) setPlane(1)
        if (input.keyboard.isKeyPressed(GLFW_KEY_3)) setPlane(2)
        if (input.keyboard.isKeyPressed(GLFW_KEY_4)) setPlane(3)

        // Region navigation (arrow keys)
        if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT)) navigateRegion(-1, 0)
        if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT)) navigateRegion(1, 0)
        if (input.keyboard.isKeyPressed(GLFW_KEY_UP)) navigateRegion(0, 1)
        if (input.keyboard.isKeyPressed(GLFW_KEY_DOWN)) navigateRegion(0, -1)

        // Plane navigation (PgUp/PgDn)
        if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_UP)) {
            if (currentPlane < 3) setPlane(currentPlane + 1)
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_DOWN)) {
            if (currentPlane > 0) setPlane(currentPlane - 1)
        }

        // Toggle map editor panel (F5)
        if (input.keyboard.isKeyPressed(GLFW_KEY_F5)) {
            showEditorPanel = !showEditorPanel
            if (showEditorPanel) {
                editorPanelFirstFrame = true
            }
        }

        // Tile picking (left-click)
        if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            handleTilePick()
        }
    }

    /**
     * Handle tile picking via left-click.
     */
    private fun handleTilePick() {
        val td = terrainData ?: return
        val cam = scene.camera
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Set the world offset for the tile picker so it converts world-space
        // ray positions into local tile coordinates (0-63)
        tilePicker.worldOffsetX = currentRegionX * 64 * tileSize
        tilePicker.worldOffsetZ = -currentRegionY * 64 * tileSize

        // Convert mouse position to world ray
        tilePicker.screenToWorldRay(
            input.mouse.x.toFloat(),
            input.mouse.y.toFloat(),
            window.width,
            window.height,
            cam,
            getAspectRatio()
        )

        // Pick tile
        val selectedTile = tilePicker.pickTile(
            td.tiles,
            currentRegionX,
            currentRegionY,
            currentPlane
        )

        if (selectedTile != null) {
            // Update tile details with object lookup
            tilePicker.updateTileDetails(
                td.tiles,
                cache,
                mapObjects,
                currentRegionX * 64,
                currentRegionY * 64
            )

            // Update highlight mesh
            updateTileHighlight(td.tiles)
        }
    }

    /**
     * Update the tile highlight mesh for the selected tile.
     */
    private fun updateTileHighlight(terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>) {
        val tile = tilePicker.selectedTile ?: return

        // Clean up old mesh
        tileHighlightMesh?.cleanup()

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()

        // Get corner heights
        val x = tile.localX
        val y = tile.localY
        val nextX = (x + 1).coerceAtMost(63)
        val nextY = (y + 1).coerceAtMost(63)

        val h00 = terrainTiles[tile.plane][x][y].height * heightScale
        val h10 = terrainTiles[tile.plane][nextX][y].height * heightScale
        val h01 = terrainTiles[tile.plane][x][nextY].height * heightScale
        val h11 = terrainTiles[tile.plane][nextX][nextY].height * heightScale

        // Calculate WORLD positions for corners (with small Y offset to prevent z-fighting)
        // Offset by the center region's world origin so highlight aligns with world-space terrain
        val worldOriginX = currentRegionX * 64 * tileSize
        val worldOriginZ = -currentRegionY * 64 * tileSize
        val yOffset = 0.1f
        val x0 = worldOriginX + x * tileSize
        val x1 = worldOriginX + (x + 1) * tileSize
        val z0 = worldOriginZ + (-y * tileSize)
        val z1 = worldOriginZ + (-(y + 1) * tileSize)

        // Create line mesh for tile outline
        val positions = floatArrayOf(
            // Bottom edge (SW -> SE)
            x0, h00 + yOffset, z0,
            x1, h10 + yOffset, z0,
            // Right edge (SE -> NE)
            x1, h10 + yOffset, z0,
            x1, h11 + yOffset, z1,
            // Top edge (NE -> NW)
            x1, h11 + yOffset, z1,
            x0, h01 + yOffset, z1,
            // Left edge (NW -> SW)
            x0, h01 + yOffset, z1,
            x0, h00 + yOffset, z0
        )

        // Bright yellow color for highlight
        val colors = FloatArray(8 * 3) { i ->
            when (i % 3) {
                0 -> 1f  // R
                1 -> 1f  // G
                2 -> 0f  // B
                else -> 1f
            }
        }

        val indices = IntArray(8) { it }

        tileHighlightMesh = Mesh(
            positions = positions,
            colors = colors,
            textureCoords = null,
            normals = null,
            indices = indices,
            colorComponents = 3
        )
    }

    override fun onUpdate(deltaTime: Float) {
        // Process any pending terrain edits (dirty chunk rebuilds)
        val editResult = editManager?.processEdits() ?: EditResult.NONE
        // editResult.hasChanges can be used later for object reactivity (e.g., repositioning
        // objects on modified terrain)

        // When terrain edits occur, the merged atlas terrain becomes stale.
        // Invalidate it so the renderer falls back to the legacy per-region path
        // until the next full region reload rebuilds the merged meshes.
        if (editResult.hasChanges && mergedOpaqueTerrain != null) {
            mergedOpaqueTerrain?.cleanup()
            mergedOpaqueTerrain = null
            mergedAlphaTerrain?.cleanup()
            mergedAlphaTerrain = null
        }
    }

    // Frame counter for debug logging (only logs first few frames to avoid spam)
    private var frameNumber = 0

    override fun onRender() {
        val logFrame = frameNumber < 5
        if (logFrame) {
            println("[RENDER] Frame $frameNumber: onRender() start")
            System.out.flush()
        }

        // Reset per-frame performance counters
        RenderMetrics.resetFrame()

        // Clear
        glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val renderStart = RenderMetrics.startTiming()
        renderScene()
        RenderMetrics.renderTimeMs = RenderMetrics.endTiming(renderStart)

        if (logFrame) {
            println("[RENDER] Frame $frameNumber: renderScene() done in ${RenderMetrics.renderTimeMs}ms")
            System.out.flush()
        }

        // Render UI
        renderUI()

        if (logFrame) {
            println("[RENDER] Frame $frameNumber: onRender() complete")
            System.out.flush()
        }
        frameNumber++
    }

    private fun renderScene() {
        val logFrame = frameNumber < 5

        // Update UBOs for lighting
        uniformBuffers.updateCamera(
            scene.camera.getViewMatrix(),
            scene.camera.getProjectionMatrix(getAspectRatio()),
            scene.camera.position
        )
        uniformBuffers.updateLighting(ambientLight, lightDirection, lightColor)

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        }

        // Render terrain - use atlas path (2 draw calls) when available,
        // fall back to legacy multi-pass path otherwise
        val atlas = terrainAtlas
        val opaqueAtlasMesh = mergedOpaqueTerrain
        val alphaAtlasMesh = mergedAlphaTerrain
        val atlasShader = terrainAtlasShader
        val atlasAlphaShader = terrainAtlasAlphaShader

        if (atlas != null && atlasShader != null && atlasAlphaShader != null && (opaqueAtlasMesh != null || alphaAtlasMesh != null)) {
            // ===== Atlas rendering path: 2 draw calls for ALL terrain =====
            renderSystem.disableCullFace()
            val identityModel = Matrix4f().identity()

            // Bind atlas texture to GL_TEXTURE0
            atlas.bindPage(0, 0)

            // Draw 1: Opaque terrain (color + textured, merged with atlas)
            if (opaqueAtlasMesh != null) {
                atlasShader.bind()
                atlasShader.setUniform("model", identityModel)
                atlasShader.setUniform("atlasTexture", 0)
                opaqueAtlasMesh.render()
                RenderMetrics.terrainOpaqueDrawCalls++
                RenderMetrics.trianglesSubmitted += opaqueAtlasMesh.vertexCount / 3
            }

            // Draw 2: Alpha terrain (with GL alpha blending state)
            if (alphaAtlasMesh != null) {
                glDepthFunc(GL_LEQUAL)
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                atlasAlphaShader.bind()
                atlasAlphaShader.setUniform("model", identityModel)
                atlasAlphaShader.setUniform("atlasTexture", 0)
                alphaAtlasMesh.render()
                RenderMetrics.terrainAlphaDrawCalls++
                RenderMetrics.trianglesSubmitted += alphaAtlasMesh.vertexCount / 3

                glDisable(GL_BLEND)
                glDepthMask(true)
                glDepthFunc(GL_LESS)
            }

            // Unbind atlas texture
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, 0)

            renderSystem.enableCullFace()
        } else {
            // ===== Legacy rendering path: multi-pass per-texture draws =====
            editManager?.let { em ->
                renderSystem.disableCullFace()

                // Pass 1: Color mesh (untextured tiles) with RGB colors
                uboColorShader.bind()
                em.renderColor(uboColorShader, centerModelMatrix)
                RenderMetrics.terrainOpaqueDrawCalls++ // center color mesh
                for (i in contextRegions.indices) {
                    val ctx = contextRegions[i] ?: continue
                    uboColorShader.setUniform("model", contextModelMatrices[i])
                    ctx.terrainMeshes.colorMesh?.let { mesh ->
                        mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
                    }
                }

                // Pass 2: Textured terrain (single texture per triangle)
                uboTexturedShader.bind()
                uboTexturedShader.setUniform("textureSampler", 0)
                em.renderTextured(uboTexturedShader, centerModelMatrix, terrainTextureBinding)
                for (i in contextRegions.indices) {
                    val ctx = contextRegions[i] ?: continue
                    uboTexturedShader.setUniform("model", contextModelMatrices[i])
                    for (batch in ctx.terrainMeshes.texturedBatches) {
                        terrainTextureBinding(batch.textureId)
                        batch.mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                    }
                }
                TextureLoader.unbindTexture()

                // Pass 3: Alpha terrain (multi-pass alpha blending)
                glDepthFunc(GL_LEQUAL)
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                terrainAlphaShader.bind()
                terrainAlphaShader.setUniform("textureSampler", 0)
                em.renderAlpha(terrainAlphaShader, centerModelMatrix, terrainTextureBinding)
                for (i in contextRegions.indices) {
                    val ctx = contextRegions[i] ?: continue
                    terrainAlphaShader.setUniform("model", contextModelMatrices[i])
                    for (batch in ctx.terrainMeshes.alphaBatches) {
                        terrainTextureBinding(batch.textureId)
                        batch.mesh.render()
                        RenderMetrics.terrainAlphaDrawCalls++
                        RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                    }
                }

                TextureLoader.unbindTexture()
                glDisable(GL_BLEND)
                glDepthMask(true)
                glDepthFunc(GL_LESS)

                renderSystem.enableCullFace()
            }
        }

        // Render tile highlight (if any selected tile)
        renderTileHighlight()

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:   terrain done, starting renderObjects()")
            System.out.flush()
        }

        // Render objects
        renderObjects()

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:   renderObjects() done")
            System.out.flush()
        }

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        }
    }

    /**
     * Render the tile highlight outline for the selected tile.
     */
    private fun renderTileHighlight() {
        val highlightMesh = tileHighlightMesh ?: return

        // Use simple shader for the highlight (no lighting)
        simpleShader.bind()
        simpleShader.setUniform("model", Matrix4f().identity())
        simpleShader.setUniform("view", scene.camera.getViewMatrix())
        simpleShader.setUniform("projection", scene.camera.getProjectionMatrix(getAspectRatio()))

        // Disable depth test to always show highlight, or enable with slight offset
        glDisable(GL_DEPTH_TEST)

        // Set line width (if supported)
        glLineWidth(3f)

        // Render as lines
        highlightMesh.renderLines()

        // Restore state
        glLineWidth(1f)
        glEnable(GL_DEPTH_TEST)
    }

    /**
     * Pre-computed instance entry from the parallel transform phase.
     * Contains the model ID and the raw 16-float column-major matrix data
     * ready for direct upload to the GPU via [InstanceManager.addInstanceRaw].
     */
    private data class InstanceEntry(val modelId: Int, val matrixData: FloatArray)

    /** Chunk size for parallel transform computation. */
    private val PARALLEL_CHUNK_SIZE = 512

    private fun renderObjects() {
        val im = instanceManager ?: return
        val logFrame = frameNumber < 5

        // Only instance center region objects -- context region objects are statically batched.
        // Snapshot the displaced models map for thread-safe reads.
        val displacedSnapshot = displacedModels
        val centerObjects = objectInstances

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     renderObjects: ${centerObjects.size} objects")
            System.out.flush()
        }

        // --- Parallel phase: compute transforms on Dispatchers.Default ---
        // Each coroutine gets its own thread-local Matrix4f to avoid allocation
        // on the hot path. The transform computation is pure math with no GL calls.
        val transformStart = RenderMetrics.startTiming()
        val entries: List<InstanceEntry> = if (centerObjects.size <= PARALLEL_CHUNK_SIZE) {
            // Small object count: compute sequentially to avoid coroutine overhead
            val tempMatrix = Matrix4f()
            centerObjects.map { obj ->
                computeInstanceEntry(obj, displacedSnapshot, tempMatrix)
            }
        } else {
            runBlocking(Dispatchers.Default) {
                centerObjects.chunked(PARALLEL_CHUNK_SIZE).map { chunk ->
                    async {
                        val tempMatrix = Matrix4f()  // Thread-local: one per coroutine
                        chunk.map { obj ->
                            computeInstanceEntry(obj, displacedSnapshot, tempMatrix)
                        }
                    }
                }.awaitAll().flatten()
            }
        }
        RenderMetrics.transformTimeMs = RenderMetrics.endTiming(transformStart)

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     transforms done in ${RenderMetrics.transformTimeMs}ms")
            System.out.flush()
        }

        // --- Sequential phase: GL calls on main thread ---
        val ir = indirectRenderer
        val useIndirect = ir != null && ir.isReady()

        val uploadStart = RenderMetrics.startTiming()
        var addedCount = 0

        if (useIndirect) {
            // Multi-Draw Indirect path: feed instances into IndirectRenderer
            ir.prepareFrame()
            for (entry in entries) {
                if (ir.addInstanceRaw(entry.modelId, entry.matrixData)) {
                    addedCount++
                }
            }
            ir.uploadAll()
        } else {
            // Legacy instanced path: feed instances into InstanceManager
            im.prepareFrame()
            for (entry in entries) {
                if (im.addInstanceRaw(entry.modelId, entry.matrixData)) {
                    addedCount++
                }
            }
            im.uploadAll()
        }

        RenderMetrics.uploadTimeMs = RenderMetrics.endTiming(uploadStart)
        RenderMetrics.instanceCount = addedCount

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     upload done in ${RenderMetrics.uploadTimeMs}ms, $addedCount instances, indirect=$useIndirect")
            System.out.flush()
        }

        // IMPORTANT: Disable face culling for RS models which often have two-sided faces
        renderSystem.disableCullFace()

        if (useIndirect) {
            // === Multi-Draw Indirect rendering: 1-2 draw calls for ALL center objects ===
            val indColorShader = indirectColorShader!!
            val indTexShader = indirectTexturedShader!!

            // Render untextured objects (1 MDI call)
            ir.renderUntextured(indColorShader)

            // Render textured objects (1 MDI call) with proper GL state
            renderSystem.enableBlending()
            renderSystem.enableAlphaToCoverage()

            indTexShader.bind()
            indTexShader.setUniform("textureArray", 0)
            ir.renderTextured(indTexShader)

            renderSystem.disableBlending()
            renderSystem.disableAlphaToCoverage()

            // Capture MDI draw call count
            RenderMetrics.indirectDrawCalls = ir.getDrawCallCount()
            RenderMetrics.trianglesSubmitted += ir.getTotalTrianglesSubmitted()
        } else {
            // === Legacy instanced rendering: per-mesh draw calls ===

            // Render instanced center region objects (untextured)
            im.renderUntextured(instancedColorShader)

            // Render instanced center region objects (textured) with proper GL state
            renderSystem.enableBlending()
            renderSystem.enableAlphaToCoverage()

            instancedTexturedShader.bind()
            instancedTexturedShader.setUniform("textureSampler", 0)

            if (im.objectAtlas != null) {
                im.renderTexturedWithAtlas(instancedTexturedShader) { materialId ->
                    val glTexId = TextureLoader.getTextureForMaterial(cache, materialId)
                    TextureLoader.bindTexture(glTexId)
                }
            } else {
                im.renderTextured(instancedTexturedShader) { materialId ->
                    val glTexId = TextureLoader.getTextureForMaterial(cache, materialId)
                    TextureLoader.bindTexture(glTexId)
                }
            }

            TextureLoader.unbindTexture()
            renderSystem.disableBlending()
            renderSystem.disableAlphaToCoverage()

            // Capture instanced draw call count from InstanceManager
            val instanceStats = im.getStats()
            RenderMetrics.instancedDrawCalls = instanceStats.drawCalls
            RenderMetrics.trianglesSubmitted += countInstancedTriangles(im)
        }

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     instanced rendering done")
            System.out.flush()
        }

        // Render static batched context region objects
        renderStaticBatchedObjects()

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     static batched objects done")
            System.out.flush()
        }

        renderSystem.enableCullFace()
    }

    /**
     * Render context region objects from static batched meshes.
     * These meshes have geometry pre-transformed to world space during region loading,
     * so they render with an identity model matrix and no per-frame transform computation.
     *
     * Batches are keyed by [BatchKey]:
     * - UNTEXTURED: vertex colors only, drawn with [staticColorShader]
     * - Atlas page (atlasPageIndex >= 0): drawn with [staticTexturedShader], atlas page bound
     * - Fallback texture (textureId >= 0, atlasPageIndex == -1): drawn with [staticTexturedShader],
     *   individual texture bound via TextureLoader
     */
    private fun renderStaticBatchedObjects() {
        val batcher = contextObjectBatcher ?: return
        val meshes = batcher.getAllMeshes()
        if (meshes.isEmpty()) return

        RenderMetrics.staticBatchMeshCount = meshes.size

        val identityModel = Matrix4f().identity()
        val atlas = objectAtlas

        // Render untextured batches
        val untexturedMesh = meshes[BatchKey.UNTEXTURED]
        if (untexturedMesh != null) {
            staticColorShader.bind()
            staticColorShader.setUniform("model", identityModel)
            untexturedMesh.render()
            RenderMetrics.staticBatchDrawCalls++
            RenderMetrics.trianglesSubmitted += untexturedMesh.vertexCount / 3
        }

        // Render textured batches (atlas pages and fallback textures)
        val texturedKeys = meshes.keys.filter { it != BatchKey.UNTEXTURED }
        if (texturedKeys.isEmpty()) return

        renderSystem.enableBlending()
        renderSystem.enableAlphaToCoverage()

        staticTexturedShader.bind()
        staticTexturedShader.setUniform("model", identityModel)
        staticTexturedShader.setUniform("textureSampler", 0)

        // Sort by atlas page index to minimize texture binds
        val sortedKeys = texturedKeys.sortedBy { it.atlasPageIndex }

        var lastBoundPage = -2  // -2 = nothing bound yet
        for (key in sortedKeys) {
            val mesh = meshes[key] ?: continue

            if (key.atlasPageIndex >= 0 && atlas != null) {
                // Atlas page batch: bind the atlas page
                if (key.atlasPageIndex != lastBoundPage) {
                    atlas.bindPage(key.atlasPageIndex, 0)
                    lastBoundPage = key.atlasPageIndex
                }
            } else if (key.textureId >= 0) {
                // Fallback texture batch: bind individual texture
                val glTexId = TextureLoader.getTextureForMaterial(cache, key.textureId)
                TextureLoader.bindTexture(glTexId)
                lastBoundPage = -2
            }

            mesh.render()
            RenderMetrics.staticBatchDrawCalls++
            RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
        }

        TextureLoader.unbindTexture()
        renderSystem.disableBlending()
        renderSystem.disableAlphaToCoverage()
    }

    /**
     * Count the total triangles rendered by the InstanceManager this frame.
     * Delegates to [InstanceManager.getTotalTrianglesSubmitted] which sums
     * (vertexCount/3 * instanceCount) for each active instanced mesh.
     */
    private fun countInstancedTriangles(im: InstanceManager): Int {
        return im.getTotalTrianglesSubmitted()
    }

    /**
     * Compute an [InstanceEntry] for a single object instance.
     * This is a pure math function with no GL calls, safe for use from any thread.
     *
     * @param obj The object instance to compute the transform for
     * @param displacedSnapshot Read-only snapshot of displaced model ID overrides
     * @param tempMatrix Thread-local Matrix4f reused across calls to avoid allocation
     * @return An InstanceEntry with the model ID and pre-computed raw matrix data
     */
    private fun computeInstanceEntry(
        obj: ObjectInstance,
        displacedSnapshot: Map<Int, Int>,
        tempMatrix: Matrix4f
    ): InstanceEntry {
        // For contourType 1 objects with displacement, use the displaced model ID
        // so the rendering path picks up the displaced geometry.
        // Fall back to the cached combined model ID for all other objects.
        val combinedModelId = displacedSnapshot[System.identityHashCode(obj)]
            ?: obj.cachedCombinedModelId

        // Build transform matrix matching client order:
        // RS client applies to model vertices: Rotate Y -> Terrain Tilt -> Scale -> Offset
        // Then model is placed at world position with height adjustment
        // Matrix operations are right-to-left, so we specify them in reverse order
        //
        // For terrain alignment (groundContourType=3), the client's hillChange applies:
        // 1. X-axis rotation (pitch tilt based on N/S slope)
        // 2. Z-axis rotation (roll tilt based on E/W slope)
        // 3. Y translation (height adjustment to sit on lowest corner)
        tempMatrix.identity()
            .translate(obj.worldPosition)        // 6. Move to world position

        // Apply terrain alignment (height adjustment + tilt rotations)
        if (obj.terrainAlignment.needsAlignment()) {
            // Height adjustment from terrain alignment (after world position)
            tempMatrix.translate(0f, obj.terrainAlignment.heightAdjustment, 0f)
        }

        tempMatrix
            .translate(obj.offset)               // 5. Apply definition offset
            .scale(obj.scale)                    // 4. Scale

        // Apply terrain tilt rotations (after Y rotation in application order)
        // RS applies: RotateY -> TiltX -> TiltZ (in that order on vertices)
        // Matrix equivalent (right-to-left): TiltZ * TiltX * RotateY
        if (obj.terrainAlignment.needsAlignment()) {
            tempMatrix.rotateZ(obj.terrainAlignment.tiltZ)  // 3. Roll tilt (last in app order)
            tempMatrix.rotateX(obj.terrainAlignment.tiltX)  // 2. Pitch tilt
        }

        tempMatrix.rotateY(obj.rotation)   // 1. Object Y rotation (first in app order)

        // Apply mirroring if needed (negates Z coordinates)
        // This is applied last in the matrix chain = first on vertices
        if (obj.mirrored) {
            tempMatrix.scale(1f, 1f, -1f)  // 0. Mirror across XY plane
        }

        val matrixData = FloatArray(16)
        tempMatrix.get(matrixData)
        return InstanceEntry(combinedModelId, matrixData)
    }

    // ========================================================================
    // UI Rendering
    // ========================================================================

    private fun renderUI() {
        imGui.beginFrame()

        // Debug panel
        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera, window.width, window.height) {
            renderSystem.toggleWireframe()
        }

        // Map editor panel (sole control surface)
        if (showEditorPanel) {
            renderMapEditorPanel()
        }

        imGui.endFrame()
    }

    /**
     * Render the unified Map Editor panel.
     * Contains navigation, view toggles, tool palette, tool content,
     * rendering settings, and controls help.
     */
    private fun renderMapEditorPanel() {
        // Size constraints: 320-400 width, 200 to 80% screen height
        val maxHeight = window.height * 0.8f
        ImGui.setNextWindowSizeConstraints(320f, 200f, 400f, maxHeight)

        // Set initial position on first frame (right side)
        if (editorPanelFirstFrame) {
            ImGui.setNextWindowPos(window.width - 340f, 10f)
            ImGui.setNextWindowSize(340f, 600f)
            editorPanelFirstFrame = false
        }

        val flags = ImGuiWindowFlags.None

        if (ImGui.begin("Map Editor (F5)", flags)) {
            clampWindowToScreen()

            // --- Navigation Section ---
            renderNavigationSection()

            ImGui.separator()

            // --- View Toggles Section ---
            renderViewTogglesSection()

            ImGui.separator()

            // --- Tool Palette ---
            renderToolPalette()

            ImGui.spacing()

            // --- Tool-specific content ---
            renderCurrentToolContent()

            ImGui.separator()

            // --- Rendering Settings (collapsible) ---
            renderRenderingSettingsSection()

            ImGui.separator()

            // Controls help
            ImGui.textWrapped("WASD move, Space/Shift up/down, Right-click look, Arrows navigate, Ctrl fast, R roofs")
        }
        ImGui.end()
    }

    /**
     * Navigation section: region coordinates, plane radio buttons, go-to input, quick locations.
     */
    private fun renderNavigationSection() {
        // Current position
        ImGui.text("Region: (%d, %d)".format(currentRegionX, currentRegionY))
        ImGui.sameLine()
        ImGui.text("  Plane:")
        for (plane in 0..3) {
            ImGui.sameLine()
            if (ImGui.radioButton("$plane##plane", currentPlane == plane)) {
                setPlane(plane)
            }
        }

        // Go-to region input
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("X##region", regionXInput)
        ImGui.sameLine()
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("Y##region", regionYInput)
        ImGui.sameLine()
        if (ImGui.button("Go", 40f, 0f)) {
            jumpToRegion(regionXInput.get().coerceIn(0, 255), regionYInput.get().coerceIn(0, 255))
        }

        // Quick locations (collapsible)
        if (ImGui.collapsingHeader("Quick Locations")) {
            var col = 0
            for ((name, coords) in knownRegions) {
                if (col > 0) ImGui.sameLine()
                if (ImGui.button(name, 130f, 0f)) {
                    regionXInput.set(coords.first)
                    regionYInput.set(coords.second)
                    jumpToRegion(coords.first, coords.second)
                }
                col = (col + 1) % 2
            }
        }
    }

    /**
     * View toggles section: terrain, roofs, ground decorations in a row.
     */
    private fun renderViewTogglesSection() {
        // Terrain toggle
        if (ImGui.checkbox("Terrain (T)", showTerrain)) {
            showTerrain = !showTerrain
        }

        ImGui.sameLine()

        // Roof visibility toggle
        val roofsEnabled = ImBoolean(showRoofs)
        if (ImGui.checkbox("Roofs (R)", roofsEnabled)) {
            showRoofs = roofsEnabled.get()
            reloadRegion()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Toggle visibility of roof shapes (12-21)\nHide roofs to see inside buildings")
        }

        // Ground decoration toggle
        val groundDecEnabled = ImBoolean(ObjectRenderer.groundDecorationEnabled)
        if (ImGui.checkbox("Ground Decorations", groundDecEnabled)) {
            ObjectRenderer.groundDecorationEnabled = groundDecEnabled.get()
            reloadRegion()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Toggle ground decoration rendering\nWhen off, only interactive/blocking ground decorations render")
        }
    }

    /**
     * Tool palette: buttons for each editor tool.
     */
    private fun renderToolPalette() {
        ImGui.text("Tool")

        for (tool in EditorTool.entries) {
            if (tool.ordinal > 0) ImGui.sameLine()

            val isSelected = currentEditorTool == tool
            if (isSelected) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1f)
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.8f)
            }

            val label = if (tool.wip) "${tool.label}*" else tool.label
            if (ImGui.button(label)) {
                currentEditorTool = tool
            }
            if (tool.wip && ImGui.isItemHovered()) {
                ImGui.setTooltip("${tool.label} (WIP - not yet functional)")
            }

            if (isSelected) {
                ImGui.popStyleColor(2)
            }
        }
    }

    /**
     * Dispatch to the current tool's content renderer.
     */
    private fun renderCurrentToolContent() {
        when (currentEditorTool) {
            EditorTool.SELECT -> renderEditorSelectTool()
            EditorTool.OBJECT_PLACE -> renderEditorObjectPlaceTool()
            EditorTool.OBJECT_MOVE -> renderEditorObjectMoveTool()
            EditorTool.TERRAIN_PAINT -> renderEditorTerrainPaintTool()
            EditorTool.HEIGHT_EDIT -> renderEditorHeightEditTool()
            EditorTool.FLAG_EDIT -> renderEditorFlagEditTool()
        }
    }

    /**
     * Rendering settings section: blending, overlays, noise, lighting parameters,
     * terrain alignment, and tilt debug.
     */
    private fun renderRenderingSettingsSection() {
        if (!ImGui.collapsingHeader("Rendering Settings")) return

        // Terrain blending toggle
        val blendingEnabled = ImBoolean(TerrainLoader.enableBlending)
        if (ImGui.checkbox("Terrain Blending", blendingEnabled)) {
            TerrainLoader.enableBlending = blendingEnabled.get()
            reloadTerrain()
        }

        // Overlay toggle (for debugging underlay-only terrain)
        val overlaysEnabled = ImBoolean(TerrainLoader.enableOverlays)
        if (ImGui.checkbox("Show Overlays", overlaysEnabled)) {
            TerrainLoader.enableOverlays = overlaysEnabled.get()
            reloadTerrain()
        }

        // Color noise toggle - adds subtle per-vertex brightness variation like RS client
        val colorNoiseEnabled = ImBoolean(TerrainLoader.enableColorNoise)
        if (ImGui.checkbox("Color Noise", colorNoiseEnabled)) {
            TerrainLoader.enableColorNoise = colorNoiseEnabled.get()
            reloadTerrain()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Adds subtle per-vertex brightness variation\nlike the RS client (+-1.5%% noise)")
        }

        // Terrain lighting toggle
        val lightingEnabled = ImBoolean(TerrainLoader.enableLighting)
        if (ImGui.checkbox("Terrain Lighting", lightingEnabled)) {
            TerrainLoader.enableLighting = lightingEnabled.get()
            reloadTerrain()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Applies RS-style per-vertex lighting based on\nterrain normals and sun direction")
        }

        // Lighting parameters section (collapsible)
        if (TerrainLoader.enableLighting && ImGui.collapsingHeader("Lighting Parameters")) {
            ImGui.indent()

            // Sun direction sliders
            ImGui.text("Light Direction")
            val lightDirX = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_X)
            val lightDirY = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_Y)
            val lightDirZ = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_Z)

            var lightingChanged = false
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir X", lightDirX, -1f, 1f)) {
                TerrainLoader.LIGHT_DIRECTION_X = lightDirX[0]
                lightingChanged = true
            }
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir Y", lightDirY, -1f, 1f)) {
                TerrainLoader.LIGHT_DIRECTION_Y = lightDirY[0]
                lightingChanged = true
            }
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir Z", lightDirZ, -1f, 1f)) {
                TerrainLoader.LIGHT_DIRECTION_Z = lightDirZ[0]
                lightingChanged = true
            }

            ImGui.spacing()

            // RS lighting parameters
            ImGui.text("RS Lighting Formula:")
            ImGui.textWrapped("factor = sunBrightness + dot * (dot>0 ? bright : dark)")

            val sunBrightness = floatArrayOf(TerrainLoader.SUN_BRIGHTNESS)
            val brightFactor = floatArrayOf(TerrainLoader.BRIGHT_FACTOR)
            val darkFactor = floatArrayOf(TerrainLoader.DARK_FACTOR)

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Sun Brightness", sunBrightness, 0.5f, 1.5f)) {
                TerrainLoader.SUN_BRIGHTNESS = sunBrightness[0]
                lightingChanged = true
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Base ambient brightness (default: 1.2)")
            }

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Bright Factor", brightFactor, 0.2f, 1.5f)) {
                TerrainLoader.BRIGHT_FACTOR = brightFactor[0]
                lightingChanged = true
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Factor for lit surfaces (default: 0.865)")
            }

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dark Factor", darkFactor, 0.5f, 2.0f)) {
                TerrainLoader.DARK_FACTOR = darkFactor[0]
                lightingChanged = true
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Factor for shadowed surfaces (default: 0.814)")
            }

            // Reset to defaults button
            if (ImGui.button("Reset to Defaults")) {
                TerrainLoader.LIGHT_DIRECTION_X = 0.163f
                TerrainLoader.LIGHT_DIRECTION_Y = 0.070f
                TerrainLoader.LIGHT_DIRECTION_Z = 1.00f
                TerrainLoader.SUN_BRIGHTNESS = 1.2f
                TerrainLoader.BRIGHT_FACTOR = 0.865f
                TerrainLoader.DARK_FACTOR = 0.814f
                lightingChanged = true
            }

            // Reload terrain if parameters changed
            if (lightingChanged) {
                reloadTerrain()
            }

            ImGui.unindent()
        }

        ImGui.spacing()

        // Terrain alignment toggle (for objects with groundContourType=3)
        val terrainAlignEnabled = ImBoolean(TerrainAligner.enabled)
        if (ImGui.checkbox("Terrain Alignment", terrainAlignEnabled)) {
            TerrainAligner.enabled = terrainAlignEnabled.get()
            reloadRegion()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Tilt objects to match terrain slopes\n(RS groundContourType=3 / hillChange)")
        }

        // Tilt negation toggles (for coordinate system debugging)
        if (TerrainAligner.enabled && ImGui.collapsingHeader("Tilt Debug")) {
            ImGui.indent()

            // Show debug stats
            ImGui.text("Stats: ${TerrainAligner.getDebugStats()}")

            val negateXTilt = ImBoolean(TerrainAligner.negateXTilt)
            if (ImGui.checkbox("Negate X Tilt", negateXTilt)) {
                TerrainAligner.negateXTilt = negateXTilt.get()
                reloadRegion()
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Negate X-axis tilt (pitch) for coordinate system fix")
            }

            val negateZTilt = ImBoolean(TerrainAligner.negateZTilt)
            if (ImGui.checkbox("Negate Z Tilt", negateZTilt)) {
                TerrainAligner.negateZTilt = negateZTilt.get()
                reloadRegion()
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Negate Z-axis tilt (roll) for coordinate system fix")
            }

            // Button to enable debug logging
            if (ImGui.button("Enable Debug Logging")) {
                TerrainAligner.enableDebug(true)
                reloadRegion()
            }

            ImGui.unindent()
        }
    }

    // ========================================================================
    // Editor Tool Content
    // ========================================================================

    /**
     * SELECT tool content: Object Inspector, Tile Inspector, and Tile Details (Debug).
     */
    private fun renderEditorSelectTool() {
        val details = tilePicker.selectedTileDetails
        val tile = tilePicker.selectedTile

        if (tile == null) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Left-click on terrain to select a tile")
            return
        }

        // --- Object Inspector ---
        if (ImGui.collapsingHeader("Object Inspector")) {
            if (details == null || details.objects.isEmpty()) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "No objects on selected tile")
            } else {
                for ((index, tileObj) in details.objects.withIndex()) {
                    val obj = tileObj.mapObject
                    val def = tileObj.definition

                    val objName = def.name.ifEmpty { "<unnamed>" }
                    if (ImGui.treeNode("editor_obj_$index", "#%d: %s".format(obj.id, objName))) {

                        // Shape name lookup
                        val shapeName = ObjectShape.entries.find { it.id == obj.shape }?.name ?: "Unknown"
                        ImGui.text("Shape:    %s (%d)".format(shapeName, obj.shape))
                        ImGui.text("Rotation: %d".format(obj.rotation))

                        // Model IDs
                        ImGui.text("Models:   %s".format(
                            if (tileObj.modelIds.isEmpty()) "(none)"
                            else tileObj.modelIds.joinToString(", ")
                        ))

                        // Interactive flag
                        val interactiveLabel = when (def.interactive) {
                            -1 -> "Default (-1)"
                            0 -> "No (0)"
                            1 -> "Yes (1)"
                            else -> "${def.interactive}"
                        }
                        ImGui.text("Interactive: %s".format(interactiveLabel))

                        ImGui.spacing()
                        ImGui.text("Definition Properties")
                        ImGui.indent()
                        ImGui.text("Size:     %dx%d".format(def.sizeX, def.sizeY))
                        ImGui.text("Solid:    %d".format(def.solid))

                        val blocksProjectile = (def.block and ObjectDefinition.PROJECTILE) != 0
                        ImGui.text("Blocks Projectile: %s".format(if (blocksProjectile) "Yes" else "No"))
                        ImGui.unindent()

                        ImGui.spacing()

                        // Placeholder action buttons (disabled)
                        ImGui.beginDisabled()
                        ImGui.button("Edit##edit_$index")
                        ImGui.sameLine()
                        ImGui.button("Delete##delete_$index")
                        ImGui.endDisabled()
                        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                            ImGui.setTooltip("Not yet implemented")
                        }

                        ImGui.treePop()
                    }
                }
            }
        }

        // --- Tile Inspector ---
        if (ImGui.collapsingHeader("Tile Inspector")) {
            if (details == null || details.tileData == null) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "No tile data available")
            } else {
                val tileData = details.tileData

                ImGui.text("Position: (%d, %d) Plane %d".format(tile.localX, tile.localY, tile.plane))
                ImGui.text("Height:   %d".format(tileData.height))

                ImGui.spacing()
                ImGui.text("Overlay")
                ImGui.indent()
                ImGui.text("ID:       %d".format(tileData.overlayId))
                ImGui.text("Shape:    %d".format(tileData.overlayShape))
                ImGui.text("Rotation: %d".format(tileData.overlayRotation))
                ImGui.unindent()

                ImGui.spacing()
                ImGui.text("Underlay")
                ImGui.indent()
                ImGui.text("ID:       %d".format(tileData.underlayId))
                ImGui.unindent()

                ImGui.spacing()
                ImGui.text("Flags (0x%02X)".format(tileData.flags))
                ImGui.indent()

                // Read-only flag checkboxes
                ImGui.beginDisabled()
                val blocksWalk = ImBoolean(tileData.flags and 0x1 != 0)
                ImGui.checkbox("Blocks Walk (0x1)", blocksWalk)
                val bridge = ImBoolean(tileData.flags and 0x2 != 0)
                ImGui.checkbox("Bridge (0x2)", bridge)
                val invisible = ImBoolean(tileData.flags and 0x4 != 0)
                ImGui.checkbox("Invisible (0x4)", invisible)
                val forcePlane0 = ImBoolean(tileData.flags and 0x8 != 0)
                ImGui.checkbox("Force Plane 0 (0x8)", forcePlane0)
                ImGui.endDisabled()

                ImGui.unindent()
            }
        }

        // --- Tile Details (Debug) ---
        // Detailed overlay/underlay definitions, UV coords, corner heights, and per-object info.
        if (ImGui.collapsingHeader("Tile Details (Debug)")) {
            if (details == null || details.tileData == null) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "No tile data available")
            } else {
                val tileData = details.tileData

                // Tile coordinates
                ImGui.text("Tile Coordinates")
                ImGui.separator()
                ImGui.text("  Region: (%d, %d)".format(tile.regionX, tile.regionY))
                ImGui.text("  Local:  (%d, %d)".format(tile.localX, tile.localY))
                ImGui.text("  Plane:  %d".format(tile.plane))
                ImGui.text("  World:  (%.2f, %.2f, %.2f)".format(tile.worldX, tile.worldY, tile.worldZ))

                ImGui.spacing()
                ImGui.text("Overlay Definition")
                ImGui.separator()
                ImGui.text("  ID:       %d".format(tileData.overlayId))
                ImGui.text("  Shape:    %d".format(tileData.overlayShape))
                ImGui.text("  Rotation: %d".format(tileData.overlayRotation))

                val overlayDef = details.overlayDef
                if (overlayDef != null) {
                    ImGui.text("  Texture:  %d".format(overlayDef.texture))
                    ImGui.text("  Scale:    %d".format(overlayDef.scale))
                    ImGui.text("  Colour:   0x%04X (%d)".format(overlayDef.colour, overlayDef.colour))
                    ImGui.text("  BlendCol: 0x%04X (%d)".format(overlayDef.blendColour, overlayDef.blendColour))
                    ImGui.text("  Underlay Override: %s".format(overlayDef.underlayOverrides))

                    // UV coordinates for overlay
                    val uvs = details.cornerUVsOverlay
                    if (uvs != null) {
                        ImGui.text("  UV Scale: %.4f (1/%d)".format(1f / overlayDef.scale.toFloat(), overlayDef.scale))
                        ImGui.text("  UV SW: (%.3f, %.3f)".format(uvs[0][0], uvs[0][1]))
                        ImGui.text("  UV SE: (%.3f, %.3f)".format(uvs[1][0], uvs[1][1]))
                        ImGui.text("  UV NW: (%.3f, %.3f)".format(uvs[2][0], uvs[2][1]))
                        ImGui.text("  UV NE: (%.3f, %.3f)".format(uvs[3][0], uvs[3][1]))
                    }
                } else if (tileData.overlayId > 0) {
                    ImGui.textColored(1f, 0.5f, 0.5f, 1f, "  (Definition not found)")
                }

                ImGui.spacing()
                ImGui.text("Underlay Definition")
                ImGui.separator()
                ImGui.text("  ID:       %d".format(tileData.underlayId))

                val underlayDef = details.underlayDef
                if (underlayDef != null) {
                    ImGui.text("  Texture:  %d".format(underlayDef.texture))
                    ImGui.text("  Scale:    %d".format(underlayDef.scale))
                    ImGui.text("  Colour:   0x%06X".format(underlayDef.colour))
                    ImGui.text("  Hue:      %d".format(underlayDef.hue))
                    ImGui.text("  Sat:      %d".format(underlayDef.saturation))
                    ImGui.text("  Light:    %d".format(underlayDef.lightness))
                    ImGui.text("  Chroma:   %d".format(underlayDef.chroma))

                    // UV coordinates for underlay
                    val uvs = details.cornerUVsUnderlay
                    if (uvs != null) {
                        ImGui.text("  UV Scale: %.4f (1/%d)".format(1f / underlayDef.scale.toFloat(), underlayDef.scale))
                        ImGui.text("  UV SW: (%.3f, %.3f)".format(uvs[0][0], uvs[0][1]))
                        ImGui.text("  UV SE: (%.3f, %.3f)".format(uvs[1][0], uvs[1][1]))
                        ImGui.text("  UV NW: (%.3f, %.3f)".format(uvs[2][0], uvs[2][1]))
                        ImGui.text("  UV NE: (%.3f, %.3f)".format(uvs[3][0], uvs[3][1]))
                    }
                } else if (tileData.underlayId > 0) {
                    ImGui.textColored(1f, 0.5f, 0.5f, 1f, "  (Definition not found)")
                }

                // Corner heights
                ImGui.spacing()
                ImGui.text("Corner Heights (scaled)")
                ImGui.separator()
                ImGui.text("  SW: %.2f  SE: %.2f".format(details.cornerHeights[0], details.cornerHeights[1]))
                ImGui.text("  NW: %.2f  NE: %.2f".format(details.cornerHeights[2], details.cornerHeights[3]))

                // Objects on tile (detailed view with model/texture/retexture info)
                ImGui.spacing()
                ImGui.text("Objects on Tile (%d)".format(details.objects.size))
                ImGui.separator()
                if (details.objects.isEmpty()) {
                    ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  (No objects)")
                } else {
                    for ((index, tileObj) in details.objects.withIndex()) {
                        val obj = tileObj.mapObject
                        val def = tileObj.definition
                        if (ImGui.treeNode("debug_obj_$index", "Object %d: %s".format(obj.id, def.name.ifEmpty { "<unnamed>" }))) {
                            ImGui.text("  Shape: %d, Rotation: %d".format(obj.shape, obj.rotation))
                            ImGui.text("  World Pos: (%d, %d)".format(obj.x, obj.y))
                            ImGui.text("  Size: %dx%d".format(def.sizeX, def.sizeY))

                            // Model IDs
                            ImGui.text("  Model IDs: %s".format(
                                if (tileObj.modelIds.isEmpty()) "(none)"
                                else tileObj.modelIds.joinToString(", ")
                            ))

                            // Texture IDs
                            ImGui.text("  Texture IDs: %s".format(
                                if (tileObj.textureIds.isEmpty()) "(none)"
                                else tileObj.textureIds.sorted().joinToString(", ")
                            ))

                            // Object retexturing/recoloring if present
                            if (def.retextureSrc != null && def.retextureDst != null) {
                                ImGui.textColored(0.5f, 1f, 0.5f, 1f, "  Has Retexturing")
                                for (i in def.retextureSrc!!.indices) {
                                    if (i < def.retextureDst!!.size) {
                                        val src = def.retextureSrc!![i].toInt() and 0xffff
                                        val dst = def.retextureDst!![i].toInt() and 0xffff
                                        ImGui.text("    Tex %d -> %d".format(src, dst))
                                    }
                                }
                            }
                            if (def.recolorSrc != null && def.recolorDst != null) {
                                ImGui.textColored(0.5f, 0.5f, 1f, 1f, "  Has Recoloring")
                            }

                            ImGui.treePop()
                        }
                    }
                }

                ImGui.spacing()
                ImGui.separator()
                if (ImGui.button("Clear Selection")) {
                    tilePicker.clearSelection()
                    tileHighlightMesh?.cleanup()
                    tileHighlightMesh = null
                }
            }
        }
    }

    /**
     * OBJECT_PLACE tool content: Object browser for searching and placing objects.
     */
    private fun renderEditorObjectPlaceTool() {
        if (ImGui.collapsingHeader("Object Browser", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Search objects to place")
            ImGui.spacing()

            ImGui.setNextItemWidth(-1f)
            ImGui.inputText("##obj_search", editorObjectSearchQuery)

            ImGui.spacing()

            // Scrollable placeholder list area
            val listHeight = ImGui.getContentRegionAvailY().coerceAtLeast(100f).coerceAtMost(300f)
            if (ImGui.beginChild("obj_search_results", 0f, listHeight, true)) {
                val query = editorObjectSearchQuery.get().trim()
                if (query.isEmpty()) {
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Type a name or ID to search...")
                } else {
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Object search not yet implemented")
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Query: \"%s\"".format(query))
                }
            }
            ImGui.endChild()

            ImGui.spacing()
            ImGui.beginDisabled()
            ImGui.button("Place Selected Object")
            ImGui.endDisabled()
        }
    }

    /**
     * OBJECT_MOVE tool content: placeholder for moving objects.
     */
    private fun renderEditorObjectMoveTool() {
        ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click an object to select it, then drag to move.")
        ImGui.spacing()
        ImGui.textColored(1f, 0.8f, 0.3f, 1f, "This tool is not yet functional.")
    }

    /**
     * TERRAIN_PAINT tool content: overlay/underlay painting controls.
     */
    private fun renderEditorTerrainPaintTool() {
        if (ImGui.collapsingHeader("Terrain Painter", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click to paint terrain")
            ImGui.spacing()

            // Paint mode: Overlay / Underlay
            ImGui.text("Paint Mode")
            if (ImGui.radioButton("Overlay", editorPaintMode == 0)) {
                editorPaintMode = 0
            }
            ImGui.sameLine()
            if (ImGui.radioButton("Underlay", editorPaintMode == 1)) {
                editorPaintMode = 1
            }

            ImGui.spacing()

            // ID input
            val idLabel = if (editorPaintMode == 0) "Overlay ID" else "Underlay ID"
            ImGui.setNextItemWidth(120f)
            ImGui.inputInt(idLabel, editorOverlayUnderlayId)

            ImGui.spacing()

            // Brush size slider
            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Brush Size", editorBrushSize, 1, 5)

            ImGui.spacing()
            ImGui.beginDisabled()
            ImGui.button("Apply Paint")
            ImGui.endDisabled()
        }
    }

    /**
     * HEIGHT_EDIT tool content: height brush controls.
     */
    private fun renderEditorHeightEditTool() {
        if (ImGui.collapsingHeader("Height Editor", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click and drag to edit height")
            ImGui.spacing()

            // Brush radius slider
            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Brush Radius", editorHeightBrushRadius, 1, 3)

            // Strength slider
            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Strength", editorHeightStrength, 1, 10)

            ImGui.spacing()

            // Mode radio buttons
            ImGui.text("Mode")
            if (ImGui.radioButton("Raise", editorHeightMode == 0)) {
                editorHeightMode = 0
            }
            ImGui.sameLine()
            if (ImGui.radioButton("Lower", editorHeightMode == 1)) {
                editorHeightMode = 1
            }
            ImGui.sameLine()
            if (ImGui.radioButton("Smooth", editorHeightMode == 2)) {
                editorHeightMode = 2
            }

            ImGui.spacing()
            ImGui.beginDisabled()
            ImGui.button("Apply Height Change")
            ImGui.endDisabled()
        }
    }

    /**
     * FLAG_EDIT tool content: tile flag toggle checkboxes.
     */
    private fun renderEditorFlagEditTool() {
        if (ImGui.collapsingHeader("Flag Editor", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click tile to toggle flags")
            ImGui.spacing()

            val details = tilePicker.selectedTileDetails
            val tileData = details?.tileData

            if (tileData == null) {
                ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Select a tile first (left-click)")
            } else {
                ImGui.text("Tile (%d, %d) - Current flags: 0x%02X".format(
                    details.tile.localX, details.tile.localY, tileData.flags
                ))
                ImGui.spacing()

                // Editable flag checkboxes (placeholder - changes are not persisted)
                ImGui.beginDisabled()
                val blocksWalk = ImBoolean(tileData.flags and 0x1 != 0)
                ImGui.checkbox("Blocks Walk (0x1)##flag_edit", blocksWalk)
                val bridgeFlag = ImBoolean(tileData.flags and 0x2 != 0)
                ImGui.checkbox("Bridge (0x2)##flag_edit", bridgeFlag)
                val invisibleFlag = ImBoolean(tileData.flags and 0x4 != 0)
                ImGui.checkbox("Invisible (0x4)##flag_edit", invisibleFlag)
                val forcePlane0Flag = ImBoolean(tileData.flags and 0x8 != 0)
                ImGui.checkbox("Force Plane 0 (0x8)##flag_edit", forcePlane0Flag)
                ImGui.endDisabled()

                ImGui.spacing()
                ImGui.textColored(1f, 0.8f, 0.3f, 1f, "Flag editing not yet functional")
            }
        }
    }

    // ========================================================================
    // Region Loading and Navigation
    // ========================================================================

    private fun setPlane(plane: Int) {
        currentPlane = plane.coerceIn(0, 3)
        loadRegion(currentRegionX, currentRegionY, currentPlane)
    }

    private fun navigateRegion(dx: Int, dy: Int) {
        val newX = (currentRegionX + dx).coerceIn(0, 255)
        val newY = (currentRegionY + dy).coerceIn(0, 255)
        jumpToRegion(newX, newY)
    }

    private fun jumpToRegion(regionX: Int, regionY: Int) {
        currentRegionX = regionX.coerceIn(0, 255)
        currentRegionY = regionY.coerceIn(0, 255)
        regionXInput.set(currentRegionX)
        regionYInput.set(currentRegionY)

        loadRegion(currentRegionX, currentRegionY, currentPlane)
    }

    /** Reload the current region (same coordinates and plane). */
    private fun reloadRegion() = loadRegion(currentRegionX, currentRegionY, currentPlane)

    /** Clear terrain cache and reload the current region. */
    private fun reloadTerrain() {
        TerrainLoader.clearCache()
        reloadRegion()
    }

    /**
     * Build merged atlas terrain meshes from the center region (editManager) and
     * all 8 context regions. Produces exactly 2 draw calls: one opaque and one alpha.
     *
     * For the center region, terrain mesh data is obtained from the editManager's
     * current tile state. For context regions, it is obtained from their already-
     * computed TerrainMeshData (via computeTerrainMeshData which was called during
     * loadContextRegions).
     *
     * Each region's local-coordinate vertices are offset by the region's world
     * position (regionX * 64 * tileSize, 0, -regionY * 64 * tileSize) before
     * merging, so the merged mesh is in world coordinates and can be drawn with
     * an identity model matrix.
     */
    private fun buildMergedAtlasTerrain(centerRx: Int, centerRy: Int, plane: Int, tileSize: Float) {
        val atlas = terrainAtlas ?: return

        // Collect all TerrainMeshData from all 9 regions.
        // Each entry is (meshData, regionX, regionY) so we can offset positions.
        data class RegionMeshEntry(
            val data: TerrainLoader.TerrainMeshData,
            val rx: Int,
            val ry: Int
        )

        val entries = mutableListOf<RegionMeshEntry>()

        // Center region: compute mesh data from editManager's current state
        val em = editManager
        if (em != null) {
            val centerTd = TerrainLoader.loadRegion(cache, centerRx, centerRy)
            if (centerTd != null) {
                val neighbors = TerrainLoader.buildNeighborData(cache, centerRx, centerRy)
                val centerMeshData = TerrainLoader.computeTerrainMeshData(centerTd, plane, neighbors)
                entries.add(RegionMeshEntry(centerMeshData, centerRx, centerRy))
            }
        }

        // Context regions: compute mesh data for each
        for (i in CONTEXT_OFFSETS.indices) {
            val ctx = contextRegions[i] ?: continue
            val rx = ctx.regionX
            val ry = ctx.regionY
            val td = TerrainLoader.loadRegion(cache, rx, ry) ?: continue
            val neighbors = TerrainLoader.buildNeighborData(cache, rx, ry)
            val meshData = TerrainLoader.computeTerrainMeshData(td, plane, neighbors)
            entries.add(RegionMeshEntry(meshData, rx, ry))
        }

        if (entries.isEmpty()) return

        // Merge all opaque data (color + textured, since atlas unifies them)
        // All color meshes have atlas transforms (sentinel white pixel), and all
        // textured meshes have atlas transforms, so they can all go into one mesh.
        val allOpaquePositions = mutableListOf<FloatArray>()
        val allOpaqueColors = mutableListOf<FloatArray>()
        val allOpaqueTexCoords = mutableListOf<FloatArray>()
        val allOpaqueNormals = mutableListOf<FloatArray>()
        val allOpaqueAtlasTransforms = mutableListOf<FloatArray>()
        val allOpaqueIndices = mutableListOf<IntArray>()
        var opaqueVertexOffset = 0

        val allAlphaPositions = mutableListOf<FloatArray>()
        val allAlphaColors = mutableListOf<FloatArray>()
        val allAlphaTexCoords = mutableListOf<FloatArray>()
        val allAlphaNormals = mutableListOf<FloatArray>()
        val allAlphaAlphas = mutableListOf<FloatArray>()
        val allAlphaAtlasTransforms = mutableListOf<FloatArray>()
        val allAlphaIndices = mutableListOf<IntArray>()
        var alphaVertexOffset = 0

        for (entry in entries) {
            val offsetX = entry.rx * 64 * tileSize
            val offsetZ = -entry.ry * 64 * tileSize

            // Helper: offset positions from local coords to world coords
            fun offsetPositions(positions: FloatArray): FloatArray {
                val result = positions.copyOf()
                var i = 0
                while (i < result.size) {
                    result[i] += offsetX       // x
                    // result[i+1] unchanged   // y (height is already correct)
                    result[i + 2] += offsetZ   // z
                    i += 3
                }
                return result
            }

            // Helper: offset indices by running vertex count
            fun offsetIndices(indices: IntArray, offset: Int): IntArray {
                if (offset == 0) return indices
                val result = IntArray(indices.size)
                for (j in indices.indices) {
                    result[j] = indices[j] + offset
                }
                return result
            }

            val data = entry.data

            // Add color mesh (untextured) to opaque pool
            data.colorArrays?.let { c ->
                if (c.positions.isNotEmpty() && c.atlasTransforms != null && c.texCoords != null) {
                    val vertCount = c.positions.size / 3
                    allOpaquePositions.add(offsetPositions(c.positions))
                    allOpaqueColors.add(c.colors)
                    allOpaqueTexCoords.add(c.texCoords)
                    allOpaqueNormals.add(c.normals)
                    allOpaqueAtlasTransforms.add(c.atlasTransforms)
                    allOpaqueIndices.add(offsetIndices(c.indices, opaqueVertexOffset))
                    opaqueVertexOffset += vertCount
                }
            }

            // Add textured meshes to opaque pool
            for (t in data.texturedArrays) {
                if (t.positions.isNotEmpty() && t.atlasTransforms != null) {
                    val vertCount = t.positions.size / 3
                    allOpaquePositions.add(offsetPositions(t.positions))
                    allOpaqueColors.add(t.colors)
                    allOpaqueTexCoords.add(t.texCoords)
                    allOpaqueNormals.add(t.normals)
                    allOpaqueAtlasTransforms.add(t.atlasTransforms)
                    allOpaqueIndices.add(offsetIndices(t.indices, opaqueVertexOffset))
                    opaqueVertexOffset += vertCount
                }
            }

            // Add alpha meshes to alpha pool
            for (a in data.alphaArrays) {
                if (a.positions.isNotEmpty() && a.atlasTransforms != null) {
                    val vertCount = a.positions.size / 3
                    allAlphaPositions.add(offsetPositions(a.positions))
                    allAlphaColors.add(a.colors)
                    allAlphaTexCoords.add(a.texCoords)
                    allAlphaNormals.add(a.normals)
                    allAlphaAlphas.add(a.alphas)
                    allAlphaAtlasTransforms.add(a.atlasTransforms)
                    allAlphaIndices.add(offsetIndices(a.indices, alphaVertexOffset))
                    alphaVertexOffset += vertCount
                }
            }
        }

        // Build the merged opaque mesh
        if (opaqueVertexOffset > 0) {
            val positions = concatFloatArrays(allOpaquePositions)
            val colors = concatFloatArrays(allOpaqueColors)
            val texCoords = concatFloatArrays(allOpaqueTexCoords)
            val normals = concatFloatArrays(allOpaqueNormals)
            val atlasTransforms = concatFloatArrays(allOpaqueAtlasTransforms)
            val indices = concatIntArrays(allOpaqueIndices)

            mergedOpaqueTerrain = Mesh.createWithAtlasTransform(
                positions, colors, texCoords, normals, atlasTransforms, indices
            )
            println("[MapViewer] Merged opaque terrain: $opaqueVertexOffset vertices, ${indices.size} indices")
        }

        // Build the merged alpha mesh
        if (alphaVertexOffset > 0) {
            val positions = concatFloatArrays(allAlphaPositions)
            val colors = concatFloatArrays(allAlphaColors)
            val texCoords = concatFloatArrays(allAlphaTexCoords)
            val normals = concatFloatArrays(allAlphaNormals)
            val alphas = concatFloatArrays(allAlphaAlphas)
            val atlasTransforms = concatFloatArrays(allAlphaAtlasTransforms)
            val indices = concatIntArrays(allAlphaIndices)

            mergedAlphaTerrain = Mesh.createWithAtlasAndAlpha(
                positions, colors, texCoords, normals, alphas, atlasTransforms, indices
            )
            println("[MapViewer] Merged alpha terrain: $alphaVertexOffset vertices, ${indices.size} indices")
        }
    }

    /** Concatenate a list of FloatArrays into one. */
    private fun concatFloatArrays(arrays: List<FloatArray>): FloatArray {
        val totalSize = arrays.sumOf { it.size }
        val result = FloatArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            arr.copyInto(result, offset)
            offset += arr.size
        }
        return result
    }

    /** Concatenate a list of IntArrays into one. */
    private fun concatIntArrays(arrays: List<IntArray>): IntArray {
        val totalSize = arrays.sumOf { it.size }
        val result = IntArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            arr.copyInto(result, offset)
            offset += arr.size
        }
        return result
    }

    private fun loadRegion(regionX: Int, regionY: Int, plane: Int) {
        // Clean up existing resources
        cleanupRegion()

        // Clear tile picker selection when loading new region
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null

        // Time overall region loading (terrain + objects + context)
        val regionLoadStart = RenderMetrics.startTiming()

        // Initialize chunked terrain via edit manager (replaces monolithic loadRegionMeshes)
        editManager = TerrainEditManager(regionX, regionY, plane, cache).also { it.init() }

        // Time object loading phase (instance creation + model registration)
        val objectLoadStart = RenderMetrics.startTiming()

        // Load objects
        val regionData = RegionLoader.loadRegion(cache, regionX, regionY)
        // Also load terrain data from TerrainLoader for height values
        // TerrainLoader computes authentic RS heights (cumulative, multi-octave noise)
        val td = TerrainLoader.loadRegion(cache, regionX, regionY)

        // Store terrain data for tile picker
        terrainData = td
        // Store raw MapObjects for tile picker object lookup
        mapObjects = regionData?.objects ?: emptyList()

        if (regionData != null && td != null) {
            // Create instance manager for this region (larger capacity for multi-region objects).
            // Pass the object atlas and pixel provider so textured models have their
            // textures packed into the atlas at registration time, with UVs remapped to
            // atlas space. This collapses texture-based draw call splits.
            val atlas = objectAtlas
            instanceManager = InstanceManager(
                maxInstancesPerMesh = 50000,
                objectAtlas = atlas,
                texturePixelProvider = if (atlas != null) {
                    TexturePixelProvider { textureId ->
                        TextureLoader.getTexturePixelsForMaterial(cache, textureId)
                    }
                } else null
            )

            // Create IndirectRenderer if shaders are available and indirect rendering is enabled.
            // Uses the same object atlas and pixel provider as InstanceManager.
            if (useIndirectRendering && indirectColorShader != null && indirectTexturedShader != null) {
                val ir = IndirectRenderer(
                    maxTotalInstances = 200000,
                    objectAtlas = atlas,
                    texturePixelProvider = if (atlas != null) {
                        TexturePixelProvider { textureId ->
                            TextureLoader.getTexturePixelsForMaterial(cache, textureId)
                        }
                    } else null
                )
                ir.init()
                indirectRenderer = ir
            }

            // Create object instances for this plane using WORLD coordinates
            // The terrain mesh uses local coords (0-63) but is positioned via model matrix
            // Objects use world tile coordinates to align with the translated terrain
            // Pass td.tiles for height lookups (matches TerrainLoader's computed heights)
            objectInstances = createObjectInstances(regionData, td.tiles, regionX, regionY, plane)

            // Register models with instance manager
            // Use combined models for multi-model objects (matching RS client behavior)
            // Apply retexturing/recoloring from ObjectDefinition if present
            var registeredCount = 0
            for (obj in objectInstances) {
                // Use the cached combined model ID (includes modifications hash)
                val combinedId = obj.cachedCombinedModelId
                if (registeredModels.contains(combinedId)) continue

                // Load and combine all models for this object with modifications applied
                val meshDataList = if (obj.modifications.hasModifications()) {
                    ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                } else {
                    ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                }
                if (meshDataList != null && meshDataList.isNotEmpty()) {
                    instanceManager!!.registerModel(combinedId, meshDataList)
                    indirectRenderer?.registerModel(combinedId, meshDataList)
                    registeredModels.add(combinedId)
                    registeredCount++
                }
            }

            // Register displaced meshes for contourType 1 objects.
            // Each instance gets a unique mesh with per-vertex terrain displacement,
            // registered with a unique model ID. These use the instanced rendering path
            // with 1 instance each (the displacement is baked into the geometry).
            val terrainTiles = td.tiles
            for (obj in objectInstances) {
                if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) continue

                // Load base model MeshData (with modifications if any)
                val baseMeshDataList = if (obj.modifications.hasModifications()) {
                    ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                } else {
                    ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                }
                if (baseMeshDataList == null || baseMeshDataList.isEmpty()) continue

                // Apply per-vertex displacement to each mesh batch
                val displacedMeshDataList = mutableListOf<MeshData>()
                var anyDisplaced = false

                for (meshData in baseMeshDataList) {
                    val displaced = VertexDisplacer.displaceVertices(
                        meshData = meshData,
                        sceneX = obj.rsSceneX,
                        sceneY = obj.rsSceneY,
                        averageHeight = obj.rsAverageHeight,
                        plane = obj.mapObject.level,
                        tiles = terrainTiles,
                        regionBaseX = regionX * 64,
                        regionBaseY = regionY * 64,
                        rsRotation = obj.mapObject.rotation,
                        mirrored = obj.mirrored,
                        resizeX = obj.definition.resizeX,
                        resizeY = obj.definition.resizeY,
                        resizeZ = obj.definition.resizeZ,
                        rsOffsetX = obj.definition.offsetX,
                        rsOffsetZ = obj.definition.offsetZ
                    )

                    if (displaced != null) {
                        displacedMeshDataList.add(displaced)
                        anyDisplaced = true
                    } else {
                        // No displacement needed for this batch (flat terrain), use original
                        displacedMeshDataList.add(meshData)
                    }
                }

                if (anyDisplaced) {
                    // Register displaced mesh with a unique model ID
                    // Displaced models are unique per-instance, so only need capacity for 1-4 instances
                    val displacedId = nextDisplacedId++
                    instanceManager!!.registerModel(displacedId, displacedMeshDataList, maxInstances = 4)
                    indirectRenderer?.registerModel(displacedId, displacedMeshDataList)
                    registeredModels.add(displacedId)
                    displacedModels[System.identityHashCode(obj)] = displacedId
                }
            }

            // Populate object spatial index for future terrain reactivity
            populateObjectSpatialIndex()
        }

        // Compute model matrices for terrain positioning (local coords -> world coords)
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        centerModelMatrix.identity().translate(regionX * 64 * tileSize, 0f, -regionY * 64 * tileSize)

        // Load surrounding context regions (terrain + objects)
        loadContextRegions(regionX, regionY, plane)

        // Upload object atlas to GPU after all models have been registered.
        // Textures were packed incrementally during registerModel() calls above
        // and in loadContextRegions(). Now upload all pages at once.
        val atlas = objectAtlas
        if (atlas != null && atlas.getTextureCount() > 0) {
            atlas.upload()
            val stats = atlas.getStats()
            println("[MapViewer] Object atlas: ${stats.textureCount} textures on ${stats.pageCount} page(s), " +
                    "utilization ${(stats.utilization * 100).toInt()}%")
        }

        // Build shared geometry for IndirectRenderer (must happen after atlas upload
        // so that the texture array can be built from the uploaded atlas pages).
        val ir = indirectRenderer
        if (ir != null) {
            try {
                ir.buildSharedGeometry()
                println("[MapViewer] IndirectRenderer: ${ir.getRegisteredModelCount()} models, " +
                        "${ir.getMeshEntryCount()} mesh entries in shared geometry buffer")
            } catch (e: Exception) {
                System.err.println("[MapViewer] IndirectRenderer build failed, falling back to instanced: ${e.message}")
                ir.cleanup()
                indirectRenderer = null
            }
        }

        // Build merged atlas terrain meshes (2 draw calls for all 9 regions)
        if (terrainAtlas != null) {
            buildMergedAtlasTerrain(regionX, regionY, plane, tileSize)
        }

        // Record total region load time (terrain + objects + context + atlas build)
        RenderMetrics.terrainLoadTimeMs = RenderMetrics.endTiming(regionLoadStart)

        // Record object load time (instance creation, model registration, context regions, atlas upload)
        RenderMetrics.objectLoadTimeMs = RenderMetrics.endTiming(objectLoadStart)

        // Populate atlas stats for the debug panel
        updateAtlasStats()

        // Center camera on the world-space center of this region
        val worldCenterX = (regionX * 64 + 32) * tileSize
        val worldCenterZ = -(regionY * 64 + 32) * tileSize
        scene.camera.setPosition(worldCenterX, 50f, worldCenterZ + 80f)
        scene.camera.pitch = -35f
        scene.camera.yaw = 0f
        scene.camera.farPlane = 4000f
    }

    /**
     * Populate [RenderMetrics] atlas stats from current terrain and object atlases.
     */
    private fun updateAtlasStats() {
        val tAtlas = terrainAtlas
        if (tAtlas != null) {
            val stats = tAtlas.getStats()
            RenderMetrics.terrainAtlasTextures = stats.textureCount
            RenderMetrics.terrainAtlasPages = stats.pageCount
            RenderMetrics.terrainAtlasUtilization = stats.utilization * 100f
        } else {
            RenderMetrics.terrainAtlasTextures = 0
            RenderMetrics.terrainAtlasPages = 0
            RenderMetrics.terrainAtlasUtilization = 0f
        }

        val oAtlas = objectAtlas
        if (oAtlas != null && oAtlas.getTextureCount() > 0) {
            val stats = oAtlas.getStats()
            RenderMetrics.objectAtlasTextures = stats.textureCount
            RenderMetrics.objectAtlasPages = stats.pageCount
            RenderMetrics.objectAtlasUtilization = stats.utilization * 100f
        } else {
            RenderMetrics.objectAtlasTextures = 0
            RenderMetrics.objectAtlasPages = 0
            RenderMetrics.objectAtlasUtilization = 0f
        }
    }

    private fun cleanupRegion() {
        // Clean up merged atlas terrain meshes
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null

        // Clean up chunked terrain GPU resources
        editManager?.cleanup()
        editManager = null
        terrainData = null
        mapObjects = emptyList()
        objectsByTile.clear()
        instanceManager?.cleanup()
        instanceManager = null
        indirectRenderer?.cleanup()
        indirectRenderer = null
        objectInstances = emptyList()
        registeredModels.clear()
        displacedModels.clear()
        nextDisplacedId = Int.MIN_VALUE / 2
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null

        // Clean up static batched context region objects
        contextObjectBatcher?.cleanup()
        contextObjectBatcher = null

        // Clean up and clear context region references.
        // Context regions built with neighbor data own their terrain meshes and must
        // clean them up explicitly. Also clear the batched mesh cache for any stale entries.
        for (i in contextRegions.indices) {
            contextRegions[i]?.cleanup()
            contextRegions[i] = null
        }
        TerrainLoader.clearBatchedMeshCache()
    }

    /**
     * CPU-computed data for a context region, ready for GPU upload on the main thread.
     * Produced by the parallel phase of [loadContextRegions] and consumed by the
     * sequential GPU upload phase.
     */
    private class ContextRegionCpuData(
        val index: Int,
        val rx: Int,
        val ry: Int,
        val terrainMeshData: TerrainLoader.TerrainMeshData,
        val objectInstances: List<ObjectInstance>,
        /** Map of combinedModelId -> MeshData list, for models not yet registered with InstanceManager. */
        val newModelMeshData: Map<Int, List<MeshData>>,
        /** Map of combinedModelId -> MeshData list for ALL models (including already registered).
         *  Used for static batch building where we need the original vertex data. */
        val allModelMeshData: Map<Int, List<MeshData>>
    )

    /**
     * Load the 8 surrounding context regions for visual context.
     * Each neighbor gets static terrain meshes and objects are statically batched
     * into merged meshes grouped by texture/atlas page.
     *
     * Context region objects are static (never edited while the player is in the
     * center region), so we bake their world-space geometry into StaticBatcher
     * meshes during loading instead of computing transforms every frame.
     *
     * When running with a MemoryCache (MEM_CACHE=true), this uses parallel loading:
     * - Phase 1 (parallel, Dispatchers.Default): For all 8 regions concurrently --
     *   decode region data, build neighbor data, compute terrain mesh arrays (CPU only),
     *   create object instances, and load model MeshData from cache.
     * - Phase 2 (main thread, sequential): Upload terrain mesh data to GPU,
     *   register models with InstanceManager (for center region), build static batches.
     *
     * When running with FileCache, falls back to sequential loading since FileCache
     * is not safe for concurrent access.
     */
    private fun loadContextRegions(centerRegionX: Int, centerRegionY: Int, plane: Int) {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val useParallel = cache is MemoryCache

        if (useParallel) {
            loadContextRegionsParallel(centerRegionX, centerRegionY, plane, tileSize)
        } else {
            loadContextRegionsSequential(centerRegionX, centerRegionY, plane, tileSize)
        }
    }

    /**
     * Parallel implementation of context region loading.
     * Phase 1 runs on Dispatchers.Default, Phase 2 runs on the calling (main) thread.
     */
    private fun loadContextRegionsParallel(centerRegionX: Int, centerRegionY: Int, plane: Int, tileSize: Float) {
        val showRoofsLocal = showRoofs  // Capture for safe use in parallel lambdas

        // Collect the set of already-registered model IDs at the start so parallel
        // workers can check against it without synchronization. Any model ID in this
        // snapshot does not need its MeshData loaded for InstanceManager registration,
        // but we still need ALL MeshData for static batch building.
        val alreadyRegistered = registeredModels.toSet()

        // Phase 1 (parallel): CPU-only computation for all 8 context regions
        val cpuResults: List<ContextRegionCpuData?> = runBlocking {
            CONTEXT_OFFSETS.indices.map { i ->
                async(Dispatchers.Default) {
                    val rx = centerRegionX + CONTEXT_OFFSETS[i][0]
                    val ry = centerRegionY + CONTEXT_OFFSETS[i][1]

                    // Skip if out of bounds
                    if (rx < 0 || rx > 255 || ry < 0 || ry > 255) return@async null

                    // Load terrain region data (thread-safe ConcurrentHashMap cache)
                    val terrainData = TerrainLoader.loadRegion(cache, rx, ry) ?: return@async null

                    // Build neighbor data (calls TerrainLoader.loadRegion, thread-safe)
                    val ctxNeighbors = TerrainLoader.buildNeighborData(cache, rx, ry)

                    // Compute terrain mesh data (pure CPU, no GL calls)
                    TerrainLoader.loadDefinitions(cache)
                    val terrainMeshData = TerrainLoader.computeTerrainMeshData(terrainData, plane, ctxNeighbors)

                    // Load object data (RegionLoader with useCache=false for thread safety)
                    val ctxRegionData = RegionLoader.loadRegion(cache, rx, ry, useCache = false)
                    val ctxTiles = terrainData.tiles
                    var ctxObjectInstances: List<ObjectInstance> = emptyList()
                    val newModelMeshData = mutableMapOf<Int, List<MeshData>>()
                    val allModelMeshData = mutableMapOf<Int, List<MeshData>>()

                    if (ctxRegionData != null) {
                        // Create instances for all planes using world coordinates
                        val allPlaneInstances = ObjectRenderer.createInstancesAllPlanes(
                            objects = ctxRegionData.objects,
                            regionX = rx,
                            regionY = ry,
                            tileHeights = { localX, localY, p ->
                                TerrainLoader.getTileHeightScaled(ctxTiles, localX, localY, p)
                            },
                            rsHeightSampler = { localX, localY, p ->
                                TerrainLoader.getTileHeight(ctxTiles, localX, localY, p)
                            }
                        )

                        // Flatten and filter
                        ctxObjectInstances = allPlaneInstances.values.flatten().let { instances ->
                            if (!showRoofsLocal) instances.filter { it.mapObject.shape !in 12..21 }
                            else instances
                        }

                        // Pre-load model MeshData for ALL models used by context objects.
                        // ModelLoader caches are ConcurrentHashMap -- thread-safe.
                        // allModelMeshData is needed for static batch building (vertex data).
                        // newModelMeshData tracks only models not yet registered with InstanceManager.
                        for (obj in ctxObjectInstances) {
                            val combinedId = obj.cachedCombinedModelId
                            if (allModelMeshData.containsKey(combinedId)) continue

                            val meshDataList = if (obj.modifications.hasModifications()) {
                                ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                            } else {
                                ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                            }
                            if (meshDataList != null && meshDataList.isNotEmpty()) {
                                allModelMeshData[combinedId] = meshDataList
                                if (!alreadyRegistered.contains(combinedId)) {
                                    newModelMeshData[combinedId] = meshDataList
                                }
                            }
                        }
                    }

                    ContextRegionCpuData(i, rx, ry, terrainMeshData, ctxObjectInstances, newModelMeshData, allModelMeshData)
                }
            }.map { it.await() }
        }

        // Phase 2 (main thread): GPU upload, model registration, and static batch building
        for (i in cpuResults.indices) {
            val cpuData = cpuResults[i]
            if (cpuData == null) {
                // Out of bounds or missing region data
                contextRegions[i] = null
                contextModelMatrices[i].identity()
                continue
            }

            val rx = cpuData.rx
            val ry = cpuData.ry

            // Compute model matrix for this neighbor's terrain
            contextModelMatrices[i].identity().translate(rx * 64 * tileSize, 0f, -ry * 64 * tileSize)

            // Upload terrain mesh data to GPU (GL calls happen here)
            val terrainMeshes = TerrainLoader.uploadTerrainMeshData(cpuData.terrainMeshData)

            // Register NEW models with the shared instance manager (needed for center region
            // objects that may share model IDs with context region objects)
            val im = instanceManager
            val ir = indirectRenderer
            if (im != null) {
                for ((combinedId, meshDataList) in cpuData.newModelMeshData) {
                    if (!registeredModels.contains(combinedId)) {
                        im.registerModel(combinedId, meshDataList)
                        ir?.registerModel(combinedId, meshDataList)
                        registeredModels.add(combinedId)
                    }
                }
            }

            contextRegions[i] = ContextRegion(rx, ry, terrainMeshes, cpuData.objectInstances)
        }

        // Phase 3 (main thread): Build static batches from all context region objects
        buildContextObjectBatches(cpuResults)
    }

    /**
     * Sequential (original) implementation of context region loading.
     * Used as fallback when running with FileCache (not thread-safe for concurrent access).
     */
    private fun loadContextRegionsSequential(centerRegionX: Int, centerRegionY: Int, plane: Int, tileSize: Float) {
        // Collect all context region data for static batch building
        val allContextData = mutableListOf<Pair<List<ObjectInstance>, Map<Int, List<MeshData>>>>()

        for (i in CONTEXT_OFFSETS.indices) {
            val rx = centerRegionX + CONTEXT_OFFSETS[i][0]
            val ry = centerRegionY + CONTEXT_OFFSETS[i][1]

            // Skip if out of bounds
            if (rx < 0 || rx > 255 || ry < 0 || ry > 255) {
                contextRegions[i] = null
                contextModelMatrices[i].identity()
                continue
            }

            // Compute model matrix for this neighbor's terrain
            contextModelMatrices[i].identity().translate(rx * 64 * tileSize, 0f, -ry * 64 * tileSize)

            // Load terrain meshes with neighbor data for seamless edge blending
            val ctxNeighbors = TerrainLoader.buildNeighborData(cache, rx, ry)
            val terrainMeshes = TerrainLoader.loadRegionMeshes(cache, rx, ry, plane, ctxNeighbors)
            if (terrainMeshes == null) {
                contextRegions[i] = null
                continue
            }

            // Load objects for this context region using ObjectRenderer.createInstancesAllPlanes
            // which produces WORLD-coord object instances
            val ctxRegionData = RegionLoader.loadRegion(cache, rx, ry)
            val ctxTerrainData = TerrainLoader.loadRegion(cache, rx, ry)
            var ctxObjectInstances: List<ObjectInstance> = emptyList()
            val allModelMeshData = mutableMapOf<Int, List<MeshData>>()

            if (ctxRegionData != null && ctxTerrainData != null) {
                val ctxTiles = ctxTerrainData.tiles

                // Create instances for all planes using world coordinates
                val allPlaneInstances = ObjectRenderer.createInstancesAllPlanes(
                    objects = ctxRegionData.objects,
                    regionX = rx,
                    regionY = ry,
                    tileHeights = { localX, localY, p ->
                        TerrainLoader.getTileHeightScaled(ctxTiles, localX, localY, p)
                    },
                    rsHeightSampler = { localX, localY, p ->
                        TerrainLoader.getTileHeight(ctxTiles, localX, localY, p)
                    }
                )

                // Flatten all planes into a single list (all rendered simultaneously)
                // Apply roof filtering consistent with the center region
                ctxObjectInstances = allPlaneInstances.values.flatten().let { instances ->
                    if (!showRoofs) instances.filter { it.mapObject.shape !in 12..21 }
                    else instances
                }

                // Load MeshData and register models with the shared instance manager.
                // Also collect ALL MeshData for static batch building.
                val im = instanceManager
                val ir = indirectRenderer
                for (obj in ctxObjectInstances) {
                    val combinedId = obj.cachedCombinedModelId
                    if (allModelMeshData.containsKey(combinedId)) continue

                    val meshDataList = if (obj.modifications.hasModifications()) {
                        ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                    } else {
                        ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                    }
                    if (meshDataList != null && meshDataList.isNotEmpty()) {
                        allModelMeshData[combinedId] = meshDataList
                        // Register with InstanceManager if not already registered
                        // (needed for center region objects that may share model IDs)
                        if (im != null && !registeredModels.contains(combinedId)) {
                            im.registerModel(combinedId, meshDataList)
                            ir?.registerModel(combinedId, meshDataList)
                            registeredModels.add(combinedId)
                        }
                    }
                }
            }

            contextRegions[i] = ContextRegion(rx, ry, terrainMeshes, ctxObjectInstances)
            allContextData.add(Pair(ctxObjectInstances, allModelMeshData))
        }

        // Build static batches from all context region objects
        buildContextObjectBatchesFromData(allContextData)
    }

    /**
     * Build static batches from context region data collected during parallel loading.
     *
     * For each context region object, computes its world-space transform matrix
     * (same math as [computeInstanceEntry]), transforms the model's vertex data
     * to world space, and adds the transformed geometry to a [StaticBatcher]
     * grouped by [BatchKey] (atlas page or texture ID).
     *
     * The resulting merged meshes are drawn with a single draw call per batch key,
     * eliminating per-frame transform computation for context region objects.
     */
    private fun buildContextObjectBatches(cpuResults: List<ContextRegionCpuData?>) {
        val allContextData = mutableListOf<Pair<List<ObjectInstance>, Map<Int, List<MeshData>>>>()
        for (cpuData in cpuResults) {
            if (cpuData == null) continue
            allContextData.add(Pair(cpuData.objectInstances, cpuData.allModelMeshData))
        }
        buildContextObjectBatchesFromData(allContextData)
    }

    /**
     * Build static batches from collected context region object data.
     *
     * @param allContextData List of (objectInstances, allModelMeshData) pairs,
     *        one per context region that has objects.
     */
    private fun buildContextObjectBatchesFromData(
        allContextData: List<Pair<List<ObjectInstance>, Map<Int, List<MeshData>>>>
    ) {
        // Clean up any existing batcher
        contextObjectBatcher?.cleanup()
        contextObjectBatcher = null

        // Collect all context objects with their MeshData
        var totalObjects = 0
        for ((instances, _) in allContextData) {
            totalObjects += instances.size
        }
        if (totalObjects == 0) return

        val batcher = StaticBatcher()
        val transformer = GeometryTransformer()
        val tempMatrix = Matrix4f()
        val atlas = objectAtlas

        var batchedCount = 0
        var skippedCount = 0

        for ((instances, meshDataMap) in allContextData) {
            for (obj in instances) {
                val combinedId = obj.cachedCombinedModelId
                val meshDataList = meshDataMap[combinedId]
                if (meshDataList == null || meshDataList.isEmpty()) {
                    skippedCount++
                    continue
                }

                // Compute world-space transform matrix (same as computeInstanceEntry)
                tempMatrix.identity()
                    .translate(obj.worldPosition)

                if (obj.terrainAlignment.needsAlignment()) {
                    tempMatrix.translate(0f, obj.terrainAlignment.heightAdjustment, 0f)
                }

                tempMatrix
                    .translate(obj.offset)
                    .scale(obj.scale)

                if (obj.terrainAlignment.needsAlignment()) {
                    tempMatrix.rotateZ(obj.terrainAlignment.tiltZ)
                    tempMatrix.rotateX(obj.terrainAlignment.tiltX)
                }

                tempMatrix.rotateY(obj.rotation)

                if (obj.mirrored) {
                    tempMatrix.scale(1f, 1f, -1f)
                }

                // Transform each MeshData batch and add to the batcher
                for (meshData in meshDataList) {
                    if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

                    // Determine the batch key based on texture and atlas state
                    val batchKey = resolveBatchKey(meshData, atlas)

                    // Remap UVs to atlas space if this mesh uses the atlas
                    val texCoords = if (batchKey.atlasPageIndex >= 0 && atlas != null && meshData.textureId >= 0) {
                        remapTexCoordsToAtlas(meshData.textureCoords, meshData.textureId, atlas)
                    } else {
                        meshData.textureCoords
                    }

                    // Transform geometry to world space and add to batcher.
                    // Use transformMatchingInstanced to match the instanced shader's normal
                    // transformation (mat3(model) * normal) for pixel-identical output.
                    val transformed = transformer.transformMatchingInstanced(
                        meshData.positions,
                        meshData.colors,
                        texCoords,
                        meshData.normals,
                        meshData.indices,
                        tempMatrix
                    )
                    batcher.addGeometry(batchKey, transformed)
                }

                batchedCount++
            }
        }

        if (batchedCount > 0) {
            // Build merged GPU meshes from accumulated geometry
            batcher.buildMeshes()
            contextObjectBatcher = batcher

            val stats = batcher.getStats()
            println("[MapViewer] Static batched $batchedCount context objects into ${stats.batchCount} batches " +
                    "(${stats.totalVertices} vertices, ${stats.totalTriangles} triangles), skipped $skippedCount")
        }
    }

    /**
     * Resolve the [BatchKey] for a MeshData entry based on the object atlas state.
     *
     * - Untextured (textureId == -1): [BatchKey.UNTEXTURED]
     * - Textured with atlas region: BatchKey(textureId = -1, atlasPageIndex = pageIndex)
     * - Textured without atlas / atlas full: BatchKey(textureId = originalId, atlasPageIndex = -1)
     */
    private fun resolveBatchKey(meshData: MeshData, atlas: TextureAtlas?): BatchKey {
        if (meshData.textureId == -1) return BatchKey.UNTEXTURED

        if (atlas != null) {
            val region = atlas.getRegion(meshData.textureId)
            if (region != null) {
                return BatchKey(textureId = -1, atlasPageIndex = region.atlasPageIndex)
            }
        }

        // Fallback: no atlas or texture not packed
        return BatchKey(textureId = meshData.textureId, atlasPageIndex = -1)
    }

    /**
     * Remap texture coordinates from local [0,1] space to atlas UV space.
     * Returns the remapped coordinates, or null if input is null.
     */
    private fun remapTexCoordsToAtlas(
        texCoords: FloatArray?,
        textureId: Int,
        atlas: TextureAtlas
    ): FloatArray? {
        if (texCoords == null) return null
        val region = atlas.getRegion(textureId) ?: return texCoords

        val remapped = FloatArray(texCoords.size)
        val uScale = region.u1 - region.u0
        val vScale = region.v1 - region.v0

        var i = 0
        while (i < texCoords.size - 1) {
            remapped[i] = region.u0 + texCoords[i] * uScale
            remapped[i + 1] = region.v0 + texCoords[i + 1] * vScale
            i += 2
        }

        return remapped
    }

    /**
     * Get terrain height at a world position.
     * Note: This function uses core RegionLoader which has raw heights.
     * For accurate heights matching the terrain mesh, use TerrainLoader's computed heights.
     *
     * @deprecated Use TerrainLoader.getTileHeightScaled for heights matching terrain mesh
     */
    @Suppress("unused")
    private fun getTerrainHeight(regionData: RegionData, worldX: Int, worldY: Int, plane: Int): Float {
        val localX = worldX and 63
        val localY = worldY and 63
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return 0f
        // Note: This uses RegionLoader's raw height * 8 formula
        // For consistency with terrain mesh, load TerrainLoader's RegionData instead
        val tileInfo = RegionLoader.getTileInfo(regionData.tiles, localX, localY, plane)
        return tileInfo.height * 8f * CoordinateUtil.DEFAULT_SCALE
    }

    /**
     * Check if a tile has the bridge flag set on plane 1.
     * The bridge flag (0x2) on plane 1 indicates that objects from higher planes
     * should be rendered on the plane below (linkBelow behavior).
     */
    private fun hasBridgeFlag(regionData: RegionData, localX: Int, localY: Int): Boolean {
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return false
        val settingsIndex = MapDefinition.index(localX, localY, 1)
        return (regionData.settings[settingsIndex].toInt() and 0x2) == 0x2
    }

    /**
     * Create object instances using LOCAL coordinates for single-region rendering.
     * The terrain mesh is at (0,0) to (63*tileSize, -63*tileSize) in local coords,
     * so objects must be positioned relative to (0,0), not world coords.
     *
     * Multi-Plane Rendering:
     * This function renders ALL objects from ALL planes (0-3) simultaneously.
     * Each object uses its own plane (obj.level) for height sampling, so objects
     * on higher planes (like rooftops, treetops) appear at the correct elevation.
     *
     * Roof Toggling:
     * When showRoofs is false, objects with roof shapes (12-21) are skipped.
     * This allows seeing inside buildings by hiding the roofs.
     *
     * The client places objects using the average height of the 4 corners at the object's
     * position (see SceneGraph.addObject in darkan-bot). For objects spanning multiple tiles,
     * it samples the center tiles of the object's bounding box.
     *
     * Height scale must match TerrainLoader.HEIGHT_SCALE = 32 * DEFAULT_SCALE
     * to ensure objects sit correctly on the terrain surface.
     *
     * Bridge Shifting (linkBelow):
     * When the bridge flag (0x2) is set on plane 1 at a tile, objects at that tile
     * use the height of the plane below (linkBelow behavior). This makes bridge deck
     * objects (railings, etc.) sit at the lower plane's height.
     * See Scene.linkBelow() in darkan-bot for reference implementation.
     */
    private fun createObjectInstances(
        regionData: RegionData,
        terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>,
        regionX: Int,
        regionY: Int,
        plane: Int
    ): List<ObjectInstance> {
        val instances = mutableListOf<ObjectInstance>()
        val baseWorldX = regionX * 64
        val baseWorldY = regionY * 64
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Get the height scale from TerrainLoader to ensure consistency
        // TerrainLoader now stores computed absolute heights (already includes * 32)
        // so HEIGHT_SCALE = 1/128 converts to OpenGL units
        val heightScale = TerrainLoader.getHeightScale()

        for (obj in regionData.objects) {
            // Convert world tile to local tile
            val localTileX = obj.x - baseWorldX
            val localTileY = obj.y - baseWorldY

            // Skip if outside region bounds
            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            // Roof shapes (12-21) - skip when showRoofs is false
            val isRoofShape = obj.shape in 12..21
            if (!showRoofs && isRoofShape) continue

            // Multi-plane rendering: Render ALL objects from ALL planes (0-3)
            // Each object uses its own plane's height data for vertical positioning
            //
            // Bridge tiles: Objects at bridge locations use their OWN plane's height.
            // The bridge deck (plane 1) has elevated height data, so objects there
            // (railings, deck tiles, etc.) render at the elevated bridge height.
            val isBridgeTile = hasBridgeFlag(regionData, localTileX, localTileY)

            // Use the object's actual plane for height sampling
            // Each object renders at its own plane's height
            val heightPlane = obj.level

            val def = ObjectRenderer.getDefinition(obj.id) ?: continue
            val modelIds = ObjectRenderer.getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            // Filter non-interactive ground decorations when preference is off
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !ObjectRenderer.groundDecorationEnabled && !ObjectRenderer.shouldRenderGroundDecoration(def)) continue
            // Skip untextured ground decoration markers (hidden under terrain in client due to GL_LEQUAL)
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !ObjectRenderer.isGroundDecorationVisible(cache, modelIds)) continue

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

            // Calculate the 4 corner tile coordinates for height sampling
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
            // Use heightPlane (object's actual plane) so bridge objects stay elevated
            // Heights now come from TerrainLoader which computes authentic RS heights
            val h00 = TerrainLoader.getTileHeight(terrainTiles, x0, y0, heightPlane)
            val h10 = TerrainLoader.getTileHeight(terrainTiles, x1, y0, heightPlane)
            val h01 = TerrainLoader.getTileHeight(terrainTiles, x0, y1, heightPlane)
            val h11 = TerrainLoader.getTileHeight(terrainTiles, x1, y1, heightPlane)
            val averageHeight = (h00 + h10 + h01 + h11) / 4f

            // Apply height scale (same as terrain)
            val terrainHeight = averageHeight * heightScale

            // Calculate WORLD position (not local) for multi-region rendering.
            // Objects use world tile coordinates so they align with terrain positioned
            // via the model matrix. Terrain mesh vertices are in local coords (0-63),
            // but the model uniform translates them to world space.
            //
            // RS client (SceneGraph.addObject) places ALL objects at tile CENTER:
            //   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + sizeX * 256
            //   sceneY = (baseY << 9) + (locSizeY << 8) = baseY * 512 + sizeY * 256
            //
            // For walls (size 1x1): sceneX = baseX * 512 + 256 (tile center)
            // Wall model geometry extends from center to the appropriate edge.
            val worldTileX = regionX * 64 + localTileX
            val worldTileY = regionY * 64 + localTileY
            val basePosX = worldTileX * tileSize
            val basePosZ = -worldTileY * tileSize

            // ALL objects (including walls) are centered within their tile footprint
            val posX = basePosX + (sizeX * tileSize * 0.5f)
            val posZ = basePosZ - (sizeY * tileSize * 0.5f)

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
            val decorOffset = ObjectRenderer.getWallDecorationOffset(obj.shape, obj.rotation, def.decorDisplacement)

            // Calculate terrain alignment for objects with groundContourType 1, 2, or 3
            // This tilts the object to match the terrain slope
            // Use heightPlane for consistency with height sampling
            val terrainAlignment = TerrainAligner.calculateObjectTilt(
                localTileX = localTileX,
                localTileY = localTileY,
                sizeX = sizeX,
                sizeY = sizeY,
                groundContourType = def.groundContourType.toInt(),
                groundContourModifier = def.groundContourModifier,
                averageHeight = terrainHeight,  // Pass the same height object is positioned at
                heightSampler = { tx, ty ->
                    // Use TerrainLoader's computed heights
                    TerrainLoader.getTileHeightScaled(terrainTiles, tx, ty, heightPlane)
                }
            )

            // Compute RS-unit scene position and average height for per-vertex displacement.
            // These match the RS client's SceneGraph.addObject:
            //   sceneX = (baseX << 9) + (locSizeX << 8)
            //   sceneY = (baseY << 9) + (locSizeY << 8)
            //   averageHeight = (h00 + h10 + h01 + h11) shr 2
            val contourType = def.groundContourType.toInt()
            val contourMod = def.groundContourModifier
            val rsSceneX: Int
            val rsSceneY: Int
            val rsAvgHeight: Int
            if (VertexDisplacer.needsDisplacement(contourType, contourMod)) {
                rsSceneX = (localTileX shl 9) + (sizeX shl 8)
                rsSceneY = (localTileY shl 9) + (sizeY shl 8)
                // Use integer average (shr 2) matching RS client, NOT floating-point /4f
                rsAvgHeight = (h00 + h10 + h01 + h11) shr 2
            } else {
                rsSceneX = 0
                rsSceneY = 0
                rsAvgHeight = 0
            }

            // Get model modifications (retexturing/recoloring) from definition
            val modifications = ObjectRenderer.getModifications(def)

            // WALL_WHOLE_CORNER (shape 2) creates TWO wall segments at the same position:
            // - Primary wall: mirrored, using the original rotation
            // - Secondary wall: not mirrored (unless def.mirrored), using (rotation + 1) & 3
            // This creates an L-shaped corner wall.
            val isWallWholeCorner = obj.shape == ObjectShape.WALL_WHOLE_CORNER.id

            if (isWallWholeCorner) {
                // Primary wall - mirrored (rotation + 4 in client causes inversion)
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = ObjectRenderer.rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = true,  // Primary wall is always mirrored for WALL_WHOLE_CORNER
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                ))

                // Secondary wall - not mirrored (unless def.mirrored), rotated by +90 degrees
                val secondaryRotation = (obj.rotation + 1) and 3
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = ObjectRenderer.rotationToRadians(secondaryRotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = def.mirrored,  // Secondary wall uses definition.mirrored only
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                ))
            } else {
                // Normal object - single instance
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = position,
                    rotation = ObjectRenderer.rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    // Combine definition offset with decoration offset
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = def.mirrored,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                ))
            }
        }

        return instances
    }

    /** Pack two tile coordinates into a single Long for use as a map key. */
    private fun packXY(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /**
     * Populate the [objectsByTile] spatial index from the current [objectInstances].
     * Each object is indexed by every tile it occupies (based on its footprint size).
     * Multi-tile objects are expanded across their full bounding box.
     */
    private fun populateObjectSpatialIndex() {
        objectsByTile.clear()
        for (obj in objectInstances) {
            val localTileX = obj.mapObject.x - currentRegionX * 64
            val localTileY = obj.mapObject.y - currentRegionY * 64

            // Determine footprint size (accounting for rotation swap)
            val sizeX: Int
            val sizeY: Int
            if (obj.mapObject.rotation == 1 || obj.mapObject.rotation == 3) {
                sizeX = obj.definition.sizeY
                sizeY = obj.definition.sizeX
            } else {
                sizeX = obj.definition.sizeX
                sizeY = obj.definition.sizeY
            }

            // Index every tile in the object's footprint
            for (dx in 0 until sizeX) {
                for (dy in 0 until sizeY) {
                    val tx = localTileX + dx
                    val ty = localTileY + dy
                    if (tx in 0..63 && ty in 0..63) {
                        objectsByTile.getOrPut(packXY(tx, ty)) { mutableListOf() }.add(obj)
                    }
                }
            }
        }
    }

    /**
     * Clamp the current ImGui window position to stay within screen bounds.
     * Call this inside ImGui.begin() block.
     */
    private fun clampWindowToScreen() {
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()
        val screenWidth = window.width.toFloat()
        val screenHeight = window.height.toFloat()

        var newX = pos.x
        var newY = pos.y

        // Clamp X: keep at least 50px visible on screen
        if (newX + size.x < 50f) newX = 50f - size.x
        if (newX > screenWidth - 50f) newX = screenWidth - 50f

        // Clamp Y: keep title bar on screen (at least 20px from top)
        if (newY < 0f) newY = 0f
        if (newY > screenHeight - 50f) newY = screenHeight - 50f

        if (newX != pos.x || newY != pos.y) {
            ImGui.setWindowPos(newX, newY)
        }
    }

    override fun onCleanup() {
        cleanupRegion()
        uniformBuffers.cleanup()
        uboColorShader.cleanup()
        uboTexturedShader.cleanup()
        terrainAlphaShader.cleanup()
        instancedColorShader.cleanup()
        instancedTexturedShader.cleanup()
        indirectColorShader?.cleanup()
        indirectTexturedShader?.cleanup()
        staticColorShader.cleanup()
        staticTexturedShader.cleanup()
        terrainAtlasShader?.cleanup()
        terrainAtlasAlphaShader?.cleanup()
        terrainAtlas?.cleanup()
        terrainAtlas = null
        objectAtlas?.cleanup()
        objectAtlas = null
        // Clear atlas lookup so TerrainLoader reverts to legacy mode
        TerrainLoader.atlasLookup = null
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
        imGui.cleanup()
    }

    /**
     * A surrounding (context) region rendered as static, non-editable terrain + objects.
     * These provide visual context around the editable center region.
     *
     * When built with neighbor data, terrainMeshes are owned by this ContextRegion and
     * must be cleaned up via [cleanup]. When built without neighbor data, they may be
     * owned by TerrainLoader's batchedMeshCache.
     */
    private class ContextRegion(
        val regionX: Int,
        val regionY: Int,
        val terrainMeshes: TerrainLoader.TerrainMeshes,
        val objectInstances: List<ObjectInstance>
    ) {
        fun cleanup() {
            terrainMeshes.cleanup()
        }
    }

    /**
     * Available editing tools for the Map Editor panel.
     * SELECT is the default mode (existing tile-pick behavior).
     * Other tools are placeholders for future editing functionality.
     */
    private enum class EditorTool(val label: String, val wip: Boolean = true) {
        SELECT("Select", wip = false),
        OBJECT_PLACE("Place Object"),
        OBJECT_MOVE("Move Object"),
        TERRAIN_PAINT("Paint Terrain"),
        HEIGHT_EDIT("Edit Height"),
        FLAG_EDIT("Edit Flags")
    }
}
