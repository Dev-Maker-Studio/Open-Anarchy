package com.rs.game.content.items

import com.rs.game.content.ItemConstants.ItemDegrade
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.model.Item

const val DEGRADE_CHARGES_KEY = "combatCharges"

fun Item.degradeChargePercent(): Int? {
    val charges = getMetaDataI(DEGRADE_CHARGES_KEY)
    if (charges < 0) return null
    val max = ItemDegrade.forId(id)?.defaultCharges ?: return null
    if (max <= 0) return null
    return ((charges * 100 + max - 1) / max).coerceIn(0, 100)
}

fun Item.drainDegradeCharge() {
    val metadata = getMetaData() ?: return
    val before = degradeChargePercent()
    metadata[DEGRADE_CHARGES_KEY] = getMetaDataI(DEGRADE_CHARGES_KEY) - 1
    if (degradeChargePercent() != before) markChanged()
}

@ServerStartupEvent
fun mapDegradeChargeTooltip() {
    ItemTooltips.metadata(DEGRADE_CHARGES_KEY) { _, item, _ ->
        item.degradeChargePercent()?.let { "Charge: ${ItemTooltips.value("$it%")}" }
    }
}
