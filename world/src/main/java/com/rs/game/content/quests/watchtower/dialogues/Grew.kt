package com.rs.game.content.quests.watchtower.dialogues

import com.rs.engine.dialogue.HeadE.*
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.content.quests.watchtower.*
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick

private const val GREW = 854

private const val GREW_TASK_STARTED = "watchtowerGrewTaskStarted"
private const val GREW_TASK_COMPLETE = "watchtowerGrewTaskComplete"
private const val YELLOW_PILLAR_VARBIT = 3128
private const val RELIC_PART_2_HANDED_IN = "watchtowerRelicPart2HandedIn"

@ServerStartupEvent
fun mapWatchtowerGrew() {
    onNpcClick(GREW, options = arrayOf("Talk-to")) { (player, npc) ->
        grewDialogue(player, npc)
    }
}

private fun grewDialogue(player: Player, npc: NPC) {
    player.startConversation {
        if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_FIND_WAY_INTO_GUTANOTH) {
            npc(
                npc,
                ANGRY,
                "What do you want, little morsel? You would look good on my plate!"
            )
            return@startConversation
        }

        if (player.getBool(GREW_TASK_COMPLETE)) {
            if (!player.inventory.containsItem(RELIC_PART_2, 1) &&
                !player.bank.containsItem(RELIC_PART_2, 1) &&
                !player.getBool(RELIC_PART_2_HANDED_IN)
            ) {
                npc(
                    npc,
                    CALM_TALK,
                    "What, you lost it?"
                )

                exec {
                    player.inventory.addItemDrop(RELIC_PART_2, 1)
                }

                npc(
                    npc,
                    CALM_TALK,
                    "Take another and don't lose it, morsel."
                )
                return@startConversation
            }

            if (!player.inventory.containsItem(WATCHTOWER_CRYSTAL_1, 1) &&
                !player.bank.containsItem(WATCHTOWER_CRYSTAL_1, 1) &&
                player.vars.getVarBit(YELLOW_PILLAR_VARBIT) == 0 &&
                !player.isQuestComplete(Quest.WATCHTOWER)
            ) {
                npc(
                    npc,
                    CALM_TALK,
                    "The morsel has lost the crystal?"
                )

                npc(
                    npc,
                    HAPPY_TALKING,
                    "Grew has another. Take it, morsel."
                )

                exec {
                    player.inventory.addItemDrop(WATCHTOWER_CRYSTAL_1, 1)
                }

                return@startConversation
            }

            npc(
                npc,
                HAPPY_TALKING,
                "The morsel has done well."
            )
            return@startConversation
        }

        if (!player.getBool(GREW_TASK_STARTED)) {
            npc(
                npc,
                ANGRY,
                "What do you want, little morsel? You would look good on my plate!"
            )

            player(
                CALM_TALK,
                "I want to enter the city of ogres."
            )

            npc(
                npc,
                ANGRY,
                "Hah! I should eat you instead!"
            )

            options {
                op("Don't eat me; I can help you.") {
                    player(
                        WORRIED,
                        "Don't eat me; I can help you."
                    )

                    npc(
                        npc,
                        CALM_TALK,
                        "What can a morsel like you do for me?"
                    )

                    player(
                        HAPPY_TALKING,
                        "I am a mighty adventurer, slayer of monsters and user of magic powers."
                    )

                    npc(
                        npc,
                        HAPPY_TALKING,
                        "Well, well, perhaps the morsel can help after all... If you t'ink you're tough, find Gorad, my enemy to the south-east, and knock one of his teeth out! Heheheheh!"
                    )

                    exec {
                        player.set(GREW_TASK_STARTED, true)

                        if (player.getQuestStage(Quest.WATCHTOWER) == STAGE_FIND_WAY_INTO_GUTANOTH) {
                            player.setQuestStage(
                                Quest.WATCHTOWER,
                                STAGE_COLLECT_OGRE_RELIC
                            )
                        }
                    }
                }

                op("You will have to kill me first.") {
                    player(
                        ANGRY,
                        "You will have to kill me first."
                    )

                    npc(
                        npc,
                        ANGRY,
                        "That's not tricky - guards!"
                    )
                }
            }

            return@startConversation
        }

        npc(
            npc,
            CALM_TALK,
            "The morsel is back. Does it have our tooth for us?"
        )

        if (!player.inventory.containsItem(OGRE_TOOTH, 1)) {
            player(
                WORRIED,
                "Err, I don't have it."
            )

            npc(
                npc,
                ANGRY,
                "Morsel, you dare to return without the tooth! Either you are stoopid, or wants to be eaten!"
            )

            return@startConversation
        }

        player(
            HAPPY_TALKING,
            "I have it."
        )

        exec {
            player.inventory.deleteItem(OGRE_TOOTH, 1)

            player.inventory.addItemDrop(RELIC_PART_2, 1)
            player.inventory.addItemDrop(WATCHTOWER_CRYSTAL_1, 1)

            player.set(GREW_TASK_COMPLETE, true)
        }

        npc(
            npc,
            HAPPY_TALKING,
            "It's got it; good, good. That'll annoy Gorad lots! Heheheheh. Here's a token of my gratitude: some old gem I stole from Gorad. And an old part of a statue."
        )

        simple(
            "You get part of a horrible statue... and one of the crystals!"
        )
    }
}