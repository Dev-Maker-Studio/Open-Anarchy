package org.darkan.core.cache

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object WindowTree {

    private const val SUFFIX = "-tree"
    private const val GIT = ".git"

    fun prepare(base: File): File {
        if (GeneratedWorld.windowX == WorldWindow.CENTER_SECTOR_X && GeneratedWorld.windowY == WorldWindow.CENTER_SECTOR_Y) return base
        val target = File(EnvVars.cacheBuildPath + SUFFIX)
        val source = base.toPath()
        val destination = target.toPath()
        var linked = 0
        Files.walk(source).use { paths ->
            for (path in paths) {
                if (path.any { it.toString() == GIT }) continue
                val link = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(link)
                } else if (!Files.exists(link)) {
                    link(path, link)
                    linked++
                }
            }
        }
        logInfo("Window tree ${target.path}: linked $linked files from ${base.path}")
        return target
    }

    private fun link(path: Path, link: Path) {
        Files.createLink(link, path.toRealPath())
    }
}
