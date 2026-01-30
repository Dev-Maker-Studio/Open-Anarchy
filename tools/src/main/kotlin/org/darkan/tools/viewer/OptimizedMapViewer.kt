package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.render.*
import org.darkan.tools.engine.scene.SpatialScene
import org.darkan.tools.ui.ImGuiLayer
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import world.gregs.voidps.cache.Cache

/**
 * Optimized map viewer using the high-performance rendering engine.
 *
 * Features:
 * - Frustum culling for visible region determination
 * - GPU instancing for repeated objects
 * - Texture atlasing for reduced bind calls
 * - Static batching for terrain
 * - LOD for distant objects
 * - Uniform Buffer Objects for shared data
 */
class OptimizedMapViewer(
    private val cache: Cache
) : Engine() {
    // Spatial scene with all optimizations
    private lateinit var spatialScene: SpatialScene

    // Uniform buffers
    private lateinit var uniformBuffers: UniformBuffers

    // Texture atlas
    private lateinit var textureAtlas: TextureAtlas

    // Shaders with UBO support
    private lateinit var uboColorShader: ShaderProgram
    private lateinit var uboTexturedShader: ShaderProgram
    private lateinit var uboInstancedShader: ShaderProgram
    private lateinit var uboInstancedTexturedShader: ShaderProgram

    // UI
    private lateinit var imguiLayer: ImGuiLayer
    private var showStatsPanel = true
    private var showDebugPanel = true
    private var wireframeMode = false

    // Camera
    private val camera = Camera()

    // Scene configuration
    private val ambientLight = Vector3f(0.4f, 0.4f, 0.4f)
    private val lightDirection = Vector3f(-0.5f, -1f, -0.5f).normalize()
    private val lightColor = Vector3f(0.8f, 0.8f, 0.8f)
    private val clearColor = Vector3f(0.2f, 0.3f, 0.4f)

    // Stats
    private var lastUpdateResult: org.darkan.tools.engine.scene.SceneUpdateResult? = null
    private var frameTimeHistory = FloatArray(120)
    private var frameTimeIndex = 0
    private var avgFrameTime = 0f

    override fun onInit() {
        // Initialize texture loader
        TextureLoader.init(cache)

        // Initialize scene
        spatialScene = SpatialScene()

        // Initialize uniform buffers
        uniformBuffers = UniformBuffers()
        uniformBuffers.init()

        // Initialize texture atlas
        textureAtlas = TextureAtlas(4096, 128)

        // Load UBO-based shaders
        loadUBOShaders()

        // Initialize ImGui
        imguiLayer = ImGuiLayer(window)
        imguiLayer.init()

        // Configure camera for map viewing
        camera.setPosition(0f, 200f, 0f)
        camera.pitch = -45f
        camera.yaw = 45f
        camera.moveSpeed = 50f
        camera.farPlane = 2000f

        // Enable multisampling
        glEnable(GL_MULTISAMPLE)
    }

    private fun loadUBOShaders() {
        // Load UBO color shader
        val uboColorVert = loadShaderSource("/shaders/ubo_color.vert")
        val uboColorFrag = loadShaderSource("/shaders/ubo_color.frag")

        uboColorShader = ShaderProgram.createWithUniforms(
            uboColorVert, uboColorFrag,
            "model"
        )
        uniformBuffers.bindToShader(uboColorShader)

        // Load UBO textured shader
        val uboTexturedVert = loadShaderSource("/shaders/ubo_textured.vert")
        val uboTexturedFrag = loadShaderSource("/shaders/ubo_textured.frag")

        uboTexturedShader = ShaderProgram.createWithUniforms(
            uboTexturedVert, uboTexturedFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(uboTexturedShader)

        // Load UBO instanced shader
        val uboInstancedVert = loadShaderSource("/shaders/ubo_instanced.vert")

        uboInstancedShader = ShaderProgram.create(uboInstancedVert, uboColorFrag)
        uniformBuffers.bindToShader(uboInstancedShader)

        // Load UBO instanced textured shader
        val uboInstancedTexturedVert = loadShaderSource("/shaders/ubo_instanced_textured.vert")

        uboInstancedTexturedShader = ShaderProgram.createWithUniforms(
            uboInstancedTexturedVert, uboTexturedFrag,
            "textureSampler"
        )
        uniformBuffers.bindToShader(uboInstancedTexturedShader)
    }

    override fun onInput() {
        // Camera movement (use isKeyDown for continuous movement)
        if (input.keyboard.isKeyDown(GLFW_KEY_W)) camera.moveForward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_S)) camera.moveBackward(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_A)) camera.moveLeft(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_D)) camera.moveRight(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_SPACE)) camera.moveUp(deltaTime)
        if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_SHIFT)) camera.moveDown(deltaTime)

        // Mouse look
        if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_RIGHT)) {
            camera.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
        }

        // Scroll zoom
        val scroll = input.mouse.scrollY.toFloat()
        if (scroll != 0f) {
            camera.zoom(scroll)
        }

        // Speed adjustment
        if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_CONTROL)) {
            camera.moveSpeed = 200f
        } else {
            camera.moveSpeed = 50f
        }

        // Toggle keys (use isKeyPressed for single-press detection)
        if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) showStatsPanel = !showStatsPanel
        if (input.keyboard.isKeyPressed(GLFW_KEY_F2)) showDebugPanel = !showDebugPanel
        if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) wireframeMode = !wireframeMode
    }

    override fun onUpdate(deltaTime: Float) {
        // Update scene visibility and LOD
        lastUpdateResult = spatialScene.update(camera, getAspectRatio())

        // Prepare instanced rendering
        spatialScene.prepareInstancedRendering()

        // Track frame time
        frameTimeHistory[frameTimeIndex] = deltaTime * 1000f
        frameTimeIndex = (frameTimeIndex + 1) % frameTimeHistory.size
        avgFrameTime = frameTimeHistory.average().toFloat()
    }

    override fun onRender() {
        // Clear with ambient background color
        glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Update shared uniforms once
        uniformBuffers.updateCamera(
            camera.getViewMatrix(),
            camera.getProjectionMatrix(getAspectRatio()),
            camera.position
        )
        uniformBuffers.updateLighting(ambientLight, lightDirection, lightColor)

        if (wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        }

        // Render scene
        spatialScene.render(
            colorShader = uboColorShader,
            texturedShader = uboTexturedShader,
            uniformBuffers = uniformBuffers
        ) { textureId ->
            // Bind texture
            val glTextureId = TextureLoader.getTextureForMaterial(cache, textureId)
            TextureLoader.bindTexture(glTextureId)
        }

        if (wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        }

        // Render UI
        renderUI()
    }

    private fun renderUI() {
        imguiLayer.beginFrame()

        if (showStatsPanel) {
            renderStatsPanel()
        }

        if (showDebugPanel) {
            renderDebugPanel()
        }

        imguiLayer.endFrame()
    }

    private fun renderStatsPanel() {
        ImGui.setNextWindowPos(10f, 10f)
        ImGui.setNextWindowSize(300f, 0f)

        val flags = ImGuiWindowFlags.NoMove or
                   ImGuiWindowFlags.NoResize or
                   ImGuiWindowFlags.AlwaysAutoResize

        if (ImGui.begin("Performance Stats", flags)) {
            // FPS
            val fps = if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
            ImGui.text("FPS: %.1f (%.2f ms)".format(fps, avgFrameTime))

            // Frame time graph
            ImGui.plotLines("", frameTimeHistory, frameTimeHistory.size, frameTimeIndex,
                "Frame Time", 0f, 33.3f, 280f, 50f)

            ImGui.separator()

            // Scene stats
            val stats = spatialScene.getStats()
            ImGui.text("Objects: %d / %d visible".format(stats.visibleObjects, stats.totalObjects))
            ImGui.text("Chunks: %d / %d visible".format(stats.visibleChunks, stats.totalChunks))
            ImGui.text("Draw Calls: %d".format(stats.drawCalls))
            ImGui.text("Instances: %d".format(stats.instanceCount))

            ImGui.separator()

            // Culling stats
            ImGui.text("Object Cull: %.1f%%".format(stats.objectCullRatio * 100))
            ImGui.text("Chunk Cull: %.1f%%".format(stats.chunkCullRatio * 100))
            ImGui.text("Cull Time: %.3f ms".format(stats.cullTimeMs))
            ImGui.text("LOD Time: %.3f ms".format(stats.lodTimeMs))

            ImGui.separator()

            // Rendering stats
            ImGui.text("Static Batches: %d".format(stats.staticBatches))
            ImGui.text("Instanced Models: %d".format(stats.instancedModels))
        }
        ImGui.end()
    }

    private fun renderDebugPanel() {
        ImGui.setNextWindowPos(window.width - 310f, 10f)
        ImGui.setNextWindowSize(300f, 0f)

        val flags = ImGuiWindowFlags.NoMove or
                   ImGuiWindowFlags.NoResize or
                   ImGuiWindowFlags.AlwaysAutoResize

        if (ImGui.begin("Debug", flags)) {
            // Camera info
            ImGui.text("Camera Position:")
            ImGui.text("  X: %.1f".format(camera.position.x))
            ImGui.text("  Y: %.1f".format(camera.position.y))
            ImGui.text("  Z: %.1f".format(camera.position.z))

            ImGui.separator()

            ImGui.text("Yaw: %.1f  Pitch: %.1f".format(camera.yaw, camera.pitch))
            ImGui.text("FOV: %.1f".format(camera.fov))

            ImGui.separator()

            // Render mode
            ImGui.text("Wireframe: %s".format(if (wireframeMode) "ON" else "OFF"))

            ImGui.separator()

            // Controls
            ImGui.textWrapped("Controls:")
            ImGui.text("  WASD - Move")
            ImGui.text("  Space/Shift - Up/Down")
            ImGui.text("  Right Click - Look")
            ImGui.text("  Scroll - Zoom")
            ImGui.text("  Ctrl - Fast move")
            ImGui.text("  F1 - Stats panel")
            ImGui.text("  F2 - Debug panel")
            ImGui.text("  F3 - Wireframe")
        }
        ImGui.end()
    }

    override fun onCleanup() {
        spatialScene.cleanup()
        uniformBuffers.cleanup()
        textureAtlas.cleanup()

        uboColorShader.cleanup()
        uboTexturedShader.cleanup()
        uboInstancedShader.cleanup()
        uboInstancedTexturedShader.cleanup()

        imguiLayer.cleanup()
        TextureLoader.clearCache()
    }

    /**
     * Get the spatial scene for external manipulation.
     */
    fun getSpatialScene(): SpatialScene = spatialScene

    /**
     * Get the instance manager for registering models.
     */
    fun getInstanceManager(): InstanceManager = spatialScene.instanceManager

    /**
     * Get the texture atlas for texture management.
     */
    fun getTextureAtlas(): TextureAtlas = textureAtlas

    companion object {
        /**
         * Create and run the optimized map viewer.
         */
        fun run(cache: Cache) {
            val viewer = OptimizedMapViewer(cache)
            viewer.run()
        }
    }
}
