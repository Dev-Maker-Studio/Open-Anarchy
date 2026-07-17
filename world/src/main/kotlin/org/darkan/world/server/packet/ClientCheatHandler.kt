package org.darkan.world.server.packet

import com.rs.engine.command.Commands
import com.rs.game.model.entity.player.Player
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import org.darkan.core.net.prot.ClientCheat
import org.darkan.core.net.prot.handler.PacketHandler

class ClientCheatHandler : PacketHandler<Player, ClientCheat> {
    override suspend fun handle(player: Player, packet: ClientCheat) {
        if (!player.isRunning) return
        logInfo("ClientCheat: user=${player.username} client=${packet.client} save=${packet.save} command='${packet.command}' rights=${player.rights}")
        try {
            // console = true (came from the client console), clientCommand = packet.client
            val handled = Commands.processCommand(player, packet.command, true, packet.client)
            if (!handled) {
                logInfo("ClientCheat: no matching command '${packet.command}' for rights ${player.rights}")
                player.sendMessage("Unknown command: ::${packet.command}")
            }
        } catch (e: Throwable) {
            logError("ClientCheat command '${packet.command}' from ${player.username} threw", e)
            player.sendMessage("Error executing command: ${e::class.simpleName}: ${e.message}")
        }
    }
}
