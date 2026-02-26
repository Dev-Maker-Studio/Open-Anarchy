package org.darkan.world.server.packet

import com.rs.engine.command.Commands
import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.ClientCheat
import org.darkan.core.net.prot.handler.PacketHandler

class ClientCheatHandler : PacketHandler<Player, ClientCheat> {
    override suspend fun handle(player: Player, packet: ClientCheat) {
        if (!player.isRunning) return
        try {
            Commands.processCommand(player, packet.command, true, packet.client)
        } catch (_: Throwable) {}
    }
}
