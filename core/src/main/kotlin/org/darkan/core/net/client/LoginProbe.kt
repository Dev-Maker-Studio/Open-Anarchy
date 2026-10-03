package org.darkan.core.net.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.darkan.core.EnvVars
import org.darkan.core.net.js5.Js5Client
import org.darkan.core.net.login.LobbyLoginRequest
import org.darkan.core.net.login.LoginRsaKey
import org.darkan.core.net.login.WorldLoginRequest
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.packet.BuildRegion
import org.darkan.core.net.prot.revision.rev727.register727
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.system.exitProcess

object LoginProbe {

    private const val PUBLIC_EXPONENT = 65537L
    private const val LISTEN_MILLIS = 6000L
    private const val ISAAC_WORDS = 4
    private const val USAGE = "usage: LoginProbe <lobbyHost> <lobbyPort> <worldHost> <worldPort> <worldId> <username> <password>"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 7) {
            println(USAGE)
            exitProcess(1)
        }
        register727()
        runBlocking { probe(args[0], args[1].toInt(), args[2], args[3].toInt(), args[4].toInt(), args[5], args[6]) }
        exitProcess(0)
    }

    private suspend fun probe(lobbyHost: String, lobbyPort: Int, worldHost: String, worldPort: Int, worldId: Int, username: String, password: String) {
        val codec = Codec.get(EnvVars.majorVersion) ?: error("No codec for ${EnvVars.majorVersion}")
        val key = LoginRsaKey(BigInteger(EnvVars.js5RsaModulus), BigInteger.valueOf(PUBLIC_EXPONENT))
        val lobbyCrcs = Js5Client.connect(lobbyHost, lobbyPort, EnvVars.majorVersion, EnvVars.minorVersion, EnvVars.js5ServerToken).use { it.indexCrcs() }
        val lobby = ClientConnection.lobby(
            lobbyHost, lobbyPort, codec,
            LobbyLoginRequest(EnvVars.majorVersion, EnvVars.minorVersion, seed(), username, password, EnvVars.loginServerToken, EnvVars.clientKey, EnvVars.js5ServerToken, lobbyCrcs),
            key
        )
        val details = (lobby as? LoginResult.Connected)?.details ?: return println("lobby login refused: $lobby")
        println("lobby login ok as ${details.displayName}")
        (lobby as LoginResult.Connected).connection.close()
        val worldCrcs = Js5Client.connect(worldHost, worldPort, EnvVars.majorVersion, EnvVars.minorVersion, EnvVars.js5ServerToken).use { it.indexCrcs() }
        val world = ClientConnection.world(
            worldHost, worldPort, codec,
            WorldLoginRequest(EnvVars.majorVersion, EnvVars.minorVersion, seed(), username, details.worldLoginToken, EnvVars.worldToken, worldId, worldCrcs),
            key
        )
        val connected = world as? LoginResult.Connected ?: return println("world login refused: $world")
        println("world $worldId login ok, player index ${connected.details.playerIndex}")
        val job = connected.connection.start(CoroutineScope(Dispatchers.IO))
        withTimeoutOrNull(LISTEN_MILLIS) {
            while (true) {
                val packet = connected.connection.session.readChannel.receive()
                if (packet is BuildRegion) println("build region: chunk ${packet.chunkX},${packet.chunkY} regions ${packet.regionIds.joinToString()}")
            }
        }
        job.cancel()
        connected.connection.close()
    }

    private fun seed() = IntArray(ISAAC_WORDS) { SecureRandom().nextInt() }
}
