package org.darkan.tools.cs2

import org.darkan.tools.cs2.ir.Break
import org.darkan.tools.cs2.ir.AndAll
import org.darkan.tools.cs2.ir.Compare
import org.darkan.tools.cs2.ir.Condition
import org.darkan.tools.cs2.ir.OrAny
import org.darkan.tools.cs2.ir.negate
import org.darkan.tools.cs2.ir.Continue
import org.darkan.tools.cs2.ir.Cs2Function
import org.darkan.tools.cs2.ir.Cs2Type
import org.darkan.tools.cs2.ir.Goto
import org.darkan.tools.cs2.ir.If
import org.darkan.tools.cs2.ir.Label
import org.darkan.tools.cs2.ir.Return
import org.darkan.tools.cs2.ir.Stmt
import org.darkan.tools.cs2.ir.Switch
import org.darkan.tools.cs2.ir.SwitchCase
import org.darkan.tools.cs2.ir.While

/**
 * Rebuilds `if` / `else` / `while` / `switch` from the control-flow graph.
 *
 * CS2 comes out of a straightforward expression compiler, so the block layout
 * follows a small number of shapes and address order is meaningful: forward
 * branches skip over a region, and the only backward branches are loop edges.
 * Anything that does not match a known shape falls back to a label and an
 * explicit `goto`, which still re-compiles exactly.
 */
class Cs2Structurer(
    private val script: Cs2Script,
    private val scriptId: Int,
    private val context: Cs2Context,
    /**
     * Skips structuring and renders the blocks literally. Used as a fallback for
     * the few scripts whose shape the structurer cannot reproduce exactly.
     */
    private val faithful: Boolean = false,
) {
    private val cfg = Cs2Cfg.build(script)
    private val lifter = Cs2Lifter(script, context)
    private val lifted = arrayOfNulls<LiftedBlock>(cfg.blocks.size)
    private val entryStacks = arrayOfNulls<StackState>(cfg.blocks.size)
    private val labelled = HashSet<Int>()

    fun structure(): Cs2Function {
        liftAll()
        if (faithful) return function(unstructured())

        // A jump can be discovered after its target block was already emitted,
        // so structure once to collect the labels and again to place them.
        structureRange(0, cfg.blocks.size, emptySet())
        val structured = structureRange(0, cfg.blocks.size, emptySet())

        // If any jump ended up without a landing place, the shape was one this
        // structurer does not recognise. Fall back to a literal block-by-block
        // rendering, which is less pleasant to read but always correct.
        val body = if (danglingLabels(structured).isEmpty()) structured else unstructured()
        return function(body)
    }

    /**
     * Return values come back in the order they were pushed, which can interleave
     * the stacks, so the tuple type is taken from an actual `return` rather than
     * from the grouped signature.
     */
    private fun returnTypesFrom(body: List<Stmt>): List<Cs2Type> {
        val returned = firstReturn(body) ?: return script.returnOrder.ifEmpty { returnTypes() }
        return returned.values.map { it.type }
    }

    private fun firstReturn(statements: List<Stmt>): Return? {
        for (statement in statements) {
            when (statement) {
                is Return -> if (statement.values.isNotEmpty()) return statement
                is If -> (firstReturn(statement.then) ?: firstReturn(statement.otherwise))?.let { return it }
                is While -> firstReturn(statement.body)?.let { return it }
                is Switch -> {
                    statement.cases.forEach { case -> firstReturn(case.body)?.let { return it } }
                    firstReturn(statement.default)?.let { return it }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun function(body: List<Stmt>) = Cs2Function(
        scriptId = scriptId,
        name = script.name,
        intArgs = script.intArgsCount,
        stringArgs = script.stringArgsCount,
        longArgs = script.longArgsCount,
        intLocals = script.intLocalsCount,
        stringLocals = script.stringLocalsCount,
        longLocals = script.longLocalsCount,
        body = body,
        returns = returnTypesFrom(body),
        argumentOrder = script.argumentOrder,
    )

    /** Labels jumped to but never placed. */
    private fun danglingLabels(body: List<Stmt>): Set<String> {
        val wanted = HashSet<String>()
        val placed = HashSet<String>()
        fun walk(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Goto -> wanted.add(statement.label)
                    is Label -> placed.add(statement.name)
                    is If -> {
                        walk(statement.then)
                        walk(statement.otherwise)
                    }
                    is While -> walk(statement.body)
                    is Switch -> {
                        statement.cases.forEach { walk(it.body) }
                        walk(statement.default)
                    }
                    else -> Unit
                }
            }
        }
        walk(body)
        return wanted - placed
    }

    /**
     * Every block in address order, each labelled, with its terminator written
     * out as an explicit jump. This is the shape the code generator reproduces
     * exactly, so it is the safe answer when structuring does not fit.
     */
    private fun unstructured(): List<Stmt> {
        val out = ArrayList<Stmt>()
        for (id in cfg.blocks.indices) {
            val block = lifted[id] ?: continue
            out.add(Label("L$id"))
            out.addAll(block.statements)
            when {
                block.returns != null -> out.add(Return(block.returns))
                block.condition != null -> {
                    val target = branchTarget(id)
                    if (target >= 0) out.add(If(block.condition, listOf(Goto("L$target")), emptyList()))
                }
                block.switchSubject != null -> out.add(switchAsJumps(id, block))
                endsWithGoto(id) -> {
                    val target = branchTarget(id)
                    if (target >= 0) out.add(Goto("L$target"))
                }
            }
        }
        return out
    }

    /** A switch rendered as one jump per key, with no structured arms. */
    private fun switchAsJumps(id: Int, block: LiftedBlock): Switch {
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1
        val cases = table.mapNotNull { case ->
            val target = cfg.blockStartingAt(last + case.offset + 1)?.id ?: return@mapNotNull null
            SwitchCase(listOf(case.key), listOf(Goto("L$target")))
        }
        return Switch(block.switchSubject!!, cases, emptyList(), block.switchTable, defaultFirst = true)
    }

    private fun returnTypes(): List<Cs2Type> {
        val signature = script.returnSignature ?: ReturnSignature.EMPTY
        return List(signature.ints) { Cs2Type.INT } +
            List(signature.strings) { Cs2Type.STRING } +
            List(signature.longs) { Cs2Type.LONG }
    }

    /** Lifts every block, propagating the operand stacks along control-flow edges. */
    private fun liftAll() {
        entryStacks[0] = StackState()
        val worklist = ArrayDeque(listOf(0))
        while (worklist.isNotEmpty()) {
            val id = worklist.removeFirst()
            if (lifted[id] != null) continue
            val entry = entryStacks[id] ?: StackState()
            val block = lifter.lift(cfg.blocks[id], entry)
            lifted[id] = block
            for (successor in cfg.blocks[id].successors) {
                if (entryStacks[successor] == null) entryStacks[successor] = block.exitStack
                if (lifted[successor] == null) worklist.addLast(successor)
            }
        }

        // Scripts carry a trailing default `return` that both arms of an
        // if/else can make unreachable. Dropping it would change the bytecode,
        // so unreached blocks are lifted from an empty stack as well.
        for (id in cfg.blocks.indices) {
            if (lifted[id] != null) continue
            lifted[id] = try {
                lifter.lift(cfg.blocks[id], StackState())
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Block a conditional branch jumps to, or -1. */
    private fun branchTarget(id: Int): Int {
        val block = cfg.blocks[id]
        val targets = Cs2Cfg.jumpTargets(script, block.end - 1)
        return targets.firstOrNull()?.let { cfg.blockStartingAt(it)?.id } ?: -1
    }

    private fun endsWithGoto(id: Int): Boolean =
        Cs2Cfg.isUnconditionalBranch(script[cfg.blocks[id].end - 1].op)

    private fun endsWithCondition(id: Int): Boolean = lifted[id]?.condition != null

    private fun endsWithSwitch(id: Int): Boolean = lifted[id]?.switchSubject != null

    private fun endsWithReturn(id: Int): Boolean = lifted[id]?.returns != null

    /**
     * Structures blocks `[from, to)` into a statement list.
     *
     * `breakTargets` holds blocks that a `break` would land on, so a jump to one
     * becomes a `break` rather than a goto.
     */
    private fun structureRange(
        from: Int,
        to: Int,
        breakTargets: Set<Int>,
        /** A jump here is this region's natural exit and emits nothing. */
        exitTarget: Int = -1,
        /** A jump here goes back to a loop test. */
        continueTarget: Int = -1,
    ): List<Stmt> {
        val out = ArrayList<Stmt>()
        var id = from
        while (id < to) {
            val block = lifted[id]
            if (block == null) {
                id++
                continue
            }
            if (id in labelled) out.add(Label("L$id"))
            out.addAll(block.statements)

            when {
                endsWithReturn(id) -> {
                    out.add(Return(block.returns!!))
                    id++
                }

                endsWithSwitch(id) -> {
                    val consumed = structureSwitch(id, to, out, breakTargets)
                    id = consumed
                }

                endsWithCondition(id) -> {
                    val consumed = structureConditional(id, to, out, breakTargets)
                    id = consumed
                }

                endsWithGoto(id) -> {
                    val target = branchTarget(id)
                    val last = nextLiveBlock(id + 1, to) < 0
                    when {
                        target < 0 -> Unit
                        target == exitTarget || target == to -> Unit // the region ends here anyway
                        target == continueTarget -> if (!last) out.add(Continue())
                        target in breakTargets -> if (!last) out.add(Break())
                        else -> {
                            labelled.add(target)
                            out.add(Goto("L$target"))
                        }
                    }
                    id++
                }

                else -> id++
            }
        }
        return out
    }

    /**
     * Handles a block ending in a conditional branch.
     *
     * Three shapes are recognised, in order: a `while` loop (the region the
     * branch skips ends by jumping back here), an `if`/`else` (the then-region
     * ends by jumping past the else-region), and a bare `if`.
     */
    /**
     * A conditional branch reduced to "run [regionStart, skipTarget) when
     * [condition] holds".
     */
    private data class Guard(val condition: Condition, val regionStart: Int, val skipTarget: Int)

    /**
     * CS2 nearly always emits a conditional branch that hops over a single
     * unconditional one:
     * ```
     * i     BRANCH_LESS_THAN +1   ; enter the body when the test passes
     * i+1   BRANCH exit           ; otherwise skip it
     * i+2   <body>
     * ```
     * Read literally that is an `if` with an empty arm, so the trampoline is
     * folded away first and the condition kept as written. Without it the branch
     * skips the region directly and the sense is inverted.
     */
    private fun guardAt(id: Int): Guard? {
        val first = lifted[id]?.condition ?: return null
        val taken = branchTarget(id)
        if (taken < 0) return null

        // `a && b`: every term hops over its own branch to the shared skip
        // target, so the terms run one after another and all must pass.
        if (taken == id + 2 && id + 1 < cfg.blocks.size && isTrampoline(id + 1)) {
            val skip = branchTarget(id + 1)
            if (skip < 0) return null
            val terms = ArrayList<Condition>()
            terms.add(first)
            var cursor = id + 2
            while (cursor + 1 < cfg.blocks.size) {
                val block = lifted[cursor] ?: break
                val next = block.condition ?: break
                // A term is only part of the chain if the block does nothing
                // else; otherwise those statements belong inside the body.
                if (block.statements.isNotEmpty()) break
                if (branchTarget(cursor) != cursor + 2) break
                if (!isTrampoline(cursor + 1) || branchTarget(cursor + 1) != skip) break
                terms.add(next)
                cursor += 2
            }
            val condition = if (terms.size == 1) first else AndAll(terms)
            return Guard(condition, cursor, skip)
        }

        // `a || b`: every term branches straight into the shared body, and a
        // lone branch past them all skips it.
        val terms = ArrayList<Condition>()
        var cursor = id
        while (cursor < cfg.blocks.size) {
            val block = lifted[cursor] ?: break
            val term = block.condition ?: break
            if (cursor != id && block.statements.isNotEmpty()) break
            if (branchTarget(cursor) != taken) break
            terms.add(term)
            cursor++
        }
        if (terms.size > 1 && cursor < cfg.blocks.size && isTrampoline(cursor) && cursor + 1 == taken) {
            val skip = branchTarget(cursor)
            if (skip >= 0) return Guard(OrAny(terms), taken, skip)
        }

        // `a || (b && c)`: this term jumps into the body and whatever follows
        // guards the same body. Recursing picks up the nested chain.
        if (terms.size == 1) {
            val rest = guardAt(id + 1)
            if (rest != null && rest.regionStart == taken) {
                return Guard(OrAny(listOf(first, rest.condition)), taken, rest.skipTarget)
            }
        }

        // Otherwise the branch simply skips the region, so the region runs when
        // the condition does *not* hold.
        return Guard(first.negate(), id + 1, taken)
    }

    /**
     * True when a block does nothing but jump *and* nothing else jumps to it.
     * Folding one away that another branch targets would leave that branch with
     * nowhere to land.
     */
    private fun isTrampoline(id: Int): Boolean {
        val block = cfg.blocks[id]
        return block.end - block.start == 1 &&
            Cs2Cfg.isUnconditionalBranch(script[block.start].op) &&
            lifted[id]?.statements.isNullOrEmpty() &&
            block.predecessors.size <= 1
    }

    private fun structureConditional(
        id: Int,
        to: Int,
        out: MutableList<Stmt>,
        breakTargets: Set<Int>,
    ): Int {
        val guard = guardAt(id)
        if (guard == null) return id + 1
        val (condition, regionStart, skipTarget) = guard

        // A jump out of this region cannot be structured here. Fall back to the
        // single comparison rather than the chain: consuming a chain's blocks
        // would leave the enclosing region to emit them a second time.
        if (skipTarget <= id || skipTarget > to || regionStart >= to) {
            val plain = lifted[id]!!.condition!!
            val target = branchTarget(id)
            labelled.add(target.coerceIn(0, cfg.blocks.size - 1))
            out.add(If(plain, listOf(Goto("L$target")), emptyList()))
            return id + 1
        }

        val lastOfRegion = skipTarget - 1
        if (lastOfRegion >= regionStart && endsWithGoto(lastOfRegion)) {
            val jump = branchTarget(lastOfRegion)

            // while: the body jumps back to the test.
            if (jump == id) {
                val body = structureRange(
                    regionStart, skipTarget, breakTargets + skipTarget, continueTarget = id,
                )
                out.add(While(condition, body))
                return skipTarget
            }

            // if/else: the then-region jumps past the else-region.
            if (jump in (skipTarget + 1)..to) {
                val then = structureRange(regionStart, skipTarget, breakTargets, exitTarget = jump)
                val otherwise = structureRange(skipTarget, jump, breakTargets, exitTarget = jump)
                out.add(If(condition, then, otherwise))
                return jump
            }
        }

        val then = structureRange(regionStart, skipTarget, breakTargets, exitTarget = skipTarget)
        out.add(If(condition, then, emptyList()))
        return skipTarget
    }

    /**
     * Handles a `SWITCH`. Case bodies run in address order and each ends by
     * jumping to the block after the statement, which becomes the `break`.
     */
    /** The next block in `[from, to)` that was reached, or -1. */
    private fun nextLiveBlock(from: Int, to: Int): Int {
        for (id in from until to) if (lifted[id] != null) return id
        return -1
    }

    private fun structureSwitch(
        id: Int,
        to: Int,
        out: MutableList<Stmt>,
        breakTargets: Set<Int>,
    ): Int {
        val block = lifted[id]!!
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1

        // Group keys that jump to the same block into one case.
        val byTarget = LinkedHashMap<Int, MutableList<Int>>()
        for (case in table) {
            val targetBlock = cfg.blockStartingAt(last + case.offset + 1)?.id ?: continue
            byTarget.getOrPut(targetBlock) { ArrayList() }.add(case.key)
        }

        // The block right after the switch jumps to the default arm, which sits
        // past every case body.
        val afterSwitch = id + 1
        val jumpsToDefault = afterSwitch < cfg.blocks.size && isTrampoline(afterSwitch)
        val defaultTarget = if (jumpsToDefault) branchTarget(afterSwitch) else afterSwitch

        val caseStarts = byTarget.keys.sorted()
        if (caseStarts.isEmpty()) {
            out.add(Switch(block.switchSubject!!, emptyList(), emptyList(), block.switchTable))
            return afterSwitch
        }

        val end = switchEnd(caseStarts, afterSwitch, defaultTarget, to)

        // The default arm runs from where the no-match path lands until the next
        // case body starts, or to the join point when none follows it. Bounding
        // it this way keeps it from swallowing case bodies whichever layout the
        // compiler used.
        val defaultStop = minOf(end, caseStarts.firstOrNull { it > defaultTarget } ?: end)
        val hasDefault = defaultTarget < defaultStop

        val boundaries = (caseStarts + listOfNotNull(defaultTarget.takeIf { hasDefault }) + end)
            .filter { it <= end }
            .distinct()
            .sorted()

        val cases = ArrayList<SwitchCase>()
        for (start in caseStarts) {
            if (start >= end) continue
            val stop = boundaries.firstOrNull { it > start } ?: end
            cases.add(SwitchCase(byTarget.getValue(start), structureRange(start, stop, breakTargets + end, exitTarget = end)))
        }
        val default =
            if (hasDefault) structureRange(defaultTarget, defaultStop, breakTargets + end, exitTarget = end)
            else emptyList()

        out.add(Switch(block.switchSubject!!, cases, default, block.switchTable, !jumpsToDefault))
        return end
    }

    /**
     * Where a `switch` ends.
     *
     * The no-match jump lands either on a real default arm or straight on the
     * join point. Only a case body's *own* trailing jump can tell them apart:
     * if one jumps past the no-match target, that target is a default arm.
     * Branches from nested `if`s inside a case body must not be mistaken for it.
     */
    private fun switchEnd(caseStarts: List<Int>, afterSwitch: Int, defaultTarget: Int, to: Int): Int {
        var end = to
        var found = false
        for ((index, start) in caseStarts.withIndex()) {
            val boundary = caseStarts.getOrNull(index + 1) ?: defaultTarget
            val tail = boundary - 1
            if (tail < start || tail == afterSwitch || tail >= cfg.blocks.size) continue
            if (!endsWithGoto(tail)) continue
            val target = branchTarget(tail)
            if (target > defaultTarget && target < end) {
                end = target
                found = true
            }
        }
        return if (found) end else defaultTarget
    }
}
