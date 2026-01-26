package org.darkan.world.model

import io.ktor.utils.io.*
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logDebug
import org.darkan.core.Logger.logError
import org.darkan.core.model.Account
import org.darkan.core.net.Session
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.Ping
import org.darkan.core.net.prot.handler.PacketHandlers
import org.darkan.world.social.SocialClient

class WorldPlayer(
    val session: Session,
    val account: Account,
    val socialClient: SocialClient,
) {
    var chatType: Int = 0
    var friendsChatOwnerUsername: String? = null

    suspend fun handleDecodedPackets() {
        for (i in 0 until EnvVars.packetQueueCapacity) {
            val packet: ClientProt = session.readChannel.tryReceive().getOrNull() ?: break
            if (packet !is Ping) {
                logDebug("Handling packet: $packet")
            }
            try {
                PacketHandlers.getHandler<WorldPlayer>(packet.javaClass)?.handle(this, packet)
            } catch (e: Throwable) {
                logError("Failed to handle packet: $packet", e)
            }
        }
    }
}


