package org.darkan.tools.rsinterface

import org.darkan.cs2.vm.Cs2Event
import org.darkan.cs2.vm.Cs2HookDispatcher
import org.lwjgl.glfw.GLFW
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook

enum class InterfaceEvent(
    val field: String?,
    val boundKey: String,
    private val hookAccessor: (InterfaceComponentDefinition) -> InterfaceComponentHook?,
    internal val transmitFilter: ((InterfaceComponentDefinition) -> IntArray?)? = null,
) {
    LOAD("onLoadScript", "LOAD", { it.onLoadScript }),

    MOUSE_ENTER("onMouseOver", "MOUSEOVER", { it.onMouseOver }),

    MOUSE_OVER("popupScript", "MOUSEREPEAT", { it.popupScript }),

    MOUSE_EXIT("onMouseLeaveScript", "MOUSELEAVE", { it.onMouseLeaveScript }),

    MOUSE_PRESS("onClick", "CLICK", { it.onClick }),

    CLICK_REPEAT("onClickRepeat", "CLICKREPEAT", { it.onClickRepeat }),

    RELEASE("onRelease", "RELEASE", { it.onRelease }),

    HOLD("onHold", "HOLD", { it.onHold }),

    DRAG("onDrag", "DRAG", { it.onDrag }),

    DRAG_COMPLETE("onDragComplete", "DRAGCOMPLETE", { it.onDragComplete }),

    MOUSE_WHEEL("onMouseMove", "SCROLLWHEEL", { it.onMouseMove }),

    OPTION("params", "OP", { it.params }),

    TARGET_ENTER("onTargetEnter", "TARGETENTER", { it.onTargetEnter }),

    TARGET_LEAVE("onTargetLeave", "OPT", { it.onTargetLeave }),

    DESELECT("hookParams", "TARGETLEAVE", { it.hookParams }),

    TIMER("onTimer", "TIMER", { it.onTimer }),

    VARP_TRANSMIT("onVarpTransmit", "VARTRANSMIT", { it.onVarpTransmit }, { it.varps }),

    STAT_TRANSMIT(
        "onStatTransmit",
        "STATTRANSMIT",
        { it.onStatTransmit },
        { it.statTransmitFilter },
    ),

    INV_TRANSMIT(
        "mouseLeaveScript",
        "INVTRANSMIT",
        { it.mouseLeaveScript },
        { it.mouseLeaveArrayParams },
    ),

    VARC_TRANSMIT("onKey", "VARCTRANSMIT", { it.onKey }, { it.keyPressArray }),

    VARC_STRING_TRANSMIT(
        "onScrollWheel",
        "VARCSTRTRANSMIT",
        { it.onScrollWheel },
        { it.mouseWheelArray },
    ),

    KEY("onKeyType", "KEY", { null }),
    ;

    fun definitionHook(definition: InterfaceComponentDefinition): InterfaceComponentHook? =
        hookAccessor(definition)
}

enum class OptionKind {
    OPTION,

    USE,

    CONTINUE,
}

data class ComponentOption(
    val component: ComponentState,
    val option: Int,
    val label: String,
    val kind: OptionKind = OptionKind.OPTION,
    val slot: Int = -1,
    val cursor: Int = -1,
) {
    val text: String get() = if (component.opBase.isBlank()) label else "$label ${component.opBase}"
}

data class InterfaceViewport(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(pointX: Int, pointY: Int): Boolean =
        pointX >= x && pointY >= y && pointX < x + width && pointY < y + height
}

data class InterfacePointer(
    val x: Int,
    val y: Int,
    val left: Boolean = false,
    val right: Boolean = false,
    val middle: Boolean = false,
    val wheel: Int = 0,
)

class InterfaceDrag internal constructor(
    val source: ComponentState,
    val offsetX: Int,
    val offsetY: Int,
    val bounds: ComponentState? = null,
) {
    var target: ComponentState? = null
        internal set

    var targetSlot: Int = -1
        internal set

    var sourceSlot: Int = -1
        internal set

    var active: Boolean = false
        internal set

    var pointerX: Int = 0
        internal set
    var pointerY: Int = 0
        internal set

    internal var cycles: Int = 0

    internal var startX: Int = 0
    internal var startY: Int = 0
}

data class InteractionTrace(
    val event: InterfaceEvent,
    val component: Int,
    val hash: Int,
    val script: Int,
    val bound: Boolean,
    val error: String? = null,
)

class InterfaceInteraction(
    private val instances: () -> InterfaceInstance?,
    private val dispatcher: Cs2HookDispatcher,
    private val host: InterfaceCs2Host,
) {

    var liveMode: Boolean = true

    var clickOnRelease: Boolean = false

    var directScroll: Boolean = true

    var scrollStep: Int = DEFAULT_SCROLL_STEP

    var slotResolver: (ComponentState, Int, Int) -> Int = { component, localX, localY ->
        component.slotAt(localX, localY)
    }

    val trace: List<InteractionTrace> get() = traceLog

    var traceCapacity: Int = DEFAULT_TRACE_CAPACITY

    val hovered: List<ComponentState> get() = hoverChain

    val pressedChain: List<ComponentState> get() = pressChain

    val pressed: ComponentState?
        get() = pressChain.lastOrNull { it.interactive && it.type != ComponentType.CONTAINER }
            ?: pressChain.lastOrNull()

    var pressMillis: Long = 0
        private set

    var focused: ComponentState? = null
        private set

    var drag: InterfaceDrag? = null
        private set

    var selected: ComponentState? = null
        private set

    var selectedSlot: Int = -1
        private set

    val menu: List<ComponentOption> get() = menuEntries

    val menuOpen: Boolean get() = menuEntries.isNotEmpty()

    var menuX: Int = 0
        private set
    var menuY: Int = 0
        private set

    var viewport: InterfaceViewport = InterfaceViewport(0, 0, 0, 0)
        private set

    var pointer: InterfacePointer = InterfacePointer(-1, -1)
        private set

    var pointerMoved: Boolean = false
        private set

    private val traceLog = ArrayList<InteractionTrace>()
    private var hoverChain: List<ComponentState> = emptyList()
    private var pressChain: List<ComponentState> = emptyList()
    private var menuEntries: List<ComponentOption> = emptyList()

    private var deferredOption: ComponentOption? = null
    private var attached: InterfaceInstance? = null
    private var transmitDepth: Int = 0
    private var cycleAccumulator: Long = 0
    private var lastLeft = false
    private var lastRight = false

    init {
        host.onVarpWritten = ::varpChanged
        host.onVarcWritten = ::varcChanged
        host.onVarcStringWritten = ::varcStringChanged
        host.onDragPickup = ::dragPickup
    }


    fun update(viewport: InterfaceViewport, pointer: InterfacePointer, deltaMillis: Long) {
        this.viewport = viewport
        val previous = this.pointer
        this.pointer = pointer

        val instance = instances()
        if (instance !== attached) {
            reset()
            attached = instance
        }
        if (instance == null) {
            return
        }

        host.mouseX = pointer.x - viewport.x
        host.mouseY = pointer.y - viewport.y
        host.mouseLeft = pointer.left
        host.mouseRight = pointer.right
        host.mouseMiddle = pointer.middle

        cycleAccumulator += deltaMillis.coerceAtLeast(0)
        val cycles = (cycleAccumulator / CYCLE_MILLIS).toInt().coerceAtMost(MAX_CATCH_UP_CYCLES)
        cycleAccumulator %= CYCLE_MILLIS

        if (!liveMode) {
            reset()
            lastLeft = pointer.left
            lastRight = pointer.right
            return
        }

        updateHover(instance, pointer)
        updateButtons(pointer, cycles, deltaMillis)
        updateDrag(instance, pointer, cycles)
        if (pointer.wheel != 0) {
            wheel(instance, pointer.wheel)
        }
        repeat(cycles) {
            for (component in hoverChain) {
                dispatch(component, InterfaceEvent.MOUSE_OVER, pointerEvent(component, pointer))
            }
            for (component in instance.components) {
                if (!component.hidden) {
                    dispatch(component, InterfaceEvent.TIMER, Cs2Event(source = component.hash))
                }
            }
            cooldownTick(instance)
        }

        pointerMoved = previous.x != pointer.x || previous.y != pointer.y
        lastLeft = pointer.left
        lastRight = pointer.right
    }

    fun reset() {
        hoverChain.forEach { it.hovered = false }
        pressChain.forEach { it.pressed = false }
        hoverChain = emptyList()
        pressChain = emptyList()
        menuEntries = emptyList()
        deferredOption = null
        drag = null
        selected = null
        selectedSlot = -1
        focused = null
        pressMillis = 0
        cycleAccumulator = 0
        lastLeft = false
        lastRight = false
    }

    fun clearTrace() {
        traceLog.clear()
    }


    private fun updateHover(instance: InterfaceInstance, pointer: InterfacePointer) {
        val chain = if (menuOpen || !viewport.contains(pointer.x, pointer.y)) {
            emptyList()
        } else {
            instance.hoverChain(
                viewport.x, viewport.y, viewport.width, viewport.height,
                pointer.x, pointer.y,
            )
        }

        for (component in hoverChain) {
            if (chain.none { it === component }) {
                component.hovered = false
                dispatch(component, InterfaceEvent.MOUSE_EXIT, pointerEvent(component, pointer))
            }
        }
        for (component in chain) {
            if (!component.hovered) {
                component.hovered = true
                dispatch(component, InterfaceEvent.MOUSE_ENTER, pointerEvent(component, pointer))
            }
        }
        hoverChain = chain
    }


    private fun updateButtons(
        pointer: InterfacePointer,
        cycles: Int,
        deltaMillis: Long,
    ) {
        if (pointer.right && !lastRight && openMenu(pointer.x, pointer.y)) {
            return
        }
        if (pointer.left && !lastLeft && menuOpen) {
            menuEntries.firstOrNull()?.let { chooseOption(it) }
            closeMenu()
            return
        }

        if (pointer.left && !lastLeft) {
            val choice = defaultOption()
            beginPress(pointer)
            if (drag == null) {
                choice?.let { chooseOption(it, alreadyPressed = true) }
            } else {
                deferredOption = choice
            }
        }
        if (pointer.left) {
            pressMillis += deltaMillis.coerceAtLeast(0)
            if (drag == null) {
                repeat(cycles) {
                    for (component in pressChain) {
                        if (hoverChain.any { it === component }) {
                            dispatch(component, InterfaceEvent.CLICK_REPEAT, pointerEvent(component, pointer))
                        }
                    }
                    for (component in hoverChain) {
                        dispatch(component, InterfaceEvent.HOLD, pointerEvent(component, pointer))
                    }
                }
            }
        }

        val released = pressChain.filter { state -> !pointer.left || hoverChain.none { it === state } }
        if (released.isNotEmpty()) {
            for (component in released) {
                component.pressed = false
                val stillInside = hoverChain.any { it === component }
                if (clickOnRelease && !pointer.left && stillInside) {
                    dispatch(component, InterfaceEvent.MOUSE_PRESS, pointerEvent(component, pointer))
                }
                dispatch(component, InterfaceEvent.RELEASE, pointerEvent(component, pointer))
            }
            pressChain = pressChain.filter { state -> released.none { it === state } }
        }
        if (!pointer.left) {
            pressMillis = 0
        }
    }

    private fun beginPress(pointer: InterfacePointer) {
        pressChain = hoverChain
        pressMillis = 0
        focused = pressed
        for (component in pressChain) {
            component.pressed = true
            if (drag == null && component.draggable) {
                beginDrag(component, pointer.x - component.screenX, pointer.y - component.screenY)
            }
            if (drag != null) {
                continue
            }
            if (!clickOnRelease) {
                dispatch(component, InterfaceEvent.MOUSE_PRESS, pointerEvent(component, pointer))
            }
        }
    }


    fun dragPickup(component: ComponentState, offsetX: Int, offsetY: Int) {
        if (!component.draggable) {
            return
        }
        beginDrag(component, offsetX, offsetY)
    }

    private fun beginDrag(source: ComponentState, offsetX: Int, offsetY: Int) {
        if (drag != null || menuOpen) {
            return
        }
        drag = InterfaceDrag(
            source = source,
            offsetX = offsetX,
            offsetY = offsetY,
            bounds = dragBounds(source),
        ).apply {
            startX = source.screenX
            startY = source.screenY
            pointerX = pointer.x
            pointerY = pointer.y
            sourceSlot = slotOf(source, pointer.x, pointer.y)
        }
    }

    private fun dragBounds(source: ComponentState): ComponentState? {
        if (source.dragUnbounded) {
            return null
        }
        val depth = source.dragDepth
        if (depth == 0) {
            return source.depthChild
        }
        val instance = instances() ?: return source.depthChild
        var current: ComponentState = source
        repeat(depth) {
            current = instance.parentOf(current) ?: return null
        }
        return current
    }

    private fun updateDrag(instance: InterfaceInstance, pointer: InterfacePointer, cycles: Int) {
        val current = drag ?: return
        current.cycles += cycles
        current.pointerX = pointer.x
        current.pointerY = pointer.y

        val positionX = dragPositionX(current)
        val positionY = dragPositionY(current)
        if (!current.active && current.cycles > current.source.dragDeadTime) {
            val travelX = positionX - current.startX
            val travelY = positionY - current.startY
            val zone = current.source.dragDeadzone
            if (travelX > zone || travelX < -zone || travelY > zone || travelY < -zone) {
                current.active = true
            }
        }

        val target = hoverChain.lastOrNull { it !== current.source && it.dropTarget }
        current.target = target
        current.targetSlot = if (target == null) -1 else slotOf(target, pointer.x, pointer.y)

        if (pointer.left) {
            if (current.active) {
                repeat(cycles) {
                    dispatch(current.source, InterfaceEvent.DRAG, dragEvent(current))
                }
            }
            return
        }

        if (current.active) {
            dispatch(current.source, InterfaceEvent.DRAG_COMPLETE, dragEvent(current))
            instance.invalidateLayout()
        } else {
            deferredOption?.let { chooseOption(it, alreadyPressed = true) }
        }
        deferredOption = null
        drag = null
    }

    private fun dragEvent(current: InterfaceDrag): Cs2Event {
        val bounds = current.bounds
        var x = dragPositionX(current)
        var y = dragPositionY(current)
        if (bounds != null) {
            x += bounds.scrollX - bounds.screenX
            y += bounds.scrollY - bounds.screenY
        }
        return Cs2Event(
            mouseX = x,
            mouseY = y,
            source = current.source.hash,
            sourceSlot = current.sourceSlot,
            target = current.target?.hash ?: -1,
            targetSlot = current.targetSlot,
            optionName = current.source.opBase,
        )
    }

    private fun dragPositionX(current: InterfaceDrag): Int {
        val position = current.pointerX - current.offsetX
        val bounds = current.bounds ?: return position
        val last = bounds.screenX + bounds.width - current.source.width
        return position.coerceIn(bounds.screenX, maxOf(bounds.screenX, last))
    }

    private fun dragPositionY(current: InterfaceDrag): Int {
        val position = current.pointerY - current.offsetY
        val bounds = current.bounds ?: return position
        val last = bounds.screenY + bounds.height - current.source.height
        return position.coerceIn(bounds.screenY, maxOf(bounds.screenY, last))
    }


    private fun wheel(instance: InterfaceInstance, delta: Int) {
        var handled = false
        for (component in hoverChain) {
            val ran = dispatch(
                component,
                InterfaceEvent.MOUSE_WHEEL,
                Cs2Event(
                    mouseX = pointer.x - component.screenX,
                    mouseY = -delta,
                    source = component.hash,
                    sourceSlot = slotOf(component, pointer.x, pointer.y),
                ),
            )
            handled = handled || ran
        }
        if (!directScroll || handled) {
            return
        }
        val under = hoverChain.lastOrNull() ?: return
        val scroller = instance.scrollableAncestor(under) ?: return
        instance.scrollBy(scroller, 0, -delta * scrollStep)
    }


    fun key(code: Int, char: Char, modifiers: Int, repeat: Boolean = false) {
        val clientCode = InterfaceKeys.fromGlfw(code)
        if (clientCode != 0) {
            host.heldKeys.add(clientCode)
        }
        if (!liveMode) {
            return
        }
        val instance = instances() ?: return
        val mask = InterfaceKeys.modifierMask(modifiers)

        var consumed = false
        for (component in instance.components) {
            if (!component.clickable || component.hidden) {
                continue
            }
            val index = matchOptionKey(component, clientCode, char, mask, repeat)
            if (index >= 0) {
                consumed = true
                triggerOptionKey(component, index)
            }
        }
        if (consumed) {
            return
        }

        val event = Cs2Event(keyCode = clientCode, keyChar = char.code)
        var delivered = false
        for (component in instance.components) {
            if (host.boundHooks[component.hash]?.containsKey(InterfaceEvent.KEY.boundKey) == true) {
                delivered = true
                dispatch(component, InterfaceEvent.KEY, event.copy(source = component.hash))
            }
        }
        if (!delivered) {
            focused?.let { dispatch(it, InterfaceEvent.KEY, event.copy(source = it.hash)) }
        }
    }

    fun keyReleased(code: Int) {
        val clientCode = InterfaceKeys.fromGlfw(code)
        if (clientCode == 0) {
            return
        }
        host.heldKeys.remove(clientCode)
        val instance = instances() ?: return
        for (component in instance.components) {
            for (index in component.optionKeyCodes.indices) {
                if (component.optionKeyCodes[index] == clientCode) {
                    component.optionKeyCooldowns[index] = 0
                }
            }
        }
    }

    fun focus(component: ComponentState?) {
        focused = component
    }

    private fun matchOptionKey(
        component: ComponentState,
        clientCode: Int,
        char: Char,
        mask: Int,
        repeat: Boolean,
    ): Int {
        for (index in component.optionKeyCodes.indices) {
            if (component.optionKeyCooldowns[index] != 0) {
                continue
            }
            if (component.optionKeyChars[index] > 0 && component.optionKeyChars[index] == char.code) {
                return index
            }
            val code = component.optionKeyCodes[index]
            if (code == 0 || code != clientCode) {
                continue
            }
            if (repeat && component.optionKeyRepeats[index] == 0) {
                continue
            }
            val required = component.optionKeyModifiers[index]
            if (required == 0 || (required and mask) == required) {
                return index
            }
        }
        return -1
    }

    private fun triggerOptionKey(component: ComponentState, index: Int) {
        val repeats = component.optionKeyRepeats.getOrElse(index) { 0 }
        component.optionKeyCooldowns[index] = if (repeats != 0) repeats else Int.MAX_VALUE
        if (index == ComponentState.OPTION_COUNT) {
            select(component, slotOf(component, pointer.x, pointer.y))
            return
        }
        chooseOption(
            ComponentOption(
                component = component,
                option = index + 1,
                label = component.optionAt(index) ?: "",
                slot = slotOf(component, pointer.x, pointer.y),
                cursor = component.optionCursor(index),
            )
        )
    }

    private fun cooldownTick(instance: InterfaceInstance) {
        for (component in instance.components) {
            for (index in component.optionKeyCooldowns.indices) {
                val cooldown = component.optionKeyCooldowns[index]
                if (cooldown in 1 until Int.MAX_VALUE) {
                    component.optionKeyCooldowns[index] = cooldown - 1
                }
            }
        }
    }


    fun openMenu(pointX: Int, pointY: Int): Boolean {
        val entries = ArrayList<ComponentOption>()
        for (component in hoverChain) {
            collectOptions(component, pointX, pointY, entries)
        }
        entries.reverse()
        menuEntries = entries
        menuX = pointX
        menuY = pointY
        return entries.isNotEmpty()
    }

    fun closeMenu() {
        menuEntries = emptyList()
    }

    fun defaultOption(pointX: Int = pointer.x, pointY: Int = pointer.y): ComponentOption? {
        if (menuOpen) {
            return null
        }
        val entries = ArrayList<ComponentOption>()
        for (component in hoverChain) {
            collectOptions(component, pointX, pointY, entries)
        }
        return entries.lastOrNull { leftClickable(it) }
    }

    private fun leftClickable(choice: ComponentOption): Boolean = when (choice.kind) {
        OptionKind.USE, OptionKind.CONTINUE -> true
        OptionKind.OPTION -> choice.option in 1..LEFT_CLICK_OPTIONS
    }

    fun optionsFor(component: ComponentState, pointX: Int = pointer.x, pointY: Int = pointer.y): List<ComponentOption> {
        val entries = ArrayList<ComponentOption>()
        collectOptions(component, pointX, pointY, entries)
        entries.reverse()
        return entries
    }

    private fun collectOptions(
        component: ComponentState,
        pointX: Int,
        pointY: Int,
        into: MutableList<ComponentOption>,
    ) {
        val slot = slotOf(component, pointX, pointY)
        for (index in 9 downTo 5) {
            component.optionAt(index)?.let {
                into.add(ComponentOption(component, index + 1, it, OptionKind.OPTION, slot, component.optionCursor(index)))
            }
        }
        if (component.useOptionFlags != 0 && component.useOption.isNotBlank()) {
            into.add(
                ComponentOption(component, 0, component.useOption, OptionKind.USE, slot, component.targetCursor)
            )
        }
        for (index in 4 downTo 0) {
            component.optionAt(index)?.let {
                into.add(ComponentOption(component, index + 1, it, OptionKind.OPTION, slot, component.optionCursor(index)))
            }
        }
        if (component.continueEnabled) {
            into.add(
                ComponentOption(
                    component,
                    0,
                    component.continueText ?: CONTINUE_TEXT,
                    OptionKind.CONTINUE,
                    slot,
                )
            )
        }
    }

    fun chooseOption(choice: ComponentOption, alreadyPressed: Boolean = false) {
        closeMenu()
        val component = choice.component
        when (choice.kind) {
            OptionKind.USE -> select(component, choice.slot)
            OptionKind.CONTINUE -> record(InterfaceEvent.OPTION, component, -1, false, null)
            OptionKind.OPTION -> {
                val event = Cs2Event(
                    mouseX = pointer.x - component.screenX,
                    mouseY = pointer.y - component.screenY,
                    source = component.hash,
                    sourceSlot = choice.slot,
                    clickedOption = choice.option,
                    optionName = component.opBase,
                )
                if (!dispatch(component, InterfaceEvent.OPTION, event) && !alreadyPressed) {
                    dispatch(component, InterfaceEvent.MOUSE_PRESS, event)
                }
            }
        }
    }


    fun select(component: ComponentState, slot: Int = -1) {
        cancelSelection()
        selected = component
        selectedSlot = slot
        dispatch(
            component,
            InterfaceEvent.TARGET_ENTER,
            Cs2Event(source = component.hash, sourceSlot = slot, optionName = component.opBase),
        )
    }

    fun cancelSelection() {
        val component = selected ?: return
        val slot = selectedSlot
        selected = null
        selectedSlot = -1
        val event = Cs2Event(source = component.hash, sourceSlot = slot, optionName = component.opBase)
        dispatch(component, InterfaceEvent.DESELECT, event)
        dispatch(component, InterfaceEvent.TARGET_LEAVE, event)
    }


    fun varpChanged(id: Int) = transmit(InterfaceEvent.VARP_TRANSMIT, id)

    fun statChanged(id: Int) = transmit(InterfaceEvent.STAT_TRANSMIT, id)

    fun invChanged(id: Int) = transmit(InterfaceEvent.INV_TRANSMIT, id)

    fun varcChanged(id: Int) = transmit(InterfaceEvent.VARC_TRANSMIT, id)

    fun varcStringChanged(id: Int) = transmit(InterfaceEvent.VARC_STRING_TRANSMIT, id)

    private fun transmit(event: InterfaceEvent, id: Int) {
        if (!liveMode) {
            return
        }
        val instance = instances() ?: return
        if (transmitDepth >= MAX_TRANSMIT_DEPTH) {
            host.onError("Transmit hooks nested more than $MAX_TRANSMIT_DEPTH deep on ${event.boundKey} $id")
            return
        }
        transmitDepth++
        try {
            for (component in instance.components) {
                val bound = host.boundHooks[component.hash]?.get(event.boundKey)
                val ids = if (bound != null) {
                    bound.triggers.takeIf { it.isNotEmpty() }?.toIntArray()
                } else {
                    event.transmitFilter?.invoke(component.definition)
                }
                if (ids != null && !ids.contains(id)) {
                    continue
                }
                dispatch(component, event, Cs2Event(source = component.hash))
            }
        } finally {
            transmitDepth--
        }
    }


    fun dispatch(component: ComponentState, event: InterfaceEvent, data: Cs2Event): Boolean {
        if (!liveMode) {
            return false
        }
        val bound = host.boundHooks[component.hash]?.get(event.boundKey)
        val defined = if (bound == null) event.definitionHook(component.definition) else null
        if (bound == null && defined == null) {
            return false
        }
        host.setActive(0, component)
        if (bound != null) {
            val error = capturingErrors { dispatcher.dispatch(bound, data) }
            record(event, component, bound.scriptId, true, error)
        } else {
            val error = capturingErrors { dispatcher.dispatch(defined!!, data) }
            record(event, component, defined!!.script, false, error)
        }
        return true
    }

    private fun pointerEvent(component: ComponentState, pointer: InterfacePointer) = Cs2Event(
        mouseX = pointer.x - component.screenX,
        mouseY = pointer.y - component.screenY,
        source = component.hash,
        sourceSlot = slotOf(component, pointer.x, pointer.y),
        optionName = component.opBase,
    )

    private fun slotOf(component: ComponentState, pointX: Int, pointY: Int): Int {
        if (component.slotId >= 0) {
            return component.slotId
        }
        return slotResolver(component, pointX - component.screenX, pointY - component.screenY)
    }

    private fun capturingErrors(block: () -> Unit): String? {
        val before = host.log.size
        block()
        if (host.log.size <= before) {
            return null
        }
        return host.log.subList(before, host.log.size).joinToString("; ")
    }

    private fun record(
        event: InterfaceEvent,
        component: ComponentState,
        script: Int,
        bound: Boolean,
        error: String?,
    ) {
        traceLog.add(InteractionTrace(event, component.index, component.hash, script, bound, error))
        while (traceLog.size > traceCapacity.coerceAtLeast(1)) {
            traceLog.removeAt(0)
        }
    }

    companion object {
        const val CYCLE_MILLIS = 20L

        const val MAX_CATCH_UP_CYCLES = 4

        const val DEFAULT_SCROLL_STEP = 30

        const val DEFAULT_TRACE_CAPACITY = 256

        const val MAX_TRANSMIT_DEPTH = 10

        const val CONTINUE_TEXT = "Continue"

        const val LEFT_CLICK_OPTIONS = 5
    }
}

object InterfaceKeys {

    const val SHIFT = 81

    const val CTRL = 82

    const val ALT = 86

    const val MOD_CTRL = 0x1

    const val MOD_ALT = 0x2

    const val MOD_SHIFT = 0x4

    const val MOD_NONE = 0x8

    private val CODES: Map<Int, Int> = buildMap {
        put(GLFW.GLFW_KEY_F1, 1)
        put(GLFW.GLFW_KEY_F2, 2)
        put(GLFW.GLFW_KEY_F3, 3)
        put(GLFW.GLFW_KEY_F4, 4)
        put(GLFW.GLFW_KEY_F5, 5)
        put(GLFW.GLFW_KEY_F6, 6)
        put(GLFW.GLFW_KEY_F7, 7)
        put(GLFW.GLFW_KEY_F8, 8)
        put(GLFW.GLFW_KEY_F9, 9)
        put(GLFW.GLFW_KEY_F10, 10)
        put(GLFW.GLFW_KEY_F11, 11)
        put(GLFW.GLFW_KEY_F12, 12)
        put(GLFW.GLFW_KEY_ESCAPE, 13)

        put(GLFW.GLFW_KEY_1, 16)
        put(GLFW.GLFW_KEY_2, 17)
        put(GLFW.GLFW_KEY_3, 18)
        put(GLFW.GLFW_KEY_4, 19)
        put(GLFW.GLFW_KEY_5, 20)
        put(GLFW.GLFW_KEY_6, 21)
        put(GLFW.GLFW_KEY_7, 22)
        put(GLFW.GLFW_KEY_8, 23)
        put(GLFW.GLFW_KEY_9, 24)
        put(GLFW.GLFW_KEY_0, 25)

        put(GLFW.GLFW_KEY_Q, 32)
        put(GLFW.GLFW_KEY_W, 33)
        put(GLFW.GLFW_KEY_E, 34)
        put(GLFW.GLFW_KEY_R, 35)
        put(GLFW.GLFW_KEY_T, 36)
        put(GLFW.GLFW_KEY_Y, 37)
        put(GLFW.GLFW_KEY_U, 38)
        put(GLFW.GLFW_KEY_I, 39)
        put(GLFW.GLFW_KEY_O, 40)
        put(GLFW.GLFW_KEY_P, 41)
        put(GLFW.GLFW_KEY_A, 48)
        put(GLFW.GLFW_KEY_S, 49)
        put(GLFW.GLFW_KEY_D, 50)
        put(GLFW.GLFW_KEY_F, 51)
        put(GLFW.GLFW_KEY_G, 52)
        put(GLFW.GLFW_KEY_H, 53)
        put(GLFW.GLFW_KEY_J, 54)
        put(GLFW.GLFW_KEY_K, 55)
        put(GLFW.GLFW_KEY_L, 56)
        put(GLFW.GLFW_KEY_Z, 64)
        put(GLFW.GLFW_KEY_X, 65)
        put(GLFW.GLFW_KEY_C, 66)
        put(GLFW.GLFW_KEY_V, 67)
        put(GLFW.GLFW_KEY_B, 68)
        put(GLFW.GLFW_KEY_N, 69)
        put(GLFW.GLFW_KEY_M, 70)

        put(GLFW.GLFW_KEY_TAB, 80)
        put(GLFW.GLFW_KEY_LEFT_SHIFT, SHIFT)
        put(GLFW.GLFW_KEY_RIGHT_SHIFT, SHIFT)
        put(GLFW.GLFW_KEY_LEFT_CONTROL, CTRL)
        put(GLFW.GLFW_KEY_RIGHT_CONTROL, CTRL)
        put(GLFW.GLFW_KEY_SPACE, 83)
        put(GLFW.GLFW_KEY_ENTER, 84)
        put(GLFW.GLFW_KEY_KP_ENTER, 84)
        put(GLFW.GLFW_KEY_BACKSPACE, 85)
        put(GLFW.GLFW_KEY_LEFT_ALT, ALT)
        put(GLFW.GLFW_KEY_RIGHT_ALT, ALT)

        put(GLFW.GLFW_KEY_KP_ADD, 87)
        put(GLFW.GLFW_KEY_KP_SUBTRACT, 88)
        put(GLFW.GLFW_KEY_KP_MULTIPLY, 89)
        put(GLFW.GLFW_KEY_KP_DIVIDE, 90)

        put(GLFW.GLFW_KEY_LEFT, 96)
        put(GLFW.GLFW_KEY_RIGHT, 97)
        put(GLFW.GLFW_KEY_UP, 98)
        put(GLFW.GLFW_KEY_DOWN, 99)
        put(GLFW.GLFW_KEY_INSERT, 100)
        put(GLFW.GLFW_KEY_DELETE, 101)
        put(GLFW.GLFW_KEY_HOME, 102)
        put(GLFW.GLFW_KEY_END, 103)
        put(GLFW.GLFW_KEY_PAGE_UP, 104)
        put(GLFW.GLFW_KEY_PAGE_DOWN, 105)
    }

    fun fromGlfw(code: Int): Int = CODES[code] ?: 0

    fun modifierMask(glfwModifiers: Int): Int {
        var mask = 0
        if ((glfwModifiers and GLFW.GLFW_MOD_CONTROL) != 0) {
            mask = mask or MOD_CTRL
        }
        if ((glfwModifiers and GLFW.GLFW_MOD_ALT) != 0) {
            mask = mask or MOD_ALT
        }
        if ((glfwModifiers and GLFW.GLFW_MOD_SHIFT) != 0) {
            mask = mask or MOD_SHIFT
        }
        return if (mask == 0) MOD_NONE else mask
    }
}
