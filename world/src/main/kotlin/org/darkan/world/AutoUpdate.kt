package org.darkan.world

import com.rs.game.World
import com.rs.utils.Ticks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.darkan.core.EnvVars
import org.darkan.core.deploy.ArtifactWatcher
import org.darkan.core.Logger.logInfo

object AutoUpdate {
    private val watcher = ArtifactWatcher("world-server", CoroutineScope(Dispatchers.IO + SupervisorJob())) { version ->
        val minutes = EnvVars.autoUpdateCountdownMinutes
        logInfo("Starting the $minutes minute system update countdown for build $version")
        World.sendWorldMessage("<col=ff0000>A new server build is ready. The server will restart in $minutes minutes.", false)
        World.safeShutdown(Ticks.fromMinutes(minutes))
    }

    @JvmStatic
    fun start() = watcher.start()
}
