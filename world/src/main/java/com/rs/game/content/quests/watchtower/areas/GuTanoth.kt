package com.rs.game.content.quests.watchtower.areas

import com.rs.engine.dialogue.HeadE.CALM_TALK
import com.rs.engine.dialogue.HeadE.CONFUSED
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.content.quests.watchtower.CAVE_NIGHTSHADE
import com.rs.game.content.quests.watchtower.OGRE_RELIC
import com.rs.game.content.quests.watchtower.ROCK_CAKE
import com.rs.game.content.quests.watchtower.STAGE_ENTER_GUTANOTH
import com.rs.game.content.quests.watchtower.STAGE_FIND_SHAMAN_ENCLAVE
import com.rs.game.content.quests.watchtower.STAGE_GET_SKAVID_MAP
import com.rs.game.content.skills.agility.Agility
import com.rs.game.content.world.doors.Doors
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.lib.util.Utils
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnNpc
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onObjectClick
import com.rs.plugin.kts.onPlayerStep
import org.darkan.core.Constants
import org.darkan.core.model.Animation
import world.gregs.voidps.type.Tile

private const val ENTRANCE_GUARD = 858
private const val MARKET_GUARD = 859
private const val SOUTHWEST_GUARD = 860
private const val TOLL_BRIDGE_GUARD = 861
private const val SHAMAN_CAVE_GUARD = 870
private const val OGRE_TRADER = 875

private const val CITY_GATE = 2786
private const val BROKEN_BRIDGE_GAP = 2831
private const val ROCK_CAKE_STALL = 2793

private const val COINS = 995
private const val GOLD_BAR = 2357
private const val BRIDGE_TOLL = 20

private const val ROCK_CAKE_THIEVING_LEVEL = 15
private const val ROCK_CAKE_THIEVING_XP = 10.0
private const val BROKEN_BRIDGE_AGILITY_LEVEL = 25

private const val GU_TANOTH_EJECTION_GROAN =
    "watchtower_gu_tanoth_ejection_groan"

private const val MARKET_GIFT_REQUESTED =
    "watchtower_gu_tanoth_market_gift_requested"

private const val MARKET_GIFT_ACCEPTED =
    "watchtower_gu_tanoth_market_gift_accepted"

private const val GOLDEN_GATEWAY_UNLOCKED =
    "watchtower_gu_tanoth_golden_gateway_unlocked"

private const val SHAMAN_CAVE_GUARD_DISTRACTED =
    "watchtower_shaman_cave_guard_distracted"

private val CITY_GATE_TILE = Tile.of(2550, 3028, 0)
private val BROKEN_BRIDGE_GAP_TILE = Tile.of(2531, 3029, 0)
private val ROCK_CAKE_STALL_TILE = Tile.of(2513, 3036, 0)

private val GUARD_PUNCH_DESTINATION = Tile.of(2545, 3065, 0)

private val GUARD_PUNCH_EXIT_TILES = arrayOf(
    Tile.of(2544, 3064, 0),
    Tile.of(2545, 3064, 0),
    Tile.of(2546, 3064, 0),
    Tile.of(2544, 3065, 0),
    Tile.of(2546, 3065, 0),
    Tile.of(2544, 3066, 0),
    Tile.of(2545, 3066, 0),
    Tile.of(2546, 3066, 0)
)

@ServerStartupEvent
fun mapGuTanoth() {
    onNpcClick(
        ENTRANCE_GUARD,
        options = arrayOf("Talk-to")
    ) { (player, npc) ->
        if (npc.withinDistance(CITY_GATE_TILE, 8)) {
            handleGoldenGatewayGuard(player, npc)
        } else {
            handleEntranceGuard(player, npc)
        }
    }

    onNpcClick(
        MARKET_GUARD,
        options = arrayOf("Talk-to")
    ) { (player, npc) ->
        handleMarketGuard(player, npc)
    }

    onNpcClick(
        SOUTHWEST_GUARD,
        options = arrayOf("Talk-to")
    ) { (player, npc) ->
        handleSouthwestGuard(player, npc)
    }

    onNpcClick(
        TOLL_BRIDGE_GUARD,
        options = arrayOf("Talk-to")
    ) { (player, npc) ->
        handleTollBridgeGuard(player, npc)
    }

    onNpcClick(
        SHAMAN_CAVE_GUARD,
        options = arrayOf("Talk-to")
    ) { (player, npc) ->
        handleShamanCaveGuard(player, npc)
    }

    onItemOnNpc(SHAMAN_CAVE_GUARD) { (player, item, npc) ->
        if (item.id == CAVE_NIGHTSHADE) {
            handleNightshadeOnShamanCaveGuard(player, npc)
        }
    }

    onNpcClick(
        OGRE_TRADER,
        options = arrayOf("Talk-to")
    ) { (player, npc) ->
        handleOgreTrader(player, npc)
    }

    onObjectClick(
        CITY_GATE,
        tiles = arrayOf(CITY_GATE_TILE)
    ) { (player, obj) ->
        val questAttribs =
            player.questManager.getAttribs(Quest.WATCHTOWER)

        if (questAttribs.getB(GOLDEN_GATEWAY_UNLOCKED)) {
            Doors.handleDoubleDoor(player, obj)
            return@onObjectClick
        }

        player.sendMessage("The ogre guard won't let you through.")
    }

    onObjectClick(
        ROCK_CAKE_STALL,
        tiles = arrayOf(ROCK_CAKE_STALL_TILE),
        options = arrayOf("Steal-from")
    ) { (player) ->
        handleRockCakeStall(player)
    }

    onObjectClick(
        BROKEN_BRIDGE_GAP,
        tiles = arrayOf(BROKEN_BRIDGE_GAP_TILE),
        options = arrayOf("Jump-over")
    ) { (player) ->
        handleBrokenBridge(player)
    }

    onPlayerStep(GUARD_PUNCH_EXIT_TILES) { (player) ->
        if (!player.tempAttribs.getB(GU_TANOTH_EJECTION_GROAN))
            return@onPlayerStep

        player.tempAttribs.removeB(GU_TANOTH_EJECTION_GROAN)
        player.sendMessage("Urrrrrgh!")
    }
}

private fun handleEntranceGuard(player: Player, npc: NPC) {
    if (player.getQuestStage(Quest.WATCHTOWER) != STAGE_ENTER_GUTANOTH)
        return

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "Well, what proof of friendship did you bring?"
        )

        if (!player.inventory.containsItem(OGRE_RELIC, 1)) {
            player(
                CONFUSED,
                "I don't have anything."
            )

            npc(
                npc,
                CALM_TALK,
                "Why have you returned with no proof of companionship? Back to whence you came!"
            )

            exec {
                player.fadeScreen {
                    player.tele(GUARD_PUNCH_DESTINATION)

                    player.applyHit(
                        Hit(
                            player,
                            Utils.random(10, 50),
                            Hit.HitLook.TRUE_DAMAGE
                        )
                    )
                }
            }

            return@startConversation
        }

        player(
            CALM_TALK,
            "I have a relic from a chieftain."
        )

        npc(
            npc,
            CALM_TALK,
            "It's got the statue of Dalgroth. Welcome to Gu'Tanoth, friend of the ogres."
        )

        exec {
            player.inventory.deleteItem(OGRE_RELIC, 1)

            player.setQuestStage(
                Quest.WATCHTOWER,
                STAGE_GET_SKAVID_MAP
            )

            player.addWalkSteps(
                Tile.of(2550, 3027, 0),
                3,
                false
            )
        }
    }
}

private fun handleGoldenGatewayGuard(player: Player, npc: NPC) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_GET_SKAVID_MAP)
        return

    val questAttribs =
        player.questManager.getAttribs(Quest.WATCHTOWER)

    if (questAttribs.getB(GOLDEN_GATEWAY_UNLOCKED))
        return

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "Creature, did you bring me the gold?"
        )

        if (!player.inventory.containsItem(GOLD_BAR, 1))
            return@startConversation

        exec {
            player.inventory.deleteItem(GOLD_BAR, 1)
        }

        player(
            CALM_TALK,
            "Here it is!"
        )

        npc(
            npc,
            CALM_TALK,
            "It's brought it! On your way."
        )

        exec {
            questAttribs.setB(
                GOLDEN_GATEWAY_UNLOCKED,
                true
            )

            player.addWalkSteps(
                CITY_GATE_TILE,
                3,
                false
            )
        }
    }
}

private fun handleMarketGuard(player: Player, npc: NPC) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_GET_SKAVID_MAP)
        return

    val questAttribs =
        player.questManager.getAttribs(Quest.WATCHTOWER)

    if (questAttribs.getB(MARKET_GIFT_ACCEPTED))
        return

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "Oi! Where do you think you are going? You are for the cooking pot!"
        )

        options {
            op("But I am a friend to ogres...") {
                player(
                    CALM_TALK,
                    "But I am a friend to ogres..."
                )

                npc(
                    npc,
                    CALM_TALK,
                    "Prove it to us with a gift. Get us something from the market."
                )

                player(
                    CONFUSED,
                    "Like what?"
                )

                npc(
                    npc,
                    CALM_TALK,
                    "Surprise us..."
                )

                exec {
                    questAttribs.setB(
                        MARKET_GIFT_REQUESTED,
                        true
                    )
                }
            }

            op("Not if I can help it.") {
                player(
                    CALM_TALK,
                    "Not if I can help it."
                )

                npc(
                    npc,
                    CALM_TALK,
                    "You can help by being tonight's dinner, or you can go away. Now, which shall it be?"
                )

                options {
                    op("Okay, okay, I'm going.") {
                        player(
                            CALM_TALK,
                            "Okay, okay, I'm going."
                        )

                        npc(
                            npc,
                            CALM_TALK,
                            "Back to whence you came!"
                        )

                        exec {
                            ejectPlayerFromGuTanoth(player)
                        }
                    }

                    op("I tire of ogres; prepare to die!") {
                        player(
                            CALM_TALK,
                            "I tire of ogres; prepare to die!"
                        )

                        npc(
                            npc,
                            CALM_TALK,
                            "Grrrrr!"
                        )

                        exec {
                            npc.setCombatTarget(player)
                        }
                    }
                }
            }
        }
    }
}

private fun handleSouthwestGuard(player: Player, npc: NPC) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_GET_SKAVID_MAP)
        return

    val questAttribs =
        player.questManager.getAttribs(Quest.WATCHTOWER)

    if (questAttribs.getB(MARKET_GIFT_ACCEPTED))
        return

    if (
        questAttribs.getB(MARKET_GIFT_REQUESTED) &&
        player.inventory.containsItem(ROCK_CAKE, 1)
    ) {
        handleRockCakeHandIn(player, npc)
        return
    }

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "Stop, creature!"
        )
    }
}

private fun handleRockCakeStall(player: Player) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_GET_SKAVID_MAP)
        return

    val questAttribs =
        player.questManager.getAttribs(Quest.WATCHTOWER)

    if (!questAttribs.getB(MARKET_GIFT_REQUESTED)) {
        player.sendMessage("You have no reason to steal from the counter.")
        return
    }

    if (questAttribs.getB(MARKET_GIFT_ACCEPTED)) {
        player.sendMessage("You have no reason to steal another rock cake.")
        return
    }

    if (player.skills.getLevel(Skills.THIEVING) < ROCK_CAKE_THIEVING_LEVEL) {
        player.sendMessage(
            "You need a Thieving level of $ROCK_CAKE_THIEVING_LEVEL to steal from this."
        )
        return
    }

    if (!player.inventory.hasFreeSlots()) {
        player.sendMessage("Not enough space in your inventory.")
        return
    }

    player.setNextFaceTile(ROCK_CAKE_STALL_TILE)
    player.setNextAnimation(Animation(881))
    player.lock(2)

    player.inventory.addItem(ROCK_CAKE, 1)
    player.skills.addXp(
        Constants.THIEVING,
        ROCK_CAKE_THIEVING_XP
    )
}

private fun handleRockCakeHandIn(player: Player, npc: NPC) {
    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "Stop, creature! Oh it's you. Well, what have you got for us, then?"
        )

        player(
            CALM_TALK,
            "How about this?"
        )

        item(
            ROCK_CAKE,
            "You give the guard a rock cake."
        )

        npc(
            npc,
            CALM_TALK,
            "Well, well, look at this. My favourite: rock cake! Okay, we will let it through."
        )

        exec {
            player.inventory.deleteItem(ROCK_CAKE, 1)

            val questAttribs =
                player.questManager.getAttribs(Quest.WATCHTOWER)

            questAttribs.setB(
                MARKET_GIFT_ACCEPTED,
                true
            )

            player.addWalkSteps(
                Tile.of(2508, 3011, 0),
                10,
                false
            )

            Agility.handleObstacle(
                player,
                3303,
                2,
                Tile.of(2506, 3011, 0),
                1.0
            )
        }
    }
}

private fun handleTollBridgeGuard(player: Player, npc: NPC) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_GET_SKAVID_MAP)
        return

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "What do you want, small thing? Leave us alone!"
        )
    }
}

private fun handleBrokenBridge(player: Player) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_GET_SKAVID_MAP)
        return

    if (player.skills.getLevel(Skills.AGILITY) < BROKEN_BRIDGE_AGILITY_LEVEL) {
        player.startConversation {
            npc(
                TOLL_BRIDGE_GUARD,
                CALM_TALK,
                "Come back when you've got the moves!"
            )
        }
        return
    }

    if (player.x < BROKEN_BRIDGE_GAP_TILE.x) {
        Agility.handleObstacle(
            player,
            3303,
            1,
            Tile.of(2533, 3029, 0),
            1.0
        )

        player.startConversation {
            player(
                CALM_TALK,
                "I'm glad that was easier on the way back!"
            )
        }

        return
    }

    player.startConversation {
        npc(
            TOLL_BRIDGE_GUARD,
            CALM_TALK,
            "Oi! Little thing. If you want to cross here, you must pay me 20 gold pieces first!"
        )

        player(
            CONFUSED,
            "You want me to give you 20 gold pieces to let me jump off a bridge?"
        )

        npc(
            TOLL_BRIDGE_GUARD,
            CALM_TALK,
            "That's what I said, like it or lump it."
        )

        options {
            op("Okay, I'll pay it.") {
                player(
                    CALM_TALK,
                    "Okay, I'll pay it."
                )

                if (!player.inventory.containsItem(COINS, BRIDGE_TOLL)) {
                    player.sendMessage(
                        "You don't have enough gold pieces."
                    )
                    return@op
                }

                npc(
                    TOLL_BRIDGE_GUARD,
                    CALM_TALK,
                    "A wise choice, little thing."
                )

                exec {
                    player.inventory.deleteItem(
                        COINS,
                        BRIDGE_TOLL
                    )

                    Agility.handleObstacle(
                        player,
                        3303,
                        1,
                        Tile.of(2529, 3029, 0),
                        1.0
                    )

                    player.sendMessage(
                        "You daringly jump across the chasm."
                    )
                }

                player(
                    CALM_TALK,
                    "Phew! I just made it."
                )
            }

            op("Forget it, I'm not paying.") {
                player(
                    CALM_TALK,
                    "Forget it, I'm not paying."
                )

                npc(
                    TOLL_BRIDGE_GUARD,
                    CALM_TALK,
                    "In that case you're not crossing."
                )
            }
        }
    }
}

private fun handleShamanCaveGuard(player: Player, npc: NPC) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_FIND_SHAMAN_ENCLAVE)
        return

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "What do you want?"
        )

        options {
            op("I want to go in there.") {
                player(
                    CALM_TALK,
                    "I want to go in there."
                )

                npc(
                    npc,
                    CALM_TALK,
                    "Oh you do, do you? How about 'no'?"
                )

                exec {
                    npc.setCombatTarget(player)
                }
            }

            op("I want to rid the world of ogres!") {
                player(
                    CALM_TALK,
                    "I want to rid the world of ogres."
                )

                npc(
                    npc,
                    CALM_TALK,
                    "You dare mock me, creature!"
                )

                exec {
                    npc.setCombatTarget(player)
                }
            }
        }
    }
}

private fun handleNightshadeOnShamanCaveGuard(
    player: Player,
    npc: NPC
) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_FIND_SHAMAN_ENCLAVE)
        return

    if (!player.inventory.containsItem(CAVE_NIGHTSHADE, 1))
        return

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "What is this? Arrrrgh! I cannot stand this plant! Argh, it burns! It burns!"
        )

        exec {
            player.inventory.deleteItem(CAVE_NIGHTSHADE, 1)

            player.sendOptionDialogue(
                "You can run past, but keep in mind that a dangerous area follows!"
            ) { options ->
                options.add("Proceed regardless") {
                    player.set(
                        SHAMAN_CAVE_GUARD_DISTRACTED,
                        true
                    )

                    player.sendMessage(
                        "You run past the guard while he's busy."
                    )
                }

                options.add("Stay out")
            }
        }
    }
}

private fun ejectPlayerFromGuTanoth(player: Player) {
    val damage =
        (player.hitpoints * 0.05).toInt().coerceAtLeast(1)

    player.fadeScreen {
        player.tele(GUARD_PUNCH_DESTINATION)

        player.applyHit(
            Hit(
                player,
                damage,
                Hit.HitLook.TRUE_DAMAGE
            )
        )

        player.tempAttribs.setB(
            GU_TANOTH_EJECTION_GROAN,
            true
        )
    }
}

private fun handleOgreTrader(player: Player, npc: NPC) {
    if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_GET_SKAVID_MAP)
        return

    player.startConversation {
        npc(
            npc,
            CALM_TALK,
            "Arr, small thing wants my food, does it? I'll teach you to deal with ogres!"
        )

        exec {
            npc.setCombatTarget(player)
        }
    }
}