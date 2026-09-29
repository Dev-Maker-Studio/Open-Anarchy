package com.rs.game.content.quests.deserttreasure

import com.rs.engine.dialogue.DialogueBuilder
import com.rs.engine.dialogue.HeadE.*
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onItemOnNpc
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onNpcDeath

private const val MALAK = 1920
private const val RUANTUN = 1916
private const val DESSOUS = 1914
private const val HIGH_PRIEST_ENTRANA = 216

private const val SILVER_BAR = 2355
private const val GARLIC = 1550
private const val SPICE = 2007
private const val PESTLE_AND_MORTAR = 233
private const val GARLIC_POWDER = 4668

private const val SILVER_POT_EMPTY = 4658
private const val BLESSED_POT_EMPTY = 4659
private const val SILVER_POT_BLOOD = 4660
private const val BLESSED_POT_BLOOD = 4661
private const val BLESSED_POT_GARLIC = 4663
private const val BLESSED_POT_SPICE = 4665
private const val BLESSED_POT_COMPLETE = 4667

private const val MALAK_DEAL = "dtMalakDeal"
private const val MALAK_ASKED_HOW = "dtMalakAskedHow"
private const val DESSOUS_KILLED = "dtDessousKilled"
private const val BLOOD_DIAMOND_CLAIMED = "dtBloodDiamondClaimed"

private val potRecipes = listOf(
    Triple(BLESSED_POT_BLOOD, GARLIC_POWDER, BLESSED_POT_GARLIC),
    Triple(BLESSED_POT_BLOOD, SPICE, BLESSED_POT_SPICE),
    Triple(BLESSED_POT_GARLIC, SPICE, BLESSED_POT_COMPLETE),
    Triple(BLESSED_POT_SPICE, GARLIC_POWDER, BLESSED_POT_COMPLETE)
)

@ServerStartupEvent
fun mapBloodDiamondDesertTreasure() {
    onNpcClick(MALAK) { (player) -> malakDialogue(player) }
    onNpcClick(RUANTUN) { (player) -> ruantunDialogue(player) }
    onItemOnNpc(HIGH_PRIEST_ENTRANA) { (player, item) -> blessPot(player, item.id) }
    onNpcDeath(DESSOUS) { e -> (e.killer as? Player)?.set(DESSOUS_KILLED, true) }

    onItemOnItem(intArrayOf(GARLIC), intArrayOf(PESTLE_AND_MORTAR)) { e ->
        e.player.inventory.deleteItem(GARLIC, 1)
        e.player.inventory.addItem(GARLIC_POWDER, 1)
        e.player.sendMessage("You crush the garlic into a fine powder.")
    }
    onItemOnItem(intArrayOf(BLESSED_POT_BLOOD, BLESSED_POT_SPICE), intArrayOf(GARLIC)) { e ->
        e.player.sendMessage("You need to crush the garlic before adding it to the pot.")
    }
    potRecipes.forEach { (pot, ingredient, result) ->
        onItemOnItem(intArrayOf(pot), intArrayOf(ingredient)) { e ->
            e.player.inventory.deleteItem(pot, 1)
            e.player.inventory.deleteItem(ingredient, 1)
            e.player.inventory.addItem(result, 1)
            e.player.sendMessage(if (ingredient == SPICE) "You add some spices to the pot." else "You add some crushed garlic to the pot.")
        }
    }
}

private fun malakDialogue(player: Player) {
    player.startConversation {
        when {
            player.getQuestStage(Quest.DESERT_TREASURE) != STAGE_DIAMONDS_OF_AZZANADRA ->
                npc(MALAK, FRUSTRATED, "Begone, human, before I lose my patience.")
            player.getBool(BLOOD_DIAMOND_CLAIMED) -> malakAfterDeal(player)
            !player.getBool(MALAK_DEAL) -> malakPitch(player)
            else -> malakDealFollowUp(player)
        }
    }
}

private fun DialogueBuilder.malakPitch(player: Player) {
    if (player.equipment.contains(6465)) {
    npc(MALAK, SKEPTICAL, "A human, eh? Don't think that silly little ring of yours will fool me. Give me one good reason why I should not just take you to Lord Drakan now.")
} else {
    npc(MALAK, SKEPTICAL, "A human, eh? Give me one good reason why I should not just take you to Lord Drakan now.")
    npc(MALAK, EVIL_LAUGH, "You had better make it a good one too, or you will not survive the night, I'll wager.")
}

label("malakPitch")
options {
        op("I'm here to worship Zamorak") {
            player(CHEERFUL, "I am here to worship the almighty Zamorak! Yay! Go Zamorak!")
            npc(MALAK, CHUCKLE, "I see. You are a moron. You have probably settled right in with the rest of the idiots in this pathetic excuse for a village.")
            npc(MALAK, CALM_TALK, "Unfortunately for you, that is not a good enough reason to explain your presence here.")
            npc(MALAK, CALM_TALK, "Now tell me the reason for your coming here, or I will ensure you suffer a horrible fate indeed.")
            goto("malakPitch")
        }
        op("I'm here to praise Lord Drakan") {
            player(NERVOUS, "I am here only to serve the mighty Drakan. Yup, Drakan, he's the man.")
            if (player.isQuestComplete(Quest.VAMPYRE_SLAYER))
                npc(MALAK, SKEPTICAL, "I see. I would perhaps be more inclined to believe you if I could not smell the death blood of his brother Draynor upon you.")
                npc(MALAK, CALM_TALK, "What are you real intentions here?")
            else
                npc(MALAK, SKEPTICAL, "Really? That is interesting, that you would want to give your life by tresspassing in this land for such an unbelievable reason.")
                npc(MALAK, ANGRY, "Now speak, what is your purpose in coming here?")
            goto("malakPitch")
        }
        op("I'm here to worship you, mighty Malak") {
            player(NERVOUS, "I came here looking for you, oh mighty Malak, so that I might serve your glory!")
            npc(MALAK, ANGRY, "Please. Do not think that I am so vain and foolish as to allow you to avoid my question with such obvious sycophancy.")
            npc(MALAK, ANGRY, "Now tell me the reason behind your being here, or I will ensure that you suffer.")
            goto("malakPitch")
        }
        op("I'm here to kill Lord Drakan") {
            player(ANGRY, "I am here to kill Lord Drakan, and every stinking one of his vampyre brood!")
            npc(MALAK, EVIL_LAUGH, "Hah! Most entertaining, human!")
            npc(MALAK, CALM_TALK, "Now tell me the reason you are here, or we shall soon see who will be killing whom.")
            goto("malakPitch")
        }
        op("I'm looking for a special diamond...") { malakDealOffer(player) }
    }
}

private fun DialogueBuilder.malakDealOffer(player: Player) {
    player(CALM_TALK, "I am here looking for a special diamond... I have reason to believe it is somewhere in this vicinity, and it is probably in the possession of a warrior of Zamorak.")
    player(CALM_TALK, "I'm fairly sure it will have some kind of magical aura or something too. I don't suppose you've seen it, or know where it might be?")
    npc(MALAK, CALM_TALK, "Interesting... Well perhaps we can come to a little... arrangement, human.")
    npc(MALAK, QUESTION, "I may have information that may assist you, but you in turn will have to do something for me. What do you say? Do you think we could come to some form of agreement?")
    player(CONFUSED, "Well, what kind of something? No offence, but you're not exactly the trustworthy type...")
    npc(MALAK, CHUCKLE, "Ah, you have a healthy sense of paranoia, I see. It is not a particularly unfair request on my part...")
    npc(MALAK, CALM_TALK, "All I ask is that you ensure that the current owner of the diamond is killed. For my part, I will let you know his whereabouts, and how exactly to kill him.")
    npc(MALAK, CALM_TALK, "When he is dead, you may take the diamond from his corpse and do with it what you will. I have no interest in such baubles.")
    npc(MALAK, QUESTION, "So what say you? A life for a diamond. As a mark of good faith, I will give you some information free:")
    npc(MALAK, CALM_TALK, "The current owner of this diamond is named Dessous.")
    options {
        op("Yes") {
            player(CALM_TALK, "Well... I can't see any drawback. Okay, I accept your offer. Now tell me what you know.")
            exec { player.set(MALAK_DEAL, true) }
            npc(MALAK, CHUCKLE, "What I know? Hah! After you have been alive for as long as I, the things I know are enough to fill a library.")
            npc(MALAK, CALM_TALK, " I'm afraid you will need to be a little more specific.")
            malakQuestions(player)
        }
        op("No") {
            player(FRUSTRATED, "I don't trust you, or anything you say. I'm afraid I have to decline.")
            npc(MALAK, CALM_TALK, "As you wish human. I doubt anybody near here knows of the diamond you seek, however.")
            npc(MALAK, CALM_TALK, "If you wish to claim it as your own, you have little choice but to accept my bargain. I will wait here until such time as you change your mind.")
        }
    }
}

private fun DialogueBuilder.malakQuestions(player: Player) {
    label("malakQuestions")
    options {
        op("Why do you want Dessous dead?") {
            player(CONFUSED, "I don't see exactly how this bargain benefits you... Why exactly do you want Dessous killed anyway?")
            npc(MALAK, ANGRY, " That is an impertinent question to demand from myself.")
            npc(MALAK, CALM_TALK, " However, if it will help seal our deal, I will let you know some of the details.")
            npc(MALAK, CALM_TALK, "As you may or may not know, myself and my blood kin are the rulers of this land, all serving under Lord Drakan. However, we tend not to die of old age or similar natural causes, which means that to gain another Lords title or land, there often need to be...")
            npc(MALAK, CALM_TALK, "Unnatural causes of death involved. Let us just say, that Dessous is in control of some land that I myself would like some say in, and that it is not in my interest to be seen to be responsible for the death of a fellow Lord.")
            npc(MALAK, QUESTION, "It would however be extremely advantageous to myself should some random human adventurer take it upon themself to remove this rival for me... Do you understand?")
            player(CALM_TALK, "Yes... I think so...")
            npc(MALAK, CALM_TALK, "Good, we understand each other then. We will both benefit from the death of Dessous.")
            goto("malakQuestions")
        }
        op("Where can I find Dessous?") {
            player(CONFUSED, "Where can I find this Dessous?")
            npc(MALAK, CALM_TALK, "He currently resides in a graveyard to the South-East of here. You will not be able to move the gravestone which he lies beneath however, you will need to find some way to lure him out from his tomb.")
            player(CONFUSED, "And how exactly would I go about doing that?")
            npc(MALAK, CALM_TALK, "Well, he is a vampyre, so fresh blood would almost certainly entice him out.")
            npc(MALAK, CALM_TALK, "However, even though he is a frail and decrepit example of our species, he will be able to kill a weakling human such as yourself extremely easily. Having him in a bloodlust as he does so, will not make your job any easier.")
            goto("malakQuestions")
        }
        op("How can I kill Dessous?") {
            exec { player.set(MALAK_ASKED_HOW, true) }
            player(CONFUSED, "So what advice can you give me on killing Dessous?")
            npc(MALAK, CALM_TALK, "As ancient and weak as Dessous is, he is still more than a match for the likes of you.")
            npc(MALAK, CALM_TALK, "That is, assuming you were to fight him fairly.")
            npc(MALAK, CALM_TALK, "My proposal would be for you to even the odds up a little bit...")
            player(CONFUSED, "How would I go about doing that?")
            npc(MALAK, CALM_TALK, "Well, my plan would be as follows. First, take a silver bar to the man living in the sewers in Draynor. He was an assistant to Count Draynor in some of his...")
            npc(MALAK, CALM_TALK, "more interesting experiments many years past. Tell him you need a sacrificial offering pot. He will know what you speak of, it is a unique type of container used in various ancient vampyric ceremonies.")
            npc(MALAK, CALM_TALK, "Then take the pot to Entrana, and get it blessed by the Head Monk. This will lend the pot some holy power.")
            npc(MALAK, CALM_TALK, "If you then bring that silver pot back to me, I will provide you with some fresh blood, to put into it. To that pot of blood, you will add some crushed garlic, and some spice to disguise the smell.")
            npc(MALAK, CALM_TALK, "Use that pot of blood upon Dessous' tomb, and he will be unable to resist rising and drinking from it.")
            npc(MALAK, CALM_TALK, "The combination of garlic, silver, and blessings from Saradomin will act upon him as a poison, and allow you to kill him.")
            npc(MALAK, CALM_TALK, "This is just my suggestion of course, you may ignore it if you wish, although I offer no guarantees of your ability to defeat him otherwise.")
            npc(MALAK, CALM_TALK, "Was there anything else you wanted?")
            goto("malakQuestions")
        }
        op("Actually, I don't need to know anything.") {
            player(CALM_TALK, "Never mind, I will figure out all I need to know by myself.")
            npc(MALAK, CALM_TALK, "As you wish. Come and see me when you have managed to kill Dessous.")
        }
    }
}

private fun DialogueBuilder.malakDealFollowUp(player: Player) {
    val emptyPot = listOf(BLESSED_POT_EMPTY, SILVER_POT_EMPTY).firstOrNull { player.inventory.containsOneItem(it) }

    if (player.getBool(DESSOUS_KILLED)) {
        malakReward(player)
        return
    }

    if (emptyPot != null) {
        val bloodPot = if (emptyPot == BLESSED_POT_EMPTY) BLESSED_POT_BLOOD else SILVER_POT_BLOOD
        player(CALM_TALK, "I found Ruantun in Draynors sewers. He made me this pot, now where can I get some fresh blood to fill it with?")
        exec {
            player.inventory.deleteItem(emptyPot, 1)
            player.inventory.addItem(bloodPot, 1)
        }
        simple("Malak cuts you and pours some of your blood into the pot.")
        player(SAD, "Ow!")
        npc(MALAK, CHUCKLE, "There you go. As fresh as it gets.")
        player(FRUSTRATED, "Thanks for nothing.")
        npc(MALAK, CALM_TALK, "Come and speak to me again when you have managed to kill Dessous.")
        return
    }

    if (!player.getBool(MALAK_ASKED_HOW)) {
        npc(MALAK, CALM_TALK, "Back for something, human? I notice Dessous still lives.")
        malakQuestions(player)
        return
    }

    npc(MALAK, CALM_TALK, "Why are you still here? I notice Dessous still lives.")
    options {
        op("Where can I find Dessous?") {
            player(CONFUSED, "Where can I find this Dessous?")
            npc(MALAK, CALM_TALK, "He currently resides in a graveyard to the South-East of here. You will not be able to move the gravestone which he lies beneath however, you will need to find some way to lure him out from his tomb.")
            player(CONFUSED, "And how exactly would I go about doing that?")
            npc(MALAK, CALM_TALK, "Come and see me when you have prepared a silver ritual pot in the manner I have told you.")
            npc(MALAK, CALM_TALK, "I will ensure that you get some fresh blood that you may taint with garlic and spices, to lure our Dessous.")
        }
        op("How do I kill Dessous again?") {
            player(CONFUSED, "How am I supposed to kill Dessous again?")
            npc(MALAK, CALM_TALK, "Take a silver bar to the man in the Draynor sewers. He will fashion a ritualistic pot for you, which you should then take to Entrana and get blessed.")
            npc(MALAK, CALM_TALK, "When you have done that, come back here and speak to me, I will provide you with some fresh blood which you will then crush some garlic into, and then add some spices to hide the garlic.")
            npc(MALAK, CALM_TALK, "In this way, you will be able to lure him from his tomb and he should be sufficiently weakened to be vulnerable to your attacks.")
        }
        op("Actually, I don't need to know anything.") {
            player(CALM_TALK, "Never mind, I will figure out all I need to know by myself.")
            npc(MALAK, CALM_TALK, "As you wish. Come and see me when you have managed to kill Dessous.")
        }
    }
}

private fun DialogueBuilder.malakReward(player: Player) {
    npc(MALAK, EVIL_LAUGH, "Ah, the wandering hero returns! I take it you have dispatched poor old Dessous for me?")
    player(ANGRY, "Quit playing games with me, Malak. I want that diamond, and I want it now!")
    exec {
        player.inventory.deleteItem(BLESSED_POT_EMPTY, player.inventory.getAmountOf(BLESSED_POT_EMPTY))
        DesertTreasureDiamonds.giveBloodDiamond(player)
        player.set(BLOOD_DIAMOND_CLAIMED, true)
    }
    npc(MALAK, ANGRY, "Do not take that tone of voice with me, meat. You should be thankful I have allowed you your life.")
    npc(MALAK, ANGRY, "Here, take your precious little bauble.")
    npc(MALAK, ANGRY, "I will take that silver pot from you as well, humans are not meant to possess such artefacts. Now get out of my sight, our deal is complete, and if I see you here again I will not hesistate to take you to Lord Drakan.")
    npc(MALAK, ANGRY, "He will be extremely pleased to meet the murderer of poor Dessous, I suspect.")
}

private fun DialogueBuilder.malakAfterDeal(player: Player) {
    if (DesertTreasureDiamonds.hasBloodDiamond(player)) {
        npc(MALAK, ANGRY, "Be lucky I have let you live, meat. Our deal is done, I wish no further dealing with you.")
        return
    }

    player(ANGRY, "Where is the Diamond of Blood? I know you have it!")
    exec { DesertTreasureDiamonds.giveBloodDiamond(player) }
    npc(MALAK, ANGRY, "Do not take that tone of voice with me, meat. Here, take your bauble. I have no use for it.")
}

private fun ruantunDialogue(player: Player) {
    player.startConversation {
        if (player.getQuestStage(Quest.DESERT_TREASURE) != STAGE_DIAMONDS_OF_AZZANADRA || !player.getBool(MALAK_DEAL)) {
            npc(RUANTUN, SAD, "You sshould not be down here...")
            return@startConversation
        }

        player(CALM_TALK, "Hello.")
        npc(RUANTUN, SAD, "You sshould not be down here...")
        player(CONFUSED, "Are you an assistant to Count Draynor?")
        npc(RUANTUN, SAD, "I usssed to have that honour... Why do you ssseek me?")

        if (player.inventory.containsOneItem(SILVER_BAR)) {
            player(CALM_TALK, "I have a silver bar with me, I was wondering if you could make it into a 'sacrificial offering pot' for me?")
            exec {
                player.inventory.deleteItem(SILVER_BAR, 1)
                player.inventory.addItem(SILVER_POT_EMPTY, 1)
            }
            npc(RUANTUN, CALM_TALK, "Yesss, of courssse... There you are, put it to good usssse...")
        } else {
            player(CONFUSED, "I understand that you can make me a 'sacrificial offering pot' if I bring you a bar of silver?")
            npc(RUANTUN, CONFUSED, "And where did you hear thisss?")
            player(CALM_TALK, "It was from Malak in Canifis.")
            npc(RUANTUN, CALM_TALK, "Ah, I sssee... Yesss, I know how to make sssuch an item... It has been many yearsss sssince I have needed to however...")
            npc(RUANTUN, CALM_TALK, "It is not my wisssh to quessstion your desssire for sssuch an item. I wasss merely sssurprisssed that one sssuch as you would make sssuch a requessst...")
            npc(RUANTUN, SAD, "I will happily make you thisss pot, but you mussst bring me a bar of sssilver... Alasss, I can no longer collect my own ingredientsss, and mussst remain here...")
        }
    }
}

private fun blessPot(player: Player, itemId: Int) {
    val blessed = when (itemId) {
        SILVER_POT_EMPTY -> BLESSED_POT_EMPTY
        SILVER_POT_BLOOD -> BLESSED_POT_BLOOD
        else -> null
    }
    if (blessed == null || !player.getBool(MALAK_DEAL)) {
        player.sendMessage("Nothing interesting happens.")
        return
    }

    player.startConversation {
        npc(HIGH_PRIEST_ENTRANA, CALM_TALK, "Many greetings. Welcome to our fair island.")
        player(CALM_TALK, "Hi, I was wondering, can you quickly bless this for me?")
        if (itemId == SILVER_POT_BLOOD) {
            npc(HIGH_PRIEST_ENTRANA, CONFUSED, "A somewhat strange request, but I see no harm in... Wait a moment... is that BLOOD?")
            player(NERVOUS, "Erm... yes...")
            npc(HIGH_PRIEST_ENTRANA, CONFUSED, "Why do you want me to bless a pot full of someones blood???")
            player(NERVOUS, "Oh, don't worry, it's not 'someones', it's my own!")
            exec { swapPot(player, itemId, blessed) }
            npc(HIGH_PRIEST_ENTRANA, CONFUSED, "I... see... Well it is an unusual thing, but I see no harm in it. Take this, and walk in peace with Saradomin.")
        } else {
            exec { swapPot(player, itemId, blessed) }
            npc(HIGH_PRIEST_ENTRANA, CALM_TALK, "A somewhat strange request, but I see no harm in it. There you go. May Saradomin walk with you.")
        }
    }
}

private fun swapPot(player: Player, from: Int, to: Int) {
    if (!player.inventory.containsOneItem(from)) return
    player.inventory.deleteItem(from, 1)
    player.inventory.addItem(to, 1)
}