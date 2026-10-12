package com.rs.game.content.bosses.instance

import com.rs.game.content.skills.magic.TeleType
import com.rs.game.model.entity.player.Controller
import com.rs.game.tasks.WorldTasks
import world.gregs.voidps.type.Tile

class BossInstanceController(@Transient private val instance: BossInstance? = null, private val defKey: String = "") : Controller() {
    private val def: BossInstanceDef? get() = instance?.def ?: BossInstanceDefs[defKey]

    override fun start() {
        val live = instance ?: return leave()
        player.setInstancedArea(live.instance)
        player.tele(live.localTile(live.def.entrance))
        setInside(1)
    }

    override fun onTeleported(type: TeleType) = release()

    override fun forceClose() {
        setInside(0)
        instance?.remove(player)
    }

    override fun logout(): Boolean {
        setInside(0)
        instance?.remove(player)
        player.setTile(returnTile())
        return false
    }

    override fun login(): Boolean {
        player.tele(returnTile())
        WorldTasks.schedule { restoreArea() }
        return true
    }

    fun leaveInstance() = leave()

    private fun leave() {
        release()
        player.tele(returnTile())
        restoreArea()
    }

    private fun release() {
        setInside(0)
        instance?.remove(player)
        removeController()
    }

    private fun restoreArea() {
        val area = def?.returnController ?: return
        player.controllerManager.startController(area())
    }

    private fun setInside(value: Int) {
        val varbit = def?.insideVarbit ?: return
        if (varbit != -1) player.vars.setVarBit(varbit, value)
    }

    private fun returnTile(): Tile = def?.returnTo ?: player.tile
}
