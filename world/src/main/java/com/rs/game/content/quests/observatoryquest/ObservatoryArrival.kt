package com.rs.game.content.quests.observatoryquest

import com.rs.engine.dialogue.DialogueBuilder
import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.World
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.model.Animation
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onObjectClick
import world.gregs.voidps.type.Tile
import com.rs.game.content.skills.Skills

private const val UNCUT_SAPPHIRE = 1623

private const val WATER_RUNE = 555
private const val AMULET_OF_DEFENCE = 1729
private const val BLACK_2H_SWORD = 1313
private const val LAW_RUNE = 563
private const val TUNA = 361
private const val MAPLE_LONGBOW = 851
private const val WEAPON_POISON = 187
private const val SUPER_STRENGTH_1 = 161

private const val CONSTELLATION_XP = 875.0

private const val OBSERVATORY_PROFESSOR = 488

private val RECEPTION_PROFESSOR_TILE =
    Tile.of(2439, 3186, 0)

private val OBSERVATORY_PROFESSOR_TILE =
    Tile.of(2438, 3160, 0)

private const val WAVE_ANIMATION = 863
private const val CHEER_ANIMATION = 862

@ServerStartupEvent
fun mapObservatoryArrival() {

    onLogin { (player) ->
        syncObservatoryProfessor(player)
    }

    onNpcClick(
        OBSERVATORY_PROFESSOR,
        options = arrayOf("Talk-to")
    ) { e ->

        val player = e.player
        val npc = e.npc

        if (
            npc !is OwnedNPC ||
            npc.owner != player
        ) {
            return@onNpcClick
        }

        if (
            npc.tile.isAt(OBSERVATORY_PROFESSOR_TILE)
        ) {
            when (
                player.getQuestStage(
                    Quest.OBSERVATORY_QUEST
                )
            ) {

                STAGE_TRAVELLING_TO_OBSERVATORY -> {
                    repairTelescope(
                        player,
                        npc
                    )
                }

                STAGE_OBSERVING_CONSTELLATION -> {
                    observingConstellationDialogue(
                        player
                    )
                }

                STAGE_COMPLETE -> {

                    player.startConversation {
                        npc(
                            OBSERVATORY_PROFESSOR,
                            HeadE.HAPPY_TALKING,
                            "Hello again."
                        )
                    }
                }
            }

            return@onNpcClick
        }

    }
}

fun syncObservatoryProfessor(
    player: Player
) {
    World.getNPCsInChunkRange(
        player.chunkId,
        10
    )
        .filterIsInstance<OwnedNPC>()
        .filter {
            it.id == OBSERVATORY_PROFESSOR &&
            it.owner == player
        }
        .forEach {
            it.finish()
        }

    val stage =
        player.getQuestStage(
            Quest.OBSERVATORY_QUEST
        )

    val tile =
        when {
            stage < STAGE_TRAVELLING_TO_OBSERVATORY ->
                RECEPTION_PROFESSOR_TILE

            else ->
                OBSERVATORY_PROFESSOR_TILE
        }

    val professor = OwnedNPC(
        player,
        OBSERVATORY_PROFESSOR,
        tile,
        true
    )

    professor.setAutoDespawnAtDistance(
        false
    )
}

private fun repairTelescope(
    player: Player,
    professor: OwnedNPC
) {
    player.startConversation {

        player(
            HeadE.HAPPY_TALKING,
            "Hi, professor!"
        )

        exec {
            professor.anim(
                Animation(WAVE_ANIMATION)
            )
        }

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.HAPPY_TALKING,
            "Oh, hi there!"
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "I'm just adding the finishing touches."
        )

        player(
            HeadE.CALM_TALK,
            "Okay, don't let me interrupt!"
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "Thank you."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "Right, let's get this finished."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "Hmmmm..."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "Bit of a tap here..."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "Player, I'm just going upstairs to finish off."
        )

        player(
            HeadE.CALM_TALK,
            "Right-oh."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "In goes the lens."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.HAPPY_TALKING,
            "Looking good."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "And one final adjustment."
        )

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.HAPPY_TALKING,
            "And all our work pays off."
        )

        exec {
            professor.anim(
                Animation(CHEER_ANIMATION)
            )

            player.setQuestStage(
                Quest.OBSERVATORY_QUEST,
                STAGE_OBSERVING_CONSTELLATION
            )
        }
    }
}

private fun observingConstellationDialogue(
    player: Player
) {
    player.startConversation {

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.CALM_TALK,
            "What would you like to talk about?"
        )

        options {
            op("Talk about the Observatory Quest.") {

                npc(
                    OBSERVATORY_PROFESSOR,
                    HeadE.HAPPY_TALKING,
                    "Hello, friend."
                )

                if (!hasViewedObservatoryConstellation(player)) {

                    player(
                        HeadE.HAPPY_TALKING,
                        "Hi, this really is impressive."
                    )

                    npc(
                        OBSERVATORY_PROFESSOR,
                        HeadE.HAPPY_TALKING,
                        "Certainly is. Please, take a look through the telescope and tell me what you see."
                    )

                    return@op
                }

                player(
                    HeadE.HAPPY_TALKING,
                    "I've had a look through the telescope."
                )

                npc(
                    OBSERVATORY_PROFESSOR,
                    HeadE.CALM_TALK,
                    "What did you see? If you're not sure, you can find out by looking at the star charts dotted around the walls downstairs."
                )

                player(
                    HeadE.CALM_TALK,
                    "It was..."
                )

                firstConstellationPage(player)
            }

            op("Talk about Treasure Trails.") {
                player.sendMessage(
                    "You decide to talk about that another time."
                )
            }
        }
    }
}

/*
 * =============================================================
 * CONSTELLATION ANSWER MENUS
 * =============================================================
 *
 * The order below follows the supplied quest transcript:
 *
 * Page 1:
 * Aquarius
 * Capricorn
 * Scorpio
 *
 * Page 2:
 * Libra
 * Virgo
 * Leo
 *
 * Page 3:
 * Cancer
 * Gemini
 * Taurus
 *
 * Page 4:
 * Aries
 * Pisces
 *
 * Sagittarius is included on the final page because it is one
 * of the twelve Observatory constellations and has its own
 * successful-result dialogue later in the transcript.
 */

private fun checkConstellationAnswer(
    player: Player,
    answer: Int
) {
    val actual =
        getObservatoryConstellation(player)

    player.startConversation {

        player(
            HeadE.HAPPY_TALKING,
            "${getObservatoryConstellationName(answer)}!"
        )

        if (answer != actual) {

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.CALM_TALK,
                "I'm afraid not. Have another look. Remember, you can check the star charts on the walls for reference."
            )

            return@startConversation
        }

        npc(
            OBSERVATORY_PROFESSOR,
            HeadE.HAPPY_TALKING,
            "That's exactly it!"
        )

        player(
            HeadE.HAPPY_TALKING,
            "Yes! Woo hoo!"
        )

        exec {
            player.anim(
                Animation(CHEER_ANIMATION)
            )
        }

        observatoryConstellationSuccess(
            player,
            actual
        )
    }
}

private fun DialogueBuilder.observatoryConstellationSuccess(
    player: Player,
    constellation: Int
) {
    when (constellation) {

        CONSTELLATION_AQUARIUS -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Aquarius, the water-bearer."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "It seems suitable, then, to award you with water runes!"
            )
        }

        CONSTELLATION_ARIES -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Aries, the ram."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "A fierce fighter. I'm sure he'll look down on you and improve your Attack for such insight."
            )
        }

        CONSTELLATION_CANCER -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Cancer, the crab."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "An armoured creature - I think I shall reward you with an amulet of protection."
            )
        }

        CONSTELLATION_CAPRICORN -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Capricorn, the goat."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "Capricorn will surely reward your insight with an increase to your Strength."
            )
        }

        CONSTELLATION_GEMINI -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Gemini, the twins."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "With the double nature of Gemini, I can't offer you anything more suitable than a two-handed weapon."
            )
        }

        CONSTELLATION_LEO -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Leo, the lion."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "I think the majestic power of the lion will improve your Constitution."
            )
        }

        CONSTELLATION_LIBRA -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Libra, the scales."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "Hmmm, balance, law, order - I shall award you with law runes!"
            )
        }

        CONSTELLATION_PISCES -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Pisces, the fish."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "What's more suitable as a reward than some tuna?"
            )
        }

        CONSTELLATION_SAGITTARIUS -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Sagittarius, the centaur."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "As you've spotted the archer, I shall reward you with a maple longbow."
            )
        }

        CONSTELLATION_SCORPIO -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Scorpio, the scorpion."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "I think weapon poison would make a suitable reward."
            )
        }

        CONSTELLATION_TAURUS -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Taurus, the bull."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "This Strength potion should be a suitable reward."
            )
        }

        CONSTELLATION_VIRGO -> {
            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "That's Virgo, the virtuous."
            )

            npc(
                OBSERVATORY_PROFESSOR,
                HeadE.HAPPY_TALKING,
                "Virgo will surely provide you with an increase to Defense."
            )
        }
    }

    npc(
        OBSERVATORY_PROFESSOR,
        HeadE.HAPPY_TALKING,
        "By Saradomin's earlobes! You must be a friend of the gods indeed."
    )

    npc(
        OBSERVATORY_PROFESSOR,
        HeadE.HAPPY_TALKING,
        "Look in your backpack for your reward, in payment for your work."
    )

    npc(
        OBSERVATORY_PROFESSOR,
        HeadE.CALM_TALK,
        "I'll unlock the doors to make it easier for you to get back."
    )

    player(
        HeadE.CALM_TALK,
        "And I suppose I'll just jump down?"
    )

    npc(
        OBSERVATORY_PROFESSOR,
        HeadE.CALM_TALK,
        "Oh right! I'll just have my assistant tie a rope off or something..."
    )

    npc(
        OBSERVATORY_PROFESSOR,
        HeadE.CALM_TALK,
        "Perhaps in the future you can help me fix the bridge, but there's too much preparation I'll have to do before then."
    )

    exec {
        if (
            player.getQuestStage(Quest.OBSERVATORY_QUEST) ==
            STAGE_OBSERVING_CONSTELLATION
        ) {
            giveObservatoryConstellationReward(
                player,
                constellation
            )

            player.questManager.completeQuest(Quest.OBSERVATORY_QUEST)
            syncObservatoryProfessor(player)
            syncObservatoryRope(player)
        }
    }

}

private fun giveObservatoryConstellationReward(
    player: Player,
    constellation: Int
) {

    player.inventory.addItem(
        UNCUT_SAPPHIRE,
        1
    )

    when (constellation) {

        CONSTELLATION_AQUARIUS -> {
            player.inventory.addItem(
                WATER_RUNE,
                25
            )
        }

        CONSTELLATION_ARIES -> {
            player.skills.addXpQuest(
                Skills.ATTACK,
                CONSTELLATION_XP
            )
        }

        CONSTELLATION_CANCER -> {
            player.inventory.addItem(
                AMULET_OF_DEFENCE,
                1
            )
        }

        CONSTELLATION_CAPRICORN -> {
            player.skills.addXpQuest(
                Skills.STRENGTH,
                CONSTELLATION_XP
            )
        }

        CONSTELLATION_GEMINI -> {
            player.inventory.addItem(
                BLACK_2H_SWORD,
                1
            )
        }

        CONSTELLATION_LEO -> {
            player.skills.addXpQuest(
                Skills.HITPOINTS,
                CONSTELLATION_XP
            )
        }

        CONSTELLATION_LIBRA -> {
            player.inventory.addItem(
                LAW_RUNE,
                3
            )
        }

        CONSTELLATION_PISCES -> {
            player.inventory.addItem(
                TUNA,
                3
            )
        }

        CONSTELLATION_SAGITTARIUS -> {
            player.inventory.addItem(
                MAPLE_LONGBOW,
                1
            )
        }

        CONSTELLATION_SCORPIO -> {
            player.inventory.addItem(
                WEAPON_POISON,
                1
            )
        }

        CONSTELLATION_TAURUS -> {
            player.inventory.addItem(
                SUPER_STRENGTH_1,
                1
            )
        }

        CONSTELLATION_VIRGO -> {
            player.skills.addXpQuest(
                Skills.DEFENSE,
                CONSTELLATION_XP
            )
        }
    }
}
