package com.rs.game.content.minigames.pest

import com.rs.game.content.skills.magic.TeleType
import com.rs.game.model.entity.Teleport
import com.rs.game.model.entity.async.schedule
import com.rs.game.model.entity.player.Controller
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.map.pathfinder.Direction

private const val MUSIC = 588
private const val DEATH_JINGLE = 90

class PestControlGameController(@Transient val game: PestControlGame?) : Controller() {
    @Transient
    private var loggingOut = false

    override fun playAmbientOnControllerRegionEnter() = false

    override fun playAmbientStrictlyBackgroundMusic() = true

    override fun start() {
        player.setForceMultiArea(true)
        player.musicsManager.playSongAndUnlock(MUSIC)
        sendInterfaces()
    }

    override fun sendInterfaces() {
        game?.sendHud(player)
    }

    override fun canMove(dir: Direction): Boolean {
        val game = game ?: return true
        return !game.blocksMovement(player.tile.transform(dir.dx, dir.dy))
    }

    override fun processTeleport(tele: Teleport): Boolean {
        if (tele.type() == TeleType.OBJECT) return true
        player.simpleDialogue("You can't leave the pest control area like this.")
        return false
    }

    override fun canSummonFamiliar(): Boolean {
        player.sendMessage("You feel it's best to keep your familiar away during this game.")
        return false
    }

    override fun sendDeath(): Boolean {
        val game = game ?: return true
        player.lock()
        player.schedule {
            player.anim(Seq.human_death)
            wait(1)
            player.sendMessage("Oh dear, you have died.")
            wait(2)
            player.tele(game.deckTile())
            player.anim(-1)
            player.jingle(DEATH_JINGLE)
            player.unlock()
            player.reset()
        }
        return false
    }

    override fun logout(): Boolean {
        loggingOut = true
        game?.leave(player)
        player.tile = (game?.tier ?: LanderTier.NOVICE).exitTile
        return true
    }

    override fun login(): Boolean {
        player.tile = LanderTier.NOVICE.exitTile
        return true
    }

    override fun forceClose() {
        game?.leave(player)
        player.setForceMultiArea(false)
        player.interfaceManager.removeOverlay()
        player.reset()
        if (!loggingOut) player.useStairs(-1, (game?.tier ?: LanderTier.NOVICE).exitTile, 1, 2)
    }
}
