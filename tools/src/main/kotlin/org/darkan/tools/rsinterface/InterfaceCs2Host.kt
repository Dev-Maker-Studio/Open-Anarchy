package org.darkan.tools.rsinterface

import org.darkan.cs2.Cs2Op
import org.darkan.cs2.ir.VarSpace
import org.darkan.cs2.vm.Cs2Hook
import org.darkan.cs2.vm.Cs2Host
import org.darkan.cs2.vm.Cs2Values
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

    /**
     * Hooks the running scripts have bound, keyed by component hash then by the
     * event suffix of the opcode that bound them - `IF_SETONCLICK` stores under
     * `CLICK`, `HOOK_MOUSE_ENTER` under `MOUSE_ENTER`.
     *
     * The suffix is not the hook's meaning. Use [boundHook] to look one up by the
     * definition field it corresponds to; [HOOK_SUFFIX_BY_FIELD] documents the
     * pairing and where it came from.
     */
    val boundHooks: MutableMap<Int, MutableMap<String, Cs2Hook>> = LinkedHashMap()

    /** Messages a script produced, shown in the editor. */
    val log: MutableList<String> = ArrayList()

    var onChanged: (() -> Unit)? = null

    /**
     * Notified after a script writes a varp or varbit, with the varp id.
     *
     * `IF_SETONVARTRANSMIT` hooks fire off varp writes, so something has to turn a
     * write into a dispatch. The host is the only place that sees every write, but
     * it knows nothing about components, so it hands the id out and lets the
     * interaction runtime decide who cares.
     */
    var onVarpWritten: ((Int) -> Unit)? = null

    /** Notified after a script writes a client int var, with the varc id. */
    var onVarcWritten: ((Int) -> Unit)? = null

    /** Notified after a script writes a client string var, with the varc id. */
    var onVarcStringWritten: ((Int) -> Unit)? = null

    /**
     * Asked to start a drag of a component from a grab offset inside it, for
     * `IF_DRAGPICKUP` / `CC_DRAGPICKUP`.
     *
     * The host knows nothing about pointer state, so the interaction runtime supplies
     * this; when nothing is listening the opcode is a no-op, exactly as an
     * unimplemented one would be.
     */
    var onDragPickup: ((ComponentState, Int, Int) -> Unit)? = null

    // --- Live input state, answered by the query opcodes -------------------

    /** Pointer position in the interface's own coordinates; `GET_MOUSEX`/`GET_MOUSEY`. */
    var mouseX: Int = -1
    var mouseY: Int = -1

    /** Mouse button state answered by `GET_MOUSEBUTTONS`. */
    var mouseLeft: Boolean = false
    var mouseRight: Boolean = false
    var mouseMiddle: Boolean = false

    /** Client key codes currently held, answered by the `KEYHELD_*` opcodes. */
    val heldKeys: MutableSet<Int> = LinkedHashSet()

    // `PARAHEIGHT`. Scripts size a row from it, so the 0 an unimplemented opcode answers leaves
    // every wrapped label present but zero pixels tall.
    var paragraphLines: ((String, Int, Int) -> Int)? = null

    fun setActive(slot: Int, component: ComponentState?) {
        activeComponents[slot.coerceIn(0, 1)] = component
    }

    fun clearHooks() {
        boundHooks.clear()
    }

    /**
     * The hook a script bound for the definition field [field] on [hash], or null.
     *
     * Callers should prefer this over indexing [boundHooks] directly: it takes the
     * same key `InterfaceComponentDefinition.hooks()` uses, so a runtime-bound
     * hook and a cache-defined one are looked up by the same name.
     */
    fun boundHook(hash: Int, field: String): Cs2Hook? {
        val suffix = HOOK_SUFFIX_BY_FIELD[field] ?: return null
        return boundHooks[hash]?.get(suffix)
    }

    override fun invoke(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values {
        val name = op.opName
        // The runtime-component opcodes name their target by hash *and* move the
        // active register, so neither half of the split below fits them. They are
        // resolved first and the rest of this method never sees them.
        runtimeComponentOp(name, operand, args)?.let { return it }

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
            "IF_SETGRAPHIC", "CC_SETGRAPHIC" -> mutate(component) { it.graphicId = values.first() }
            "IF_SETTEXT", "CC_SETTEXT" -> mutate(component) { it.text = args.strings.firstOrNull() ?: "" }
            // `*_SETFILL` is the name the client's opcode table uses; the `*_SETFILLED`
            // spelling is kept so a rename in the opcode table cannot silently drop
            // the handler.
            "IF_SETFILLED", "CC_SETFILLED", "IF_SETFILL", "CC_SETFILL" ->
                mutate(component) { it.filled = values.first() != 0 }
            // These three re-resolve the component's box straight away; see
            // [InterfaceInstance.resolve] for why waiting for the next frame breaks
            // every script that builds a list.
            "IF_SETSIZE", "CC_SETSIZE" ->
                reshape(component) { it.setSize(values[0], values[1], values[2], values[3]) }
            "IF_SETPOSITION", "CC_SETPOSITION" ->
                reshape(component) { it.setPosition(values[0], values[1], values[2], values[3]) }
            "IF_SETASPECT", "CC_SETASPECT" -> reshape(component) { it.setAspect(values[0], values[1]) }
            "IF_SETSCROLLSIZE", "CC_SETSCROLLSIZE" ->
                mutate(component) { it.setScrollSize(values[0], values[1]) }
            "IF_SETSCROLLPOS", "CC_SETSCROLLPOS" ->
                mutate(component) { it.setScrollPos(values[0], values[1]) }
            "IF_SETOBJECT", "CC_SETOBJECT" ->
                mutate(component) { it.setObject(values[0], values[1]) }
            "IF_SETMODEL", "CC_SETMODEL" -> mutate(component) { it.setModel(values[0]) }
            "IF_SETMODELANGLE", "CC_SETMODELANGLE" ->
                mutate(component) { it.setModelAngle(values[0], values[1], values[2], values[3]) }
            "IF_SETTEXTALIGN", "CC_SETTEXTALIGN" ->
                mutate(component) { it.setTextAlign(values[0], values[1], values[2]) }
            "IF_SETTEXTFONT", "CC_SETTEXTFONT" -> mutate(component) { it.fontId = values.first() }
            "IF_SETTEXTSHADOW", "CC_SETTEXTSHADOW" -> mutate(component) { it.shadow = values.first() == 1 }
            "IF_SETMAXLINES", "CC_SETMAXLINES" -> mutate(component) { it.maxTextLines = values.first() }

            "IF_SETHFLIP", "CC_SETHFLIP" -> mutate(component) { it.flipHorizontal = values.first() == 1 }
            "IF_SETVFLIP", "CC_SETVFLIP" -> mutate(component) { it.flipVertical = values.first() == 1 }
            "IF_SETTILING", "CC_SETTILING" -> mutate(component) { it.tiling = values.first() == 1 }
            "IF_SETOUTLINE", "CC_SETOUTLINE" -> mutate(component) { it.borderThickness = values.first() }
            "IF_SETGRAPHICSHADOW", "CC_SETGRAPHICSHADOW" ->
                mutate(component) { it.graphicShadow = values.first() }
            "IF_SETLINEWID", "CC_SETLINEWID" -> mutate(component) { it.lineWidth = values.first() }

            "IF_SETDRAGRENDERBEHAVIOUR", "CC_SETDRAGRENDERBEHAVIOUR" ->
                mutate(component) { it.dragType = values.first() }

            // Opcode 68/899. The generated table calls it `SETDRAGGABLE` after the
            // official name list; the client calls it `SETDEPTHCHILD` and it assigns
            // `Component.depthChildOverride` from a (component hash, slot) pair. It has
            // nothing to do with the drag dead zone or dead time - those are the two
            // opcodes below - and reading it that way is what left every scrollbar
            // thumb undraggable: the override is the only thing that makes a
            // runtime-created component pass the client's `hasDepthFlags`.
            "IF_SETDRAGGABLE", "CC_SETDRAGGABLE" -> mutate(component) {
                it.depthChild = depthChildOf(values[0], values[1])
            }

            // Options are addressed 1-based by every opcode that touches them.
            "IF_SETOP", "CC_SETOP" ->
                mutate(component) { it.setOption(values.first() - 1, args.strings.firstOrNull() ?: "") }
            "IF_CLEAROPS", "CC_CLEAROPS" -> mutate(component) { it.clearOptions() }
            "IF_SETOPBASE", "CC_SETOPBASE" ->
                mutate(component) { it.opBase = args.strings.firstOrNull() ?: "" }
            "IF_SETOPCURSOR", "CC_SETOPCURSOR" ->
                mutate(component) { it.setOptionCursor(values[0] - 1, values[1]) }
            "IF_SETMOUSEOVERCURSOR", "CC_SETMOUSEOVERCURSOR" ->
                mutate(component) { it.hoverCursor = values.first() }
            "IF_SETTARGETOPCURSOR", "CC_SETTARGETOPCURSOR" ->
                mutate(component) { it.targetCursor = values.first() }
            "IF_SETOPCHAR", "CC_SETOPCHAR" ->
                mutate(component) { it.setOptionKeyChar(values[0] - 1, values[1]) }
            "IF_SETOPTCHAR", "CC_SETOPTCHAR" ->
                mutate(component) { it.setOptionKeyChar(ComponentState.OPTION_COUNT, values.first()) }

            "IF_SETNOCLICKTHROUGH", "CC_SETNOCLICKTHROUGH" ->
                mutate(component) { it.noClickThrough = values.first() == 1 }
            "IF_SETDRAGDEADZONE", "CC_SETDRAGDEADZONE" ->
                mutate(component) { it.dragDeadzone = values.first() }
            "IF_SETDRAGDEADTIME", "CC_SETDRAGDEADTIME" ->
                mutate(component) { it.dragDeadTime = values.first() }

            // `CC_DRAGPICKUP` starts a drag of the active component from a grab offset
            // the script chooses, rather than from wherever the pointer happens to be.
            // A scrollbar's track uses it to hand the drag to the thumb, which is how
            // clicking the gutter pages the list.
            "IF_DRAGPICKUP", "CC_DRAGPICKUP" -> {
                if (component != null) {
                    onDragPickup?.invoke(component, values.getOrElse(0) { 0 }, values.getOrElse(1) { 0 })
                }
                Cs2Values.EMPTY
            }

            "IF_CLEARSCRIPTHOOKS", "CC_CLEARSCRIPTHOOKS" -> {
                component?.let {
                    boundHooks.remove(it.hash)
                    it.scripted = false
                    it.optionHook = it.definition.params != null
                }
                Cs2Values.EMPTY
            }

            "IF_GETWIDTH", "CC_GETWIDTH" -> ints(component?.width ?: 0)
            "IF_GETHEIGHT", "CC_GETHEIGHT" -> ints(component?.height ?: 0)
            // The client returns `Component.x`/`.y`, which is the position the layout
            // pass computed *relative to the parent's content box* - not a screen
            // coordinate. Scripts feed the value straight back to `IF_SETPOSITION`
            // with position mode 0, so answering with [ComponentState.screenX] moves
            // the component by the whole interface's origin every time one asks.
            "IF_GETX", "CC_GETX" -> ints(component?.x ?: 0)
            "IF_GETY", "CC_GETY" -> ints(component?.y ?: 0)
            "IF_GETHIDE", "CC_GETHIDE" -> ints(if (component?.hidden == true) 1 else 0)
            "IF_GETTRANS", "CC_GETTRANS" -> ints(component?.transparency ?: 0)
            "IF_GETCOLOUR", "CC_GETCOLOUR" -> ints(component?.colour ?: 0)
            "IF_GETSCROLLX", "CC_GETSCROLLX" -> ints(component?.scrollX ?: 0)
            "IF_GETSCROLLY", "CC_GETSCROLLY" -> ints(component?.scrollY ?: 0)
            "IF_GETSCROLLWIDTH", "CC_GETSCROLLWIDTH" -> ints(component?.scrollWidth ?: 0)
            "IF_GETSCROLLHEIGHT", "CC_GETSCROLLHEIGHT" -> ints(component?.scrollHeight ?: 0)
            "IF_GETGRAPHIC", "CC_GETGRAPHIC" -> ints(component?.graphicId ?: -1)
            "IF_GETTEXT", "CC_GETTEXT" -> strings(component?.text ?: "")
            "IF_GETOP", "CC_GETOP" -> strings(component?.options?.getOrNull(values.first() - 1) ?: "")
            "IF_GETOPBASE", "CC_GETOPBASE" -> strings(component?.opBase ?: "")
            "IF_GETTARGETMASK", "CC_GETTARGETMASK" -> ints(component?.useOptionFlags ?: 0)

            "GET_MOUSEX" -> ints(mouseX)
            "GET_MOUSEY" -> ints(mouseY)
            "GET_MOUSEBUTTONS" -> ints(
                if (mouseLeft) 1 else 0,
                if (mouseRight) 1 else 0,
                if (mouseMiddle) 1 else 0,
            )

            // The opcode names disagree with the key codes the client checks, and
            // the codes win: 86 is Alt, 82 is Ctrl and 81 is Shift in the client's
            // key table, so KEYHELD_CTRL really reports Alt. Kept verbatim so a
            // script behaves the way it does in the client.
            "KEYHELD_CTRL" -> ints(if (heldKeys.contains(KEY_ALT)) 1 else 0)
            "KEYHELD_SHIFT" -> ints(if (heldKeys.contains(KEY_CTRL)) 1 else 0)
            "KEYHELD_ALT" -> ints(if (heldKeys.contains(KEY_SHIFT)) 1 else 0)

            "STAT", "STAT_BASE" -> ints(stat(args.ints.firstOrNull() ?: -1))

            "PARAHEIGHT" -> ints(
                paragraphLines?.invoke(
                    args.strings.firstOrNull() ?: "",
                    args.ints.getOrElse(0) { 0 },
                    args.ints.getOrElse(1) { -1 },
                ) ?: 1,
            )

            "ENUM" -> enumLookup(args.ints)
            "ENUM_STRING" -> strings(enums(args.ints.firstOrNull() ?: -1)?.getString(args.ints.getOrElse(1) { 0 }) ?: "")
            "ENUM_GETOUTPUTCOUNT" -> ints(enums(args.ints.firstOrNull() ?: -1)?.length ?: 0)
            "STRUCT_PARAM" -> structParam(args.ints)

            "MES", "WRITECONSOLE" -> {
                args.strings.forEach { log.add(it) }
                Cs2Values.EMPTY
            }

            else -> Cs2Host.defaults(op)
        }
    }

    /**
     * The opcodes that create, destroy and re-target runtime components, or null
     * when [name] is not one of them.
     *
     * A great many RS widgets are not in the cache at all: the cache holds an empty
     * layer and a load script fills it with `CC_CREATE` children - the nine graphics
     * of a dropdown's border, the six parts of a scrollbar, one text child per
     * dropdown row. Without these opcodes the layer stays empty and every `CC_*`
     * call after the create lands on the layer itself, which is why an
     * unimplemented `CC_CREATE` shows up as a widget that is not merely unstyled
     * but absent.
     *
     * All of them address a container by packed hash and most also move the active
     * `CC_*` register, so they cannot go through the hash-or-register split in
     * [invoke]. The register is chosen by the instruction's operand, exactly as the
     * client picks between its two `CS2Interface` slots.
     */
    private fun runtimeComponentOp(name: String, operand: Int, args: Cs2Values): Cs2Values? {
        val slot = if (operand == 1) 1 else 0
        return when (name) {
            "CC_CREATE" -> {
                val container = args.ints.getOrElse(0) { -1 }
                val child = instanceOf(container)?.let { instance ->
                    val parent = instance.stateByHash(container) ?: return@let null
                    instance.createChild(parent.index, args.ints.getOrElse(1) { 0 }, args.ints.getOrElse(2) { 0 })
                }
                if (child == null) {
                    log.add("cc_create failed on ${args.ints}")
                } else {
                    // The synthetic index may have been recycled from a deleted
                    // component, and a brand new component has no callbacks.
                    boundHooks.remove(child.hash)
                    activeComponents[slot] = child
                    onChanged?.invoke()
                }
                Cs2Values.EMPTY
            }

            // Despite the `CC_` prefix this one names its container by hash rather
            // than using the active register, as the client's `clearCompChildren` does.
            "CC_DELETEALL" -> {
                val container = args.ints.getOrElse(0) { -1 }
                instanceOf(container)?.let { instance ->
                    instance.stateByHash(container)?.let { instance.deleteChildren(it.index) }
                }
                onChanged?.invoke()
                Cs2Values.EMPTY
            }

            "CC_DELETE" -> {
                activeComponents[slot]?.let { target ->
                    instanceOf(target.hash)?.deleteChild(target)
                    activeComponents[slot] = null
                    onChanged?.invoke()
                }
                Cs2Values.EMPTY
            }

            // The opcode table calls this `IF_GETNEXTSUBID`; the client calls it
            // `IF_GETSUBCOUNT` and counts the filled slots. Both descriptions agree,
            // because the count is also the index of the next free slot, which is
            // exactly what every caller passes straight to `CC_CREATE`.
            "IF_GETNEXTSUBID" -> {
                val container = args.ints.getOrElse(0) { -1 }
                val count = instanceOf(container)?.let { instance ->
                    instance.stateByHash(container)?.let { instance.slotCount(it.index) }
                }
                ints(count ?: 0)
            }

            // `IF_FIND` / `CC_FIND` are the client's `CC_SETCHILD` / `CC_SETCHILD_SLOT`:
            // they point the active register at a component so the `CC_*` setters can
            // work on something other than the last thing created.
            "IF_FIND" -> {
                val found = componentOf(args.ints.getOrElse(0) { -1 })
                activeComponents[slot] = found
                ints(if (found == null) 0 else 1)
            }

            "CC_FIND" -> {
                val container = args.ints.getOrElse(0) { -1 }
                val childSlot = args.ints.getOrElse(1) { -1 }
                val found = instanceOf(container)?.let { instance ->
                    val parent = instance.stateByHash(container) ?: return@let null
                    if (childSlot == -1) parent else instance.slotChild(parent.index, childSlot)
                }
                activeComponents[slot] = found
                ints(if (found == null) 0 else 1)
            }

            // The client returns `Component.parentId`, which is the parent's packed
            // hash and -1 at the root of the interface.
            "IF_GETLAYER" -> {
                val hash = args.ints.getOrElse(0) { -1 }
                val parent = instanceOf(hash)?.let { instance ->
                    instance.stateByHash(hash)?.let { instance.parentOf(it) }
                }
                ints(parent?.hash ?: -1)
            }

            // Not the same question as `IF_GETLAYER`: this one crosses out of the
            // component's own interface into whatever container mounts it, which is
            // how a script walks all the way up to the game frame to test whether a
            // component is really on screen. It has to bottom out at -1, because a
            // script that walks with it loops forever otherwise.
            "IF_GETPARENTLAYER" -> {
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

    /**
     * The interface owning [hash], resolved the same way [componentOf] does: by the
     * hash's high half. A component reached only through a mounted sub-interface is
     * not found, matching the existing lookup rather than diverging from it.
     */
    private fun instanceOf(hash: Int): InterfaceInstance? = interfaces(hash shr 16)

    /**
     * The component a (hash, slot) pair addresses, as the client's
     * `Component.listIcomponent` resolves it.
     */
    private fun depthChildOf(hash: Int, slot: Int): ComponentState? =
        instanceOf(hash)?.slotChildByHash(hash, slot)

    private fun mutate(component: ComponentState?, change: (ComponentState) -> Unit): Cs2Values {
        if (component != null) {
            change(component)
            onChanged?.invoke()
        }
        return Cs2Values.EMPTY
    }

    /**
     * [mutate] for the opcodes that move the box model, which additionally resolve
     * the component's new size and position immediately. The very next instruction
     * is routinely `CC_GETHEIGHT` on the same component, and it has to see the size
     * the script just set rather than the one from before it.
     */
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
        val state = if (op.opName.startsWith("CC_")) activeComponents[0] else componentOf(component)
        val target = if (op.opName.startsWith("CC_")) state?.hash ?: return else component
        val event = eventSuffix(op.opName)
        val events = boundHooks.getOrPut(target) { LinkedHashMap() }
        if (hook == null) events.remove(event) else events[event] = hook
        // Mark the component itself so the interaction runtime's interactive test
        // stays a field read. A component built by CC_CREATE has a blank definition,
        // so this flag is the only evidence it carries any behaviour at all.
        if (hook != null) {
            state?.scripted = true
        } else if (events.isEmpty()) {
            state?.scripted = false
        }
        // An op hook additionally makes the component's options offerable, so it needs
        // recording where [ComponentState.optionAt] can see it. Falls back to the cache
        // hook rather than to false, because unbinding the script's hook leaves whatever
        // the definition declared still in place.
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
        /** Client key code for Shift, from the client's `REMAPPED_KEYS` table. */
        const val KEY_SHIFT = 81

        /** Client key code for Ctrl. */
        const val KEY_CTRL = 82

        /** Client key code for Alt. */
        const val KEY_ALT = 86

        /**
         * The event suffix [setHook] files an opcode under: the opcode name minus
         * its `IF_`/`CC_` form prefix. `CC_HOOK_MOUSE_ENTER` and `HOOK_MOUSE_ENTER`
         * both reduce to `MOUSE_ENTER`, which is what makes the two forms of a hook
         * setter interchangeable.
         */
        fun eventSuffix(opName: String): String = opName
            .removePrefix("CC_")
            .removePrefix("IF_")
            .removePrefix("SETON")
            .removePrefix("HOOK_")

        /**
         * Definition hook field to the event suffix of the opcode that overrides it.
         *
         * Recovered by following each `IF_SETON*` opcode through the client's
         * dispatch table to the `Component` field it assigns. The pairing is not
         * guessable from the names: `IF_SETONMOUSEOVER` writes `popupScript` while
         * the field actually called `onMouseOver` is written by `HOOK_MOUSE_ENTER`,
         * and `IF_SETONINVTRANSMIT` writes the field the cache decoder calls
         * `mouseLeaveScript` rather than the similarly named `onMouseLeaveScript`.
         * The definition field is the stable identity, so it is the key.
         */
        val HOOK_SUFFIX_BY_FIELD: Map<String, String> = mapOf(
            "onMouseOver" to "MOUSE_ENTER",
            "onMouseLeaveScript" to "MOUSE_EXIT",
            "onClick" to "CLICK",
            "popupScript" to "MOUSEOVER",
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
            "onTargetLeave" to "TARGETLEAVE",
            "hookParams" to "OP",
            "params" to "OPT",
            // The cache has no field for typed keys; `IF_SETONKEY` is the only way
            // to bind one, so the client field name is carried here for symmetry.
            "onKeyType" to "KEY",
        )
    }
}
