package org.darkan.tools.cs2

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ParamDefinition

/**
 * Whole-cache analysis that turns raw instruction lists into something the
 * decompiler can trust:
 *
 *  - every script's return signature, resolved across calls by fixpoint;
 *  - which param ids yield strings rather than ints;
 *  - confirmation that the opcode table's pop/push counts actually balance.
 *
 * The load-bearing observation is that CS2's compiler leaves all three operand
 * stacks empty at every basic-block boundary. Anything else would be a signature
 * error, so simulating each block in isolation both validates the table and
 * gives the decompiler a clean starting state per block.
 */
class Cs2Analyzer(cache: Cache) : Cs2Context {

    private val scripts = Cs2Cache(cache)
    private val loaded = HashMap<Int, Cs2Script?>()
    private val paramTypes = HashMap<Int, Boolean>()

    val ids: IntArray = scripts.scriptIds().sortedArray()

    override fun script(id: Int): Cs2Script? = loaded.getOrPut(id) {
        try {
            scripts.load(id)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Param types come straight from the cache. Only the `s` type lands on the
     * string stack - the other type characters (`c`, `d`, `o`, `n`, ...) are all
     * int-stack types. Ids that the cache does not define fall back to whatever
     * the corpus solver worked out.
     */
    override fun paramIsString(paramId: Int): Boolean {
        val declared = Cache.params.getOrNull(paramId) ?: ParamDefinition.EMPTY
        if (declared.type != 0.toChar()) return declared.isString()
        return paramTypes[paramId] ?: false
    }

    /** Problems found while validating a single script. */
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

    // ------------------------------------------------------------ param types

    /**
     * `*_PARAM` opcodes push an int or a string depending on the param's
     * declared type, which is not in the script. The type is still pinned down
     * by the stacks having to balance: try a script's unknown param ids both
     * ways and keep the assignment that validates.
     */
    private fun resolveParamTypes(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        var resolved = 0
        for ((_, script) in all) {
            val unknown = paramIdsIn(script).filter { it !in paramTypes }.distinct()
            if (unknown.isEmpty()) continue
            if (unknown.size > 12) continue // too many to enumerate; a later script will pin them
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

    /**
     * The first pass assigns each param id the moment it is seen, so an early
     * script can lock in a wrong guess. This revisits scripts that still fail,
     * allowing already-assigned ids to be flipped, and keeps any change that
     * fixes the script without breaking one that already passed.
     */
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

    /** Scripts that mention a given param id, so refinement only re-checks those. */
    private val scriptsByParamId = HashMap<Int, MutableList<Cs2Script>>()

    private fun indexParamUsers(all: List<Pair<Int, Cs2Script>>) {
        if (scriptsByParamId.isNotEmpty()) return
        for ((_, script) in all) {
            for (id in paramIdsIn(script).distinct()) {
                scriptsByParamId.getOrPut(id) { ArrayList() }.add(script)
            }
        }
    }

    /** True when the current param typing breaks another script that uses these ids. */
    private fun breaksOthers(ids: List<Int>, exclude: Cs2Script): Boolean =
        ids.asSequence()
            .flatMap { scriptsByParamId[it].orEmpty().asSequence() }
            .distinct()
            .any { it !== exclude && validate(it) != null }

    /** Param ids reachable by `*_PARAM` opcodes, read from the constant push before them. */
    private fun paramIdsIn(script: Cs2Script): List<Int> {
        val out = ArrayList<Int>()
        for (index in script.instructions.indices) {
            if (script[index].op.kind != OpKind.PARAM) continue
            for (back in index - 1 downTo maxOf(0, index - 4)) {
                val candidate = script[back]
                if (candidate.op.opName == "PUSH_CONSTANT_INT") {
                    out.add(candidate.intOperand)
                    break
                }
            }
        }
        return out
    }

    // ------------------------------------------------------ return signatures

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

    /**
     * A directly or mutually recursive script cannot be bootstrapped by the
     * fixpoint: working out what it returns needs the answer already. There are
     * only a handful, and the stacks still have to balance, so the signature can
     * be found by trying the plausible shapes and keeping the one that works.
     */
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

    /**
     * Simulates to each `RETURN` and reports what is left on the stacks. Returns
     * null when the script does not simulate cleanly with the current
     * assumptions, leaving the previous estimate in place for the next pass.
     */
    private fun computeReturnSignature(script: Cs2Script): ReturnSignature? {
        val walk = simulate(script)
        return if (walk.failure != null) null else walk.returns
    }

    // -------------------------------------------------------------- validation

    /**
     * Returns null when the script simulates cleanly, or a description of the
     * first inconsistency found.
     */
    fun validate(script: Cs2Script, assumeReturns: Boolean = false): String? =
        simulate(script).failure

    /** Outcome of walking a script's control-flow graph with the operand stacks. */
    class Walk(
        val cfg: Cs2Cfg?,
        /** Stack state on entry to each block, once reachability settles. */
        val entryStates: Array<SymbolicStacks?>,
        val returns: ReturnSignature,
        val failure: String?,
    )

    /**
     * Propagates stack state along control-flow edges.
     *
     * Stacks are usually empty at block boundaries, but not always - the
     * compiler leaves a value live across a branch for chained comparisons - so
     * state is carried along edges rather than reset per block. Reaching a block
     * with mismatched depths means a signature is wrong.
     */
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
                // A call reveals the order its arguments were pushed in, which
                // the callee's own header cannot express.
                if (instruction.op.kind == OpKind.GOSUB) {
                    recordArgumentOrder(instruction.intOperand, stacks)
                }
                try {
                    step(instruction, stacks)
                } catch (e: Exception) {
                    return Walk(
                        cfg, entryStates, returns ?: ReturnSignature.EMPTY,
                        "instruction $index (${instruction.op.opName}): ${e.message}",
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

    private fun step(instruction: Cs2Instruction, stacks: SymbolicStacks) {
        val effect = effectiveSignature(instruction, this, stacks)
        val constant = when (instruction.op.opName) {
            "PUSH_CONSTANT_INT" -> instruction.intOperand
            else -> null
        }
        stacks.apply(effect, pushedIntConstant = constant, pushedString = instruction.strOperand)
    }
}
