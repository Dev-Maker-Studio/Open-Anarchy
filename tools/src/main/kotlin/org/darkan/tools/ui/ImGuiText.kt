package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol

/**
 * Text helpers that do not treat their argument as a printf format string.
 *
 * Only `ImGui.text` is safe in imgui-java: it binds to `ImGui::TextUnformatted`.
 * Every other text call passes the string straight through as the *format*
 * argument -
 *
 * ```
 * public static native void textDisabled(String text); //  ImGui::TextDisabled(text, NULL);
 * public static native void textWrapped(String text);  //  ImGui::TextWrapped(text, NULL);
 * public static native void textColored(int, String);  //  ImGui::TextColored(ImColor(col), text, NULL);
 * ```
 *
 * so a single `%` in the string makes the C runtime read a conversion argument
 * that was never pushed. In this editor that is not a theoretical hazard: the
 * panels display interface text, component names and script strings straight out
 * of the cache, any of which can contain a `%`, and the zoom control legitimately
 * wants to print one. The observed symptom is the label rendering as a fragment
 * of some unrelated string followed by a run of `?`, because the format walked
 * off into whatever happened to be in the varargs registers.
 *
 * These wrappers reproduce each style through the unformatted path instead, so
 * arbitrary text is always safe to display.
 */
object ImGuiText {

    /** Greyed-out text, safe for arbitrary content. */
    fun disabled(text: String) {
        pushDisabledColour()
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    /** Word-wrapped text, safe for arbitrary content. */
    fun wrapped(text: String) {
        // The zero wrap position is ImGui's "wrap at the window's right edge",
        // which is what TextWrapped does before delegating to Text.
        ImGui.pushTextWrapPos(0f)
        ImGui.text(text)
        ImGui.popTextWrapPos()
    }

    /** Coloured text, safe for arbitrary content. [colour] is ImGui's packed ABGR. */
    fun coloured(colour: Int, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, colour)
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    /** Coloured text from normalised components, safe for arbitrary content. */
    fun coloured(red: Float, green: Float, blue: Float, text: String) {
        coloured(red, green, blue, 1f, text)
    }

    /** Coloured text from normalised components with an explicit alpha. */
    fun coloured(red: Float, green: Float, blue: Float, alpha: Float, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, red, green, blue, alpha)
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    /**
     * A hover tooltip, safe for arbitrary content. `SetTooltip` is one of the
     * printf calls, and tooltips are where script errors and component names end
     * up, so it is the last place that should be formatting its argument.
     */
    fun tooltip(text: String) {
        ImGui.beginTooltip()
        wrapped(text)
        ImGui.endTooltip()
    }

    /** Greyed-out, word-wrapped text. */
    fun disabledWrapped(text: String) {
        pushDisabledColour()
        wrapped(text)
        ImGui.popStyleColor()
    }

    /** Pushes the theme's disabled-text colour; the caller pops it. */
    private fun pushDisabledColour() {
        val colour = ImGui.getStyle().getColor(ImGuiCol.TextDisabled)
        ImGui.pushStyleColor(ImGuiCol.Text, colour.x, colour.y, colour.z, colour.w)
    }
}
