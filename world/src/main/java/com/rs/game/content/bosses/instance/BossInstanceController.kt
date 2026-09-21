package com.rs.game.content.bosses.instance

import com.rs.game.content.skills.magic.TeleType
import com.rs.game.model.entity.player.Controller
import world.gregs.voidps.type.Tile

class BossInstanceController(@Transient private val instance: BossInstance?) : Controller() {
    val practiceMode: Boolean get() = instance?.settings?.practiceMode == true

    override fun start() {
        val live = instance ?: return leave()
        live.instance.let { player.setInstancedArea(it) }
        player.tele(live.localTile(live.def.entrance))
    }

    override fun gainXP(skillId: Int, exp: Double): Boolean = !practiceMode

    override fun sendDeath(): Boolean {
        if (!practiceMode) return true
        player.reset()
        player.tele(returnTile())
        leave()
        return false
    }

    override fun onTeleported(type: TeleType) = leave()

    override fun forceClose() {
        instance?.remove(player)
    }

    override fun logout(): Boolean {
        instance?.remove(player)
        player.setTile(returnTile())
        return true
    }

    override fun login(): Boolean {
        player.tele(returnTile())
        return true
    }

    private fun returnTile(): Tile = instance?.def?.returnTo ?: player.tile

    private fun leave() {
        instance?.remove(player)
        player.tele(returnTile())
        removeController()
    }
}
