package com.rs.game.content.quests.watchtower

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onItemOnNpc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.gameval.gen.Varbit

val WATCHTOWER_SHAMAN_VARBITS = mapOf(
    Npc.qip_watchtower_ogre_shaman_01_normal to Varbit.watchtower_shaman_1,
    Npc.qip_watchtower_ogre_shaman_02_normal to Varbit.watchtower_shaman_2,
    Npc.qip_watchtower_ogre_shaman_03_normal to Varbit.watchtower_shaman_3,
    Npc.qip_watchtower_ogre_shaman_04_normal to Varbit.watchtower_shaman_4,
    Npc.qip_watchtower_ogre_shaman_05_normal to Varbit.watchtower_shaman_5,
    Npc.qip_watchtower_ogre_shaman_06_normal to Varbit.watchtower_shaman_6
)

private const val JANGERBERRIES_ADDED = "watchtower_ogre_potion_jangerberries_added"

private val SHAMAN_DESTROYED_LINES = listOf(
    "That's one destroyed...",
    "That's the second one gone...",
    "That's the third one dealt with...",
    "There goes the fourth...",
    "That's five! Only one more left now..."
)

@ServerStartupEvent
fun mapWatchtowerPotion() {
    onItemOnItem(intArrayOf(Obj.pestle_and_mortar), intArrayOf(Obj.bat_bones)) { e ->
        val player = e.player
        if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_MAKE_OGRE_POTION) return@onItemOnItem
        player.inventory.deleteItem(Obj.bat_bones, 1)
        player.inventory.addItem(Obj.ground_bat_bones, 1)
        player.anim(Seq.human_herbing_grind)
        player.sendMessage("You grind the bat bones.")
    }

    onItemOnItem(intArrayOf(Obj.jangerberries), intArrayOf(Obj.obj_91)) { e ->
        val player = e.player
        if (player.getQuestStage(Quest.WATCHTOWER) !in STAGE_MAKE_OGRE_POTION..STAGE_DEFEAT_SHAMANS) return@onItemOnItem
        if (player.getBool(JANGERBERRIES_ADDED)) {
            explodePotion(player, Obj.jangerberries)
            return@onItemOnItem
        }
        player.inventory.deleteItem(Obj.jangerberries, 1)
        player.set(JANGERBERRIES_ADDED, true)
    }

    onItemOnItem(intArrayOf(Obj.ground_bat_bones), intArrayOf(Obj.obj_91)) { e ->
        val player = e.player
        if (player.getQuestStage(Quest.WATCHTOWER) !in STAGE_MAKE_OGRE_POTION..STAGE_DEFEAT_SHAMANS) return@onItemOnItem
        if (!player.getBool(JANGERBERRIES_ADDED)) {
            explodePotion(player, Obj.ground_bat_bones)
            return@onItemOnItem
        }
        player.inventory.deleteItem(Obj.ground_bat_bones, 1)
        player.inventory.deleteItem(Obj.obj_91, 1)
        player.inventory.addItem(Obj.ogre_potion, 1)
        player.set(JANGERBERRIES_ADDED, false)
    }

    onItemOnNpc(*WATCHTOWER_SHAMAN_VARBITS.keys.toTypedArray<Any>()) { (player, item, npc) ->
        if (item.id != Obj.magic_ogre_potion || player.getQuestStage(Quest.WATCHTOWER) != STAGE_DEFEAT_SHAMANS) return@onItemOnNpc
        val varbit = WATCHTOWER_SHAMAN_VARBITS[npc.id] ?: return@onItemOnNpc
        if (player.vars.getVarBit(varbit) != 0) return@onItemOnNpc
        player.sendMessage("You pour some of the potion onto the shaman.")
        player.vars.saveVarBit(varbit, 1)
        val destroyed = WATCHTOWER_SHAMAN_VARBITS.values.count { player.vars.getVarBit(it) != 0 }
        if (destroyed == WATCHTOWER_SHAMAN_VARBITS.size) {
            finishShamans(player)
            return@onItemOnNpc
        }
        SHAMAN_DESTROYED_LINES.getOrNull(destroyed - 1)?.let { player.startConversation { player(HeadE.HAPPY_TALKING, it) } }
    }
}

private fun explodePotion(player: Player, wrongIngredient: Int) {
    player.startConversation { player(HeadE.SCARED, "OW! That hurt!") }
    player.inventory.deleteItem(wrongIngredient, 1)
    if (player.inventory.containsItem(Obj.obj_91, 1)) {
        player.inventory.deleteItem(Obj.obj_91, 1)
        player.inventory.addItem(Obj.vial_empty, 1)
    }
    player.set(JANGERBERRIES_ADDED, false)
    player.applyHit(Hit(player, 100, Hit.HitLook.TRUE_DAMAGE))
}

private fun finishShamans(player: Player) {
    player.inventory.deleteItem(Obj.magic_ogre_potion, 1)
    player.inventory.addItem(Obj.vial_empty, 1)
    if (!player.inventory.containsItem(Obj.powering_crystal3, 1)) player.inventory.addItemDrop(Obj.powering_crystal3, 1)
    player.sendMessage("A crystal drops from the hands of the disappearing ogre. You snatch it up quickly.")
    player.setQuestStage(Quest.WATCHTOWER, STAGE_RECOVER_CRYSTALS)
}
