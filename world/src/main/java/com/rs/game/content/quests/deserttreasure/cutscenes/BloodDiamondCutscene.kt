package com.rs.game.content.quests.deserttreasure

import com.rs.engine.cutscenekt.cutscene
import com.rs.engine.dialogue.HeadE.*
import com.rs.engine.quest.Quest
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onPlayerStep
import world.gregs.voidps.type.Tile

private const val ROAVAR = 1042
private const val MALAK_CUTSCENE = 1920

private const val BLOOD_INTRO_CUTSCENE_SEEN = "dtBloodIntroCutsceneSeen"

private val HAIR_OF_THE_DOG_ENTRY = arrayOf(
    Tile.of(3494, 3480, 0)
)

private val ROAVAR_TILE = Tile.of(3493, 3471, 0)
private val MALAK_TILE = Tile.of(3497, 3478, 0)

@ServerStartupEvent
fun mapBloodDiamondIntroCutscene() {
    onPlayerStep(HAIR_OF_THE_DOG_ENTRY) { (player) ->
        if (player.getQuestStage(Quest.DESERT_TREASURE) != STAGE_DIAMONDS_OF_AZZANADRA)
            return@onPlayerStep
        if (player.getBool(BLOOD_INTRO_CUTSCENE_SEEN))
            return@onPlayerStep

        player.set(BLOOD_INTRO_CUTSCENE_SEEN, true)
        playBloodDiamondIntroCutscene(player)
    }
}

private fun playBloodDiamondIntroCutscene(player: Player) {
    player.cutscene {
        endTile = player.tile
        hideMinimap()

        camPos(3502, 3475, 900)
        camLook(ROAVAR_TILE.x, ROAVAR_TILE.y, 500)

        dialogue {
            npc(ROAVAR, NERVOUS, "M-my lord Malak, what brings you here?")
        }
        waitForDialogue()

        panTo(MALAK_TILE)
        dialogue {
            npc(MALAK_CUTSCENE, CALM_TALK, "Hmmm?<br>You know very well that your tithe is due, Roavar.")
            npc(MALAK_CUTSCENE, CALM_TALK, "I am here to collect it this time.")
        }
        waitForDialogue()

        panTo(ROAVAR_TILE)
        dialogue {
            npc(ROAVAR, NERVOUS, "Y-yes of course, sire, but surely your graciousness is too important to perform such menial duties such as this himself?")
            npc(ROAVAR, NERVOUS, "Is this not Gadderanks job, your most high beneficence?")
        }
        waitForDialogue()

        panTo(MALAK_TILE)
        dialogue {
            npc(MALAK_CUTSCENE, CALM_TALK, "Indeed it is Roavar, but I cannot find that incompetent fool.<br>And assuming you are not the one who wishes to tell Lord Drakan that his tithe has not been collected, I saw fit to take on the duty myself this time.")
        }
        waitForDialogue()

        panTo(ROAVAR_TILE)
        dialogue {
            npc(ROAVAR, NERVOUS, "Of course my lord, I meant no disrespect.")
        }
        waitForDialogue()

        panTo(MALAK_TILE)
        dialogue {
            npc(MALAK_CUTSCENE, QUESTION, "So you have the tithe ready for me?")
        }
        waitForDialogue()

        panTo(ROAVAR_TILE)
        dialogue {
            npc(ROAVAR, NERVOUS, "Of course my lord, I trust you will find it all there and in good order.")
        }
        waitForDialogue()

        panTo(MALAK_TILE)
        dialogue {
            npc(MALAK_CUTSCENE, CALM_TALK, "Hmmm.<br>Well, for your sake you had better hope that indeed it is.")
            npc(MALAK_CUTSCENE, ANGRY, "The mood I am in for having to come to this wolfen cesspit means any shortfall is coming from you directly.")
            npc(MALAK_CUTSCENE, CALM_TALK, "Then again... perhaps this was not a total waste of my time after all.")
            npc(MALAK_CUTSCENE, ANGRY, "You there!")
        }
        waitForDialogue()

        panTo(player.tile)
        dialogue {
            player(NERVOUS, "M-me?")
            npc(MALAK_CUTSCENE, CALM_TALK, "Yes, you.<br>Your bearing suggest you are not one of the usual peasants, and your stench suggests you are not one of these filthy wolfen folk.")
            npc(MALAK_CUTSCENE, CALM_TALK, "Come here, I would like to know what you are doing in these lands.")
        }
        waitForDialogue()

        camPosResetSoft()
        unhideMinimap()
        stop()
    }
}

private suspend fun com.rs.engine.cutscenekt.Cutscene.panTo(tile: Tile) {
    camLook(tile.x, tile.y, 500, 2, 2)
    wait(2)
}
