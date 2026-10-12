package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol

object ImGuiText {

    fun disabled(text: String) {
        pushDisabledColour()
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    fun wrapped(text: String) {
        ImGui.pushTextWrapPos(0f)
        ImGui.text(text)
        ImGui.popTextWrapPos()
    }

    fun coloured(colour: Int, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, colour)
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    fun coloured(red: Float, green: Float, blue: Float, text: String) {
        coloured(red, green, blue, 1f, text)
    }

    fun coloured(red: Float, green: Float, blue: Float, alpha: Float, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, red, green, blue, alpha)
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    fun bullet(text: String) {
        ImGui.bullet()
        ImGui.text(text)
    }

    fun treeNode(id: String, label: String): Boolean {
        return ImGui.treeNode("$label###$id")
    }

    fun tooltip(text: String) {
        ImGui.beginTooltip()
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + ImGui.getFontSize() * 35f)
        ImGui.text(text)
        ImGui.popTextWrapPos()
        ImGui.endTooltip()
    }

    fun colouredWrapped(red: Float, green: Float, blue: Float, alpha: Float, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, red, green, blue, alpha)
        wrapped(text)
        ImGui.popStyleColor()
    }

    fun disabledWrapped(text: String) {
        pushDisabledColour()
        wrapped(text)
        ImGui.popStyleColor()
    }

    private fun pushDisabledColour() {
        val colour = ImGui.getStyle().getColor(ImGuiCol.TextDisabled)
        ImGui.pushStyleColor(ImGuiCol.Text, colour.x, colour.y, colour.z, colour.w)
    }
}
