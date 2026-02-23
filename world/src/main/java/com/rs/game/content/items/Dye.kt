package com.rs.game.content.items

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem

enum class Dye(val id: Int, val inputID: Int?, val inputQuantity: Int?) {
    RED(1763, 1951, 3),
    YELLOW(1765, 1957, 2),
    BLUE(1767, 1793, 2),
    ORANGE(1769, null, null),
    PURPLE(1773, null, null),
    GREEN(1771, null, null);

    companion object {
        fun fromId(id: Int): Dye? {
            return entries.find { it.id == id }
        }
    }
}

@ServerStartupEvent
fun mapDyeMixing() {
    val dyeCombinations = mapOf(
        setOf(Dye.RED.id, Dye.YELLOW.id) to Dye.ORANGE.id,
        setOf(Dye.BLUE.id, Dye.YELLOW.id) to Dye.GREEN.id,
        setOf(Dye.BLUE.id, Dye.RED.id) to Dye.PURPLE.id
    )
    onItemOnItem(*Dye.entries.map { it.id }.toIntArray()) { e ->
        val result = dyeCombinations[setOf(e.item1.id, e.item2.id)]
        if (result != null) {
            e.player.inventory.deleteItem(e.item1.id, 1)
            e.player.inventory.deleteItem(e.item2.id, 1)
            e.player.inventory.addItem(result)
        }
    }
}

