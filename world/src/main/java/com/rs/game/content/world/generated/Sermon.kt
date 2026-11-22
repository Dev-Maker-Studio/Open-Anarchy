package com.rs.game.content.world.generated

import com.rs.engine.cutscene.Cutscene
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils
import org.darkan.core.map.pathfinder.Direction
import org.darkan.core.model.MoveType

private const val SERMON_SHARE = 2
private val PRAISES = arrayOf("Amen.", "Preach", "Praise the Lord.")
private val CLOSING_PRAYER = listOf(
    "That concludes our service..." to 4,
    "Let us pray in closing." to 4,
    "The Lord bless you" to 3,
    "and keep you." to 3,
    "The Lord make His face." to 4,
    "Shine upon you." to 3,
    "And give you peace." to 3,
)

class Sermon(private val church: Church, private val sermon: Array<String>) : Cutscene() {
    private val start = church.serviceTile

    override fun construct(player: Player) {
        val priest = church.priest ?: return
        setEndTile(start)
        music(CHURCH_MUSIC)
        action {
            priest.forceTalk("Let us pray.")
        }
        delay(1)
        playerMove(start.x, start.y, start.level, MoveType.WALK)
        delay(2)
        action {
            priest.forceTalk("Father in heaven...")
            priest.faceDir(Direction.NORTH)
        }
        delay(3)
        action { priest.forceTalk("Grant me the wisdom to preach...") }
        delay(4)
        action { priest.forceTalk("Forgive us our sins, Christ.") }
        action(-1) { player.setNextFaceTile(player.transform(Direction.SOUTH.dx, Direction.SOUTH.dy)) }
        delay(4)
        action { priest.forceTalk("Have mercy on us..") }
        delay(3)
        action { priest.forceTalk("Amen...") }
        delay(2)
        action {
            for (npc in church.congregation()) {
                npc.forceTalk("Amen.")
                player.forceTalk("Amen.")
                npc.faceDir(npc.facing)
            }
        }
        delay(1)
        for (sentence in sermon.take((sermon.size + 1) / SERMON_SHARE)) {
            action { priest.forceTalk(sentence) }
            delay(sentence.length / 5 / 2)
            action {
                if (Utils.random(2) == 0)
                    church.congregation().randomOrNull()?.forceTalk(PRAISES[Utils.random(PRAISES.size)])
            }
            delay(sentence.length / 5 / 2)
        }
        for ((line, pause) in CLOSING_PRAYER) {
            action { priest.forceTalk(line) }
            delay(pause)
        }
        action {
            church.congregation().forEach { it.forceTalk("Amen.") }
            for (congregate in World.players) {
                if ((congregate.controllerManager.controller as? ChurchService)?.church !== church)
                    continue
                congregate.forceTalk("Amen.")
                if (ChurchBlessing.bless(congregate))
                    congregate.sendMessage("<col=00FFFF>You have increased in knowledge of prayer...")
            }
        }
    }
}
