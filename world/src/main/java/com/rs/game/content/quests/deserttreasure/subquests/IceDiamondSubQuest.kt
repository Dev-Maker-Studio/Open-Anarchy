package com.rs.game.content.quests.deserttreasure

import com.rs.engine.dialogue.DialogueBuilder
import com.rs.engine.dialogue.HeadE.*
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.World
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnNpc
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onNpcDeath
import com.rs.plugin.kts.onObjectClick
import com.rs.plugin.kts.onPlayerStep
import com.rs.plugin.kts.onLogin
import world.gregs.voidps.type.Tile

private const val TROLL_CHILD = 1934
private const val TROLL_FATHER = 1948
private const val TROLL_MOTHER = 1950
private const val FATHER_ICE_BLOCK = 1944
private const val MOTHER_ICE_BLOCK = 1946

private val FATHER_ICE_TILE = Tile.of(2825, 3808, 0)
private val MOTHER_ICE_TILE = Tile.of(2825, 3811, 0)

private val SWEET_FOOD = intArrayOf(1891, 1893, 1895, 1897, 1899, 1901)

private const val CHILD_CHEERED = "dtIceChildCheered"
private const val ICE_DEAL_ACCEPTED = "dtIceDealAccepted"
private const val ICE_FATHER_FREED = "dtIceFatherFreed"
private const val ICE_MOTHER_FREED = "dtIceMotherFreed"
private const val ICE_DIAMOND_CLAIMED = "dtIceDiamondClaimed"
private const val ICE_FATHER_THANKED = "dtIceFatherThanked"
private const val ICE_MOTHER_THANKED = "dtIceMotherThanked"
private const val ICE_PARENTS_REUNITED = "dtIceParentsReunited"

@ServerStartupEvent
fun mapIceDiamondDesertTreasure() {
    onNpcClick(TROLL_CHILD) { (player) -> trollChildDialogue(player) }
    onItemOnNpc(TROLL_CHILD) { (player, item) ->
        if (item.id in SWEET_FOOD && !player.getBool(CHILD_CHEERED)) {
            player.inventory.deleteItem(item.id, 1)
            player.set(CHILD_CHEERED, true)
            player.startConversation {
                player(CALM_TALK, "Hey there little troll... Take this and dry those tears...")
                npc(TROLL_CHILD, SAD, "(sniff)")
            }
        } else {
            player.startConversation {
                npc(TROLL_CHILD, SAD, "WAAAAAAAA!")
                simple("It doesn't look like that will cheer him up at all...")
            }
        }
    }
    onNpcClick(TROLL_FATHER) { (player) -> trollParentDialogue(player, true) }
    onNpcClick(TROLL_MOTHER) { (player) -> trollParentDialogue(player, false) }
}

private fun trollChildDialogue(player: Player) {
    player.startConversation {
        when {
            player.getBool(ICE_DIAMOND_CLAIMED) && !player.containsItem(DesertTreasureDiamonds.ICE_DIAMOND) -> {
                player(CALM_TALK, "I lost that diamond of Ice you gave me...")
                item(DesertTreasureDiamonds.ICE_DIAMOND, "You receive the Ice Diamond.") {
                    DesertTreasureDiamonds.giveIceDiamond(player)
                }
                npc(TROLL_CHILD, HAPPY_TALKING, "That's okay, it blew back on an icy wind... It's almost like it wants to stay here! Here, take it back, you've earned it.")
            }
            player.getBool(ICE_FATHER_FREED) && player.getBool(ICE_MOTHER_FREED) -> {
                npc(TROLL_CHILD, HAPPY_TALKING, "Thanks for all your help! I'm surprised you managed to survive the blizzard, being a thin skinned fleshy and all!")
                player(CALM_TALK, "What can I say? I'm a lot tougher than I look.")
            }
            !player.getBool(CHILD_CHEERED) -> {
                player(CALM_TALK, "Hello there.")
                npc(TROLL_CHILD, SAD, "Waaaaaaa!")
                simple("This troll seems very upset about something. Maybe some sweet food would take his mind off things?")
            }
            !player.getBool(ICE_DEAL_ACCEPTED) -> childDeal(player)
            else -> {
                npc(TROLL_CHILD, SAD, "You didn't free my mommy and daddy yet?")
                player(CALM_TALK, "Not yet...")
                npc(TROLL_CHILD, SAD, "Please try harder! I love my mommy and daddy!")
            }
        }
    }
}

private fun DialogueBuilder.childDeal(player: Player) {
    player(CALM_TALK, "Hello there.")
    npc(TROLL_CHILD, SAD, "-sniff- H-hello there.")
    player(CONFUSED, "Why so sad, little troll?")
    npc(TROLL_CHILD, SAD, "It was the bad man! He hurt my mommy and daddy! He made them all freezey!")
    player(CONFUSED, "Bad man...?")
    npc(TROLL_CHILD, SAD, "He said it was because they stole his diamond! But they never did! They found it, and didn't know who it belonged to!")
    npc(TROLL_CHILD, SAD, "My mommy always told me stealing is wrong, they would never steal from someone!")
    npc(TROLL_CHILD, SAD, "Then he did some wavey hand thing and my mommy and daddy got frozified!")
    player(CONFUSED, "A diamond you say? Listen, I think I might be able to help your parents, but I need that Diamond in return.")
    npc(TROLL_CHILD, SAD, "-sniff- I don't think they really wanted it anyway, they would have given it back to the bad man if he'd asked before freezifying them...")
    npc(TROLL_CHILD, SAD, "I give you my promise ${player.genderTerm("mister", "lady")} that if you unfreeze my mommy and daddy, you can have the stupid diamond.")
    npc(TROLL_CHILD, QUESTION, "Do we have a deal?")
    options {
        op("Yes") {
            player(CALM_TALK, "Absolutely. Don't worry kid, I'll get your parents back to you safe and sound.")
            exec { player.set(ICE_DEAL_ACCEPTED, true) }
        }
        op("No") {
            player(SAD, "Sorry, I can't make promises any promises about that, and I don't think I have the time to waste trying to defrost some stupid ice trolls.")
            npc(TROLL_CHILD, SAD, "Waaaaaaa!")
        }
    }
}

private fun trollParentDialogue(player: Player, father: Boolean) {
    val npcId = if (father) TROLL_FATHER else TROLL_MOTHER
    val freed = if (father) player.getBool(ICE_FATHER_FREED) else player.getBool(ICE_MOTHER_FREED)
    val thankedFlag = if (father) ICE_FATHER_THANKED else ICE_MOTHER_THANKED
    player.startConversation {
        if (freed && player.getBool(ICE_FATHER_FREED) && player.getBool(ICE_MOTHER_FREED)) {
            if (!player.getBool(thankedFlag)) {
                npc(
                    npcId,
                    HAPPY_TALKING,
                    if (father)
                        "Thanks again for freeing me from that ice block! I might be a troll, but it was real uncomfortable in there!"
                    else
                        "Thanks again for freeing me from that ice block! I don't know what my little snookums would have done without us!"
                )
                exec { player.set(thankedFlag, true) }
            } else if (father) {
                player(CONFUSED, "Why did you come back up here?")
                npc(TROLL_FATHER, HAPPY_TALKING, "I like the view!")
            } else {
                player(CONFUSED, "What are you doing up here?")
                npc(TROLL_MOTHER, HAPPY_TALKING, "I think I lost an ear ring up here earlier!")
            }
        } else {
            simple("There is a thick layer of ice covering this troll. You will have to find some way of shattering it.")
        }
    }
}

private const val ICE_TROLL = 1935
private const val KAMIL = 1913

private const val ICE_GATE = "Ice gate"
private val ICE_GATE_TILE = Tile.of(2838, 3739, 0)

private const val ICE_CAVE = "Cave"
private val ICE_CAVE_ENTRANCE_TILE = Tile.of(2870, 3717, 0)
private val ICE_CAVE_EXIT_TILE = Tile.of(2874, 3720, 0)
private val KAMIL_AMBUSH_TILE = Tile.of(2867, 3755, 0)

private const val ICE_TROLLS_KILLED = "dtIceTrollsKilled"
private const val ICE_TROLLS_REQUIRED = 5
private const val KAMIL_DEFEATED = "dtIceKamilDefeated"

@ServerStartupEvent
fun mapIceTrollCaveAndKamil() {
    onLogin { (player) ->
        if (player.getBool(KAMIL_SPAWNED) && !player.getBool(KAMIL_DEFEATED)) {
            player.set(KAMIL_SPAWNED, false)
        }
    }

    onObjectClick(ICE_GATE, tiles = arrayOf(ICE_GATE_TILE)) { (player) ->
        if (!player.getBool(ICE_DEAL_ACCEPTED)) {
            player.sendMessage("The bars are frozen tightly shut and a sturdy layer of ice prevents you from slipping through.")
        } else {
            if (!player.getBool(CHILD_TOLD_ABOUT_GATE)) {
                player.set(CHILD_TOLD_ABOUT_GATE, true)
                player.startConversation {
                    npc(TROLL_CHILD, SAD, "Oh, I'm sorry. My mommy says, that we ice trolls are so cold we can freeze the air around us when we are upset or angry.")
                    npc(TROLL_CHILD, SAD, "I'm afraid I must have frozen those gates too much when I was crying, but since you told me you are going to help rescue my mommy and daddy you should be able to squeeze through the bars.")
                    npc(TROLL_CHILD, SAD, "You are much smaller than most trolls, it should be easy for you!")
                }
            }
            player.sendMessage("You squeeze through the large icy bars of the gate.")
        }
    }

    onNpcClick(ICE_TROLL) { (player) ->
        player.startConversation {
            npc(ICE_TROLL, ANGRY, "Hur hur hur! Well look here, a puny fleshy human!")
            npc(ICE_TROLL, ANGRY, "You should beware of the icy wind that runs through this valley, it will bring a fleshy like you to a cold end indeed!")
        }
    }

    onObjectClick(ICE_CAVE, tiles = arrayOf(ICE_CAVE_ENTRANCE_TILE)) { (player) ->
        if (player.getI(ICE_TROLLS_KILLED, 0) < ICE_TROLLS_REQUIRED) {
            player.sendMessage("The entrance to the cave is covered in too much ice to get through.")
        } else {
            player.tele(ICE_CAVE_EXIT_TILE)
        }
    }

    onNpcDeath(ICE_TROLL) { (killer, npc) ->
        val player = killer as? Player ?: return@onNpcDeath
        if (player.getI(ICE_TROLLS_KILLED, 0) < ICE_TROLLS_REQUIRED) {
            player.set(ICE_TROLLS_KILLED, player.getI(ICE_TROLLS_KILLED, 0) + 1)
            player.sendMessage("A chunk of ice falls away from the cave entrance...")
        }
    }

    onPlayerStep(arrayOf(KAMIL_AMBUSH_TILE)) { (player) ->
        if (player.getBool(KAMIL_DEFEATED)) {
            spawnIceParentBlocks(player)
            return@onPlayerStep
        }
        if (!player.getBool(KAMIL_SPAWNED)) {
            player.set(KAMIL_SPAWNED, true)
            player.sendMessage("You can feel an evil presence nearby...")
            OwnedNPC(player, KAMIL, KAMIL_AMBUSH_TILE.transform(1, 0), true).apply {
                setAutoDespawnAtDistance(false)
                forceTalk("Sallamakar Ro!")
            }
        }
    }

    onNpcDeath(KAMIL) { e ->
        val kamil = e.npc as? OwnedNPC ?: return@onNpcDeath
        val player = kamil.owner
        player.set(KAMIL_DEFEATED, true)
        spawnIceParentBlocks(player)
        player.startConversation {
            player(CALM_TALK, "Well, that must have been the 'bad man' that the troll kid was on about... His parents must be up ahead somewhere.")
        }
    }
}

private const val CHILD_TOLD_ABOUT_GATE = "dtIceChildToldAboutGate"
private const val KAMIL_SPAWNED = "dtIceKamilSpawned"

@ServerStartupEvent
fun mapIceTrollParentFreeing() {
    onNpcDeath(FATHER_ICE_BLOCK, MOTHER_ICE_BLOCK) { e ->
        val block = e.npc as? OwnedNPC ?: return@onNpcDeath
        val player = block.owner
        if (!player.getBool(KAMIL_DEFEATED)) return@onNpcDeath

        when (block.id) {
            FATHER_ICE_BLOCK -> freeTrollParent(player, true, block.tile)
            MOTHER_ICE_BLOCK -> freeTrollParent(player, false, block.tile)
        }
    }
}

/**
 * The frozen parents are quest-instanced so one player's progress cannot
 * remove or reveal another player's parents.
 *
 * The cache already defines Ice block 1944/1946 as passive, attackable NPCs
 * with 100 LP (10 old-style hitpoints), so no artificial weapon requirement is
 * added here: the player simply attacks/smashes the blocks normally.
 */
private fun spawnIceParentBlocks(player: Player) {
    if (!player.getBool(KAMIL_DEFEATED)) return

    if (!player.getBool(ICE_FATHER_FREED) && !hasOwnedNpc(player, FATHER_ICE_BLOCK)) {
        OwnedNPC(player, FATHER_ICE_BLOCK, FATHER_ICE_TILE, true).apply {
            setAutoDespawnAtDistance(false)
        }
    } else if (player.getBool(ICE_FATHER_FREED) && !player.getBool(ICE_PARENTS_REUNITED) && !hasOwnedNpc(player, TROLL_FATHER)) {
        OwnedNPC(player, TROLL_FATHER, FATHER_ICE_TILE, true).apply {
            setAutoDespawnAtDistance(false)
        }
    }

    if (!player.getBool(ICE_MOTHER_FREED) && !hasOwnedNpc(player, MOTHER_ICE_BLOCK)) {
        OwnedNPC(player, MOTHER_ICE_BLOCK, MOTHER_ICE_TILE, true).apply {
            setAutoDespawnAtDistance(false)
        }
    } else if (player.getBool(ICE_MOTHER_FREED) && !player.getBool(ICE_PARENTS_REUNITED) && !hasOwnedNpc(player, TROLL_MOTHER)) {
        OwnedNPC(player, TROLL_MOTHER, MOTHER_ICE_TILE, true).apply {
            setAutoDespawnAtDistance(false)
        }
    }
}

private fun hasOwnedNpc(player: Player, npcId: Int): Boolean =
    World.getNPCs().any { npc -> npc is OwnedNPC && npc.owner === player && npc.id == npcId && !npc.hasFinished() }

private fun freeTrollParent(player: Player, father: Boolean, tile: Tile) {
    val freedFlag = if (father) ICE_FATHER_FREED else ICE_MOTHER_FREED
    if (player.getBool(freedFlag)) return

    player.set(freedFlag, true)

    // Wait for the ice block's death animation before revealing the troll.
    player.tasks.schedule(6) {
        val parentId = if (father) TROLL_FATHER else TROLL_MOTHER
        if (!hasOwnedNpc(player, parentId)) {
            OwnedNPC(player, parentId, tile, true).apply {
                setAutoDespawnAtDistance(false)
            }
        }

        if (player.getBool(ICE_FATHER_FREED) && player.getBool(ICE_MOTHER_FREED)) {
            reuniteTrollParents(player)
            return@schedule
        }

        player.startConversation {
            if (father) {
                npc(TROLL_FATHER, HAPPY_TALKING, "Oh thank you! It was really cold in there! But please, you must free my wife as well! Our son is depending on us!")
            } else {
                npc(TROLL_MOTHER, HAPPY_TALKING, "Wow, thanks for breaking me out of that ice! But please, my husband is still trapped in there!")
            }
        }
    }
}

private fun reuniteTrollParents(player: Player) {
    if (player.getBool(ICE_PARENTS_REUNITED)) return
    player.set(ICE_PARENTS_REUNITED, true)

    player.startConversation {
        npc(TROLL_FATHER, HAPPY_TALKING, "Phew! Am I glad to be out of that big ice cube! Are you okay too darling?")
        npc(TROLL_MOTHER, HAPPY_TALKING, "Yes, I thought we were done for! Why ever did that nasty Kamil freeze us up there anyway?")
        player(CONFUSED, "He must have been trying to protect his Diamond...")
        npc(TROLL_FATHER, CONFUSED, "You mean that diamond I found the other day belonged to him? But why didn't he just ask for it back? It's not like I really want it or anything! And how did you know we had that diamond anyway, fleshy?")
        player(CALM_TALK, "Your son told me. That's why I rescued you, it is very important that I have that diamond...")
        npc(TROLL_MOTHER, SAD, "Ooohhhhh, my poor baby! He must have been so worried about us...")
        npc(TROLL_FATHER, HAPPY_TALKING, "Yes, but he certainly inherited his Dad's smarts! If he'd told this fleshy that he had the Diamond and not us, we might never have been rescued!")
        player(CONFUSED, "Wait... what? That stupid little troll kid had the diamond all along?")
        npc(TROLL_MOTHER, ANGRY, "Don't you talk about my baby like that!")
        npc(TROLL_FATHER, HAPPY_TALKING, "Now, now dear, all's well that ends well. We've been freed and this fleshy has certainly earned ${player.genderTerm("himself", "herself")} that diamond. Let's get out of this terrible place and see our son!")
        simple("You are transported back to the Ice gate with the trolls.")
        exec { player.tele(ICE_GATE_TILE) }
        npc(TROLL_CHILD, HAPPY_TALKING, "Mommy! Daddy! You're free!")
        npc(TROLL_FATHER, HAPPY_TALKING, "That's right son, and it's all thanks to this brave adventurer here. Now, make sure you hand over that diamond ${player.genderTerm("he", "she")} was looking for.")
        npc(TROLL_FATHER, HAPPY_TALKING, "It has been nothing but trouble for us, let's just get back to our cave and have dinner.")
        npc(TROLL_MOTHER, HAPPY_TALKING, "That's right son, it's your favorite tonight too! A big plate of raw mackerel!")
        npc(TROLL_CHILD, HAPPY_TALKING, "RAW MACKEREL! YUMMY!")
        item(DesertTreasureDiamonds.ICE_DIAMOND, "You receive the Ice Diamond.") {
            DesertTreasureDiamonds.giveIceDiamond(player)
            player.set(ICE_DIAMOND_CLAIMED, true)
        }
        npc(TROLL_CHILD, HAPPY_TALKING, "Here ya go ${player.genderTerm("mister", "lady")}! Thanks for getting my mom and dad away from the bad man!")
        player(CALM_TALK, "Don't worry about it, just as long as I don't have to go back into that blizzard.")
    }
}
