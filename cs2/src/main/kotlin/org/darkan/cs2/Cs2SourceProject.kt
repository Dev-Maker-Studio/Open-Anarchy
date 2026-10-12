package org.darkan.cs2

import java.io.File
import org.darkan.cs2.compile.Cs2CodeGen
import org.darkan.cs2.compile.Cs2Lexer
import org.darkan.cs2.compile.Cs2Parser
import world.gregs.voidps.cache.Cache

class Cs2SourceProject private constructor(
    private val context: Cs2Context,
    cache: Cache?,
    private val analyzer: Cs2Analyzer?,
    folder: File,
) {

    constructor(cache: Cache, analyzer: Cs2Analyzer, folder: File = File(DEFAULT_FOLDER)) :
        this(analyzer, cache, analyzer, folder)

    constructor(context: Cs2Context, folder: File) : this(context, null, null, folder)

    @Volatile
    var folder: File = folder
        private set

    @Volatile
    var symbols: Cs2SymbolTable = Cs2SymbolTable.EMPTY
        private set

    private val scripts = cache?.let { Cs2Cache(it) }

    init {
        reloadSymbols()
    }

    fun useFolder(directory: File): File {
        if (!directory.isDirectory) return folder
        folder = directory
        reloadSymbols()
        return folder
    }

    fun reloadSymbols() {
        symbols = try {
            if (folder.isDirectory) Cs2SymbolTable.read(folder) else Cs2SymbolTable.EMPTY
        } catch (e: Exception) {
            Cs2SymbolTable.EMPTY
        }
    }


    data class Diagnostic(
        val file: File?,
        val scriptId: Int,
        val message: String,
        val line: Int,
        val column: Int,
        val located: Boolean,
    ) {
        val location: String
            get() = "${file?.path ?: "<unknown>"}:$line:$column"

        override fun toString(): String = "$location: $message"
    }

    class Compiled(
        val scriptId: Int,
        val file: File,
        val script: Cs2Script,
        val bytes: ByteArray,
    )

    sealed class CompileResult {
        class Ok(val result: Compiled) : CompileResult()
        class Failed(val error: Diagnostic) : CompileResult()

        val compiled: Compiled? get() = (this as? Ok)?.result
        val diagnostic: Diagnostic? get() = (this as? Failed)?.error
    }


    fun fileFor(scriptId: Int): File {
        val derived = Cs2Layout.fileFor(folder, scriptId)
        if (derived.isFile) return derived
        val legacy = folder.resolve("${Cs2Layout.LEGACY_PREFIX}$scriptId.ts")
        return if (legacy.isFile) legacy else derived
    }

    fun hasSource(scriptId: Int): Boolean = fileFor(scriptId).isFile

    fun scriptIdOf(file: File): Int? = try {
        Cs2Layout.scriptIdOf(file.readText(), Cs2Layout.relativePath(folder, file))
    } catch (e: Exception) {
        null
    }

    fun isScriptFile(file: File): Boolean = Cs2Layout.isScriptFile(file)

    fun isDeclarationFile(file: File): Boolean =
        file.name == Cs2Declarations.VARS_FILE ||
            file.name == Cs2Declarations.OPCODES_FILE ||
            file.name == Cs2Declarations.GAMEVALS_FILE

    fun sourceIds(): List<Int> =
        Cs2Layout.scriptFiles(folder).mapNotNull { scriptIdOf(it) }.distinct().sorted()

    fun cachedBytes(scriptId: Int): ByteArray? = try {
        scripts?.raw(scriptId)
    } catch (e: Exception) {
        null
    }


    fun compile(scriptId: Int): CompileResult {
        val file = fileFor(scriptId)
        if (!file.isFile) {
            return CompileResult.Failed(
                Diagnostic(file, scriptId, "no source file", 1, 1, located = false),
            )
        }
        return compileFile(file)
    }

    fun compileFile(file: File): CompileResult {
        val source = try {
            file.readText()
        } catch (e: Exception) {
            return CompileResult.Failed(
                Diagnostic(file, -1, "cannot read: ${e.message}", 1, 1, located = false),
            )
        }
        val relative = Cs2Layout.relativePath(folder, file)
        val headerId = Cs2Layout.scriptIdOf(source, relative) ?: -1
        symbols.refusal()?.let {
            return CompileResult.Failed(Diagnostic(file, headerId, it, 1, 1, located = false))
        }
        return try {
            val function = Cs2Parser(source, context::paramIsString, symbols, relative, context::returnTypes).parse()
            val script = Cs2CodeGen(function, context).generate()
            CompileResult.Ok(Compiled(function.scriptId, file, script, Cs2Codec.encode(script)))
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "compile failed"
            CompileResult.Failed(locate(file, headerId, source, message))
        }
    }


    data class VerifyResult(
        val scriptId: Int,
        val identical: Boolean,
        val compiledSize: Int,
        val cachedSize: Int,
        val diagnostic: Diagnostic?,
    ) {
        val summary: String
            get() = when {
                diagnostic != null -> "compile failed: ${diagnostic.message}"
                cachedSize == 0 -> "compiled $compiledSize bytes; nothing in the cache to compare against"
                identical -> "byte-identical to the cached script ($compiledSize bytes)"
                else -> "DIFFERS from the cached script ($compiledSize bytes vs $cachedSize)"
            }
    }

    fun verify(scriptId: Int): VerifyResult {
        val result = compile(scriptId)
        val compiled = result.compiled
            ?: return VerifyResult(scriptId, false, 0, 0, result.diagnostic)
        val original = cachedBytes(compiled.scriptId)
        return VerifyResult(
            scriptId = compiled.scriptId,
            identical = original != null && compiled.bytes.contentEquals(original),
            compiledSize = compiled.bytes.size,
            cachedSize = original?.size ?: 0,
            diagnostic = null,
        )
    }


    data class ExportResult(val file: File?, val message: String) {
        val ok: Boolean get() = file != null
    }

    fun exportSource(scriptId: Int, overwrite: Boolean = false): ExportResult {
        val target = fileFor(scriptId)
        if (target.isFile && !overwrite) {
            return ExportResult(target, "already present at ${target.path}")
        }
        val analysis = analyzer
            ?: return ExportResult(null, "this project has no cache to decompile out of")
        val script = analysis.cachedScript(scriptId)
            ?: return ExportResult(null, "no clientscript $scriptId in the cache")
        val source = try {
            emit(analysis, script, scriptId, faithful = false)
        } catch (e: Exception) {
            try {
                emit(analysis, script, scriptId, faithful = true)
            } catch (fallback: Exception) {
                return ExportResult(null, "decompile failed: ${fallback.message}")
            }
        }
        return try {
            target.parentFile?.mkdirs()
            target.writeText(source)
            reloadSymbols()
            ExportResult(target, "wrote ${target.path}")
        } catch (e: Exception) {
            ExportResult(null, "cannot write ${target.path}: ${e.message}")
        }
    }

    private fun emit(analysis: Cs2Analyzer, script: Cs2Script, scriptId: Int, faithful: Boolean): String =
        Cs2Emitter(analysis.types().apply(Cs2Structurer(script, scriptId, analysis, faithful).structure())).emit()

    fun exportDeclarations(): ExportResult = try {
        folder.mkdirs()
        folder.resolve(Cs2Declarations.OPCODES_FILE)
            .writeText(Cs2Declarations.generate(stringParams = analyzer?.stringParams() ?: emptySet()))
        folder.resolve(Cs2Declarations.GAMEVALS_FILE).writeText(Cs2Declarations.gamevals())
        folder.resolve("tsconfig.json").writeText(Cs2Declarations.tsconfig())
        ExportResult(
            folder,
            "wrote ${Cs2Declarations.OPCODES_FILE}, ${Cs2Declarations.GAMEVALS_FILE} " +
                "and tsconfig.json to ${folder.path}",
        )
    } catch (e: Exception) {
        ExportResult(null, "cannot write declarations: ${e.message}")
    }


    private fun locate(file: File, scriptId: Int, source: String, message: String): Diagnostic {
        val lines = source.lines()
        val reported = LINE_IN_MESSAGE.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val quoted = QUOTED.find(message)?.groupValues?.get(1)

        var line = reported
        if (line == null && quoted != null) {
            line = try {
                Cs2Lexer(source).tokenise().firstOrNull { it.text == quoted }?.line
            } catch (e: Exception) {
                null
            }
        }
        if (line == null && quoted != null) {
            val index = lines.indexOfFirst { it.contains(quoted) }
            if (index >= 0) line = index + 1
        }

        val resolved = (line ?: 1).coerceIn(1, maxOf(1, lines.size))
        val text = lines.getOrNull(resolved - 1) ?: ""
        val column = when {
            quoted != null && text.contains(quoted) -> text.indexOf(quoted) + 1
            else -> text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 1 else it + 1 }
        }
        return Diagnostic(file, scriptId, message, resolved, column, located = line != null)
    }

    companion object {
        const val DEFAULT_FOLDER = "cs2-dump"

        private val LINE_IN_MESSAGE = Regex("""(?:at|on) line (\d+)""")
        private val QUOTED = Regex("""'([^']+)'""")
    }
}
