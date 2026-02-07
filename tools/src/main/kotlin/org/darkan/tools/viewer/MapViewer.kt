package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImFloat
import imgui.type.ImInt
import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.MapObject
import org.darkan.tools.cache.map.MapScene
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.TerrainAligner
import org.darkan.tools.cache.map.VertexDisplacer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.model.ModelModifications
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.input.TilePicker
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.engine.render.ShaderProgram
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
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapDefinition

/**
 * Map/terrain viewer with full scene rendering support.
 *
 * Features:
 * - Multi-region terrain rendering with actual underlay/overlay colors
 * - XTEA-encrypted object loading
 * - Region streaming based on camera position
 * - Multi-plane object rendering (renders ALL planes 0-3 simultaneously)
 * - Roof toggling (hide shapes 12-21 to see inside buildings)
 * - Plane selection (0-3) for terrain and bridge shifting
 * - Quick navigation to known locations
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
 * - F2: Toggle navigation panel
 * - F3: Wireframe mode
 * - T: Toggle terrain
 * - R: Toggle roofs (hide to see inside buildings)
 * - G: Toggle grid
 */
class MapViewer(
    private val cache: Cache
) : Engine(WindowConfig(title = "Darkan Map Viewer", width = 1280, height = 720)) {

    // Map scene manager for multi-region support
    private lateinit var mapScene: MapScene

    // UBO-based shaders for efficient rendering
    private lateinit var uniformBuffers: UniformBuffers
    private lateinit var uboColorShader: ShaderProgram
    private lateinit var uboTexturedShader: ShaderProgram
    private lateinit var terrainAlphaShader: ShaderProgram

    // Instanced shaders for object rendering
    private lateinit var instancedColorShader: ShaderProgram
    private lateinit var instancedTexturedShader: ShaderProgram

    // UI
    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel

    // Camera (separate from scene camera for direct control)
    private val mapCamera = Camera()

    // Current state
    private var currentRegionX = 50
    private var currentRegionY = 50
    private var currentPlane = 0

    // UI state
    private val regionXInput = ImInt(50)
    private val regionYInput = ImInt(50)
    private var showNavPanel = true
    private var showTerrain = true
    private var useMultiRegion = false  // Toggle between single/multi region mode (default: single)
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

    // Single-region support
    private var singleRegionTerrainBatches: TerrainLoader.TerrainMeshes? = null
    private var singleRegionInstanceManager: InstanceManager? = null
    private var singleRegionObjects: List<ObjectInstance> = emptyList()
    private val singleRegionModels = mutableSetOf<Int>()

    // Displaced model tracking for contourType 1 objects in single-region mode.
    // Maps ObjectInstance identity hash -> displaced model ID.
    // Each contourType 1 instance gets unique geometry with per-vertex terrain displacement.
    private val singleRegionDisplacedModels = mutableMapOf<Int, Int>()
    private var nextSingleRegionDisplacedId = Int.MIN_VALUE / 2

    // Tile picker for debugging UV issues
    private val tilePicker = TilePicker()
    private var showTilePickerPanel = true
    private var singleRegionTerrainData: TerrainLoader.RegionData? = null
    private var singleRegionMapObjects: List<MapObject> = emptyList()  // Raw MapObjects for tile picker
    private var tileHighlightMesh: Mesh? = null

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

        // Load floor and object definitions (needed for both modes)
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)



        // Initialize map scene with XTEA support (lazy - for multi-region mode)
        mapScene = MapScene(cache, viewRadius = 2)

        // Configure camera for map viewing
        mapCamera.setPosition(0f, 100f, 0f)
        mapCamera.pitch = -60f
        mapCamera.yaw = 0f
        mapCamera.moveSpeed = 100f
        mapCamera.farPlane = 3000f

        // Also setup the scene camera for single-region mode
        scene.camera.setPosition(0f, 50f, 50f)
        scene.camera.pitch = -45f
        scene.camera.yaw = -90f
        scene.camera.moveSpeed = 20f
        scene.camera.farPlane = 2000f

        // Enable depth testing and multisampling
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_MULTISAMPLE)

        // Initialize based on default mode
        if (useMultiRegion) {
            mapScene.initialize()
            jumpToRegion(currentRegionX, currentRegionY)
        } else {
            // Load XTEA keys for object loading
            if (!MapXteas.isLoaded()) {
                MapXteas.load()
            }
            loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
        }

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

        // Load instanced shaders (for objects - use standard RGB shaders)
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
    }

    override fun onInput() {
        if (imGui.wantCaptureKeyboard() || imGui.wantCaptureMouse()) {
            return
        }

        val cam = if (useMultiRegion) mapCamera else scene.camera

        // Speed multiplier
        val speedMult = if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_CONTROL)) 4f else 1f
        cam.moveSpeed = if (useMultiRegion) 100f * speedMult else 20f * speedMult

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
        if (input.keyboard.isKeyPressed(GLFW_KEY_F2)) showNavPanel = !showNavPanel
        if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) renderSystem.toggleWireframe()
        if (input.keyboard.isKeyPressed(GLFW_KEY_T)) showTerrain = !showTerrain
        if (input.keyboard.isKeyPressed(GLFW_KEY_R)) {
            showRoofs = !showRoofs
            mapScene.showRoofs = showRoofs  // Sync with MapScene for multi-region mode
            // Reload objects to apply the change (single-region only - multi-region filters dynamically)
            if (!useMultiRegion) {
                loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
            }
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_M)) {
            useMultiRegion = !useMultiRegion
            if (useMultiRegion) {
                // Switching to multi-region: clean up single-region resources
                cleanupSingleRegion()
                mapScene.initialize()
                jumpToRegion(currentRegionX, currentRegionY)
            } else {
                // Switching to single-region: clean up multi-region resources
                mapScene.cleanup()
                loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
            }
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

        // Toggle tile picker panel (F4)
        if (input.keyboard.isKeyPressed(GLFW_KEY_F4)) {
            showTilePickerPanel = !showTilePickerPanel
        }

        // Tile picking (left-click in single-region mode only)
        if (!useMultiRegion && input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            handleTilePick()
        }
    }

    /**
     * Handle tile picking via left-click.
     */
    private fun handleTilePick() {
        val terrainData = singleRegionTerrainData ?: return
        val cam = scene.camera

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
            terrainData.tiles,
            currentRegionX,
            currentRegionY,
            currentPlane
        )

        if (selectedTile != null) {
            // Update tile details with object lookup
            tilePicker.updateTileDetails(
                terrainData.tiles,
                cache,
                singleRegionMapObjects,
                currentRegionX * 64,
                currentRegionY * 64
            )

            // Update highlight mesh
            updateTileHighlight(terrainData.tiles)
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

        // Calculate world positions for corners (with small Y offset to prevent z-fighting)
        val yOffset = 0.1f
        val x0 = x * tileSize
        val x1 = (x + 1) * tileSize
        val z0 = -y * tileSize
        val z1 = -(y + 1) * tileSize

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
        if (useMultiRegion) {
            // Update map scene based on camera position
            mapScene.update(mapCamera.position.x, mapCamera.position.z)

            // Perform frustum culling
            mapScene.cull(mapCamera, getAspectRatio())

            // Update current region based on camera
            val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
            val regionSize = 64 * tileSize
            currentRegionX = (mapCamera.position.x / regionSize).toInt().coerceIn(0, 255)
            currentRegionY = (-mapCamera.position.z / regionSize).toInt().coerceIn(0, 255)
        }
    }

    override fun onRender() {
        // Clear
        glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        if (useMultiRegion) {
            renderMultiRegion()
        } else {
            renderSingleRegion()
        }

        // Render UI
        renderUI()
    }

    private fun renderMultiRegion() {
        // Update shared uniforms
        uniformBuffers.updateCamera(
            mapCamera.getViewMatrix(),
            mapCamera.getProjectionMatrix(getAspectRatio()),
            mapCamera.position
        )
        uniformBuffers.updateLighting(ambientLight, lightDirection, lightColor)

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        }

        // Disable face culling for terrain (two-sided rendering)
        renderSystem.disableCullFace()

        // Render terrain (non-instanced)
        mapScene.renderTerrain(uboColorShader)

        // Restore GL state for objects
        renderSystem.enableCullFace()

        // Render objects (instanced)
        mapScene.renderObjects(
            instancedColorShader,
            instancedTexturedShader
        ) { textureId ->
            TextureLoader.bindTexture(textureId)
        }

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        }
    }

    private fun renderSingleRegion() {
        // Update UBOs for lighting (same as multi-region)
        uniformBuffers.updateCamera(
            scene.camera.getViewMatrix(),
            scene.camera.getProjectionMatrix(getAspectRatio()),
            scene.camera.position
        )
        uniformBuffers.updateLighting(ambientLight, lightDirection, lightColor)

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        }

        // Render terrain with vertex color blending (no alpha needed - colors match at boundaries)
        val terrainBatches = singleRegionTerrainBatches
        if (terrainBatches != null) {
            val modelMatrix = Matrix4f().identity()

            // Disable face culling for terrain (two-sided rendering)
            renderSystem.disableCullFace()

            // Render color mesh (untextured tiles) with RGB colors
            terrainBatches.colorMesh?.let { mesh ->
                uboColorShader.bind()
                uboColorShader.setUniform("model", modelMatrix)
                mesh.render()
            }

            // Render textured terrain batches (single texture per triangle)
            val batches = terrainBatches.texturedBatches
            if (batches.isNotEmpty()) {
                uboTexturedShader.bind()
                uboTexturedShader.setUniform("model", modelMatrix)
                uboTexturedShader.setUniform("textureSampler", 0)

                for (batch in batches) {
                    TextureLoader.bindTexture(
                        TextureLoader.getTextureForMaterial(cache, batch.textureId)
                    )
                    batch.mesh.render()
                }

                TextureLoader.unbindTexture()
            }

            // Render alpha terrain batches (multi-pass alpha blending)
            // Each batch is a single texture layer rendered on top of the opaque base
            // with per-vertex alpha controlling the blend strength.
            val alpha = terrainBatches.alphaBatches
            if (alpha.isNotEmpty()) {
                // Alpha layers render at the same depth as the opaque base.
                // Use GL_LEQUAL so equal-depth fragments pass, and disable depth
                // writes so alpha layers don't block each other.
                glDepthFunc(GL_LEQUAL)
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                terrainAlphaShader.bind()
                terrainAlphaShader.setUniform("model", modelMatrix)
                terrainAlphaShader.setUniform("textureSampler", 0)

                for (batch in alpha) {
                    TextureLoader.bindTexture(
                        TextureLoader.getTextureForMaterial(cache, batch.textureId)
                    )
                    batch.mesh.render()
                }

                TextureLoader.unbindTexture()
                glDisable(GL_BLEND)
                glDepthMask(true)
                glDepthFunc(GL_LESS)
            }

            // Restore GL state
            renderSystem.enableCullFace()
        }

        // Render tile highlight (if any selected tile)
        renderTileHighlight()

        // Render objects
        renderSingleRegionObjects()

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

    private val tempModelMatrix = Matrix4f()

    private fun renderSingleRegionObjects() {
        val instanceManager = singleRegionInstanceManager ?: return

        instanceManager.prepareFrame()

        var addedCount = 0
        for (obj in singleRegionObjects) {
            // For contourType 1 objects with displacement, use the displaced model ID
            // so the rendering path picks up the displaced geometry.
            // Fall back to the standard combined model ID for all other objects.
            val combinedModelId = singleRegionDisplacedModels[System.identityHashCode(obj)]
                ?: ObjectRenderer.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)

            // Build transform matrix matching client order:
            // RS client applies to model vertices: Rotate Y -> Terrain Tilt -> Scale -> Offset
            // Then model is placed at world position with height adjustment
            // Matrix operations are right-to-left, so we specify them in reverse order
            //
            // For terrain alignment (groundContourType=3), the client's hillChange applies:
            // 1. X-axis rotation (pitch tilt based on N/S slope)
            // 2. Z-axis rotation (roll tilt based on E/W slope)
            // 3. Y translation (height adjustment to sit on lowest corner)
            tempModelMatrix.identity()
                .translate(obj.worldPosition)        // 6. Move to world position

            // Apply terrain alignment (height adjustment + tilt rotations)
            if (obj.terrainAlignment.needsAlignment()) {
                // Height adjustment from terrain alignment (after world position)
                tempModelMatrix.translate(0f, obj.terrainAlignment.heightAdjustment, 0f)
            }

            tempModelMatrix
                .translate(obj.offset)               // 5. Apply definition offset
                .scale(obj.scale)                    // 4. Scale

            // Apply terrain tilt rotations (after Y rotation in application order)
            // RS applies: RotateY -> TiltX -> TiltZ (in that order on vertices)
            // Matrix equivalent (right-to-left): TiltZ * TiltX * RotateY
            if (obj.terrainAlignment.needsAlignment()) {
                tempModelMatrix.rotateZ(obj.terrainAlignment.tiltZ)  // 3. Roll tilt (last in app order)
                tempModelMatrix.rotateX(obj.terrainAlignment.tiltX)  // 2. Pitch tilt
            }

            tempModelMatrix.rotateY(obj.rotation)   // 1. Object Y rotation (first in app order)

            // Apply mirroring if needed (negates Z coordinates)
            // This is applied last in the matrix chain = first on vertices
            if (obj.mirrored) {
                tempModelMatrix.scale(1f, 1f, -1f)  // 0. Mirror across XY plane
            }

            if (instanceManager.addInstance(combinedModelId, tempModelMatrix)) {
                addedCount++
            }
        }

        instanceManager.uploadAll()

        // IMPORTANT: Disable face culling for RS models which often have two-sided faces
        renderSystem.disableCullFace()

        // Render untextured batches first (vertex colors only)
        // The shader uses UBOs for camera/lighting which were already updated
        instanceManager.renderUntextured(instancedColorShader)

        // Render textured batches with proper GL state (like ModelViewer)
        renderSystem.enableBlending()
        renderSystem.enableAlphaToCoverage()

        instancedTexturedShader.bind()
        instancedTexturedShader.setUniform("textureSampler", 0)

        instanceManager.renderTextured(instancedTexturedShader) { materialId ->
            // Convert material ID to OpenGL texture ID (same as ModelViewer)
            val textureId = TextureLoader.getTextureForMaterial(cache, materialId)
            TextureLoader.bindTexture(textureId)
        }

        TextureLoader.unbindTexture()

        // Restore GL state
        renderSystem.disableBlending()
        renderSystem.disableAlphaToCoverage()
        renderSystem.enableCullFace()
    }

    private fun renderUI() {
        imGui.beginFrame()

        // Debug panel
        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(if (useMultiRegion) mapCamera else scene.camera, window.width, window.height) {
            renderSystem.toggleWireframe()
        }

        // Navigation panel
        if (showNavPanel) {
            renderNavigationPanel()
        }

        // Tile picker panel (only in single-region mode)
        if (showTilePickerPanel && !useMultiRegion) {
            renderTilePickerPanel()
        }

        imGui.endFrame()
    }

    // Track first frame for initial positioning of panels
    private var tilePickerFirstFrame = true
    private var navPanelFirstFrame = true

    /**
     * Render the tile picker information panel.
     * Shows detailed information about the selected tile for debugging UV issues.
     */
    private fun renderTilePickerPanel() {
        // Set size constraints: min width 320, max height 80% of screen
        val maxHeight = window.height * 0.8f
        ImGui.setNextWindowSizeConstraints(320f, 150f, 450f, maxHeight)

        // Only set initial position on first frame
        if (tilePickerFirstFrame) {
            ImGui.setNextWindowPos(10f, 180f)
            tilePickerFirstFrame = false
        }

        // Allow moving and resizing, scrollbar appears automatically when needed
        val flags = ImGuiWindowFlags.None

        if (ImGui.begin("Tile Picker (F4) - Click to Select", flags)) {
            // Clamp window position to stay on screen
            clampWindowToScreen()
            val details = tilePicker.selectedTileDetails
            val tile = tilePicker.selectedTile

            if (tile == null) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Left-click on terrain to select a tile")
                ImGui.separator()
                ImGui.text("This panel shows tile details for debugging")
                ImGui.text("overlay/underlay textures and UV mapping.")
            } else {
                // Tile coordinates
                ImGui.text("Tile Coordinates")
                ImGui.separator()
                ImGui.text("  Region: (%d, %d)".format(tile.regionX, tile.regionY))
                ImGui.text("  Local:  (%d, %d)".format(tile.localX, tile.localY))
                ImGui.text("  Plane:  %d".format(tile.plane))
                ImGui.text("  World:  (%.2f, %.2f, %.2f)".format(tile.worldX, tile.worldY, tile.worldZ))

                if (details != null) {
                    val tileData = details.tileData
                    if (tileData != null) {
                        ImGui.spacing()
                        ImGui.text("Tile Data")
                        ImGui.separator()
                        ImGui.text("  Height:   %d".format(tileData.height))
                        ImGui.text("  Flags:    0x%02X".format(tileData.flags))
                        if (tileData.flags != 0) {
                            val flagsList = mutableListOf<String>()
                            if (tileData.blocksWalk) flagsList.add("BLOCK_WALK")
                            if (tileData.isBridge) flagsList.add("BRIDGE")
                            if (tileData.isInvisible) flagsList.add("INVISIBLE")
                            if (tileData.forcesCollisionPlane0) flagsList.add("FORCE_PLANE_0")
                            ImGui.text("            ${flagsList.joinToString(", ")}")
                        }

                        ImGui.spacing()
                        ImGui.text("Overlay")
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
                        ImGui.text("Underlay")
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

                        // Objects on tile
                        ImGui.spacing()
                        ImGui.text("Objects on Tile (%d)".format(details.objects.size))
                        ImGui.separator()
                        if (details.objects.isEmpty()) {
                            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  (No objects)")
                        } else {
                            for ((index, tileObj) in details.objects.withIndex()) {
                                val obj = tileObj.mapObject
                                val def = tileObj.definition
                                if (ImGui.treeNode("obj_$index", "Object %d: %s".format(obj.id, def.name.ifEmpty { "<unnamed>" }))) {
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
        ImGui.end()
    }

    private fun renderNavigationPanel() {
        // Set size constraints: min width 280, max height 80% of screen
        val maxHeight = window.height * 0.8f
        ImGui.setNextWindowSizeConstraints(280f, 200f, 400f, maxHeight)

        // Only set initial position on first frame (right side of screen)
        if (navPanelFirstFrame) {
            ImGui.setNextWindowPos(window.width - 290f, 10f)
            navPanelFirstFrame = false
        }

        // Allow moving and resizing, scrollbar appears automatically when needed
        val flags = ImGuiWindowFlags.None

        if (ImGui.begin("Navigation (F2)", flags)) {
            // Clamp window position to stay on screen
            clampWindowToScreen()
            // Mode indicator
            ImGui.text("Mode: %s".format(if (useMultiRegion) "Multi-Region" else "Single"))
            if (ImGui.button(if (useMultiRegion) "Switch to Single (M)" else "Switch to Multi (M)")) {
                useMultiRegion = !useMultiRegion
                if (useMultiRegion) {
                    cleanupSingleRegion()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                } else {
                    mapScene.cleanup()
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                }
            }

            ImGui.separator()

            // Current position
            ImGui.text("Region: (%d, %d)".format(currentRegionX, currentRegionY))
            ImGui.text("Plane: %d".format(currentPlane))

            if (useMultiRegion) {
                val stats = mapScene.getStats()
                ImGui.text("Loaded: %d regions".format(stats.loadedRegions))
                ImGui.text("Visible: %d regions".format(stats.visibleRegions))
                ImGui.text("XTEA Keys: %d".format(MapXteas.size()))
            }

            ImGui.separator()

            // Region navigation
            ImGui.text("Go to Region:")
            ImGui.setNextItemWidth(80f)
            ImGui.inputInt("X", regionXInput)
            ImGui.sameLine()
            ImGui.setNextItemWidth(80f)
            ImGui.inputInt("Y", regionYInput)

            if (ImGui.button("Go", 60f, 0f)) {
                jumpToRegion(regionXInput.get().coerceIn(0, 255), regionYInput.get().coerceIn(0, 255))
            }

            ImGui.separator()

            // Plane selection
            ImGui.text("Plane (1-4 / PgUp/PgDn):")
            for (plane in 0..3) {
                if (plane > 0) ImGui.sameLine()
                if (ImGui.radioButton("$plane", currentPlane == plane)) {
                    setPlane(plane)
                }
            }

            ImGui.separator()

            // Quick locations
            ImGui.text("Quick Locations:")
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

            ImGui.separator()

            // Layer toggles
            if (ImGui.checkbox("Show Terrain (T)", showTerrain)) {
                showTerrain = !showTerrain
            }

            // Roof visibility toggle
            val roofsEnabled = ImBoolean(showRoofs)
            if (ImGui.checkbox("Show Roofs (R)", roofsEnabled)) {
                showRoofs = roofsEnabled.get()
                mapScene.showRoofs = showRoofs  // Sync with MapScene for multi-region mode
                // Reload objects to apply the change (single-region only - multi-region filters dynamically)
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Toggle visibility of roof shapes (12-21)\nHide roofs to see inside buildings")
            }

            // Ground decoration toggle
            val groundDecEnabled = ImBoolean(ObjectRenderer.groundDecorationEnabled)
            if (ImGui.checkbox("Ground Decorations", groundDecEnabled)) {
                ObjectRenderer.groundDecorationEnabled = groundDecEnabled.get()
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                } else {
                    mapScene.cleanup()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Toggle ground decoration rendering\nWhen off, only interactive/blocking ground decorations render")
            }

            // Marker face filter toggle
            val hideMarkerFaces = ImBoolean(ModelLoader.hideUntexturedMarkerFaces)
            if (ImGui.checkbox("Hide Marker Faces", hideMarkerFaces)) {
                ModelLoader.hideUntexturedMarkerFaces = hideMarkerFaces.get()
                // Reload to regenerate meshes with the new filter setting
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                } else {
                    mapScene.cleanup()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Hide untextured faces on models that also have textured faces.\nFilters out label backing panels, ground markers, and other\nplaceholder geometry (e.g. signpost green panels).")
            }

            // Terrain blending toggle
            val blendingEnabled = ImBoolean(TerrainLoader.enableBlending)
            if (ImGui.checkbox("Terrain Blending", blendingEnabled)) {
                TerrainLoader.enableBlending = blendingEnabled.get()
                // Reload terrain to apply the change
                TerrainLoader.clearCache()
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                } else {
                    // Force multi-region reload
                    mapScene.cleanup()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                }
            }

            // Overlay toggle (for debugging underlay-only terrain)
            val overlaysEnabled = ImBoolean(TerrainLoader.enableOverlays)
            if (ImGui.checkbox("Show Overlays", overlaysEnabled)) {
                TerrainLoader.enableOverlays = overlaysEnabled.get()
                // Reload terrain to apply the change
                TerrainLoader.clearCache()
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                } else {
                    mapScene.cleanup()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                }
            }

            // Color noise toggle - adds subtle per-vertex brightness variation like RS client
            val colorNoiseEnabled = ImBoolean(TerrainLoader.enableColorNoise)
            if (ImGui.checkbox("Color Noise", colorNoiseEnabled)) {
                TerrainLoader.enableColorNoise = colorNoiseEnabled.get()
                // Reload terrain to apply the change
                TerrainLoader.clearCache()
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                } else {
                    mapScene.cleanup()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Adds subtle per-vertex brightness variation\nlike the RS client (+-1.5%% noise)")
            }

            // Terrain lighting toggle
            val lightingEnabled = ImBoolean(TerrainLoader.enableLighting)
            if (ImGui.checkbox("Terrain Lighting", lightingEnabled)) {
                TerrainLoader.enableLighting = lightingEnabled.get()
                TerrainLoader.clearCache()
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                } else {
                    mapScene.cleanup()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                }
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
                    TerrainLoader.clearCache()
                    if (!useMultiRegion) {
                        loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                    } else {
                        mapScene.cleanup()
                        mapScene.initialize()
                        jumpToRegion(currentRegionX, currentRegionY)
                    }
                }

                ImGui.unindent()
            }

            ImGui.separator()

            // Terrain alignment toggle (for objects with groundContourType=3)
            val terrainAlignEnabled = ImBoolean(TerrainAligner.enabled)
            if (ImGui.checkbox("Terrain Alignment", terrainAlignEnabled)) {
                TerrainAligner.enabled = terrainAlignEnabled.get()
                // Reload objects to apply the change
                if (!useMultiRegion) {
                    loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                } else {
                    mapScene.cleanup()
                    mapScene.initialize()
                    jumpToRegion(currentRegionX, currentRegionY)
                }
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
                    if (!useMultiRegion) {
                        loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                    } else {
                        mapScene.cleanup()
                        mapScene.initialize()
                        jumpToRegion(currentRegionX, currentRegionY)
                    }
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Negate X-axis tilt (pitch) for coordinate system fix")
                }

                val negateZTilt = ImBoolean(TerrainAligner.negateZTilt)
                if (ImGui.checkbox("Negate Z Tilt", negateZTilt)) {
                    TerrainAligner.negateZTilt = negateZTilt.get()
                    if (!useMultiRegion) {
                        loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                    } else {
                        mapScene.cleanup()
                        mapScene.initialize()
                        jumpToRegion(currentRegionX, currentRegionY)
                    }
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Negate Z-axis tilt (roll) for coordinate system fix")
                }

                // Button to enable debug logging
                if (ImGui.button("Enable Debug Logging")) {
                    TerrainAligner.enableDebug(true)
                    if (!useMultiRegion) {
                        loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
                    } else {
                        mapScene.cleanup()
                        mapScene.initialize()
                        jumpToRegion(currentRegionX, currentRegionY)
                    }
                }

                ImGui.unindent()
            }

            ImGui.separator()

            // Controls help
            ImGui.textWrapped("WASD move, Space/Shift up/down, Right-click look, Arrows navigate, Ctrl fast, R roofs")
        }
        ImGui.end()
    }

    private fun setPlane(plane: Int) {
        currentPlane = plane.coerceIn(0, 3)
        mapScene.setPlane(currentPlane)

        if (!useMultiRegion) {
            loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
        }
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

        if (useMultiRegion) {
            val newPos = mapScene.jumpToRegion(currentRegionX, currentRegionY)
            mapCamera.setPosition(newPos.x, newPos.y + 50f, newPos.z)
        } else {
            loadSingleRegion(currentRegionX, currentRegionY, currentPlane)
        }
    }

    private fun loadSingleRegion(regionX: Int, regionY: Int, plane: Int) {
        // Clean up existing resources
        cleanupSingleRegion()

        // Clear tile picker selection when loading new region
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null

        // Load terrain meshes with texture batching
        singleRegionTerrainBatches = TerrainLoader.loadRegionMeshes(cache, regionX, regionY, plane)

        // Load objects
        val regionData = RegionLoader.loadRegion(cache, regionX, regionY)
        // Also load terrain data from TerrainLoader for height values
        // TerrainLoader computes authentic RS heights (cumulative, multi-octave noise)
        val terrainData = TerrainLoader.loadRegion(cache, regionX, regionY)

        // Store terrain data for tile picker
        singleRegionTerrainData = terrainData
        // Store raw MapObjects for tile picker object lookup
        singleRegionMapObjects = regionData?.objects ?: emptyList()

        if (regionData != null && terrainData != null) {
            // Create instance manager for this region (smaller capacity than multi-region)
            singleRegionInstanceManager = InstanceManager(maxInstancesPerMesh = 5000)

            // Create object instances for this plane using LOCAL coordinates
            // The terrain mesh is at local coords (0,0) to (63,63) tiles
            // So objects must also be at local coordinates
            // Pass terrainData.tiles for height lookups (matches TerrainLoader's computed heights)
            singleRegionObjects = createLocalObjectInstances(regionData, terrainData.tiles, regionX, regionY, plane)

            // Register models with instance manager
            // Use combined models for multi-model objects (matching RS client behavior)
            // Apply retexturing/recoloring from ObjectDefinition if present
            var registeredCount = 0
            for (obj in singleRegionObjects) {
                // Get the combined model ID for this object's models, including modifications
                // Objects with different retexture/recolor mappings get separate registrations
                val combinedId = ObjectRenderer.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)
                if (singleRegionModels.contains(combinedId)) continue

                // Load and combine all models for this object with modifications applied
                val meshDataList = if (obj.modifications.hasModifications()) {
                    ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                } else {
                    ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                }
                if (meshDataList != null && meshDataList.isNotEmpty()) {
                    singleRegionInstanceManager!!.registerModel(combinedId, meshDataList)
                    singleRegionModels.add(combinedId)
                    registeredCount++
                }
            }

            // Register displaced meshes for contourType 1 objects.
            // Each instance gets a unique mesh with per-vertex terrain displacement,
            // registered with a unique model ID. These use the instanced rendering path
            // with 1 instance each (the displacement is baked into the geometry).
            val terrainTiles = terrainData.tiles
            for (obj in singleRegionObjects) {
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
                    val displacedId = nextSingleRegionDisplacedId++
                    singleRegionInstanceManager!!.registerModel(displacedId, displacedMeshDataList)
                    singleRegionModels.add(displacedId)
                    singleRegionDisplacedModels[System.identityHashCode(obj)] = displacedId
                }
            }
        }

        // Center camera on the local terrain mesh (not world coordinates)
        // Terrain mesh spans from (0, 0) to (63 * tileSize, -63 * tileSize)
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val meshCenterX = 32f * tileSize
        val meshCenterZ = -32f * tileSize
        scene.camera.setPosition(meshCenterX, 50f, meshCenterZ + 80f)
        scene.camera.pitch = -35f
        scene.camera.yaw = 0f
    }

    private fun cleanupSingleRegion() {
        // Clear reference to batched terrain meshes (actual cleanup is handled by TerrainLoader cache)
        singleRegionTerrainBatches = null
        singleRegionTerrainData = null
        singleRegionMapObjects = emptyList()
        singleRegionInstanceManager?.cleanup()
        singleRegionInstanceManager = null
        singleRegionObjects = emptyList()
        singleRegionModels.clear()
        singleRegionDisplacedModels.clear()
        nextSingleRegionDisplacedId = Int.MIN_VALUE / 2
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null
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
    private fun createLocalObjectInstances(
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

            // Calculate local position (matching terrain mesh coordinates)
            // Terrain uses: x = localX * tileSize, z = -localY * tileSize
            //
            // RS client (SceneGraph.addObject) places ALL objects at tile CENTER:
            //   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + sizeX * 256
            //   sceneY = (baseY << 9) + (locSizeY << 8) = baseY * 512 + sizeY * 256
            //
            // For walls (size 1x1): sceneX = baseX * 512 + 256 (tile center)
            // Wall model geometry extends from center to the appropriate edge.
            val basePosX = localTileX * tileSize
            val basePosZ = -localTileY * tileSize

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
        cleanupSingleRegion()
        mapScene.cleanup()
        uniformBuffers.cleanup()
        uboColorShader.cleanup()
        uboTexturedShader.cleanup()
        terrainAlphaShader.cleanup()
        instancedColorShader.cleanup()
        instancedTexturedShader.cleanup()
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
        imGui.cleanup()
    }
}
