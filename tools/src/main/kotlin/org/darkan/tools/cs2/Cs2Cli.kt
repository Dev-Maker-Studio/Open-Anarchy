package org.darkan.tools.cs2

import java.io.File
import org.darkan.tools.cs2.ir.AndAll
import org.darkan.tools.cs2.ir.ArrayAssign
import org.darkan.tools.cs2.ir.ArrayDefine
import org.darkan.tools.cs2.ir.ArrayRef
import org.darkan.tools.cs2.ir.Binary
import org.darkan.tools.cs2.ir.Callback
import org.darkan.tools.cs2.ir.Compare
import org.darkan.tools.cs2.ir.Condition
import org.darkan.tools.cs2.ir.Expr
import org.darkan.tools.cs2.ir.ExprStmt
import org.darkan.tools.cs2.ir.If
import org.darkan.tools.cs2.ir.Join
import org.darkan.tools.cs2.ir.LocalAssign
import org.darkan.tools.cs2.ir.MultiAssign
import org.darkan.tools.cs2.ir.OpCall
import org.darkan.tools.cs2.ir.OrAny
import org.darkan.tools.cs2.ir.ParallelAssign
import org.darkan.tools.cs2.ir.Return
import org.darkan.tools.cs2.ir.ScriptCall
import org.darkan.tools.cs2.ir.Stmt
import org.darkan.tools.cs2.ir.Switch
import org.darkan.tools.cs2.ir.VarAssign
import org.darkan.tools.cs2.ir.VarRef
import org.darkan.tools.cs2.ir.While
import kotlin.math.abs
import org.darkan.tools.cs2.compile.Cs2CodeGen
import org.darkan.tools.cs2.compile.Cs2Parser
import org.darkan.tools.cs2.vm.Cs2Event
import org.darkan.tools.cs2.vm.Cs2Hook
import org.darkan.tools.cs2.vm.Cs2HookDispatcher
import org.darkan.tools.cs2.vm.Cs2Host
import org.darkan.tools.cs2.vm.Cs2Values
import org.darkan.tools.cs2.vm.Cs2Vm
import org.darkan.tools.cs2.vm.NoOpCs2Host
import org.darkan.tools.rsinterface.InterfaceCs2Host
import org.darkan.tools.rsinterface.InterfaceInstance
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder

/**
 * `cs2` sub-commands for the tools CLI.
 *
 * ```
 * cs2 verify              decode + re-encode every script, assert byte identity
 * cs2 dump <id>           print the raw instruction listing for one script
 * cs2 info <id>           print one script's header
 * ```
 */
fun runCs2(cache: Cache, args: List<String>) {
    when (args.firstOrNull() ?: "help") {
        "verify" -> cs2Verify(cache)
        "dump" -> cs2Dump(cache, args.getOrNull(1)?.toIntOrNull())
        "info" -> cs2Info(cache, args.getOrNull(1)?.toIntOrNull())
        "unknown" -> cs2Unknown(cache)
        "analyse", "analyze" -> cs2Analyse(cache)
        "decompile" -> cs2Decompile(cache, args.getOrNull(1)?.toIntOrNull())
        "decompile-all" -> cs2DecompileAll(cache, args.getOrNull(1))
        "roundtrip" -> cs2RoundTrip(cache, args.getOrNull(1)?.toIntOrNull())
        "diff" -> cs2Diff(cache, args.getOrNull(1)?.toIntOrNull())
        "run" -> cs2Run(cache, args.getOrNull(1)?.toIntOrNull(), args.drop(2))
        "declarations" -> cs2Declarations(args.getOrNull(1))
        "interface" -> cs2Interface(cache, args.getOrNull(1)?.toIntOrNull())
        "interfaces" -> cs2InterfaceSweep(cache)
        "compile" -> cs2Compile(cache, args.getOrNull(1), args.getOrNull(2)?.toIntOrNull())
        else -> println(
            """
            cs2 sub-commands:
              verify        decode and re-encode every clientscript, checking byte identity
              dump <id>     print the instruction listing for one script
              info <id>     print one script's header
            """.trimIndent(),
        )
    }
}

private fun cs2Decompile(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 decompile <scriptId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script = analyzer.script(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    val function = Cs2Structurer(script, scriptId, analyzer).structure()
    println(Cs2Emitter(function).emit())
}

/** Writes the ambient TypeScript declarations next to the decompiled scripts. */
private fun cs2Declarations(directory: String?) {
    val target = File(directory ?: ".").apply { mkdirs() }
    target.resolve("cs2.d.ts").writeText(Cs2Declarations.generate())
    target.resolve("tsconfig.json").writeText(Cs2Declarations.tsconfig())
    println("wrote ${target.resolve("cs2.d.ts").absolutePath}")
    println("wrote ${target.resolve("tsconfig.json").absolutePath}")
}

private fun cs2DecompileAll(cache: Cache, directory: String?) {
    println("Decompiling every clientscript...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    val target = directory?.let { File(it).apply { mkdirs() } }
    target?.resolve("cs2.d.ts")?.writeText(Cs2Declarations.generate())
    target?.resolve("tsconfig.json")?.writeText(Cs2Declarations.tsconfig())
    val variables = LinkedHashSet<VarRef>()
    val failures = ArrayList<Pair<Int, String>>()
    var written = 0
    var gotos = 0
    val started = System.currentTimeMillis()

    for (id in analyzer.ids) {
        val script = analyzer.script(id) ?: continue
        try {
            val function = Cs2Structurer(script, id, analyzer).structure()
            val source = Cs2Emitter(function).emit()
            collectVariables(function.body, variables)
            if (source.contains("goto(")) gotos++
            target?.resolve("clientscript-$id.ts")?.writeText(source)
            written++
        } catch (e: Exception) {
            failures.add(id to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    }

    target?.resolve("vars.d.ts")?.writeText(Cs2Declarations.variables(variables))

    println()
    println("decompiled:      $written")
    println("variables:       ${variables.size}")
    println("failed:          ${failures.size}")
    println("with raw gotos:  $gotos")
    println("elapsed:         ${System.currentTimeMillis() - started}ms")
    if (target != null) println("written to:      ${target.absolutePath}")

    if (failures.isNotEmpty()) {
        println()
        println("First failures:")
        failures.take(10).forEach { (id, message) -> println("  script $id: $message") }
        val grouped = failures.groupingBy { it.second.take(60) }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
        println()
        println("Grouped:")
        grouped.forEach { (message, count) -> println("  %-62s %d".format(message, count)) }
    }
}

private fun cs2Analyse(cache: Cache) {
    println("Analysing clientscripts...")
    val started = System.currentTimeMillis()
    val report = Cs2Analyzer(cache).analyse { println("  $it") }
    println()
    println("analysed:      ${report.analysed}")
    println("string params: ${report.stringParams.size}")
    println("problems:      ${report.problems.size}")
    println("elapsed:       ${System.currentTimeMillis() - started}ms")

    val returning = report.returnSignatures.values.count { it.total > 0 }
    println("scripts returning values: $returning")

    if (report.problems.isNotEmpty()) {
        println()
        println("First problems:")
        report.problems.take(15).forEach { println("  script ${it.scriptId}: ${it.message}") }
        val opcode = Regex("\\(([A-Z][A-Z0-9_]*)\\)")
        val byOpcode = report.problems
            .groupingBy { opcode.find(it.message)?.groupValues?.get(1) ?: it.message.substringBefore(' ') }
            .eachCount()
            .entries.sortedByDescending { it.value }
        println()
        println("Grouped by opcode:")
        byOpcode.forEach { (name, count) -> println("  %-32s %d".format(name, count)) }
        printSuspectOpcodes(cache, report)
    }
    println()
    println(if (report.ok) "PASS - every script balances." else "FAIL")
}

/**
 * Underflows surface at whichever opcode happens to consume the stack, not at
 * the one whose signature is wrong. Ranking opcodes by how much more often they
 * appear in failing scripts than in passing ones points at the real culprit.
 */
private fun printSuspectOpcodes(cache: Cache, report: Cs2Analyzer.Report) {
    val scripts = Cs2Cache(cache)
    val failing = report.problems.map { it.scriptId }.toSet()
    val inFailing = HashMap<String, Int>()
    val inPassing = HashMap<String, Int>()

    for (id in scripts.scriptIds()) {
        val script = try {
            scripts.load(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        val used = script.instructions.mapTo(HashSet()) { it.op.opName }
        val into = if (id in failing) inFailing else inPassing
        used.forEach { into[it] = (into[it] ?: 0) + 1 }
    }

    val failCount = failing.size.coerceAtLeast(1)
    val passCount = (report.analysed - failing.size).coerceAtLeast(1)
    val ranked = inFailing.entries
        .filter { it.value >= 3 }
        .map { (name, count) ->
            val failRate = count.toDouble() / failCount
            val passRate = (inPassing[name] ?: 0).toDouble() / passCount
            Triple(name, failRate / (passRate + 1e-6), count)
        }
        .sortedByDescending { it.second }
        .take(15)

    println()
    println("Opcodes enriched in failing scripts (likely wrong signatures):")
    ranked.forEach { (name, enrichment, count) ->
        val op = Cs2Opcodes.byName(name)
        println(
            "  %-32s x%-8.1f in %d failing   pop(%d,%d,%d) push(%d,%d,%d)".format(
                name, enrichment, count,
                op?.popInt ?: 0, op?.popStr ?: 0, op?.popLong ?: 0,
                op?.pushInt ?: 0, op?.pushStr ?: 0, op?.pushLong ?: 0,
            ),
        )
    }
}

private fun cs2Unknown(cache: Cache) {
    println("Inferring operand widths for opcodes the client no longer defines...")
    val result = Cs2OperandInference.infer(cache) { println("  $it") }
    println()
    println("resolved (${result.resolved.size}):")
    result.resolved.entries.sortedBy { it.key }.forEach { (id, width) ->
        println("  opcode %4d  %-4s  %d uses".format(id, width.name, result.occurrences[id] ?: 0))
    }
    if (result.ambiguous.isNotEmpty()) {
        println()
        println("ambiguous (${result.ambiguous.size}):")
        result.ambiguous.entries.sortedBy { it.key }.forEach { (id, widths) ->
            println("  opcode %4d  %s".format(id, widths.map { it.name }))
        }
    }
    if (result.unsolvedScripts.isNotEmpty()) {
        println()
        println("unsolved scripts (${result.unsolvedScripts.size}): ${result.unsolvedScripts.take(20)}")
    }
}

private fun cs2Verify(cache: Cache) {
    println("Verifying clientscript codec round-trip...")
    val started = System.currentTimeMillis()
    val report = verifyCodecRoundTrip(cache) { println("  $it") }
    val elapsed = System.currentTimeMillis() - started

    println()
    println("archives:   ${report.total}")
    println("identical:  ${report.identical}")
    println("mismatched: ${report.mismatched.size}")
    println("failed:     ${report.failed.size}")
    println("elapsed:    ${elapsed}ms")

    if (report.failed.isNotEmpty()) {
        println()
        println("First failures:")
        report.failed.take(10).forEach { (id, message) -> println("  script $id: $message") }
    }
    if (report.mismatched.isNotEmpty()) {
        println()
        println("First mismatches: ${report.mismatched.take(20)}")
    }
    println()
    println(if (report.ok) "PASS - every script re-encoded byte-identically." else "FAIL")
}

private fun cs2Dump(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 dump <scriptId>")
        return
    }
    val script = Cs2Cache(cache).load(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    printHeader(scriptId, script)
    println()
    script.instructions.forEachIndexed { index, instruction ->
        println("%5d  %s".format(index, instruction))
    }
    script.switchTables.forEachIndexed { table, cases ->
        println()
        println("switch table $table:")
        cases.forEach { println("  case ${it.key} -> +${it.offset}") }
    }
}

private fun cs2Info(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 info <scriptId>")
        return
    }
    val script = Cs2Cache(cache).load(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    printHeader(scriptId, script)
}

private fun printHeader(scriptId: Int, script: Cs2Script) {
    println("script $scriptId")
    println("  name:         ${script.name ?: "<none>"}")
    println("  instructions: ${script.size}")
    println("  int args:     ${script.intArgsCount}   locals: ${script.intLocalsCount}")
    println("  string args:  ${script.stringArgsCount}   locals: ${script.stringLocalsCount}")
    println("  long args:    ${script.longArgsCount}   locals: ${script.longLocalsCount}")
    println("  switch tables: ${script.switchTables.size}")
}

/**
 * Decompiles every script, re-compiles the TypeScript, and checks the bytes
 * come back unchanged. This is the end-to-end guarantee: anything that does not
 * round-trip is a decompiler or code-generator bug, not a formatting choice.
 */
private fun cs2RoundTrip(cache: Cache, limit: Int?) {
    println("Decompiling and re-compiling every clientscript...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    var identical = 0
    val mismatched = ArrayList<Int>()
    val failed = ArrayList<Pair<Int, String>>()
    val divergences = HashMap<String, Int>()
    val lostWork = ArrayList<Int>()
    var viaFallback = 0
    val started = System.currentTimeMillis()
    val ids = if (limit != null) analyzer.ids.take(limit) else analyzer.ids.toList()

    for (id in ids) {
        val raw = try {
            Cs2Cache(cache).raw(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        try {
            val script = Cs2Codec.decode(raw)
            script.returnSignature = analyzer.script(id)?.returnSignature
            val source = Cs2Emitter(Cs2Structurer(script, id, analyzer).structure()).emit()
            val rebuilt = Cs2CodeGen(Cs2Parser(source, analyzer::paramIsString).parse(), analyzer).generate()
            if (Cs2Codec.encode(rebuilt).contentEquals(raw)) {
                identical++
            } else if (faithfulMatches(raw, script, id, analyzer)) {
                identical++
                viaFallback++
            } else {
                mismatched.add(id)
                firstDivergence(script, rebuilt)?.let { divergences[it] = (divergences[it] ?: 0) + 1 }
                if (!sameWork(script, rebuilt)) lostWork.add(id)
            }
        } catch (e: Exception) {
            failed.add(id to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    }

    println()
    println("scripts:    ${ids.size}")
    println("identical:  $identical  (${viaFallback} of them via the faithful fallback)")
    println("mismatched: ${mismatched.size}")
    println("failed:     ${failed.size}")
    println("elapsed:    ${System.currentTimeMillis() - started}ms")

    if (failed.isNotEmpty()) {
        println()
        println("Failed scripts: ${failed.take(12).map { it.first }}")
        println("Failure causes:")
        failed.groupingBy { it.second.take(70) }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
            .forEach { (message, count) -> println("  %-72s %d".format(message, count)) }
    }
    println(
        if (lostWork.isEmpty()) "every mismatch is layout-only; no instructions gained or lost"
        else "WARNING: ${lostWork.size} scripts changed their actual work: ${lostWork.take(10)}",
    )
    if (divergences.isNotEmpty()) {
        println()
        println("Where the opcode sequence first diverges:")
        divergences.entries.sortedByDescending { it.value }.take(15)
            .forEach { (where, count) -> println("  %-52s %d".format(where, count)) }
    }
    if (mismatched.isNotEmpty()) println("\nFirst mismatches: ${mismatched.take(20)}")
    println()
    println(if (mismatched.isEmpty() && failed.isEmpty()) "PASS" else "FAIL")
}

/** Shows the original and re-compiled instruction listings side by side. */
private fun cs2Diff(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 diff <scriptId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val original = analyzer.script(scriptId) ?: run {
        println("No clientscript $scriptId in the cache.")
        return
    }
    val source = Cs2Emitter(Cs2Structurer(original, scriptId, analyzer).structure()).emit()
    val rebuilt = Cs2CodeGen(Cs2Parser(source, analyzer::paramIsString).parse(), analyzer).generate()

    println(source)
    println("header: locals ${original.intLocalsCount}/${original.stringLocalsCount}/${original.longLocalsCount} " +
        "vs ${rebuilt.intLocalsCount}/${rebuilt.stringLocalsCount}/${rebuilt.longLocalsCount}")
    println("switch tables: ${original.switchTables.size} vs ${rebuilt.switchTables.size}")
    println("instructions: ${original.size} vs ${rebuilt.size}")
    original.switchTables.forEachIndexed { index, table ->
        val other = rebuilt.switchTables.getOrNull(index)
        if (table != other) {
            println("switch table $index differs:")
            println("  original:   ${table.take(12)}")
            println("  recompiled: ${other?.take(12)}")
        }
    }
    println()
    reportWorkDifference(original, rebuilt)
    println("%-4s %-40s %s".format("#", "original", "recompiled"))
    val count = maxOf(original.size, rebuilt.size)
    var shown = 0
    for (index in 0 until count) {
        val left = original.instructions.getOrNull(index)
        val right = rebuilt.instructions.getOrNull(index)
        // Jump distances shift as soon as anything else differs, so only flag
        // places where the actual opcode sequence diverges.
        val jump = left?.op?.let { Cs2Cfg.isConditionalBranch(it) || Cs2Cfg.isUnconditionalBranch(it) } == true
        val same = if (jump) left?.op?.opName == right?.op?.opName else left?.toString() == right?.toString()
        if (same) continue
        println("%-4d %-40s %-40s".format(index, left?.toString() ?: "", right?.toString() ?: ""))
        if (++shown > 12) {
            println("... stopping after the first dozen opcode differences")
            break
        }
    }
    if (shown == 0) {
        println("(the opcode sequence matches)")
        for (index in 0 until count) {
            val left = original.instructions.getOrNull(index) ?: continue
            val right = rebuilt.instructions.getOrNull(index) ?: continue
            if (left.toString() == right.toString()) continue
            println("%-4d %-40s %-40s  target %d vs %d".format(
                index, left, right, index + left.intOperand + 1, index + right.intOperand + 1))
            if (++shown > 8) break
        }
    }
}

/** The first place two instruction lists stop agreeing, as `original -> rebuilt`. */
private fun firstDivergence(original: Cs2Script, rebuilt: Cs2Script): String? {
    for (index in 0 until maxOf(original.size, rebuilt.size)) {
        val left = original.instructions.getOrNull(index)
        val right = rebuilt.instructions.getOrNull(index)
        val jump = left?.op?.let { Cs2Cfg.isConditionalBranch(it) || Cs2Cfg.isUnconditionalBranch(it) } == true
        val same = if (jump) left?.op?.opName == right?.op?.opName else left?.toString() == right?.toString()
        if (same) continue
        return "${left?.op?.opName ?: "<end>"} -> ${right?.op?.opName ?: "<end>"}"
    }
    return null
}

/**
 * True when both scripts do the same work, ignoring layout. Jumps are excluded
 * because their distances shift with any layout change; everything else must
 * match exactly, so a mismatch here means the decompiler dropped or invented
 * instructions rather than merely arranging them differently.
 */
private fun sameWork(original: Cs2Script, rebuilt: Cs2Script): Boolean {
    fun census(script: Cs2Script) = script.instructions
        .filterNot { Cs2Cfg.isConditionalBranch(it.op) || Cs2Cfg.isUnconditionalBranch(it.op) }
        .groupingBy { it.toString() }
        .eachCount()
    return census(original) == census(rebuilt)
}

/** Lists instructions the rebuilt script gained or lost, ignoring jumps. */
private fun reportWorkDifference(original: Cs2Script, rebuilt: Cs2Script) {
    fun census(script: Cs2Script) = script.instructions
        .filterNot { Cs2Cfg.isConditionalBranch(it.op) || Cs2Cfg.isUnconditionalBranch(it.op) }
        .groupingBy { it.toString() }
        .eachCount()
    val before = census(original)
    val after = census(rebuilt)
    val changed = (before.keys + after.keys)
        .mapNotNull { key ->
            val delta = (after[key] ?: 0) - (before[key] ?: 0)
            if (delta == 0) null else key to delta
        }
    if (changed.isEmpty()) {
        println("work: unchanged (layout only)")
        return
    }
    println("work changed:")
    changed.sortedByDescending { abs(it.second) }.take(10).forEach { (key, delta) ->
        println("  %+d  %s".format(delta, key))
    }
}

/** Runs a script on the virtual machine with a do-nothing host, for smoke testing. */
private fun cs2Run(cache: Cache, scriptId: Int?, args: List<String>) {
    if (scriptId == null) {
        println("usage: cs2 run <scriptId> [intArgs...]")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script = analyzer.script(scriptId) ?: run {
        println("No clientscript $scriptId in the cache.")
        return
    }

    val calls = ArrayList<String>()
    val host = object : NoOpCs2Host() {
        override fun invoke(op: Cs2Op, operand: Int, values: Cs2Values): Cs2Values {
            calls.add("${op.opName}(${values.ints.joinToString(", ")}${
                if (values.strings.isEmpty()) "" else ", " + values.strings.joinToString(", ") { "\"$it\"" }
            })")
            return Cs2Host.defaults(op)
        }

        override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) {
            calls.add("${op.opName} on $component -> ${hook?.scriptId ?: "none"} args=${hook?.args?.ints}")
        }

        override fun onError(message: String) = println("  error: $message")
    }

    val ints = args.mapNotNull { it.toIntOrNull() }
    val result = Cs2Vm(analyzer, host).execute(script, ints)
    println("ran script $scriptId with ints=$ints")
    println("returned: ints=${result.ints} strings=${result.strings} longs=${result.longs}")
    println("host calls (${calls.size}):")
    calls.take(40).forEach { println("  $it") }
}

/**
 * Re-decompiles with structuring disabled and checks that form round-trips.
 *
 * Structured output is the goal, but fidelity comes first: where the structurer
 * cannot reproduce a script's exact branch layout, the literal block-by-block
 * rendering can, and that is what gets written out for those scripts.
 */
private fun faithfulMatches(raw: ByteArray, script: Cs2Script, id: Int, analyzer: Cs2Analyzer): Boolean =
    try {
        val source = Cs2Emitter(Cs2Structurer(script, id, analyzer, faithful = true).structure()).emit()
        val rebuilt = Cs2CodeGen(Cs2Parser(source, analyzer::paramIsString).parse(), analyzer).generate()
        Cs2Codec.encode(rebuilt).contentEquals(raw)
    } catch (e: Exception) {
        false
    }

/**
 * Opens an interface headlessly, runs its components' load scripts through the
 * virtual machine, and reports what the scripts actually did. This is the same
 * path the editor takes, minus the rendering.
 */
private fun cs2Interface(cache: Cache, interfaceId: Int?) {
    if (interfaceId == null) {
        println("usage: cs2 interface <interfaceId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    val decoder = InterfaceDecoder()
    val definitions = decoder.create(decoder.size(cache) + 1)
    if (interfaceId !in definitions.indices) {
        println("No interface $interfaceId in the cache.")
        return
    }
    decoder.load(definitions, cache, interfaceId)
    val definition = definitions[interfaceId]
    if (definition.components == null) {
        println("Interface $interfaceId has no components.")
        return
    }

    val instance = InterfaceInstance(interfaceId, definition)
    var current: InterfaceInstance? = instance
    val host = InterfaceCs2Host(
        interfaces = { id -> if (id == interfaceId) current else null },
        enums = { id -> Cache.enums.getOrNull(id) },
        structs = { id -> Cache.structs.getOrNull(id) },
        params = { id -> Cache.params.getOrNull(id) },
    )
    val dispatcher = Cs2HookDispatcher(analyzer, host)

    // Snapshot the fields scripts most often change, so the report can show
    // what running the interface actually did rather than just that it ran.
    val before = instance.components.map { Triple(it.hidden, it.text, it.spriteId) }

    var ran = 0
    for (component in instance.components) {
        val hook = component.definition.onLoadScript ?: continue
        host.setActive(0, component)
        dispatcher.dispatch(hook, Cs2Event(source = component.hash))
        ran++
    }
    current = instance

    val changed = instance.components.filterIndexed { index, state ->
        val (hidden, text, sprite) = before[index]
        state.hidden != hidden || state.text != text || state.spriteId != sprite
    }

    println("interface $interfaceId: ${instance.components.size} components")
    println("  components with hooks: ${instance.components.count { it.definition.hooks().isNotEmpty() }}")
    println("  onLoad scripts run:    $ran")
    println("  components changed:    ${changed.size}")
    println("  hooks bound by scripts: ${host.boundHooks.values.sumOf { it.size }}")

    changed.take(10).forEach { state ->
        val (hidden, text, sprite) = before[state.index]
        val parts = ArrayList<String>()
        if (state.hidden != hidden) parts.add("hidden $hidden -> ${state.hidden}")
        if (state.text != text) parts.add("text \"$text\" -> \"${state.text}\"")
        if (state.spriteId != sprite) parts.add("sprite $sprite -> ${state.spriteId}")
        println("    component ${state.index}: ${parts.joinToString(", ")}")
    }
    host.boundHooks.entries.take(6).forEach { (hash, events) ->
        println("    hooks on component ${hash and 0xFFFF}: ${events.keys.joinToString(", ")}")
    }
    if (host.log.isNotEmpty()) {
        println("  script output:")
        host.log.take(10).forEach { println("    $it") }
    }
}

/** Runs every interface's load scripts, checking the whole path holds up. */
private fun cs2InterfaceSweep(cache: Cache) {
    println("Running load scripts for every interface...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    val decoder = InterfaceDecoder()
    val definitions = decoder.create(decoder.size(cache) + 1)

    var opened = 0
    var withComponents = 0
    var scriptsRun = 0
    var componentsChanged = 0
    var hooksBound = 0
    val failures = ArrayList<Pair<Int, String>>()
    val started = System.currentTimeMillis()

    for (id in definitions.indices) {
        try {
            decoder.load(definitions, cache, id)
        } catch (e: Exception) {
            failures.add(id to "decode: ${e.message}")
            continue
        }
        opened++
        val definition = definitions[id]
        if (definition.components == null) continue
        withComponents++

        try {
            val instance = InterfaceInstance(id, definition)
            val host = InterfaceCs2Host(
                interfaces = { other -> if (other == id) instance else null },
                enums = { Cache.enums.getOrNull(it) },
                structs = { Cache.structs.getOrNull(it) },
                params = { Cache.params.getOrNull(it) },
            )
            val dispatcher = Cs2HookDispatcher(analyzer, host)
            val before = instance.components.map { Triple(it.hidden, it.text, it.spriteId) }

            for (component in instance.components) {
                val hook = component.definition.onLoadScript ?: continue
                host.setActive(0, component)
                dispatcher.dispatch(hook, Cs2Event(source = component.hash))
                scriptsRun++
            }
            componentsChanged += instance.components.count { state ->
                val (hidden, text, sprite) = before[state.index]
                state.hidden != hidden || state.text != text || state.spriteId != sprite
            }
            hooksBound += host.boundHooks.values.sumOf { it.size }
        } catch (e: Exception) {
            failures.add(id to "run: ${e.message ?: e::class.simpleName}")
        }
    }

    println()
    println("interfaces decoded:   $opened")
    println("with components:      $withComponents")
    println("onLoad scripts run:   $scriptsRun")
    println("components changed:   $componentsChanged")
    println("hooks bound at run:   $hooksBound")
    println("failures:             ${failures.size}")
    println("elapsed:              ${System.currentTimeMillis() - started}ms")
    if (failures.isNotEmpty()) {
        failures.groupingBy { it.second.take(60) }.eachCount()
            .entries.sortedByDescending { it.value }.take(8)
            .forEach { (message, count) -> println("  %-62s %d".format(message, count)) }
    }
}

/** Every variable a statement tree reads or writes. */
private fun collectVariables(statements: List<Stmt>, into: MutableSet<VarRef>) {
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
            is While -> { walkCondition(statement.condition); collectVariables(statement.body, into) }
            is Switch -> {
                walkExpr(statement.subject)
                statement.cases.forEach { collectVariables(it.body, into) }
                collectVariables(statement.default, into)
            }
            else -> Unit
        }
    }
}

/**
 * Compiles an edited script back from disk and reports whether it still matches
 * the cache. Names are resolved through the folder's declarations, so a renamed
 * variable or function compiles to exactly what it did before.
 */
private fun cs2Compile(cache: Cache, directory: String?, scriptId: Int?) {
    if (directory == null || scriptId == null) {
        println("usage: cs2 compile <dir> <scriptId>")
        return
    }
    val folder = File(directory)
    val file = folder.resolve("clientscript-$scriptId.ts")
    if (!file.isFile) {
        println("No ${file.path}")
        return
    }

    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val symbols = Cs2SymbolTable.read(folder)

    val rebuilt = try {
        Cs2CodeGen(Cs2Parser(file.readText(), analyzer::paramIsString, symbols).parse(), analyzer).generate()
    } catch (e: Exception) {
        println("compile failed: ${e.message}")
        return
    }
    val bytes = Cs2Codec.encode(rebuilt)
    val original = Cs2Cache(cache).raw(scriptId)

    println("compiled ${file.name}: ${bytes.size} bytes, ${rebuilt.size} instructions")
    println(
        when {
            original == null -> "no original in the cache to compare against"
            bytes.contentEquals(original) -> "identical to the cached script"
            else -> "DIFFERS from the cached script (${original.size} bytes)"
        },
    )
}
