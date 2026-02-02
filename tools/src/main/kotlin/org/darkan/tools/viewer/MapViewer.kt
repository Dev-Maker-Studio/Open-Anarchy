package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImFloat
import imgui.type.ImInt
import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.map.MapScene
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.TerrainAligner
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.InstanceManager
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

/**
 * Map/terrain viewer with full scene rendering support.
 *
 * Features:
 * - Multi-region terrain rendering with actual underlay/overlay colors
 * - XTEA-encrypted object loading
 * - Region streaming based on camera position
 * - Plane selection (0-3)
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

        println("Map Viewer initialized")
        println("  XTEA keys loaded: ${MapXteas.size()}")
        println("  Multi-region mode: $useMultiRegion")
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

            // Render textured terrain batches
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

            // Restore GL state
            renderSystem.enableCullFace()
        }

        // Render objects
        renderSingleRegionObjects()

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        }
    }

    private var debugObjectRenderFrame = 0
    private val tempModelMatrix = Matrix4f()
    private var wallRotationDebugDone = false

    private fun renderSingleRegionObjects() {
        val instanceManager = singleRegionInstanceManager ?: return

        instanceManager.prepareFrame()

        // Debug: verify wall rotations are different on first frame
        if (!wallRotationDebugDone) {
            wallRotationDebugDone = true
            val wallsByRotation = singleRegionObjects
                .filter { it.mapObject.shape == 0 } // WALL_STRAIGHT
                .groupBy { it.mapObject.rotation }
            println("=== WALL ROTATION DEBUG ===")
            for ((rot, walls) in wallsByRotation.entries.sortedBy { it.key }) {
                val radians = walls.firstOrNull()?.rotation ?: 0f
                val degrees = Math.toDegrees(radians.toDouble())
                println("  Rotation $rot: ${walls.size} walls, radians=$radians, degrees=$degrees")
                // Print first wall's matrix values
                walls.firstOrNull()?.let { wall ->
                    val testMatrix = Matrix4f().identity().rotateY(wall.rotation)
                    println("    Matrix m00=${testMatrix.m00()} m02=${testMatrix.m02()} m20=${testMatrix.m20()} m22=${testMatrix.m22()}")
                }
            }
            println("===========================")
        }

        var addedCount = 0
        for (obj in singleRegionObjects) {
            // Use combined model ID for multi-model objects
            val combinedModelId = ObjectRenderer.getCombinedModelId(obj.modelIds)

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

        // Debug output for first frame only
        if (debugObjectRenderFrame == 0) {
            val stats = instanceManager.getStats()
            val untexturedCount = stats.instancesPerBatch.count { it.key.textureId == -1 && it.value > 0 }
            val texturedCount = stats.instancesPerBatch.count { it.key.textureId != -1 && it.value > 0 }
            println("Objects: $addedCount instances, ${stats.registeredModels} models, untextured=$untexturedCount, textured=$texturedCount batches")

            // Debug: show first multi-model object's batch instance counts
            for (obj in singleRegionObjects) {
                if (obj.modelIds.size > 1) {
                    val combinedId = ObjectRenderer.getCombinedModelId(obj.modelIds)
                    val batchesForModel = stats.instancesPerBatch.filter { it.key.modelId == combinedId }
                    println("=== Multi-model ${obj.definition.name} batch instance counts ===")
                    for ((key, count) in batchesForModel) {
                        println("  Batch (modelId=${key.modelId}, textureId=${key.textureId}): $count instances")
                    }
                    break // Only show first one
                }
            }
            debugObjectRenderFrame++
        }

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
        debugPanel.render(if (useMultiRegion) mapCamera else scene.camera) {
            renderSystem.toggleWireframe()
        }

        // Navigation panel
        if (showNavPanel) {
            renderNavigationPanel()
        }

        imGui.endFrame()
    }

    private fun renderNavigationPanel() {
        ImGui.setNextWindowPos(window.width - 290f, 10f)
        ImGui.setNextWindowSize(280f, 0f)

        val flags = ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize or ImGuiWindowFlags.AlwaysAutoResize

        if (ImGui.begin("Navigation (F2)", flags)) {
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

            // Terrain blending toggle
            val blendingEnabled = ImBoolean(TerrainLoader.enableBlending)
            if (ImGui.checkbox("Terrain Blending", blendingEnabled)) {
                TerrainLoader.enableBlending = blendingEnabled.get()
                println("[MapViewer] Terrain blending: ${if (blendingEnabled.get()) "ENABLED" else "DISABLED"}")
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
                println("[MapViewer] Overlay rendering: ${if (overlaysEnabled.get()) "ENABLED" else "DISABLED"}")
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
                println("[MapViewer] Color noise: ${if (colorNoiseEnabled.get()) "ENABLED" else "DISABLED"}")
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
                println("[MapViewer] Terrain lighting: ${if (lightingEnabled.get()) "ENABLED" else "DISABLED"}")
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
                println("[MapViewer] Terrain alignment: ${if (terrainAlignEnabled.get()) "ENABLED" else "DISABLED"}")
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
                    println("[MapViewer] Negate X tilt: ${if (negateXTilt.get()) "YES" else "NO"}")
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
                    println("[MapViewer] Negate Z tilt: ${if (negateZTilt.get()) "YES" else "NO"}")
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
                    println("[MapViewer] Terrain alignment debug enabled - reload region to see stats")
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
            ImGui.textWrapped("WASD move, Space/Shift up/down, Right-click look, Arrows navigate, Ctrl fast")
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

        // Load terrain meshes with texture batching
        singleRegionTerrainBatches = TerrainLoader.loadRegionMeshes(cache, regionX, regionY, plane)
        if (singleRegionTerrainBatches != null) {
            val batches = singleRegionTerrainBatches!!
            println("Loaded terrain: color mesh=${batches.colorMesh != null}, textured batches=${batches.texturedBatches.size}")
            batches.texturedBatches.forEach { batch ->
                println("  Terrain texture batch: textureId=${batch.textureId}")
            }
        } else {
            println("Failed to load terrain meshes for region ($regionX, $regionY)")
        }

        // Load objects
        val regionId = (regionX shl 8) or regionY
        println("Loading region ($regionX, $regionY) = ID $regionId")
        println("  XTEA keys for region: ${MapXteas.getKeys(regionId)?.contentToString() ?: "NONE"}")

        // Debug: Check if archives exist
        val terrainArchive = cache.archiveId(world.gregs.voidps.cache.Index.MAPS, "m${regionX}_$regionY")
        val objectArchive = cache.archiveId(world.gregs.voidps.cache.Index.MAPS, "l${regionX}_$regionY")
        println("  Terrain archive (m${regionX}_$regionY): $terrainArchive")
        println("  Object archive (l${regionX}_$regionY): $objectArchive")

        if (objectArchive != -1) {
            val xtea = MapXteas.getKeys(regionId)
            val objectData = cache.data(world.gregs.voidps.cache.Index.MAPS, objectArchive, 0, xtea)
            println("  Object data size: ${objectData?.size ?: 0} bytes")
        }

        val regionData = RegionLoader.loadRegion(cache, regionX, regionY)
        if (regionData != null) {
            println("Region data loaded: ${regionData.objects.size} objects total")

            // Create instance manager for this region (smaller capacity than multi-region)
            singleRegionInstanceManager = InstanceManager(maxInstancesPerMesh = 5000)

            // Create object instances for this plane using LOCAL coordinates
            // The terrain mesh is at local coords (0,0) to (63,63) tiles
            // So objects must also be at local coordinates
            singleRegionObjects = createLocalObjectInstances(regionData, regionX, regionY, plane)

            println("Objects for plane $plane: ${singleRegionObjects.size}")

            // Debug: Count objects by groundContourType
            val contourTypeCounts = singleRegionObjects.groupBy { it.definition.groundContourType.toInt() }
                .mapValues { it.value.size }
            println("  groundContourType distribution: $contourTypeCounts")
            val alignedCount = singleRegionObjects.count { it.terrainAlignment.needsAlignment() }
            println("  Objects with terrain alignment: $alignedCount")

            // Register models with instance manager
            // Use combined models for multi-model objects (matching RS client behavior)
            var registeredCount = 0
            var multiModelCount = 0
            var debuggedMultiModel = false
            for (obj in singleRegionObjects) {
                // Get the combined model ID for this object's models
                val combinedId = ObjectRenderer.getCombinedModelId(obj.modelIds)
                if (singleRegionModels.contains(combinedId)) continue

                val isMultiModel = obj.modelIds.size > 1
                if (isMultiModel) {
                    multiModelCount++
                }

                // Load and combine all models for this object
                // Enable debug for first multi-model object
                val enableDebug = isMultiModel && !debuggedMultiModel
                val meshDataList = ModelLoader.createCombinedMeshDataList(cache, obj.modelIds, enableDebug)
                if (meshDataList != null && meshDataList.isNotEmpty()) {
                    // Debug: trace first multi-model object
                    if (enableDebug) {
                        debuggedMultiModel = true
                        println("=== MULTI-MODEL MESHDATA: ${obj.definition.name} (id=${obj.definition.id}) ===")
                        println("  Model IDs: ${obj.modelIds}")
                        println("  Combined ID: $combinedId")
                        println("  MeshData batches: ${meshDataList.size}")
                        for ((idx, mesh) in meshDataList.withIndex()) {
                            val faceCount = mesh.indices.size / 3
                            println("    Batch $idx: textureId=${mesh.textureId}, faces=$faceCount, verts=${mesh.positions.size/3}")
                            // Verify positions are not all zero
                            val nonZeroPositions = mesh.positions.count { it != 0f }
                            if (nonZeroPositions == 0 && mesh.positions.isNotEmpty()) {
                                println("      WARNING: All positions are zero!")
                            }
                        }
                    }
                    singleRegionInstanceManager!!.registerModel(combinedId, meshDataList)
                    singleRegionModels.add(combinedId)
                    registeredCount++
                }
            }
            println("Registered $registeredCount unique models (combined) for ${singleRegionObjects.size} object instances")
            println("Multi-model objects found: $multiModelCount")
        } else {
            println("Failed to load region data for ($regionX, $regionY)")
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
        singleRegionInstanceManager?.cleanup()
        singleRegionInstanceManager = null
        singleRegionObjects = emptyList()
        singleRegionModels.clear()
    }

    private fun getTerrainHeight(regionData: RegionData, worldX: Int, worldY: Int, plane: Int): Float {
        val localX = worldX and 63
        val localY = worldY and 63
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return 0f
        val tileInfo = RegionLoader.getTileInfo(regionData.tiles, localX, localY, plane)
        return tileInfo.height * 8f * CoordinateUtil.DEFAULT_SCALE
    }

    /**
     * Create object instances using LOCAL coordinates for single-region rendering.
     * The terrain mesh is at (0,0) to (63*tileSize, -63*tileSize) in local coords,
     * so objects must be positioned relative to (0,0), not world coords.
     *
     * The client places objects using the average height of the 4 corners at the object's
     * position (see SceneGraph.addObject in darkan-bot). For objects spanning multiple tiles,
     * it samples the center tiles of the object's bounding box.
     *
     * Height scale must match TerrainLoader.HEIGHT_SCALE = 32 * DEFAULT_SCALE
     * to ensure objects sit correctly on the terrain surface.
     */
    private fun createLocalObjectInstances(
        regionData: RegionData,
        regionX: Int,
        regionY: Int,
        plane: Int
    ): List<ObjectInstance> {
        val instances = mutableListOf<ObjectInstance>()
        val baseWorldX = regionX * 64
        val baseWorldY = regionY * 64
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Height scale must match TerrainLoader.HEIGHT_SCALE for objects to sit on terrain
        // Terrain: rawHeight * 32 / 128 = rawHeight * 0.25
        val heightScale = 32f * CoordinateUtil.DEFAULT_SCALE

        for (obj in regionData.objects) {
            if (obj.level != plane) continue

            val def = ObjectRenderer.getDefinition(obj.id) ?: continue
            val modelIds = ObjectRenderer.getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            // Convert world tile to local tile
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
            val h00 = RegionLoader.getTileInfo(regionData.tiles, x0, y0, plane).height
            val h10 = RegionLoader.getTileInfo(regionData.tiles, x1, y0, plane).height
            val h01 = RegionLoader.getTileInfo(regionData.tiles, x0, y1, plane).height
            val h11 = RegionLoader.getTileInfo(regionData.tiles, x1, y1, plane).height
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
            val terrainAlignment = TerrainAligner.calculateObjectTilt(
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
                    RegionLoader.getTileInfo(regionData.tiles, clampedX, clampedY, plane).height * heightScale
                }
            )

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
                mirrored = def.mirrored
            ))
        }

        return instances
    }

    override fun onCleanup() {
        cleanupSingleRegion()
        mapScene.cleanup()
        uniformBuffers.cleanup()
        uboColorShader.cleanup()
        uboTexturedShader.cleanup()
        instancedColorShader.cleanup()
        instancedTexturedShader.cleanup()
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
        imGui.cleanup()
    }
}
