// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.game.content.world.npcs

import com.rs.engine.dialogue.Conversation
import com.rs.engine.dialogue.Dialogue
import com.rs.engine.dialogue.HeadE
import com.rs.game.content.LootBeam
import com.rs.game.ge.GE
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.PluginEventHandler
import com.rs.plugin.handlers.NPCClickHandler
import com.rs.plugin.handlers.NPCInteractionDistanceHandler
import com.rs.plugin.handlers.ObjectClickHandler

@PluginEventHandler
class Banker(player: Player, npc: NPC) : Conversation(player) {

    init {
        addNPC(npc.id, HeadE.CHEERFUL_EXPOSITION, "Good day. How may I help you?")
        addOptions { options ->
            options.option("I'd like to access my bank account, please.") { player.bank.open() }
            options.option("I'd like to check my PIN settings.") { player.bank.openPinSettings() }
            options.option("I'd like to see my collection box.") { GE.openCollection(player) }
            options.option("What is this place?", Dialogue()
                .addNPC(npc.id, HeadE.CHEERFUL_EXPOSITION, "This is a branch of the Bank of Gielinor. We have branches in many towns.")
                .addOptions { nested ->
                    nested.option("And what do you do?", Dialogue()
                        .addNPC(npc.id, HeadE.CHEERFUL_EXPOSITION, "We will look after your items and money for you. Leave your valuables with us if you want to keep them safe."))
                    nested.option("Didn't you used to be called the Bank of Varrock?", Dialogue()
                        .addNPC(npc.id, HeadE.CALM_TALK, "Yes we did, but people kept on coming into our branches outside of Varrock and telling us that our signs were wrong. They acted as if we didn't know what town we were in or something."))
                })
            options.option("I'd like to change my threshold for valuable loot notifications.", LootBeam.settings(player, npc.id))
        }

        create()
    }

    companion object {
        @JvmField
        val bankerDistance = NPCInteractionDistanceHandler(arrayOf<Any>("Banker")) { _, _ -> 1 }

        @JvmField
        val bankerHandler = NPCClickHandler(arrayOf<Any>("Banker", 14707, 2619, 13455, 15194)) { e ->
            when (e.option) {
                "Bank" -> e.player.bank.open()
                "Collect" -> GE.openCollection(e.player)
                "Talk-to" -> e.player.startConversation(Banker(e.player, e.npc))
            }
        }

        @JvmField
        val bankObjHandler = ObjectClickHandler(arrayOf<Any>("Bank booth", "Bank", "Bank chest", "Bank table", "Counter", "Shantay chest", "Darkmeyer Treasury")) { e ->
            when (e.option) {
                "Bank" -> e.player.bank.open()
                "Collect" -> GE.openCollection(e.player)
                "Use" -> if (e.obj.definitions.getName(e.player.vars) == "Bank chest") e.player.bank.open()
                "Open" -> if (e.obj.definitions.getName(e.player.vars) == "Shantay chest") e.player.bank.open()
                else -> e.player.sendMessage("Unhandled bank object option: ${e.option}")
            }
        }

        @JvmField
        val depositBoxHandler = ObjectClickHandler(arrayOf<Any>("Bank deposit box", "Deposit box", "Deposit Box", "Deposit chest", "Pulley lift")) { e ->
            when (e.option) {
                "Deposit", "Use" -> e.player.bank.openDepositBox()
                else -> e.player.sendMessage("Unhandled deposit box object option: ${e.option}")
            }
        }
    }
}
