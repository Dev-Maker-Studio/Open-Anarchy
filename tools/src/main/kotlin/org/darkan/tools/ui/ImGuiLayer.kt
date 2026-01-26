package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiConfigFlags
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import org.darkan.tools.engine.window.Window

/**
 * ImGui integration layer for debug UI rendering.
 */
class ImGuiLayer(private val window: Window) {
    private val imGuiGlfw = ImGuiImplGlfw()
    private val imGuiGl3 = ImGuiImplGl3()

    fun init() {
        ImGui.createContext()

        val io = ImGui.getIO()
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable)

        io.setFontGlobalScale(1.0f)

        imGuiGlfw.init(window.handle, true)
        imGuiGl3.init("#version 330 core")
    }

    fun beginFrame() {
        imGuiGlfw.newFrame()
        ImGui.newFrame()
    }

    fun endFrame() {
        ImGui.render()
        imGuiGl3.renderDrawData(ImGui.getDrawData())
    }

    fun cleanup() {
        imGuiGl3.dispose()
        imGuiGlfw.dispose()
        ImGui.destroyContext()
    }

    fun wantCaptureKeyboard(): Boolean = ImGui.getIO().wantCaptureKeyboard

    fun wantCaptureMouse(): Boolean = ImGui.getIO().wantCaptureMouse
}
