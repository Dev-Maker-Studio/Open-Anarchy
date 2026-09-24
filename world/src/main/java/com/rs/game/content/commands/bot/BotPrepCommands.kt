package com.rs.game.content.commands.bot

import com.rs.db.WorldDB
import com.rs.engine.command.Commands
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.Constants
import org.darkan.core.model.Rights
import world.gregs.voidps.type.Tile

@ServerStartupEvent
fun loadBotPrepCommands() {
    Commands.add(Rights.PLAYER, "botprep [tele x y (z) | item id (amount) | equip id | level skill level | reset]", "Prepares a verified bot for its task.") { p, args ->
        if (!p.isBot) return@add
        if (p.controllerManager.controller != null) {
            p.sendMessage("botprep refused: finish the current activity first.")
            return@add
        }
        WorldDB.getLogs().logCommand(p.username, "botprep " + args.joinToString(" "))
        when (args[0]) {
            "tele" -> {
                p.resetWalkSteps()
                p.tele(Tile.of(args[1].toInt(), args[2].toInt(), args.getOrNull(3)?.toInt() ?: 0))
            }
            "item" -> p.inventory.addItem(args[1].toInt(), args.getOrNull(2)?.toInt() ?: 1)
            "equip" -> equip(p, args[1].toInt())
            "level" -> setLevel(p, args[1].toInt(), args[2].toInt())
            "reset" -> {
                p.inventory.reset()
                p.equipment.reset()
                p.appearance.generateAppearanceData()
            }
            else -> p.sendMessage("Unknown botprep step: ${args[0]}")
        }
    }
}

private fun equip(p: Player, itemId: Int) {
    if (!p.inventory.addItem(itemId, 1)) return
    Equipment.sendWear(p, p.inventory.items.getThisItemSlot(itemId), itemId, true)
    Equipment.refreshEquipBonuses(p)
}

private fun setLevel(p: Player, skill: Int, level: Int) {
    if (skill !in Constants.SKILL_NAME.indices || level !in 1..(if (skill == Constants.DUNGEONEERING) 120 else 99)) return
    p.skills.set(skill, level)
    p.skills.setXp(skill, Skills.getXPForLevel(level).toDouble())
    p.appearance.generateAppearanceData()
}
