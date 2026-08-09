package org.darkan.tools.rsinterface

import org.darkan.tools.cs2.Cs2Op
import org.darkan.tools.cs2.vm.Cs2Hook
import org.darkan.tools.cs2.vm.Cs2Host
import org.darkan.tools.cs2.vm.Cs2Values
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition

/**
 * Answers CS2 opcodes against a live interface.
 *
 * Only the opcodes that actually move an interface are implemented; the virtual
 * machine pads anything else to the right shape, so an unimplemented opcode
 * behaves as a no-op returning zero rather than derailing the script.
 *
 * `CC_*` opcodes act on an "active component" rather than naming one. The
 * client keeps two such registers and picks between them with the instruction's
 * own operand, which is why [activeComponents] has two slots.
 */
class InterfaceCs2Host(
    private val interfaces: (Int) -> InterfaceInstance?,
    private val enums: (Int) -> EnumDefinition?,
    private val structs: (Int) -> StructDefinition?,
    private val params: (Int) -> ParamDefinition?,
) : Cs2Host {

    private val varps = HashMap<Int, Int>()
    private val varbits = HashMap<Int, Int>()
    private val varcs = HashMap<Int, Int>()
    private val varcStrings = HashMap<Int, String>()

    /** The two `CC_*` target registers. */
    private val activeComponents = arrayOfNulls<ComponentState>(2)

    /** Hooks the running scripts have bound, keyed by component hash then event. */
    val boundHooks: MutableMap<Int, MutableMap<String, Cs2Hook>> = LinkedHashMap()

    /** Messages a script produced, shown in the editor. */
    val log: MutableList<String> = ArrayList()

    var onChanged: (() -> Unit)? = null

    fun setActive(slot: Int, component: ComponentState?) {
        activeComponents[slot.coerceIn(0, 1)] = component
    }

    fun clearHooks() {
        boundHooks.clear()
    }

    override fun invoke(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values {
        val name = op.opName
        val component = when {
            name.startsWith("CC_") -> activeComponents[if (operand == 1) 1 else 0]
            args.ints.isNotEmpty() -> componentOf(args.ints.last())
            else -> null
        }

        // Setters take the component id last, so the value arguments come first.
        val values = if (name.startsWith("CC_")) args.ints else args.ints.dropLast(1)

        return when (name) {
            "IF_SETHIDE", "CC_SETHIDE" -> mutate(component) { it.hidden = values.first() != 0 }
            "IF_SETCOLOUR", "CC_SETCOLOUR" -> mutate(component) { it.colour = values.first() }
            "IF_SETTRANS", "CC_SETTRANS" -> mutate(component) { it.transparency = values.first() }
            "IF_SETGRAPHIC", "CC_SETGRAPHIC" -> mutate(component) { it.spriteId = values.first() }
            "IF_SETTEXT", "CC_SETTEXT" -> mutate(component) { it.text = args.strings.firstOrNull() ?: "" }
            "IF_SETFILLED", "CC_SETFILLED" -> mutate(component) { it.filled = values.first() != 0 }
            "IF_SETSIZE", "CC_SETSIZE" ->
                mutate(component) { it.setSize(values[0], values[1], values[2], values[3]) }
            "IF_SETPOSITION", "CC_SETPOSITION" ->
                mutate(component) { it.setPosition(values[0], values[1], values[2], values[3]) }
            "IF_SETSCROLLSIZE", "CC_SETSCROLLSIZE" ->
                mutate(component) { it.setScrollSize(values[0], values[1]) }
            "IF_SETSCROLLPOS", "CC_SETSCROLLPOS" ->
                mutate(component) { it.setScrollPos(values[0], values[1]) }
            "IF_SETOBJECT", "CC_SETOBJECT" ->
                mutate(component) { it.setObject(values[0], values[1]) }
            "IF_SETMODEL", "CC_SETMODEL" -> mutate(component) { it.setModel(values[0]) }
            "IF_SETMODELANGLE", "CC_SETMODELANGLE" ->
                mutate(component) { it.setModelAngle(values[0], values[1], values[2], values[3]) }

            "IF_GETWIDTH", "CC_GETWIDTH" -> ints(component?.width ?: 0)
            "IF_GETHEIGHT", "CC_GETHEIGHT" -> ints(component?.height ?: 0)
            "IF_GETX", "CC_GETX" -> ints(component?.screenX ?: 0)
            "IF_GETY", "CC_GETY" -> ints(component?.screenY ?: 0)
            "IF_GETHIDE", "CC_GETHIDE" -> ints(if (component?.hidden == true) 1 else 0)
            "IF_GETTRANS", "CC_GETTRANS" -> ints(component?.transparency ?: 0)
            "IF_GETCOLOUR", "CC_GETCOLOUR" -> ints(component?.colour ?: 0)
            "IF_GETSCROLLX", "CC_GETSCROLLX" -> ints(component?.scrollX ?: 0)
            "IF_GETSCROLLY", "CC_GETSCROLLY" -> ints(component?.scrollY ?: 0)
            "IF_GETTEXT", "CC_GETTEXT" -> strings(component?.text ?: "")

            "ENUM" -> enumLookup(args.ints)
            "STRUCT_PARAM" -> structParam(args.ints)

            "MES", "WRITECONSOLE" -> {
                args.strings.forEach { log.add(it) }
                Cs2Values.EMPTY
            }

            else -> Cs2Host.defaults(op)
        }
    }

    private fun componentOf(hash: Int): ComponentState? =
        interfaces(hash shr 16)?.stateByHash(hash)

    private fun mutate(component: ComponentState?, change: (ComponentState) -> Unit): Cs2Values {
        if (component != null) {
            change(component)
            onChanged?.invoke()
        }
        return Cs2Values.EMPTY
    }

    private fun ints(vararg values: Int) = Cs2Values(ints = values.toList())

    private fun strings(vararg values: String) = Cs2Values(strings = values.toList())

    /** `ENUM` takes key type, value type, enum id and key. */
    private fun enumLookup(args: List<Int>): Cs2Values {
        if (args.size < 4) return Cs2Values.EMPTY
        val definition = enums(args[2]) ?: return if (args[1] == 's'.code) strings("") else ints(-1)
        val key = args[3]
        return if (args[1] == 's'.code) strings(definition.getString(key) ?: "") else ints(definition.getInt(key))
    }

    /** `STRUCT_PARAM` takes a struct id and a param id. */
    private fun structParam(args: List<Int>): Cs2Values {
        if (args.size < 2) return Cs2Values.EMPTY
        val param = params(args[1])
        val struct = structs(args[0])
        return if (param?.isString() == true) {
            strings(struct?.getStringValue(args[1]) ?: param.defaultString ?: "")
        } else {
            ints(struct?.getIntValue(args[1], param?.defaultInt ?: 0) ?: (param?.defaultInt ?: 0))
        }
    }

    override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) {
        val target = if (op.opName.startsWith("CC_")) {
            activeComponents[0]?.hash ?: return
        } else {
            component
        }
        val event = op.opName.removePrefix("IF_SETON").removePrefix("CC_SETON").removePrefix("HOOK_")
        val events = boundHooks.getOrPut(target) { LinkedHashMap() }
        if (hook == null) events.remove(event) else events[event] = hook
    }

    override fun varp(id: Int) = varps[id] ?: 0

    override fun setVarp(id: Int, value: Int) {
        varps[id] = value
        onChanged?.invoke()
    }

    override fun varbit(id: Int) = varbits[id] ?: 0

    override fun setVarbit(id: Int, value: Int) {
        varbits[id] = value
        onChanged?.invoke()
    }

    override fun varc(id: Int) = varcs[id] ?: 0

    override fun setVarc(id: Int, value: Int) {
        varcs[id] = value
    }

    override fun varcString(id: Int) = varcStrings[id] ?: ""

    override fun setVarcString(id: Int, value: String) {
        varcStrings[id] = value
    }

    override fun onError(message: String) {
        log.add("error: $message")
    }
}
