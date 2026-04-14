package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiTreeNodeFlags
import imgui.flag.ImGuiWindowFlags
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.RenderMetrics

/**
 * Debug information panel showing FPS, camera position, render stats,
 * and comprehensive performance metrics from [RenderMetrics].
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

        // Set size constraints: min width 280, max height 80% of screen
        val maxHeight = windowHeight * 0.8f
        ImGui.setNextWindowSizeConstraints(280f, 100f, 420f, maxHeight)

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

            // Performance Metrics section (collapsible, open by default)
            renderPerformanceMetrics()

            // Show async loading progress if regions are loading
            renderAsyncLoadingProgress()

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

    /**
     * Render the comprehensive performance metrics section.
     * Reads all data from [RenderMetrics].
     */
    private fun renderPerformanceMetrics() {
        if (!ImGui.collapsingHeader("Render Stats##perf", ImGuiTreeNodeFlags.DefaultOpen)) return

        val m = RenderMetrics

        // --- Draw Calls ---
        ImGui.text("Draw Calls")
        ImGui.indent()
        ImGui.text("Terrain Opaque:   %d".format(m.terrainOpaqueDrawCalls))
        ImGui.text("Terrain Alpha:    %d".format(m.terrainAlphaDrawCalls))
        ImGui.text("Instanced (objs): %d".format(m.instancedDrawCalls))
        ImGui.text("Indirect (MDI):   %d".format(m.indirectDrawCalls))
        ImGui.text("Static Batch:     %d".format(m.staticBatchDrawCalls))

        // Total in accent color
        ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.8f, 1.0f, 1.0f)
        ImGui.text("Total:            %d".format(m.totalDrawCalls))
        ImGui.popStyleColor()
        ImGui.unindent()

        ImGui.spacing()

        // --- Geometry ---
        ImGui.text("Geometry")
        ImGui.indent()
        ImGui.text("Triangles:  %,d".format(m.trianglesSubmitted))
        ImGui.text("Instances:  %,d".format(m.instanceCount))
        ImGui.text("Batch Meshes: %d".format(m.staticBatchMeshCount))
        ImGui.unindent()

        ImGui.spacing()

        // --- Frame Timing ---
        ImGui.text("Frame Timing")
        ImGui.indent()
        ImGui.text("Render:     %.2f ms".format(m.renderTimeMs))
        ImGui.text("Transform:  %.2f ms".format(m.transformTimeMs))
        ImGui.text("Upload:     %.2f ms".format(m.uploadTimeMs))
        ImGui.unindent()

        ImGui.spacing()

        // --- Load Timing (collapsible) ---
        if (ImGui.collapsingHeader("Load Timing##load")) {
            ImGui.indent()
            ImGui.text("Region Load:  %.1f ms".format(m.terrainLoadTimeMs))
            ImGui.text("Object Load:  %.1f ms".format(m.objectLoadTimeMs))
            ImGui.unindent()
        }

        // --- Atlas Stats (collapsible) ---
        if (ImGui.collapsingHeader("Atlas Stats##atlas")) {
            ImGui.indent()
            ImGui.text("Terrain Atlas")
            ImGui.text("  Textures: %d".format(m.terrainAtlasTextures))
            ImGui.text("  Pages:    %d".format(m.terrainAtlasPages))
            ImGui.text("  Usage:    %.0f%%".format(m.terrainAtlasUtilization))

            ImGui.spacing()

            ImGui.text("Object Atlas")
            ImGui.text("  Textures: %d".format(m.objectAtlasTextures))
            ImGui.text("  Pages:    %d".format(m.objectAtlasPages))
            ImGui.text("  Usage:    %.0f%%".format(m.objectAtlasUtilization))
            ImGui.unindent()
        }
    }

    /**
     * Show async loading progress when regions are loading in background.
     * Reads from [RenderMetrics] snapshot values.
     */
    private fun renderAsyncLoadingProgress() {
        val m = RenderMetrics
        if (m.regionsLoading <= 0 && m.pendingGpuTasks <= 0) return

        ImGui.spacing()
        ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.8f, 0.3f, 1f)
        ImGui.text("Loading...")
        ImGui.popStyleColor()

        ImGui.indent()
        ImGui.text("Regions: %d ready, %d loading".format(m.regionsReady, m.regionsLoading))
        if (m.pendingGpuTasks > 0) {
            ImGui.text("GPU Tasks: %d pending".format(m.pendingGpuTasks))
        }
        if (m.gpuUploadTasksProcessed > 0) {
            ImGui.text("GPU Drain: %d tasks, %.1f ms".format(m.gpuUploadTasksProcessed, m.gpuUploadDrainTimeMs))
        }
        ImGui.unindent()
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
