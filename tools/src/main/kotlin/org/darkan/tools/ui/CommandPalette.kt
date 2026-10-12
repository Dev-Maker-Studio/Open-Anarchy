package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiFocusedFlags
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import org.lwjgl.glfw.GLFW.*

class CommandPalette {

    class Command(
        val id: String,
        val label: String,
        val group: String,
        val key: Int,
        val mods: Int,
        val description: String,
        val enabled: () -> Boolean,
        val run: () -> Unit,
    ) {
        val shortcut: String = buildString {
            if (key == 0) {
                return@buildString
            }
            if ((mods and GLFW_MOD_CONTROL) != 0) append("Ctrl+")
            if ((mods and GLFW_MOD_SHIFT) != 0) append("Shift+")
            if ((mods and GLFW_MOD_ALT) != 0) append("Alt+")
            append(keyName(key))
        }
    }

    private val commands = LinkedHashMap<String, Command>()

    private val query = ImString(96)
    private var open = false
    private var focusRequested = false
    private var highlighted = 0
    private var matches = emptyList<Command>()

    var lastRun: String = ""
        private set

    val groups: Map<String, List<Command>>
        get() = commands.values.groupBy { it.group }

    val all: Collection<Command> get() = commands.values

    @Suppress("LongParameterList")
    fun register(
        id: String,
        label: String,
        group: String = "General",
        key: Int = 0,
        mods: Int = 0,
        description: String = "",
        enabled: () -> Boolean = { true },
        run: () -> Unit,
    ) {
        commands[id] = Command(id, label, group, key, mods, description, enabled, run)
    }

    fun unregister(id: String) {
        commands.remove(id)
    }

    fun clear() {
        commands.clear()
    }

    fun run(id: String): Boolean {
        val command = commands[id] ?: return false
        return fire(command)
    }

    fun show() {
        open = true
        focusRequested = true
        query.set("")
        highlighted = 0
    }

    fun handleShortcuts(pressedKeys: List<Pair<Int, Int>>, textFieldFocused: Boolean) {
        if (textFieldFocused || pressedKeys.isEmpty()) {
            return
        }
        for ((key, mods) in pressedKeys) {
            val relevant = mods and (GLFW_MOD_CONTROL or GLFW_MOD_SHIFT or GLFW_MOD_ALT)
            val command = commands.values.firstOrNull { it.key == key && it.mods == relevant } ?: continue
            fire(command)
        }
    }

    fun render() {
        if (!open) {
            return
        }
        val viewportWidth = ImGui.getMainViewport().sizeX
        val viewportX = ImGui.getMainViewport().posX
        val viewportY = ImGui.getMainViewport().posY
        ImGui.setNextWindowPos(viewportX + (viewportWidth - PALETTE_WIDTH) / 2f, viewportY + 90f)
        ImGui.setNextWindowSize(PALETTE_WIDTH, 0f)
        ImGui.setNextWindowFocus()

        val flags = ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or
            ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoDocking or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.AlwaysAutoResize
        if (!ImGui.begin("##palette", flags)) {
            ImGui.end()
            return
        }

        if (focusRequested) {
            ImGui.setKeyboardFocusHere()
            focusRequested = false
        }
        ImGui.setNextItemWidth(-1f)
        val submitted = ImGui.inputTextWithHint(
            "##query", "Run a command...", query,
            ImGuiInputTextFlags.EnterReturnsTrue,
        )

        matches = filter(query.get().trim())
        highlighted = highlighted.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

        if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) {
            highlighted = (highlighted + 1).coerceAtMost((matches.size - 1).coerceAtLeast(0))
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) {
            highlighted = (highlighted - 1).coerceAtLeast(0)
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
            open = false
        }

        ImGui.separator()
        if (matches.isEmpty()) {
            ImGuiText.disabled("No matching command.")
        }
        for ((index, command) in matches.withIndex()) {
            val usable = command.enabled()
            if (!usable) {
                ImGui.beginDisabled()
            }
            if (ImGui.selectable("${command.label}##cmd${command.id}", index == highlighted)) {
                fire(command)
                open = false
            }
            if (command.shortcut.isNotEmpty()) {
                ImGui.sameLine(PALETTE_WIDTH - 130f)
                ImGuiText.disabled(command.shortcut)
            }
            if (!usable) {
                ImGui.endDisabled()
            }
            if (index == highlighted && command.description.isNotEmpty()) {
                ImGuiText.disabled("    ${command.description}")
            }
        }

        if (submitted) {
            matches.getOrNull(highlighted)?.let { fire(it) }
            open = false
        }
        if (!ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows) && !focusRequested) {
            open = false
        }
        ImGui.end()
    }

    fun renderShortcutList() {
        for ((group, items) in groups) {
            if (!ImGui.collapsingHeader(group)) {
                continue
            }
            for (command in items) {
                ImGui.text(command.label)
                if (command.shortcut.isNotEmpty()) {
                    ImGui.sameLine(220f)
                    ImGuiText.disabled(command.shortcut)
                }
                if (command.description.isNotEmpty()) {
                    ImGuiText.disabled("    ${command.description}")
                }
            }
        }
    }

    private fun fire(command: Command): Boolean {
        if (!command.enabled()) {
            return false
        }
        command.run()
        lastRun = command.label
        return true
    }

    private fun filter(text: String): List<Command> {
        if (text.isEmpty()) {
            return commands.values.toList()
        }
        val needle = text.lowercase()
        return commands.values
            .mapNotNull { command ->
                val score = score("${command.group} ${command.label}".lowercase(), needle)
                if (score < 0) null else command to score
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    private fun score(haystack: String, needle: String): Int {
        var at = 0
        var first = -1
        var last = 0
        for (character in needle) {
            val found = haystack.indexOf(character, at)
            if (found < 0) {
                return -1
            }
            if (first < 0) {
                first = found
            }
            last = found
            at = found + 1
        }
        return (last - first) + first / 4
    }

    companion object {
        private const val PALETTE_WIDTH = 520f

        private fun keyName(key: Int): String = when (key) {
            in GLFW_KEY_A..GLFW_KEY_Z -> ('A' + (key - GLFW_KEY_A)).toString()
            in GLFW_KEY_0..GLFW_KEY_9 -> ('0' + (key - GLFW_KEY_0)).toString()
            in GLFW_KEY_F1..GLFW_KEY_F12 -> "F${key - GLFW_KEY_F1 + 1}"
            GLFW_KEY_DELETE -> "Del"
            GLFW_KEY_ESCAPE -> "Esc"
            GLFW_KEY_SPACE -> "Space"
            GLFW_KEY_TAB -> "Tab"
            GLFW_KEY_ENTER -> "Enter"
            GLFW_KEY_LEFT -> "Left"
            GLFW_KEY_RIGHT -> "Right"
            GLFW_KEY_UP -> "Up"
            GLFW_KEY_DOWN -> "Down"
            GLFW_KEY_LEFT_BRACKET -> "["
            GLFW_KEY_RIGHT_BRACKET -> "]"
            else -> "key $key"
        }
    }
}
