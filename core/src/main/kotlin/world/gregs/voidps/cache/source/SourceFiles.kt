package world.gregs.voidps.cache.source

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object SourceFiles {

    const val PRISTINE_EXTENSION = ".pristine"

    private val hex = "0123456789abcdef".toCharArray()

    fun isPristine(name: String): Boolean = name.endsWith(PRISTINE_EXTENSION)

    fun pristine(path: String): String = "$path$PRISTINE_EXTENSION"

    fun pristine(file: Path): Path = file.resolveSibling("${file.fileName}$PRISTINE_EXTENSION")

    fun guarded(path: String): String? =
        if (isPristine(path)) path.dropLast(PRISTINE_EXTENSION.length) else null

    fun guarded(file: Path): Path? {
        val name = guarded(file.fileName.toString()) ?: return null
        return file.resolveSibling(name)
    }

    fun write(path: Path, bytes: ByteArray): Boolean {
        if (unchanged(path, bytes)) {
            return false
        }
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(path, bytes)
        return true
    }

    private fun unchanged(path: Path, bytes: ByteArray): Boolean {
        try {
            if (Files.size(path) != bytes.size.toLong()) {
                return false
            }
        } catch (e: IOException) {
            return false
        }
        return Files.readAllBytes(path).contentEquals(bytes)
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    fun digest(value: String, bytes: Int = 8): String =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).copyOf(bytes))

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            out[index * 2] = hex[value shr 4]
            out[index * 2 + 1] = hex[value and 0xf]
        }
        return String(out)
    }

    fun delete(path: Path, stop: Path) {
        Files.deleteIfExists(path)
        var parent = path.parent
        while (parent != null && parent != stop && parent.startsWith(stop)) {
            val empty = Files.list(parent).use { !it.findAny().isPresent }
            if (!empty) {
                return
            }
            Files.deleteIfExists(parent)
            parent = parent.parent
        }
    }
}
