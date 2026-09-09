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
package com.rs.game.content.world

import com.rs.game.content.world.areas.dungeons.UndergroundDungeonController
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import com.rs.plugin.kts.onItemOnItem
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item

private val UNLIT = intArrayOf(
    Obj.torch_unlit, Obj.obj_36, Obj.unlit_black_candle, Obj.candle_lantern_unlit, Obj.obj_4522,
    Obj.obj_4537, Obj.slayer_buglan_off, Obj.bullseye_lantern_unlit, Obj.cave_goblin_mining_helmet_unlit, Obj.tog_sapphire_lantern_unlit,
)

private val LIT = intArrayOf(
    Obj.torch_lit, Obj.obj_33, Obj.obj_32, Obj.candle_lantern_black_lit, Obj.oil_lamp_lit,
    Obj.oil_lantern_lit, Obj.slayer_buglan_on, Obj.bullseye_lantern_lit, Obj.cave_goblin_mining_helmet_lit, Obj.tog_sapphire_lantern_lit,
)

private val PERMANENT = intArrayOf(Obj.atseer_seers_headband, Obj.atseer_seers_headband2, Obj.atseer_seers_headband3, Obj.atseer_seers_headband4)

private const val EXPLOSIVE_SLOTS = 2

@ServerStartupEvent
fun mapLightSources() {
    onItemOnItem(UNLIT, intArrayOf(Obj.tinderbox)) { LightSource.lightSource(it.player, it.getUsedWith(Obj.tinderbox).slot) }
    onItemClick(*LIT.toTypedArray(), options = arrayOf("Extinguish")) { LightSource.extinguishSource(it.player, it.slotId, false) }
}

object LightSource {

    private fun litSources(player: Player): Sequence<Item> =
        (player.inventory.items.array().asSequence() + player.equipment.itemsCopy.asSequence()).filterNotNull()

    @JvmStatic
    fun hasExplosiveSource(player: Player): Boolean =
        litSources(player).any { litSlot(it.id) in 0 until EXPLOSIVE_SLOTS }

    @JvmStatic
    fun hasPermenantSource(player: Player): Boolean {
        if (player.inventory.containsOneItem(*PERMANENT) || player.equipment.containsOneItem(*PERMANENT)) return true
        return litSources(player).any { litSlot(it.id) >= EXPLOSIVE_SLOTS }
    }

    @JvmStatic
    fun hasLightSource(player: Player): Boolean = hasExplosiveSource(player) || hasPermenantSource(player)

    private fun litSlot(itemId: Int): Int = LIT.indexOf(itemId)

    private fun unlitSlot(itemId: Int): Int = UNLIT.indexOf(itemId)

    @JvmStatic
    fun extinguishSource(player: Player, itemSlot: Int, forceExtinguish: Boolean): Boolean {
        val item = player.inventory.getItem(itemSlot) ?: return false
        val slot = litSlot(item.id)
        if (slot == -1) return false
        if (!forceExtinguish && player.controllerManager.controller is UndergroundDungeonController) {
            player.sendMessage("You cannot extinguish the ${item.name.lowercase()} as you will not have a light source.")
            return false
        }
        player.inventory.replaceItem(UNLIT[slot], item.amount, itemSlot)
        player.sendMessage("You extinguish the ${item.name.lowercase()}.")
        return true
    }

    @JvmStatic
    fun lightSource(player: Player, itemSlot: Int): Boolean {
        val item = player.inventory.getItem(itemSlot) ?: return false
        val slot = unlitSlot(item.id)
        if (slot == -1) return false
        if (!player.inventory.containsItem(Obj.tinderbox, 1)) {
            player.sendMessage("You need a tinderbox in order to light the ${item.name.lowercase()}.")
            return false
        }
        player.inventory.replaceItem(LIT[slot], item.amount, itemSlot)
        player.sendMessage("You light the ${item.name.lowercase()}.")
        return true
    }
}
