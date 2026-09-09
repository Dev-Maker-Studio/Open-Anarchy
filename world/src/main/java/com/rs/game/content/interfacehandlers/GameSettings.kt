package com.rs.game.content.interfacehandlers

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Varbit
import org.darkan.core.model.IFEvents

enum class GameSetting(val varbit: Int, val values: Int) {
    INTERFACE_SKIN(Varbit.option_gameframe_skin, 2);

    fun select(player: Player, value: Int) {
        if (value in 0 until values) player.vars.saveVarBit(varbit, value)
    }
}

object GameSettings {
    private const val MAX_VALUES = 16

    fun open(player: Player) {
        if (player.interfaceManager.containsScreenInter()) {
            player.sendMessage("Please close the interface you have open before changing your game settings.")
            return
        }
        player.stopAll()
        player.interfaceManager.sendInterface(Interface.game_settings)
        player.packets.setIFEvents(IFEvents(Component.game_settings.dropdown, 0, MAX_VALUES - 1).enableRightClickOptions(*IntArray(GameSetting.entries.size) { it }))
    }

    fun select(player: Player, row: Int, value: Int) {
        GameSetting.entries.getOrNull(row)?.select(player, value)
    }
}

@ServerStartupEvent
fun mapGameSettings() {
    onButtonClick(Interface.game_settings) { e ->
        when (e.component) {
            Component.game_settings.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.game_settings.dropdown -> GameSettings.select(e.player, e.opNum - 1, e.slotId)
        }
    }
}
