package org.darkan.lobby.social

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import org.darkan.core.map.WorldWindow
import org.darkan.lobby.Lobby
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object WindowOrchestrator {

    enum class Status { DISABLED, ONLINE, STARTING, STARTED, AT_CAPACITY, FAILED }

    private val running = ConcurrentHashMap<Int, Process>()

    fun ensure(windowX: Int, windowY: Int): Status {
        if (!EnvVars.windowOrchestrator) return Status.DISABLED
        val worldId = WorldWindow.worldId(windowX, windowY)
        val online = Lobby.worldList.get(worldId)
        if (online != null && !online.offline) return Status.ONLINE
        synchronized(running) {
            running[worldId]?.let { if (it.isAlive) return Status.STARTING else running.remove(worldId) }
            running.values.removeIf { !it.isAlive }
            if (running.size >= EnvVars.windowMaxRunning) {
                logWarn("WindowOrchestrator: not starting window $windowX,$windowY, ${running.size} windows are already running")
                return Status.AT_CAPACITY
            }
            return start(windowX, windowY, worldId)
        }
    }

    private fun start(windowX: Int, windowY: Int, worldId: Int): Status = try {
        val gateway = "ws://localhost:${EnvVars.lobbyApiPort}/social/ws"
        val log = File("logs/window-${windowX}_$windowY.log")
        log.parentFile?.mkdirs()
        val process = ProcessBuilder("bash", EnvVars.windowScript, windowX.toString(), windowY.toString(), gateway, "foreground")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        running[worldId] = process
        logInfo("WindowOrchestrator: starting window $windowX,$windowY as world $worldId")
        Status.STARTED
    } catch (e: Exception) {
        logWarn("WindowOrchestrator: could not start window $windowX,$windowY", e)
        Status.FAILED
    }
}
