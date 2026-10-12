package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import java.util.Collections

private const val UNSET = Int.MIN_VALUE

class InterfaceDocument(
    val id: Int,
    components: List<InterfaceComponentDefinition> = emptyList(),
    val history: InterfaceHistory = InterfaceHistory()
) {

    private val items = ArrayList<InterfaceComponentDefinition>(components)
    private val view: List<InterfaceComponentDefinition> = Collections.unmodifiableList(items)

    private var cachedInstance: InterfaceInstance? = null
    private var structureDirty = true
    private var statesDirty = false

    private val remapHashes = id > 0

    var dirty: Boolean = false
        private set

    var selected: Int = -1
        set(value) {
            field = if (value in items.indices) value else -1
        }

    var onRebuild: ((InterfaceInstance) -> Unit)? = null

    var lastReindexIssues: List<InterfaceIssue> = emptyList()
        private set

    init {
        for (index in items.indices) {
            items[index].id = ComponentDefinitions.pack(id, index)
        }
    }

    val componentCount: Int get() = items.size

    val components: List<InterfaceComponentDefinition> get() = view

    fun component(index: Int): InterfaceComponentDefinition? = items.getOrNull(index)

    fun hash(index: Int): Int = ComponentDefinitions.pack(id, index)


    fun children(parentIndex: Int): List<Int> = items.indices.filter { items[it].parent == parentIndex }

    fun subtree(index: Int): List<Int> {
        if (index !in items.indices) {
            return emptyList()
        }
        return collectSubtree(index, childMap()).sorted()
    }

    fun depth(index: Int): Int {
        if (index !in items.indices) {
            return -1
        }
        var depth = 0
        var current = items[index].parent
        var guard = items.size
        while (current in items.indices && guard-- > 0) {
            depth++
            current = items[current].parent
        }
        return depth
    }


    fun instance(): InterfaceInstance {
        var current = cachedInstance
        if (current == null || structureDirty) {
            current = InterfaceInstance(id, InterfaceDefinition(id, items.toTypedArray()))
            cachedInstance = current
            structureDirty = false
            statesDirty = false
            onRebuild?.invoke(current)
        } else if (statesDirty) {
            current.reset()
            statesDirty = false
        }
        return current
    }

    fun definition(): InterfaceDefinition = instance().definition

    fun invalidateStates() {
        statesDirty = true
    }

    fun rebuild() {
        structureDirty = true
    }


    val canUndo: Boolean get() = history.canUndo

    val canRedo: Boolean get() = history.canRedo

    val undoLabel: String? get() = history.undoLabel

    val redoLabel: String? get() = history.redoLabel

    fun snapshot(): Array<InterfaceComponentDefinition> =
        Array(items.size) { ComponentDefinitions.deepCopy(items[it]) }

    fun undo(): Boolean {
        val entry = history.undo(selected, ::snapshot) ?: return false
        restore(entry)
        return true
    }

    fun redo(): Boolean {
        val entry = history.redo(selected, ::snapshot) ?: return false
        restore(entry)
        return true
    }

    fun clearHistory() {
        history.clear()
    }

    fun endEdit() {
        history.endCoalescing()
    }

    private fun restore(entry: HistoryEntry) {
        items.clear()
        items.addAll(entry.components)
        for (index in items.indices) {
            items[index].id = ComponentDefinitions.pack(id, index)
        }
        selected = entry.selected
        structureDirty = true
        dirty = true
    }

    fun markSaved() {
        dirty = false
    }


    fun <T> edit(label: String, coalesceKey: String? = null, block: () -> T): T {
        beginEdit(label, coalesceKey)
        val parentsBefore = IntArray(items.size) { items[it].parent }
        val result = block()
        var structural = false
        for (index in items.indices) {
            if (items[index].parent != parentsBefore[index]) {
                structural = true
                break
            }
        }
        if (structural) {
            structureDirty = true
        } else {
            statesDirty = true
        }
        return result
    }

    fun editComponent(
        index: Int,
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Boolean {
        val component = items.getOrNull(index) ?: return false
        edit(label, coalesceKey) { block(component) }
        return true
    }

    fun editComponents(
        indices: Collection<Int>,
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Int {
        val targets = indices.filter { it in items.indices }
        if (targets.isEmpty()) {
            return 0
        }
        edit(label, coalesceKey) { targets.forEach { block(items[it]) } }
        return targets.size
    }

    private fun beginEdit(label: String, coalesceKey: String? = null) {
        lastReindexIssues = emptyList()
        history.record(label, coalesceKey, selected, ::snapshot)
        dirty = true
    }


    fun addComponent(type: Int, parentIndex: Int = ROOT, insertAt: Int = -1): Int =
        addComponent(ComponentTemplates.defaults(type), parentIndex, insertAt)

    fun addComponent(
        definition: InterfaceComponentDefinition,
        parentIndex: Int = ROOT,
        insertAt: Int = -1
    ): Int {
        if (!validParent(parentIndex) || items.size >= ComponentDefinitions.MAX_COMPONENTS) {
            return -1
        }
        val label = "Add ${ComponentType.name(definition.type)}"
        beginEdit(label)
        val position = insertionPosition(parentIndex, insertAt)
        val slots = ArrayList<Slot>(items.size + 1)
        for (index in 0 until position) {
            slots.add(Slot(index))
        }
        slots.add(Slot(-1, definition, parentOld = parentIndex))
        for (index in position until items.size) {
            slots.add(Slot(index))
        }
        applySlots(slots)
        selected = position
        return position
    }

    fun addTemplate(
        template: ComponentTemplate,
        parentIndex: Int = ROOT,
        insertAt: Int = -1
    ): IntArray {
        if (!validParent(parentIndex)) {
            return IntArray(0)
        }
        val created = template.create()
        if (created.isEmpty() || items.size + created.size > ComponentDefinitions.MAX_COMPONENTS) {
            return IntArray(0)
        }
        beginEdit("Add ${template.name}")
        val position = insertionPosition(parentIndex, insertAt)
        val slots = ArrayList<Slot>(items.size + created.size)
        for (index in 0 until position) {
            slots.add(Slot(index))
        }
        for (component in created) {
            val slot = Slot(-1, component.definition)
            if (component.parent == ComponentTemplates.ATTACH) {
                slot.parentOld = parentIndex
            } else {
                slot.parentSlot = position + component.parent
            }
            slots.add(slot)
        }
        for (index in position until items.size) {
            slots.add(Slot(index))
        }
        applySlots(slots)
        selected = position
        return IntArray(created.size) { position + it }
    }

    fun deleteComponent(index: Int): Boolean = deleteComponents(listOf(index)) > 0

    fun deleteComponents(indices: Collection<Int>): Int {
        val roots = topLevel(indices)
        if (roots.isEmpty()) {
            return 0
        }
        val map = childMap()
        val removed = HashSet<Int>()
        for (root in roots) {
            removed.addAll(collectSubtree(root, map))
        }
        val count = removed.size
        val label = if (count == 1) {
            "Delete ${ComponentType.name(items[roots[0]].type)}"
        } else {
            "Delete $count components"
        }
        beginEdit(label)
        var fallback = selected
        while (fallback in items.indices && fallback in removed) {
            fallback = items[fallback].parent
        }
        val slots = ArrayList<Slot>(items.size - count)
        for (index in items.indices) {
            if (index !in removed) {
                slots.add(Slot(index))
            }
        }
        applySlots(slots, selectionSource = fallback)
        return count
    }

    fun duplicateComponent(index: Int): Int = duplicateComponents(listOf(index)).firstOrNull() ?: -1

    fun duplicateComponents(indices: Collection<Int>): IntArray {
        val roots = topLevel(indices)
        if (roots.isEmpty()) {
            return IntArray(0)
        }
        val map = childMap()
        val groups = roots.map { root ->
            val order = orderedSubtree(root, map)
            DuplicateGroup(order, (collectSubtree(root, map).max()) + 1, IntArray(order.size))
        }
        val total = groups.sumOf { it.order.size }
        if (items.size + total > ComponentDefinitions.MAX_COMPONENTS) {
            return IntArray(0)
        }
        val label = if (roots.size == 1) {
            "Duplicate ${ComponentType.name(items[roots[0]].type)}"
        } else {
            "Duplicate ${roots.size} components"
        }
        beginEdit(label)
        val byPosition = groups.groupBy { it.position }
        val slots = ArrayList<Slot>(items.size + total)
        for (position in 0..items.size) {
            byPosition[position]?.forEach { group ->
                group.order.forEachIndexed { offset, node ->
                    group.slots[offset] = slots.size
                    slots.add(Slot(-1, ComponentDefinitions.deepCopy(items[node])))
                }
            }
            if (position < items.size) {
                slots.add(Slot(position))
            }
        }
        for (group in groups) {
            val positions = HashMap<Int, Int>(group.order.size)
            group.order.forEachIndexed { offset, node -> positions[node] = offset }
            group.order.forEachIndexed { offset, node ->
                val slot = slots[group.slots[offset]]
                val parent = items[node].parent
                val within = if (offset == 0) null else positions[parent]
                if (within == null) {
                    slot.parentOld = parent
                } else {
                    slot.parentSlot = group.slots[within]
                }
            }
        }
        val oldToNew = applySlots(slots, selectionSource = roots[0])
        repointCopies(groups, oldToNew)
        selected = groups[0].slots[0]
        return IntArray(groups.size) { groups[it].slots[0] }
    }

    fun reparent(index: Int, newParent: Int, insertAt: Int = -1): Boolean {
        if (index !in items.indices) {
            return false
        }
        val label = if (newParent == items[index].parent) "Reorder component" else "Reparent component"
        return moveSubtrees(listOf(index), newParent, insertAt, label) > 0
    }

    fun reparentAll(indices: Collection<Int>, newParent: Int, insertAt: Int = -1): Int {
        val roots = topLevel(indices)
        if (roots.isEmpty()) {
            return 0
        }
        val label = if (roots.size == 1) "Reparent component" else "Reparent ${roots.size} components"
        return moveSubtrees(roots, newParent, insertAt, label)
    }

    private fun moveSubtrees(roots: List<Int>, newParent: Int, insertAt: Int, label: String): Int {
        if (roots.isEmpty() || !validParent(newParent)) {
            return 0
        }
        val map = childMap()
        val moving = HashSet<Int>()
        val order = ArrayList<Int>()
        for (root in roots) {
            order.addAll(orderedSubtree(root, map))
            moving.addAll(collectSubtree(root, map))
        }
        if (newParent in moving) {
            return 0
        }
        val remaining = items.indices.filter { it !in moving }
        val position = insertionPositionAmong(remaining, moving, newParent, insertAt, map)
        beginEdit(label)
        for (root in roots) {
            items[root].parent = newParent
        }
        val slots = ArrayList<Slot>(items.size)
        for (offset in 0..remaining.size) {
            if (offset == position) {
                order.forEach { slots.add(Slot(it)) }
            }
            if (offset < remaining.size) {
                slots.add(Slot(remaining[offset]))
            }
        }
        applySlots(slots)
        return roots.size
    }

    fun move(index: Int, delta: Int): Boolean {
        if (index !in items.indices || delta == 0) {
            return false
        }
        val parent = items[index].parent
        val siblings = children(parent)
        val current = siblings.indexOf(index)
        val target = (current + delta).coerceIn(0, siblings.lastIndex)
        if (target == current) {
            return false
        }
        return reorder(index, target)
    }

    fun reorder(index: Int, toPosition: Int): Boolean {
        if (index !in items.indices) {
            return false
        }
        return reparent(index, items[index].parent, toPosition)
    }

    fun setType(index: Int, type: Int, applyDefaults: Boolean = false): Boolean {
        val component = items.getOrNull(index) ?: return false
        if (component.type == type) {
            return false
        }
        return editComponent(index, "Set type ${ComponentType.name(type)}") { definition ->
            definition.type = type
            if (applyDefaults) {
                applyTypeDefaults(definition, type)
            }
        }
    }


    fun bakeLiveState(indices: Collection<Int>? = null): Int {
        val current = cachedInstance ?: return 0
        val targets = (indices ?: items.indices).filter { it in items.indices }
        if (targets.isEmpty()) {
            return 0
        }
        val label = if (targets.size == 1) "Bake live state" else "Bake live state (${targets.size})"
        edit(label) {
            for (index in targets) {
                val state = current.state(index) ?: continue
                bake(state, items[index])
            }
        }
        return targets.size
    }

    private fun bake(state: ComponentState, definition: InterfaceComponentDefinition) {
        definition.basePositionX = state.basePositionX
        definition.basePositionY = state.basePositionY
        definition.baseWidth = state.baseWidth
        definition.baseHeight = state.baseHeight
        definition.horizontalSizeMode = state.horizontalSizeMode.toByte()
        definition.verticalSizeMode = state.verticalSizeMode.toByte()
        definition.horizontalPositionMode = state.horizontalPositionMode.toByte()
        definition.verticalPositionMode = state.verticalPositionMode.toByte()
        definition.scrollWidth = state.scrollWidth
        definition.scrollHeight = state.scrollHeight
        definition.hidden = state.hidden
        definition.colour = state.colour
        definition.alpha = state.transparency
        definition.filled = state.filled
        definition.text = state.text
        definition.fontId = state.fontId
        definition.horizontalTextAlign = state.horizontalTextAlign
        definition.verticalTextAlign = state.verticalTextAlign
        definition.lineHeight = state.lineSpacing
        definition.lineCount = state.maxTextLines
        definition.shaded = state.shadow
        definition.defaultImage = state.graphicId
        definition.imageRotation = state.graphicAngle
        definition.aBoolean4861 = state.tiling
        definition.flipVertical = state.flipVertical
        definition.flipHorizontal = state.flipHorizontal
        definition.rotation = state.borderThickness
        definition.backgroundColour = state.graphicShadow
        definition.defaultMediaType = state.mediaType
        definition.defaultMediaId = state.mediaId
        definition.animation = state.animation
        definition.viewportX = state.modelOriginX
        definition.viewportY = state.modelOriginY
        definition.viewportZ = state.modelOriginZ
        definition.graphicPitch = state.modelPitch
        definition.graphicRoll = state.modelRoll
        definition.graphicYaw = state.modelYaw
        definition.graphicScale = state.modelScale
        definition.viewportWidth = state.modelAspectWidth
        definition.viewportHeight = state.modelAspectHeight
        definition.lineWidth = state.lineWidth
        definition.lineMirrored = state.lineMirrored
    }


    fun validate(assets: InterfaceAssets = InterfaceAssets.NONE): List<InterfaceIssue> =
        InterfaceValidator.validate(id, items, assets)


    fun copyAs(newId: Int): InterfaceDocument {
        val copies = List(items.size) { ComponentDefinitions.deepCopy(items[it]) }
        val count = copies.size
        if (remapHashes && newId > 0) {
            for (copy in copies) {
                ComponentDefinitions.remapHookArgs(copy) { value ->
                    if (ComponentDefinitions.isComponentHash(value, id, count)) {
                        ComponentDefinitions.pack(newId, ComponentDefinitions.indexOf(value))
                    } else {
                        null
                    }
                }
            }
        }
        return InterfaceDocument(newId, copies)
    }


    private class Slot(
        val source: Int,
        val created: InterfaceComponentDefinition? = null,
        var parentOld: Int = UNSET,
        var parentSlot: Int = UNSET
    )

    private class DuplicateGroup(
        val order: List<Int>,
        val position: Int,
        val slots: IntArray
    )

    private fun applySlots(slots: List<Slot>, selectionSource: Int = selected): IntArray {
        val previousCount = items.size
        val oldToNew = IntArray(previousCount) { -1 }
        slots.forEachIndexed { index, slot ->
            if (slot.source >= 0) {
                oldToNew[slot.source] = index
            }
        }
        val next = ArrayList<InterfaceComponentDefinition>(slots.size)
        for (slot in slots) {
            next.add(slot.created ?: items[slot.source])
        }
        val issues = ArrayList<InterfaceIssue>()
        next.forEachIndexed { index, definition ->
            val slot = slots[index]
            definition.parent = when {
                slot.source >= 0 -> mapParent(definition.parent, oldToNew, index, issues)
                slot.parentSlot != UNSET -> slot.parentSlot
                else -> mapParent(slot.parentOld, oldToNew, index, issues)
            }
            definition.id = ComponentDefinitions.pack(id, index)
        }
        if (remapHashes) {
            next.forEachIndexed { index, definition ->
                ComponentDefinitions.remapHookArgs(definition) { value ->
                    if (!ComponentDefinitions.isComponentHash(value, id, previousCount)) {
                        return@remapHookArgs null
                    }
                    val target = oldToNew[ComponentDefinitions.indexOf(value)]
                    if (target >= 0) {
                        ComponentDefinitions.pack(id, target)
                    } else {
                        issues.add(
                            InterfaceIssue(
                                IssueSeverity.WARNING, index, "HOOK_ARG_CLEARED",
                                "a hook argument pointed at deleted component " +
                                    "${ComponentDefinitions.indexOf(value)} and was set to -1"
                            )
                        )
                        -1
                    }
                }
            }
        }
        items.clear()
        items.addAll(next)
        lastReindexIssues = issues
        selected = if (selectionSource in oldToNew.indices) oldToNew[selectionSource] else -1
        structureDirty = true
        dirty = true
        return oldToNew
    }

    private fun mapParent(
        parent: Int,
        oldToNew: IntArray,
        index: Int,
        issues: MutableList<InterfaceIssue>
    ): Int {
        if (parent < 0 || parent >= oldToNew.size) {
            return ROOT
        }
        val mapped = oldToNew[parent]
        if (mapped >= 0) {
            return mapped
        }
        issues.add(
            InterfaceIssue(
                IssueSeverity.WARNING, index, "PARENT_CLEARED",
                "parent $parent no longer exists; the component was moved to the top level"
            )
        )
        return ROOT
    }

    private fun repointCopies(groups: List<DuplicateGroup>, oldToNew: IntArray) {
        if (!remapHashes) {
            return
        }
        val count = items.size
        for (group in groups) {
            val originals = HashMap<Int, Int>(group.order.size)
            group.order.forEachIndexed { offset, node -> originals[oldToNew[node]] = group.slots[offset] }
            for (slot in group.slots) {
                ComponentDefinitions.remapHookArgs(items[slot]) { value ->
                    if (!ComponentDefinitions.isComponentHash(value, id, count)) {
                        return@remapHookArgs null
                    }
                    originals[ComponentDefinitions.indexOf(value)]?.let { ComponentDefinitions.pack(id, it) }
                }
            }
        }
    }

    private fun childMap(): Map<Int, List<Int>> = items.indices.groupBy { items[it].parent }

    private fun collectSubtree(index: Int, map: Map<Int, List<Int>>): List<Int> {
        val result = ArrayList<Int>()
        val seen = BooleanArray(items.size)
        val queue = ArrayDeque<Int>()
        queue.addLast(index)
        seen[index] = true
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            for (child in map[current] ?: emptyList()) {
                if (!seen[child]) {
                    seen[child] = true
                    queue.addLast(child)
                }
            }
        }
        return result
    }

    private fun orderedSubtree(index: Int, map: Map<Int, List<Int>>): List<Int> {
        val result = ArrayList<Int>()
        val seen = BooleanArray(items.size)
        fun walk(current: Int) {
            if (seen[current]) {
                return
            }
            seen[current] = true
            result.add(current)
            for (child in map[current] ?: emptyList()) {
                walk(child)
            }
        }
        walk(index)
        return result
    }

    private fun validParent(parentIndex: Int): Boolean =
        parentIndex == ROOT || parentIndex in items.indices

    private fun insertionPosition(parentIndex: Int, insertAt: Int): Int {
        val siblings = children(parentIndex)
        if (insertAt in siblings.indices) {
            return siblings[insertAt]
        }
        val last = siblings.lastOrNull()
        if (last != null) {
            return collectSubtree(last, childMap()).max() + 1
        }
        return if (parentIndex in items.indices) parentIndex + 1 else items.size
    }

    private fun insertionPositionAmong(
        remaining: List<Int>,
        moving: Set<Int>,
        parentIndex: Int,
        insertAt: Int,
        map: Map<Int, List<Int>>
    ): Int {
        val siblings = (map[parentIndex] ?: emptyList()).filter { it !in moving }
        if (insertAt in siblings.indices) {
            return remaining.indexOf(siblings[insertAt]).coerceAtLeast(0)
        }
        val last = siblings.lastOrNull()
        if (last != null) {
            val end = collectSubtree(last, map).filter { it !in moving }.max()
            return remaining.indexOf(end) + 1
        }
        if (parentIndex in items.indices) {
            return remaining.indexOf(parentIndex) + 1
        }
        return remaining.size
    }

    private fun topLevel(indices: Collection<Int>): List<Int> {
        val requested = indices.filter { it in items.indices }.toHashSet()
        return requested.filter { index ->
            var current = items[index].parent
            var guard = items.size
            while (current in items.indices && guard-- > 0) {
                if (current in requested) {
                    return@filter false
                }
                current = items[current].parent
            }
            true
        }.sorted()
    }

    private fun applyTypeDefaults(definition: InterfaceComponentDefinition, type: Int) {
        val defaults = ComponentTemplates.defaults(type)
        definition.scrollWidth = defaults.scrollWidth
        definition.scrollHeight = defaults.scrollHeight
        definition.disableHover = defaults.disableHover
        definition.colour = defaults.colour
        definition.filled = defaults.filled
        definition.alpha = defaults.alpha
        definition.fontId = defaults.fontId
        definition.text = defaults.text
        definition.lineHeight = defaults.lineHeight
        definition.horizontalTextAlign = defaults.horizontalTextAlign
        definition.verticalTextAlign = defaults.verticalTextAlign
        definition.shaded = defaults.shaded
        definition.defaultImage = defaults.defaultImage
        definition.imageRotation = defaults.imageRotation
        definition.aBoolean4861 = defaults.aBoolean4861
        definition.imageRepeat = defaults.imageRepeat
        definition.rotation = defaults.rotation
        definition.backgroundColour = defaults.backgroundColour
        definition.flipVertical = defaults.flipVertical
        definition.flipHorizontal = defaults.flipHorizontal
        definition.defaultMediaType = defaults.defaultMediaType
        definition.defaultMediaId = defaults.defaultMediaId
        definition.animated = defaults.animated
        definition.centreType = defaults.centreType
        definition.ignoreZBuffer = defaults.ignoreZBuffer
        definition.viewportX = defaults.viewportX
        definition.viewportY = defaults.viewportY
        definition.viewportZ = defaults.viewportZ
        definition.graphicPitch = defaults.graphicPitch
        definition.graphicRoll = defaults.graphicRoll
        definition.graphicYaw = defaults.graphicYaw
        definition.graphicScale = defaults.graphicScale
        definition.animation = defaults.animation
        definition.viewportWidth = defaults.viewportWidth
        definition.viewportHeight = defaults.viewportHeight
        definition.lineWidth = defaults.lineWidth
        definition.lineMirrored = defaults.lineMirrored
    }

    companion object {

        const val ROOT = ComponentDefinitions.ROOT

        fun decode(cache: Cache, id: Int): InterfaceDocument {
            val decoder = InterfaceDecoder()
            val definitions = decoder.create(id + 1)
            decoder.load(definitions, cache, id)
            return of(definitions[id], id)
        }

        fun of(definition: InterfaceDefinition, id: Int = definition.id): InterfaceDocument =
            InterfaceDocument(id, definition.components?.toList() ?: emptyList())

        fun empty(id: Int): InterfaceDocument = InterfaceDocument(id)
    }
}
