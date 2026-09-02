package org.darkan.cs2

import java.io.File
import world.gregs.voidps.cache.Cache

/**
 * Compiles one folder of TypeScript clientscripts to bytecode, headlessly.
 *
 * This is the entry point for code outside the tools CLI - the cache source tree packer, whose
 * index 12 is stored as exactly the folder `cs2 decompile-all` writes (see `docs/cache-source.md`).
 * Nothing here touches a window, a GL context or ImGui; the whole `:cs2` module is headless by
 * construction and depends only on `:core`.
 *
 * ```kotlin
 * val compiler = Cs2Compiler(Cache.get(), File(tree, "clientscripts"))
 * val bytes = compiler.compile(File(tree, "clientscripts/proc/quicksort.ts"))
 * val id = compiler.scriptIdOf(File(tree, "clientscripts/proc/quicksort.ts"))
 * ```
 *
 * ### Cost, and what is shared
 *
 * Compiling anything at all needs the whole corpus first: a script's return signature is only
 * decidable across calls, and a `*_PARAM` opcode pushes an int or a string depending on the param
 * type, neither of which the source states. [Cs2Analyzer.analyse] does that work over all 6567
 * cached scripts and takes seconds, so it runs **once**, lazily, on first use, and is then shared
 * by every compile. [compile] itself allocates a fresh [Cs2Parser][org.darkan.cs2.compile.Cs2Parser]
 * and [Cs2CodeGen][org.darkan.cs2.compile.Cs2CodeGen] per call and holds no state between calls,
 * so a pack can compile the folder's files in parallel: the analysis, the gameval derivations
 * ([Cs2Names]) and the folder's symbol table are all read-only once built, and each is either
 * concurrent or guarded internally.
 *
 * Compiling one file never requires the rest of the folder to compile. What it does require is the
 * folder's *declarations* - `vars.d.ts` and `gamevals.d.ts` bind renamed identifiers to ids - which
 * are read once into [Cs2SourceProject.symbols]. Call [reloadSources] after editing those, or after
 * adding or renaming script files, and the next compile picks the change up.
 *
 * ### The lower-level pieces
 *
 * The facade is deliberately thin. Where more is needed, the pipeline is public:
 *
 *  - [Cs2Analyzer] `(cache).analyse()` - the corpus analysis, and the `Cs2Context` everything
 *    downstream resolves calls through;
 *  - [Cs2SourceProject] `(cache, analyzer, folder)` - the folder itself: paths ([Cs2Layout]),
 *    the symbol table ([Cs2SymbolTable]), per-file [Cs2SourceProject.compileFile] with a
 *    [Cs2SourceProject.Diagnostic] carrying `file:line:col`, [Cs2SourceProject.verify] against the
 *    cache and [Cs2SourceProject.exportSource] the other way;
 *  - [Cs2HotReload.sweepFolder] - the whole-folder sweep `cs2 sources` / `cs2 verify-sources` run,
 *    which compiles every source file and reports how many still match the cache byte for byte;
 *  - [Cs2Codec] - the raw index 12 file format, and [verifyCodecRoundTrip] over the whole index;
 *  - [Cs2Structurer] / [Cs2Emitter] - the other direction, bytecode to TypeScript.
 *
 * @property folder the folder of `.ts` sources this compiler reads, as handed in.
 */
class Cs2Compiler(private val cache: Cache, val folder: File) {

    /**
     * The corpus analysis, done once on first use.
     *
     * `by lazy` rather than an `init` block on purpose: constructing a compiler for a tree that
     * turns out to need no rebuild should cost nothing.
     */
    private val analyzer: Cs2Analyzer by lazy {
        Cs2Analyzer(cache).also { it.analyse() }
    }

    private val project: Cs2SourceProject by lazy {
        Cs2SourceProject(cache, analyzer, folder)
    }

    /**
     * Compiles one source file to the bytes index 12 holds.
     *
     * Throws [Cs2CompileException] when the file does not compile, or when it does not say which
     * script it is; the message is `file:line:col: what went wrong`, positioned as well as the
     * lexer and parser allow (see [Cs2SourceProject.Diagnostic]).
     */
    fun compile(file: File): ByteArray = when (val result = project.compileFile(file)) {
        is Cs2SourceProject.CompileResult.Ok -> result.result.bytes
        is Cs2SourceProject.CompileResult.Failed -> throw Cs2CompileException(result.error)
    }

    /**
     * Which script [file] is: its `// [proc,quicksort]` header run through
     * `gamevals/clientscript.json`, falling back to the path it sits at when it has no header at
     * all ([Cs2Layout.scriptIdOf]).
     *
     * Throws [Cs2CompileException] rather than returning a sentinel, because every caller of this
     * has to have an id: a file that cannot say which archive it belongs to has no safe default,
     * and picking one would silently write a script into the wrong slot. Use
     * [Cs2SourceProject.scriptIdOf] where a null is genuinely wanted.
     */
    fun scriptIdOf(file: File): Int = project.scriptIdOf(file)
        ?: throw Cs2CompileException(
            Cs2SourceProject.Diagnostic(
                file = file,
                scriptId = -1,
                message = "no clientscript header, and the path names no script in gamevals/clientscript.json",
                line = 1,
                column = 1,
                located = false,
            ),
        )

    /**
     * Where [scriptId]'s source lives, or null when the folder has no file for it.
     *
     * The path is the catalog's: `<category>/<name>.ts` from the script's gameval name. A folder
     * written by an older build still holding it at the flat `clientscript-<id>.ts` path is
     * accepted where that is what exists.
     */
    fun sourceFileOf(scriptId: Int): File? = project.fileFor(scriptId).takeIf { it.isFile }

    /** Every script id the folder has a source file for, ascending. */
    fun sourceIds(): List<Int> = project.sourceIds()

    /**
     * Re-reads `vars.d.ts`, `gamevals.d.ts` and the per-script name bindings.
     *
     * Needed after those files change on disk; the bindings are folder-scoped, so a compile using
     * a stale table would resolve a renamed identifier to the old id. Not thread-safe against
     * concurrent [compile] calls - reload between sweeps, not during one.
     */
    fun reloadSources() = project.reloadSymbols()
}

/** A source file that did not compile, with somewhere to jump to. */
class Cs2CompileException(val diagnostic: Cs2SourceProject.Diagnostic) :
    RuntimeException(diagnostic.toString())
