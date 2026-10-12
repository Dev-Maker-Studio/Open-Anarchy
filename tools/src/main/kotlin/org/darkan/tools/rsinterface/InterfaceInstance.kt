package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceDefinition

class InterfaceInstance(
    val id: Int,
    val definition: InterfaceDefinition
) {
    val components: List<ComponentState>

    private val childIndices: Map<Int, List<Int>>

    private val subInterfaces = LinkedHashMap<Int, InterfaceInstance>()

    private val slotChildren = LinkedHashMap<Int, ArrayList<ComponentState?>>()

    private val runtimeComponents = LinkedHashMap<Int, ComponentState>()

    private val freeIndices = ArrayDeque<Int>()

    private var nextRuntimeIndex = 0

    private var layoutDirty = true
    private var laidOutWidth = -1
    private var laidOutHeight = -1

    init {
        val definitions = definition.components ?: emptyArray()
        components = definitions.mapIndexed { index, component ->
            ComponentState(component, index, id).also { state ->
                state.invalidateLayout = { layoutDirty = true }
            }
        }
        childIndices = components.groupBy({ it.parent }, { it.index })
        nextRuntimeIndex = components.size
    }

    fun state(index: Int): ComponentState? = components.getOrNull(index) ?: runtimeComponents[index]

    fun stateByHash(hash: Int): ComponentState? {
        if ((hash shr 16) == id) {
            return state(hash and 0xffff)
        }
        for (sub in subInterfaces.values) {
            val found = sub.stateByHash(hash)
            if (found != null) {
                return found
            }
        }
        return null
    }

    fun children(parentIndex: Int): List<ComponentState> {
        val fixed = childIndices[parentIndex]?.mapNotNull { components.getOrNull(it) } ?: emptyList()
        val slots = slotChildren[parentIndex] ?: return fixed
        return fixed + slots.filterNotNull()
    }


    fun createChild(parentIndex: Int, type: Int, slot: Int): ComponentState? {
        if (type == ComponentType.CONTAINER || slot < 0 || slot > ComponentDefinitions.MAX_COMPONENTS) {
            return null
        }
        if (state(parentIndex) == null) {
            return null
        }
        val slots = slotChildren.getOrPut(parentIndex) { ArrayList() }
        while (slots.size <= slot) {
            slots.add(null)
        }
        slots[slot]?.let(::dispose)

        val index = freeIndices.removeFirstOrNull() ?: nextRuntimeIndex++
        val definition = ComponentDefinitions.create(id, index, type)
        definition.parent = parentIndex
        val child = ComponentState(definition, index, id, slot)
        child.invalidateLayout = { layoutDirty = true }
        slots[slot] = child
        runtimeComponents[index] = child
        layoutDirty = true
        return child
    }

    fun deleteChildren(parentIndex: Int) {
        val slots = slotChildren.remove(parentIndex) ?: return
        slots.forEach { child -> child?.let(::dispose) }
        layoutDirty = true
    }

    fun deleteChild(state: ComponentState): Boolean {
        val slots = slotChildren[state.parent] ?: return false
        if (state.slotId !in slots.indices || slots[state.slotId] !== state) {
            return false
        }
        slots[state.slotId] = null
        dispose(state)
        layoutDirty = true
        return true
    }

    fun slotCount(parentIndex: Int): Int {
        val slots = slotChildren[parentIndex] ?: return 0
        val gap = slots.indexOfFirst { it == null }
        return if (gap == -1) slots.size else gap
    }

    fun slotChild(parentIndex: Int, slot: Int): ComponentState? =
        slotChildren[parentIndex]?.getOrNull(slot)

    fun slotChildByHash(hash: Int, slot: Int): ComponentState? {
        val owner = stateByHash(hash) ?: return null
        if (slot < 0) {
            return owner
        }
        return slotChild(owner.index, slot)
    }

    private fun dispose(state: ComponentState) {
        slotChildren.remove(state.index)?.forEach { child -> child?.let(::dispose) }
        subInterfaces.remove(state.index)
        runtimeComponents.remove(state.index)
        freeIndices.addLast(state.index)
    }

    fun subInterface(containerIndex: Int): InterfaceInstance? = subInterfaces[containerIndex]

    fun openSub(containerIndex: Int, instance: InterfaceInstance) {
        subInterfaces[containerIndex] = instance
        layoutDirty = true
    }

    fun closeSub(containerIndex: Int) {
        if (subInterfaces.remove(containerIndex) != null) {
            layoutDirty = true
        }
    }

    fun invalidateLayout() {
        layoutDirty = true
    }

    fun layoutDeclared() {
        layout(
            if (laidOutWidth > 0) laidOutWidth else DEFAULT_VIEWPORT_WIDTH,
            if (laidOutHeight > 0) laidOutHeight else DEFAULT_VIEWPORT_HEIGHT,
        )
    }

    fun resolve(state: ComponentState) {
        val parent = parentOf(state)
        val width: Int
        val height: Int
        if (parent == null) {
            width = if (laidOutWidth > 0) laidOutWidth else DEFAULT_VIEWPORT_WIDTH
            height = if (laidOutHeight > 0) laidOutHeight else DEFAULT_VIEWPORT_HEIGHT
        } else {
            width = if (parent.scrollWidth != 0) parent.scrollWidth else parent.width
            height = if (parent.scrollHeight != 0) parent.scrollHeight else parent.height
        }
        applySize(state, width, height)
        applyPosition(state, width, height)
        clampScroll(state)
    }

    fun reset() {
        components.forEach { it.reset() }
        subInterfaces.clear()
        slotChildren.clear()
        runtimeComponents.clear()
        freeIndices.clear()
        nextRuntimeIndex = components.size
        layoutDirty = true
    }

    fun layout(width: Int, height: Int) {
        if (!layoutDirty && width == laidOutWidth && height == laidOutHeight) {
            return
        }
        laidOutWidth = width
        laidOutHeight = height
        layoutDirty = false
        layoutChildren(-1, width, height)
    }

    fun hitTest(
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        pointX: Int,
        pointY: Int
    ): List<ComponentState> {
        layout(width, height)
        val hits = ArrayList<ComponentState>()
        hitTestChildren(
            -1, originX, originY, originX + width, originY + height,
            originX, originY, pointX, pointY, hits
        )
        return hits
    }

    fun hoverChain(
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        pointX: Int,
        pointY: Int
    ): List<ComponentState> {
        val hits = hitTest(originX, originY, width, height, pointX, pointY)
        val blocker = hits.indexOfLast { it.noClickThrough }
        return if (blocker <= 0) hits else hits.subList(blocker, hits.size)
    }

    fun interactiveAt(
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        pointX: Int,
        pointY: Int
    ): ComponentState? {
        val chain = hoverChain(originX, originY, width, height, pointX, pointY)
        return chain.lastOrNull { it.interactive && it.type != ComponentType.CONTAINER }
            ?: chain.lastOrNull { it.interactive }
    }

    fun parentOf(state: ComponentState): ComponentState? = state(state.parent)

    fun contains(state: ComponentState): Boolean =
        (state.interfaceId == id && state(state.index) === state) ||
            subInterfaces.values.any { it.contains(state) }

    fun enclosing(state: ComponentState): ComponentState? {
        if (state.interfaceId == id) {
            return parentOf(state)
        }
        for ((containerIndex, sub) in subInterfaces) {
            if (sub.contains(state)) {
                return sub.enclosing(state) ?: components.getOrNull(containerIndex)
            }
        }
        return null
    }

    fun ancestors(state: ComponentState): List<ComponentState> {
        val chain = ArrayList<ComponentState>()
        var current = parentOf(state)
        while (current != null && chain.size < components.size + runtimeComponents.size) {
            chain.add(current)
            current = parentOf(current)
        }
        return chain
    }

    fun scrollableAncestor(state: ComponentState): ComponentState? {
        if (scrollable(state)) {
            return state
        }
        return ancestors(state).firstOrNull { scrollable(it) }
    }

    private fun scrollable(state: ComponentState): Boolean =
        state.type == ComponentType.CONTAINER &&
            (state.scrollHeight > state.height || state.scrollWidth > state.width)

    fun scrollBy(state: ComponentState, deltaX: Int, deltaY: Int): Boolean {
        val beforeX = state.scrollX
        val beforeY = state.scrollY
        state.setScrollPos(state.scrollX + deltaX, state.scrollY + deltaY)
        clampScroll(state)
        return state.scrollX != beforeX || state.scrollY != beforeY
    }

    @Suppress("LongParameterList")
    private fun hitTestChildren(
        parentIndex: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        offsetX: Int,
        offsetY: Int,
        pointX: Int,
        pointY: Int,
        hits: MutableList<ComponentState>
    ) {
        for (child in children(parentIndex)) {
            if (child.hidden) {
                continue
            }
            val startX = child.x + offsetX
            val startY = child.y + offsetY
            val box = clipBox(child, startX, startY, clipLeft, clipTop, clipRight, clipBottom)
            if (box[0] >= box[2] || box[1] >= box[3]) {
                continue
            }
            if (pointX >= box[0] && pointX < box[2] && pointY >= box[1] && pointY < box[3]) {
                hits.add(child)
            }
            if (child.type == ComponentType.CONTAINER) {
                hitTestChildren(
                    child.index, box[0], box[1], box[2], box[3],
                    startX - child.scrollX, startY - child.scrollY, pointX, pointY, hits
                )
                subInterfaces[child.index]?.hitTestChildren(
                    -1, box[0], box[1], box[2], box[3], startX, startY, pointX, pointY, hits
                )
            }
        }
    }

    internal fun clipBox(
        state: ComponentState,
        startX: Int,
        startY: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int
    ): IntArray {
        if (state.type == ComponentType.TYPE_2) {
            return intArrayOf(clipLeft, clipTop, clipRight, clipBottom)
        }
        var right = startX + state.width
        var bottom = startY + state.height
        if (state.type == ComponentType.LINE) {
            right++
            bottom++
        }
        return intArrayOf(
            maxOf(startX, clipLeft),
            maxOf(startY, clipTop),
            minOf(right, clipRight),
            minOf(bottom, clipBottom)
        )
    }

    private fun layoutChildren(parentIndex: Int, parentWidth: Int, parentHeight: Int) {
        for (child in children(parentIndex)) {
            applySize(child, parentWidth, parentHeight)
            applyPosition(child, parentWidth, parentHeight)
            clampScroll(child)
            if (child.type == ComponentType.CONTAINER) {
                val innerWidth = if (child.scrollWidth != 0) child.scrollWidth else child.width
                val innerHeight = if (child.scrollHeight != 0) child.scrollHeight else child.height
                layoutChildren(child.index, innerWidth, innerHeight)
                val sub = subInterfaces[child.index]
                if (sub != null) {
                    sub.invalidateLayout()
                    sub.layout(innerWidth, innerHeight)
                }
            }
        }
    }

    private fun applySize(state: ComponentState, parentWidth: Int, parentHeight: Int) {
        when (state.horizontalSizeMode) {
            SIZE_ABSOLUTE -> state.width = state.baseWidth
            SIZE_INSET -> state.width = parentWidth - state.baseWidth
            SIZE_PROPORTIONAL -> state.width = (state.baseWidth * parentWidth) shr 14
        }
        when (state.verticalSizeMode) {
            SIZE_ABSOLUTE -> state.height = state.baseHeight
            SIZE_INSET -> state.height = parentHeight - state.baseHeight
            SIZE_PROPORTIONAL -> state.height = (parentHeight * state.baseHeight) shr 14
        }
        if (state.horizontalSizeMode == SIZE_ASPECT && state.aspectY != 0) {
            state.width = state.aspectX * state.height / state.aspectY
        }
        if (state.verticalSizeMode == SIZE_ASPECT && state.aspectX != 0) {
            state.height = state.width * state.aspectY / state.aspectX
        }
    }

    private fun applyPosition(state: ComponentState, parentWidth: Int, parentHeight: Int) {
        state.x = when (state.horizontalPositionMode) {
            POSITION_ABSOLUTE -> state.basePositionX
            POSITION_CENTRE -> state.basePositionX + (parentWidth - state.width) / 2
            POSITION_END -> parentWidth - state.width - state.basePositionX
            POSITION_PROPORTIONAL -> (state.basePositionX * parentWidth) shr 14
            POSITION_PROPORTIONAL_CENTRE ->
                (parentWidth - state.width) / 2 + ((state.basePositionX * parentWidth) shr 14)
            else -> parentWidth - state.width - ((state.basePositionX * parentWidth) shr 14)
        }
        state.y = when (state.verticalPositionMode) {
            POSITION_ABSOLUTE -> state.basePositionY
            POSITION_CENTRE -> state.basePositionY + (parentHeight - state.height) / 2
            POSITION_END -> parentHeight - state.height - state.basePositionY
            POSITION_PROPORTIONAL -> (parentHeight * state.basePositionY) shr 14
            POSITION_PROPORTIONAL_CENTRE ->
                (parentHeight - state.height) / 2 + ((parentHeight * state.basePositionY) shr 14)
            else -> parentHeight - state.height - ((parentHeight * state.basePositionY) shr 14)
        }
    }

    internal fun clampScroll(state: ComponentState) {
        state.scrollX = state.scrollX.coerceAtMost(state.scrollWidth - state.width).coerceAtLeast(0)
        state.scrollY = state.scrollY.coerceAtMost(state.scrollHeight - state.height).coerceAtLeast(0)
    }

    companion object {
        private const val SIZE_ABSOLUTE = 0
        private const val SIZE_INSET = 1
        private const val SIZE_PROPORTIONAL = 2
        private const val SIZE_ASPECT = 4

        private const val POSITION_ABSOLUTE = 0
        private const val POSITION_CENTRE = 1
        private const val POSITION_END = 2
        private const val POSITION_PROPORTIONAL = 3
        private const val POSITION_PROPORTIONAL_CENTRE = 4

        const val DEFAULT_VIEWPORT_WIDTH = 512
        const val DEFAULT_VIEWPORT_HEIGHT = 334
    }
}
