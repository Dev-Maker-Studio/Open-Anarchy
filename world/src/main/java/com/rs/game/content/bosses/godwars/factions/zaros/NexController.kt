package com.rs.game.content.bosses.godwars.factions.zaros

import com.rs.game.content.skills.magic.TeleType
import com.rs.game.model.entity.player.Controller
import org.darkan.core.gameval.gen.Cs2
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Varbit

class NexController(@Transient private var arena: NexArena? = null) : Controller() {
    private fun war() = arena ?: NexArena.globalInstance.also { arena = it }

    override fun start() {
        war().addPlayer(player)
        unseal()
        sendInterfaces()
    }

    override fun logout(): Boolean {
        war().removePlayer(player)
        return false
    }

    override fun login(): Boolean {
        war().addPlayer(player)
        unseal()
        sendInterfaces()
        return false
    }

    override fun sendInterfaces() {
        player.interfaceManager.setFadingInterface(Interface.interface_601)
        player.packets.sendRunScriptReverse(Cs2.cs2_1171)
    }

    override fun sendDeath(): Boolean {
        remove()
        removeController()
        return true
    }

    override fun onTeleported(type: TeleType) {
        remove()
        removeController()
    }

    override fun forceClose() = remove()

    private fun remove() {
        war().removePlayer(player)
        unseal()
        player.interfaceManager.closeFadingInterface()
    }

    /** The public prison keeps its climbable landslide; only an instance seals itself shut. */
    private fun unseal() = player.vars.setVarBit(Varbit.godwars2_climbdown_multi, 0)
}
