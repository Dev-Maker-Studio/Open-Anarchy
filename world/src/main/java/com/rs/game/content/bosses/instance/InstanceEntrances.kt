package com.rs.game.content.bosses.instance

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.gameval.gen.Loc

private val ENTRANCES = mapOf(
    Loc.loc_26425 to "general_graardor",
    Loc.loc_26426 to "kree_arra",
    Loc.loc_26427 to "commander_zilyana",
    Loc.loc_26428 to "kril_tsutsaroth",
    Loc.loc_66011 to "nex",
    Loc.loc_1816 to "king_black_dragon",
    Loc.loc_12202 to "giant_mole",
    Loc.dagexp_bossroomladder_down to "dagannoth_kings",
    Loc.loc_23610 to "kalphite_queen",
    Loc.sum2_corp_beast_cave_exit_double to "corporeal_beast",
)

private val INSTANCE_EXITS = arrayOf<Any>(
    Loc.instance_exit_bandos_door,
    Loc.instance_exit_armadyl_door,
    Loc.instance_exit_saradomin_door,
    Loc.instance_exit_zamorak_door,
    Loc.instance_exit_kbd_lever,
    Loc.instance_exit_mole_rope,
    Loc.instance_exit_dagannoth_ladder,
    Loc.instance_exit_kalphite_rope,
    Loc.instance_exit_corp_passage,
)

@ServerStartupEvent
fun mapInstanceEntrances() {
    onObjectClick(*ENTRANCES.keys.toTypedArray<Any>()) { (player, obj, option) ->
        val def = ENTRANCES[obj.id]?.let { BossInstanceDefs[it] } ?: return@onObjectClick
        when (option) {
            "Start instance" -> InstanceInterface.open(player, def)
            "Join instance" -> InstanceInterface.promptJoin(player, def)
            "Rejoin-last instance" -> BossInstances.rejoin(player)?.let { player.sendMessage(it) }
        }
    }

    onObjectClick(*INSTANCE_EXITS) { (player) -> leave(player) }
}

private fun leave(player: Player) {
    val controller = player.controllerManager.controller as? BossInstanceController
    if (controller == null) player.sendMessage("You are not in an instance.")
    else controller.leaveInstance()
}
