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
import org.darkan.core.map.RegionLoader
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.TerrainAligner
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainEditManager
import org.darkan.tools.cache.terrain.EditResult
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.input.TilePicker
import org.darkan.tools.engine.region.AsyncRegionManager
import org.darkan.tools.engine.region.RegionManager
import org.darkan.tools.engine.render.BatchKey
import org.darkan.tools.engine.render.IndirectRenderer
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.RenderMetrics
import org.darkan.tools.engine.render.TexturePixelProvider
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.render.TextureAtlas
import org.darkan.tools.engine.render.UniformBuffers
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.ui.DebugPanel
import org.darkan.tools.ui.ImGuiLayer
import org.darkan.tools.ui.LoadingScreen
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import world.gregs.voidps.cache.Cache

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

    // Async region manager: handles loading, object creation, displacement,
    // static batching, and dynamic render distance for all regions.
    // Center region loads synchronously; context regions load in the background.
    private lateinit var asyncRegionManager: AsyncRegionManager

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

    // UI
    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel
    private lateinit var loadingScreen: LoadingScreen

    // Current state
    private var currentRegionX = 52
    private var currentRegionY = 50
    private var currentPlane = 0

    // UI state
    private val regionXInput = ImInt(50)
    private val regionYInput = ImInt(50)
    private var showTerrain = true
    private var showRoofs = true  // Toggle roof visibility (shapes 12-21)
    private val renderDistanceInput = intArrayOf(1)  // Dynamic render distance (0-5)

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

    // Region rendering state -- terrain edit manager for the center region
    private var editManager: TerrainEditManager? = null

    // Pre-allocated texture binding callback to avoid per-frame lambda allocation
    private val terrainTextureBinding: (Int) -> Unit = { texId ->
        TextureLoader.bindTexture(TextureLoader.getTextureForMaterial(cache, texId))
    }

    // Tile picker for debugging UV issues
    private val tilePicker = TilePicker()
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

    // Overlay shape/rotation for terrain paint tool
    private val editorOverlayShape = intArrayOf(0)
    private val editorOverlayRotation = intArrayOf(0)

    // Drag tracking for continuous tools (prevents re-painting same tile)
    private val editedTilesThisDrag = mutableSetOf<Pair<Int, Int>>()

    // Object place tool state
    private var objectSearchResults: List<ObjectDefinition> = emptyList()
    private var selectedObjectId = -1
    private val editorObjectShape = intArrayOf(10)  // Default to CENTREPIECE_STRAIGHT
    private val editorObjectRotation = intArrayOf(0)

    // Object move tool state
    private data class MovingObject(
        val instance: ObjectInstance,
        val originalX: Int,
        val originalY: Int
    )
    private var movingObject: MovingObject? = null
    private var moveOriginMesh: Mesh? = null

    // Placed objects (user-placed via Object Place tool)
    private val placedObjects = mutableListOf<ObjectInstance>()

    // Brush preview state
    private var brushPreviewMesh: Mesh? = null
    private var lastBrushCenter: Pair<Int, Int>? = null
    private lateinit var brushPreviewShader: ShaderProgram

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

        // Initialize async region manager (loads definitions and XTEA keys)
        asyncRegionManager = AsyncRegionManager(cache)
        asyncRegionManager.initialize()

        // Build terrain texture atlas from all overlay/underlay texture definitions
        buildTerrainAtlas()

        // Object atlas disabled: the CPU-side UV remap (remapUVsToAtlas) cannot handle
        // model UVs outside [0,1] which rely on GL_REPEAT for tiling. This causes wrong
        // UV projections and spurious transparency when remapped UVs sample empty atlas space.
        // To re-enable, this needs shader-side fract()+remap like the terrain atlas does.
        objectAtlas = null

        // Wire async region manager to atlas references
        asyncRegionManager.objectAtlas = objectAtlas
        asyncRegionManager.terrainAtlas = terrainAtlas

        loadingScreen = LoadingScreen()

        // Load brush preview shader (RGBA vertex colors for semi-transparent overlay)
        val brushVertSrc = loadShaderSource("/shaders/brush_preview.vert")
        val brushFragSrc = loadShaderSource("/shaders/brush_preview.frag")
        brushPreviewShader = ShaderProgram.createWithUniforms(
            brushVertSrc, brushFragSrc,
            "model", "view", "projection"
        )

        // Setup the scene camera (will be repositioned when region loads)
        scene.camera.setPosition(0f, 50f, 50f)
        scene.camera.pitch = -45f
        scene.camera.yaw = -90f
        scene.camera.moveSpeed = 20f
        scene.camera.nearPlane = 1.0f
        scene.camera.farPlane = 4000f

        // Enable depth testing and multisampling
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_MULTISAMPLE)

        isInitialLoading = true
        loadRegionAsync(currentRegionX, currentRegionY, currentPlane)
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
        // Section 1: Keyboard input -- only gated by wantCaptureKeyboard
        // Camera movement, toggles, plane nav, and escape-to-cancel still work
        // even when the mouse is hovering over an ImGui panel.
        if (!imGui.wantCaptureKeyboard()) {
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

            // Toggle keys
            if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) debugPanel.toggleVisibility()
            if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) renderSystem.toggleWireframe()
            if (input.keyboard.isKeyPressed(GLFW_KEY_T)) showTerrain = !showTerrain
            if (input.keyboard.isKeyPressed(GLFW_KEY_R)) {
                showRoofs = !showRoofs
                asyncRegionManager.showRoofs = showRoofs
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

            // Escape to cancel object move
            if (input.keyboard.isKeyPressed(GLFW_KEY_ESCAPE) && movingObject != null) {
                val moving = movingObject!!
                // Restore original world position
                val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
                val origWorldX = currentRegionX * 64 + moving.originalX
                val origWorldY = currentRegionY * 64 + moving.originalY
                val def = moving.instance.definition
                val mo = moving.instance.mapObject
                val sizeX: Int
                val sizeY: Int
                if (mo.rotation == 1 || mo.rotation == 3) {
                    sizeX = def.sizeY
                    sizeY = def.sizeX
                } else {
                    sizeX = def.sizeX
                    sizeY = def.sizeY
                }
                val basePosX = origWorldX * tileSize
                val basePosZ = -origWorldY * tileSize
                val posOffsetX = sizeX * tileSize * 0.5f
                val posOffsetZ = -sizeY * tileSize * 0.5f
                moving.instance.worldPosition.x = basePosX + posOffsetX
                moving.instance.worldPosition.z = basePosZ + posOffsetZ
                // Restore Y from terrain heights
                recalcObjectHeight(moving.instance)
                movingObject = null
                moveOriginMesh?.cleanup()
                moveOriginMesh = null
            }
        }

        // Section 2: Camera mouse input -- gated by wantCaptureMouse
        if (!imGui.wantCaptureMouse()) {
            val cam = scene.camera

            // Camera rotation
            if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_RIGHT)) {
                cam.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
            }

            // Scroll zoom
            if (input.mouse.scrollY != 0.0) {
                cam.zoom(input.mouse.scrollY.toFloat())
            }
        }

        // Section 3: Tool left-click dispatch -- gated by wantCaptureMouse
        if (!imGui.wantCaptureMouse()) {
            when (currentEditorTool) {
                EditorTool.SELECT -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleTilePick()
                    }
                }
                EditorTool.TERRAIN_PAINT -> {
                    if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleTerrainPaint()
                    }
                    if (input.mouse.isButtonReleased(GLFW_MOUSE_BUTTON_LEFT)) {
                        editedTilesThisDrag.clear()
                    }
                }
                EditorTool.HEIGHT_EDIT -> {
                    if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleHeightEdit()
                    }
                }
                EditorTool.FLAG_EDIT -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleTilePick()
                    }
                }
                EditorTool.OBJECT_PLACE -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleObjectPlace()
                    }
                }
                EditorTool.OBJECT_MOVE -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT) && movingObject == null) {
                        handleObjectMoveStart()
                    } else if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT) && movingObject != null) {
                        handleObjectMoveDrag()
                    }
                    if (input.mouse.isButtonReleased(GLFW_MOUSE_BUTTON_LEFT) && movingObject != null) {
                        handleObjectMoveEnd()
                    }
                }
            }
        }
    }

    /**
     * Handle tile picking via left-click.
     */
    private fun handleTilePick() {
        val td = asyncRegionManager.centerTerrainData ?: return
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
                asyncRegionManager.centerMapObjects,
                currentRegionX * 64,
                currentRegionY * 64
            )

            // Update highlight mesh
            updateTileHighlight(td.tiles)
        }
    }

    /**
     * Perform a raycast to find the tile coordinate under the cursor.
     * Unlike handleTilePick(), this does NOT update selection state or highlight mesh.
     */
    private fun getTileUnderCursor(): Pair<Int, Int>? {
        val td = asyncRegionManager.centerTerrainData ?: return null
        val cam = scene.camera
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        tilePicker.worldOffsetX = currentRegionX * 64 * tileSize
        tilePicker.worldOffsetZ = -currentRegionY * 64 * tileSize

        tilePicker.screenToWorldRay(
            input.mouse.x.toFloat(),
            input.mouse.y.toFloat(),
            window.width,
            window.height,
            cam,
            getAspectRatio()
        )

        val picked = tilePicker.pickTile(
            td.tiles,
            currentRegionX,
            currentRegionY,
            currentPlane
        ) ?: return null

        return Pair(picked.localX, picked.localY)
    }

    /**
     * Handle terrain painting. Called continuously while mouse button is held.
     * Uses editorBrushSize radius, applies overlay or underlay based on editorPaintMode.
     */
    private fun handleTerrainPaint() {
        val em = editManager ?: return
        val center = getTileUnderCursor() ?: return
        val radius = editorBrushSize[0] - 1  // brush size 1 = single tile (radius 0)
        val id = editorOverlayUnderlayId.get()

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val tx = center.first + dx
                val ty = center.second + dy
                if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                val key = Pair(tx, ty)
                if (key in editedTilesThisDrag) continue
                editedTilesThisDrag.add(key)

                if (editorPaintMode == 0) {
                    // Overlay
                    em.mutableData.setOverlayId(
                        currentPlane, tx, ty,
                        id,
                        editorOverlayShape[0],
                        editorOverlayRotation[0]
                    )
                } else {
                    // Underlay
                    em.mutableData.setUnderlayId(currentPlane, tx, ty, id)
                }
            }
        }
    }

    /**
     * Handle height editing. Called continuously while mouse button is held.
     * Applies raise, lower, or smooth based on editorHeightMode.
     * No drag throttle -- continuous application for sculpting feel.
     */
    private fun handleHeightEdit() {
        val em = editManager ?: return
        val center = getTileUnderCursor() ?: return
        val radius = editorHeightBrushRadius[0]
        val strength = editorHeightStrength[0]

        when (editorHeightMode) {
            0, 1 -> {
                // Raise (0) or Lower (1)
                // RS heights are negative (higher terrain = more negative)
                val sign = if (editorHeightMode == 0) -1 else 1
                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.first + dx
                        val ty = center.second + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue

                        // Distance-based falloff
                        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
                        val falloff = (1f - dist / (radius + 1)).coerceIn(0f, 1f)
                        val delta = (strength * falloff * sign).toInt()

                        val current = em.mutableData.tiles[currentPlane][tx][ty].height
                        em.mutableData.setHeight(currentPlane, tx, ty, current + delta)
                    }
                }
            }
            2 -> {
                // Smooth: average height in radius, move each tile halfway toward average
                var sum = 0L
                var count = 0
                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.first + dx
                        val ty = center.second + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                        sum += em.mutableData.tiles[currentPlane][tx][ty].height
                        count++
                    }
                }
                if (count == 0) return
                val avg = (sum / count).toInt()

                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.first + dx
                        val ty = center.second + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                        val current = em.mutableData.tiles[currentPlane][tx][ty].height
                        val newHeight = current + (avg - current) / 2
                        em.mutableData.setHeight(currentPlane, tx, ty, newHeight)
                    }
                }
            }
        }
    }

    /**
     * Recompute Y positions for all center objects using current mutable tile heights.
     * Called when terrain height edits occur so objects follow the terrain.
     *
     * The height sampling algorithm matches ObjectRenderer.createInstancesAllPlanes():
     * for objects fitting within the region, it samples the center tiles of the
     * bounding box rather than the full footprint corners.
     */
    private fun updateObjectHeights() {
        val em = editManager ?: return
        val heightScale = TerrainLoader.getHeightScale()
        val baseWorldX = currentRegionX * 64
        val baseWorldY = currentRegionY * 64

        for (obj in asyncRegionManager.centerObjects) {
            val mo = obj.mapObject
            val def = obj.definition

            // Convert to local tile coordinates
            val localTileX = mo.x - baseWorldX
            val localTileY = mo.y - baseWorldY
            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (mo.rotation == 1 || mo.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Calculate center tile coordinates for height sampling (matching ObjectRenderer)
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

            // Average height of 4 sample points (matching client algorithm)
            val plane = mo.level
            val h00 = em.mutableData.tiles[plane][x0][y0].height * heightScale
            val h10 = em.mutableData.tiles[plane][x1][y0].height * heightScale
            val h01 = em.mutableData.tiles[plane][x0][y1].height * heightScale
            val h11 = em.mutableData.tiles[plane][x1][y1].height * heightScale
            val newHeight = (h00 + h10 + h01 + h11) / 4f

            // Update Y position (worldPosition is a mutable var Vector3f)
            obj.worldPosition.y = newHeight

            // Recompute terrain alignment for type 3 objects (hillChange tilt)
            if (obj.groundContourType == 3) {
                obj.terrainAlignment = TerrainAligner.calculateObjectTilt(
                    localTileX = localTileX,
                    localTileY = localTileY,
                    sizeX = sizeX,
                    sizeY = sizeY,
                    groundContourType = obj.groundContourType,
                    groundContourModifier = obj.groundContourModifier,
                    averageHeight = newHeight,
                    heightSampler = { tx, ty ->
                        val cx = tx.coerceIn(0, 63)
                        val cy = ty.coerceIn(0, 63)
                        em.mutableData.tiles[plane][cx][cy].height * heightScale
                    }
                )
            }
        }
    }

    /**
     * Search object definitions by name (case-insensitive substring) or exact ID.
     * Returns at most [limit] results.
     */
    private fun searchObjectDefinitions(query: String, limit: Int = 50): List<ObjectDefinition> {
        val defs = Cache.objects
        val results = mutableListOf<ObjectDefinition>()

        // Try exact ID match first
        val idMatch = query.toIntOrNull()
        if (idMatch != null && idMatch in defs.indices) {
            val def = defs[idMatch]
            if (def.name != "null") results.add(def)
        }

        // Name search
        val lowerQuery = query.lowercase()
        for (def in defs) {
            if (results.size >= limit) break
            if (def.name == "null" || def.id == -1) continue
            if (def.name.lowercase().contains(lowerQuery) && def !in results) {
                results.add(def)
            }
        }

        return results
    }

    /**
     * Compute world-space position for an object at a given local tile coordinate.
     * Follows the same algorithm as ObjectRenderer.createInstances() for consistent positioning.
     *
     * @return Triple of (worldPosition, sizeX, sizeY) where sizes account for rotation
     */
    private fun computeObjectWorldPosition(
        localX: Int,
        localY: Int,
        def: ObjectDefinition,
        rotation: Int
    ): Triple<Vector3f, Int, Int> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()
        val em = editManager

        val sizeX: Int
        val sizeY: Int
        if (rotation == 1 || rotation == 3) {
            sizeX = def.sizeY
            sizeY = def.sizeX
        } else {
            sizeX = def.sizeX
            sizeY = def.sizeY
        }

        // Height sampling from 4 center tiles (matching ObjectRenderer)
        val xA: Int
        val xB: Int
        if (localX + sizeX <= 64) {
            xA = localX + (sizeX shr 1)
            xB = localX + ((sizeX + 1) shr 1)
        } else {
            xA = localX
            xB = localX + 1
        }
        val yA: Int
        val yB: Int
        if (localY + sizeY <= 64) {
            yA = localY + (sizeY shr 1)
            yB = localY + ((sizeY + 1) shr 1)
        } else {
            yA = localY
            yB = localY + 1
        }
        val x0 = xA.coerceIn(0, 63)
        val x1 = xB.coerceIn(0, 63)
        val y0 = yA.coerceIn(0, 63)
        val y1 = yB.coerceIn(0, 63)

        val terrainHeight = if (em != null) {
            val h00 = em.mutableData.tiles[currentPlane][x0][y0].height * heightScale
            val h10 = em.mutableData.tiles[currentPlane][x1][y0].height * heightScale
            val h01 = em.mutableData.tiles[currentPlane][x0][y1].height * heightScale
            val h11 = em.mutableData.tiles[currentPlane][x1][y1].height * heightScale
            (h00 + h10 + h01 + h11) / 4f
        } else 0f

        val worldTileX = currentRegionX * 64 + localX
        val worldTileY = currentRegionY * 64 + localY
        val basePosX = worldTileX * tileSize
        val basePosZ = -worldTileY * tileSize
        val posOffsetX = sizeX * tileSize * 0.5f
        val posOffsetZ = -sizeY * tileSize * 0.5f

        return Triple(
            Vector3f(basePosX + posOffsetX, terrainHeight, basePosZ + posOffsetZ),
            sizeX,
            sizeY
        )
    }

    /**
     * Recalculate the Y (height) position for a single ObjectInstance
     * using current mutable tile data.
     */
    private fun recalcObjectHeight(obj: ObjectInstance) {
        val em = editManager ?: return
        val heightScale = TerrainLoader.getHeightScale()
        val baseWorldX = currentRegionX * 64
        val baseWorldY = currentRegionY * 64
        val mo = obj.mapObject
        val def = obj.definition

        val localTileX = mo.x - baseWorldX
        val localTileY = mo.y - baseWorldY
        if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) return

        val sizeX: Int
        val sizeY: Int
        if (mo.rotation == 1 || mo.rotation == 3) {
            sizeX = def.sizeY
            sizeY = def.sizeX
        } else {
            sizeX = def.sizeX
            sizeY = def.sizeY
        }

        val xA = if (localTileX + sizeX <= 64) localTileX + (sizeX shr 1) else localTileX
        val xB = if (localTileX + sizeX <= 64) localTileX + ((sizeX + 1) shr 1) else localTileX + 1
        val yA = if (localTileY + sizeY <= 64) localTileY + (sizeY shr 1) else localTileY
        val yB = if (localTileY + sizeY <= 64) localTileY + ((sizeY + 1) shr 1) else localTileY + 1

        val x0 = xA.coerceIn(0, 63)
        val x1 = xB.coerceIn(0, 63)
        val y0 = yA.coerceIn(0, 63)
        val y1 = yB.coerceIn(0, 63)

        val plane = mo.level
        val h00 = em.mutableData.tiles[plane][x0][y0].height * heightScale
        val h10 = em.mutableData.tiles[plane][x1][y0].height * heightScale
        val h01 = em.mutableData.tiles[plane][x0][y1].height * heightScale
        val h11 = em.mutableData.tiles[plane][x1][y1].height * heightScale
        obj.worldPosition.y = (h00 + h10 + h01 + h11) / 4f
    }

    /**
     * Ensure the model for an object definition is registered with the InstanceManager.
     * Returns the combined model ID, or -1 if registration failed.
     */
    private fun ensureModelRegistered(def: ObjectDefinition, shape: Int): Int {
        val im = asyncRegionManager.instanceManager ?: return -1
        val modelIds = ObjectRenderer.getModelIds(def, shape)
        if (modelIds.isEmpty()) return -1

        val modifications = ObjectRenderer.getModifications(def)
        val combinedId = ModelLoader.getCombinedModelIdWithModifications(modelIds, modifications)

        if (!im.isModelRegistered(combinedId)) {
            val meshDataList = if (modifications.hasModifications()) {
                ModelLoader.createCombinedMeshDataListWithModifications(cache, modelIds, modifications)
            } else {
                ModelLoader.createCombinedMeshDataList(cache, modelIds)
            }
            if (meshDataList != null && meshDataList.isNotEmpty()) {
                im.registerModel(combinedId, meshDataList)
            } else {
                return -1
            }
        }

        return combinedId
    }

    /**
     * Handle object placement at the clicked tile.
     * Creates an ObjectInstance and adds it to the placedObjects list so it
     * renders in the scene alongside loaded map objects.
     */
    private fun handleObjectPlace() {
        if (selectedObjectId < 0) return
        val tile = getTileUnderCursor() ?: return

        val def = ObjectRenderer.getDefinition(selectedObjectId) ?: return
        val shape = editorObjectShape[0]
        val rotation = editorObjectRotation[0]

        // Ensure model is registered with InstanceManager
        val combinedId = ensureModelRegistered(def, shape)
        if (combinedId == -1) return

        val (worldPos, sizeX, sizeY) = computeObjectWorldPosition(tile.first, tile.second, def, rotation)

        val worldX = currentRegionX * 64 + tile.first
        val worldY = currentRegionY * 64 + tile.second
        val modelIds = ObjectRenderer.getModelIds(def, shape)
        val modifications = ObjectRenderer.getModifications(def)

        // Create a synthetic MapObject for the placed object
        val mapObject = MapObject(selectedObjectId, worldX, worldY, currentPlane, shape, rotation)

        val scaleX = def.modelSizeX / 128f
        val scaleY = def.modelSizeZ / 128f
        val scaleZ = def.modelSizeY / 128f
        val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
        val defOffsetY = def.offsetZ * CoordinateUtil.DEFAULT_SCALE
        val defOffsetZ = def.offsetY * CoordinateUtil.DEFAULT_SCALE

        val instance = ObjectInstance(
            mapObject = mapObject,
            definition = def,
            modelIds = modelIds,
            worldPosition = worldPos,
            rotation = ObjectRenderer.rotationToRadians(rotation),
            scale = Vector3f(scaleX, scaleY, scaleZ),
            offset = Vector3f(defOffsetX, -defOffsetY, -defOffsetZ),
            mirrored = def.mirrored,
            modifications = modifications,
            cachedCombinedModelId = combinedId
        )

        placedObjects.add(instance)

        println("[MapEditor] Placed object '${def.name}' (id=$selectedObjectId) at ($worldX, $worldY) plane=$currentPlane shape=$shape rot=$rotation")
    }

    /**
     * Start moving an object. Find the ObjectInstance at the clicked tile
     * from centerObjects, and store it along with its original local coordinates.
     */
    private fun handleObjectMoveStart() {
        val tile = getTileUnderCursor() ?: return
        val instances = asyncRegionManager.centerObjects

        val worldX = currentRegionX * 64 + tile.first
        val worldY = currentRegionY * 64 + tile.second

        // Find the ObjectInstance at this tile by matching through mapObject coordinates
        for (instance in instances) {
            val obj = instance.mapObject
            val def = instance.definition
            if (obj.level != currentPlane) continue

            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            if (worldX in obj.x until (obj.x + sizeX) &&
                worldY in obj.y until (obj.y + sizeY)) {
                val origLocalX = obj.x - currentRegionX * 64
                val origLocalY = obj.y - currentRegionY * 64
                movingObject = MovingObject(instance, origLocalX, origLocalY)

                // Build a tile outline mesh at the original position for reference
                moveOriginMesh?.cleanup()
                moveOriginMesh = buildTileOutlineMesh(origLocalX, origLocalY, sizeX, sizeY)

                println("[MapEditor] Grabbed object '${def.name}' (id=${obj.id}) at (${obj.x}, ${obj.y}) for moving")
                return
            }
        }

        // Also check placedObjects
        for (instance in placedObjects) {
            val obj = instance.mapObject
            val def = instance.definition
            if (obj.level != currentPlane) continue

            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            if (worldX in obj.x until (obj.x + sizeX) &&
                worldY in obj.y until (obj.y + sizeY)) {
                val origLocalX = obj.x - currentRegionX * 64
                val origLocalY = obj.y - currentRegionY * 64
                movingObject = MovingObject(instance, origLocalX, origLocalY)
                moveOriginMesh?.cleanup()
                moveOriginMesh = buildTileOutlineMesh(origLocalX, origLocalY, sizeX, sizeY)
                println("[MapEditor] Grabbed placed object '${def.name}' (id=${obj.id}) for moving")
                return
            }
        }
    }

    /**
     * Drag the moving object to a new tile position.
     * Updates the object's worldPosition so it renders at the cursor location.
     */
    private fun handleObjectMoveDrag() {
        val moving = movingObject ?: return
        val tile = getTileUnderCursor() ?: return

        val def = moving.instance.definition
        val rotation = moving.instance.mapObject.rotation

        val (newPos, _, _) = computeObjectWorldPosition(tile.first, tile.second, def, rotation)
        moving.instance.worldPosition.set(newPos)
    }

    /**
     * Finalize the object move. Clears the drag state and origin marker.
     */
    private fun handleObjectMoveEnd() {
        movingObject = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
    }

    /**
     * Build a bright wireframe outline mesh marking a tile footprint.
     * Used to show where a dragged object originated from.
     */
    private fun buildTileOutlineMesh(localX: Int, localY: Int, sizeX: Int, sizeY: Int): Mesh {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()
        val em = editManager
        val worldOriginX = currentRegionX * 64 * tileSize
        val worldOriginZ = -currentRegionY * 64 * tileSize
        val yOffset = 0.2f

        // Get height at origin tile
        val h = if (em != null) {
            em.mutableData.tiles[currentPlane][localX.coerceIn(0, 63)][localY.coerceIn(0, 63)].height * heightScale
        } else 0f

        val x0 = worldOriginX + localX * tileSize
        val x1 = worldOriginX + (localX + sizeX) * tileSize
        val z0 = worldOriginZ + (-localY * tileSize)
        val z1 = worldOriginZ + (-(localY + sizeY) * tileSize)

        val positions = floatArrayOf(
            x0, h + yOffset, z0,
            x1, h + yOffset, z0,
            x1, h + yOffset, z0,
            x1, h + yOffset, z1,
            x1, h + yOffset, z1,
            x0, h + yOffset, z1,
            x0, h + yOffset, z1,
            x0, h + yOffset, z0
        )

        // Orange color for move origin marker
        val colors = FloatArray(8 * 3) { i ->
            when (i % 3) {
                0 -> 1f   // R
                1 -> 0.5f // G
                2 -> 0f   // B
                else -> 1f
            }
        }

        val indices = IntArray(8) { it }

        return Mesh(
            positions = positions,
            colors = colors,
            textureCoords = null,
            normals = null,
            indices = indices,
            colorComponents = 3
        )
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

    /**
     * Update the brush preview mesh for terrain paint / height edit tools.
     * Builds a semi-transparent filled quad mesh covering the brush area
     * conforming to the terrain surface. Only rebuilt when the center tile changes.
     */
    private fun updateBrushPreview() {
        // Only show brush preview for paint and height tools, and only when mouse is not over ImGui
        val isBrushTool = currentEditorTool == EditorTool.TERRAIN_PAINT || currentEditorTool == EditorTool.HEIGHT_EDIT
        if (!isBrushTool || imGui.wantCaptureMouse()) {
            if (brushPreviewMesh != null) {
                brushPreviewMesh?.cleanup()
                brushPreviewMesh = null
                lastBrushCenter = null
            }
            return
        }

        val center = getTileUnderCursor()
        if (center == null) {
            if (brushPreviewMesh != null) {
                brushPreviewMesh?.cleanup()
                brushPreviewMesh = null
                lastBrushCenter = null
            }
            return
        }

        // Skip rebuild if same tile
        if (center == lastBrushCenter) return
        lastBrushCenter = center

        brushPreviewMesh?.cleanup()

        val em = editManager ?: return

        val radius = when (currentEditorTool) {
            EditorTool.TERRAIN_PAINT -> editorBrushSize[0] - 1
            EditorTool.HEIGHT_EDIT -> editorHeightBrushRadius[0]
            else -> 0
        }

        // Determine brush color based on tool/mode
        val brushColor = when (currentEditorTool) {
            EditorTool.TERRAIN_PAINT -> floatArrayOf(0.2f, 0.8f, 0.2f, 0.25f)  // Green
            EditorTool.HEIGHT_EDIT -> when (editorHeightMode) {
                0 -> floatArrayOf(0.2f, 0.4f, 0.9f, 0.25f)     // Blue for raise
                1 -> floatArrayOf(0.9f, 0.2f, 0.2f, 0.25f)     // Red for lower
                2 -> floatArrayOf(0.9f, 0.9f, 0.2f, 0.25f)     // Yellow for smooth
                else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f)
            }
            else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f)
        }

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()
        val worldOriginX = currentRegionX * 64 * tileSize
        val worldOriginZ = -currentRegionY * 64 * tileSize
        val yOffset = 0.15f

        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        var vertexIndex = 0

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val tx = center.first + dx
                val ty = center.second + dy
                if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                val nextX = (tx + 1).coerceAtMost(63)
                val nextY = (ty + 1).coerceAtMost(63)

                val h00 = em.mutableData.tiles[currentPlane][tx][ty].height * heightScale
                val h10 = em.mutableData.tiles[currentPlane][nextX][ty].height * heightScale
                val h01 = em.mutableData.tiles[currentPlane][tx][nextY].height * heightScale
                val h11 = em.mutableData.tiles[currentPlane][nextX][nextY].height * heightScale

                val x0 = worldOriginX + tx * tileSize
                val x1 = worldOriginX + (tx + 1) * tileSize
                val z0 = worldOriginZ + (-ty * tileSize)
                val z1 = worldOriginZ + (-(ty + 1) * tileSize)

                // 4 corner vertices per tile quad (2 triangles)
                // SW corner
                positions.add(x0); positions.add(h00 + yOffset); positions.add(z0)
                // SE corner
                positions.add(x1); positions.add(h10 + yOffset); positions.add(z0)
                // NW corner
                positions.add(x0); positions.add(h01 + yOffset); positions.add(z1)
                // NE corner
                positions.add(x1); positions.add(h11 + yOffset); positions.add(z1)

                // RGBA color for all 4 vertices
                for (v in 0 until 4) {
                    colors.add(brushColor[0])
                    colors.add(brushColor[1])
                    colors.add(brushColor[2])
                    colors.add(brushColor[3])
                }

                // Two triangles: SW-SE-NW and SE-NE-NW
                indices.add(vertexIndex)
                indices.add(vertexIndex + 1)
                indices.add(vertexIndex + 2)
                indices.add(vertexIndex + 1)
                indices.add(vertexIndex + 3)
                indices.add(vertexIndex + 2)

                vertexIndex += 4
            }
        }

        if (positions.isNotEmpty()) {
            brushPreviewMesh = Mesh(
                positions = positions.toFloatArray(),
                colors = colors.toFloatArray(),
                textureCoords = null,
                normals = null,
                indices = indices.toIntArray(),
                colorComponents = 4
            )
        }
    }

    /**
     * Render the brush preview overlay.
     * Uses alpha blending with depth test on but depth write off so the preview
     * sits on top of terrain without occluding objects.
     */
    private fun renderBrushPreview() {
        val mesh = brushPreviewMesh ?: return

        brushPreviewShader.bind()
        brushPreviewShader.setUniform("model", Matrix4f().identity())
        brushPreviewShader.setUniform("view", scene.camera.getViewMatrix())
        brushPreviewShader.setUniform("projection", scene.camera.getProjectionMatrix(getAspectRatio()))

        // Enable blending, keep depth test, disable depth write
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)

        mesh.render()

        // Restore state
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    override fun onDrainGpuUploads(budgetNanos: Long) {
        val drainStart = RenderMetrics.startTiming()
        val previousReady = asyncRegionManager.getLoadProgress().regionsReady
        asyncRegionManager.drainGpuUploads(budgetNanos)
        val currentProgress = asyncRegionManager.getLoadProgress()
        RenderMetrics.gpuUploadDrainTimeMs = RenderMetrics.endTiming(drainStart)

        // Check if initial loading is complete
        if (isInitialLoading && currentProgress.isComplete) {
            isInitialLoading = false
            if (terrainAtlas != null) {
                asyncRegionManager.buildMergedAtlasTerrain()
            }
        } else if (!isInitialLoading && currentProgress.isComplete && currentProgress.regionsReady > previousReady) {
            // Context regions finished loading, rebuild merged terrain
            if (terrainAtlas != null) {
                asyncRegionManager.buildMergedAtlasTerrain()
            }
        }
    }

    override fun onRenderLoadingScreen() {
        // Still render the scene if we have any data (center region loads synchronously)
        RenderMetrics.resetFrame()
        glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Render whatever is loaded so far
        val renderStart = RenderMetrics.startTiming()
        renderScene()
        RenderMetrics.renderTimeMs = RenderMetrics.endTiming(renderStart)

        // Render loading overlay on top
        imGui.beginFrame()
        loadingScreen.render(asyncRegionManager.getLoadProgress(), window.width, window.height)
        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera, window.width, window.height) {
            renderSystem.toggleWireframe()
        }
        imGui.endFrame()
    }

    override fun onUpdate(deltaTime: Float) {
        // Process any pending terrain edits (dirty chunk rebuilds)
        val editResult = editManager?.processEdits() ?: EditResult.NONE

        // When terrain edits occur, the merged atlas terrain becomes stale.
        if (editResult.hasChanges && asyncRegionManager.mergedOpaqueTerrain != null) {
            asyncRegionManager.invalidateMergedTerrain()
        }

        // Reposition objects when terrain heights change
        if (editResult.heightsChanged) {
            updateObjectHeights()
        }

        // Update brush preview every frame for terrain paint / height edit tools
        updateBrushPreview()
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
        val opaqueAtlasMesh = asyncRegionManager.mergedOpaqueTerrain
        val alphaAtlasMesh = asyncRegionManager.mergedAlphaTerrain
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
            // Depth func is already GL_LEQUAL globally, no need to switch.
            if (alphaAtlasMesh != null) {
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
            }

            // Unbind atlas texture
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, 0)

            renderSystem.enableCullFace()

            // Also render context regions' legacy terrain meshes
            // (these stream in as each region becomes READY)
            val readyContextRegions = asyncRegionManager.getReadyContextRegions()
            if (readyContextRegions.isNotEmpty()) {
                renderSystem.disableCullFace()

                for (ctxRegion in readyContextRegions) {
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)

                    // Color mesh
                    terrainMeshes.colorMesh?.let { mesh ->
                        uboColorShader.bind()
                        uboColorShader.setUniform("model", regionModelMatrix)
                        mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
                    }

                    // Textured batches
                    if (terrainMeshes.texturedBatches.isNotEmpty()) {
                        uboTexturedShader.bind()
                        uboTexturedShader.setUniform("textureSampler", 0)
                        uboTexturedShader.setUniform("model", regionModelMatrix)
                        for (batch in terrainMeshes.texturedBatches) {
                            terrainTextureBinding(batch.textureId)
                            batch.mesh.render()
                            RenderMetrics.terrainOpaqueDrawCalls++
                            RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                        }
                        TextureLoader.unbindTexture()
                    }

                    // Alpha batches
                    if (terrainMeshes.alphaBatches.isNotEmpty()) {
                        glDepthMask(false)
                        glEnable(GL_BLEND)
                        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                        terrainAlphaShader.bind()
                        terrainAlphaShader.setUniform("textureSampler", 0)
                        terrainAlphaShader.setUniform("model", regionModelMatrix)
                        for (batch in terrainMeshes.alphaBatches) {
                            terrainTextureBinding(batch.textureId)
                            batch.mesh.render()
                            RenderMetrics.terrainAlphaDrawCalls++
                            RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                        }
                        TextureLoader.unbindTexture()
                        glDisable(GL_BLEND)
                        glDepthMask(true)
                    }
                }

                renderSystem.enableCullFace()
            }
        } else {
            // ===== Legacy rendering path: multi-pass per-texture draws =====
            editManager?.let { em ->
                renderSystem.disableCullFace()

                // Pass 1: Color mesh (untextured tiles) with RGB colors
                uboColorShader.bind()
                em.renderColor(uboColorShader, asyncRegionManager.centerModelMatrix)
                RenderMetrics.terrainOpaqueDrawCalls++ // center color mesh
                for (ctxRegion in asyncRegionManager.getReadyContextRegions()) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    uboColorShader.setUniform("model", regionModelMatrix)
                    terrainMeshes.colorMesh?.let { mesh ->
                        mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
                    }
                }

                // Pass 2: Textured terrain (single texture per triangle)
                uboTexturedShader.bind()
                uboTexturedShader.setUniform("textureSampler", 0)
                em.renderTextured(uboTexturedShader, asyncRegionManager.centerModelMatrix, terrainTextureBinding)
                for (ctxRegion in asyncRegionManager.getReadyContextRegions()) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    uboTexturedShader.setUniform("model", regionModelMatrix)
                    for (batch in terrainMeshes.texturedBatches) {
                        terrainTextureBinding(batch.textureId)
                        batch.mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                    }
                }
                TextureLoader.unbindTexture()

                // Pass 3: Alpha terrain (multi-pass alpha blending)
                // Depth func is already GL_LEQUAL globally, no need to switch.
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                terrainAlphaShader.bind()
                terrainAlphaShader.setUniform("textureSampler", 0)
                em.renderAlpha(terrainAlphaShader, asyncRegionManager.centerModelMatrix, terrainTextureBinding)
                for (ctxRegion in asyncRegionManager.getReadyContextRegions()) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    terrainAlphaShader.setUniform("model", regionModelMatrix)
                    for (batch in terrainMeshes.alphaBatches) {
                        terrainTextureBinding(batch.textureId)
                        batch.mesh.render()
                        RenderMetrics.terrainAlphaDrawCalls++
                        RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                    }
                }

                TextureLoader.unbindTexture()
                glDisable(GL_BLEND)
                glDepthMask(true)

                renderSystem.enableCullFace()
            }
        }

        // Render tile highlight (if any selected tile)
        renderTileHighlight()

        // Render brush preview overlay (terrain paint / height edit tools)
        renderBrushPreview()

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
     * Render the tile highlight outline for the selected tile,
     * and the move-origin marker if an object is being dragged.
     */
    private fun renderTileHighlight() {
        val highlightMesh = tileHighlightMesh
        val originMesh = moveOriginMesh

        if (highlightMesh == null && originMesh == null) return

        // Use simple shader for the highlight (no lighting)
        simpleShader.bind()
        simpleShader.setUniform("model", Matrix4f().identity())
        simpleShader.setUniform("view", scene.camera.getViewMatrix())
        simpleShader.setUniform("projection", scene.camera.getProjectionMatrix(getAspectRatio()))

        // Disable depth test to always show highlight, or enable with slight offset
        glDisable(GL_DEPTH_TEST)

        // Set line width (if supported)
        glLineWidth(3f)

        // Render tile selection highlight
        highlightMesh?.renderLines()

        // Render move origin marker (orange outline showing where the object was)
        originMesh?.renderLines()

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
        val im = asyncRegionManager.instanceManager ?: return
        val logFrame = frameNumber < 5

        val allObjects = asyncRegionManager.centerObjects + placedObjects

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     renderObjects: ${allObjects.size} center objects")
            System.out.flush()
        }

        // --- Parallel phase: compute transforms on Dispatchers.Default ---
        // Each coroutine gets its own thread-local Matrix4f to avoid allocation
        // on the hot path. The transform computation is pure math with no GL calls.
        val transformStart = RenderMetrics.startTiming()
        val entries: List<InstanceEntry> = if (allObjects.size <= PARALLEL_CHUNK_SIZE) {
            // Small object count: compute sequentially to avoid coroutine overhead
            val tempMatrix = Matrix4f()
            allObjects.map { obj ->
                computeInstanceEntry(obj, tempMatrix)
            }
        } else {
            runBlocking(Dispatchers.Default) {
                allObjects.chunked(PARALLEL_CHUNK_SIZE).map { chunk ->
                    async {
                        val tempMatrix = Matrix4f()  // Thread-local: one per coroutine
                        chunk.map { obj ->
                            computeInstanceEntry(obj, tempMatrix)
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

        // Enable polygon offset to prevent z-fighting between objects and terrain.
        // Many scenery objects (ground decorations, walls, floor decals) have geometry
        // that sits exactly coplanar with terrain surfaces. The small depth bias nudges
        // object fragments slightly closer to the camera so they consistently win the
        // depth test against terrain fragments at the same depth.
        renderSystem.enablePolygonOffset(-1.0f, -1.0f)

        if (useIndirect) {
            // === Multi-Draw Indirect rendering: 1-2 draw calls for ALL center objects ===
            val indColorShader = indirectColorShader!!
            val indTexShader = indirectTexturedShader!!

            // Render untextured objects (1 MDI call)
            ir.renderUntextured(indColorShader)

            // Render textured objects (1 MDI call) with proper GL state
            renderSystem.enableBlending()

            indTexShader.bind()
            indTexShader.setUniform("textureArray", 0)
            ir.renderTextured(indTexShader)

            renderSystem.disableBlending()

            // Capture MDI draw call count
            RenderMetrics.indirectDrawCalls = ir.getDrawCallCount()
            RenderMetrics.trianglesSubmitted += ir.getTotalTrianglesSubmitted()
        } else {
            // === Legacy instanced rendering: per-mesh draw calls ===

            // Render instanced center region objects (untextured)
            im.renderUntextured(instancedColorShader)

            // Render instanced center region objects (textured) with proper GL state.
            // Only use standard alpha blending (no alpha-to-coverage) to match the RS client.
            // Alpha-to-coverage + blending double-applies transparency, making semi-transparent
            // textures far too transparent (e.g., alpha=0.5 → ~25% effective opacity).
            renderSystem.enableBlending()

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

        // Disable polygon offset after all object rendering is complete
        renderSystem.disablePolygonOffset()

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     static batched objects done")
            System.out.flush()
        }

        renderSystem.enableCullFace()
    }

    /**
     * Render context region objects from static batched meshes.
     * Each async context region has its own set of batch meshes, keyed by [BatchKey].
     * Geometry is pre-transformed to world space, so renders with an identity model matrix.
     *
     * Batches are keyed by [BatchKey]:
     * - UNTEXTURED: vertex colors only, drawn with [staticColorShader]
     * - Atlas page (atlasPageIndex >= 0): drawn with [staticTexturedShader], atlas page bound
     * - Fallback texture (textureId >= 0, atlasPageIndex == -1): drawn with [staticTexturedShader],
     *   individual texture bound via TextureLoader
     */
    private fun renderStaticBatchedObjects() {
        val identityModel = Matrix4f().identity()
        val atlas = objectAtlas
        var totalMeshes = 0

        for (ctxRegion in asyncRegionManager.getReadyContextRegions()) {
            val meshes = ctxRegion.batchMeshes ?: continue
            if (meshes.isEmpty()) continue
            totalMeshes += meshes.size

            // Render untextured
            val untexturedMesh = meshes[BatchKey.UNTEXTURED]
            if (untexturedMesh != null) {
                staticColorShader.bind()
                staticColorShader.setUniform("model", identityModel)
                untexturedMesh.render()
                RenderMetrics.staticBatchDrawCalls++
                RenderMetrics.trianglesSubmitted += untexturedMesh.vertexCount / 3
            }

            // Render textured
            val texturedKeys = meshes.keys.filter { it != BatchKey.UNTEXTURED }
            if (texturedKeys.isNotEmpty()) {
                renderSystem.enableBlending()
                staticTexturedShader.bind()
                staticTexturedShader.setUniform("model", identityModel)
                staticTexturedShader.setUniform("textureSampler", 0)

                for (key in texturedKeys.sortedBy { it.atlasPageIndex }) {
                    val mesh = meshes[key] ?: continue
                    if (key.atlasPageIndex >= 0 && atlas != null) {
                        atlas.bindPage(key.atlasPageIndex, 0)
                    } else if (key.textureId >= 0) {
                        val glTexId = TextureLoader.getTextureForMaterial(cache, key.textureId)
                        TextureLoader.bindTexture(glTexId)
                    }
                    mesh.render()
                    RenderMetrics.staticBatchDrawCalls++
                    RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
                }

                TextureLoader.unbindTexture()
                renderSystem.disableBlending()
            }
        }

        RenderMetrics.staticBatchMeshCount = totalMeshes
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
     * @param tempMatrix Thread-local Matrix4f reused across calls to avoid allocation
     * @return An InstanceEntry with the model ID and pre-computed raw matrix data
     */
    private fun computeInstanceEntry(
        obj: ObjectInstance,
        tempMatrix: Matrix4f
    ): InstanceEntry {
        // For contourType 1 objects with displacement, use the displaced model ID
        // so the rendering path picks up the displaced geometry.
        // Fall back to the cached combined model ID for all other objects.
        val combinedModelId = asyncRegionManager.getModelIdForInstance(obj)

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

        // Render distance slider
        ImGui.spacing()
        ImGui.setNextItemWidth(120f)
        if (ImGui.sliderInt("Render Distance", renderDistanceInput, 0, RegionManager.MAX_RENDER_DISTANCE)) {
            // Will take effect on next loadRegion call
        }
        ImGui.sameLine()
        if (ImGui.button("Apply##renderDist")) {
            asyncRegionManager.setRenderDistanceAsync(renderDistanceInput[0])
        }
        if (ImGui.isItemHovered()) {
            val dist = renderDistanceInput[0]
            val gridSize = dist * 2 + 1
            val totalRegions = gridSize * gridSize
            ImGui.setTooltip("Render distance: $dist ($gridSize x $gridSize = $totalRegions regions)\n0 = center only, 1 = 3x3, 2 = 5x5, etc.")
        }
    }

    /**
     * Tool palette: buttons for each editor tool.
     */
    private fun renderToolPalette() {
        ImGui.text("Tool")

        val buttonWidth = (ImGui.getContentRegionAvailX() - ImGui.getStyle().itemSpacingX * 2f) / 3f

        for (tool in EditorTool.entries) {
            if (tool.ordinal % 3 != 0) ImGui.sameLine()

            val isSelected = currentEditorTool == tool
            if (isSelected) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1f)
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.8f)
            }

            val label = if (tool.wip) "${tool.label}*" else tool.label
            if (ImGui.button(label, buttonWidth, 0f)) {
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
                            if (def.originalTextureColours != null && def.modifiedTextureColours != null) {
                                ImGui.textColored(0.5f, 1f, 0.5f, 1f, "  Has Retexturing")
                                for (i in def.originalTextureColours!!.indices) {
                                    if (i < def.modifiedTextureColours!!.size) {
                                        val src = def.originalTextureColours!![i].toInt() and 0xffff
                                        val dst = def.modifiedTextureColours!![i].toInt() and 0xffff
                                        ImGui.text("    Tex %d -> %d".format(src, dst))
                                    }
                                }
                            }
                            if (def.originalColours != null && def.modifiedColours != null) {
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
        // Show selected object prominently at the top
        if (selectedObjectId >= 0) {
            val selDef = ObjectRenderer.getDefinition(selectedObjectId)
            if (selDef != null) {
                ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Selected: %s (ID: %d)".format(selDef.name, selDef.id))
                ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Left-click terrain to place")
            }
            ImGui.spacing()
        } else {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Search and select an object below to place it")
            ImGui.spacing()
        }

        if (ImGui.collapsingHeader("Object Browser", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.setNextItemWidth(-1f)
            if (ImGui.inputText("##obj_search", editorObjectSearchQuery)) {
                val query = editorObjectSearchQuery.get().trim()
                objectSearchResults = if (query.length >= 2) {
                    searchObjectDefinitions(query)
                } else {
                    emptyList()
                }
            }

            ImGui.spacing()

            // Scrollable results list
            val listHeight = ImGui.getContentRegionAvailY().coerceAtLeast(100f).coerceAtMost(300f)
            if (ImGui.beginChild("obj_search_results", 0f, listHeight, true)) {
                val query = editorObjectSearchQuery.get().trim()
                if (query.length < 2) {
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Type 2+ characters to search...")
                } else if (objectSearchResults.isEmpty()) {
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "No results for \"%s\"".format(query))
                } else {
                    for (def in objectSearchResults) {
                        val isSelected = selectedObjectId == def.id
                        val label = "%d: %s".format(def.id, def.name)
                        if (ImGui.selectable(label, isSelected)) {
                            selectedObjectId = def.id
                        }
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip("ID: %d\nSize: %dx%d".format(def.id, def.sizeX, def.sizeY))
                        }
                    }
                }
            }
            ImGui.endChild()

            // Shape and rotation controls
            if (selectedObjectId >= 0) {
                ImGui.spacing()
                ImGui.separator()
                ImGui.spacing()
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Shape##obj", editorObjectShape, 0, 22)
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Rotation##obj", editorObjectRotation, 0, 3)

                // Show count of placed objects
                if (placedObjects.isNotEmpty()) {
                    ImGui.spacing()
                    ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Placed objects: %d".format(placedObjects.size))
                }
            }
        }
    }

    /**
     * OBJECT_MOVE tool content: click an object to grab it, drag to reposition, release to place.
     */
    private fun renderEditorObjectMoveTool() {
        val moving = movingObject
        if (moving != null) {
            val def = moving.instance.definition
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Dragging: %s (ID: %d)".format(def.name, def.id))
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Release mouse to drop. Escape to cancel.")
            ImGui.spacing()
            ImGui.text("Original: (%d, %d)".format(
                currentRegionX * 64 + moving.originalX,
                currentRegionY * 64 + moving.originalY
            ))
            ImGui.spacing()
            if (ImGui.button("Cancel Move")) {
                // Restore original position
                val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
                val origWorldX = currentRegionX * 64 + moving.originalX
                val origWorldY = currentRegionY * 64 + moving.originalY
                val sizeX: Int
                val sizeY: Int
                val mo = moving.instance.mapObject
                if (mo.rotation == 1 || mo.rotation == 3) {
                    sizeX = def.sizeY
                    sizeY = def.sizeX
                } else {
                    sizeX = def.sizeX
                    sizeY = def.sizeY
                }
                val basePosX = origWorldX * tileSize
                val basePosZ = -origWorldY * tileSize
                moving.instance.worldPosition.x = basePosX + sizeX * tileSize * 0.5f
                moving.instance.worldPosition.z = basePosZ + -sizeY * tileSize * 0.5f
                recalcObjectHeight(moving.instance)
                movingObject = null
                moveOriginMesh?.cleanup()
                moveOriginMesh = null
            }
        } else {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click an object to grab it")
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Drag to reposition, release to place")
        }
    }

    /**
     * TERRAIN_PAINT tool content: overlay/underlay painting controls.
     */
    private fun renderEditorTerrainPaintTool() {
        if (ImGui.collapsingHeader("Terrain Painter", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click or drag to paint terrain")
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

            // Overlay-specific: shape and rotation
            if (editorPaintMode == 0) {
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Shape", editorOverlayShape, 0, 11)
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Rotation", editorOverlayRotation, 0, 3)
            }

            ImGui.spacing()

            // Brush size slider
            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Brush Size", editorBrushSize, 1, 5)

            ImGui.spacing()
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Left-click or drag to paint")
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
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Left-click and drag to edit height")
        }
    }

    /**
     * FLAG_EDIT tool content: tile flag toggle checkboxes.
     */
    private fun renderEditorFlagEditTool() {
        if (ImGui.collapsingHeader("Flag Editor", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click tile to select, toggle flags below")
            ImGui.spacing()

            val tile = tilePicker.selectedTile
            val em = editManager

            if (tile == null) {
                ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Select a tile first (left-click)")
            } else if (em == null) {
                ImGui.textColored(1f, 0.4f, 0.4f, 1f, "No edit manager available")
            } else {
                val x = tile.localX
                val y = tile.localY
                val currentFlags = em.mutableData.tiles[currentPlane][x][y].flags

                ImGui.text("Tile (%d, %d) - Flags: 0x%02X".format(x, y, currentFlags))
                ImGui.spacing()

                // Flag checkboxes -- each toggles a single bit
                var newFlags = currentFlags

                val blocksWalk = ImBoolean(currentFlags and 0x1 != 0)
                if (ImGui.checkbox("Blocks Walk (0x1)##flag_edit", blocksWalk)) {
                    newFlags = if (blocksWalk.get()) newFlags or 0x1 else newFlags and 0x1.inv()
                }
                val bridge = ImBoolean(currentFlags and 0x2 != 0)
                if (ImGui.checkbox("Bridge (0x2)##flag_edit", bridge)) {
                    newFlags = if (bridge.get()) newFlags or 0x2 else newFlags and 0x2.inv()
                }
                val invisible = ImBoolean(currentFlags and 0x4 != 0)
                if (ImGui.checkbox("Invisible (0x4)##flag_edit", invisible)) {
                    newFlags = if (invisible.get()) newFlags or 0x4 else newFlags and 0x4.inv()
                }
                val forcePlane0 = ImBoolean(currentFlags and 0x8 != 0)
                if (ImGui.checkbox("Force Plane 0 (0x8)##flag_edit", forcePlane0)) {
                    newFlags = if (forcePlane0.get()) newFlags or 0x8 else newFlags and 0x8.inv()
                }

                // Apply changes if flags were modified
                if (newFlags != currentFlags) {
                    em.mutableData.setFlags(currentPlane, x, y, newFlags)
                    // Refresh tile details so the inspector shows updated data
                    tilePicker.updateTileDetails(
                        em.mutableData.toTileArray(),
                        cache,
                        asyncRegionManager.centerMapObjects,
                        currentRegionX * 64,
                        currentRegionY * 64
                    )
                }
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


    /** Redirect old loadRegion calls to the async version. */
    private fun loadRegion(regionX: Int, regionY: Int, plane: Int) = loadRegionAsync(regionX, regionY, plane)

    private fun loadRegionAsync(regionX: Int, regionY: Int, plane: Int) {
        // Clear tile picker selection when loading new region
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null

        val regionLoadStart = RenderMetrics.startTiming()

        // Initialize chunked terrain via edit manager
        editManager?.cleanup()
        editManager = try {
            TerrainEditManager(regionX, regionY, plane, cache).also { it.init() }
        } catch (e: Exception) {
            println("[MapViewer] TerrainEditManager init failed for ($regionX, $regionY): ${e.message}")
            null
        }

        // Navigate to the new region (reuses existing context regions when possible)
        asyncRegionManager.renderDistance = renderDistanceInput[0]
        asyncRegionManager.showRoofs = showRoofs
        asyncRegionManager.navigateToRegion(regionX, regionY, plane)

        // Create IndirectRenderer if shaders are available and indirect rendering is enabled.
        val im = asyncRegionManager.instanceManager
        indirectRenderer?.cleanup()
        indirectRenderer = null
        if (useIndirectRendering && im != null && indirectColorShader != null && indirectTexturedShader != null) {
            val atlas = objectAtlas
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

            for (obj in asyncRegionManager.centerObjects) {
                val combinedId = asyncRegionManager.getModelIdForInstance(obj)
                val meshDataList = if (obj.modifications.hasModifications()) {
                    ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                } else {
                    ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                }
                if (!meshDataList.isNullOrEmpty()) {
                    ir.registerModel(combinedId, meshDataList)
                }
            }

            try {
                ir.buildSharedGeometry()
                indirectRenderer = ir
                println("[MapViewer] IndirectRenderer: ${ir.getRegisteredModelCount()} models, " +
                        "${ir.getMeshEntryCount()} mesh entries in shared geometry buffer")
            } catch (e: Exception) {
                System.err.println("[MapViewer] IndirectRenderer build failed, falling back to instanced: ${e.message}")
                ir.cleanup()
            }
        }

        // Record total region load time
        RenderMetrics.terrainLoadTimeMs = RenderMetrics.endTiming(regionLoadStart)

        updateAtlasStats()

        // Center camera on the world-space center of this region
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val worldCenterX = (regionX * 64 + 32) * tileSize
        val worldCenterZ = -(regionY * 64 + 32) * tileSize
        scene.camera.setPosition(worldCenterX, 50f, worldCenterZ + 80f)
        scene.camera.pitch = -35f
        scene.camera.yaw = 0f
        scene.camera.nearPlane = 1.0f
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
        editManager?.cleanup()
        editManager = null
        indirectRenderer?.cleanup()
        indirectRenderer = null
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null
        placedObjects.clear()
        movingObject = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
        brushPreviewMesh?.cleanup()
        brushPreviewMesh = null
        lastBrushCenter = null
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
        asyncRegionManager.destroy()  // Cancel coroutine scope
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
        brushPreviewShader.cleanup()
        brushPreviewMesh?.cleanup()
        brushPreviewMesh = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
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
     * Available editing tools for the Map Editor panel.
     * SELECT is the default mode (existing tile-pick behavior).
     * Other tools are placeholders for future editing functionality.
     */
    private enum class EditorTool(val label: String, val wip: Boolean = true) {
        SELECT("Select", wip = false),
        OBJECT_PLACE("Place Object", wip = false),
        OBJECT_MOVE("Move Object", wip = false),
        TERRAIN_PAINT("Paint Terrain", wip = false),
        HEIGHT_EDIT("Edit Height", wip = false),
        FLAG_EDIT("Edit Flags", wip = false)
    }
}
