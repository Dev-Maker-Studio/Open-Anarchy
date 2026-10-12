package org.darkan.tools.ui

import org.darkan.tools.rsinterface.ComponentState
import org.darkan.tools.rsinterface.ComponentTemplate
import org.darkan.tools.rsinterface.InterfaceDocument
import org.darkan.tools.rsinterface.InterfaceInstance
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

class InterfaceEditContext {

    var document: InterfaceDocument? = null
        private set

    var instance: InterfaceInstance? = null
        private set

    private val selected = LinkedHashSet<Int>()

    val selection: Set<Int> get() = selected

    var primary: Int = -1
        private set

    var revision: Int = 0
        private set

    var onEdit: (() -> Unit)? = null

    var revealed: Int = -1

    var clipboard: ComponentTemplate? = null


    fun attach(document: InterfaceDocument?) {
        this.document = document
        this.instance = null
        selected.clear()
        primary = -1
        revision++
    }

    fun frame(instance: InterfaceInstance?) {
        this.instance = instance
    }

    val open: Boolean get() = document != null

    val componentCount: Int get() = document?.componentCount ?: 0

    fun definition(index: Int): InterfaceComponentDefinition? = document?.component(index)

    fun state(index: Int): ComponentState? = instance?.state(index)

    fun primaryDefinition(): InterfaceComponentDefinition? = definition(primary)

    fun primaryState(): ComponentState? = state(primary)


    fun isSelected(index: Int): Boolean = index in selected

    fun select(index: Int, additive: Boolean = false) {
        if (index < 0) {
            clearSelection()
            return
        }
        if (!additive) {
            selected.clear()
            selected.add(index)
            primary = index
        } else if (!selected.remove(index)) {
            selected.add(index)
            primary = index
        } else {
            primary = selected.lastOrNull() ?: -1
        }
        document?.selected = primary
    }

    fun setSelection(indices: Collection<Int>, primaryIndex: Int = indices.lastOrNull() ?: -1) {
        selected.clear()
        selected.addAll(indices.filter { it >= 0 })
        primary = if (primaryIndex in selected) primaryIndex else selected.lastOrNull() ?: -1
        document?.selected = primary
    }

    fun clearSelection() {
        selected.clear()
        primary = -1
        document?.selected = -1
    }

    fun targets(): List<Int> = when {
        selected.isNotEmpty() -> selected.toList()
        primary >= 0 -> listOf(primary)
        else -> emptyList()
    }

    fun reveal(index: Int) {
        if (index < 0) {
            return
        }
        revealed = index
        select(index)
    }


    fun editPrimary(
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Boolean {
        val current = document ?: return false
        if (!current.editComponent(primary, label, coalesceKey, block)) {
            return false
        }
        touched()
        return true
    }

    fun editSelection(
        label: String,
        coalesceKey: String? = null,
        block: (InterfaceComponentDefinition) -> Unit
    ): Int {
        val current = document ?: return 0
        val changed = current.editComponents(targets(), label, coalesceKey, block)
        if (changed > 0) {
            touched()
        }
        return changed
    }

    fun structural(block: (InterfaceDocument) -> Unit) {
        val current = document ?: return
        current.selected = primary
        block(current)
        val landed = current.selected
        setSelection(if (landed >= 0) listOf(landed) else emptyList(), landed)
        touched()
    }

    fun endEdit() {
        document?.endEdit()
    }

    fun undo() {
        val current = document ?: return
        if (current.undo()) {
            afterHistory(current)
        }
    }

    fun redo() {
        val current = document ?: return
        if (current.redo()) {
            afterHistory(current)
        }
    }

    private fun afterHistory(current: InterfaceDocument) {
        val landed = current.selected
        setSelection(if (landed >= 0) listOf(landed) else emptyList(), landed)
        touched()
    }

    fun touched() {
        revision++
        onEdit?.invoke()
    }
}
