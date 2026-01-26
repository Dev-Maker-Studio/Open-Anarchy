package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import org.darkan.tools.engine.render.Camera

/**
 * Debug information panel showing FPS, camera position, and render stats.
 */
class DebugPanel {
    var visible = true
    var fps = 0
    var deltaTime = 0f
    var vertexCount = 0
    var drawCalls = 0
    var modelCount = 0
    var wireframeMode = false

    fun render(camera: Camera, onWireframeToggle: () -> Unit) {
        if (!visible) return

        ImGui.setNextWindowPos(10f, 10f)
        ImGui.setNextWindowSize(250f, 0f)

        val flags = ImGuiWindowFlags.NoMove or
                    ImGuiWindowFlags.NoResize or
                    ImGuiWindowFlags.AlwaysAutoResize

        if (ImGui.begin("Debug Info", flags)) {
            ImGui.text("FPS: $fps")
            ImGui.text("Delta: %.3f ms".format(deltaTime * 1000))
            ImGui.separator()

            ImGui.text("Camera Position:")
            ImGui.text("  X: %.2f".format(camera.position.x))
            ImGui.text("  Y: %.2f".format(camera.position.y))
            ImGui.text("  Z: %.2f".format(camera.position.z))
            ImGui.text("Yaw: %.1f  Pitch: %.1f".format(camera.yaw, camera.pitch))
            ImGui.separator()

            ImGui.text("Render Stats:")
            ImGui.text("  Vertices: $vertexCount")
            ImGui.text("  Draw Calls: $drawCalls")
            ImGui.text("  Models: $modelCount")
            ImGui.separator()

            if (ImGui.checkbox("Wireframe", wireframeMode)) {
                onWireframeToggle()
            }

            ImGui.separator()
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Controls:")
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  WASD - Move")
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  Space/Shift - Up/Down")
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  Right Click - Look")
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  Scroll - Zoom")
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  ESC - Release Mouse")
        }
        ImGui.end()
    }

    fun toggleVisibility() {
        visible = !visible
    }
}
