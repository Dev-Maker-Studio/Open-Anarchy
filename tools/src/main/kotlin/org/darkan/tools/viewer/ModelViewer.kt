package org.darkan.tools.viewer

import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.scene.SceneNode
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.ui.AssetBrowser
import org.darkan.tools.ui.DebugPanel
import org.darkan.tools.ui.ImGuiLayer
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.*
import world.gregs.voidps.cache.Cache

/**
 * Interactive model viewer application.
 */
class ModelViewer(
    private val cache: Cache
) : Engine(WindowConfig(title = "Darkan Model Viewer", width = 1280, height = 720)) {

    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel
    private lateinit var assetBrowser: AssetBrowser

    private var modelNode: SceneNode? = null
    private var gridNode: SceneNode? = null
    private var currentModelId = -1

    private var autoRotate = false
    private var rotationSpeed = 30f

    override fun onInit() {
        imGui = ImGuiLayer(window)
        imGui.init()

        debugPanel = DebugPanel()
        assetBrowser = AssetBrowser(cache)
        assetBrowser.init()

        // Setup callbacks
        assetBrowser.setOnModelSelected { modelId ->
            loadModel(modelId)
        }

        assetBrowser.setOnItemSelected { id, def ->
            if (def.modelId > 0) {
                loadModel(def.modelId)
            }
        }

        assetBrowser.setOnNpcSelected { id, def ->
            def.modelIds?.firstOrNull()?.let { loadModel(it) }
        }

        assetBrowser.setOnObjectSelected { id, def ->
            def.modelIds?.firstOrNull()?.firstOrNull()?.let { loadModel(it) }
        }

        // Setup camera
        scene.camera.setPosition(0f, 2f, 5f)
        scene.camera.moveSpeed = 3f

        // Add grid
        gridNode = SceneNode(mesh = Mesh.createGrid(20f, 20))
        scene.addNode("grid", gridNode!!)

        // Load default model - try several until we find a valid one
        var loaded = false
        for (id in listOf(0, 1, 10, 100, 1000, 2000, 5000)) {
            if (loadModel(id)) {
                println("Successfully loaded model $id")
                loaded = true
                break
            }
        }
        if (!loaded) {
            println("Warning: Could not load any default model")
        }
    }

    fun loadModel(modelId: Int): Boolean {
        // Remove existing model
        modelNode?.let {
            scene.removeNode("model")
            it.cleanup()
        }

        val mesh = ModelLoader.loadMesh(cache, modelId)
        if (mesh != null && mesh.vertexCount > 0) {
            println("Loaded model $modelId: ${mesh.vertexCount} vertices")
            modelNode = SceneNode(mesh = mesh)
            scene.addNode("model", modelNode!!)
            currentModelId = modelId
            return true
        } else {
            println("Failed to load model $modelId (mesh is ${if (mesh == null) "null" else "empty"})")
            modelNode = null
            currentModelId = -1
            return false
        }
    }

    override fun onInput() {
        // Don't process game input if ImGui wants it
        if (imGui.wantCaptureKeyboard() || imGui.wantCaptureMouse()) {
            return
        }

        val camera = scene.camera

        // Camera movement
        if (input.keyboard.isKeyDown(GLFW_KEY_W)) camera.moveForward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_S)) camera.moveBackward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_A)) camera.moveLeft(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_D)) camera.moveRight(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_SPACE)) camera.moveUp(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_SHIFT)) camera.moveDown(deltaTime)

        // Camera rotation (when mouse captured)
        if (input.mouseCaptured) {
            camera.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
        }

        // Scroll zoom
        if (input.mouse.scrollY != 0.0) {
            camera.zoom(input.mouse.scrollY.toFloat())
        }

        // Keyboard shortcuts
        if (input.keyboard.isKeyPressed(GLFW_KEY_R)) {
            autoRotate = !autoRotate
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) {
            debugPanel.toggleVisibility()
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F2)) {
            assetBrowser.toggleVisibility()
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) {
            renderSystem.toggleWireframe()
        }

        // Model navigation
        if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT) || input.keyboard.isKeyPressed(GLFW_KEY_COMMA)) {
            if (currentModelId > 0) {
                loadModel(currentModelId - 1)
            }
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT) || input.keyboard.isKeyPressed(GLFW_KEY_PERIOD)) {
            val nextId = if (currentModelId < 0) 0 else currentModelId + 1
            loadModel(nextId)
        }
    }

    override fun onUpdate(deltaTime: Float) {
        // Auto-rotate model
        if (autoRotate && modelNode != null) {
            val current = modelNode!!.rotation
            val angle = rotationSpeed * deltaTime
            current.rotateY(Math.toRadians(angle.toDouble()).toFloat())
        }
    }

    override fun onRender() {
        // Render scene
        scene.render(colorShader, getAspectRatio())

        // Render ImGui
        imGui.beginFrame()

        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.modelCount = if (currentModelId >= 0) 1 else 0
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera) {
            renderSystem.toggleWireframe()
        }

        assetBrowser.render()

        // Model info panel
        renderModelInfo()

        imGui.endFrame()
    }

    private fun renderModelInfo() {
        if (currentModelId < 0) return

        imgui.ImGui.setNextWindowPos(10f, imgui.ImGui.getIO().displaySizeY - 150f)
        imgui.ImGui.setNextWindowSize(250f, 0f)

        if (imgui.ImGui.begin("Model Info", imgui.flag.ImGuiWindowFlags.NoMove or imgui.flag.ImGuiWindowFlags.NoResize)) {
            imgui.ImGui.text("Model ID: $currentModelId")

            val def = ModelLoader.getCachedDefinition(currentModelId)
            if (def != null) {
                imgui.ImGui.text("Vertices: ${def.vertexCount}")
                imgui.ImGui.text("Faces: ${def.faceCount}")
            }

            imgui.ImGui.separator()
            if (imgui.ImGui.checkbox("Auto-Rotate (R)", autoRotate)) {
                autoRotate = !autoRotate
            }

            imgui.ImGui.separator()
            imgui.ImGui.text("Navigation: < > or , .")
        }
        imgui.ImGui.end()
    }

    override fun onCleanup() {
        ModelLoader.clearCache()
        imGui.cleanup()
    }
}
