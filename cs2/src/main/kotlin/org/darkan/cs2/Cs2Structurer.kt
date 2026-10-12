package org.darkan.cs2

import org.darkan.cs2.ir.Break
import org.darkan.cs2.ir.AndAll
import org.darkan.cs2.ir.Compare
import org.darkan.cs2.ir.Condition
import org.darkan.cs2.ir.OrAny
import org.darkan.cs2.ir.negate
import org.darkan.cs2.ir.Continue
import org.darkan.cs2.ir.Cs2Function
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.Goto
import org.darkan.cs2.ir.If
import org.darkan.cs2.ir.IntConst
import org.darkan.cs2.ir.Label
import org.darkan.cs2.ir.LocalAssign
import org.darkan.cs2.ir.LongConst
import org.darkan.cs2.ir.Return
import org.darkan.cs2.ir.StrConst
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.ir.Stmt
import org.darkan.cs2.ir.Switch
import org.darkan.cs2.ir.SwitchCase
import org.darkan.cs2.ir.While

class Cs2Structurer(
    private val script: Cs2Script,
    private val scriptId: Int,
    private val context: Cs2Context,
    private val faithful: Boolean = false,
) {
    private val cfg = Cs2Cfg.build(script)
    private val lifter = Cs2Lifter(script, context)
    private val lifted = arrayOfNulls<LiftedBlock>(cfg.blocks.size)
    private val entryStacks = arrayOfNulls<StackState>(cfg.blocks.size)
    private val labelled = HashSet<Int>()

    private val reached = BooleanArray(cfg.blocks.size)

    private var dropUnreachable = false

    val deadCode = ArrayList<Cs2DeadCode>()

    val eliminated = ArrayList<LocalAssign>()

    fun structure(): Cs2Function {
        liftAll()
        if (faithful) return function(renumberLabels(dropUnusedLabels(unstructured())))

        dropUnreachable = true
        structureRange(0, cfg.blocks.size)
        val structured = structureRange(0, cfg.blocks.size)

        if (danglingLabels(structured).isNotEmpty()) {
            dropUnreachable = false
            return function(renumberLabels(dropUnusedLabels(unstructured())))
        }

        reportUnreachable()
        val cleanup = Cs2Cleanup(
            cfg, lifted, reached,
            script.intArgsCount, script.stringArgsCount, script.longArgsCount,
        )
        val body = cleanup.clean(structured, returnsValues = returnTypesFrom(structured).isNotEmpty())
        deadCode.addAll(cleanup.findings)
        eliminated.addAll(cleanup.eliminated)
        return function(renumberLabels(dropUnusedLabels(body)))
    }

    private fun reportUnreachable() {
        var id = 0
        while (id < cfg.blocks.size) {
            if (reached[id] || isImplicitReturn(id)) {
                id++
                continue
            }
            val start = id
            val statements = ArrayList<Stmt>()
            while (id < cfg.blocks.size && !reached[id] && !isImplicitReturn(id)) {
                lifted[id]?.let { statements.addAll(blockStatements(script, cfg, id, it)) }
                id++
            }
            if (statements.isNotEmpty() && statements.all { it is Goto || it is Label }) {
                continue
            }
            val range = cfg.blocks[start].start until cfg.blocks[id - 1].end
            val note = if (statements.isEmpty()) "could not be lifted" else null
            deadCode.add(
                Cs2DeadCode(
                    Cs2DeadCodeKind.UNREACHABLE, statements, note = note,
                    instructions = range,
                ),
            )
        }
    }

    private fun isImplicitReturn(id: Int): Boolean {
        if (id != cfg.blocks.lastIndex || reached[id]) return false
        val block = lifted[id] ?: return false
        if (block.statements.isNotEmpty()) return false
        val values = block.returns ?: return false
        return values.all { it is IntConst || it is StrConst || it is LongConst || it is TypedConst }
    }

    private fun live(id: Int): Boolean =
        lifted[id] != null && (!dropUnreachable || reached[id])

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

    private fun dropUnusedLabels(body: List<Stmt>): List<Stmt> {
        val wanted = HashSet<String>()
        fun collect(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Goto -> wanted.add(statement.label)
                    is If -> {
                        collect(statement.then)
                        collect(statement.otherwise)
                    }
                    is While -> collect(statement.body)
                    is Switch -> {
                        statement.cases.forEach { collect(it.body) }
                        collect(statement.default)
                    }
                    else -> Unit
                }
            }
        }
        collect(body)

        fun prune(statements: List<Stmt>): List<Stmt> = statements.mapNotNull { statement ->
            when (statement) {
                is Label -> statement.takeIf { it.name in wanted }
                is If -> If(statement.condition, prune(statement.then), prune(statement.otherwise))
                is While -> While(statement.condition, prune(statement.body))
                is Switch -> statement.copy(
                    cases = statement.cases.map { SwitchCase(it.keys, prune(it.body)) },
                    default = prune(statement.default),
                )
                else -> statement
            }
        }
        return prune(body)
    }

    private fun renumberLabels(body: List<Stmt>): List<Stmt> {
        val names = LinkedHashMap<String, String>()
        fun collect(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Label -> names.getOrPut(statement.name) { "L${names.size}" }
                    is If -> {
                        collect(statement.then)
                        collect(statement.otherwise)
                    }
                    is While -> collect(statement.body)
                    is Switch -> {
                        statement.cases.forEach { collect(it.body) }
                        collect(statement.default)
                    }
                    else -> Unit
                }
            }
        }
        collect(body)
        if (names.isEmpty()) return body

        fun rename(statements: List<Stmt>): List<Stmt> = statements.map { statement ->
            when (statement) {
                is Label -> Label(names[statement.name] ?: statement.name)
                is Goto -> Goto(names[statement.label] ?: statement.label)
                is If -> If(statement.condition, rename(statement.then), rename(statement.otherwise))
                is While -> While(statement.condition, rename(statement.body))
                is Switch -> statement.copy(
                    cases = statement.cases.map { SwitchCase(it.keys, rename(it.body)) },
                    default = rename(statement.default),
                )
                else -> statement
            }
        }
        return rename(body)
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

    private fun switchAsJumps(id: Int, block: LiftedBlock): Switch {
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1
        val cases = table.mapNotNull { case ->
            val target = cfg.blockStartingAt(last + case.offset + 1)?.id ?: return@mapNotNull null
            SwitchCase(listOf(case.key), listOf(Goto("L$target")))
        }
        return Switch(block.switchSubject!!, cases, emptyList(), block.switchTable)
    }

    private fun returnTypes(): List<Cs2Type> {
        val signature = script.returnSignature ?: ReturnSignature.EMPTY
        return List(signature.ints) { Cs2Type.INT } +
            List(signature.strings) { Cs2Type.STRING } +
            List(signature.longs) { Cs2Type.LONG }
    }

    private fun liftAll() {
        entryStacks[0] = StackState()
        val worklist = ArrayDeque(listOf(0))
        while (worklist.isNotEmpty()) {
            val id = worklist.removeFirst()
            reached[id] = true
            if (lifted[id] != null) continue
            val entry = entryStacks[id] ?: StackState()
            val block = lifter.lift(cfg.blocks[id], entry)
            lifted[id] = block
            for (successor in cfg.blocks[id].successors) {
                if (entryStacks[successor] == null) entryStacks[successor] = block.exitStack
                if (lifted[successor] == null) worklist.addLast(successor)
            }
        }

        for (id in cfg.blocks.indices) {
            if (lifted[id] != null) continue
            lifted[id] = try {
                lifter.lift(cfg.blocks[id], StackState())
            } catch (e: Exception) {
                null
            }
        }
    }

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

    private fun structureRange(
        from: Int,
        to: Int,
        breakAt: Int = -1,
        exits: Set<Int> = emptySet(),
        continueTarget: Int = -1,
    ): List<Stmt> {
        val out = ArrayList<Stmt>()
        var id = from
        var fallsOut = false
        while (id < to) {
            val block = lifted[id]
            if (block == null || !live(id)) {
                id++
                continue
            }
            if (id in labelled) out.add(Label("L$id"))
            if (!rotatesInto(id, to)) out.addAll(block.statements)
            fallsOut = true

            when {
                endsWithReturn(id) -> {
                    out.add(Return(block.returns!!))
                    fallsOut = false
                    id++
                }

                endsWithSwitch(id) -> {
                    id = structureSwitch(id, to, out, breakAt, exits, continueTarget)
                }

                endsWithCondition(id) -> {
                    id = structureConditional(id, to, out, breakAt, exits, continueTarget)
                }

                endsWithGoto(id) -> {
                    val target = branchTarget(id)
                    val last = nextLiveBlock(id + 1, to) < 0
                    when {
                        target < 0 -> Unit
                        target == continueTarget -> if (!last) out.add(Continue())
                        target in exits -> if (!last) {
                            labelled.add(target)
                            out.add(Goto("L$target"))
                        }
                        target == breakAt -> out.add(Break())
                        else -> {
                            labelled.add(target)
                            out.add(Goto("L$target"))
                        }
                    }
                    fallsOut = false
                    id++
                }

                else -> id++
            }
        }
        if (fallsOut && !alwaysExits(out) && id == to && to !in exits &&
            to in cfg.blocks.indices && live(to)
        ) {
            when {
                to == continueTarget -> Unit
                to == breakAt -> out.add(Break())
                else -> {
                    labelled.add(to)
                    out.add(Goto("L$to"))
                }
            }
        }
        return out
    }

    private fun joined(join: Int, to: Int, exits: Set<Int>): Set<Int> = when {
        join < to -> setOf(join)
        to in exits -> exits
        else -> setOf(to)
    }

    private fun carried(join: Int, to: Int, exits: Set<Int>): Set<Int> =
        if (join == to) exits - join else emptySet()

    private fun alwaysExits(statements: List<Stmt>): Boolean = when (val last = statements.lastOrNull()) {
        is Return, is Goto, is Break, is Continue -> true
        is If -> last.otherwise.isNotEmpty() && alwaysExits(last.then) && alwaysExits(last.otherwise)
        is Switch -> last.default.isNotEmpty() && alwaysExits(last.default) &&
            last.cases.all { alwaysExits(it.body) && it.body.none { statement -> statement is Break } }
        else -> false
    }

    private data class Guard(val condition: Condition, val regionStart: Int, val skipTarget: Int)

    private fun guardAt(id: Int): Guard? = graphGuard(id) ?: singleGuard(id)

    private sealed interface Truth

    private object Always : Truth

    private object Never : Truth

    private class Holds(val condition: Condition) : Truth

    private fun graphGuard(id: Int): Guard? {
        if (lifted[id]?.condition == null || branchTarget(id) < 0) return null
        val nodes = LinkedHashSet<Int>()
        nodes.add(id)
        var best: Guard? = null
        var cut = id + 1
        while (true) {
            val sinks = sinksOf(nodes) ?: break
            if (sinks.size == 2 && sinks.min() == cut) {
                val skip = sinks.max()
                val truth = evaluate(id, cut, skip, nodes, HashMap()) as? Holds
                if (truth != null) best = Guard(truth.condition, cut, skip)
            }
            if (cut >= cfg.blocks.size || !absorbable(cut)) break
            if (cfg.blocks[cut].predecessors.any { it !in nodes }) break
            nodes.add(cut)
            cut++
        }
        return best
    }

    private fun sinksOf(nodes: Set<Int>): Set<Int>? {
        val out = LinkedHashSet<Int>()
        for (node in nodes) {
            for (next in successorsOf(node) ?: return null) {
                if (next < 0) return null
                if (next !in nodes) out.add(next)
            }
        }
        return out
    }

    private fun successorsOf(id: Int): List<Int>? {
        val block = lifted[id] ?: return null
        val target = branchTarget(id)
        if (target < 0) return null
        if (block.condition == null) return listOf(target)
        val fall = id + 1
        if (fall !in cfg.blocks.indices) return null
        return listOf(target, fall)
    }

    private fun absorbable(id: Int): Boolean {
        val block = lifted[id] ?: return false
        if (block.statements.isNotEmpty() || block.returns != null || block.switchSubject != null) {
            return false
        }
        return block.condition != null || isLoneBranch(id)
    }

    private fun isLoneBranch(id: Int): Boolean {
        val block = cfg.blocks[id]
        return block.end - block.start == 1 && Cs2Cfg.isUnconditionalBranch(script[block.start].op)
    }

    private fun evaluate(
        node: Int,
        body: Int,
        skip: Int,
        nodes: Set<Int>,
        memo: HashMap<Int, Truth?>,
    ): Truth? {
        if (node == body) return Always
        if (node == skip) return Never
        if (node !in nodes) return null
        memo[node]?.let { return it }
        if (memo.containsKey(node)) return null
        memo[node] = null

        val block = lifted[node]!!
        val test = block.condition
        val result = if (test == null) {
            evaluate(branchTarget(node), body, skip, nodes, memo)
        } else {
            val taken = evaluate(branchTarget(node), body, skip, nodes, memo)
            val fall = evaluate(node + 1, body, skip, nodes, memo)
            if (taken == null || fall == null) null else combine(test, taken, fall)
        }
        memo[node] = result
        return result
    }

    private fun combine(test: Condition, whenTrue: Truth, whenFalse: Truth): Truth? {
        if (whenTrue is Always && whenFalse is Always) return null
        if (whenTrue is Never && whenFalse is Never) return null
        if (whenTrue is Always && whenFalse is Never) return Holds(test)
        if (whenTrue is Never && whenFalse is Always) return Holds(test.negate())
        if (whenTrue is Always) return Holds(anyOf(test, condition(whenFalse)))
        if (whenFalse is Never) return Holds(allOf(test, condition(whenTrue)))
        if (whenTrue is Never) return Holds(allOf(test.negate(), condition(whenFalse)))
        if (whenFalse is Always) return Holds(anyOf(test.negate(), condition(whenTrue)))
        val taken = condition(whenTrue)
        val fall = condition(whenFalse)
        shareTail(test, taken, fall)?.let { return Holds(it) }
        shareTail(test.negate(), fall, taken)?.let { return Holds(it) }
        shareGuard(test, taken, fall)?.let { return Holds(it) }
        shareGuard(test.negate(), fall, taken)?.let { return Holds(it) }
        return null
    }

    private fun condition(truth: Truth): Condition = (truth as Holds).condition

    private fun shareTail(test: Condition, longer: Condition, shorter: Condition): Condition? {
        if (longer !is OrAny) return null
        val tail = if (shorter is OrAny) shorter.terms else listOf(shorter)
        if (tail.size >= longer.terms.size) return null
        if (longer.terms.takeLast(tail.size) != tail) return null
        val head = longer.terms.dropLast(tail.size)
        val guarded = allOf(test, if (head.size == 1) head.first() else OrAny(head))
        return OrAny(listOf(guarded) + tail)
    }

    private fun shareGuard(test: Condition, shorter: Condition, longer: Condition): Condition? {
        if (longer !is AndAll) return null
        val tail = if (shorter is AndAll) shorter.terms else listOf(shorter)
        if (tail.size >= longer.terms.size) return null
        if (longer.terms.takeLast(tail.size) != tail) return null
        val head = longer.terms.dropLast(tail.size)
        val guard = anyOf(test, if (head.size == 1) head.first() else AndAll(head))
        return AndAll(listOf(guard) + tail)
    }

    private fun allOf(left: Condition, right: Condition): Condition {
        val terms = ArrayList<Condition>()
        if (left is AndAll) terms.addAll(left.terms) else terms.add(left)
        if (right is AndAll) terms.addAll(right.terms) else terms.add(right)
        return AndAll(terms)
    }

    private fun anyOf(left: Condition, right: Condition): Condition {
        val terms = ArrayList<Condition>()
        if (left is OrAny) terms.addAll(left.terms) else terms.add(left)
        if (right is OrAny) terms.addAll(right.terms) else terms.add(right)
        return OrAny(terms)
    }

    private fun singleGuard(id: Int): Guard? {
        val first = lifted[id]?.condition ?: return null
        val taken = branchTarget(id)
        if (taken < 0) return null
        if (taken == id + 1) return Guard(first, taken, taken)
        return Guard(first.negate(), id + 1, taken)
    }

    private fun rotatesInto(id: Int, to: Int): Boolean {
        if (!endsWithCondition(id) || lifted[id]?.statements.isNullOrEmpty()) return false
        val skipTarget = guardAt(id)?.skipTarget ?: return false
        if (skipTarget <= id || skipTarget > to) return false
        val tail = skipTarget - 1
        return tail > id && endsWithGoto(tail) && branchTarget(tail) == id
    }

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
        breakAt: Int,
        exits: Set<Int>,
        continueTarget: Int,
    ): Int {
        val guard = guardAt(id) ?: return id + 1
        val (condition, regionStart, skipTarget) = guard

        if (regionStart == skipTarget) {
            out.add(If(condition, emptyList(), emptyList()))
            return skipTarget
        }

        if (continueTarget >= 0 && skipTarget == breakAt && skipTarget == to &&
            regionStart in (id + 1)..to
        ) {
            out.add(If(condition.negate(), listOf(Break()), emptyList()))
            return regionStart
        }

        if (skipTarget > to && skipTarget in exits && regionStart in (id + 1)..to) {
            val then = structureRange(regionStart, to, breakAt, exits, continueTarget)
            out.add(If(condition, then, emptyList()))
            return to
        }

        if (skipTarget <= id || skipTarget > to || regionStart >= to) {
            val target = branchTarget(id)
            val terms = ArrayList<Condition>()
            var cursor = id
            while (cursor < to) {
                val block = lifted[cursor] ?: break
                val term = block.condition ?: break
                if (cursor != id && block.statements.isNotEmpty()) break
                if (branchTarget(cursor) != target) break
                terms.add(term)
                cursor++
            }
            labelled.add(target.coerceIn(0, cfg.blocks.size - 1))
            val test = if (terms.size == 1) terms.first() else OrAny(terms)
            out.add(If(test, listOf(Goto("L$target")), emptyList()))
            return cursor
        }

        val lastOfRegion = skipTarget - 1
        if (lastOfRegion >= regionStart && endsWithGoto(lastOfRegion)) {
            val jump = branchTarget(lastOfRegion)

            if (jump == id) {
                val body = structureRange(regionStart, skipTarget, skipTarget, continueTarget = id)
                if (rotatesInto(id, to)) {
                    val header = lifted[id]!!.statements +
                        If(condition.negate(), listOf(Break()), emptyList())
                    out.add(While(null, header + body))
                } else {
                    out.add(While(condition, body))
                }
                return skipTarget
            }

            if (jump in (skipTarget + 1)..to) {
                val join = joined(jump, to, exits)
                val then = structureRange(regionStart, skipTarget, breakAt, join, continueTarget)
                val otherwise = structureRange(skipTarget, jump, breakAt, join, continueTarget)
                out.add(If(condition, then, otherwise))
                return jump
            }
        }

        val then = structureRange(
            regionStart, skipTarget, breakAt, joined(skipTarget, to, exits), continueTarget,
        )
        out.add(If(condition, then, emptyList()))
        return skipTarget
    }

    private fun nextLiveBlock(from: Int, to: Int): Int {
        for (id in from until to) if (live(id)) return id
        return -1
    }

    private fun structureSwitch(
        id: Int,
        to: Int,
        out: MutableList<Stmt>,
        breakAt: Int,
        exits: Set<Int>,
        continueTarget: Int,
    ): Int {
        val block = lifted[id]!!
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1

        val byTarget = LinkedHashMap<Int, MutableList<Int>>()
        for (case in table) {
            val targetBlock = cfg.blockStartingAt(last + case.offset + 1)?.id ?: continue
            byTarget.getOrPut(targetBlock) { ArrayList() }.add(case.key)
        }

        val afterSwitch = id + 1
        val jumpsToDefault = afterSwitch < cfg.blocks.size && isTrampoline(afterSwitch)
        val defaultTarget = if (jumpsToDefault) branchTarget(afterSwitch) else afterSwitch

        val caseStarts = byTarget.keys.sorted()
        if (caseStarts.isEmpty()) {
            out.add(Switch(block.switchSubject!!, emptyList(), emptyList(), block.switchTable))
            return afterSwitch
        }

        var end = switchEnd(caseStarts, afterSwitch, defaultTarget, to)

        if (!jumpsToDefault) {
            val tail = caseStarts.min() - 1
            if (tail >= afterSwitch && endsWithGoto(tail)) {
                val target = branchTarget(tail)
                if (target > caseStarts.max() && target <= to) end = target
            }
        }

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
            val arm = structureRange(start, stop, end, carried(end, to, exits), continueTarget)
            cases.add(SwitchCase(byTarget.getValue(start), arm))
        }
        var default =
            if (hasDefault) structureRange(defaultTarget, defaultStop, end, carried(end, to, exits), continueTarget)
            else emptyList()

        val meaningful =
            if (default.isEmpty()) cases.filter { it.body.isNotEmpty() } else cases.toList()
        cases.clear()
        cases.addAll(meaningful)

        if (default.isEmpty() && cases.isNotEmpty() && cases.all { it.body.singleOrNull() is Goto }) {
            default = listOf(Break())
        }

        out.add(Switch(block.switchSubject!!, cases, default, block.switchTable))
        return end
    }

    private fun switchEnd(caseStarts: List<Int>, afterSwitch: Int, defaultTarget: Int, to: Int): Int {
        var end = to
        var found = false
        for ((index, start) in caseStarts.withIndex()) {
            val boundary = caseStarts.getOrNull(index + 1) ?: defaultTarget
            val tail = boundary - 1
            if (tail < start || tail == afterSwitch || tail >= cfg.blocks.size) continue
            if (!endsWithGoto(tail)) continue
            val target = branchTarget(tail)
            if (target > defaultTarget && target <= end) {
                end = target
                found = true
            }
        }
        return if (found) end else defaultTarget
    }
}
