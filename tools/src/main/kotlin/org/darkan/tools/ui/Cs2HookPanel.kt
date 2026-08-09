package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImInt
import org.darkan.tools.cs2.vm.Cs2Event
import org.darkan.tools.cs2.vm.Cs2Hook
import org.darkan.tools.cs2.vm.Cs2HookDispatcher
import org.darkan.tools.rsinterface.ComponentState
import org.darkan.tools.rsinterface.InterfaceCs2Host
import org.darkan.tools.rsinterface.InterfaceInstance
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook

/**
 * Lets every CS2 hook on the open interface be inspected and fired.
 *
 * Hooks come from two places and both are listed: the ones baked into the
 * component definitions, and the ones scripts bind at runtime with the
 * `IF_SETON*` / `CC_SETON*` opcodes. Firing one runs the real script against the
 * live interface, so the effect shows up in the render immediately.
 */
class Cs2HookPanel(
    private val dispatcher: Cs2HookDispatcher,
    private val host: InterfaceCs2Host,
) {
    var visible = true

    private var firstFrame = true

    /** Event data substituted for the hooks' placeholder arguments. */
    private val mouseX = ImInt(0)
    private val mouseY = ImInt(0)
    private val slot = ImInt(-1)
    private val option = ImInt(1)
    private val keyCode = ImInt(0)

    /** The last script that ran, for the status line. */
    private var lastRun: String = "nothing run yet"

    fun render(instance: InterfaceInstance?, selected: ComponentState?, windowHeight: Int) {
        if (!visible) return
        if (firstFrame) {
            ImGui.setNextWindowPos(10f, 320f)
            ImGui.setNextWindowSize(380f, (windowHeight * 0.55f).coerceAtLeast(320f))
            firstFrame = false
        }
        if (!ImGui.begin("CS2 Hooks")) {
            ImGui.end()
            return
        }

        if (instance == null) {
            ImGui.text("No interface open.")
            ImGui.end()
            return
        }

        renderEventInputs()
        ImGui.separator()

        if (ImGui.button("Run onLoad for every component")) runAllLoadScripts(instance)
        ImGui.sameLine()
        if (ImGui.button("Reset")) {
            instance.reset()
            host.clearHooks()
            host.log.clear()
            lastRun = "interface reset"
        }
        ImGui.textWrapped(lastRun)
        ImGui.separator()

        if (ImGui.collapsingHeader("Defined hooks", ImGuiTreeNodeFlags.DefaultOpen)) {
            renderDefinedHooks(instance, selected)
        }
        if (ImGui.collapsingHeader("Bound by scripts")) {
            renderBoundHooks(instance)
        }
        if (ImGui.collapsingHeader("Script output")) {
            renderLog()
        }
        ImGui.end()
    }

    private fun renderEventInputs() {
        ImGui.text("Event data")
        ImGui.inputInt("mouse x", mouseX)
        ImGui.inputInt("mouse y", mouseY)
        ImGui.inputInt("slot", slot)
        ImGui.inputInt("option", option)
        ImGui.inputInt("key", keyCode)
    }

    private fun event(source: Int) = Cs2Event(
        mouseX = mouseX.get(),
        mouseY = mouseY.get(),
        source = source,
        sourceSlot = slot.get(),
        clickedOption = option.get(),
        keyCode = keyCode.get(),
        keyChar = keyCode.get(),
    )

    /**
     * Hooks declared on the components themselves. Only components that carry
     * at least one are listed, which is a small fraction of any interface.
     */
    private fun renderDefinedHooks(instance: InterfaceInstance, selected: ComponentState?) {
        var shown = 0
        for (component in instance.components) {
            val hooks = component.definition.hooks()
            if (hooks.isEmpty()) continue
            if (selected != null && selected !== component && !ImGui.getIO().keyShift) {
                // Without shift the list follows the selection, which keeps it
                // usable on interfaces with hundreds of hooked components.
                if (selected.index != component.index) continue
            }
            shown++
            if (!ImGui.treeNode("component ${component.index} (${hooks.size} hooks)")) continue
            for ((name, hook) in hooks) {
                ImGui.text("$name -> script ${hook.script}")
                ImGui.sameLine()
                if (ImGui.button("fire##$name${component.index}")) fire(name, hook, component)
                if (hook.args.isNotEmpty()) {
                    ImGui.textDisabled("  args: ${hook.args.joinToString(", ") { describe(it) }}")
                }
            }
            ImGui.treePop()
        }
        if (shown == 0) {
            ImGui.textDisabled(
                if (selected == null) "Select a component, or hold shift to list every hook."
                else "This component has no hooks. Hold shift to list every hook.",
            )
        }
    }

    private fun renderBoundHooks(instance: InterfaceInstance) {
        if (host.boundHooks.isEmpty()) {
            ImGui.textDisabled("No scripts have bound a hook yet.")
            return
        }
        for ((hash, events) in host.boundHooks) {
            val component = instance.stateByHash(hash)
            val label = if (component == null) "component $hash" else "component ${component.index}"
            if (!ImGui.treeNode("$label (${events.size})")) continue
            for ((event, hook) in events) {
                ImGui.text("$event -> script ${hook.scriptId}")
                ImGui.sameLine()
                if (ImGui.button("fire##bound$hash$event")) fireBound(event, hook, hash)
            }
            ImGui.treePop()
        }
    }

    private fun renderLog() {
        if (host.log.isEmpty()) {
            ImGui.textDisabled("No output.")
            return
        }
        host.log.takeLast(80).forEach { ImGui.textWrapped(it) }
        if (ImGui.button("Clear output")) host.log.clear()
    }

    private fun describe(value: Any) = when (value) {
        is String -> "\"$value\""
        is Int -> if (Cs2HookDispatcher.isSentinel(value)) sentinelName(value) else value.toString()
        else -> value.toString()
    }

    private fun sentinelName(value: Int) = when (value) {
        Cs2HookDispatcher.MOUSE_X -> "<mouse x>"
        Cs2HookDispatcher.MOUSE_Y -> "<mouse y>"
        Cs2HookDispatcher.SOURCE_COMPONENT -> "<this component>"
        Cs2HookDispatcher.CLICKED_OPTION -> "<option>"
        Cs2HookDispatcher.SOURCE_SLOT -> "<slot>"
        Cs2HookDispatcher.TARGET_COMPONENT -> "<target component>"
        Cs2HookDispatcher.TARGET_SLOT -> "<target slot>"
        Cs2HookDispatcher.KEY_CODE -> "<key code>"
        Cs2HookDispatcher.KEY_CHAR -> "<key char>"
        else -> value.toString()
    }

    private fun fire(name: String, hook: InterfaceComponentHook, component: ComponentState) {
        host.setActive(0, component)
        dispatcher.dispatch(hook, event(component.hash))
        lastRun = "$name on component ${component.index} ran script ${hook.script}"
    }

    private fun fireBound(name: String, hook: Cs2Hook, hash: Int) {
        dispatcher.dispatch(hook, event(hash))
        lastRun = "$name ran script ${hook.scriptId}"
    }

    /**
     * Interfaces set themselves up in their components' load scripts, so running
     * them all is the closest thing to opening the interface for real.
     */
    private fun runAllLoadScripts(instance: InterfaceInstance) {
        var count = 0
        for (component in instance.components) {
            val hook = component.definition.onLoadScript ?: continue
            host.setActive(0, component)
            dispatcher.dispatch(hook, event(component.hash))
            count++
        }
        lastRun = "ran $count onLoad scripts"
    }
}
