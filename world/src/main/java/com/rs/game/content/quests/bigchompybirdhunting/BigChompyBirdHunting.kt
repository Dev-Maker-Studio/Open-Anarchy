package com.rs.game.content.quests.bigchompybirdhunting

import com.rs.engine.quest.Quest
import com.rs.engine.quest.QuestHandler
import com.rs.engine.quest.QuestOutline
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import org.darkan.core.Constants

const val STAGE_UNSTARTED = 0
const val STAGE_MAKING_OGRE_ARROWS = 1
const val STAGE_GETTING_BLOATED_TOAD = 2
const val STAGE_SHOWING_BLOATED_TOAD = 3
const val STAGE_BAITING_CHOMPY = 4
const val STAGE_RANTZ_FAILED_HUNT = 5
const val STAGE_HUNTING_CHOMPY = 6
const val STAGE_COOKING_CHOMPY = 7
const val STAGE_COMPLETE = 8

const val WOLF_BONES = 2859
const val WOLFBONE_ARROWHEADS = 2861

const val ACHEY_LOGS = 2862
const val OGRE_ARROW_SHAFT = 2864
const val FLIGHTED_OGRE_ARROW = 2865
const val OGRE_ARROW = 2866

const val OGRE_BELLOWS = 2871
const val OGRE_BELLOWS_3 = 2872
const val OGRE_BELLOWS_2 = 2873
const val OGRE_BELLOWS_1 = 2874

const val BLOATED_TOAD = 2875

const val RAW_CHOMPY = 2876
const val COOKED_CHOMPY = 2878
const val SEASONED_CHOMPY = 2882

const val OGRE_BOW = 2883

const val REQUIRED_COOKING_LEVEL = 30
const val REQUIRED_FLETCHING_LEVEL = 5
const val REQUIRED_RANGED_LEVEL = 30

@QuestHandler(
    quest = Quest.BIG_CHOMPY_BIRD_HUNTING,
    startText = "Speak to Rantz in the Feldip Hills.",
    itemsText = "Knife, axe, feathers and wolf bones. Other items can be obtained during the quest.",
    combatText = "You will need to hunt a chompy bird.",
    rewardsText =
        "262 Fletching XP<br>" +
        "1,470 Cooking XP<br>" +
        "735 Ranged XP<br>" +
        "Ogre bow<br>" +
        "Ability to fletch ogre arrows<br>" +
        "Ability to hunt and cook chompy birds<br>" +
        "Ability to earn ogre hats",
    completedStage = STAGE_COMPLETE
)
class BigChompyBirdHunting : QuestOutline() {

    companion object {
        @JvmStatic
        fun meetsRequirements(player: Player): Boolean {
            return player.skills.getLevel(Constants.COOKING) >= REQUIRED_COOKING_LEVEL &&
                    player.skills.getLevel(Constants.FLETCHING) >= REQUIRED_FLETCHING_LEVEL &&
                    player.skills.getLevel(Constants.RANGE) >= REQUIRED_RANGED_LEVEL
        }
    }

    override fun getJournalLines(
        player: Player,
        stage: Int
    ): List<String> = when (stage) {

        STAGE_UNSTARTED -> listOf(
            "I can start this quest by speaking to Rantz",
            "in the Feldip Hills.",
            "",
            "I need level 30 Cooking, level 5 Fletching",
            "and level 30 Ranged."
        )

        STAGE_MAKING_OGRE_ARROWS -> listOf(
            "Rantz wants to hunt a chompy bird.",
            "He has asked me to make some 'stabbers' for him.",
            "",
            "I need to make ogre arrows using Achey logs,",
            "feathers and arrowheads made from wolf bones.",
            "Rantz needs six ogre arrows."
        )

        STAGE_GETTING_BLOATED_TOAD -> listOf(
            "I gave Rantz six ogre arrows.",
            "",
            "He says chompy birds are attracted to",
            "'fatsy toadies'.",
            "I need to find a way to inflate one of the",
            "toads from the nearby swamp."
        )

        STAGE_SHOWING_BLOATED_TOAD -> listOf(
            "I have managed to make a bloated toad.",
            "",
            "I should show the 'fatsy toady' to Rantz",
            "so he can tell me what to do with it."
        )

        STAGE_BAITING_CHOMPY -> listOf(
            "Rantz approved of my bloated toad.",
            "",
            "I need to place a bloated toad in the",
            "clearing to the south so that it can attract",
            "a chompy bird."
        )

        STAGE_RANTZ_FAILED_HUNT -> listOf(
            "The bloated toad attracted a chompy bird,",
            "but Rantz keeps missing it.",
            "",
            "Perhaps I can convince him to let me",
            "try using his ogre bow."
        )

        STAGE_HUNTING_CHOMPY -> listOf(
            "Rantz has lent me an ogre bow.",
            "",
            "I need to use the bow and ogre arrows",
            "to hunt a chompy bird for Rantz and his family."
        )

        STAGE_COOKING_CHOMPY -> listOf(
            "I have hunted a chompy bird.",
            "",
            "Rantz wants me to prepare and cook the bird",
            "for him, Fycie and Bugs.",
            "I should find out how each of them wants",
            "their chompy prepared."
        )

        STAGE_COMPLETE -> listOf(
            "QUEST COMPLETE!"
        )

        else -> listOf(
            "Invalid quest stage. Report this to an administrator."
        )
    }

    override fun complete(player: Player) {

        player.skills.addXpQuest(
            Skills.FLETCHING,
            262.0
        )

        player.skills.addXpQuest(
            Skills.COOKING,
            1470.0
        )

        player.skills.addXpQuest(
            Skills.RANGE,
            735.0
        )

        sendQuestCompleteInterface(
            player,
            OGRE_BOW
        )
    }
}
