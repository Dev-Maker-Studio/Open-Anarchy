package org.darkan.cs2.source

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.darkan.core.cache.CacheSource
import org.darkan.cs2.Cs2CompileException
import org.darkan.cs2.Cs2Compiler
import org.darkan.cs2.Cs2Corpus
import org.darkan.cs2.Cs2Layout
import org.darkan.cs2.Cs2Names
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive

/**
 * Index 12 as TypeScript: the cache source tree's clientscripts codec.
 *
 * The `clientscripts/` directory of a source tree is exactly the folder `cs2 decompile-all`
 * writes and the CS2 editor opens - `<category>/<name>.ts` per script, named by its gameval name
 * through `gamevals/clientscript.json`, plus the project files a TypeScript editor needs
 * (`cs2.d.ts`, `gamevals.d.ts`, `vars.d.ts`, `tsconfig.json`) and the `dead-code.md` report.
 * Editing a script therefore means editing the TypeScript, and the server compiles it back to
 * bytecode when it rebuilds the cache; see `docs/cache-source.md` and `docs/cs2-editor.md`.
 *
 * ### Parity, and the pristine fallback
 *
 * The decompiler normalises - it drops unreachable code, folds initialisers into declarations and
 * lays branches out canonically - so 1,066 of the 6,567 scripts compile back to *equivalent* but
 * different bytes. [unpack] compiles every script it decompiles straight back and compares:
 * where the bytes differ it writes the original ones beside the source as `<name>.ts.pristine`
 * and records the source's SHA-256 under `pristine` in `index.json`. [pack] emits those original
 * bytes while the source still hashes to the recorded value, and compiles the source once it does
 * not, which is what lets a normalising compiler live inside the tree's byte-for-byte parity
 * guarantee. `cache status` reports the count, and it can only shrink.
 *
 * [exact] is therefore `true`: this codec assembles its own fallback while it unpacks, so packing
 * what it wrote *does* reproduce the group. Saying `false` would only make the unpacker compile
 * all 6,567 scripts a second time to rediscover the same answer.
 *
 * ### Where the cache comes from
 *
 * Compiling *anything* needs the whole corpus analysed first - a script's return signature is only
 * decidable across calls - so both directions need a [Cache] to read index 12 and the param
 * definitions out of, and [SourceCodec] is not handed one. [cache] is that seam: it defaults to
 * [Cache.get] and can be pointed elsewhere by a caller that knows better. See the class comment on
 * [state] for the one configuration that default cannot serve.
 */
object Cs2SourceCodec : SourceCodec {

    /** `.ts` - what a script's source is stored as. */
    const val EXTENSION = ".ts"

    /**
     * The original bytes kept beside a source the compiler cannot reproduce, `<name>.ts.pristine`.
     *
     * The name is the framework's, not this codec's: `cache status` finds a sidecar by this
     * extension and the file it guards by dropping it.
     */
    const val PRISTINE_EXTENSION = ".pristine"

    /** Index 12's archives hold one file each, and it is always file 0. */
    private const val FILE = 0

    /**
     * Where the corpus is analysed from. Replaceable so a caller can hand in a cache of its own;
     * see the note on [state] about the one case where the default cannot work.
     */
    @Volatile
    var cache: () -> Cache = { defaultCache() }

    override val id: String
        get() = "cs2-source"

    /**
     * True: see the class comment. The pristine fallback is assembled inside [unpack], so what
     * this codec leaves on disk always packs back to the bytes it came from.
     */
    override fun exact(archive: Int): Boolean = true

    /** A script is one file and its name carries the script, not the file id. */
    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    // ------------------------------------------------------------------------- unpack

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
        val source = decompiled.source.toByteArray(Charsets.UTF_8)

        val files = ArrayList<SourceFile>(3)
        files.add(SourceFile(path, source))
        // The declarations and the report are a property of the whole corpus, not of any one
        // script, and SourceCodec has no per-index hook to write them from - so the first archive
        // to get here carries them. They are added to exactly one archive's file list because the
        // unpacker deletes everything under the directory that no archive claimed.
        if (state.claimProjectFiles(archive.archive)) {
            for ((name, body) in state.projectFiles) {
                files.add(SourceFile(name, body.toByteArray(Charsets.UTF_8)))
            }
        }

        // Compiled through the corpus rather than the folder: the folder is still being written,
        // and freshly decompiled source carries the catalogs' own names and no renames, so there
        // is nothing in the folder's declarations for it to resolve through yet.
        val rebuilt = try {
            state.corpus.compileSource(decompiled.source, path)
        } catch (e: Exception) {
            null
        }
        if (rebuilt != null && rebuilt.contentEquals(bytes)) {
            return UnpackedArchive(files)
        }
        files.add(SourceFile("$path$PRISTINE_EXTENSION", bytes))
        return UnpackedArchive(files, mapOf(FILE to sha256(source)))
    }

    // --------------------------------------------------------------------------- pack

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = sourceFile(directory, archive)
        if (!Files.isRegularFile(file)) {
            throw IOException("Clientscript $archive has no source at ${file.toAbsolutePath()}.")
        }
        val source = Files.readAllBytes(file)
        val ids = metadata.fileIds()
        check(ids.size == 1 && ids[FILE] == FILE) {
            "Clientscript $archive is one file on disk but its metadata lists ${ids.joinToString()}."
        }

        // An unedited script the compiler cannot reproduce is served from the bytes kept beside
        // it. The guard is the whole point: the moment the source is edited it stops matching and
        // the edit is compiled instead.
        val guard = metadata.pristine[FILE]
        if (guard != null && guard == sha256(source)) {
            val sidecar = sidecarOf(file)
            if (!Files.isRegularFile(sidecar)) {
                throw IOException(
                    "Clientscript $archive is recorded as pristine in index.json but " +
                        "${sidecar.toAbsolutePath()} is missing, so its original bytes are gone; " +
                        "restore the sidecar or drop the archive's \"pristine\" entry to compile " +
                        "${file.fileName} instead."
                )
            }
            return PackedArchive(Files.readAllBytes(sidecar), ids)
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

    // -------------------------------------------------------------------------- paths

    /**
     * Which script a tree path is, resolved through the catalog.
     *
     * [SourceCodec.archiveOf] is given the path and nothing else, so the header inside the file -
     * which is what actually binds a source to a script - cannot be read here; the path is the
     * catalog's own (`<category>/<name>.ts` from `gamevals/clientscript.json`) and a rename moves
     * the file and the catalog entry together, so the two agree for anything the tools wrote.
     * [archiveOf] with a directory is the version that reads the header, for a caller that has one.
     *
     * The project files belong to no archive and answer null, as does anything else - an editor's
     * scratch file, a stray `.d.ts` - which an incremental build reports as unclaimed rather than
     * repacking something at random.
     */
    override fun archiveOf(path: String): Int? {
        if (!path.endsWith(EXTENSION) || path.endsWith(DECLARATION_EXTENSION)) {
            return null
        }
        return Cs2Names.scriptIdOfPath(path) ?: legacyId(path)
    }

    /**
     * Which script the file at [path] under [directory] is, header first.
     *
     * The header survives a file being copied or moved, so it is the binding [Cs2Layout] trusts;
     * this is [archiveOf] with the one thing the interface cannot pass it.
     */
    fun archiveOf(directory: Path, path: String): Int? {
        val file = directory.resolve(path)
        if (Files.isRegularFile(file)) {
            Cs2Layout.scriptIdOf(Files.readString(file), path)?.let { return it }
        }
        return archiveOf(path)
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = sourceFile(directory, archive)
        if (!Files.exists(file)) {
            return emptyList()
        }
        val sidecar = sidecarOf(file)
        return if (Files.exists(sidecar)) listOf(file, sidecar) else listOf(file)
    }

    /**
     * Where [archive]'s source is: the catalog's path, or the numbered flat path an older dump
     * wrote where that is what is actually there.
     */
    private fun sourceFile(directory: Path, archive: Int): Path {
        val derived = directory.resolve(Cs2Layout.pathOf(archive))
        if (Files.isRegularFile(derived)) {
            return derived
        }
        val legacy = directory.resolve("${Cs2Layout.LEGACY_PREFIX}$archive$EXTENSION")
        return if (Files.isRegularFile(legacy)) legacy else derived
    }

    /** `<name>.ts.pristine` beside `<name>.ts`. */
    private fun sidecarOf(file: Path): Path =
        file.resolveSibling("${file.fileName}$PRISTINE_EXTENSION")

    private fun legacyId(path: String): Int? {
        if (path.contains('/') || !path.startsWith(Cs2Layout.LEGACY_PREFIX)) {
            return null
        }
        return path.substring(Cs2Layout.LEGACY_PREFIX.length).dropLast(EXTENSION.length).toIntOrNull()
    }

    // -------------------------------------------------------------------- shared state

    /**
     * The corpus analysis and the folder's compiler, one set per directory.
     *
     * Both are lazy and both are shared: analysing the corpus takes seconds and compiling one
     * file takes under a millisecond, so a build that repacks three scripts must not pay for the
     * analysis three times, and a build that repacks nothing must not pay for it at all.
     *
     * **The one configuration this cannot serve.** With `CACHE_SOURCE_PATH` set the server builds
     * its cache *from* the tree, and [Cache.get] is that build - so a pack that reached for it
     * would be waiting for the build it is part of. The corpus has to come from somewhere that
     * already exists, which means either a cache handed in through [cache] or, properly, an index
     * 12 context derived from the tree's own sources. Until that exists, a pack in that
     * configuration says so rather than hanging.
     */
    private fun state(directory: Path): State =
        states.computeIfAbsent(directory.toAbsolutePath().normalize().toString()) {
            State(directory.toAbsolutePath().normalize())
        }

    private val states = ConcurrentHashMap<String, State>()

    /**
     * Forget every directory's analysis and put [cache] back to its default.
     *
     * For tests, and for a tool that swapped the cache underneath a folder: the analysis is keyed
     * by directory and would otherwise still be the old cache's.
     */
    fun reset() {
        states.clear()
        cache = { defaultCache() }
    }

    private class State(val directory: Path) {

        private val resolved: Cache by lazy { cache() }

        val corpus: Cs2Corpus by lazy { Cs2Corpus(resolved) }

        val types by lazy { corpus.types() }

        /**
         * The folder's own compiler: reads its declarations, so it honours renames.
         *
         * Handed the corpus's analyzer rather than building its own, so a process that unpacks
         * and packs the same tree - the parity check, `cache unpack` followed by `cache verify` -
         * analyses the corpus once instead of twice.
         */
        val compiler: Cs2Compiler by lazy {
            Cs2Compiler(resolved, directory.toFile()) { corpus.analyzer }
        }

        /**
         * The whole-corpus decompile, done once, for the files no single script can produce.
         *
         * The pass is what fills in `vars.d.ts`: a variable is only known to be used because some
         * script uses it, so the file is a function of all 6,567 of them.
         */
        val projectFiles: Map<String, String> by lazy {
            val decompiled = corpus.decompile()
            corpus.projectFiles(decompiled, types)
        }

        private val claimant = AtomicInteger(UNCLAIMED)

        /**
         * True for the one archive that carries the project files out of [unpack].
         *
         * Which archive gets there first is a race, but it *stays* that archive: a second unpack
         * of the same directory in the same process would otherwise emit them from nobody, and
         * the unpacker deletes everything no archive claimed.
         */
        fun claimProjectFiles(archive: Int): Boolean =
            claimant.compareAndSet(UNCLAIMED, archive) || claimant.get() == archive
    }

    /**
     * The cache the corpus is analysed from when nothing was handed in.
     *
     * [Cache.get] is right for every tool and for a server serving a prepacked cache. It is not
     * available while a server is building its cache out of a tree, because that build is what
     * would produce it, so that case is named rather than deadlocked on.
     */
    private fun defaultCache(): Cache {
        if (CacheSource.enabled && CacheSource.lastBuild == null) {
            throw IllegalStateException(
                "Cannot compile clientscripts while the cache is still being built from " +
                    "CACHE_SOURCE_PATH: the corpus analysis every compile needs would read the " +
                    "cache this build is producing. Point Cs2SourceCodec.cache at a cache that " +
                    "already exists - a prepacked one, or the previous build - before building a " +
                    "tree whose clientscripts have changed."
            )
        }
        return Cache.get()
    }

    /** No archive has claimed the project files yet; -1 is not an archive id. */
    private const val UNCLAIMED = -1

    /** A `.d.ts` is a declaration file, never a script; [Cs2Layout] draws the same line. */
    private const val DECLARATION_EXTENSION = ".d$EXTENSION"

    private val hex = "0123456789abcdef".toCharArray()

    /** SHA-256 as lower case hex, the form `index.json` records a pristine guard in. */
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xff
            out[index * 2] = hex[value shr 4]
            out[index * 2 + 1] = hex[value and 0xf]
        }
        return String(out)
    }
}
