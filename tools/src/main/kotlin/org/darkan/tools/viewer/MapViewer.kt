package org.darkan.tools.viewer

import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.scene.SceneNode
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.ui.DebugPanel
import org.darkan.tools.ui.ImGuiLayer
import org.darkan.tools.util.CoordinateUtil
import org.lwjgl.glfw.GLFW.*
import world.gregs.voidps.cache.Cache

/**
 * Map/terrain viewer application.
 */
class MapViewer(
    private val cache: Cache
) : Engine(WindowConfig(title = "Darkan Map Viewer", width = 1280, height = 720)) {

    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel

    private var terrainNode: SceneNode? = null
    private var currentRegionX = 50
    private var currentRegionY = 50
    private var currentPlane = 0

    override fun onInit() {
        imGui = ImGuiLayer(window)
        imGui.init()

        debugPanel = DebugPanel()

        // Setup camera for overhead view
        scene.camera.setPosition(0f, 50f, 50f)
        scene.camera.pitch = -45f
        scene.camera.yaw = -90f
        scene.camera.moveSpeed = 20f
        scene.camera.farPlane = 2000f

        // Load default region
        loadRegion(currentRegionX, currentRegionY, currentPlane)
    }

    fun loadRegion(regionX: Int, regionY: Int, plane: Int = 0) {
        // Remove existing terrain
        terrainNode?.let {
            scene.removeNode("terrain")
            it.cleanup()
        }

        val mesh = TerrainLoader.loadRegionMesh(cache, regionX, regionY, plane)
        if (mesh != null) {
            terrainNode = SceneNode(mesh = mesh)
            scene.addNode("terrain", terrainNode!!)
            currentRegionX = regionX
            currentRegionY = regionY
            currentPlane = plane

            // Center camera on region
            val center = CoordinateUtil.regionCenter(regionX, regionY)
            scene.camera.setPosition(center.x, 30f, center.z + 30f)
        } else {
            terrainNode = null
        }
    }

    override fun onInput() {
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

        // Camera rotation
        if (input.mouseCaptured) {
            camera.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
        }

        // Scroll zoom
        if (input.mouse.scrollY != 0.0) {
            camera.zoom(input.mouse.scrollY.toFloat())
        }

        // Keyboard shortcuts
        if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) {
            debugPanel.toggleVisibility()
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) {
            renderSystem.toggleWireframe()
        }

        // Region navigation
        if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT)) {
            loadRegion(currentRegionX - 1, currentRegionY, currentPlane)
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT)) {
            loadRegion(currentRegionX + 1, currentRegionY, currentPlane)
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_UP)) {
            loadRegion(currentRegionX, currentRegionY + 1, currentPlane)
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_DOWN)) {
            loadRegion(currentRegionX, currentRegionY - 1, currentPlane)
        }

        // Plane navigation
        if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_UP)) {
            if (currentPlane < 3) loadRegion(currentRegionX, currentRegionY, currentPlane + 1)
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_DOWN)) {
            if (currentPlane > 0) loadRegion(currentRegionX, currentRegionY, currentPlane - 1)
        }
    }

    override fun onRender() {
        scene.render(colorShader, getAspectRatio())

        imGui.beginFrame()

        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera) {
            renderSystem.toggleWireframe()
        }

        renderMapInfo()

        imGui.endFrame()
    }

    private fun renderMapInfo() {
        imgui.ImGui.setNextWindowPos(imgui.ImGui.getIO().displaySizeX - 260f, 10f)
        imgui.ImGui.setNextWindowSize(250f, 0f)

        if (imgui.ImGui.begin("Map Info", imgui.flag.ImGuiWindowFlags.NoMove or imgui.flag.ImGuiWindowFlags.NoResize)) {
            imgui.ImGui.text("Region: ($currentRegionX, $currentRegionY)")
            imgui.ImGui.text("Plane: $currentPlane")

            imgui.ImGui.separator()
            imgui.ImGui.text("Navigation:")
            imgui.ImGui.text("  Arrow Keys - Move Region")
            imgui.ImGui.text("  PgUp/PgDn - Change Plane")

            imgui.ImGui.separator()
            imgui.ImGui.text("Go to Region:")

            val regionX = intArrayOf(currentRegionX)
            val regionY = intArrayOf(currentRegionY)

            imgui.ImGui.setNextItemWidth(80f)
            if (imgui.ImGui.inputInt("X##regionX", regionX)) {
                regionX[0] = regionX[0].coerceIn(0, 255)
            }
            imgui.ImGui.sameLine()
            imgui.ImGui.setNextItemWidth(80f)
            if (imgui.ImGui.inputInt("Y##regionY", regionY)) {
                regionY[0] = regionY[0].coerceIn(0, 255)
            }

            if (imgui.ImGui.button("Load")) {
                loadRegion(regionX[0], regionY[0], currentPlane)
            }
        }
        imgui.ImGui.end()
    }

    override fun onCleanup() {
        TerrainLoader.clearCache()
        imGui.cleanup()
    }
}
