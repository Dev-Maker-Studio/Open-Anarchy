package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiTreeNodeFlags
import imgui.flag.ImGuiWindowFlags
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.GpuTimerPool
import org.darkan.tools.engine.render.IndirectRenderer
import org.darkan.tools.engine.render.RenderMetrics

class DebugPanel {
    var visible = true
    var fps = 0
    var deltaTime = 0f
    var vertexCount = 0
    var drawCalls = 0
    var modelCount = 0
    var wireframeMode = false

    private var firstFrame = true

    fun render(camera: Camera, onWireframeToggle: () -> Unit) {
        render(camera, 800, 600, onWireframeToggle)
    }

    fun render(camera: Camera, windowWidth: Int, windowHeight: Int, onWireframeToggle: () -> Unit) {
        if (!visible) return

        val maxHeight = windowHeight * 0.8f
        ImGui.setNextWindowSizeConstraints(280f, 100f, 420f, maxHeight)

        if (firstFrame) {
            ImGui.setNextWindowPos(10f, 10f)
            firstFrame = false
        }

        val flags = ImGuiWindowFlags.None

        if (ImGui.begin("Debug Info (F1)", flags)) {
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

            renderPerformanceMetrics()

            renderAsyncLoadingProgress()

            ImGui.separator()

            if (ImGui.checkbox("Wireframe", wireframeMode)) {
                onWireframeToggle()
            }

            ImGui.separator()
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Controls:")
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "  WASD - Move")
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "  Space/Shift - Up/Down")
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "  Right Click - Look")
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "  Scroll - Zoom")
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "  ESC - Release Mouse")
        }
        ImGui.end()
    }

    private fun renderPerformanceMetrics() {
        if (!ImGui.collapsingHeader("Render Stats##perf", ImGuiTreeNodeFlags.DefaultOpen)) return

        val m = RenderMetrics

        ImGui.text("Draw Calls")
        ImGui.indent()
        ImGui.text("Terrain Opaque:   %d".format(m.terrainOpaqueDrawCalls))
        ImGui.text("Terrain Alpha:    %d".format(m.terrainAlphaDrawCalls))
        ImGui.text("Instanced (objs): %d".format(m.instancedDrawCalls))
        ImGui.text("Indirect (MDI):   %d".format(m.indirectDrawCalls))
        ImGui.text("  MDI commands:   %d".format(m.indirectCommands))
        ImGui.text("Static Batch:     %d".format(m.staticBatchDrawCalls))
        ImGui.text("Sky:              %d".format(m.skyDrawCalls))

        ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.8f, 1.0f, 1.0f)
        ImGui.text("Total:            %d".format(m.totalDrawCalls))
        ImGui.popStyleColor()
        ImGui.unindent()

        ImGui.spacing()

        ImGui.text("Geometry")
        ImGui.indent()
        ImGui.text("Triangles:  %,d".format(m.trianglesSubmitted))
        ImGui.text("Instances:  %,d".format(m.instanceCount))
        ImGui.text("Batch Meshes: %d".format(m.staticBatchMeshCount))
        val unregistered = m.droppedModelIds - m.droppedModelsUnavailable
        if (m.droppedInstances > 0) {
            ImGui.pushStyleColor(ImGuiCol.Text, if (unregistered > 0) 1.0f else 0.9f, if (unregistered > 0) 0.3f else 0.8f, 0.3f, 1.0f)
            ImGui.text(
                "DROPPED:    %,d instances (%d unregistered, %d no geometry)"
                    .format(m.droppedInstances, unregistered, m.droppedModelsUnavailable)
            )
            ImGui.popStyleColor()
        } else {
            ImGui.text("Dropped:    0")
        }
        ImGui.unindent()

        ImGui.spacing()

        renderCullingStats()

        ImGui.spacing()

        ImGui.text("CPU Frame Timing")
        ImGui.indent()
        ImGui.text("Render:     %.2f ms".format(m.renderTimeMs))
        ImGui.text("Transform:  %.2f ms".format(m.transformTimeMs))
        ImGui.text("Cull (CPU): %.2f ms".format(m.cullCpuTimeMs))
        ImGui.text("Sky:        %.2f ms".format(m.skyTimeMs))
        ImGui.text("Upload:     %.2f ms".format(m.uploadTimeMs))
        ImGui.text("Present:    %.2f ms".format(m.presentTimeMs))
        if (m.gpuSyncWaitMs > 0.0) {
            ImGui.text("GPU wait:   %.2f ms".format(m.gpuSyncWaitMs))
        } else {
            ImGuiText.coloured(0.6f, 0.6f, 0.6f, "(V-Sync wait is inside Render;")
            ImGuiText.coloured(0.6f, 0.6f, 0.6f, " -Ddarkan.gpuSync=true to split it out)")
        }
        ImGui.unindent()

        ImGui.spacing()

        renderCpuPhases()

        ImGui.spacing()

        renderGpuTiming()

        ImGui.spacing()

        if (ImGui.collapsingHeader("Load Timing##load")) {
            ImGui.indent()
            ImGui.text("Region Load:  %.1f ms".format(m.terrainLoadTimeMs))
            ImGui.text("Object Load:  %.1f ms".format(m.objectLoadTimeMs))
            ImGui.unindent()
        }

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

    private fun renderCullingStats() {
        val m = RenderMetrics
        ImGui.text("Culling")
        ImGui.indent()

        IndirectRenderer.requestCullStats()

        if (!m.cullActive) {
            ImGuiText.coloured(0.8f, 0.6f, 0.3f, "GPU cull: off (drawing everything)")
        } else if (m.cullVisibleItemsStale < 0) {
            ImGui.text("GPU cull: on (counters warming up)")
        } else {
            val total = m.cullItemsTotal
            val visible = m.cullVisibleItemsStale
            val culledPct = if (total > 0) (total - visible) * 100f / total else 0f
            ImGui.text("Objects:  %,d / %,d drawn".format(visible, total))
            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1.0f)
            ImGui.text("  culled: %.0f%%".format(culledPct))
            ImGui.popStyleColor()
            ImGui.text("Tris:     %,d visible".format(m.cullVisibleTrianglesStale))
            ImGuiText.coloured(0.6f, 0.6f, 0.6f, "  (GPU counters, %d frames stale)".format(m.cullStatsAgeFrames))
        }

        if (m.contextRegionsConsidered > 0) {
            ImGui.text("Regions:  %d / %d drawn".format(
                m.contextRegionsConsidered - m.contextRegionsCulled, m.contextRegionsConsidered
            ))
        }
        if (m.batchesConsidered > 0) {
            ImGui.text("Batches:  %d / %d drawn".format(
                m.batchesConsidered - m.batchesCulled, m.batchesConsidered
            ))
            ImGui.text("  regions: %d / %d tested per batch".format(
                m.batchRegionsConsidered - m.batchRegionsCulled, m.batchRegionsConsidered
            ))
        }
        ImGui.unindent()
    }

    private fun renderCpuPhases() {
        val m = RenderMetrics
        if (!ImGui.collapsingHeader("CPU Phases##cpuphase", ImGuiTreeNodeFlags.DefaultOpen)) return

        ImGui.indent()
        for (phase in RenderMetrics.CPU_PHASE_NAMES.indices) {
            ImGui.text("%-15s %6.3f ms".format(RenderMetrics.CPU_PHASE_NAMES[phase], m.cpuPhaseMs[phase]))
        }
        ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.8f, 1.0f, 1.0f)
        ImGui.text("%-15s %6.3f ms".format("CPU total", m.cpuPhaseMs.sum()))
        ImGui.popStyleColor()
        ImGui.unindent()
    }

    private fun renderGpuTiming() {
        val m = RenderMetrics
        if (!ImGui.collapsingHeader("GPU Timing##gputime", ImGuiTreeNodeFlags.DefaultOpen)) return

        ImGui.indent()
        if (!m.gpuTimingActive) {
            ImGuiText.coloured(0.8f, 0.6f, 0.3f, "timer queries unavailable")
            ImGui.unindent()
            return
        }

        for (pass in RenderMetrics.GPU_PASS_NAMES.indices) {
            ImGui.text("%-15s %6.3f ms".format(RenderMetrics.GPU_PASS_NAMES[pass], m.gpuPassMs[pass]))
        }
        ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.8f, 1.0f, 1.0f)
        ImGui.text("%-15s %6.3f ms".format("GPU total", m.gpuTotalMs))
        ImGui.popStyleColor()
        ImGuiText.coloured(0.6f, 0.6f, 0.6f, "(queries read %d frames late)".format(GpuTimerPool.DEPTH))
        ImGui.unindent()
    }

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

    private fun clampWindowToScreen(screenWidth: Float, screenHeight: Float) {
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()

        var newX = pos.x
        var newY = pos.y

        if (newX + size.x < 50f) newX = 50f - size.x
        if (newX > screenWidth - 50f) newX = screenWidth - 50f

        if (newY < 0f) newY = 0f
        if (newY > screenHeight - 50f) newY = screenHeight - 50f

        if (newX != pos.x || newY != pos.y) {
            ImGui.setWindowPos(newX, newY)
        }
    }
}
