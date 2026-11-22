package com.rs.game.content.bosses.godwars.factions.zaros

import com.rs.game.content.skills.magic.TeleType
import com.rs.game.content.world.generated.GodwarsDungeons
import com.rs.game.model.entity.player.Controller
import org.darkan.core.gameval.gen.Cs2
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Varbit

class NexController(@Transient private var arena: NexArena? = null, private val dungeon: String? = null) : Controller() {
    private fun war() = arena ?: (if (dungeon == null) NexArena.globalInstance else GodwarsDungeons.byId(dungeon)?.nexArena)?.also { arena = it }

    override fun start() {
        war()?.addPlayer(player)
        seal(1)
        sendInterfaces()
    }

    override fun logout(): Boolean {
        war()?.removePlayer(player)
        dungeon?.let(GodwarsDungeons::byId)?.let { player.setTile(it.surface) }
        return false
    }

    override fun login(): Boolean {
        if (dungeon != null) {
            GodwarsDungeons.byId(dungeon)?.let { player.tele(it.surface) }
            seal(0)
            return true
        }
        war()?.addPlayer(player)
        seal(1)
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
        war()?.removePlayer(player)
        seal(0)
        player.interfaceManager.closeFadingInterface()
    }

    private fun seal(value: Int) = player.vars.setVarBit(Varbit.godwars2_climbdown_multi, value)
}
