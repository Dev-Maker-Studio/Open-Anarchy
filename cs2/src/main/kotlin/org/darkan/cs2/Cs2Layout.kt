package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import java.io.File

object Cs2Layout {

    fun pathOf(scriptId: Int): String =
        Cs2Names.scriptPaths()[scriptId] ?: "${Cs2Names.UNCATEGORISED}/${headerOf(scriptId)}.ts"

    fun fileFor(folder: File, scriptId: Int): File = File(folder, pathOf(scriptId))

    fun headerOf(scriptId: Int): String =
        Gameval.rawName(Gameval.CLIENTSCRIPT, scriptId)
            ?: GamevalCatalog.placeholder(Gameval.CLIENTSCRIPT, scriptId.toString())

    fun referencePrefix(scriptId: Int): String =
        "../".repeat(pathOf(scriptId).count { it == '/' })


    fun scriptIdOf(source: String, relativePath: String?): Int? {
        NUMERIC_HEADER.find(source)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val header = gamevalHeader(source)
        if (header != null) return Cs2Names.scriptIdOfRawName(header)
        return relativePath?.let { Cs2Names.scriptIdOfPath(it) }
    }

    fun gamevalHeader(source: String): String? = GAMEVAL_HEADER.find(source)?.groupValues?.get(1)

    fun relativePath(folder: File, file: File): String? {
        val root = folder.absoluteFile.normalize().path
        val path = file.absoluteFile.normalize().path
        if (!path.startsWith("$root${File.separatorChar}")) return null
        return path.substring(root.length + 1).replace(File.separatorChar, '/')
    }

    fun isScriptFile(file: File): Boolean =
        file.name.endsWith(".ts") && !file.name.endsWith(".d.ts")

    fun scriptFiles(folder: File): List<File> {
        if (!folder.isDirectory) return emptyList()
        val out = ArrayList<File>(8192)
        fun walk(directory: File, depth: Int) {
            val entries = directory.listFiles() ?: return
            for (entry in entries.sortedBy { it.name }) {
                when {
                    entry.isDirectory -> if (depth < MAX_DEPTH) walk(entry, depth + 1)
                    isScriptFile(entry) -> out.add(entry)
                }
            }
        }
        walk(folder, 0)
        return out
    }

    fun legacyFiles(folder: File): List<File> =
        folder.listFiles().orEmpty().filter {
            it.isFile && LEGACY_NAME.matches(it.name)
        }.sortedBy { it.name }

    fun move(from: File, to: File): Boolean {
        if (from.absoluteFile.normalize() == to.absoluteFile.normalize()) return false
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            to.writeBytes(from.readBytes())
            from.delete()
        }
        val parent = from.parentFile
        if (parent != null && parent.isDirectory && parent.list().orEmpty().isEmpty()) parent.delete()
        return true
    }

    private const val MAX_DEPTH = 4

    const val LEGACY_PREFIX = "clientscript-"

    private val LEGACY_NAME = Regex("""^clientscript-\d+\.ts$""")

    private val NUMERIC_HEADER = Regex("""^// clientscript (\d+)$""", RegexOption.MULTILINE)

    private val GAMEVAL_HEADER = Regex("""^// (\[[^\]\n]*\]|\w+)$""", RegexOption.MULTILINE)
}
