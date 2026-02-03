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

    // Track if this is the first frame (for initial positioning)
    private var firstFrame = true

    fun render(camera: Camera, onWireframeToggle: () -> Unit) {
        render(camera, 800, 600, onWireframeToggle)
    }

    fun render(camera: Camera, windowWidth: Int, windowHeight: Int, onWireframeToggle: () -> Unit) {
        if (!visible) return

        // Set size constraints: min width 250, max height 80% of screen
        val maxHeight = windowHeight * 0.8f
        ImGui.setNextWindowSizeConstraints(250f, 100f, 400f, maxHeight)

        // Only set initial position on first frame
        if (firstFrame) {
            ImGui.setNextWindowPos(10f, 10f)
            firstFrame = false
        }

        // Allow moving and resizing, scrollbar appears automatically when needed
        val flags = ImGuiWindowFlags.None

        if (ImGui.begin("Debug Info (F1)", flags)) {
            // Clamp window position to stay on screen
            clampWindowToScreen(windowWidth.toFloat(), windowHeight.toFloat())
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

    /**
     * Clamp the current window position to stay within screen bounds.
     * Call this inside ImGui.begin() block.
     */
    private fun clampWindowToScreen(screenWidth: Float, screenHeight: Float) {
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()

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
}
