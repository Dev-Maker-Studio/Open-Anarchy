package com.rs.game.content.quests.deserttreasure.dialogues

import com.rs.engine.dialogue.HeadE.*
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.content.quests.deserttreasure.RASOLO_DEAL_ACCEPTED
import com.rs.game.content.quests.deserttreasure.STAGE_DIAMONDS_OF_AZZANADRA
import com.rs.game.content.quests.deserttreasure.DesertTreasureDiamonds
import com.rs.game.content.shops.ShopsHandler
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick

private const val RASOLO = 1972
private const val GILDED_CROSS = 4674
private const val RING_OF_VISIBILITY = 4657

const val RASOLO_CROSS_RETURNED = "dtRasoloCrossReturned"

@ServerStartupEvent
fun mapRasoloDesertTreasure() {
    onNpcClick(RASOLO, options = arrayOf("Talk-to")) { (player) ->
        rasoloDialogue(player)
    }

    onNpcClick(RASOLO, options = arrayOf("Trade")) { (player) ->
        ShopsHandler.openShop(player, "rasolo_the_wandering_merchant")
    }
}

private fun rasoloDialogue(player: Player) {
    player.startConversation {
        if (player.getBool(RASOLO_CROSS_RETURNED)) {
            val hasRing = player.inventory.containsOneItem(RING_OF_VISIBILITY) ||
                    player.equipment.containsOneItem(RING_OF_VISIBILITY)

            if (!hasRing) {
                player(CALM_TALK, "I lost that ring you gave me...")
                npc(RASOLO, CALM_TALK, "Then by all means, take another. Only a foolish merchant would give away his only stock!")
                exec {
                    player.inventory.addItemDrop(RING_OF_VISIBILITY, 1)
                }
                return@startConversation
            }

            npc(RASOLO, CALM_TALK, "So how goes your quest?<br>Did you manage to find the Diamond you were looking for yet?")
            if (DesertTreasureDiamonds.hasShadowDiamond(player)) {
                player(CALM_TALK, "Yes, I did.")
            } else {
                player(CALM_TALK, "Not yet...")
                npc(RASOLO, CALM_TALK, "Well, his lair is very close to here. I suggest you look around for it.")
            }
            return@startConversation
        }

        if (player.getBool(RASOLO_DEAL_ACCEPTED)) {
            npc(RASOLO, CALM_TALK, "Have you retrieved my gilded cross for me yet?")

            if (player.inventory.containsOneItem(GILDED_CROSS)) {
                exec {
                    player.inventory.deleteItem(GILDED_CROSS, 1)
                    player.inventory.addItemDrop(RING_OF_VISIBILITY, 1)
                    player.set(RASOLO_CROSS_RETURNED, true)
                }
                player(HAPPY_TALKING, "Yes I have!")
                npc(RASOLO, HAPPY_TALKING, "Excellent, excellent.<br>Here, take this ring. While you wear it, you will be able to see the things that live in shadows...")
                npc(RASOLO, CALM_TALK, "And you will be able to find the entrance to Damis' lair.")
                return@startConversation
            }

            player(CALM_TALK, "No, not yet...")
            npc(RASOLO, CALM_TALK, "Well what seems to be the problem?")

            label("helpOptions")
            options {
                op("Where can I find Laheeb?") {
                    player(CALM_TALK, "Where can I find this Laheeb?")
                    goto("laheebDirections")
                }
                op("Can't I just buy your ring?") {
                    player(CALM_TALK, "Can't I just buy your ring?")
                    npc(RASOLO, CALM_TALK, "No, it is not for sale.<br>Some things are more important than money, and the return of my gilded cross is one of them.")
                    goto("helpOptions")
                }
                op("Is Damis near here, then?") {
                    player(CALM_TALK, "Is Damis near here, then?")
                    npc(RASOLO, CALM_TALK, "You would be surprised to know just how close he is...")
                    goto("helpOptions")
                }
            }

            label("laheebDirections")
            npc(RASOLO, CALM_TALK, "Well, as a travelling merchant I have roamed these lands for many years...")
            npc(RASOLO, CALM_TALK, "To the far east of here there is an area that is dry, and barren like the desert...<br>it is called...")
            player(CALM_TALK, "Al Kharid?")
            npc(RASOLO, CALM_TALK, "...Yes, Al Kharid.<br>Now, to the south of Al Kharid there is a passageway, it is called the...")
            player(CALM_TALK, "Shantay Pass.")
            npc(RASOLO, CALM_TALK, "...Yes.<br>I didn't realise you had travelled there yourself.")
            npc(RASOLO, CALM_TALK, "Anyway, when you have gone through the Shantay Pass, you will find yourself in a hostile desert...<br>You will need to bring water with you to keep your life. Now, to the south-west of this pass, you will find a small village...")
            player(CALM_TALK, "The Bedabin camp.")
            npc(RASOLO, CONFUSED, "If you know where Laheeb lives, why did you ask me?")
            player(CALM_TALK, "I don't.<br>Sorry, please continue.")
            npc(RASOLO, CALM_TALK, "Well, okay then.<br>Anyway, south of this encampment there is an area where few have ever been...<br>It is a village of murderous bandits, and treacherous thieves...<br>This is where Laheeb makes his home.")
            return@startConversation
        }

        npc(RASOLO, HAPPY_TALKING, "Greetings friend.<br>I am Rasolo, the famous merchant.<br>Would you care to see my wares?")

        if (player.getQuestStage(Quest.DESERT_TREASURE) != STAGE_DIAMONDS_OF_AZZANADRA) {
            options {
                op("Yes") {
                    exec { ShopsHandler.openShop(player, "rasolo_the_wandering_merchant") }
                }
                op("No") {
                    player(CALM_TALK, "No thanks.")
                }
            }
            return@startConversation
        }

        options {
            op("Yes") {
                exec { ShopsHandler.openShop(player, "rasolo_the_wandering_merchant") }
            }
            op("No") {
                player(CALM_TALK, "No thanks.")
            }
            op("Ask about the Diamonds of Azzanadra") {
                player(CALM_TALK, "No, actually I was looking for something specific...")
                npc(RASOLO, CONFUSED, "Hmmmm?<br>And what would that be?")
                player(CALM_TALK, "I am looking for one of the Diamonds of Azzanadra. I have reason to believe it may be somewhere around here...")
                npc(RASOLO, CALM_TALK, "Ahhh...<br>The Shadow Diamond...")
                npc(RASOLO, CALM_TALK, "I know the object of which you speak.<br>It is guarded by a fearsome warrior known as Damis, they say, who lives in the shadows, invisible to prying eyes...")
                player(CONFUSED, "How can I find this 'Damis' then?")
                goto("offerHelp")
            }
        }

        label("offerHelp")
        npc(RASOLO, CALM_TALK, "Well now... perhaps we can help each other out here.")
        npc(RASOLO, CALM_TALK, "I have in my possession a small trinket, a ring, that allows its wearer to see the unseen...")
        player(CALM_TALK, "How much do you want for it?")
        npc(RASOLO, CALM_TALK, "Ah, but it is not for sale...<br><br>As such...")
        npc(RASOLO, CALM_TALK, "I am offering to trade it for an item that was rightfully mine, but that was stolen by a bandit named Laheeb.")
        npc(RASOLO, CALM_TALK, "The item in question is a gilded cross, that has some sentimental value to myself.<br>I wish for you to recover this item for me, and I will happily let you have my ring of visibility.")
        player(CONFUSED, "Where can I find this Laheeb?")

        label("initialDirections")
        npc(RASOLO, CALM_TALK, "Well, as a travelling merchant I have roamed these lands for many years...")
        npc(RASOLO, CALM_TALK, "To the far east of here there is an area that is dry, and barren like the desert...<br>it is called...")
        player(CALM_TALK, "Al Kharid?")
        npc(RASOLO, CALM_TALK, "...Yes, Al Kharid.<br>Now, to the south of Al Kharid there is a passageway, it is called the...")
        player(CALM_TALK, "Shantay Pass.")
        npc(RASOLO, CALM_TALK, "...Yes.<br>I didn't realise you had travelled there yourself.")
        npc(RASOLO, CALM_TALK, "Anyway, when you have gone through the Shantay Pass, you will find yourself in a hostile desert...<br>You will need to bring water with you to keep your life. Now, to the south-west of this pass, you will find a small village...")
        player(CALM_TALK, "The Bedabin camp.")
        npc(RASOLO, CONFUSED, "If you know where Laheeb lives, why did you ask me?")
        player(CALM_TALK, "I don't.<br>Sorry, please continue.")
        npc(RASOLO, CALM_TALK, "Well, okay then.<br>Anyway, south of this encampment there is an area where few have ever been...<br>It is a village of murderous bandits, and treacherous thieves...<br>This is where Laheeb makes his home.")
        npc(RASOLO, CALM_TALK, "He will have hidden his stolen treasure somewhere in that village. I am sure of it.<br>When you find his loot, you will find my gilded cross. Return it to me, and I will reward you with my ring of visibility, so that you may find Damis.<br>Does this seem fair to you?")
        options {
            op("Yes") {
                player(HAPPY_TALKING, "Not a problem.<br>I'll be back with your cross before you know it.")
                exec { player.set(RASOLO_DEAL_ACCEPTED, true) }
            }
            op("No") {
                player(CALM_TALK, "Sounds like too much effort to me.<br>I'll find this Damis by myself.")
                npc(RASOLO, CALM_TALK, "As you wish.")
            }
        }
    }
}
