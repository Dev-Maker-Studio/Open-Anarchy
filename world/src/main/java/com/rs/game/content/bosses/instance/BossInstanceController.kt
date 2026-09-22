package com.rs.game.content.bosses.instance

import com.rs.game.content.skills.magic.TeleType
import com.rs.game.model.entity.player.Controller
import com.rs.game.tasks.WorldTasks
import world.gregs.voidps.type.Tile

class BossInstanceController(@Transient private val instance: BossInstance?) : Controller() {
    override fun start() {
        val live = instance ?: return leave()
        live.instance.let { player.setInstancedArea(it) }
        player.tele(live.localTile(live.def.entrance))
        setInside(1)
    }

    override fun onTeleported(type: TeleType) = leave()

    override fun forceClose() {
        setInside(0)
        instance?.remove(player)
    }

    override fun logout(): Boolean {
        setInside(0)
        instance?.remove(player)
        player.setTile(returnTile())
        return true
    }

    override fun login(): Boolean {
        player.tele(returnTile())
        WorldTasks.schedule { restoreArea() }
        return true
    }

    private fun setInside(value: Int) {
        val varbit = instance?.def?.insideVarbit ?: return
        if (varbit != -1) player.vars.setVarBit(varbit, value)
    }

    private fun returnTile(): Tile = instance?.def?.returnTo ?: player.tile

    fun leaveInstance() = leave()

    private fun leave() {
        setInside(0)
        instance?.remove(player)
        player.tele(returnTile())
        removeController()
        restoreArea()
    }

    /**
     * An arena inside a controlled area leaves the player standing in it, so they get its controller
     * back: without it the godwars doors, altars and the prison entrance stop answering.
     */
    private fun restoreArea() {
        val area = instance?.def?.returnController ?: return
        player.controllerManager.startController(area())
    }
}
