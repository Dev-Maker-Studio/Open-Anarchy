package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiChildFlags
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImBoolean
import imgui.type.ImString
import org.darkan.cs2.vm.Cs2HookDispatcher
import org.darkan.tools.rsinterface.InterfaceCs2Host
import org.darkan.tools.rsinterface.InterfaceEvent

class Cs2HookPanel(
    private val context: InterfaceEditContext,
    private val host: InterfaceCs2Host,
    private val onRunHook: (Int, InterfaceEvent) -> Unit,
    private val onOpenScript: (Int) -> Unit,
    private val onRunLoadScripts: () -> Unit,
) {
    var visible: Boolean = true

    private val filter = ImString(48)
    private var selectionOnly = false

    fun scripts(): List<Int> {
        val document = context.document ?: return emptyList()
        val ids = LinkedHashSet<Int>()
        for (index in 0 until document.componentCount) {
            document.component(index)?.hooks()?.values?.forEach { ids.add(it.script) }
        }
        for (events in host.boundHooks.values) {
            events.values.forEach { ids.add(it.scriptId) }
        }
        return ids.filter { it >= 0 }
    }

    fun render() {
        if (!visible) {
            return
        }
        if (!ImGui.begin("CS2 Hooks")) {
            ImGui.end()
            return
        }
        val document = context.document
        if (document == null) {
            ImGuiText.disabled("No interface open.")
            ImGui.end()
            return
        }

        if (ImGui.button("Run load scripts")) {
            onRunLoadScripts()
        }
        ImGui.sameLine()
        if (ImGui.button("Clear bound hooks")) {
            host.clearHooks()
        }
        ImGui.sameLine()
        ImGuiText.disabled("${host.boundHooks.size} components have runtime bindings")

        ImGui.setNextItemWidth(180f)
        ImGui.inputTextWithHint("##hookfilter", "event, field or script id", filter)
        ImGui.sameLine()
        val only = ImBoolean(selectionOnly)
        if (ImGui.checkbox("selection only", only)) {
            selectionOnly = only.get()
        }
        ImGui.separator()

        table()

        if (ImGui.collapsingHeader("Script output (${host.log.size})", ImGuiTreeNodeFlags.DefaultOpen)) {
            log()
        }
        ImGui.end()
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun table() {
        val document = context.document ?: return
        val needle = filter.get().trim().lowercase()
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.ScrollY or
            ImGuiTableFlags.SizingStretchProp or ImGuiTableFlags.BordersInnerV
        if (!ImGui.beginTable("hooks", 5, flags, 0f, TABLE_HEIGHT)) {
            return
        }
        ImGui.tableSetupColumn("at", ImGuiTableColumnFlags.WidthFixed, 44f)
        ImGui.tableSetupColumn("event", ImGuiTableColumnFlags.WidthFixed, 150f)
        ImGui.tableSetupColumn("script", ImGuiTableColumnFlags.WidthFixed, 60f)
        ImGui.tableSetupColumn("from", ImGuiTableColumnFlags.WidthFixed, 52f)
        ImGui.tableSetupColumn("arguments", ImGuiTableColumnFlags.WidthStretch)
        ImGui.tableSetupScrollFreeze(0, 1)
        ImGui.tableHeadersRow()

        var shown = 0
        for (index in 0 until document.componentCount) {
            if (selectionOnly && !context.isSelected(index)) {
                continue
            }
            val definition = document.component(index) ?: continue
            val hash = document.hash(index)
            val bound = host.boundHooks[hash]

            for ((field, hook) in definition.hooks()) {
                val event = EVENT_BY_FIELD[field]
                val overridden = event != null && bound?.containsKey(event.boundKey) == true
                if (!matches(needle, event?.name, field, hook.script)) {
                    continue
                }
                row(index, event, field, hook.script, arguments(hook.args), false, overridden)
                shown++
            }
            if (bound == null) {
                continue
            }
            for ((key, hook) in bound) {
                val event = EVENT_BY_KEY[key]
                if (!matches(needle, event?.name ?: key, event?.field ?: key, hook.scriptId)) {
                    continue
                }
                val args = hook.args.ints.map { it as Any } + hook.args.strings.map { it as Any }
                row(index, event, event?.field ?: "bound $key", hook.scriptId, arguments(args.toTypedArray()), true, false)
                shown++
            }
        }
        ImGui.endTable()
        if (shown == 0) {
            ImGuiText.disabled(
                if (selectionOnly) "The selection has no hooks." else "This interface has no hooks."
            )
        }
    }

    @Suppress("LongParameterList")
    private fun row(
        index: Int,
        event: InterfaceEvent?,
        field: String,
        script: Int,
        args: String,
        bound: Boolean,
        overridden: Boolean,
    ) {
        ImGui.tableNextRow()
        ImGui.tableSetColumnIndex(0)
        ImGui.pushID("$index/$field/$bound")
        if (ImGui.smallButton("$index")) {
            context.reveal(index)
        }

        ImGui.tableSetColumnIndex(1)
        if (overridden) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.55f, 0.55f, 0.58f, 1f)
            ImGui.text("${event?.name ?: field} (replaced)")
            ImGui.popStyleColor()
        } else {
            ImGui.text(event?.name ?: field)
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("definition field: $field")
        }
        if (event != null) {
            ImGui.sameLine()
            if (ImGui.smallButton("run")) {
                onRunHook(index, event)
            }
        }

        ImGui.tableSetColumnIndex(2)
        if (ImGui.smallButton("$script")) {
            onOpenScript(script)
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("export and open clientscript $script")
        }

        ImGui.tableSetColumnIndex(3)
        ImGuiText.disabled(if (bound) "script" else "cache")

        ImGui.tableSetColumnIndex(4)
        ImGuiText.disabled((args))
        ImGui.popID()
    }

    private fun matches(needle: String, event: String?, field: String, script: Int): Boolean {
        if (needle.isEmpty()) {
            return true
        }
        return event?.lowercase()?.contains(needle) == true ||
            field.lowercase().contains(needle) ||
            script.toString() == needle
    }

    private fun log() {
        if (host.log.isEmpty()) {
            ImGuiText.disabled("Nothing yet. Errors from a running script land here.")
            return
        }
        if (ImGui.smallButton("clear##log")) {
            host.log.clear()
            return
        }
        if (!ImGui.beginChild("hooklog", 0f, LOG_HEIGHT, ImGuiChildFlags.Borders)) {
            ImGui.endChild()
            return
        }
        for (line in host.log.takeLast(LOG_LINES)) {
            if (line.startsWith("error")) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.45f, 0.4f, 1f)
                ImGuiText.wrapped((line))
                ImGui.popStyleColor()
            } else {
                ImGuiText.wrapped((line))
            }
        }
        ImGui.endChild()
    }

    private fun arguments(args: Array<Any>): String {
        if (args.isEmpty()) {
            return "()"
        }
        return args.joinToString(", ", "(", ")") { describe(it) }
    }

    private fun describe(value: Any?): String = when (value) {
        is String -> if (value == Cs2HookDispatcher.OPTION_NAME) "<op base>" else "\"$value\""
        is Int -> when (value) {
            Cs2HookDispatcher.MOUSE_X -> "<mouse x>"
            Cs2HookDispatcher.MOUSE_Y -> "<mouse y>"
            Cs2HookDispatcher.SOURCE_COMPONENT -> "<this>"
            Cs2HookDispatcher.CLICKED_OPTION -> "<option>"
            Cs2HookDispatcher.SOURCE_SLOT -> "<slot>"
            Cs2HookDispatcher.TARGET_COMPONENT -> "<target>"
            Cs2HookDispatcher.TARGET_SLOT -> "<target slot>"
            Cs2HookDispatcher.KEY_CODE -> "<key>"
            Cs2HookDispatcher.KEY_CHAR -> "<char>"
            else -> if (value > 0xffff) "0x%08X".format(value) else value.toString()
        }
        else -> value.toString()
    }

    companion object {
        private const val TABLE_HEIGHT = 240f
        private const val LOG_HEIGHT = 130f
        private const val LOG_LINES = 200

        private val EVENT_BY_FIELD: Map<String, InterfaceEvent> =
            InterfaceEvent.entries.mapNotNull { event -> event.field?.let { it to event } }.toMap()

        private val EVENT_BY_KEY: Map<String, InterfaceEvent> =
            InterfaceEvent.entries.associateBy { it.boundKey }
    }
}
