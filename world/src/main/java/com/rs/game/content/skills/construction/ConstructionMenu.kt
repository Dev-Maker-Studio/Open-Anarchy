package com.rs.game.content.skills.construction

import com.rs.game.model.entity.player.Player
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.gameval.gen.Varbit
import org.darkan.core.model.IFEvents
import org.darkan.core.model.Item

object ConstructionMenu {
    private const val TAB_SLOTS = 2
    private const val ROW_SLOTS = 3
    private const val FIRST_CATEGORY = 0
    private val AMOUNTS = intArrayOf(1, 5, 10)
    private val ROW_OPTIONS = AMOUNTS.indices.toList().toIntArray()

    fun open(player: Player) {
        if (player.interfaceManager.containsScreenInter()) {
            player.sendMessage("Please close the interface you have open before opening the construction menu.")
            return
        }
        cancelPlacement(player)
        player.stopAll()
        player.interfaceManager.sendInterface(Interface.construction_menu)
        player.packets.setIFEvents(IFEvents(Component.construction_menu.tabs, 0, ConstructionCategory.entries.size * TAB_SLOTS - 1).enableRightClickOptions(0))
        player.packets.setIFEvents(IFEvents(Component.construction_menu.list, 0, ConstructionCatalogue.largestCategory * ROW_SLOTS - 1).enableRightClickOptions(*ROW_OPTIONS))
        select(player, FIRST_CATEGORY)
    }

    fun select(player: Player, index: Int) {
        val category = ConstructionCategory.entries.getOrNull(index) ?: return
        player.vars.setVarBit(Varbit.construction_menu_category, index)
        player.packets.sendItems(Inv.construction_menu, listing(category))
    }

    fun make(player: Player, slot: Int, opNum: Int) {
        val category = ConstructionCategory.entries.getOrNull(player.vars.getVarBit(Varbit.construction_menu_category)) ?: return
        val piece = ConstructionCatalogue.forCategory(category).getOrNull(slot / ROW_SLOTS) ?: return
        val times = AMOUNTS.getOrNull(opNum - 1) ?: return
        makePiece(player, piece, times)
    }

    private fun listing(category: ConstructionCategory): Array<Item?> =
        ConstructionCatalogue.forCategory(category).map { Item(it.item, 1).addMetaData(ItemTooltips.METADATA_KEY, tooltip(it)) }.toTypedArray<Item?>()

    private fun tooltip(piece: ConstructionPiece): String =
        "Materials: ${ItemTooltips.value(piece.materials())}<br>Hit points: ${ItemTooltips.value(piece.hitPoints)}<br>Experience: ${ItemTooltips.value(piece.xp.toInt())}"

    fun tabOf(slot: Int): Int = slot / TAB_SLOTS
}

@ServerStartupEvent
fun mapConstructionMenu() {
    onButtonClick(Interface.construction_menu) { e ->
        when (e.component) {
            Component.construction_menu.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.construction_menu.tabs -> ConstructionMenu.select(e.player, ConstructionMenu.tabOf(e.slotId))
            Component.construction_menu.list -> ConstructionMenu.make(e.player, e.slotId, e.opNum)
        }
    }
}
