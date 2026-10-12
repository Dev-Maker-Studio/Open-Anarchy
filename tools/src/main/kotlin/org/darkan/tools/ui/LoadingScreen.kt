package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import org.darkan.tools.engine.async.LoadPhase
import org.darkan.tools.engine.async.LoadProgress

class LoadingScreen {
    fun render(progress: LoadProgress, windowWidth: Int, windowHeight: Int) {
        ImGui.setNextWindowPos(0f, 0f)
        ImGui.setNextWindowSize(windowWidth.toFloat(), windowHeight.toFloat())
        val flags = ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or
                    ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoScrollbar or
                    ImGuiWindowFlags.NoInputs or ImGuiWindowFlags.NoBackground

        if (ImGui.begin("##loading_overlay", flags)) {
            val centerX = windowWidth / 2f
            val centerY = windowHeight / 2f

            val phaseText = when (progress.phase) {
                LoadPhase.IDLE -> "Initializing..."
                LoadPhase.TERRAIN -> "Loading terrain..."
                LoadPhase.OBJECTS -> "Loading objects..."
                LoadPhase.BATCHING -> "Building batches..."
                LoadPhase.GPU_UPLOAD -> "Uploading to GPU..."
                LoadPhase.DONE -> "Done!"
            }

            val textWidth = ImGui.calcTextSize(phaseText).x
            ImGui.setCursorPos(centerX - textWidth / 2, centerY - 30)
            ImGui.text(phaseText)

            val barWidth = 300f
            ImGui.setCursorPos(centerX - barWidth / 2, centerY)
            ImGui.progressBar(progress.progress, barWidth, 20f,
                "%d / %d regions".format(progress.regionsReady, progress.totalRegions))

            if (progress.pendingGpuTasks > 0) {
                val gpuText = "${progress.pendingGpuTasks} GPU tasks pending"
                val gpuWidth = ImGui.calcTextSize(gpuText).x
                ImGui.setCursorPos(centerX - gpuWidth / 2, centerY + 30)
                ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, gpuText)
            }
        }
        ImGui.end()
    }
}
