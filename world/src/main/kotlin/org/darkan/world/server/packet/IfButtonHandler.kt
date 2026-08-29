package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import com.rs.plugin.PluginManager
import com.rs.plugin.events.ButtonClickEvent
import org.darkan.core.Logger.logTrace
import org.darkan.core.net.prot.packet.IfButton
import org.darkan.core.net.prot.handler.PacketHandler

class IfButtonHandler : PacketHandler<Player, IfButton> {
    override suspend fun handle(player: Player, packet: IfButton) {
        if (!player.hasStarted() || !player.isRunning)
            return
        logTrace("IfButton: inter=${packet.interfaceId} comp=${packet.componentId} slot=${packet.slotId} item=${packet.itemId} op=${packet.opNum}")
        if (player.isDead || player.isLocked && !(packet.interfaceId == 14 && packet.componentId == 103))
            return
        if (!player.interfaceManager.topOpen(packet.interfaceId))
            return
        if (packet.opNum < 1 || packet.opNum > 10)
            return
        val cont = player.controllerManager.processButtonClick(packet.interfaceId, packet.componentId, packet.slotId, packet.itemId, packet.opNum)
        if (cont)
            PluginManager.handle(ButtonClickEvent(player, packet.interfaceId, packet.componentId, packet.slotId, packet.itemId, packet.opNum))
    }
}
