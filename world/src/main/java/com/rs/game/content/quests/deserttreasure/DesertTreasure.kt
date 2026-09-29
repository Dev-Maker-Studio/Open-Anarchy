package com.rs.game.content.quests.deserttreasure

import com.rs.engine.quest.Quest
import com.rs.engine.quest.QuestHandler
import com.rs.engine.quest.QuestOutline
import com.rs.game.content.combat.CombatDefinitions
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import org.darkan.core.gameval.gen.Varbit

const val STAGE_UNSTARTED = 0
const val STAGE_TERRY_BALANDO = 1
const val STAGE_RETURN_TO_ASGARNIA = 2
const val STAGE_MIRROR_MIRROR = 3
const val STAGE_TALK_TO_BARTENDER = 4
const val STAGE_TALK_TO_EBLIS = 5
const val STAGE_RETURN_TO_MIRRORS = 6
const val STAGE_DIAMONDS_OF_AZZANADRA = 7
const val STAGE_IFTAH_YA_SIMSIM = 8
const val STAGE_RETURN_TO_JALDRAOCHT = 9
const val STAGE_TOMB_RAIDER = 10
const val STAGE_AZZANADRA = 11
const val STAGE_COMPLETE = 12

object DesertTreasureDiamonds {
    const val BLOOD_DIAMOND = 4670
    const val ICE_DIAMOND = 4671
    const val SMOKE_DIAMOND = 4672
    const val SHADOW_DIAMOND = 4673

    fun hasBloodDiamond(player: Player) = player.containsItem(BLOOD_DIAMOND)
    fun hasIceDiamond(player: Player) = player.containsItem(ICE_DIAMOND)
    fun hasSmokeDiamond(player: Player) = player.containsItem(SMOKE_DIAMOND)
    fun hasShadowDiamond(player: Player) = player.containsItem(SHADOW_DIAMOND)

    fun giveBloodDiamond(player: Player) = player.inventory.addItemDrop(BLOOD_DIAMOND, 1)
    fun giveIceDiamond(player: Player) = player.inventory.addItemDrop(ICE_DIAMOND, 1)
    fun giveSmokeDiamond(player: Player) = player.inventory.addItemDrop(SMOKE_DIAMOND, 1)
    fun giveShadowDiamond(player: Player) = player.inventory.addItemDrop(SHADOW_DIAMOND, 1)

    fun diamondCount(player: Player) = listOf(
        hasBloodDiamond(player), hasIceDiamond(player), hasSmokeDiamond(player), hasShadowDiamond(player)
    ).count { it }

    fun hasAllDiamonds(player: Player) = diamondCount(player) == 4
}

@QuestHandler(
    quest = Quest.DESERT_TREASURE,
    startText = "Talk to Asgarnia Smith, the archaeologist who is exploring the Bedabin Camp south-west of the Shantay Pass.",
    itemsText = "Ashes, blood rune, bones, charcoal, 650 coins, 6 molten glass, 12 magic logs, 6 steel bars, cake, face mask, garlic, lockpicks, ice gloves or ice arrows, silver bar, spice, climbing boots, spiked boots.",
    combatText = "Several high level enemies.",
    rewardsText = "20,000 Magic XP<br>Ancient Magicks<br>Access to the Bandit Camp lodestone<br>Ability to buy and wield the ancient staff<br>Access to the Ancient Symbol gravestone",
    completedStage = STAGE_COMPLETE
)
class DesertTreasure : QuestOutline() {
    override fun getJournalLines(player: Player, stage: Int) = when (stage) {
        STAGE_UNSTARTED -> listOf("I should speak to Asgarnia Smith at the Bedabin Camp south-west of Shantay Pass.")
        STAGE_TERRY_BALANDO -> listOf("The archaeologist I met in the desert seemed to think that there was some hidden treasure in the desert somewhere.", "I should take this stone tablet he gave me to Terry Balando at the Exam Centre.")
        STAGE_RETURN_TO_ASGARNIA -> listOf("I've brought the tablet to Terry and he translated it for me.", "I should return to Asgarnia Smith.")
        STAGE_MIRROR_MIRROR -> listOf("Asgarnia Smith suggested I find information with the local Bedabin nomads.", "I should head to the Bandit Camp and speak to the bartender.")
        STAGE_TALK_TO_BARTENDER -> listOf("The bartender informed me that there is an old tale about four diamonds, and that I would need to speak to someone who knows more about the desert's history.")
        STAGE_TALK_TO_EBLIS -> listOf("I spoke with Eblis who shared with me the history of the diamonds.", "He said that the diamonds were taken by warriors of Zamorak and that I will need to kill them to reclaim them.")
        STAGE_RETURN_TO_MIRRORS -> listOf("Eblis offered to assist me in locating the diamonds. To do this he requires a set of special scrying glasses.")
        STAGE_DIAMONDS_OF_AZZANADRA -> buildList {
            add("I have given Eblis the special scrying glasses and he has located the four diamonds for me.")
            add("The four diamonds are spread across these areas: Canifis, Trollheim, Pollnivneach, and Baxtorian Falls. I can search for them in any order.")
            add(if (DesertTreasureDiamonds.hasBloodDiamond(player)) "I have retrieved the blood diamond from Malak, near Canifis." else "I still need to retrieve the blood diamond. I should speak to Malak in Canifis about Dessous.")
            add(if (DesertTreasureDiamonds.hasIceDiamond(player)) "I have retrieved the ice diamond from the ice trolls near Trollheim." else "I still need to retrieve the ice diamond. A troll child near Trollheim claimed his diamond was stolen.")
            add(if (DesertTreasureDiamonds.hasSmokeDiamond(player)) "I have retrieved the smoke diamond from Fareed, in the Smoke Dungeon." else "I still need to retrieve the smoke diamond, guarded by Fareed in the Smoke Dungeon near Pollnivneach.")
            add(if (DesertTreasureDiamonds.hasShadowDiamond(player)) "I have retrieved the shadow diamond from the Shadow Dungeon." else "I still need to retrieve the shadow diamond. I should speak to Rasolo, south of Baxtorian Falls, about an invisible dungeon.")
            if (DesertTreasureDiamonds.hasAllDiamonds(player)) add("I have all four diamonds. I should return to Eblis at the mirrors.")
        }
        STAGE_IFTAH_YA_SIMSIM -> listOf("I have all four diamonds and have met with Eblis, who wants me to sort the diamonds into their obelisks at the pyramid.")
        STAGE_RETURN_TO_JALDRAOCHT -> listOf("I have placed the diamonds in their respective obelisks and can now enter the Jaldraocht Pyramid.")
        STAGE_TOMB_RAIDER -> listOf("I've navigated the pyramid and found a central chamber.")
        STAGE_AZZANADRA -> listOf("At the heart of the pyramid I found a strange being, who gave me some powerful new magic spells.", "I can switch between my old spells and my new spells at any time by using the altar there, and can avoid the traps by using the secret passage.")
        STAGE_COMPLETE -> listOf("QUEST COMPLETE!")
        else -> listOf("Invalid quest stage. Report this to an administrator.")
    }

    override fun complete(player: Player) {
        player.skills.addXpQuest(Skills.MAGIC, 20000.0)
        player.combatDefinitions.setSpellbook(CombatDefinitions.Spellbook.ANCIENT)
        sendQuestCompleteInterface(player, 4675)
    }

    override fun updateStage(player: Player, stage: Int) {
        when (stage) {
            STAGE_TERRY_BALANDO -> player.vars.setVarBit(Varbit.desert_treasure, 5)
            STAGE_RETURN_TO_ASGARNIA -> player.vars.setVarBit(Varbit.desert_treasure, 10)
            STAGE_MIRROR_MIRROR -> player.vars.setVarBit(Varbit.desert_treasure, 15)
            STAGE_TALK_TO_BARTENDER -> player.vars.setVarBit(Varbit.desert_treasure, 20)
            STAGE_TALK_TO_EBLIS -> player.vars.setVarBit(Varbit.desert_treasure, 25)
            STAGE_RETURN_TO_MIRRORS -> player.vars.setVarBit(Varbit.desert_treasure, 30)
            STAGE_DIAMONDS_OF_AZZANADRA -> player.vars.setVarBit(Varbit.desert_treasure, 35)
            STAGE_IFTAH_YA_SIMSIM -> player.vars.setVarBit(Varbit.desert_treasure, 45)
            STAGE_RETURN_TO_JALDRAOCHT -> player.vars.setVarBit(Varbit.desert_treasure, 50)
            STAGE_TOMB_RAIDER -> player.vars.setVarBit(Varbit.desert_treasure, 55)
            STAGE_AZZANADRA, STAGE_COMPLETE -> player.vars.setVarBit(Varbit.desert_treasure, 60)
        }
    }
}