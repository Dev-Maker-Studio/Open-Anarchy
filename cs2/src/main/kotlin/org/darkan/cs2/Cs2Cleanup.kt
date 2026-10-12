package org.darkan.cs2

import java.util.Collections
import java.util.IdentityHashMap
import org.darkan.cs2.ir.AndAll
import org.darkan.cs2.ir.ArrayAssign
import org.darkan.cs2.ir.ArrayDefine
import org.darkan.cs2.ir.ArrayRef
import org.darkan.cs2.ir.Binary
import org.darkan.cs2.ir.Callback
import org.darkan.cs2.ir.Compare
import org.darkan.cs2.ir.Condition
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.DestructureAssign
import org.darkan.cs2.ir.Expr
import org.darkan.cs2.ir.ExprStmt
import org.darkan.cs2.ir.Goto
import org.darkan.cs2.ir.If
import org.darkan.cs2.ir.IntConst
import org.darkan.cs2.ir.Join
import org.darkan.cs2.ir.Label
import org.darkan.cs2.ir.LocalAssign
import org.darkan.cs2.ir.LocalRef
import org.darkan.cs2.ir.LongConst
import org.darkan.cs2.ir.MultiAssign
import org.darkan.cs2.ir.OpCall
import org.darkan.cs2.ir.OrAny
import org.darkan.cs2.ir.ParallelAssign
import org.darkan.cs2.ir.Return
import org.darkan.cs2.ir.ScriptCall
import org.darkan.cs2.ir.Spread
import org.darkan.cs2.ir.Stmt
import org.darkan.cs2.ir.StrConst
import org.darkan.cs2.ir.Switch
import org.darkan.cs2.ir.SwitchCase
import org.darkan.cs2.ir.TempRef
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.ir.VarAssign
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.While
import org.darkan.cs2.ir.negate

enum class Cs2DeadCodeKind(val label: String) {
    UNREACHABLE("unreachable"),
    DEAD_STORE("dead store"),
    UNUSED_RESULT("unused result"),
    CONSTANT_CONDITION("constant condition"),
    EMPTY_BRANCH("empty branch"),
}

class Cs2DeadCode(
    val kind: Cs2DeadCodeKind,
    val statements: List<Stmt> = emptyList(),
    val condition: Condition? = null,
    val note: String? = null,
    val instructions: IntRange? = null,
)

class Cs2Cleanup(
    private val cfg: Cs2Cfg,
    private val lifted: Array<LiftedBlock?>,
    private val reached: BooleanArray,
    private val intArgs: Int = 0,
    private val stringArgs: Int = 0,
    private val longArgs: Int = 0,
) {
    val findings = ArrayList<Cs2DeadCode>()

    val eliminated = ArrayList<LocalAssign>()

    private val deadTargets: MutableMap<Stmt, List<Int>> = IdentityHashMap()

    private val removed: Stmt = ExprStmt(IntConst(0))

    fun clean(body: List<Stmt>, returnsValues: Boolean): List<Stmt> {
        analyseLiveness()
        var out = foldSpreads(body)
        out = foldDestructures(out)
        out = eliminateDefInitialisers(out)
        reportDeadStores(out)
        out = foldEmptyGotos(out)
        out = dropRedundantJumps(out)
        out = normaliseBranches(out)
        reportConditions(out)
        out = markDeclarations(out)
        if (!returnsValues) out = dropTrailingVoidReturn(out)
        return out
    }


    private fun key(slot: Int, type: Cs2Type) = (slot shl 2) or type.ordinal

    private fun usesOf(statement: Stmt, into: MutableSet<Int>) {
        when (statement) {
            is ExprStmt -> reads(statement.call, into)
            is LocalAssign -> reads(statement.value, into)
            is VarAssign -> reads(statement.value, into)
            is ArrayAssign -> {
                reads(statement.index, into)
                reads(statement.value, into)
            }
            is ArrayDefine -> reads(statement.size, into)
            is ParallelAssign -> statement.values.forEach { reads(it, into) }
            is MultiAssign -> reads(statement.call, into)
            is DestructureAssign -> reads(statement.call, into)
            is Return -> statement.values.forEach { reads(it, into) }
            is If -> reads(statement.condition, into)
            is While -> statement.condition?.let { reads(it, into) }
            is Switch -> reads(statement.subject, into)
            else -> Unit
        }
    }

    private fun defsOf(statement: Stmt, into: MutableSet<Int>) {
        when (statement) {
            is LocalAssign -> into.add(key(statement.slot, statement.type))
            is ParallelAssign -> statement.targets.forEach {
                if (it is LocalRef) into.add(key(it.slot, it.type))
            }
            is DestructureAssign -> statement.targets.forEach {
                if (it is LocalRef) into.add(key(it.slot, it.type))
            }
            else -> Unit
        }
    }

    private fun reads(condition: Condition, into: MutableSet<Int>) {
        when (condition) {
            is Compare -> {
                reads(condition.left, into)
                reads(condition.right, into)
            }
            is AndAll -> condition.terms.forEach { reads(it, into) }
            is OrAny -> condition.terms.forEach { reads(it, into) }
        }
    }

    private fun reads(value: Expr, into: MutableSet<Int>) {
        when (value) {
            is LocalRef -> into.add(key(value.slot, value.type))
            is ArrayRef -> reads(value.index, into)
            is Binary -> {
                reads(value.left, into)
                reads(value.right, into)
            }
            is Join -> value.parts.forEach { reads(it, into) }
            is OpCall -> value.args.forEach { reads(it, into) }
            is ScriptCall -> value.args.forEach { reads(it, into) }
            is Callback -> {
                value.args.forEach { reads(it, into) }
                value.triggers.forEach { reads(it, into) }
            }
            else -> Unit
        }
    }

    private fun terminatorUses(block: LiftedBlock): Set<Int> {
        val out = HashSet<Int>()
        block.condition?.let { reads(it, out) }
        block.switchSubject?.let { reads(it, out) }
        block.returns?.forEach { reads(it, out) }
        return out
    }

    private fun analyseLiveness() {
        val count = cfg.blocks.size
        val upwardUses = Array(count) { HashSet<Int>() }
        val killed = Array(count) { HashSet<Int>() }
        val liveIn = Array(count) { HashSet<Int>() }
        val liveOut = Array(count) { HashSet<Int>() }

        for (id in 0 until count) {
            val block = lifted[id]?.takeIf { reached[id] } ?: continue
            val uses = upwardUses[id]
            val defs = killed[id]
            for (statement in block.statements) {
                val read = HashSet<Int>()
                usesOf(statement, read)
                read.forEach { if (it !in defs) uses.add(it) }
                val written = HashSet<Int>()
                defsOf(statement, written)
                defs.addAll(written)
            }
            terminatorUses(block).forEach { if (it !in defs) uses.add(it) }
        }

        var changed = true
        while (changed) {
            changed = false
            for (id in count - 1 downTo 0) {
                if (!reached[id] || lifted[id] == null) continue
                val out = HashSet<Int>()
                for (successor in cfg.blocks[id].successors) {
                    if (reached[successor] && lifted[successor] != null) out.addAll(liveIn[successor])
                }
                if (out != liveOut[id]) {
                    liveOut[id] = out
                    changed = true
                }
                val entering = HashSet(upwardUses[id])
                liveOut[id].forEach { if (it !in killed[id]) entering.add(it) }
                if (entering != liveIn[id]) {
                    liveIn[id] = entering
                    changed = true
                }
            }
        }

        for (id in 0 until count) {
            val block = lifted[id]?.takeIf { reached[id] } ?: continue
            val live = HashSet(liveOut[id])
            live.addAll(terminatorUses(block))
            for (statement in block.statements.asReversed()) {
                val defs = HashSet<Int>()
                defsOf(statement, defs)
                if (defs.isNotEmpty()) {
                    val dead = deadPositions(statement, live)
                    if (dead.isNotEmpty()) deadTargets[statement] = dead
                    live.removeAll(defs)
                }
                val uses = HashSet<Int>()
                usesOf(statement, uses)
                live.addAll(uses)
            }
        }
    }

    private fun deadPositions(statement: Stmt, live: Set<Int>): List<Int> = when (statement) {
        is LocalAssign -> if (key(statement.slot, statement.type) in live) emptyList() else listOf(0)
        is ParallelAssign -> statement.targets.indices.filter { index ->
            val target = statement.targets[index]
            target is LocalRef && key(target.slot, target.type) !in live
        }
        else -> emptyList()
    }


    private fun walk(statements: List<Stmt>, depth: Int, visit: (Stmt, Int) -> Unit) {
        for (statement in statements) {
            visit(statement, depth)
            when (statement) {
                is If -> {
                    walk(statement.then, depth + 1, visit)
                    walk(statement.otherwise, depth + 1, visit)
                }
                is While -> walk(statement.body, depth + 1, visit)
                is Switch -> {
                    statement.cases.forEach { walk(it.body, depth + 1, visit) }
                    walk(statement.default, depth + 1, visit)
                }
                else -> Unit
            }
        }
    }

    private fun rewrite(statements: List<Stmt>, transform: (List<Stmt>) -> List<Stmt>): List<Stmt> =
        transform(statements).map { statement ->
            when (statement) {
                is If -> statement.copy(
                    then = rewrite(statement.then, transform),
                    otherwise = rewrite(statement.otherwise, transform),
                )
                is While -> statement.copy(body = rewrite(statement.body, transform))
                is Switch -> statement.copy(
                    cases = statement.cases.map { SwitchCase(it.keys, rewrite(it.body, transform)) },
                    default = rewrite(statement.default, transform),
                )
                else -> statement
            }
        }

    private fun isConstant(value: Expr) =
        value is IntConst || value is StrConst || value is LongConst || value is TypedConst


    private fun eliminateDefInitialisers(body: List<Stmt>): List<Stmt> {
        val candidates: MutableSet<Stmt> = Collections.newSetFromMap(IdentityHashMap())
        val introduces = HashMap<Int, LocalAssign>()
        val seen = HashSet<Int>()
        walk(body, 0) { statement, _ ->
            val touched = HashSet<Int>()
            usesOf(statement, touched)
            val defs = HashSet<Int>()
            defsOf(statement, defs)
            if (statement is LocalAssign && isConstant(statement.value) && !isParameter(statement)) {
                val slot = key(statement.slot, statement.type)
                if (slot !in seen && deadTargets[statement]?.contains(0) == true) {
                    candidates.add(statement)
                    introduces[slot] = statement
                }
            }
            seen.addAll(touched)
            seen.addAll(defs)
        }
        if (candidates.isEmpty()) return body

        val next = HashMap<Int, Pair<Stmt, Int>>()
        walk(body, 0) { statement, depth ->
            val defs = HashSet<Int>()
            defsOf(statement, defs)
            for (slot in defs) {
                if (introduces[slot] === statement || slot in next) continue
                if (introduces.containsKey(slot)) next[slot] = statement to depth
            }
        }
        val drop: MutableSet<Stmt> = Collections.newSetFromMap(IdentityHashMap())
        for ((slot, initialiser) in introduces) {
            val (successor, depth) = next[slot] ?: continue
            if (depth != 0) continue
            val hosts = when (successor) {
                is LocalAssign -> true
                is DestructureAssign -> successor.targets.all { target ->
                    target is LocalRef && key(target.slot, target.type).let {
                        it == slot || introduces.containsKey(it)
                    }
                }
                else -> false
            }
            if (hosts) {
                drop.add(initialiser)
                eliminated.add(initialiser)
            }
        }
        if (drop.isEmpty()) return body
        return rewrite(body) { list -> list.filterNot { it in drop } }
    }

    private fun reportDeadStores(body: List<Stmt>) {
        val stored = HashSet<Int>()
        walk(body, 0) { statement, _ ->
            val defs = HashSet<Int>()
            defsOf(statement, defs)
            val initialiser = statement is LocalAssign && isConstant(statement.value) &&
                key(statement.slot, statement.type) !in stored
            stored.addAll(defs)
            val dead = deadTargets[statement] ?: return@walk
            if (initialiser) return@walk
            val kind =
                if (statement is DestructureAssign) Cs2DeadCodeKind.UNUSED_RESULT
                else Cs2DeadCodeKind.DEAD_STORE
            val note = when (statement) {
                is ParallelAssign -> "targets " + dead.joinToString(", ") { describe(statement.targets[it]) }
                is DestructureAssign ->
                    if (dead.size == statement.targets.size) "no result is read"
                    else "targets " + dead.joinToString(", ") { describe(statement.targets[it]) }
                else -> null
            }
            findings.add(Cs2DeadCode(kind, listOf(statement), note = note))
        }
    }

    private fun describe(target: Expr): String = when (target) {
        is LocalRef -> "${target.type.name.lowercase()} slot ${target.slot}"
        is VarRef -> "${target.space} ${target.id}"
        else -> target.toString()
    }


    private fun foldDestructures(body: List<Stmt>): List<Stmt> = rewrite(body) { list ->
        val out = ArrayList<Stmt>(list.size)
        var index = 0
        while (index < list.size) {
            val statement = list[index]
            if (statement !is MultiAssign) {
                out.add(statement)
                index++
                continue
            }
            val folded = foldAt(list, index, statement)
            if (folded == null) {
                out.add(statement)
                index++
            } else {
                out.add(folded.first)
                index += folded.second
            }
        }
        out
    }

    private fun foldSpreads(body: List<Stmt>): List<Stmt> = rewrite(body) { list ->
        val out = ArrayList<Stmt>(list.size)
        var index = 0
        while (index < list.size) {
            val statement = list[index]
            val consumer = list.getOrNull(index + 1)
            val spliced = if (statement is MultiAssign && consumer != null) {
                spliceInto(consumer, statement)
            } else {
                null
            }
            if (spliced == null) {
                out.add(statement)
                index++
            } else {
                deadTargets.remove(consumer)?.let { deadTargets[spliced] = it }
                out.add(spliced)
                index += 2
            }
        }
        out
    }

    private fun spliceInto(consumer: Stmt, call: MultiAssign): Stmt? {
        val names = call.names
        if (names.isEmpty()) return null
        var replaced = false
        fun splice(value: Expr): Expr {
            val args = when (value) {
                is OpCall -> value.args
                is ScriptCall -> value.args
                else -> null
            }
            if (args != null) {
                val start = args.indexOfFirst { it is TempRef && it == names.first() }
                val run = start >= 0 && start + names.size <= args.size &&
                    names.indices.all { args[start + it] == names[it] }
                if (run && !replaced) {
                    replaced = true
                    val rewritten = args.subList(0, start) + Spread(call.call) +
                        args.subList(start + names.size, args.size)
                    return when (value) {
                        is OpCall -> value.copy(args = rewritten.map(::splice))
                        is ScriptCall -> value.copy(args = rewritten.map(::splice))
                        else -> value
                    }
                }
            }
            return when (value) {
                is OpCall -> value.copy(args = value.args.map(::splice))
                is ScriptCall -> value.copy(args = value.args.map(::splice))
                is Binary -> value.copy(left = splice(value.left), right = splice(value.right))
                is Join -> value.copy(parts = value.parts.map(::splice))
                is ArrayRef -> value.copy(index = splice(value.index))
                else -> value
            }
        }
        fun spliceCondition(condition: Condition): Condition = when (condition) {
            is Compare -> condition.copy(left = splice(condition.left), right = splice(condition.right))
            is AndAll -> AndAll(condition.terms.map(::spliceCondition))
            is OrAny -> OrAny(condition.terms.map(::spliceCondition))
        }
        val rewritten: Stmt = when (consumer) {
            is ExprStmt -> ExprStmt(splice(consumer.call))
            is LocalAssign -> consumer.copy(value = splice(consumer.value))
            is VarAssign -> consumer.copy(value = splice(consumer.value))
            is ArrayAssign -> consumer.copy(index = splice(consumer.index), value = splice(consumer.value))
            is ArrayDefine -> consumer.copy(size = splice(consumer.size))
            is ParallelAssign -> consumer.copy(values = consumer.values.map(::splice))
            is Return -> Return(consumer.values.map(::splice))
            is If -> If(spliceCondition(consumer.condition), consumer.then, consumer.otherwise)
            is While -> While(consumer.condition?.let(::spliceCondition), consumer.body)
            is Switch -> consumer.copy(subject = splice(consumer.subject))
            else -> return null
        }
        if (!replaced) return null
        val remaining = HashSet<TempRef>()
        collectTemps(rewritten, remaining)
        if (names.any { it in remaining }) return null
        return rewritten
    }

    private fun collectTemps(statement: Stmt, into: MutableSet<TempRef>) {
        val values = ArrayList<Expr>()
        when (statement) {
            is ExprStmt -> values.add(statement.call)
            is LocalAssign -> values.add(statement.value)
            is VarAssign -> values.add(statement.value)
            is ArrayAssign -> {
                values.add(statement.index)
                values.add(statement.value)
            }
            is ArrayDefine -> values.add(statement.size)
            is ParallelAssign -> values.addAll(statement.values)
            is Return -> values.addAll(statement.values)
            is Switch -> values.add(statement.subject)
            is If -> conditionValues(statement.condition, values)
            is While -> statement.condition?.let { conditionValues(it, values) }
            else -> Unit
        }
        values.forEach { collectTemps(it, into) }
    }

    private fun conditionValues(condition: Condition, into: MutableList<Expr>) {
        when (condition) {
            is Compare -> {
                into.add(condition.left)
                into.add(condition.right)
            }
            is AndAll -> condition.terms.forEach { conditionValues(it, into) }
            is OrAny -> condition.terms.forEach { conditionValues(it, into) }
        }
    }

    private fun collectTemps(value: Expr, into: MutableSet<TempRef>) {
        when (value) {
            is TempRef -> into.add(value)
            is OpCall -> value.args.forEach { collectTemps(it, into) }
            is ScriptCall -> value.args.forEach { collectTemps(it, into) }
            is Spread -> collectTemps(value.call, into)
            is Binary -> {
                collectTemps(value.left, into)
                collectTemps(value.right, into)
            }
            is Join -> value.parts.forEach { collectTemps(it, into) }
            is ArrayRef -> collectTemps(value.index, into)
            is Callback -> {
                value.args.forEach { collectTemps(it, into) }
                value.triggers.forEach { collectTemps(it, into) }
            }
            else -> Unit
        }
    }

    private fun foldAt(list: List<Stmt>, index: Int, call: MultiAssign): Pair<Stmt, Int>? {
        val wanted = call.names.toMutableList()
        val targets = HashMap<String, Expr>()
        var cursor = index + 1
        while (wanted.isNotEmpty() && cursor < list.size) {
            val consumed = consumers(list[cursor]) ?: return null
            for ((target, temp) in consumed) {
                if (!wanted.remove(temp)) return null
                targets[temp.name] = target
            }
            cursor++
        }
        if (wanted.isNotEmpty()) return null
        val ordered = call.names.map { targets.getValue(it.name) }
        val folded = DestructureAssign(ordered, call.call)
        val dead = ordered.indices.filter { position ->
            val target = ordered[position]
            target is LocalRef && deadStore(list, index + 1, cursor, target)
        }
        if (dead.isNotEmpty()) deadTargets[folded] = dead
        return folded to (cursor - index)
    }

    private fun deadStore(list: List<Stmt>, from: Int, to: Int, target: LocalRef): Boolean {
        for (index in from until to) {
            val statement = list[index]
            val dead = deadTargets[statement] ?: continue
            val hit = when (statement) {
                is LocalAssign -> statement.slot == target.slot && statement.type == target.type
                is ParallelAssign -> dead.any { statement.targets[it] == target }
                else -> false
            }
            if (hit) return true
        }
        return false
    }

    private fun consumers(statement: Stmt): List<Pair<Expr, TempRef>>? = when (statement) {
        is LocalAssign -> (statement.value as? TempRef)
            ?.let { listOf(LocalRef(statement.slot, statement.type) as Expr to it) }
        is VarAssign -> (statement.value as? TempRef)?.let { listOf(statement.target as Expr to it) }
        is ParallelAssign -> {
            val temps = statement.values.map { it as? TempRef }
            if (temps.any { it == null }) null
            else statement.targets.indices.map { statement.targets[it] to temps[it]!! }
        }
        else -> null
    }


    private fun foldEmptyGotos(body: List<Stmt>): List<Stmt> = rewrite(body) { list ->
        val out = ArrayList<Stmt>(list.size)
        for ((index, statement) in list.withIndex()) {
            val conditional = (statement as? If)?.takeIf { it.otherwise.isEmpty() }
            val jump = conditional?.then?.singleOrNull() as? Goto
            val next = list.getOrNull(index + 1)
            if (jump != null && next is Label && next.name == jump.label) {
                out.add(If(conditional.condition, emptyList(), emptyList()))
            } else {
                out.add(statement)
            }
        }
        out
    }

    private fun dropRedundantJumps(body: List<Stmt>): List<Stmt> = rewrite(body) { list ->
        val out = ArrayList(list)
        for (index in out.indices) {
            val label = (out[index] as? Label)?.name ?: continue
            if (index == 0) continue
            val before = out[index - 1]
            if (before is Goto && before.label == label) {
                out[index - 1] = removed
            } else {
                out[index - 1] = prunedTails(before, label)
            }
        }
        out.filterNot { it === removed }
    }

    private fun prunedTails(statement: Stmt, label: String): Stmt = when (statement) {
        is If -> If(statement.condition, prunedTail(statement.then, label), prunedTail(statement.otherwise, label))
        is Switch -> statement.copy(
            cases = statement.cases.map { SwitchCase(it.keys, prunedTail(it.body, label)) },
            default = prunedTail(statement.default, label),
        )
        else -> statement
    }

    private fun prunedTail(statements: List<Stmt>, label: String): List<Stmt> {
        val last = statements.lastOrNull() ?: return statements
        if (last is Goto && last.label == label) return statements.dropLast(1)
        return statements.dropLast(1) + prunedTails(last, label)
    }

    private fun normaliseBranches(body: List<Stmt>): List<Stmt> = rewrite(body) { list ->
        list.map { statement ->
            if (statement !is If) return@map statement
            when {
                statement.then.isEmpty() && statement.otherwise.isEmpty() -> {
                    findings.add(
                        Cs2DeadCode(
                            Cs2DeadCodeKind.EMPTY_BRANCH, condition = statement.condition,
                            note = "both arms are empty",
                        ),
                    )
                    statement
                }
                statement.then.isEmpty() -> {
                    findings.add(
                        Cs2DeadCode(
                            Cs2DeadCodeKind.EMPTY_BRANCH, condition = statement.condition,
                            note = "empty then-arm; written as the negation",
                        ),
                    )
                    If(statement.condition.negate(), statement.otherwise, emptyList())
                }
                else -> statement
            }
        }
    }

    private fun reportConditions(body: List<Stmt>) {
        fun inspect(condition: Condition) {
            when (condition) {
                is Compare -> {
                    if (!isConstant(condition.left) || !isConstant(condition.right)) return
                    findings.add(
                        Cs2DeadCode(
                            Cs2DeadCodeKind.CONSTANT_CONDITION, condition = condition,
                            note = "always ${fold(condition)}",
                        ),
                    )
                }
                is AndAll -> condition.terms.forEach { inspect(it) }
                is OrAny -> condition.terms.forEach { inspect(it) }
            }
        }
        walk(body, 0) { statement, _ ->
            when (statement) {
                is If -> inspect(statement.condition)
                is While -> statement.condition?.let { inspect(it) }
                else -> Unit
            }
        }
    }

    private fun fold(condition: Compare): String {
        val left = constantValue(condition.left)
        val right = constantValue(condition.right)
        if (left == null || right == null) return "constant"
        val result = when (condition.symbol) {
            "==" -> left == right
            "!=" -> left != right
            "<" -> left < right
            ">" -> left > right
            "<=" -> left <= right
            ">=" -> left >= right
            else -> return "constant"
        }
        return result.toString()
    }

    private fun constantValue(value: Expr): Long? = when (value) {
        is IntConst -> value.value.toLong()
        is TypedConst -> value.value.toLong()
        is LongConst -> value.value
        else -> null
    }


    private fun markDeclarations(body: List<Stmt>): List<Stmt> {
        val seen = HashSet<Int>()
        val out = ArrayList<Stmt>(body.size)
        for (statement in body) {
            val here = HashSet<Int>()
            usesOf(statement, here)
            out.add(declared(statement, seen, here))
            walk(listOf(statement), 0) { nested, _ ->
                usesOf(nested, seen)
                defsOf(nested, seen)
            }
        }
        return out
    }

    private fun isParameter(statement: LocalAssign): Boolean = statement.slot < when (statement.type) {
        Cs2Type.INT -> intArgs
        Cs2Type.STRING -> stringArgs
        Cs2Type.LONG -> longArgs
    }

    private fun isParameter(target: LocalRef): Boolean = target.slot < when (target.type) {
        Cs2Type.INT -> intArgs
        Cs2Type.STRING -> stringArgs
        Cs2Type.LONG -> longArgs
    }

    private fun declared(statement: Stmt, seen: Set<Int>, uses: Set<Int>): Stmt = when {
        statement is LocalAssign && isParameter(statement) -> statement
        statement is LocalAssign -> {
            val slot = key(statement.slot, statement.type)
            if (slot in seen || slot in uses) statement else statement.copy(declare = true)
        }
        statement is DestructureAssign -> {
            val fresh = statement.targets.all { target ->
                target is LocalRef && !isParameter(target) &&
                    key(target.slot, target.type).let { it !in seen && it !in uses }
            }
            val distinct = statement.targets.distinct().size == statement.targets.size
            if (fresh && distinct) statement.copy(declare = true) else statement
        }
        else -> statement
    }

    private fun dropTrailingVoidReturn(body: List<Stmt>): List<Stmt> {
        val last = body.lastOrNull()
        if (last is Return && last.values.isEmpty()) return body.dropLast(1)
        return body
    }
}

fun blockStatements(
    script: Cs2Script,
    cfg: Cs2Cfg,
    id: Int,
    block: LiftedBlock,
): List<Stmt> {
    val out = ArrayList<Stmt>(block.statements)
    when {
        block.returns != null -> out.add(Return(block.returns))
        block.condition != null -> {
            val target = Cs2Cfg.jumpTargets(script, cfg.blocks[id].end - 1)
                .firstOrNull()?.let { cfg.blockStartingAt(it)?.id }
            if (target != null) out.add(If(block.condition, listOf(Goto("L$target")), emptyList()))
        }
        block.switchSubject != null -> {
            val table = script.switchTables.getOrNull(block.switchTable).orEmpty()
            val last = cfg.blocks[id].end - 1
            val cases = table.mapNotNull { case ->
                val target = cfg.blockStartingAt(last + case.offset + 1)?.id ?: return@mapNotNull null
                SwitchCase(listOf(case.key), listOf(Goto("L$target")))
            }
            out.add(Switch(block.switchSubject, cases, emptyList(), block.switchTable))
        }
        Cs2Cfg.isUnconditionalBranch(script[cfg.blocks[id].end - 1].op) -> {
            val target = Cs2Cfg.jumpTargets(script, cfg.blocks[id].end - 1)
                .firstOrNull()?.let { cfg.blockStartingAt(it)?.id }
            if (target != null) out.add(Goto("L$target"))
        }
    }
    return out
}
