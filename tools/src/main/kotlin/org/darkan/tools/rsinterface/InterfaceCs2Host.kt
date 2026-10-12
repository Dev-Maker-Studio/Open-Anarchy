package org.darkan.tools.rsinterface

import org.darkan.cs2.Cs2Op
import org.darkan.cs2.ir.VarSpace
import org.darkan.cs2.vm.Cs2Hook
import org.darkan.cs2.vm.Cs2Host
import org.darkan.cs2.vm.Cs2Values
import org.darkan.tools.engine.render.ObjIconStack
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition

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

    private val activeComponents = arrayOfNulls<ComponentState>(2)

    val boundHooks: MutableMap<Int, MutableMap<String, Cs2Hook>> = LinkedHashMap()

    val log: MutableList<String> = ArrayList()

    var onChanged: (() -> Unit)? = null

    var onVarpWritten: ((Int) -> Unit)? = null

    var onVarcWritten: ((Int) -> Unit)? = null

    var onVarcStringWritten: ((Int) -> Unit)? = null

    var onDragPickup: ((ComponentState, Int, Int) -> Unit)? = null


    var mouseX: Int = -1
    var mouseY: Int = -1

    var mouseLeft: Boolean = false
    var mouseRight: Boolean = false
    var mouseMiddle: Boolean = false

    val heldKeys: MutableSet<Int> = LinkedHashSet()

    var paragraphLines: ((String, Int, Int) -> Int)? = null

    fun setActive(slot: Int, component: ComponentState?) {
        activeComponents[slot.coerceIn(0, 1)] = component
    }

    fun clearHooks() {
        boundHooks.clear()
    }

    fun boundHook(hash: Int, field: String): Cs2Hook? {
        val suffix = HOOK_SUFFIX_BY_FIELD[field] ?: return null
        return boundHooks[hash]?.get(suffix)
    }

    override fun invoke(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values {
        runtimeComponentOp(op, operand, args)?.let { return it }

        val activeTarget = op.name.startsWith("CC_")
        val component = when {
            activeTarget -> activeComponents[if (operand == 1) 1 else 0]
            args.ints.isNotEmpty() -> componentOf(args.ints.last())
            else -> null
        }

        val values = if (activeTarget) args.ints else args.ints.dropLast(1)

        return when (op) {
            Cs2Op.IF_SETHIDE, Cs2Op.CC_SETHIDE -> mutate(component) { it.hidden = values.first() != 0 }
            Cs2Op.IF_SETCOLOUR, Cs2Op.CC_SETCOLOUR -> mutate(component) { it.colour = values.first() }
            Cs2Op.IF_SETTRANS, Cs2Op.CC_SETTRANS -> mutate(component) { it.transparency = values.first() }
            Cs2Op.IF_SETGRAPHIC, Cs2Op.CC_SETGRAPHIC -> mutate(component) { it.graphicId = values.first() }
            Cs2Op.IF_SETTEXT, Cs2Op.CC_SETTEXT -> mutate(component) { it.text = args.strings.firstOrNull() ?: "" }
            Cs2Op.IF_SETFILL, Cs2Op.CC_SETFILL ->
                mutate(component) { it.filled = values.first() != 0 }
            Cs2Op.IF_SETSIZE, Cs2Op.CC_SETSIZE ->
                reshape(component) { it.setSize(values[0], values[1], values[2], values[3]) }
            Cs2Op.IF_SETPOSITION, Cs2Op.CC_SETPOSITION ->
                reshape(component) { it.setPosition(values[0], values[1], values[2], values[3]) }
            Cs2Op.IF_SETASPECT, Cs2Op.CC_SETASPECT -> reshape(component) { it.setAspect(values[0], values[1]) }
            Cs2Op.IF_SETSCROLLSIZE, Cs2Op.CC_SETSCROLLSIZE ->
                mutate(component) { it.setScrollSize(values[0], values[1]) }
            Cs2Op.IF_SETSCROLLPOS, Cs2Op.CC_SETSCROLLPOS ->
                mutate(component) { it.setScrollPos(values[0], values[1]) }
            Cs2Op.IF_SETOBJECT, Cs2Op.CC_SETOBJECT, Cs2Op.IF_SETOBJECT_WEARCOL, Cs2Op.CC_SETOBJECT_WEARCOL ->
                mutate(component) { it.setObject(values[0], values[1]) }
            Cs2Op.IF_SETOBJECT_NONUM, Cs2Op.CC_SETOBJECT_NONUM,
            Cs2Op.IF_SETOBJECT_WEARCOL_NONUM, Cs2Op.CC_SETOBJECT_WEARCOL_NONUM ->
                mutate(component) { it.setObject(values[0], values[1], ObjIconStack.NONE) }
            Cs2Op.IF_SETOBJECT_ALWAYSNUM, Cs2Op.CC_SETOBJECT_ALWAYSNUM,
            Cs2Op.IF_SETOBJECT_WEARCOL_ALWAYSNUM, Cs2Op.CC_SETOBJECT_WEARCOL_ALWAYSNUM ->
                mutate(component) { it.setObject(values[0], values[1], ObjIconStack.ALWAYS) }
            Cs2Op.IF_SETMODEL, Cs2Op.CC_SETMODEL -> mutate(component) { it.setModel(values[0]) }
            Cs2Op.IF_SETMODELANGLE, Cs2Op.CC_SETMODELANGLE ->
                mutate(component) { it.setModelAngle(values[0], values[1], values[2], values[3]) }
            Cs2Op.IF_SETTEXTALIGN, Cs2Op.CC_SETTEXTALIGN ->
                mutate(component) { it.setTextAlign(values[0], values[1], values[2]) }
            Cs2Op.IF_SETTEXTFONT, Cs2Op.CC_SETTEXTFONT -> mutate(component) { it.fontId = values.first() }
            Cs2Op.IF_SETTEXTSHADOW, Cs2Op.CC_SETTEXTSHADOW -> mutate(component) { it.shadow = values.first() == 1 }
            Cs2Op.IF_SETMAXLINES, Cs2Op.CC_SETMAXLINES -> mutate(component) { it.maxTextLines = values.first() }

            Cs2Op.IF_SETHFLIP, Cs2Op.CC_SETHFLIP -> mutate(component) { it.flipHorizontal = values.first() == 1 }
            Cs2Op.IF_SETVFLIP, Cs2Op.CC_SETVFLIP -> mutate(component) { it.flipVertical = values.first() == 1 }
            Cs2Op.IF_SETTILING, Cs2Op.CC_SETTILING -> mutate(component) { it.tiling = values.first() == 1 }
            Cs2Op.IF_SETOUTLINE, Cs2Op.CC_SETOUTLINE -> mutate(component) { it.borderThickness = values.first() }
            Cs2Op.IF_SETGRAPHICSHADOW, Cs2Op.CC_SETGRAPHICSHADOW ->
                mutate(component) { it.graphicShadow = values.first() }
            Cs2Op.IF_SETLINEWID, Cs2Op.CC_SETLINEWID -> mutate(component) { it.lineWidth = values.first() }

            Cs2Op.IF_SETDRAGRENDERBEHAVIOUR, Cs2Op.CC_SETDRAGRENDERBEHAVIOUR ->
                mutate(component) { it.dragType = values.first() }

            Cs2Op.IF_SETDRAGGABLE, Cs2Op.CC_SETDRAGGABLE -> mutate(component) {
                it.depthChild = depthChildOf(values[0], values[1])
            }

            Cs2Op.IF_SETOP, Cs2Op.CC_SETOP ->
                mutate(component) { it.setOption(values.first() - 1, args.strings.firstOrNull() ?: "") }
            Cs2Op.IF_CLEAROPS, Cs2Op.CC_CLEAROPS -> mutate(component) { it.clearOptions() }
            Cs2Op.IF_SETOPBASE, Cs2Op.CC_SETOPBASE ->
                mutate(component) { it.opBase = args.strings.firstOrNull() ?: "" }
            Cs2Op.IF_SETOPCURSOR, Cs2Op.CC_SETOPCURSOR ->
                mutate(component) { it.setOptionCursor(values[0] - 1, values[1]) }
            Cs2Op.IF_SETMOUSEOVERCURSOR, Cs2Op.CC_SETMOUSEOVERCURSOR ->
                mutate(component) { it.hoverCursor = values.first() }
            Cs2Op.IF_SETTARGETOPCURSOR, Cs2Op.CC_SETTARGETOPCURSOR ->
                mutate(component) { it.targetCursor = values.first() }
            Cs2Op.IF_SETOPCHAR, Cs2Op.CC_SETOPCHAR ->
                mutate(component) { it.setOptionKeyChar(values[0] - 1, values[1]) }
            Cs2Op.IF_SETOPTCHAR, Cs2Op.CC_SETOPTCHAR ->
                mutate(component) { it.setOptionKeyChar(ComponentState.OPTION_COUNT, values.first()) }

            Cs2Op.IF_SETNOCLICKTHROUGH, Cs2Op.CC_SETNOCLICKTHROUGH ->
                mutate(component) { it.noClickThrough = values.first() == 1 }
            Cs2Op.IF_SETDRAGDEADZONE, Cs2Op.CC_SETDRAGDEADZONE ->
                mutate(component) { it.dragDeadzone = values.first() }
            Cs2Op.IF_SETDRAGDEADTIME, Cs2Op.CC_SETDRAGDEADTIME ->
                mutate(component) { it.dragDeadTime = values.first() }

            Cs2Op.IF_DRAGPICKUP, Cs2Op.CC_DRAGPICKUP -> {
                if (component != null) {
                    onDragPickup?.invoke(component, values.getOrElse(0) { 0 }, values.getOrElse(1) { 0 })
                }
                Cs2Values.EMPTY
            }

            Cs2Op.IF_CLEARSCRIPTHOOKS, Cs2Op.CC_CLEARSCRIPTHOOKS -> {
                component?.let {
                    boundHooks.remove(it.hash)
                    it.scripted = false
                    it.optionHook = it.definition.params != null
                }
                Cs2Values.EMPTY
            }

            Cs2Op.IF_GETWIDTH, Cs2Op.CC_GETWIDTH -> ints(component?.width ?: 0)
            Cs2Op.IF_GETHEIGHT, Cs2Op.CC_GETHEIGHT -> ints(component?.height ?: 0)
            Cs2Op.IF_GETX, Cs2Op.CC_GETX -> ints(component?.x ?: 0)
            Cs2Op.IF_GETY, Cs2Op.CC_GETY -> ints(component?.y ?: 0)
            Cs2Op.IF_GETHIDE, Cs2Op.CC_GETHIDE -> ints(if (component?.hidden == true) 1 else 0)
            Cs2Op.IF_GETTRANS, Cs2Op.CC_GETTRANS -> ints(component?.transparency ?: 0)
            Cs2Op.IF_GETCOLOUR, Cs2Op.CC_GETCOLOUR -> ints(component?.colour ?: 0)
            Cs2Op.IF_GETSCROLLX, Cs2Op.CC_GETSCROLLX -> ints(component?.scrollX ?: 0)
            Cs2Op.IF_GETSCROLLY, Cs2Op.CC_GETSCROLLY -> ints(component?.scrollY ?: 0)
            Cs2Op.IF_GETSCROLLWIDTH, Cs2Op.CC_GETSCROLLWIDTH -> ints(component?.scrollWidth ?: 0)
            Cs2Op.IF_GETSCROLLHEIGHT, Cs2Op.CC_GETSCROLLHEIGHT -> ints(component?.scrollHeight ?: 0)
            Cs2Op.IF_GETGRAPHIC, Cs2Op.CC_GETGRAPHIC -> ints(component?.graphicId ?: -1)
            Cs2Op.IF_GETTEXT, Cs2Op.CC_GETTEXT -> strings(component?.text ?: "")
            Cs2Op.IF_GETOP, Cs2Op.CC_GETOP -> strings(component?.options?.getOrNull(values.first() - 1) ?: "")
            Cs2Op.IF_GETOPBASE, Cs2Op.CC_GETOPBASE -> strings(component?.opBase ?: "")
            Cs2Op.IF_GETTARGETMASK, Cs2Op.CC_GETTARGETMASK -> ints(component?.useOptionFlags ?: 0)

            Cs2Op.GET_MOUSEX -> ints(mouseX)
            Cs2Op.GET_MOUSEY -> ints(mouseY)
            Cs2Op.GET_MOUSEBUTTONS -> ints(
                if (mouseLeft) 1 else 0,
                if (mouseRight) 1 else 0,
                if (mouseMiddle) 1 else 0,
            )

            Cs2Op.KEYHELD_ALT -> ints(if (heldKeys.contains(KEY_ALT)) 1 else 0)
            Cs2Op.KEYHELD_CTRL -> ints(if (heldKeys.contains(KEY_CTRL)) 1 else 0)
            Cs2Op.KEYHELD_SHIFT -> ints(if (heldKeys.contains(KEY_SHIFT)) 1 else 0)

            Cs2Op.STAT, Cs2Op.STAT_BASE -> ints(stat(args.ints.firstOrNull() ?: -1))

            Cs2Op.PARAHEIGHT -> ints(
                paragraphLines?.invoke(
                    args.strings.firstOrNull() ?: "",
                    args.ints.getOrElse(0) { 0 },
                    args.ints.getOrElse(1) { -1 },
                ) ?: 1,
            )

            Cs2Op.ENUM -> enumLookup(args.ints)
            Cs2Op.ENUM_STRING -> strings(enums(args.ints.firstOrNull() ?: -1)?.getString(args.ints.getOrElse(1) { 0 }) ?: "")
            Cs2Op.ENUM_GETOUTPUTCOUNT -> ints(enums(args.ints.firstOrNull() ?: -1)?.length ?: 0)
            Cs2Op.STRUCT_PARAM -> structParam(args.ints)

            Cs2Op.MES, Cs2Op.WRITECONSOLE -> {
                args.strings.forEach { log.add(it) }
                Cs2Values.EMPTY
            }

            else -> Cs2Host.defaults(op)
        }
    }

    private fun runtimeComponentOp(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values? {
        val slot = if (operand == 1) 1 else 0
        return when (op) {
            Cs2Op.CC_CREATE -> {
                val container = args.ints.getOrElse(0) { -1 }
                val child = instanceOf(container)?.let { instance ->
                    val parent = instance.stateByHash(container) ?: return@let null
                    instance.createChild(parent.index, args.ints.getOrElse(1) { 0 }, args.ints.getOrElse(2) { 0 })
                }
                if (child == null) {
                    log.add("cc_create failed on ${args.ints}")
                } else {
                    boundHooks.remove(child.hash)
                    activeComponents[slot] = child
                    onChanged?.invoke()
                }
                Cs2Values.EMPTY
            }

            Cs2Op.CC_DELETEALL -> {
                val container = args.ints.getOrElse(0) { -1 }
                instanceOf(container)?.let { instance ->
                    instance.stateByHash(container)?.let { instance.deleteChildren(it.index) }
                }
                onChanged?.invoke()
                Cs2Values.EMPTY
            }

            Cs2Op.CC_DELETE -> {
                activeComponents[slot]?.let { target ->
                    instanceOf(target.hash)?.deleteChild(target)
                    activeComponents[slot] = null
                    onChanged?.invoke()
                }
                Cs2Values.EMPTY
            }

            Cs2Op.IF_GETNEXTSUBID -> {
                val container = args.ints.getOrElse(0) { -1 }
                val count = instanceOf(container)?.let { instance ->
                    instance.stateByHash(container)?.let { instance.slotCount(it.index) }
                }
                ints(count ?: 0)
            }

            Cs2Op.IF_FIND -> {
                val found = componentOf(args.ints.getOrElse(0) { -1 })
                activeComponents[slot] = found
                ints(if (found == null) 0 else 1)
            }

            Cs2Op.CC_FIND -> {
                val container = args.ints.getOrElse(0) { -1 }
                val childSlot = args.ints.getOrElse(1) { -1 }
                val found = instanceOf(container)?.let { instance ->
                    val parent = instance.stateByHash(container) ?: return@let null
                    if (childSlot == -1) parent else instance.slotChild(parent.index, childSlot)
                }
                activeComponents[slot] = found
                ints(if (found == null) 0 else 1)
            }

            Cs2Op.IF_GETLAYER -> {
                val hash = args.ints.getOrElse(0) { -1 }
                val parent = instanceOf(hash)?.let { instance ->
                    instance.stateByHash(hash)?.let { instance.parentOf(it) }
                }
                ints(parent?.hash ?: -1)
            }

            Cs2Op.IF_GETPARENTLAYER -> {
                val hash = args.ints.getOrElse(0) { -1 }
                val parent = instanceOf(hash)?.let { instance ->
                    instance.stateByHash(hash)?.let { instance.enclosing(it) }
                }
                ints(parent?.hash ?: -1)
            }

            else -> null
        }
    }

    private fun componentOf(hash: Int): ComponentState? =
        interfaces(hash shr 16)?.stateByHash(hash)

    private fun instanceOf(hash: Int): InterfaceInstance? = interfaces(hash shr 16)

    private fun depthChildOf(hash: Int, slot: Int): ComponentState? =
        instanceOf(hash)?.slotChildByHash(hash, slot)

    private fun mutate(component: ComponentState?, change: (ComponentState) -> Unit): Cs2Values {
        if (component != null) {
            change(component)
            onChanged?.invoke()
        }
        return Cs2Values.EMPTY
    }

    private fun reshape(component: ComponentState?, change: (ComponentState) -> Unit): Cs2Values {
        if (component != null) {
            change(component)
            instanceOf(component.hash)?.resolve(component)
            onChanged?.invoke()
        }
        return Cs2Values.EMPTY
    }

    private fun ints(vararg values: Int) = Cs2Values(ints = values.toList())

    private fun strings(vararg values: String) = Cs2Values(strings = values.toList())

    private fun enumLookup(args: List<Int>): Cs2Values {
        if (args.size < 4) return Cs2Values.EMPTY
        val definition = enums(args[2]) ?: return if (args[1] == 's'.code) strings("") else ints(-1)
        val key = args[3]
        return if (args[1] == 's'.code) strings(definition.getString(key) ?: "") else ints(definition.getInt(key))
    }

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
        val activeTarget = op.name.startsWith("CC_")
        val state = if (activeTarget) activeComponents[0] else componentOf(component)
        val target = if (activeTarget) state?.hash ?: return else component
        val event = eventSuffix(op.name)
        val events = boundHooks.getOrPut(target) { LinkedHashMap() }
        if (hook == null) events.remove(event) else events[event] = hook
        if (hook != null) {
            state?.scripted = true
        } else if (events.isEmpty()) {
            state?.scripted = false
        }
        if (event == InterfaceEvent.OPTION.boundKey) {
            state?.optionHook = hook != null || state.definition.params != null
        }
    }

    val stats: MutableMap<Int, Int> = LinkedHashMap()

    var defaultStat: Int = 0

    fun stat(id: Int): Int = stats[id] ?: defaultStat

    fun clearStats() {
        stats.clear()
        defaultStat = 0
    }

    fun variables(space: VarSpace): Map<Int, Int> = when (space) {
        VarSpace.VARP -> varps
        VarSpace.VARBIT -> varbits
        VarSpace.VARC -> varcs
        else -> emptyMap()
    }

    fun variableStrings(): Map<Int, String> = varcStrings

    fun clearVariables() {
        varps.clear()
        varbits.clear()
        varcs.clear()
        varcStrings.clear()
    }

    override fun varp(id: Int) = varps[id] ?: 0

    override fun setVarp(id: Int, value: Int) {
        varps[id] = value
        onChanged?.invoke()
        onVarpWritten?.invoke(id)
    }

    override fun varbit(id: Int) = varbits[id] ?: 0

    override fun setVarbit(id: Int, value: Int) {
        varbits[id] = value
        onChanged?.invoke()
        onVarpWritten?.invoke(id)
    }

    override fun varc(id: Int) = varcs[id] ?: 0

    override fun setVarc(id: Int, value: Int) {
        varcs[id] = value
        onVarcWritten?.invoke(id)
    }

    override fun varcString(id: Int) = varcStrings[id] ?: ""

    override fun setVarcString(id: Int, value: String) {
        varcStrings[id] = value
        onVarcStringWritten?.invoke(id)
    }

    override fun onError(message: String) {
        log.add("error: $message")
    }

    companion object {
        const val KEY_SHIFT = 81

        const val KEY_CTRL = 82

        const val KEY_ALT = 86

        fun eventSuffix(name: String): String = name
            .removePrefix("CC_")
            .removePrefix("IF_")
            .removePrefix("SETON")

        val HOOK_SUFFIX_BY_FIELD: Map<String, String> = mapOf(
            "onMouseOver" to "MOUSEOVER",
            "onMouseLeaveScript" to "MOUSELEAVE",
            "onClick" to "CLICK",
            "popupScript" to "MOUSEREPEAT",
            "mouseLeaveScript" to "INVTRANSMIT",
            "onClickRepeat" to "CLICKREPEAT",
            "onRelease" to "RELEASE",
            "onHold" to "HOLD",
            "onDrag" to "DRAG",
            "onDragComplete" to "DRAGCOMPLETE",
            "onMouseMove" to "SCROLLWHEEL",
            "onKey" to "VARCTRANSMIT",
            "onScrollWheel" to "VARCSTRTRANSMIT",
            "onVarpTransmit" to "VARTRANSMIT",
            "onStatTransmit" to "STATTRANSMIT",
            "onTimer" to "TIMER",
            "onTargetEnter" to "TARGETENTER",
            "onTargetLeave" to "OPT",
            "hookParams" to "TARGETLEAVE",
            "params" to "OP",
            "onKeyType" to "KEY",
        )
    }
}
