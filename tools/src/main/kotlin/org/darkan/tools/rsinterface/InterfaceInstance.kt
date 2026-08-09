package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceDefinition

/**
 * A live instance of a cache interface: the component tree, the mutable
 * [ComponentState] for every component, and the layout pass that turns the
 * cache's base position/size plus the position/size modes into a concrete box
 * model.
 *
 * The layout rules are a direct transcription of the client's
 * `InterfaceRenderer.initSizes` and `InterfaceComponentRenderer.componentPositionAdjustment`.
 *
 * A CS2 interpreter drives this class through [state] / [stateByHash] to reach a
 * component, and through [openSub] / [closeSub] for `if_openSub` / `if_closeSub`.
 * Nothing here needs to be told to relayout - [ComponentState] mutators mark the
 * instance dirty and [layout] re-runs on the next frame.
 */
class InterfaceInstance(
    val id: Int,
    val definition: InterfaceDefinition
) {
    /** Component states in cache order, which is also the draw order. */
    val components: List<ComponentState>

    /** Ordered child indices per parent index; the root's children use -1. */
    private val childIndices: Map<Int, List<Int>>

    /** Sub-interfaces mounted onto a container, keyed by container index. */
    private val subInterfaces = LinkedHashMap<Int, InterfaceInstance>()

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
    }

    fun state(index: Int): ComponentState? = components.getOrNull(index)

    /**
     * Resolves the packed `interfaceId shl 16 or componentIndex` hash that CS2
     * and the network protocol use, searching mounted sub-interfaces too.
     */
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

    /** Children of [parentIndex] in draw order; pass -1 for the root children. */
    fun children(parentIndex: Int): List<ComponentState> {
        val indices = childIndices[parentIndex] ?: return emptyList()
        return indices.mapNotNull { components.getOrNull(it) }
    }

    fun subInterface(containerIndex: Int): InterfaceInstance? = subInterfaces[containerIndex]

    /** Mounts [instance] inside the container at [containerIndex] (`if_openSub`). */
    fun openSub(containerIndex: Int, instance: InterfaceInstance) {
        subInterfaces[containerIndex] = instance
        layoutDirty = true
    }

    /** Unmounts whatever is inside the container at [containerIndex]. */
    fun closeSub(containerIndex: Int) {
        if (subInterfaces.remove(containerIndex) != null) {
            layoutDirty = true
        }
    }

    fun invalidateLayout() {
        layoutDirty = true
    }

    /** Resets every component back to the values decoded from the cache. */
    fun reset() {
        components.forEach { it.reset() }
        subInterfaces.clear()
        layoutDirty = true
    }

    /**
     * Recomputes the box model against a root viewport of [width] x [height].
     * Cheap to call every frame: it only does work when something changed.
     */
    fun layout(width: Int, height: Int) {
        if (!layoutDirty && width == laidOutWidth && height == laidOutHeight) {
            return
        }
        laidOutWidth = width
        laidOutHeight = height
        layoutDirty = false
        layoutChildren(-1, width, height)
    }

    /**
     * Collects every visible component under ([pointX], [pointY]) in draw order,
     * back to front, walking the same clip rules the renderer uses so that a
     * component scrolled or clipped out of its parent is not pickable.
     *
     * The interface is assumed to be drawn at ([originX], [originY]) inside a
     * [width] x [height] viewport, the same arguments passed to the renderer.
     */
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
            if (child.type == ComponentType.CONTAINER) {
                hitTestChildren(
                    child.index, box[0], box[1], box[2], box[3],
                    startX - child.scrollX, startY - child.scrollY, pointX, pointY, hits
                )
                subInterfaces[child.index]?.hitTestChildren(
                    -1, box[0], box[1], box[2], box[3], startX, startY, pointX, pointY, hits
                )
            }
            if (pointX >= box[0] && pointX < box[2] && pointY >= box[1] && pointY < box[3]) {
                hits.add(child)
            }
        }
    }

    /**
     * The visible rectangle of a component as left/top/right/bottom, matching the
     * client's clip arithmetic: type 2 never narrows the region, and lines are one
     * pixel wider and taller because their end point is inclusive.
     */
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
            // Only containers propagate a coordinate space to children, exactly as
            // the client's updateInterfaceDimensions does. Components parented to a
            // non-container are never laid out, and never drawn, in either renderer.
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

    /**
     * Size modes, from `InterfaceRenderer.initSizes`:
     * - 0 absolute: the base size verbatim
     * - 1 inset: the parent extent minus the base size
     * - 2 proportional: `base * parent / 16384`
     * - 3 unchanged: keeps whatever size the component already had
     * - 4 aspect: derived from the other axis via [ComponentState.aspectX]/[ComponentState.aspectY]
     *
     * Mode 4 is resolved after both axes, so a width of mode 4 uses the height
     * computed by this same pass - matching the client's ordering.
     */
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

    /**
     * Position modes, from `InterfaceComponentRenderer.componentPositionAdjustment`.
     * All of them are relative to the parent's content box, and the centred and
     * right/bottom aligned modes depend on the size computed by [applySize]:
     * - 0 absolute from the top/left
     * - 1 centred, offset by the base position
     * - 2 absolute from the bottom/right
     * - 3 proportional: `base * parent / 16384`
     * - 4 centred plus a proportional offset
     * - 5 (and anything else) from the bottom/right by a proportional offset
     */
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

    /** Keeps the scroll offsets inside the scrollable overflow, as the client does. */
    private fun clampScroll(state: ComponentState) {
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
    }
}
