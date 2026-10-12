package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import org.darkan.tools.rsinterface.ComponentOption
import org.darkan.tools.rsinterface.InterfaceInteraction
import org.darkan.tools.rsinterface.OptionKind

class InterfaceMenuOverlay(private val interaction: InterfaceInteraction) {

    var hovered: Boolean = false
        private set

    fun render(screenX: (Int) -> Float, screenY: (Int) -> Float) {
        hovered = false
        if (!interaction.menuOpen) {
            return
        }
        val entries = interaction.menu
        val width = menuWidth(entries)
        ImGui.setNextWindowPos(screenX(interaction.menuX), screenY(interaction.menuY))
        ImGui.setNextWindowSize(width, 0f)
        ImGui.setNextWindowBgAlpha(1f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f)
        ImGui.pushStyleColor(ImGuiCol.WindowBg, BODY_R, BODY_G, BODY_B, 1f)
        ImGui.pushStyleColor(ImGuiCol.Border, BORDER_R, BORDER_G, BORDER_B, 1f)
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, 0.16f, 0.20f, 0.24f, 1f)
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, 0.22f, 0.30f, 0.36f, 1f)

        val flags = ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or
            ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoDocking or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoScrollbar or
            ImGuiWindowFlags.AlwaysAutoResize
        val open = ImGui.begin("##optionmenu", flags)
        if (open) {
            drawHeader(width)
            drawEntries(entries)
            hovered = ImGui.isWindowHovered(0)
            dismissOnClickAway()
        }
        ImGui.end()

        ImGui.popStyleColor(4)
        ImGui.popStyleVar(4)
    }

    private fun drawHeader(width: Float) {
        val drawList = ImGui.getWindowDrawList()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val height = ImGui.getTextLineHeight() + HEADER_PADDING * 2f
        drawList.addRectFilled(x, y, x + width, y + height, HEADER_BG)
        drawList.addText(x + PADDING, y + HEADER_PADDING, HEADER_TEXT, "Choose Option")
        ImGui.dummy(width, height)
        drawList.addLine(x, y + height, x + width, y + height, LINE)
    }

    private fun drawEntries(entries: List<ComponentOption>) {
        for ((position, entry) in entries.withIndex()) {
            ImGui.pushID(position)
            ImGui.pushStyleColor(ImGuiCol.Text, colourFor(entry)[0], colourFor(entry)[1], colourFor(entry)[2], 1f)
            if (ImGui.selectable("  ${entry.text}")) {
                interaction.chooseOption(entry)
            }
            ImGui.popStyleColor()
            if (ImGui.isItemHovered() && entry.slot >= 0) {
                ImGuiText.tooltip("component ${entry.component.index}, slot ${entry.slot}")
            }
            ImGui.popID()
        }
    }

    private fun dismissOnClickAway() {
        if (ImGui.isWindowAppearing() || ImGui.isWindowHovered(0)) {
            return
        }
        if (ImGui.isMouseClicked(ImGuiMouseButton.Left) || ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            interaction.closeMenu()
        }
    }

    private fun menuWidth(entries: List<ComponentOption>): Float {
        var widest = ImGui.calcTextSize("Choose Option").x
        for (entry in entries) {
            widest = maxOf(widest, ImGui.calcTextSize(entry.text).x)
        }
        return (widest + PADDING * 4f).coerceAtLeast(MIN_WIDTH)
    }

    private fun colourFor(entry: ComponentOption): FloatArray = when (entry.kind) {
        OptionKind.USE -> floatArrayOf(0.62f, 0.85f, 1f)
        OptionKind.CONTINUE -> floatArrayOf(1f, 0.85f, 0.45f)
        OptionKind.OPTION -> floatArrayOf(1f, 1f, 1f)
    }

    companion object {
        private const val PADDING = 4f
        private const val HEADER_PADDING = 3f
        private const val MIN_WIDTH = 120f

        private const val BODY_R = 0.03f
        private const val BODY_G = 0.03f
        private const val BODY_B = 0.03f
        private const val BORDER_R = 0.60f
        private const val BORDER_G = 0.52f
        private const val BORDER_B = 0.34f

        private const val HEADER_BG = 0xff1b1b26L.toInt()
        private const val HEADER_TEXT = 0xff3ad4ffL.toInt()
        private const val LINE = 0xff56698aL.toInt()
    }
}
