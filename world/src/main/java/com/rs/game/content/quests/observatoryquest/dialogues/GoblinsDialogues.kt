package com.rs.game.content.quests.observatoryquest.dialogues

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.World
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.ForceTalk
import org.darkan.core.Utils
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick
import com.rs.utils.Ticks
import org.darkan.core.model.Direction

private const val GREASYCHEEKS = 6127
private const val SMELLYTOES = 6128
private const val CREAKYKNEES = 6129

private fun configureObservatoryGoblin(npc: NPC) {

    npc.setRandomWalk(false)

    when (npc.id) {

        GREASYCHEEKS -> {
            npc.faceDir(Direction.WEST)
        }

        SMELLYTOES -> {
            npc.faceDir(Direction.EAST)
        }

        CREAKYKNEES -> {
            npc.faceDir(Direction.SOUTH)
        }
    }
}

private fun sendObservatoryGoblinOverhead(npc: NPC) {

    val message = when (npc.id) {

        CREAKYKNEES -> {
            if (Utils.random(2) == 0)
                "Come on! Please light!"
            else
                "Was that a spark?"
        }

        SMELLYTOES -> {
            if (Utils.random(2) == 0)
                "La la la. Do di dum dii!"
            else
                "Doh ray meeee laa doh faaa!"
        }

        GREASYCHEEKS -> {
            when (Utils.random(3)) {
                0 -> "This is gonna taste sooo good"
                1 -> "Cook, cook, cook!"
                else -> "I'm so hungry!"
            }
        }

        else -> return
    }

    npc.setNextForceTalk(
        ForceTalk(message)
    )
}

@ServerStartupEvent
fun mapObservatoryGoblinDialogues() {
onNpcClick(
        CREAKYKNEES,
        options = arrayOf("Talk-to")
    ) { e ->

        val player = e.player

        player.startConversation {

            player(
                HeadE.CALM_TALK,
                "Where did you get that lens?"
            )

            npc(
                CREAKYKNEES,
                HeadE.CALM_TALK,
                "From that strange metal thing up on the hill."
            )

            player(
                HeadE.CALM_TALK,
                "You should give that back!"
            )

            npc(
                CREAKYKNEES,
                HeadE.CALM_TALK,
                "Even if it's cracked?"
            )

            player(
                HeadE.CALM_TALK,
                "Ah, well, I suppose it's of no use. But, still."
            )
        }
    }

    onNpcClick(
        SMELLYTOES,
        options = arrayOf("Talk-to")
    ) { e ->

        val player = e.player

        player.startConversation {

            player(
                HeadE.CALM_TALK,
                "Hi there."
            )

            npc(
                SMELLYTOES,
                HeadE.CALM_TALK,
                "Hey, ids me matesh!"
            )

            player(
                HeadE.CALM_TALK,
                "Sorry, have we met?"
            )

            npc(
                SMELLYTOES,
                HeadE.CALM_TALK,
                "Yeah! you wazsh wiv me in dat pub overy by hill!"
            )

            player(
                HeadE.CALM_TALK,
                "I have no idea what you're going on about."
            )

            npc(
                SMELLYTOES,
                HeadE.CALM_TALK,
                "Glad yeeash remembers."
            )
        }
    }

    onNpcClick(
        GREASYCHEEKS,
        options = arrayOf("Talk-to")
    ) { e ->

        val player = e.player

        player.startConversation {

            player(
                HeadE.CALM_TALK,
                "Hello."
            )

            npc(
                GREASYCHEEKS,
                HeadE.CALM_TALK,
                "Shush! I'm concentrating."
            )

            player(
                HeadE.CALM_TALK,
                "Oh, sorry."
            )
        }
    }

    World.getNPCs().forEach { npc ->

        if (
            npc.id != GREASYCHEEKS &&
            npc.id != SMELLYTOES &&
            npc.id != CREAKYKNEES
        ) {
            return@forEach
        }

        configureObservatoryGoblin(npc)

        World.scheduleLooping(
            Ticks.fromSeconds(15),
            Ticks.fromSeconds(25)
        ) {

            if (npc.isDead || npc.hasFinished()) {
                return@scheduleLooping
            }

            configureObservatoryGoblin(npc)

            if (Utils.random(3) == 0) {
                sendObservatoryGoblinOverhead(npc)
            }
        }
    }
}
