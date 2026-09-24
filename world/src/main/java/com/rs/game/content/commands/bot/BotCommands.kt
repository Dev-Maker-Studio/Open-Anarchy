package com.rs.game.content.commands.bot

import com.rs.db.WorldDB
import com.rs.engine.command.Commands
import com.rs.engine.quest.Quest
import com.rs.game.content.randomizeLook
import com.rs.game.content.combat.CombatDefinitions
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.Constants
import org.darkan.core.model.Rights
import world.gregs.voidps.type.Tile

private val botCommands: Map<String, (Player, List<String>) -> Unit> = mapOf(
    "tele" to { p, args ->
        p.resetWalkSteps()
        p.tele(Tile.of(args[0].toInt(), args[1].toInt(), args.getOrNull(2)?.toInt() ?: 0))
    },
    "item" to { p, args -> p.inventory.addItem(args[0].toInt(), args.getOrNull(1)?.toInt() ?: 1) },
    "equip" to { p, args ->
        val item = args[0].toInt()
        if (p.inventory.addItem(item, 1)) Equipment.sendWear(p, p.inventory.items.getThisItemSlot(item), item, true)
    },
    "level" to { p, args ->
        val skill = args[0].toInt()
        val level = args[1].toInt()
        if (skill in Constants.SKILL_NAME.indices && level in 1..(if (skill == Constants.DUNGEONEERING) 120 else 99)) {
            p.skills.set(skill, level)
            p.skills.setXp(skill, Skills.getXPForLevel(level).toDouble())
            p.appearance.generateAppearanceData()
        }
    },
    "quest" to { p, args -> p.questManager.completeQuest(Quest.valueOf(args[0].uppercase())) },
    "spellbook" to { p, args -> p.combatDefinitions.setSpellbook(CombatDefinitions.Spellbook.valueOf(args[0].uppercase())) },
    "prayers" to { p, args -> p.prayer.setPrayerBook(args[0].equals("curses", ignoreCase = true)) },
    "appearance" to { p, _ -> randomizeLook(p) },
    "reset" to { p, _ ->
        p.inventory.reset()
        p.equipment.reset()
        p.appearance.generateAppearanceData()
    },
)

@ServerStartupEvent
fun mapBotCommands() {
    Commands.add(Rights.PLAYER, "bot [${botCommands.keys.joinToString("|")}] (args)", "Account setup commands for verified bots.") { p, args ->
        if (!p.isBot) return@add
        val command = botCommands[args.firstOrNull()] ?: return@add p.sendMessage("Bot commands: ${botCommands.keys.joinToString()}")
        if (p.controllerManager.controller != null) return@add p.sendMessage("Bot commands are unavailable during an activity.")
        WorldDB.getLogs().logCommand(p.username, "bot " + args.joinToString(" "))
        command(p, args.drop(1))
    }
}
