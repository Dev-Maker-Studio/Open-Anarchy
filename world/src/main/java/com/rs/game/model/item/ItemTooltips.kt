package com.rs.game.model.item

import com.rs.game.model.entity.player.Player
import org.darkan.core.model.Item

fun interface ItemTooltip {
    fun text(player: Player, item: Item): String?
}

object ItemTooltips {
    const val METADATA_KEY = "tooltip"
    private const val NAME_COLOUR = "ff9040"

    private val byObj = HashMap<Int, MutableList<ItemTooltip>>()
    private val global = ArrayList<ItemTooltip>()

    @JvmStatic
    fun register(tooltip: ItemTooltip) {
        global += tooltip
    }

    @JvmStatic
    fun register(objs: IntArray, tooltip: ItemTooltip) {
        for (obj in objs) byObj.getOrPut(obj) { ArrayList() } += tooltip
    }

    @JvmStatic
    fun of(player: Player, item: Item?): String? {
        if (item == null) return null
        val lines = listOfNotNull(item.getMetaData(METADATA_KEY) as? String) +
            (byObj[item.id].orEmpty().asSequence() + global.asSequence()).mapNotNull { it.text(player, item) }
        if (lines.isEmpty()) return null
        return "<col=$NAME_COLOUR>${item.name}</col><br>" + lines.joinToString("<br>")
    }
}
