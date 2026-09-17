package com.rs.game.content.items

import com.rs.game.content.ItemConstants.ItemDegrade
import com.rs.game.model.entity.player.Player
import com.rs.game.model.item.ItemTooltip
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.model.Item

object DegradeCharges {
    const val KEY = "combatCharges"

    @JvmStatic
    fun percent(item: Item): Int? {
        val charges = item.getMetaDataI(KEY)
        if (charges < 0) return null
        val max = ItemDegrade.forId(item.id)?.defaultCharges ?: return null
        if (max <= 0) return null
        return ((charges * 100 + max - 1) / max).coerceIn(0, 100)
    }

    @JvmStatic
    fun drain(player: Player, slot: Int, item: Item) {
        val before = percent(item)
        item.addMetaData(KEY, item.getMetaDataI(KEY) - 1)
        if (percent(item) != before) player.equipment.refresh(slot)
    }
}

@ServerStartupEvent
fun mapDegradeChargeTooltip() {
    ItemTooltips.register(ItemTooltip { _, item -> DegradeCharges.percent(item)?.let { "Charge: $it%" } })
}
