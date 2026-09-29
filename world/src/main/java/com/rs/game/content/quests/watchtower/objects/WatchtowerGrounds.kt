package com.rs.game.content.quests.watchtower

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.model.entity.player.Player
import world.gregs.voidps.type.Tile
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onObjectClick

private const val TOWER_GUARD = 877
private const val WATCHTOWER_WIZARD = 872

private const val EMPTY_BUSH = 2798
private const val FINGERNAILS_BUSH = 2799
private const val DAMAGED_DAGGER_BUSH = 2800
private const val TATTERED_EYE_PATCH_BUSH = 2801
private const val OLD_ROBE_BUSH = 2802
private const val UNUSUAL_ARMOUR_BUSH = 2803

private const val GROUND_FLOOR_LADDER_UP = 2833
private const val FIRST_FLOOR_LADDER_DOWN = 17122
private const val FIRST_FLOOR_LADDER_UP = 2796
private const val SECOND_FLOOR_LADDER_DOWN = 2797

private const val CYAN_PILLAR = 20025
private const val YELLOW_PILLAR = 20029
private const val MAGENTA_PILLAR = 20033
private const val WHITE_PILLAR = 21549

private const val SHIELD_LEVER = 2794

private const val CYAN_PILLAR_VARBIT = 3127
private const val YELLOW_PILLAR_VARBIT = 3128
private const val MAGENTA_PILLAR_VARBIT = 3129
private const val WHITE_PILLAR_VARBIT = 3130

private const val YELLOW_CRYSTAL = WATCHTOWER_CRYSTAL_1
private const val MAGENTA_CRYSTAL = WATCHTOWER_CRYSTAL_2
private const val CYAN_CRYSTAL = WATCHTOWER_CRYSTAL_3
private const val WHITE_CRYSTAL = WATCHTOWER_CRYSTAL_4

private const val FOUND_FINGERNAILS = "watchtowerFoundFingernails"
private const val FOUND_DAMAGED_DAGGER = "watchtowerFoundDamagedDagger"
private const val FOUND_TATTERED_EYE_PATCH = "watchtowerFoundTatteredEyePatch"
private const val FOUND_OLD_ROBE = "watchtowerFoundOldRobe"
private const val FOUND_UNUSUAL_ARMOUR = "watchtowerFoundUnusualArmour"

private val WATCHTOWER_EMPTY_BUSHES = setOf(
    Tile.of(2560, 3119, 0),
    Tile.of(2560, 3131, 0),
    Tile.of(2561, 3124, 0),
    Tile.of(2561, 3126, 0),
    Tile.of(2561, 3129, 0),
    Tile.of(2562, 3122, 0),
    Tile.of(2563, 3125, 0),
    Tile.of(2565, 3130, 0)
)

@ServerStartupEvent
fun mapWatchtowerGrounds() {
    onNpcClick(
        TOWER_GUARD,
        options = arrayOf("Talk-to")
    ) { (player, npc) ->
        player.startConversation {
            player(
                HeadE.CALM_TALK,
                "Hello. What are you doing here?"
            )

            npc(
                npc,
                HeadE.ANGRY,
                "We are the tower guards – our business is our own!"
            )
        }
    }

    onObjectClick(
        EMPTY_BUSH,
        FINGERNAILS_BUSH,
        DAMAGED_DAGGER_BUSH,
        TATTERED_EYE_PATCH_BUSH,
        OLD_ROBE_BUSH,
        UNUSUAL_ARMOUR_BUSH
    ) { (player, obj) ->
        if (player.getQuestStage(Quest.WATCHTOWER) != STAGE_SEARCH_WATCHTOWER) {
            player.sendMessage("You find nothing of interest.")
            return@onObjectClick
        }

        when (obj.id) {
            EMPTY_BUSH -> {
                if (obj.tile !in WATCHTOWER_EMPTY_BUSHES)
                    return@onObjectClick

                player.startConversation {
                    player(
                        HeadE.CALM_TALK,
                        "Hmmm, nothing here."
                    )
                }
            }

            FINGERNAILS_BUSH -> {
                if (!obj.tile.isAt(2544, 3134, 0))
                    return@onObjectClick

                searchFingernailsBush(player)
            }

            DAMAGED_DAGGER_BUSH -> {
                if (!obj.tile.isAt(2549, 3135, 0))
                    return@onObjectClick

                searchEvidenceBush(
                    player = player,
                    attribute = FOUND_DAMAGED_DAGGER,
                    itemId = DAMAGED_DAGGER,
                    text = "Aha, a dagger! I wonder if this is evidence...",
                    giveItemBeforeText = false
                )
            }

            TATTERED_EYE_PATCH_BUSH -> {
                if (!obj.tile.isAt(2546, 3130, 0))
                    return@onObjectClick

                searchEvidenceBush(
                    player = player,
                    attribute = FOUND_TATTERED_EYE_PATCH,
                    itemId = TATTERED_EYE_PATCH,
                    text = "I've found an eye patch; I had better show this to the Watchtower Wizard.",
                    giveItemBeforeText = true
                )
            }

            OLD_ROBE_BUSH -> {
                if (!obj.tile.isAt(2559, 3123, 0))
                    return@onObjectClick

                searchEvidenceBush(
                    player = player,
                    attribute = FOUND_OLD_ROBE,
                    itemId = OLD_ROBE,
                    text = "Aha! A robe. This could be a clue...",
                    giveItemBeforeText = true
                )
            }

            UNUSUAL_ARMOUR_BUSH -> {
                if (!obj.tile.isAt(2558, 3132, 0))
                    return@onObjectClick

                searchEvidenceBush(
                    player = player,
                    attribute = FOUND_UNUSUAL_ARMOUR,
                    itemId = UNUSUAL_ARMOUR,
                    text = "Here's some armour; it could be evidence...",
                    giveItemBeforeText = true
                )
            }
        }
    }

    onObjectClick(
        GROUND_FLOOR_LADDER_UP
    ) { (player, obj) ->
        if (!obj.tile.isAt(2544, 3111, 0))
            return@onObjectClick

        if (player.getQuestStage(Quest.WATCHTOWER) == STAGE_UNSTARTED) {
            player.startConversation {
                npc(
                    TOWER_GUARD,
                    HeadE.ANGRY,
                    "You can't go up there. That's private, that is."
                )
            }
            return@onObjectClick
        }

        player.startConversation {
            npc(
                TOWER_GUARD,
                HeadE.CALM_TALK,
                "It is the wizards' helping hand - let 'em up."
            )

            exec {
                player.useStairs(
                    -1,
                    Tile.of(2544, 3111, 1),
                    0,
                    1
                )
            }
        }
    }

    onObjectClick(
        FIRST_FLOOR_LADDER_DOWN
    ) { (player, obj) ->
        if (!obj.tile.isAt(2544, 3111, 1))
            return@onObjectClick

        player.useStairs(
            -1,
            Tile.of(2544, 3111, 0),
            0,
            1
        )
    }

    onObjectClick(
        FIRST_FLOOR_LADDER_UP
    ) { (player, obj) ->
        if (!obj.tile.isAt(2549, 3111, 1))
            return@onObjectClick

        player.useStairs(
            -1,
            Tile.of(2549, 3111, 2),
            0,
            1
        )
    }

    onObjectClick(
        SECOND_FLOOR_LADDER_DOWN
    ) { (player, obj) ->
        if (!obj.tile.isAt(2549, 3111, 2))
            return@onObjectClick

        player.useStairs(
            -1,
            Tile.of(2549, 3111, 1),
            0,
            1
        )
    }

    onItemOnObject(
        objectNamesOrIds = arrayOf(
            CYAN_PILLAR,
            YELLOW_PILLAR,
            MAGENTA_PILLAR,
            WHITE_PILLAR
        ),
        itemNamesOrIds = arrayOf(
            WATCHTOWER_CRYSTAL_1,
            WATCHTOWER_CRYSTAL_2,
            WATCHTOWER_CRYSTAL_3,
            WATCHTOWER_CRYSTAL_4
        )
    ) { e ->
        val player = e.player
        val obj = e.`object`
        val item = e.item

        if (player.getQuestStage(Quest.WATCHTOWER) != STAGE_RESTORE_WATCHTOWER) {
            return@onItemOnObject
        }

        val validTile = when (obj.id) {
            CYAN_PILLAR ->
                obj.tile.isAt(2545, 3116, 2)

            YELLOW_PILLAR ->
                obj.tile.isAt(2545, 3113, 2)

            MAGENTA_PILLAR ->
                obj.tile.isAt(2548, 3116, 2)

            WHITE_PILLAR ->
                obj.tile.isAt(2548, 3113, 2)

            else -> false
        }

        if (!validTile)
            return@onItemOnObject

        val correctCrystal = when (obj.id) {
            CYAN_PILLAR -> CYAN_CRYSTAL
            YELLOW_PILLAR -> YELLOW_CRYSTAL
            MAGENTA_PILLAR -> MAGENTA_CRYSTAL
            WHITE_PILLAR -> WHITE_CRYSTAL
            else -> -1
        }

        if (item.id != correctCrystal) {
            player.sendMessage(
                "That is not the right crystal for this pillar; try another."
            )
            return@onItemOnObject
        }

        val varbit = when (obj.id) {
            CYAN_PILLAR -> CYAN_PILLAR_VARBIT
            YELLOW_PILLAR -> YELLOW_PILLAR_VARBIT
            MAGENTA_PILLAR -> MAGENTA_PILLAR_VARBIT
            WHITE_PILLAR -> WHITE_PILLAR_VARBIT
            else -> return@onItemOnObject
        }

        if (player.vars.getVarBit(varbit) != 0)
            return@onItemOnObject

        player.inventory.deleteItem(correctCrystal, 1)
        player.vars.saveVarBit(varbit, 1)

        if (allCrystalsPlaced(player)) {
            player.setQuestStage(
                Quest.WATCHTOWER,
                STAGE_ACTIVATE_WATCHTOWER
            )
        }
    }

    onObjectClick(
        SHIELD_LEVER
    ) { (player, obj) ->
        if (!obj.tile.isAt(2543, 3115, 2))
            return@onObjectClick

        val stage =
            player.getQuestStage(Quest.WATCHTOWER)

        if (stage < STAGE_RESTORE_WATCHTOWER)
            return@onObjectClick

        if (player.isQuestComplete(Quest.WATCHTOWER))
            return@onObjectClick

        player.sendMessage(
            "You pull the lever..."
        )

        if (!allCrystalsPlaced(player)) {
            player.sendMessage(
                "You need to put the crystals on the correct pillars before the shield will work."
            )

            player.sendMessage(
                "All that happens now is a rather unsatisfying clunking noise."
            )
            return@onObjectClick
        }

        player.vars.saveVarBit(
            CYAN_PILLAR_VARBIT,
            2
        )

        player.vars.saveVarBit(
            YELLOW_PILLAR_VARBIT,
            2
        )

        player.vars.saveVarBit(
            MAGENTA_PILLAR_VARBIT,
            2
        )

        player.vars.saveVarBit(
            WHITE_PILLAR_VARBIT,
            2
        )

        player.sendMessage(
            "The magic force field activates."
        )

        player.startConversation {
            npc(
                WATCHTOWER_WIZARD,
                HeadE.HAPPY_TALKING,
                "Marvellous! It works! The town will now be safe."
            )

            npc(
                WATCHTOWER_WIZARD,
                HeadE.HAPPY_TALKING,
                "Your help was invaluable. Take this payment as a token of my gratitude."
            )

            npc(
                WATCHTOWER_WIZARD,
                HeadE.HAPPY_TALKING,
                "Also, let me improve your Magic level for you."
            )

            npc(
                WATCHTOWER_WIZARD,
                HeadE.HAPPY_TALKING,
                "Here is a special item for you - it's a new spell. Read the scroll and you will be able to teleport yourself here."
            )

            exec {
                player.questManager.completeQuest(
                    Quest.WATCHTOWER
                )
            }
        }
    }
}

private fun allCrystalsPlaced(
    player: Player
): Boolean {
    return player.vars.getVarBit(CYAN_PILLAR_VARBIT) >= 1 &&
            player.vars.getVarBit(YELLOW_PILLAR_VARBIT) >= 1 &&
            player.vars.getVarBit(MAGENTA_PILLAR_VARBIT) >= 1 &&
            player.vars.getVarBit(WHITE_PILLAR_VARBIT) >= 1
}

private fun searchFingernailsBush(player: Player) {
    if (player.getBool(FOUND_FINGERNAILS)) {
        if (!player.inventory.containsItem(FINGERNAILS, 1) &&
            !player.bank.containsItem(FINGERNAILS, 1)
        ) {
            player.startConversation {
                player(
                    HeadE.UPSET,
                    "What's this? Disgusting! Some fingernails. They may be a clue though... I'd better take them."
                )

                exec {
                    player.inventory.addItemDrop(
                        FINGERNAILS,
                        1
                    )
                }
            }
            return
        }

        alreadySearched(player)
        return
    }

    player.startConversation {
        player(
            HeadE.UPSET,
            "What's this? Disgusting! Some fingernails. They may be a clue though... I'd better take them."
        )

        exec {
            player.inventory.addItemDrop(
                FINGERNAILS,
                1
            )

            player.set(
                FOUND_FINGERNAILS,
                true
            )
        }
    }
}

private fun searchEvidenceBush(
    player: Player,
    attribute: String,
    itemId: Int,
    text: String,
    giveItemBeforeText: Boolean
) {
    if (player.getBool(attribute)) {
        if (!player.inventory.containsItem(itemId, 1) &&
            !player.bank.containsItem(itemId, 1)
        ) {
            player.startConversation {
                player(
                    HeadE.CALM_TALK,
                    text
                )

                exec {
                    player.inventory.addItemDrop(
                        itemId,
                        1
                    )
                }
            }
            return
        }

        alreadySearched(player)
        return
    }

    player.startConversation {
        if (giveItemBeforeText) {
            exec {
                player.inventory.addItemDrop(
                    itemId,
                    1
                )

                player.set(
                    attribute,
                    true
                )
            }

            player(
                HeadE.CALM_TALK,
                text
            )
        } else {
            player(
                HeadE.CALM_TALK,
                text
            )

            exec {
                player.inventory.addItemDrop(
                    itemId,
                    1
                )

                player.set(
                    attribute,
                    true
                )
            }
        }
    }
}

private fun alreadySearched(player: Player) {
    player.startConversation {
        player(
            HeadE.CALM_TALK,
            "I have already searched this place."
        )
    }
}
