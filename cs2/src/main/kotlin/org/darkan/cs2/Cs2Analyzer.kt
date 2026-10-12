package org.darkan.cs2

import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.decoder.ParamDecoder

class Cs2Analyzer(private val cache: Cache) : Cs2Context {

    private val scripts = Cs2Cache(cache)

    private val params: Array<ParamDefinition> by lazy { ParamDecoder().load(cache) }

    private val loaded = ConcurrentHashMap<Int, Cs2Script>()
    private val missing = ConcurrentHashMap.newKeySet<Int>()

    private val overrides = ConcurrentHashMap<Int, Cs2Script>()

    private val paramTypes = HashMap<Int, Boolean>()

    val ids: IntArray = scripts.scriptIds().sortedArray()

    override fun script(id: Int): Cs2Script? {
        overrides[id]?.let { return it }
        loaded[id]?.let { return it }
        if (id in missing) return null
        val decoded = try {
            scripts.load(id)
        } catch (e: Exception) {
            null
        }
        if (decoded == null) {
            missing.add(id)
            return null
        }
        loaded[id] = decoded
        return decoded
    }

    fun cachedScript(id: Int): Cs2Script? = loaded[id] ?: try {
        scripts.load(id)
    } catch (e: Exception) {
        null
    }

    override fun paramIsString(paramId: Int): Boolean {
        val declared = params.getOrNull(paramId) ?: ParamDefinition.EMPTY
        if (declared.type != 0.toChar()) return declared.isString()
        return paramTypes[paramId] ?: false
    }

    fun stringParams(): Set<Int> {
        val out = TreeSet<Int>()
        for (id in params.indices) {
            if (paramIsString(id)) {
                out.add(id)
            }
        }
        for ((id, string) in paramTypes) {
            if (string) {
                out.add(id)
            }
        }
        return out
    }

    override fun paramType(paramId: Int): Char =
        (params.getOrNull(paramId) ?: ParamDefinition.EMPTY).type

    @Synchronized
    fun types(log: (String) -> Unit = {}): Cs2Types {
        inferred?.let { return it }
        val functions = ids.toList().mapNotNull { id ->
            val script = script(id) ?: return@mapNotNull null
            try {
                Cs2Structurer(script, id, this).structure()
            } catch (e: Exception) {
                null
            }
        }
        log("${functions.size} scripts lifted for type inference")
        return Cs2Types.analyse(this, functions, log).also { inferred = it }
    }

    private var inferred: Cs2Types? = null

    data class ScriptProblem(val scriptId: Int, val message: String)

    data class Report(
        val analysed: Int,
        val problems: List<ScriptProblem>,
        val stringParams: Set<Int>,
        val returnSignatures: Map<Int, ReturnSignature>,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    fun analyse(log: (String) -> Unit = {}): Report {
        val all: List<Pair<Int, Cs2Script>> =
            ids.toList().mapNotNull { id -> script(id)?.let { id to it } }
        log("${all.size} scripts loaded")

        resolveParamTypes(all, log)
        resolveReturnSignatures(all, log)
        refineParamTypes(all, log)

        val problems = ArrayList<ScriptProblem>()
        for ((id, script) in all) {
            val failure = validate(script)
            if (failure != null) problems.add(ScriptProblem(id, failure))
        }
        log("${problems.size} scripts failed validation")

        return Report(
            analysed = all.size,
            problems = problems,
            stringParams = paramTypes.filterValues { it }.keys,
            returnSignatures = all.associate { (id, script) ->
                id to (script.returnSignature ?: ReturnSignature.EMPTY)
            },
        )
    }


    private fun resolveParamTypes(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        var resolved = 0
        for ((_, script) in all) {
            val unknown = paramIdsIn(script).filter { it !in paramTypes }.distinct()
            if (unknown.isEmpty()) continue
            if (unknown.size > 12) continue
            val choice = (0 until (1 shl unknown.size)).firstOrNull { mask ->
                unknown.forEachIndexed { bit, id -> paramTypes[id] = (mask shr bit) and 1 == 1 }
                validate(script, assumeReturns = true) == null
            }
            if (choice == null) {
                unknown.forEach { paramTypes.remove(it) }
            } else {
                resolved += unknown.size
            }
        }
        log("$resolved param ids typed, ${paramTypes.count { it.value }} of them strings")
    }

    private fun refineParamTypes(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        indexParamUsers(all)
        repeat(4) { round ->
            var fixed = 0
            for ((_, script) in all) {
                if (validate(script) == null) continue
                val ids = paramIdsIn(script).distinct()
                if (ids.isEmpty() || ids.size > 12) continue
                val original = ids.associateWith { paramTypes[it] }

                val worked = (0 until (1 shl ids.size)).any { mask ->
                    ids.forEachIndexed { bit, id -> paramTypes[id] = (mask shr bit) and 1 == 1 }
                    validate(script) == null && !breaksOthers(ids, script)
                }
                if (worked) {
                    fixed++
                } else {
                    original.forEach { (id, value) ->
                        if (value == null) paramTypes.remove(id) else paramTypes[id] = value
                    }
                }
            }
            log("param refinement round ${round + 1}: $fixed scripts fixed")
            if (fixed == 0) return
        }
    }

    private val scriptsByParamId = HashMap<Int, MutableList<Cs2Script>>()

    private fun indexParamUsers(all: List<Pair<Int, Cs2Script>>) {
        if (scriptsByParamId.isNotEmpty()) return
        for ((_, script) in all) {
            for (id in paramIdsIn(script).distinct()) {
                scriptsByParamId.getOrPut(id) { ArrayList() }.add(script)
            }
        }
    }

    private fun breaksOthers(ids: List<Int>, exclude: Cs2Script): Boolean =
        ids.asSequence()
            .flatMap { scriptsByParamId[it].orEmpty().asSequence() }
            .distinct()
            .any { it !== exclude && validate(it) != null }

    private fun paramIdsIn(script: Cs2Script): List<Int> {
        val out = ArrayList<Int>()
        for (index in script.instructions.indices) {
            if (script[index].op.kind != OpKind.PARAM) continue
            for (back in index - 1 downTo maxOf(0, index - 4)) {
                val candidate = script[back]
                if (candidate.op == Cs2Op.PUSH_CONSTANT_INT) {
                    out.add(candidate.intOperand)
                    break
                }
            }
        }
        return out
    }


    private fun resolveReturnSignatures(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        all.forEach { (_, script) -> script.returnSignature = ReturnSignature.EMPTY }

        var pass = 0
        while (true) {
            pass++
            var changed = 0
            for ((_, script) in all) {
                val computed = computeReturnSignature(script) ?: continue
                if (computed != script.returnSignature) {
                    script.returnSignature = computed
                    changed++
                }
            }
            log("return-signature pass $pass: $changed changed")
            if (changed == 0 || pass >= 12) break
        }
        solveRecursiveReturnSignatures(all, log)
    }

    private fun solveRecursiveReturnSignatures(
        all: List<Pair<Int, Cs2Script>>,
        log: (String) -> Unit,
    ) {
        val candidates = ArrayList<ReturnSignature>()
        for (ints in 0..4) for (strings in 0..2) for (longs in 0..2) {
            candidates.add(ReturnSignature(ints, strings, longs))
        }
        candidates.sortBy { it.total }

        repeat(3) {
            var solved = 0
            for ((_, script) in all) {
                if (validate(script) == null) continue
                val original = script.returnSignature
                val match = candidates.firstOrNull { candidate ->
                    script.returnSignature = candidate
                    validate(script) == null
                }
                if (match == null) script.returnSignature = original else solved++
            }
            log("recursive return-signature solve: $solved resolved")
            if (solved == 0) return
        }
    }

    private fun computeReturnSignature(script: Cs2Script): ReturnSignature? {
        val walk = simulate(script)
        return if (walk.failure != null) null else walk.returns
    }


    fun validate(script: Cs2Script, assumeReturns: Boolean = false): String? =
        simulate(script).failure

    class Walk(
        val cfg: Cs2Cfg?,
        val entryStates: Array<SymbolicStacks?>,
        val returns: ReturnSignature,
        val failure: String?,
    )

    fun simulate(script: Cs2Script): Walk {
        val cfg = try {
            Cs2Cfg.build(script)
        } catch (e: Exception) {
            return Walk(null, emptyArray(), ReturnSignature.EMPTY, "cfg: ${e.message}")
        }

        val entryStates = arrayOfNulls<SymbolicStacks>(cfg.blocks.size)
        entryStates[0] = SymbolicStacks()
        val worklist = ArrayDeque(listOf(0))
        var returns: ReturnSignature? = null

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            val block = cfg.blocks[blockId]
            val stacks = entryStates[blockId]!!.copy()

            var returned = false
            for (index in block.indices) {
                val instruction = script[index]
                if (Cs2Cfg.isReturn(instruction.op)) {
                    val here = ReturnSignature(stacks.intDepth, stacks.stringDepth, stacks.longs)
                    if (here.total > 0) script.returnOrder = stacks.order()
                    if (returns != null && returns != here) {
                        return Walk(cfg, entryStates, returns, "inconsistent return signature: $returns vs $here")
                    }
                    returns = here
                    returned = true
                    break
                }
                if (instruction.op.kind == OpKind.GOSUB) {
                    recordArgumentOrder(instruction.intOperand, stacks)
                }
                try {
                    step(instruction, stacks)
                } catch (e: Exception) {
                    return Walk(
                        cfg, entryStates, returns ?: ReturnSignature.EMPTY,
                        "instruction $index (${instruction.op.name}): ${e.message}",
                    )
                }
            }
            if (returned) continue

            for (successor in block.successors) {
                val existing = entryStates[successor]
                if (existing == null) {
                    entryStates[successor] = stacks.copy()
                    worklist.addLast(successor)
                } else if (!existing.sameDepthAs(stacks)) {
                    return Walk(
                        cfg, entryStates, returns ?: ReturnSignature.EMPTY,
                        "block $successor reached with mismatched stacks ($existing vs $stacks)",
                    )
                } else {
                    existing.mergeConstantsFrom(stacks)
                }
            }
        }

        return Walk(cfg, entryStates, returns ?: ReturnSignature.EMPTY, null)
    }

    private fun recordArgumentOrder(scriptId: Int, stacks: SymbolicStacks) {
        val callee = script(scriptId) ?: return
        val total = callee.intArgsCount + callee.stringArgsCount + callee.longArgsCount
        if (total == 0 || callee.argumentOrder.isNotEmpty()) return
        val order = stacks.order()
        if (order.size >= total) callee.argumentOrder = order.takeLast(total)
    }


    val overriddenIds: List<Int> get() = overrides.keys.sorted()

    fun isOverridden(id: Int): Boolean = overrides.containsKey(id)

    class Installed(
        val scriptId: Int,
        val returnSignature: ReturnSignature,
        val previousReturnSignature: ReturnSignature?,
        val headerChanged: Boolean,
        val failure: String?,
    ) {
        val signatureChanged: Boolean
            get() = previousReturnSignature != null && previousReturnSignature != returnSignature
    }

    fun install(scriptId: Int, script: Cs2Script): Installed {
        val previous = script(scriptId)
        val headerChanged = previous != null && (
            previous.intArgsCount != script.intArgsCount ||
                previous.stringArgsCount != script.stringArgsCount ||
                previous.longArgsCount != script.longArgsCount
            )
        if (!headerChanged && script.argumentOrder.isEmpty()) {
            script.argumentOrder = previous?.argumentOrder.orEmpty()
        }

        script.returnSignature = ReturnSignature.EMPTY
        val walk = simulate(script)
        if (walk.failure == null) {
            script.returnSignature = walk.returns
            simulate(script)
        } else {
            script.returnSignature = previous?.returnSignature ?: ReturnSignature.EMPTY
        }

        overrides[scriptId] = script
        return Installed(
            scriptId = scriptId,
            returnSignature = script.returnSignature ?: ReturnSignature.EMPTY,
            previousReturnSignature = previous?.returnSignature,
            headerChanged = headerChanged,
            failure = walk.failure,
        )
    }

    fun uninstall(scriptId: Int): Boolean = overrides.remove(scriptId) != null

    fun uninstallAll(): Int {
        val count = overrides.size
        overrides.clear()
        return count
    }

    fun callersOf(scriptId: Int): List<Int> {
        val callers = ArrayList<Int>()
        for (id in ids) {
            val script = script(id) ?: continue
            val calls = script.instructions.any {
                it.op.kind == OpKind.GOSUB && it.intOperand == scriptId
            }
            if (calls) callers.add(id)
        }
        return callers
    }

    fun revalidate(scriptIds: Collection<Int>): List<ScriptProblem> =
        scriptIds.mapNotNull { id ->
            val script = script(id) ?: return@mapNotNull null
            validate(script)?.let { ScriptProblem(id, it) }
        }

    private fun step(instruction: Cs2Instruction, stacks: SymbolicStacks) {
        val effect = effectiveSignature(instruction, this, stacks)
        val constant = when (instruction.op) {
            Cs2Op.PUSH_CONSTANT_INT -> instruction.intOperand
            else -> null
        }
        stacks.apply(effect, pushedIntConstant = constant, pushedString = instruction.strOperand)
    }
}
