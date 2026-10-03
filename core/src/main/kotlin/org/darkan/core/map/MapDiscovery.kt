package org.darkan.core.map

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object MapDiscovery {

    private val discovered = ConcurrentHashMap.newKeySet<Int>()
    private val file = File(EnvVars.discoveredMapsPath)

    init {
        if (file.isFile) {
            file.forEachLine { line -> line.trim().toIntOrNull()?.let(discovered::add) }
        }
        logInfo("Map discovery: ${discovered.size} squares already explored")
    }

    fun isDiscovered(regionId: Int): Boolean = regionId in discovered

    fun discover(regionIds: IntArray) {
        val added = regionIds.filter(discovered::add)
        if (added.isEmpty()) {
            return
        }
        synchronized(file) {
            file.parentFile?.mkdirs()
            file.appendText(added.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}
