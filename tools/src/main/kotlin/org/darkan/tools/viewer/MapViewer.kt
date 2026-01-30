package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImInt
import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionData
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.map.MapScene
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.render.UniformBuffers
import org.darkan.tools.engine.scene.SceneNode
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

    // Lighting
    private val ambientLight = Vector3f(0.5f, 0.5f, 0.5f)
    private val lightDirection = Vector3f(-0.5f, -1f, -0.5f).normalize()
    private val lightColor = Vector3f(0.7f, 0.7f, 0.7f)
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
    private var singleRegionNode: SceneNode? = null
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
        // Load UBO color shader (for terrain)
        val colorVert = loadShaderSource("/shaders/ubo_color.vert")
        val colorFrag = loadShaderSource("/shaders/ubo_color.frag")

        uboColorShader = ShaderProgram.createWithUniforms(
            colorVert, colorFrag,
            "model"
        )
        uniformBuffers.bindToShader(uboColorShader)

        // Load UBO textured shader (for terrain)
        val texturedVert = loadShaderSource("/shaders/ubo_textured.vert")
        val texturedFrag = loadShaderSource("/shaders/ubo_textured.frag")

        uboTexturedShader = ShaderProgram.createWithUniforms(
            texturedVert, texturedFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(uboTexturedShader)

        // Load instanced shaders (for objects)
        val instancedColorVert = loadShaderSource("/shaders/ubo_instanced.vert")
        instancedColorShader = ShaderProgram.create(instancedColorVert, colorFrag)
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

        // Render terrain (non-instanced)
        mapScene.renderTerrain(uboColorShader)

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

        // Render terrain with UBO shader
        singleRegionNode?.let { node ->
            uboColorShader.bind()
            uboColorShader.setUniform("model", Matrix4f().identity())
            node.mesh?.render()
        }

        // Render objects
        renderSingleRegionObjects()

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        }
    }

    private var debugObjectRenderFrame = 0
    private val tempModelMatrix = Matrix4f()

    private fun renderSingleRegionObjects() {
        val instanceManager = singleRegionInstanceManager ?: return

        instanceManager.prepareFrame()

        var addedCount = 0
        for (obj in singleRegionObjects) {
            for (modelId in obj.modelIds) {
                // Build transform matrix with position, rotation, and scale
                tempModelMatrix.identity()
                    .translate(obj.worldPosition)
                    .rotateY(obj.rotation)
                    .scale(obj.scale)

                if (instanceManager.addInstance(modelId, tempModelMatrix)) {
                    addedCount++
                }
            }
        }

        instanceManager.uploadAll()

        if (debugObjectRenderFrame < 5) {
            val stats = instanceManager.getStats()
            println("Frame $debugObjectRenderFrame: Added $addedCount instances, draw calls: ${stats.drawCalls}, registered models: ${stats.registeredModels}")
            debugObjectRenderFrame++
        }

        instanceManager.renderAll(instancedColorShader, instancedTexturedShader) { textureId ->
            TextureLoader.bindTexture(textureId)
        }
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

        // Load terrain mesh
        val mesh = TerrainLoader.loadRegionMesh(cache, regionX, regionY, plane)
        if (mesh != null) {
            singleRegionNode = SceneNode(mesh = mesh)
            scene.addNode("terrain", singleRegionNode!!)
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

            // Register models with instance manager
            var registeredCount = 0
            for (obj in singleRegionObjects) {
                for (modelId in obj.modelIds) {
                    if (singleRegionModels.contains(modelId)) continue
                    val meshDataList = ModelLoader.createMeshDataList(cache, modelId)
                    if (meshDataList != null && meshDataList.isNotEmpty()) {
                        singleRegionInstanceManager!!.registerModel(modelId, meshDataList)
                        singleRegionModels.add(modelId)
                        registeredCount++
                    }
                }
            }
            println("Registered $registeredCount unique models")
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
        singleRegionNode?.let {
            scene.removeNode("terrain")
            it.cleanup()
        }
        singleRegionNode = null
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

            // Get height at this local position
            val tileInfo = RegionLoader.getTileInfo(regionData.tiles, localTileX, localTileY, plane)
            val height = tileInfo.height * 8f * CoordinateUtil.DEFAULT_SCALE

            // Calculate local position (matching terrain mesh coordinates)
            // Terrain uses: x = localX * tileSize, z = -localY * tileSize
            val posX = localTileX * tileSize
            val posY = height + def.offsetY * CoordinateUtil.DEFAULT_SCALE
            val posZ = -localTileY * tileSize

            val position = Vector3f(posX, posY, posZ)

            // Calculate scale from definition
            val scaleX = def.resizeX / 128f
            val scaleY = def.resizeY / 128f
            val scaleZ = def.resizeZ / 128f

            instances.add(ObjectInstance(
                mapObject = obj,
                definition = def,
                modelIds = modelIds,
                worldPosition = position,
                rotation = ObjectRenderer.rotationToRadians(obj.rotation),
                scale = Vector3f(scaleX, scaleY, scaleZ)
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
