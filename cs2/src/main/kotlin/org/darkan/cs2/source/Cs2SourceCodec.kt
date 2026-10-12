package org.darkan.cs2.source

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.darkan.cs2.Cs2CompileException
import org.darkan.cs2.Cs2Compiler
import org.darkan.cs2.Cs2Corpus
import org.darkan.cs2.Cs2Declarations
import org.darkan.cs2.Cs2Layout
import org.darkan.cs2.Cs2Names
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive

object Cs2SourceCodec : SourceCodec {

    const val EXTENSION = ".ts"

    private const val FILE = 0

    @Volatile
    var cache: () -> Cache = { Cache.get() }

    override val id: String
        get() = "cs2-source"

    override val functional: Boolean
        get() = true

    override fun exact(archive: Int): Boolean = true

    override fun fileIdsFromMetadata(archive: Int): Boolean = true


    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Clientscript ${archive.archive} has ${archive.files.size} files; index " +
                "${Index.CLIENT_SCRIPTS} holds one script per archive."
        }
        val state = state(directory)
        val bytes = archive.files[FILE]
        val decompiled = state.corpus.decompileScript(archive.archive, bytes, state.types)
            ?: throw IOException("Clientscript ${archive.archive} could not be decompiled from its ${bytes.size} bytes.")
        val path = Cs2Layout.pathOf(archive.archive)
        return UnpackedArchive(listOf(SourceFile(path, decompiled.source.toByteArray(Charsets.UTF_8))))
    }

    override fun unpackIndex(directory: Path, index: Int, archives: IntArray): List<SourceFile> {
        if (archives.isEmpty()) {
            return emptyList()
        }
        return state(directory).projectFiles.map { (name, body) ->
            SourceFile(name, body.toByteArray(Charsets.UTF_8))
        }
    }


    override fun packIndex(directory: Path, index: Int, archives: IntArray, changedIndexFiles: List<String>) {
        state(directory).invalidateCompiler()
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = sourceFile(directory, archive)
        if (!Files.isRegularFile(file)) {
            throw IOException("Clientscript $archive has no source at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1 && ids[FILE] == FILE) {
            "Clientscript $archive is one file on disk but its metadata lists ${ids.joinToString()}."
        }

        val handle = file.toFile()
        val compiler = state(directory).compiler
        val declared = try {
            compiler.scriptIdOf(handle)
        } catch (e: Cs2CompileException) {
            throw IOException("${e.diagnostic}", e)
        }
        if (declared != archive) {
            throw IOException(
                "${file.toAbsolutePath()} says it is clientscript $declared but sits where " +
                    "clientscript $archive belongs; a rename moves the file and the catalog " +
                    "together, so run `cs2 apply-names` or fix the file's header."
            )
        }
        val bytes = try {
            compiler.compile(handle)
        } catch (e: Cs2CompileException) {
            throw IOException("${e.diagnostic}", e)
        }
        return PackedArchive(bytes, ids)
    }


    override fun archiveOf(path: String): Int? {
        if (!path.endsWith(EXTENSION) || path.endsWith(DECLARATION_EXTENSION)) {
            return null
        }
        return Cs2Names.scriptIdOfPath(path) ?: legacyId(path)
    }

    override fun archiveOf(directory: Path, path: String): Int? {
        if (SourceFiles.isPristine(path)) {
            return null
        }
        val file = directory.resolve(path)
        if (Files.isRegularFile(file)) {
            Cs2Layout.scriptIdOf(Files.readString(file), path)?.let { return it }
        }
        return archiveOf(path)
    }

    override fun owner(directory: Path, path: String): Owner {
        if (path in PROJECT_FILES) {
            return Owner.Index
        }
        val archive = archiveOf(directory, path)
        return if (archive == null) Owner.None else Owner.Archive(archive)
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = sourceFile(directory, archive)
        return if (Files.exists(file)) listOf(file) else emptyList()
    }

    private fun sourceFile(directory: Path, archive: Int): Path {
        val derived = directory.resolve(Cs2Layout.pathOf(archive))
        if (Files.isRegularFile(derived)) {
            return derived
        }
        val legacy = directory.resolve("${Cs2Layout.LEGACY_PREFIX}$archive$EXTENSION")
        return if (Files.isRegularFile(legacy)) legacy else derived
    }

    private fun legacyId(path: String): Int? {
        if (path.contains('/') || !path.startsWith(Cs2Layout.LEGACY_PREFIX)) {
            return null
        }
        return path.substring(Cs2Layout.LEGACY_PREFIX.length).dropLast(EXTENSION.length).toIntOrNull()
    }


    private fun state(directory: Path): State =
        states.computeIfAbsent(directory.toAbsolutePath().normalize().toString()) {
            State(directory.toAbsolutePath().normalize())
        }

    private val states = ConcurrentHashMap<String, State>()

    fun reset() {
        states.clear()
        cache = { Cache.get() }
    }

    private class State(val directory: Path) {

        private val resolved: Cache by lazy { cache() }

        val corpus: Cs2Corpus by lazy { Cs2Corpus(resolved) }

        val types by lazy { corpus.types() }

        val projectFiles: Map<String, String> by lazy {
            val decompiled = corpus.decompile()
            corpus.projectFiles(decompiled, types)
        }

        val compiler: Cs2Compiler
            get() = current ?: synchronized(this) {
                current ?: Cs2Compiler.fromSources(directory.toFile()).also { current = it }
            }

        @Volatile
        private var current: Cs2Compiler? = null

        fun invalidateCompiler() {
            synchronized(this) { current = null }
        }
    }

    private val PROJECT_FILES: Set<String> = setOf(
        Cs2Declarations.OPCODES_FILE,
        Cs2Declarations.GAMEVALS_FILE,
        Cs2Declarations.VARS_FILE,
        Cs2Corpus.TSCONFIG_FILE,
        Cs2Corpus.DEAD_CODE_FILE
    )

    private const val DECLARATION_EXTENSION = ".d$EXTENSION"
}
