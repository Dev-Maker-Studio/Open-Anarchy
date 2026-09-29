package com.rs.game.content.quests.murdermystery

import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import com.rs.plugin.kts.onObjectClick

const val MM_MURDERER_ANNA = 1
const val MM_MURDERER_BOB = 2
const val MM_MURDERER_CAROL = 3
const val MM_MURDERER_DAVID = 4
const val MM_MURDERER_ELIZABETH = 5
const val MM_MURDERER_FRANK = 6

const val MM_MURDERER_KEY = "murderMysteryMurderer"
private const val THREAD_TAKEN_KEY = "murderMysteryThreadTaken"
const val MM_POISON_EVIDENCE_FOUND = "murderMysteryPoisonEvidenceFound"

const val CRIMINAL_DAGGER = 1813
const val CRIMINAL_DAGGER_DUSTED = 1814
const val PUNGENT_POT = 1812

const val CRIMINAL_THREAD_RED = 1808
const val CRIMINAL_THREAD_GREEN = 1809
const val CRIMINAL_THREAD_BLUE = 1810

const val ANNAS_NECKLACE = 1796
const val ANNAS_NECKLACE_DUSTED = 1797
const val BOBS_CUP = 1798
const val BOBS_CUP_DUSTED = 1799
const val CAROLS_BOTTLE = 1800
const val CAROLS_BOTTLE_DUSTED = 1801
const val DAVIDS_BOOK = 1802
const val DAVIDS_BOOK_DUSTED = 1803
const val ELIZABETHS_NEEDLE = 1804
const val ELIZABETHS_NEEDLE_DUSTED = 1805
const val FRANKS_POT = 1806
const val FRANKS_POT_DUSTED = 1807

const val FLYPAPER = 1811
const val KILLERS_PRINT = 1815
const val ANNAS_PRINT = 1816
const val BOBS_PRINT = 1817
const val CAROLS_PRINT = 1818
const val DAVIDS_PRINT = 1819
const val ELIZABETHS_PRINT = 1820
const val FRANKS_PRINT = 1821
const val UNKNOWN_PRINT = 1822

private const val EMPTY_POT = 1931
private const val POT_OF_FLOUR = 1933

private const val PUNGENT_POT_TABLE = 25734
private const val SMASHED_WINDOW = 26213
private const val STURDY_WOODEN_GATE = 2664
private const val FLYPAPER_SACK = 2663
private const val FLOUR_BARREL = 26122

private const val ANNAS_BARREL = 2656
private const val BOBS_BARREL = 2657
private const val CAROLS_BARREL = 2658
private const val DAVIDS_BARREL = 2659
private const val ELIZABETHS_BARREL = 2660
private const val FRANKS_BARREL = 2661

private const val COMPOST_HEAP = 26120
private const val BEEHIVE = 26121
private const val KITCHEN_DRAIN = 2843
private const val SPIDERS_NEST = 26109
private const val FOUNTAIN = 2654
private const val FAMILY_CREST = 2655

private data class EvidenceBarrel(
    val objectId: Int,
    val x: Int,
    val y: Int,
    val plane: Int,
    val itemId: Int,
    val dustedItemId: Int,
    val foundText: String,
    val alreadyText: String
)

private val evidenceBarrels = arrayOf(
    EvidenceBarrel(ANNAS_BARREL, 2733, 3575, 0, ANNAS_NECKLACE, ANNAS_NECKLACE_DUSTED,
        "There's something shiny hidden at the bottom. You take Anna's Silver Necklace.",
        "I already have Anna's Necklace."),
    EvidenceBarrel(BOBS_BARREL, 2735, 3579, 0, BOBS_CUP, BOBS_CUP_DUSTED,
        "There's something shiny hidden at the bottom. You take Bob's silver cup.",
        "I already have Bob's cup."),
    EvidenceBarrel(CAROLS_BARREL, 2733, 3580, 1, CAROLS_BOTTLE, CAROLS_BOTTLE_DUSTED,
        "There's something shiny hidden at the bottom. You take Carol's silver bottle.",
        "I already have Carol's bottle."),
    EvidenceBarrel(DAVIDS_BARREL, 2733, 3577, 1, DAVIDS_BOOK, DAVIDS_BOOK_DUSTED,
        "There's something shiny hidden at the bottom. You take David's silver book.",
        "I already have David's book."),
    EvidenceBarrel(ELIZABETHS_BARREL, 2747, 3581, 1, ELIZABETHS_NEEDLE, ELIZABETHS_NEEDLE_DUSTED,
        "There's something shiny hidden at the bottom. You take Elizabeth's silver needle.",
        "I already have Elizabeth's Needle."),
    EvidenceBarrel(FRANKS_BARREL, 2747, 3577, 1, FRANKS_POT, FRANKS_POT_DUSTED,
        "There's something shiny hidden at the bottom. You take Frank's silver pot.",
        "I already have Frank's pot.")
)

@ServerStartupEvent
fun mapMurderMysteryObjects() {
    onObjectClick(PUNGENT_POT_TABLE) { (player, obj) ->
        if (obj.x != 2747 || obj.y != 3579 || obj.plane != 0)
            return@onObjectClick

        if (!murderMysteryStarted(player))
            return@onObjectClick

        beginGatheringEvidence(player)

        if (player.inventory.containsItem(PUNGENT_POT, 1)) {
            player.simpleDialogue("I already have the poisoned pot.")
            return@onObjectClick
        }

        player.inventory.addItemDrop(PUNGENT_POT, 1)
        player.simpleDialogue("It seems like Lord Sinclair was drinking from this before he died.")
    }

    onObjectClick(SMASHED_WINDOW) { (player, obj, option) ->
        if (obj.x != 2748 || obj.y != 3577 || obj.plane != 0)
            return@onObjectClick

        if (option.equals("Break", ignoreCase = true)) {
            player.simpleDialogue("You don't want to damage evidence!")
            return@onObjectClick
        }

        investigateWindow(player)
    }

    onObjectClick(STURDY_WOODEN_GATE) { (player, obj) ->
        if (obj.x != 2750 || obj.y != 3578 || obj.plane != 0)
            return@onObjectClick

        if (!murderMysteryStarted(player))
            return@onObjectClick

        player.simpleDialogue("As you approach the gate the guard dog starts barking loudly at you. There is no way an intruder could have committed the murder. It must have been someone the dog knew to get past it quietly.")
    }

    onObjectClick(FLYPAPER_SACK) { (player, obj) ->
        val correctSack = obj.plane == 0 && obj.y == 3582 && (obj.x == 2731 || obj.x == 2732)
        if (!correctSack || !murderMysteryStarted(player))
            return@onObjectClick

        beginGatheringEvidence(player)
        player.startConversation {
            simple("There's some flypaper in there. Should I take it?")
            options {
                op("Yes, it might be useful.") {
                    exec {
                        player.inventory.addItemDrop(FLYPAPER, 1)
                        player.sendMessage("You take a piece of fly paper. There is still plenty of fly paper left.")
                    }
                }
                op("No, I don't see any need for it.") {
                    exec { player.sendMessage("You leave the paper in the sack.") }
                }
            }
        }
    }

    onObjectClick(
        ANNAS_BARREL, BOBS_BARREL, CAROLS_BARREL,
        DAVIDS_BARREL, ELIZABETHS_BARREL, FRANKS_BARREL
    ) { (player, obj) ->
        if (!murderMysteryStarted(player))
            return@onObjectClick

        val barrel = evidenceBarrels.firstOrNull {
            it.objectId == obj.id && it.x == obj.x && it.y == obj.y && it.plane == obj.plane
        } ?: return@onObjectClick

        beginGatheringEvidence(player)
        if (player.inventory.containsItem(barrel.itemId, 1) ||
            player.inventory.containsItem(barrel.dustedItemId, 1)
        ) {
            player.sendMessage(barrel.alreadyText)
        } else {
            player.sendMessage(barrel.foundText)
            player.inventory.addItemDrop(barrel.itemId, 1)
        }
    }

    onObjectClick(FLOUR_BARREL) { (player, obj) ->
        if (obj.x != 2733 || obj.y != 3582 || obj.plane != 0 || !murderMysteryStarted(player))
            return@onObjectClick
        takeFlour(player)
    }

    onItemOnObject(
        objectNamesOrIds = arrayOf(FLOUR_BARREL),
        itemNamesOrIds = arrayOf(EMPTY_POT, PUNGENT_POT, FRANKS_POT, FRANKS_POT_DUSTED)
    ) { (player, obj, item) ->
        if (obj.x != 2733 || obj.y != 3582 || obj.plane != 0 || !murderMysteryStarted(player))
            return@onItemOnObject

        when (item.id) {
            EMPTY_POT -> takeFlour(player)
            PUNGENT_POT, FRANKS_POT, FRANKS_POT_DUSTED ->
                player.sendMessage("You probably shouldn't use evidence from a crime scene to keep flour in.")
        }
    }

    onObjectClick(COMPOST_HEAP) { (player, obj) ->
        if (obj.x != 2730 || obj.y != 3572 || obj.plane != 0)
            return@onObjectClick
        investigatePoisonArea(
            player,
            MM_MURDERER_ANNA,
            "It's a heap of compost.",
            "The compost is teeming with maggots. Somebody should really do something about it. It's certainly clear nobody's used poison here.",
            "There is a faint smell of poison behind the smell of the compost."
        )
    }

    onObjectClick(BEEHIVE) { (player, obj) ->
        if (obj.x != 2730 || obj.y != 3559 || obj.plane != 0)
            return@onObjectClick
        investigatePoisonArea(
            player,
            MM_MURDERER_BOB,
            "It's a very old beehive.",
            "The beehive buzzes with activity. These bees definitely don't seem poisoned at all.",
            "The hive is empty. There are a few dead bees and a faint smell of poison."
        )
    }

    onObjectClick(KITCHEN_DRAIN) { (player, obj) ->
        if (obj.x != 2736 || obj.y != 3573 || obj.plane != 0)
            return@onObjectClick
        investigatePoisonArea(
            player,
            MM_MURDERER_CAROL,
            "It's the drains from the kitchen.",
            "The drain is totally blocked. It really stinks. No, it REALLY smells bad. It's certainly clear nobody's cleaned it recently.",
            "The drain seems to have been recently cleaned. You can still smell the faint aroma of poison."
        )
    }

    onObjectClick(SPIDERS_NEST) { (player, obj) ->
        if (obj.x != 2740 || obj.y != 3574 || obj.plane != 1)
            return@onObjectClick
        investigatePoisonArea(
            player,
            MM_MURDERER_DAVID,
            "It looks like a spiders' nest of some kind...",
            "There is a spiders' nest here. You estimate there must be at least a few hundred spiders ready to hatch. It's certainly clear nobody's used poison here.",
            "A faint smell of poison and a few dead spiders is all that remains of the spiders nest."
        )
    }

    onObjectClick(FOUNTAIN) { (player, obj) ->
        if (obj.x != 2747 || obj.y != 3563 || obj.plane != 0)
            return@onObjectClick

        if (!player.getBool(MM_POISON_BUYERS_KNOWN)) {
            player.sendMessage("A fountain with large numbers of insects around the base.")
            return@onObjectClick
        }

        beginPoisonInvestigation(player)
        if (ensureMurderer(player) == MM_MURDERER_ELIZABETH) {
            player.startConversation {
                simple("The fountain is swarming with mosquitos. There's a nest of them underneath the fountain.")
                player(CALM_TALK, "I hate mosquitos, they're so annoying!")
                simple("It's certainly clear nobody's used poison here.")
                exec { recordPoisonEvidence(player, MM_MURDERER_ELIZABETH) }
            }
        } else {
            player.simpleDialogue("There are a lot of dead mosquitos around the base of the fountain. A faint smell of poison is in the air, but the water seems clean.")
        }
    }

    onObjectClick(FAMILY_CREST) { (player, obj) ->
        if (obj.x != 2746 || obj.y != 3573 || obj.plane != 0)
            return@onObjectClick
        investigatePoisonArea(
            player,
            MM_MURDERER_FRANK,
            "The Sinclair Family Crest is hung up here.",
            "It looks like the Sinclair family crest but it is very dirty. You can barely make it out under all of the grime. It's certainly clear nobody's cleaned it recently.",
            "The Sinclair family crest. It's shiny and freshly polished and has a slight smell of poison."
        )
    }
}

private fun investigateWindow(player: Player) {
    if (!murderMysteryStarted(player)) {
        player.simpleDialogue("You need the guards' permission to do that.")
        return
    }

    beginGatheringEvidence(player)
    ensureMurderer(player)

    val thread = criminalThreadFor(player)
    player.sendMessage("Some thread seems to have been caught on a loose nail on the window.")

    if (player.inventory.containsItem(thread, 1)) {
        player.simpleDialogue("You have already taken the thread.")
        return
    }

    if (player.getBool(THREAD_TAKEN_KEY)) {
        player.simpleDialogue("Lucky for you there's some thread left. You should be less careless in future.")
        player.inventory.addItemDrop(thread, 1)
        return
    }

    player.set(THREAD_TAKEN_KEY, true)
    player.inventory.addItemDrop(thread, 1)
    player.simpleDialogue("You take the thread.")
}

private fun takeFlour(player: Player) {
    beginGatheringEvidence(player)

    if (!player.inventory.containsItem(EMPTY_POT, 1)) {
        player.sendMessage("You need something to put the flour in.")
        return
    }

    player.inventory.deleteItem(EMPTY_POT, 1)
    player.inventory.addItemDrop(POT_OF_FLOUR, 1)
    player.sendMessage("You take some flour from the barrel. There's still plenty of flour left.")
}

private fun murderMysteryStarted(player: Player): Boolean {
    val stage = player.getQuestStage(Quest.MURDER_MYSTERY)
    return stage in STAGE_SOMETHINGS_AMISS until STAGE_COMPLETE
}

private fun beginGatheringEvidence(player: Player) {
    val stage = player.getQuestStage(Quest.MURDER_MYSTERY)
    if (stage == STAGE_SOMETHINGS_AMISS ||
        stage == STAGE_QUESTIONING_INHABITANTS
    ) {
        player.setQuestStage(Quest.MURDER_MYSTERY, STAGE_GATHERING_EVIDENCE)
    }
}

fun ensureMurderer(player: Player): Int {
    val existing = player.getI(MM_MURDERER_KEY, 0)
    if (existing in MM_MURDERER_ANNA..MM_MURDERER_FRANK)
        return existing

    val murderer = (MM_MURDERER_ANNA..MM_MURDERER_FRANK).random()
    player.set(MM_MURDERER_KEY, murderer)
    return murderer
}

fun criminalThreadFor(player: Player): Int = when (ensureMurderer(player)) {
    MM_MURDERER_ANNA, MM_MURDERER_DAVID -> CRIMINAL_THREAD_GREEN
    MM_MURDERER_BOB, MM_MURDERER_CAROL -> CRIMINAL_THREAD_RED
    MM_MURDERER_ELIZABETH, MM_MURDERER_FRANK -> CRIMINAL_THREAD_BLUE
    else -> error("Invalid Murder Mystery murderer state.")
}

private fun investigatePoisonArea(
    player: Player,
    suspect: Int,
    ordinaryText: String,
    murdererText: String,
    innocentText: String
) {
    if (!player.getBool(MM_POISON_BUYERS_KNOWN)) {
        player.sendMessage(ordinaryText)
        return
    }

    beginPoisonInvestigation(player)

    if (ensureMurderer(player) == suspect) {
        player.simpleDialogue(murdererText)
        recordPoisonEvidence(player, suspect)
    } else {
        player.simpleDialogue(innocentText)
    }
}

private fun beginPoisonInvestigation(player: Player) {
    if (player.getQuestStage(Quest.MURDER_MYSTERY) == STAGE_VISITING_POISON_SALESMAN)
        player.setQuestStage(Quest.MURDER_MYSTERY, STAGE_INVESTIGATING_POISONED_AREAS)
}

private fun recordPoisonEvidence(player: Player, suspect: Int) {
    if (ensureMurderer(player) != suspect)
        return

    player.set(MM_POISON_EVIDENCE_FOUND, true)
}