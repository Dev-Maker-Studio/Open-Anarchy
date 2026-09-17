package com.rs.net.encoders

import org.darkan.core.net.prot.dto.InvTooltip

class InvTooltipTracker {
    private val sent = HashMap<Long, Array<String?>>()

    fun full(key: Int, negativeKey: Boolean, tooltips: Array<String?>) {
        sent[id(key, negativeKey)] = tooltips.copyOf()
    }

    fun update(key: Int, negativeKey: Boolean, slot: Int, tooltip: String?): InvTooltip {
        val id = id(key, negativeKey)
        var slots = sent[id] ?: arrayOfNulls(slot + 1)
        if (slots.size <= slot) slots = slots.copyOf(slot + 1)
        sent[id] = slots
        val previous = slots[slot]
        slots[slot] = tooltip
        return when (tooltip) {
            null -> InvTooltip.Clear
            previous -> InvTooltip.Keep
            else -> InvTooltip.Set(tooltip)
        }
    }

    private fun id(key: Int, negativeKey: Boolean) = key.toLong() or if (negativeKey) 1L shl 32 else 0L
}
