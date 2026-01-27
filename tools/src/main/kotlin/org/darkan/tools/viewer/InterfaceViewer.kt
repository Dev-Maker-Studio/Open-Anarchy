package org.darkan.tools.viewer

import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.ui.DebugPanel
import org.darkan.tools.ui.ImGuiLayer
import imgui.type.ImInt
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceDefinitionFull
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoderFull

/**
 * Interface/UI component viewer application.
 */
class InterfaceViewer(
    private val cache: Cache
) : Engine(WindowConfig(title = "Darkan Interface Viewer", width = 1280, height = 720)) {

    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel

    private var interfaceDefs: Array<InterfaceDefinitionFull>? = null
    private var currentInterfaceId = 0
    private var selectedComponent = 0
    private val interfaceIdInput = ImInt(0)

    override fun onInit() {
        imGui = ImGuiLayer(window)
        imGui.init()

        debugPanel = DebugPanel()
        debugPanel.visible = false

        // Setup orthographic camera for 2D UI
        scene.camera.orthographic = true
        scene.camera.orthoSize = 720f

        // Load interface definitions
        loadInterfaceDefinitions()
    }

    private fun loadInterfaceDefinitions() {
        try {
            interfaceDefs = InterfaceDecoderFull().load(cache)
        } catch (e: Exception) {
            System.err.println("Failed to load interface definitions: ${e.message}")
        }
    }

    override fun onInput() {
        if (imGui.wantCaptureKeyboard() || imGui.wantCaptureMouse()) {
            return
        }

        // Interface navigation
        if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT)) {
            if (currentInterfaceId > 0) {
                currentInterfaceId--
                selectedComponent = 0
            }
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT)) {
            currentInterfaceId++
            selectedComponent = 0
        }

        if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) {
            debugPanel.toggleVisibility()
        }
    }

    override fun onRender() {
        // Clear with a dark background
        glClearColor(0.15f, 0.15f, 0.15f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Disable depth test for 2D rendering
        renderSystem.disableDepthTest()

        // Draw interface preview area
        drawInterfacePreview()

        renderSystem.enableDepthTest()

        // ImGui UI
        imGui.beginFrame()

        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.render(scene.camera) {}

        renderInterfaceBrowser()
        renderComponentDetails()

        imGui.endFrame()
    }

    private fun drawInterfacePreview() {
        // Placeholder - would render actual interface components
        // For now just draws a bordered preview area
    }

    private fun renderInterfaceBrowser() {
        imgui.ImGui.setNextWindowPos(10f, 10f)
        imgui.ImGui.setNextWindowSize(300f, 400f)

        if (imgui.ImGui.begin("Interface Browser", imgui.flag.ImGuiWindowFlags.NoMove or imgui.flag.ImGuiWindowFlags.NoResize)) {
            imgui.ImGui.text("Interface ID:")

            interfaceIdInput.set(currentInterfaceId)
            imgui.ImGui.setNextItemWidth(150f)
            if (imgui.ImGui.inputInt("##interfaceId", interfaceIdInput)) {
                interfaceIdInput.set(interfaceIdInput.get().coerceAtLeast(0))
                currentInterfaceId = interfaceIdInput.get()
                selectedComponent = 0
            }

            imgui.ImGui.separator()

            val def = interfaceDefs?.getOrNull(currentInterfaceId)
            if (def != null) {
                imgui.ImGui.text("Components: ${def.components?.size ?: 0}")

                imgui.ImGui.separator()

                imgui.ImGui.beginChild("ComponentList", 0f, 280f, true)
                def.components?.forEachIndexed { index, component ->
                    val label = "[$index] ${getComponentTypeString(component.type)}"
                    if (imgui.ImGui.selectable(label, selectedComponent == index)) {
                        selectedComponent = index
                    }
                }
                imgui.ImGui.endChild()
            } else {
                imgui.ImGui.text("No interface loaded")
            }

            imgui.ImGui.separator()
            imgui.ImGui.text("Navigation: < > Arrow Keys")
        }
        imgui.ImGui.end()
    }

    private fun renderComponentDetails() {
        val def = interfaceDefs?.getOrNull(currentInterfaceId) ?: return
        val component = def.components?.getOrNull(selectedComponent) ?: return

        imgui.ImGui.setNextWindowPos(imgui.ImGui.getIO().displaySizeX - 310f, 10f)
        imgui.ImGui.setNextWindowSize(300f, 400f)

        if (imgui.ImGui.begin("Component Details", imgui.flag.ImGuiWindowFlags.NoMove or imgui.flag.ImGuiWindowFlags.NoResize)) {
            imgui.ImGui.text("ID: ${component.id}")
            imgui.ImGui.text("Type: ${getComponentTypeString(component.type)}")
            imgui.ImGui.separator()

            imgui.ImGui.text("Position: (${component.basePositionX}, ${component.basePositionY})")
            imgui.ImGui.text("Size: ${component.baseWidth} x ${component.baseHeight}")
            imgui.ImGui.separator()

            if (component.text.isNotEmpty()) {
                imgui.ImGui.text("Text: ${component.text}")
            }

            if (component.defaultImage != -1) {
                imgui.ImGui.text("Sprite ID: ${component.defaultImage}")
            }

            if (component.defaultMediaId != 0) {
                imgui.ImGui.text("Model ID: ${component.defaultMediaId}")
            }

            imgui.ImGui.separator()
            imgui.ImGui.text("Hidden: ${component.hidden}")
            imgui.ImGui.text("Parent: ${component.parent}")
        }
        imgui.ImGui.end()
    }

    private fun getComponentTypeString(type: Int): String {
        return when (type) {
            0 -> "Container"
            3 -> "Rectangle"
            4 -> "Text"
            5 -> "Sprite"
            6 -> "Model"
            9 -> "Line"
            else -> "Type $type"
        }
    }

    override fun onCleanup() {
        imGui.cleanup()
    }
}
