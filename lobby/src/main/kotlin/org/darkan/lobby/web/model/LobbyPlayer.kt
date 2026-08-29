package org.darkan.lobby.web.model

import io.ktor.utils.io.*
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logDebug
import org.darkan.core.Logger.logError
import org.darkan.core.model.Account
import org.darkan.core.model.Vars
import org.darkan.core.model.toLobbyLoginDetails
import org.darkan.core.net.Session
import org.darkan.core.net.login.LoginToken
import org.darkan.core.net.prot.handler.PacketHandlers
import org.darkan.core.net.prot.packet.LobbyLoginDetails
import org.darkan.core.net.prot.packet.Ping
import org.darkan.core.net.prot.packet.WorldListPacket
import org.darkan.lobby.Lobby
import org.darkan.lobby.social.SocialGateway

class LobbyPlayer(val session: Session, val account: Account) {
    val vars = Vars().setSession(session)
    val worldLoginToken: String = LoginToken.issue(
        username = account.username,
        nowMs = System.currentTimeMillis(),
        ttlMs = 5 * 60 * 1000L,
        secret = EnvVars.worldLoginTokenSecret,
    )

    suspend fun login(read: ByteReadChannel) {
        try {
            // The client caches the world it is told to "play now" on, so hand it a world that
            // actually exists; the placeholder only stands in until a world registers.
            val defaultWorld = Lobby.worldList.getDefault()?.metadata?.takeIf { it.isValid }
            session.send(
                account.toLobbyLoginDetails(
                    worldLoginToken = worldLoginToken,
                    defaultWorldId = defaultWorld?.number ?: LobbyLoginDetails.DEFAULT_WORLD_ID,
                    defaultWorldAddress = defaultWorld?.ipAddress ?: LobbyLoginDetails.DEFAULT_WORLD_ADDRESS,
                ),
                noIsaac = true,
            )
            session.send(WorldListPacket(Lobby.worldList.snapshot(), refreshOnClient = true, refreshOnlyPlayerCounts = false))
            vars.setVar(281, 1000)
            vars.setVar(2528, 1)
            vars.setVar(2567, 1)

            vars.setVarBit(10242, 1) // 2 for validating email address
            vars.setVarBit(10243, 12)

            vars.setVarc(1919, 1) // set email to validated
            vars.setVarBit(11162, 1)
            vars.syncVarsToClient()

            SocialGateway.registerLobbyPlayerSession(account, session)
            session.flush()
            session.readPackets(read)
        } finally {
            try {
                SocialGateway.unregisterLobbyPlayerSession(account.username)
            } catch (_: Throwable) {
            }
            session.exit()
            session.disconnect()
        }
    }

    suspend fun handleDecodedPackets() {
        for (i in 0 until EnvVars.packetQueueCapacity) {
            val packet = session.readChannel.tryReceive().getOrNull() ?: break
            if (packet !is Ping)
                logDebug("Handling packet: $packet")
            try {
                PacketHandlers.getHandler<LobbyPlayer>(packet.javaClass)?.handle(this, packet)
            } catch (e: Throwable) {
                logError("Failed to handle packet: $packet", e)
            }
        }
    }
}