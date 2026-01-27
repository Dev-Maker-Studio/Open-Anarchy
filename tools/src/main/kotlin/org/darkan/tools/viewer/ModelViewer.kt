package org.darkan.tools.viewer

import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.model.TexturedModel
import org.darkan.tools.cache.texture.TextureLoader
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
    private var texturedModelNodes: MutableList<SceneNode> = mutableListOf()
    private var currentTexturedModel: TexturedModel? = null
    private var gridNode: SceneNode? = null
    private var currentModelId = -1

    private var autoRotate = false
    private var rotationSpeed = 30f

    override fun onInit() {
        // Initialize texture loader
        TextureLoader.init(cache)

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
        for (id in listOf(27, 0, 1, 10, 100, 1000, 2000, 5000)) {
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
        // Remove existing model nodes
        modelNode?.let {
            scene.removeNode("model")
            it.cleanup()
        }
        texturedModelNodes.forEachIndexed { index, node ->
            scene.removeNode("model_tex_$index")
            node.cleanup()
        }
        texturedModelNodes.clear()
        currentTexturedModel = null

        val texturedModel = ModelLoader.loadTexturedModel(cache, modelId)
        if (texturedModel != null) {
            var totalVertices = 0

            // Add color batch (untextured faces)
            texturedModel.colorBatch?.let { mesh ->
                if (mesh.vertexCount > 0) {
                    totalVertices += mesh.vertexCount
                    modelNode = SceneNode(mesh = mesh)
                    scene.addNode("model", modelNode!!)
                }
            }

            // Add textured batches
            texturedModel.textureBatches.forEachIndexed { index, batch ->
                if (batch.mesh.vertexCount > 0) {
                    totalVertices += batch.mesh.vertexCount
                    val node = SceneNode(mesh = batch.mesh)
                    scene.addNode("model_tex_$index", node)
                    texturedModelNodes.add(node)
                }
            }

            if (totalVertices > 0) {
                println("Loaded model $modelId: $totalVertices vertices, ${texturedModel.textureBatches.size} texture batches")
                currentTexturedModel = texturedModel
                currentModelId = modelId
                return true
            }
        }

        println("Failed to load model $modelId")
        modelNode = null
        currentModelId = -1
        return false
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
        if (autoRotate) {
            val angle = rotationSpeed * deltaTime
            val radians = Math.toRadians(angle.toDouble()).toFloat()

            modelNode?.rotation?.rotateY(radians)
            texturedModelNodes.forEach { it.rotation.rotateY(radians) }
        }
    }

    override fun onRender() {
        val aspectRatio = getAspectRatio()
        val viewMatrix = scene.camera.getViewMatrix()
        val projectionMatrix = scene.camera.getProjectionMatrix(aspectRatio)

        // Render untextured elements (grid and color batch) with color shader
        colorShader.bind()
        colorShader.setUniform("view", viewMatrix)
        colorShader.setUniform("projection", projectionMatrix)
        colorShader.setUniform("ambientLight", scene.ambientLight)
        colorShader.setUniform("lightDirection", scene.lightDirection)
        colorShader.setUniform("lightColor", scene.lightColor)

        // Render grid
        gridNode?.let { node ->
            colorShader.setUniform("model", node.getModelMatrix())
            node.mesh?.render()
        }

        // Render untextured faces
        modelNode?.let { node ->
            colorShader.setUniform("model", node.getModelMatrix())
            node.mesh?.render()
        }

        colorShader.unbind()

        // Render textured batches with textured shader
        if (texturedModelNodes.isNotEmpty() && currentTexturedModel != null) {
            texturedShader.bind()
            texturedShader.setUniform("view", viewMatrix)
            texturedShader.setUniform("projection", projectionMatrix)
            texturedShader.setUniform("ambientLight", scene.ambientLight)
            texturedShader.setUniform("lightDirection", scene.lightDirection)
            texturedShader.setUniform("lightColor", scene.lightColor)
            texturedShader.setUniform("textureSampler", 0)

            currentTexturedModel!!.textureBatches.forEachIndexed { index, batch ->
                val node = texturedModelNodes.getOrNull(index) ?: return@forEachIndexed

                // Bind texture for this batch
                val textureId = TextureLoader.getTextureForMaterial(cache, batch.textureId)
                TextureLoader.bindTexture(textureId, 0)
                texturedShader.setUniform("useTexture", true)
                texturedShader.setUniform("model", node.getModelMatrix())
                node.mesh?.render()
            }

            TextureLoader.unbindTexture()
            texturedShader.unbind()
        }

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

        imgui.ImGui.setNextWindowPos(10f, imgui.ImGui.getIO().displaySizeY - 200f)
        imgui.ImGui.setNextWindowSize(280f, 0f)

        if (imgui.ImGui.begin("Model Info", imgui.flag.ImGuiWindowFlags.NoMove or imgui.flag.ImGuiWindowFlags.NoResize)) {
            imgui.ImGui.text("Model ID: $currentModelId")

            val def = ModelLoader.getCachedDefinition(currentModelId)
            if (def != null) {
                imgui.ImGui.text("Vertices: ${def.vertexCount}")
                imgui.ImGui.text("Faces: ${def.faceCount}")
            }

            // Texture info
            currentTexturedModel?.let { model ->
                imgui.ImGui.separator()
                imgui.ImGui.text("Textures: ${if (model.hasTextures) "Yes" else "No"}")
                if (model.textureBatches.isNotEmpty()) {
                    imgui.ImGui.text("Texture batches: ${model.textureBatches.size}")
                    model.textureBatches.take(5).forEach { batch ->
                        imgui.ImGui.bulletText("Material ${batch.textureId}: ${batch.mesh.vertexCount} verts")
                    }
                    if (model.textureBatches.size > 5) {
                        imgui.ImGui.text("  ... and ${model.textureBatches.size - 5} more")
                    }
                }
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
        TextureLoader.clearCache()
        imGui.cleanup()
    }
}
