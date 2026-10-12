package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

class HistoryEntry(
    val label: String,
    val coalesceKey: String?,
    val components: Array<InterfaceComponentDefinition>,
    val selected: Int,
    internal var timestamp: Long
)

class InterfaceHistory(
    val limit: Int = DEFAULT_LIMIT,
    val coalesceWindowMillis: Long = DEFAULT_COALESCE_WINDOW
) {

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    var clock: () -> Long = { System.currentTimeMillis() }

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val undoLabel: String? get() = undoStack.lastOrNull()?.label

    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    val undoLabels: List<String> get() = undoStack.map { it.label }

    val redoLabels: List<String> get() = redoStack.map { it.label }

    val size: Int get() = undoStack.size

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun record(
        label: String,
        coalesceKey: String?,
        selected: Int,
        snapshot: () -> Array<InterfaceComponentDefinition>
    ): Boolean {
        val now = clock()
        val top = undoStack.lastOrNull()
        if (coalesceKey != null && top != null && top.coalesceKey == coalesceKey &&
            now - top.timestamp <= coalesceWindowMillis
        ) {
            top.timestamp = now
            return false
        }
        redoStack.clear()
        undoStack.addLast(HistoryEntry(label, coalesceKey, snapshot(), selected, now))
        while (undoStack.size > limit) {
            undoStack.removeFirst()
        }
        return true
    }

    fun endCoalescing() {
        undoStack.lastOrNull()?.timestamp = Long.MIN_VALUE / 2
    }

    fun undo(selected: Int, current: () -> Array<InterfaceComponentDefinition>): HistoryEntry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(HistoryEntry(entry.label, null, current(), selected, clock()))
        return entry
    }

    fun redo(selected: Int, current: () -> Array<InterfaceComponentDefinition>): HistoryEntry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(HistoryEntry(entry.label, null, current(), selected, clock()))
        return entry
    }

    companion object {
        const val DEFAULT_LIMIT = 64
        const val DEFAULT_COALESCE_WINDOW = 600L
    }
}
