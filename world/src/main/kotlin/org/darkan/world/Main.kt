package org.darkan.world

import kotlinx.coroutines.runBlocking
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.Logger.logInfo
import org.darkan.core.net.prot.handler.PacketHandlers
import org.darkan.core.net.prot.revision.register727
import org.darkan.world.server.WorldServer
import world.gregs.voidps.cache.Cache
import java.util.logging.Level

fun main() = runBlocking {
    // Ensure cache is available for CRC checks during login.
    Logger.setLogLevel(Level.ALL)
    Cache.get()
    register727()
    PacketHandlers.loadHandlersFromPackage("org.darkan.world.server.packet")
    val worldServer = WorldServer()
    logInfo("Starting world server on port ${EnvVars.worldPort}...")
    worldServer.start().join()
}


