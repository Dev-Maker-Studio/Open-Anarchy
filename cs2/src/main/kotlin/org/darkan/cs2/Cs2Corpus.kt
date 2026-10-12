package org.darkan.cs2

import java.io.File
import org.darkan.cs2.ir.AndAll
import org.darkan.cs2.ir.ArrayAssign
import org.darkan.cs2.ir.ArrayDefine
import org.darkan.cs2.ir.ArrayRef
import org.darkan.cs2.ir.Binary
import org.darkan.cs2.ir.Callback
import org.darkan.cs2.ir.Compare
import org.darkan.cs2.ir.Condition
import org.darkan.cs2.ir.Cs2Function
import org.darkan.cs2.ir.Expr
import org.darkan.cs2.ir.ExprStmt
import org.darkan.cs2.ir.If
import org.darkan.cs2.ir.Join
import org.darkan.cs2.ir.LocalAssign
import org.darkan.cs2.ir.MultiAssign
import org.darkan.cs2.ir.OpCall
import org.darkan.cs2.ir.OrAny
import org.darkan.cs2.ir.ParallelAssign
import org.darkan.cs2.ir.Return
import org.darkan.cs2.ir.ScriptCall
import org.darkan.cs2.ir.Stmt
import org.darkan.cs2.ir.Switch
import org.darkan.cs2.ir.VarAssign
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.While
import world.gregs.voidps.cache.Cache

class Cs2Corpus(private val cache: Cache) {

    val analyzer: Cs2Analyzer by lazy {
        Cs2Analyzer(cache).also { it.analyse() }
    }

    private val scripts = Cs2Cache(cache)

    fun types(log: (String) -> Unit = {}): Cs2Types = analyzer.types(log)

    val ids: IntArray get() = analyzer.ids

    class Decompiled(
        val scriptId: Int,
        val source: String,
        val function: Cs2Function,
        val deadCode: List<Cs2DeadCode>
    ) {
        val path: String get() = Cs2Layout.pathOf(scriptId)
    }

    class Corpus(
        val decompiled: Int,
        val failures: List<Pair<Int, String>>,
        val variables: Set<VarRef>,
        val gotos: Int,
        val scriptsWithDeadCode: Int,
        val deadCodeCounts: Map<Cs2DeadCodeKind, Int>,
        val deadCodeReport: String,
        val millis: Long
    ) {
        val deadCodeFindings: Int get() = deadCodeCounts.values.sum()
    }

    fun decompile(log: (String) -> Unit = {}, onScript: (Decompiled) -> Unit = {}): Corpus {
        val start = System.nanoTime()
        val types = types(log)
        val failures = ArrayList<Pair<Int, String>>()
        val variables = LinkedHashSet<VarRef>()
        val counts = HashMap<Cs2DeadCodeKind, Int>()
        val report = StringBuilder(DEAD_CODE_HEADER)
        var decompiled = 0
        var withDeadCode = 0
        var gotos = 0
        for (id in ids) {
            val script = analyzer.script(id) ?: continue
            val rendered = try {
                render(script, id, types, faithful = false)
            } catch (e: Exception) {
                try {
                    render(script, id, types, faithful = true)
                } catch (fallback: Exception) {
                    failures.add(id to (fallback.message ?: fallback::class.simpleName ?: "unknown"))
                    continue
                }
            }
            if (rendered.deadCode.isNotEmpty()) {
                withDeadCode++
                rendered.deadCode.forEach { counts[it.kind] = (counts[it.kind] ?: 0) + 1 }
                appendDeadCode(report, rendered)
            }
            collectVariables(rendered.function.body, variables)
            if (rendered.source.contains(GOTO)) {
                gotos++
            }
            onScript(rendered)
            decompiled++
        }
        return Corpus(
            decompiled, failures, variables, gotos, withDeadCode, counts, report.toString(),
            (System.nanoTime() - start) / 1_000_000
        )
    }

    fun decompileScript(scriptId: Int, bytes: ByteArray? = null, types: Cs2Types = types()): Decompiled? {
        val script = analyzer.script(scriptId) ?: return null
        if (bytes != null) {
            val cached = scripts.raw(scriptId)
            require(cached != null && cached.contentEquals(bytes)) {
                "Clientscript $scriptId is ${bytes.size} bytes here and " +
                    "${cached?.size ?: "absent"} in the cache this corpus was analysed from. " +
                    "Decompiling needs the whole corpus, so it has to be the same cache - see " +
                    "Cs2SourceCodec.cache."
            }
        }
        return try {
            render(script, scriptId, types, faithful = false)
        } catch (e: Exception) {
            render(script, scriptId, types, faithful = true)
        }
    }

    private fun render(script: Cs2Script, scriptId: Int, types: Cs2Types, faithful: Boolean): Decompiled {
        val structurer = Cs2Structurer(script, scriptId, analyzer, faithful)
        val function = types.apply(structurer.structure())
        return Decompiled(scriptId, Cs2Emitter(function).emit(), function, structurer.deadCode)
    }


    fun projectFiles(corpus: Corpus, types: Cs2Types = types()): Map<String, String> = linkedMapOf(
        Cs2Declarations.OPCODES_FILE to
            Cs2Declarations.generate(types.argumentKinds, analyzer.stringParams()),
        Cs2Declarations.GAMEVALS_FILE to Cs2Declarations.gamevals(),
        Cs2Declarations.VARS_FILE to Cs2Declarations.variables(corpus.variables) {
            types.varKind(it.space, it.id)
        },
        TSCONFIG_FILE to Cs2Declarations.tsconfig(),
        DEAD_CODE_FILE to corpus.deadCodeReport
    )

    fun write(folder: File, log: (String) -> Unit = {}): Corpus {
        folder.mkdirs()
        val corpus = decompile(log) { script ->
            val file = Cs2Layout.fileFor(folder, script.scriptId)
            file.parentFile?.mkdirs()
            file.writeText(script.source)
        }
        for ((name, body) in projectFiles(corpus)) {
            folder.resolve(name).writeText(body)
        }
        return corpus
    }

    private fun appendDeadCode(out: StringBuilder, script: Decompiled) {
        out.append("\n## ").append(Cs2Layout.headerOf(script.scriptId))
            .append(" (script ").append(script.scriptId).append(")\n")
        for (finding in script.deadCode) {
            val where = finding.instructions?.let { " (instructions ${it.first}..${it.last})" } ?: ""
            out.append("\n- **").append(finding.kind.label).append("**").append(where)
            finding.note?.let { out.append(" - ").append(it) }
            out.append('\n')
            val body = notes(script.function, listOf(finding), "").lines().drop(1)
                .joinToString("\n").trimEnd()
            if (body.isNotBlank()) {
                out.append("\n```ts\n").append(body).append("\n```\n")
            }
        }
    }

    companion object {

        const val TSCONFIG_FILE = "tsconfig.json"

        const val DEAD_CODE_FILE = "dead-code.md"

        private const val GOTO = "goto("

        private const val DEAD_CODE_HEADER =
            "# Dead code in the 727 clientscripts\n\n" +
                "Everything here is in the bytecode but cannot affect what the script does.\n" +
                "A dead store is kept in the decompiled source; unreachable code is dropped from it.\n" +
                "Unreachable code that only jumps somewhere is the compiler's own layout and is not listed,\n" +
                "and neither is the default `return` every script ends with.\n"

        fun notes(function: Cs2Function, findings: List<Cs2DeadCode>, prefix: String): String {
            val emitter = Cs2Emitter(function)
            val out = StringBuilder()
            for (finding in findings) {
                val where = finding.instructions?.let { " (instructions ${it.first}..${it.last})" } ?: ""
                val note = finding.note?.let { ": $it" } ?: ""
                out.append(prefix).append(finding.kind.label).append(where).append(note).append('\n')
                finding.condition?.let {
                    out.append(prefix).append("  ").append(emitter.renderCondition(it)).append('\n')
                }
                if (finding.statements.isEmpty()) {
                    continue
                }
                emitter.renderStatements(finding.statements).trimEnd().lines()
                    .forEach { out.append(prefix).append("  ").append(it).append('\n') }
            }
            return out.toString()
        }

        fun collectVariables(statements: List<Stmt>, into: MutableSet<VarRef>) {
            fun walkExpr(value: Expr) {
                when (value) {
                    is VarRef -> into.add(value)
                    is Binary -> { walkExpr(value.left); walkExpr(value.right) }
                    is Join -> value.parts.forEach { walkExpr(it) }
                    is ArrayRef -> walkExpr(value.index)
                    is OpCall -> value.args.forEach { walkExpr(it) }
                    is ScriptCall -> value.args.forEach { walkExpr(it) }
                    is Callback -> { value.args.forEach { walkExpr(it) }; value.triggers.forEach { walkExpr(it) } }
                    else -> Unit
                }
            }
            fun walkCondition(condition: Condition) {
                when (condition) {
                    is Compare -> { walkExpr(condition.left); walkExpr(condition.right) }
                    is AndAll -> condition.terms.forEach { walkCondition(it) }
                    is OrAny -> condition.terms.forEach { walkCondition(it) }
                }
            }
            for (statement in statements) {
                when (statement) {
                    is ExprStmt -> walkExpr(statement.call)
                    is LocalAssign -> walkExpr(statement.value)
                    is VarAssign -> { into.add(statement.target); walkExpr(statement.value) }
                    is ParallelAssign -> {
                        statement.targets.forEach { walkExpr(it) }
                        statement.values.forEach { walkExpr(it) }
                    }
                    is ArrayAssign -> { walkExpr(statement.index); walkExpr(statement.value) }
                    is ArrayDefine -> walkExpr(statement.size)
                    is MultiAssign -> walkExpr(statement.call)
                    is Return -> statement.values.forEach { walkExpr(it) }
                    is If -> {
                        walkCondition(statement.condition)
                        collectVariables(statement.then, into)
                        collectVariables(statement.otherwise, into)
                    }
                    is While -> {
                        statement.condition?.let { walkCondition(it) }
                        collectVariables(statement.body, into)
                    }
                    is Switch -> {
                        walkExpr(statement.subject)
                        statement.cases.forEach { collectVariables(it.body, into) }
                        collectVariables(statement.default, into)
                    }
                    else -> Unit
                }
            }
        }
    }
}
