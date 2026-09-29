package com.rs.game.content.quests.watchtower

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onItemOnNpc

private const val PESTLE_AND_MORTAR = 233
private const val EMPTY_VIAL = 229

private const val GUAM_POTION_UNF = 91
private const val JANGERBERRIES = 247
private const val BAT_BONES = 530

private const val OGRE_POTION = 2394

private val OGRE_SHAMANS = intArrayOf(
    5176,
    5181,
    5184,
    5187,
    5190,
    5193
)

private val OGRE_SHAMAN_VARBITS = intArrayOf(
    3131,
    3132,
    3133,
    3134,
    3135,
    3136
)

/*
 * The cache contains no separate item for the mixture after
 * jangerberries have been added to the unfinished guam potion.
 *
 * Item 91 remains in the player's inventory during that step,
 * so this attribute records that the jangerberries have already
 * been added.
 */
private const val JANGERBERRIES_ADDED =
    "watchtower_ogre_potion_jangerberries_added"

@ServerStartupEvent
fun mapWatchtowerPotion() {

    /*
     * ------------------------------------------------------------
     * GRIND BAT BONES
     * ------------------------------------------------------------
     */

    onItemOnItem(
        intArrayOf(PESTLE_AND_MORTAR),
        intArrayOf(BAT_BONES)
    ) { e ->
        val player = e.player

        if (player.getQuestStage(Quest.WATCHTOWER) <
            STAGE_MAKE_OGRE_POTION
        ) {
            return@onItemOnItem
        }

        player.inventory.deleteItem(BAT_BONES, 1)
        player.inventory.addItem(GROUND_BAT_BONES, 1)

        player.anim(364)
        player.sendMessage("You grind the bat bones.")
    }

    /*
     * ------------------------------------------------------------
     * ADD JANGERBERRIES
     * ------------------------------------------------------------
     *
     * The Watchtower potion begins with an unfinished guam
     * potion (item 91).
     *
     * There is no separate cache item for the potion after the
     * jangerberries are added, so item 91 remains in the inventory
     * while JANGERBERRIES_ADDED records the intermediate state.
     */

    onItemOnItem(
        intArrayOf(JANGERBERRIES),
        intArrayOf(GUAM_POTION_UNF)
    ) { e ->
        val player = e.player

        if (player.getQuestStage(Quest.WATCHTOWER) !=
            STAGE_MAKE_OGRE_POTION
        ) {
            return@onItemOnItem
        }

        /*
         * Adding jangerberries twice is the wrong mixture.
         */
        if (player.getBool(JANGERBERRIES_ADDED)) {
            explodePotion(player)
            return@onItemOnItem
        }

        player.inventory.deleteItem(
            JANGERBERRIES,
            1
        )

        player.set(
            JANGERBERRIES_ADDED,
            true
        )
    }

    /*
     * ------------------------------------------------------------
     * ADD GROUND BAT BONES
     * ------------------------------------------------------------
     *
     * Ground bat bones are the final ingredient.
     *
     * If the player has already added the jangerberries, the
     * unfinished guam potion becomes the completed Watchtower
     * potion (2394).
     *
     * If the player tries to add the bones before the
     * jangerberries, the volatile mixture explodes.
     */

    onItemOnItem(
        intArrayOf(GROUND_BAT_BONES),
        intArrayOf(GUAM_POTION_UNF)
    ) { e ->
        val player = e.player

        if (player.getQuestStage(Quest.WATCHTOWER) !=
            STAGE_MAKE_OGRE_POTION
        ) {
            return@onItemOnItem
        }

        if (!player.getBool(JANGERBERRIES_ADDED)) {
            explodePotion(player)
            return@onItemOnItem
        }

        player.inventory.deleteItem(
            GROUND_BAT_BONES,
            1
        )

        player.inventory.deleteItem(
            GUAM_POTION_UNF,
            1
        )

        player.inventory.addItem(
            OGRE_POTION,
            1
        )

        player.set(
            JANGERBERRIES_ADDED,
            false
        )
    }

    /*
     * ------------------------------------------------------------
     * USE MAGIC OGRE POTION ON SHAMANS
     * ------------------------------------------------------------
     */

    onItemOnNpc(*OGRE_SHAMANS) { (player, item, npc) ->

        if (item.id != MAGIC_OGRE_POTION) {
            return@onItemOnNpc
        }

        if (player.getQuestStage(Quest.WATCHTOWER) !=
            STAGE_DEFEAT_SHAMANS
        ) {
            return@onItemOnNpc
        }

        val shamanIndex =
            OGRE_SHAMANS.indexOf(npc.id)

        if (shamanIndex == -1) {
            return@onItemOnNpc
        }

        /*
         * This shaman has already been destroyed for this player.
         */
        if (player.vars.getVarBit(
                OGRE_SHAMAN_VARBITS[shamanIndex]
            ) != 0
        ) {
            return@onItemOnNpc
        }

        player.sendMessage(
            "You pour some of the potion onto the shaman."
        )

        player.vars.saveVarBit(
            OGRE_SHAMAN_VARBITS[shamanIndex],
            1
        )

        val destroyed =
            countDestroyedShamans(player)

        when (destroyed) {
            1 -> {
                player.startConversation {
                    player(
                        HeadE.HAPPY_TALKING,
                        "That's one destroyed..."
                    )
                }
            }

            2 -> {
                player.startConversation {
                    player(
                        HeadE.HAPPY_TALKING,
                        "That's the second one gone..."
                    )
                }
            }

            3 -> {
                player.startConversation {
                    player(
                        HeadE.HAPPY_TALKING,
                        "That's the third one dealt with..."
                    )
                }
            }

            4 -> {
                player.startConversation {
                    player(
                        HeadE.HAPPY_TALKING,
                        "There goes the fourth..."
                    )
                }
            }

            5 -> {
                player.startConversation {
                    player(
                        HeadE.HAPPY_TALKING,
                        "That's five! Only one more left now..."
                    )
                }
            }

            6 -> {
                finishShamans(player)
            }
        }
    }
}

/*
 * ------------------------------------------------------------
 * WRONG POTION MIX
 * ------------------------------------------------------------
 */

private fun explodePotion(player: Player) {

    player.startConversation {
        player(
            HeadE.SCARED,
            "OW! That hurt!"
        )
    }

    /*
     * The unfinished potion is destroyed by the explosion.
     */
    if (player.inventory.containsItem(
            GUAM_POTION_UNF,
            1
        )
    ) {
        player.inventory.deleteItem(
            GUAM_POTION_UNF,
            1
        )

        player.inventory.addItem(
            EMPTY_VIAL,
            1
        )
    }

    /*
     * Remove the ingredient being held for the intermediate
     * mixture if necessary.
     */
    if (player.getBool(JANGERBERRIES_ADDED)) {
        player.set(
            JANGERBERRIES_ADDED,
            false
        )
    }

    player.applyHit(
        Hit(
            player,
            100,
            Hit.HitLook.TRUE_DAMAGE
        )
    )
}

/*
 * ------------------------------------------------------------
 * COUNT DESTROYED SHAMANS
 * ------------------------------------------------------------
 *
 * The six quest varbits are the authoritative state.
 *
 * This avoids maintaining a second player attribute that can
 * disagree with the varbits after logout or other persistence
 * events.
 */

private fun countDestroyedShamans(
    player: Player
): Int {

    var destroyed = 0

    for (varbit in OGRE_SHAMAN_VARBITS) {
        if (player.vars.getVarBit(varbit) != 0) {
            destroyed++
        }
    }

    return destroyed
}

/*
 * ------------------------------------------------------------
 * FINAL SHAMAN
 * ------------------------------------------------------------
 */

private fun finishShamans(player: Player) {

    player.inventory.deleteItem(
        MAGIC_OGRE_POTION,
        1
    )

    player.inventory.addItem(
        EMPTY_VIAL,
        1
    )

    if (!player.inventory.containsItem(
            WATCHTOWER_CRYSTAL_3,
            1
        )
    ) {
        player.inventory.addItemDrop(
            WATCHTOWER_CRYSTAL_3,
            1
        )
    }

    player.sendMessage(
        "A crystal drops from the hands of the disappearing ogre. You snatch it up quickly."
    )

    player.setQuestStage(
        Quest.WATCHTOWER,
        STAGE_RECOVER_CRYSTALS
    )
}