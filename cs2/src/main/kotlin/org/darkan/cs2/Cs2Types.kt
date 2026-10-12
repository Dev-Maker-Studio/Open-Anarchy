package org.darkan.cs2

import org.darkan.cs2.ir.AndAll
import org.darkan.cs2.ir.ArrayAssign
import org.darkan.cs2.ir.ArrayDefine
import org.darkan.cs2.ir.ArrayRef
import org.darkan.cs2.ir.Binary
import org.darkan.cs2.ir.Callback
import org.darkan.cs2.ir.Compare
import org.darkan.cs2.ir.Condition
import org.darkan.cs2.ir.Cs2Function
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.DestructureAssign
import org.darkan.cs2.ir.Expr
import org.darkan.cs2.ir.ExprStmt
import org.darkan.cs2.ir.If
import org.darkan.cs2.ir.IntConst
import org.darkan.cs2.ir.Join
import org.darkan.cs2.ir.LocalAssign
import org.darkan.cs2.ir.LocalRef
import org.darkan.cs2.ir.MultiAssign
import org.darkan.cs2.ir.OpCall
import org.darkan.cs2.ir.OrAny
import org.darkan.cs2.ir.ParallelAssign
import org.darkan.cs2.ir.Return
import org.darkan.cs2.ir.ScriptCall
import org.darkan.cs2.ir.Spread
import org.darkan.cs2.ir.Stmt
import org.darkan.cs2.ir.Switch
import org.darkan.cs2.ir.SwitchCase
import org.darkan.cs2.ir.TempRef
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.ir.VarAssign
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace
import org.darkan.cs2.ir.While

object Cs2TypeChars {

    val KINDS: Map<Char, ArgType> = linkedMapOf(
        'i' to ArgType.INT,
        '1' to ArgType.BOOLEAN,
        'o' to ArgType.OBJ,
        'n' to ArgType.NPC,
        'l' to ArgType.LOC,
        'I' to ArgType.COMPONENT,
        'c' to ArgType.COORD,
        'd' to ArgType.GRAPHIC,
        'g' to ArgType.ENUM,
        'A' to ArgType.SEQ,
        't' to ArgType.SPOTANIM,
        'S' to ArgType.STAT,
        'C' to ArgType.COLOUR,
        'J' to ArgType.STRUCT,
        'M' to ArgType.MIDI,
        'P' to ArgType.SOUND,
        'v' to ArgType.INV,
        'm' to ArgType.MODEL,
        'a' to ArgType.INTERFACE,
        'µ' to ArgType.MAPELEMENT,
    )

    val NAMES: Map<Char, String> = linkedMapOf(
        'i' to "type_int",
        '1' to "type_boolean",
        's' to "type_string",
        'o' to "type_obj",
        'n' to "type_npc",
        'l' to "type_loc",
        'I' to "type_component",
        'c' to "type_coord",
        'd' to "type_graphic",
        'g' to "type_enum",
        'A' to "type_seq",
        't' to "type_spotanim",
        'S' to "type_stat",
        'C' to "type_colour",
        'J' to "type_struct",
        'M' to "type_midi",
        'P' to "type_sound",
        'v' to "type_inv",
        'm' to "type_model",
        'a' to "type_interface",
        'µ' to "type_mapelement",
    )

    private val byName: Map<String, Char> = NAMES.entries.associate { (char, name) -> name to char }

    fun charNamed(name: String): Char? = byName[name]

    fun nameOf(code: Int): String? = NAMES[code.toChar()]
}

private class TypeCodeRule(
    val typedArgs: Map<Int, Int>,
    val resultChar: Int? = null,
)

private val TYPE_CODE_RULES: Map<Cs2Op, TypeCodeRule> = mapOf(
    Cs2Op.ENUM to TypeCodeRule(mapOf(3 to 0), resultChar = 1),
    Cs2Op.ENUM_GETREVERSECOUNT to TypeCodeRule(mapOf(2 to 0)),
    Cs2Op.ENUM_GETREVERSEINDEX to TypeCodeRule(mapOf(3 to 0), resultChar = 1),
    Cs2Op.ENUM_GETREVERSEINDEX_STRING to TypeCodeRule(emptyMap(), resultChar = 0),
    Cs2Op.ENUM_HASOUTPUT to TypeCodeRule(emptyMap()),
)

class Cs2Types private constructor(
    private val context: Cs2Context,
    private val functions: List<Cs2Function>,
) {

    private val global = Store()

    private var trustedChars: Map<Char, ArgType> = emptyMap()

    private var learnedResults: Map<String, ArgType> = emptyMap()

    private var learnedArguments: Map<String, ArgType> = emptyMap()

    private val resultEvidence = HashMap<String, MutableMap<ArgType, Votes>>()
    private val argumentEvidence = HashMap<String, MutableMap<ArgType, Votes>>()

    private class Votes {
        var uses = 0
        val scripts = HashSet<Int>()

        val examples = ArrayList<String>()

        var direct = 0

        val origins = HashSet<String>()
    }

    private fun describe(symbol: String): String {
        val parts = symbol.split(":")
        return when (parts[0]) {
            "p" -> "script ${parts[1]} parameter ${parts[2]}"
            "l" -> "script ${parts[1]} local ${parts[2]}"
            "r" -> "script ${parts[1]} return ${parts[2]}"
            "v" -> "${parts[1]} ${parts[2]}"
            "o" -> "${parts[1]} result"
            "t" -> "script ${parts[1]} ${parts[2]}"
            else -> symbol
        }
    }

    private val charEvidence = HashMap<Char, MutableMap<ArgType, Votes>>()

    fun evidenceFor(slot: String): List<String> {
        val votes = argumentEvidence["a:$slot"] ?: resultEvidence["o:$slot"] ?: return emptyList()
        val out = ArrayList<String>()
        for ((kind, seen) in votes.entries.sortedByDescending { it.value.uses }) {
            out.add(
                "$kind: ${seen.uses} uses in ${seen.scripts.size} scripts, " +
                    "${seen.direct} typed on the spot, ${seen.origins.size} distinct origins",
            )
            seen.examples.forEach { out.add("    $it") }
        }
        return out
    }

    fun varKind(space: VarSpace, id: Int): ArgType? =
        if (tracked(space)) global.kindOf(varSymbol(space, id)) else null

    val argumentKinds: Map<String, ArgType>
        get() = learnedArguments.mapKeys { it.key.removePrefix("a:") }

    val resultKinds: Map<String, ArgType>
        get() = learnedResults.mapKeys { it.key.removePrefix("o:") }

    private val bySource = LinkedHashMap<String, Int>()
    private val byKind = LinkedHashMap<ArgType, Int>()


    private class Store {
        val kinds = HashMap<String, ArgType>()
        val conflicts = HashSet<String>()

        val origins = HashMap<String, String>()

        val quantities = HashSet<String>()
        var changed = false

        fun quantity(symbol: String): Boolean {
            if (!quantities.add(symbol)) return false
            val had = kinds.remove(symbol) != null
            origins.remove(symbol)
            conflicts.add(symbol)
            changed = true
            return had
        }

        fun assign(symbol: String, kind: ArgType, why: String = "") {
            if (kind == ArgType.INT || symbol in conflicts) return
            val existing = kinds[symbol]
            when {
                existing == null -> {
                    kinds[symbol] = kind
                    if (why.isNotEmpty()) origins[symbol] = why
                    changed = true
                }
                existing != kind -> {
                    kinds.remove(symbol)
                    origins.remove(symbol)
                    conflicts.add(symbol)
                    changed = true
                }
            }
        }

        fun kindOf(symbol: String): ArgType? = kinds[symbol]
    }


    private fun analyse(log: (String) -> Unit) {
        settle(log, "without type characters")
        validateChars()
        global.kinds.clear()
        global.conflicts.clear()
        trustedChars = Cs2TypeChars.KINDS.filterKeys { char ->
            Cs2TypeChars.KINDS[char] != ArgType.INT &&
                charEvidence[char].orEmpty().none { (kind, _) -> kind != Cs2TypeChars.KINDS[char] }
        }
        settle(log, "with ${trustedChars.size} of ${Cs2TypeChars.KINDS.size} type characters")

        learn()
        log(
            "learned ${learnedResults.size} opcode result kinds and " +
                "${learnedArguments.size} opcode argument slots",
        )
        settle(log, "with the learned opcode kinds")
    }

    private fun settle(log: (String) -> Unit, what: String) {
        var round = 0
        while (round < ROUNDS) {
            round++
            global.changed = false
            for (function in functions) Walker(function, null, rewrite = false).run()
            if (!global.changed) break
        }
        log("$what: settled in $round rounds, ${global.kinds.size} symbols typed")
    }

    private fun validateChars() {
        charEvidence.clear()
        for (function in functions) Walker(function, null, rewrite = false, chars = true).run()
    }

    private fun learn() {
        resultEvidence.clear()
        argumentEvidence.clear()
        for (function in functions) Walker(function, null, rewrite = false, learning = true).run()
        learnedResults = resultEvidence
            .mapNotNull { (slot, votes) -> agreed(votes, direct = false)?.let { slot to it } }.toMap()
        learnedArguments = argumentEvidence
            .mapNotNull { (slot, votes) -> agreed(votes, direct = true)?.let { slot to it } }.toMap()
    }

    private fun agreed(votes: Map<ArgType, Votes>, direct: Boolean): ArgType? {
        if (votes.size != 1) return null
        val (kind, seen) = votes.entries.first()
        if (kind == ArgType.INT) return null
        if (seen.uses < LEARNING_USES || seen.scripts.size < LEARNING_SCRIPTS) return null
        if (!direct) return kind
        return if (seen.direct > 0) kind else null
    }


    private fun paramSymbol(script: Int, slot: Int) = "p:$script:$slot"

    private fun localSymbol(script: Int, slot: Int) = "l:$script:$slot"

    private fun returnSymbol(script: Int, index: Int) = "r:$script:$index"

    private fun varSymbol(space: VarSpace, id: Int) = "v:$space:$id"

    private fun resultSymbol(op: String, index: Int) = "o:$op:$index"

    private fun argumentSlot(op: String, index: Int) = "a:$op:$index"

    private fun tempSymbol(script: Int, name: String) = "t:$script:$name"

    private fun tracked(space: VarSpace) =
        space == VarSpace.VARP || space == VarSpace.VARBIT || space == VarSpace.VARC


    fun apply(function: Cs2Function): Cs2Function {
        val locals = Store()
        var round = 0
        while (round < LOCAL_ROUNDS) {
            round++
            locals.changed = false
            Walker(function, locals, rewrite = false).run()
            if (!locals.changed) break
        }
        val walker = Walker(function, locals, rewrite = true)
        val body = walker.run()
        val paramKinds = (0 until function.intArgs).map {
            global.kindOf(paramSymbol(function.scriptId, it)) ?: ArgType.INT
        }
        val localKinds = HashMap<Int, ArgType>()
        for (slot in function.intArgs until function.intLocals) {
            locals.kindOf(localSymbol(function.scriptId, slot))?.let { localKinds[slot] = it }
        }
        val returnKinds = function.returns.mapIndexed { index, type ->
            if (type != Cs2Type.INT) ArgType.INT
            else global.kindOf(returnSymbol(function.scriptId, index)) ?: ArgType.INT
        }
        return Cs2Function(
            scriptId = function.scriptId,
            name = function.name,
            intArgs = function.intArgs,
            stringArgs = function.stringArgs,
            longArgs = function.longArgs,
            intLocals = function.intLocals,
            stringLocals = function.stringLocals,
            longLocals = function.longLocals,
            body = body,
            returns = function.returns,
            argumentOrder = function.argumentOrder,
            intParamKinds = paramKinds,
            intLocalKinds = localKinds,
            returnKinds = returnKinds,
        )
    }


    class Report(
        val symbols: Map<String, Int>,
        val constantsByKind: Map<ArgType, Int>,
        val constantsBySource: Map<String, Int>,
        val chars: List<CharCheck>,
        val learnedResults: Map<String, ArgType>,
        val learnedArguments: Map<String, ArgType>,
    )

    class CharCheck(
        val char: Char,
        val kind: ArgType,
        val agreed: Int,
        val conflicts: Map<ArgType, Int>,
        val kept: Boolean,
    )

    fun describe(): Report {
        bySource.clear()
        byKind.clear()
        val locals = functions.sumOf { apply(it).intLocalKinds.size }
        val symbols = linkedMapOf(
            "parameters" to global.kinds.keys.count { it.startsWith("p:") },
            "return slots" to global.kinds.keys.count { it.startsWith("r:") },
            "variables" to global.kinds.keys.count { it.startsWith("v:") },
            "opcode results" to global.kinds.keys.count { it.startsWith("o:") },
            "locals" to locals,
            "ordered, so not ids" to global.quantities.size,
        )
        val chars = Cs2TypeChars.KINDS.map { (char, kind) ->
            val votes = charEvidence[char].orEmpty()
            CharCheck(
                char = char,
                kind = kind,
                agreed = votes[kind]?.uses ?: 0,
                conflicts = votes.filterKeys { it != kind }.mapValues { it.value.uses },
                kept = trustedChars.containsKey(char),
            )
        }
        return Report(symbols, byKind.toMap(), bySource.toMap(), chars, learnedResults, learnedArguments)
    }

    private fun counted(kind: ArgType, source: String) {
        byKind[kind] = (byKind[kind] ?: 0) + 1
        bySource[source] = (bySource[source] ?: 0) + 1
    }


    private inner class Walker(
        private val fn: Cs2Function,
        private val locals: Store?,
        private val rewrite: Boolean,
        private val chars: Boolean = false,
        private val learning: Boolean = false,
    ) {
        fun run(): List<Stmt> = fn.body.map { statement(it) }


        private fun statement(statement: Stmt): Stmt = when (statement) {
            is LocalAssign -> {
                val symbol = localOf(statement.slot, statement.type)
                unify(symbol, symbolOf(statement.value))
                statement.copy(value = expr(statement.value, kindOf(symbol), "local"))
            }

            is VarAssign -> {
                val symbol = symbolOf(statement.target)
                unify(symbol, symbolOf(statement.value))
                statement.copy(value = expr(statement.value, kindOf(symbol), "var"))
            }

            is ParallelAssign -> {
                val values = statement.values.mapIndexed { index, value ->
                    val symbol = symbolOf(statement.targets.getOrNull(index) ?: value)
                    unify(symbol, symbolOf(value))
                    expr(value, kindOf(symbol), "assignment")
                }
                statement.copy(values = values, targets = statement.targets.map { expr(it, null, "") })
            }

            is DestructureAssign -> {
                results(statement.call, statement.targets)
                statement.copy(call = expr(statement.call, null, ""))
            }

            is MultiAssign -> {
                results(statement.call, statement.names)
                statement.copy(call = expr(statement.call, null, ""))
            }

            is Return -> Return(
                statement.values.mapIndexed { index, value ->
                    if (value.type != Cs2Type.INT) value else {
                        val symbol = returnSymbol(fn.scriptId, index)
                        unify(symbol, symbolOf(value))
                        expr(value, kindOf(symbol), "return")
                    }
                },
            )

            is ExprStmt -> ExprStmt(expr(statement.call, null, ""))
            is ArrayAssign -> statement.copy(
                index = expr(statement.index, null, ""),
                value = expr(statement.value, null, ""),
            )
            is ArrayDefine -> statement.copy(size = expr(statement.size, null, ""))
            is If -> If(condition(statement.condition), statement.then.map { statement(it) }, statement.otherwise.map { statement(it) })
            is While -> While(statement.condition?.let { condition(it) }, statement.body.map { statement(it) })
            is Switch -> {
                val subject = expr(statement.subject, null, "")
                val kind = kindOf(symbolOf(statement.subject)) ?: ArgType.INT
                if (rewrite && kind != ArgType.INT) {
                    statement.cases.forEach { case -> repeat(case.keys.size) { counted(kind, "switch subject") } }
                }
                Switch(
                    subject,
                    statement.cases.map { SwitchCase(it.keys, it.body.map { inner -> statement(inner) }) },
                    statement.default.map { statement(it) },
                    statement.table,
                    keyKind = kind,
                )
            }
            else -> statement
        }

        private fun results(call: Expr, targets: List<Expr>) {
            targets.forEachIndexed { index, target ->
                if (target.type != Cs2Type.INT) return@forEachIndexed
                val produced = when (call) {
                    is ScriptCall -> returnSymbol(call.scriptId, index)
                    is OpCall -> resultSymbol(call.op.name, index)
                    else -> null
                }
                unify(symbolOf(target), produced)
            }
        }

        private fun condition(condition: Condition): Condition = when (condition) {
            is Compare -> {
                val left = symbolOf(condition.left)
                val right = symbolOf(condition.right)
                if (condition.symbol in ORDERINGS) {
                    ordered(left, condition.right)
                    ordered(right, condition.left)
                }
                unify(left, right)
                val kind = kindOf(left) ?: kindOf(right)
                Compare(
                    condition.comparison,
                    expr(condition.left, kind, "comparison"),
                    expr(condition.right, kind, "comparison"),
                    condition.negated,
                )
            }
            is AndAll -> AndAll(condition.terms.map { condition(it) })
            is OrAny -> OrAny(condition.terms.map { condition(it) })
        }


        private fun expr(value: Expr, expected: ArgType?, source: String): Expr {
            if (expected != null && expected != ArgType.INT) {
                assign(symbolOf(value), expected, why(source))
                if (learning) noteResult(value, expected, source)
            }
            val visited = when (value) {
                is OpCall -> opCall(value)
                is ScriptCall -> scriptCall(value)
                is Callback -> callback(value)
                is Binary -> value.copy(left = expr(value.left, null, ""), right = expr(value.right, null, ""))
                is Join -> value.copy(parts = value.parts.map { expr(it, null, "") })
                is ArrayRef -> value.copy(index = expr(value.index, null, ""))
                is Spread -> Spread(expr(value.call, null, ""))
                else -> value
            }
            if (rewrite && visited is TypedConst) counted(visited.kind, "opcode table")
            if (!rewrite || expected == null || expected == ArgType.INT) return visited
            if (visited !is IntConst) return visited
            counted(expected, source)
            return TypedConst(visited.value, expected)
        }

        private fun opCall(value: OpCall): OpCall {
            val op = value.op
            val rule = TYPE_CODE_RULES[op]
            val ints = value.args.filter { it.type == Cs2Type.INT }
            var intIndex = 0
            val args = value.args.map { argument ->
                if (argument.type != Cs2Type.INT) return@map expr(argument, null, "")
                val index = intIndex++
                val declared = op.argTypes.getOrNull(index) ?: ArgType.INT
                val described = rule?.typedArgs?.get(index)?.let { typeCharAt(ints, it) }
                val kind = when {
                    declared != ArgType.INT -> declared
                    described != null -> described
                    else -> learnedArguments[argumentSlot(op.name, index)] ?: ArgType.INT
                }
                val describable = rule?.typedArgs?.containsKey(index) == true
                if (learning && declared == ArgType.INT && kind == ArgType.INT && !describable) {
                    noteArgument(op.name, index, argument)
                }
                val source = when {
                    declared != ArgType.INT -> "opcode argument"
                    described != null -> "enum type code"
                    else -> "learned argument slot"
                }
                expr(argument, kind, source)
            }
            val result = resultSymbol(op.name, 0)
            val described = rule?.resultChar?.let { typeCharAt(ints, it) }
            when {
                described != null -> declare(result, described)
                op.kind == OpKind.PARAM -> paramKind(value)?.let { declare(result, it) }
                rule == null -> learnedResults[result]?.let { declare(result, it) }
            }
            return value.copy(args = args)
        }

        private fun typeCharAt(ints: List<Expr>, index: Int): ArgType? {
            val argument = ints.getOrNull(index) ?: return null
            val code = (argument as? IntConst)?.value ?: (argument as? TypedConst)?.value ?: return null
            return trustedChars[code.toChar()]
        }

        private fun paramKind(value: OpCall): ArgType? {
            val id = value.args.lastOrNull { it.type == Cs2Type.INT }
            val paramId = (id as? IntConst)?.value ?: (id as? TypedConst)?.value ?: return null
            return trustedChars[context.paramType(paramId)]
        }

        private fun scriptCall(value: ScriptCall): ScriptCall {
            var intIndex = 0
            val args = value.args.map { argument ->
                if (argument.type != Cs2Type.INT) return@map expr(argument, null, "")
                val symbol = paramSymbol(value.scriptId, intIndex++)
                unify(symbol, symbolOf(argument))
                expr(argument, global.kindOf(symbol), "script parameter")
            }
            return value.copy(args = args)
        }

        private fun callback(value: Callback): Callback {
            var intIndex = 0
            val args = value.args.mapIndexed { index, argument ->
                if (argument.type != Cs2Type.INT) return@mapIndexed expr(argument, null, "")
                val char = value.format.getOrNull(index)
                val slot = intIndex++
                val callee = if (value.scriptId >= 0) paramSymbol(value.scriptId, slot) else null
                unify(callee, symbolOf(argument))
                val claimed = char?.let { Cs2TypeChars.KINDS[it] }
                if (chars && char != null && claimed != null && claimed != ArgType.INT) {
                    noteChar(char, argument, callee)
                }
                val kind = char?.let { trustedChars[it] } ?: ArgType.INT
                if (kind != ArgType.INT) assign(callee, kind, "hook format '$char'")
                val known = if (kind != ArgType.INT) kind else callee?.let { global.kindOf(it) }
                expr(argument, known, if (kind != ArgType.INT) "hook format" else "hook parameter")
            }
            return value.copy(args = args, triggers = value.triggers.map { expr(it, null, "") })
        }

        private fun ordered(symbol: String?, against: Expr) {
            if (symbol == null || chars) return
            val value = (against as? IntConst)?.value ?: (against as? TypedConst)?.value ?: return
            if (value == 0 || value == -1) return
            store(symbol).quantity(symbol)
        }

        private fun why(source: String) = if (source.isEmpty()) "" else "$source in ${fn.scriptId}"


        private fun localOf(slot: Int, type: Cs2Type): String? {
            if (type != Cs2Type.INT) return null
            return if (slot < fn.intArgs) paramSymbol(fn.scriptId, slot) else localSymbol(fn.scriptId, slot)
        }

        private fun symbolOf(value: Expr): String? {
            if (value.type != Cs2Type.INT) return null
            return when (value) {
                is LocalRef -> localOf(value.slot, value.type)
                is VarRef -> if (tracked(value.space)) varSymbol(value.space, value.id) else null
                is TempRef -> tempSymbol(fn.scriptId, value.name)
                is OpCall -> resultSymbol(value.op.name, 0)
                is ScriptCall -> returnSymbol(value.scriptId, 0)
                else -> null
            }
        }

        private fun store(symbol: String): Store =
            if (locals != null && symbol.startsWith("l:")) locals else global

        private fun kindOf(symbol: String?): ArgType? = symbol?.let { store(it).kindOf(it) }

        private fun assign(symbol: String?, kind: ArgType, why: String = "") {
            if (symbol == null || chars) return
            if (symbol.startsWith("o:")) return
            store(symbol).assign(symbol, kind, why)
        }

        private fun declare(symbol: String, kind: ArgType) {
            if (!chars) global.assign(symbol, kind, "the opcode says so")
        }

        private fun unify(left: String?, right: String?) {
            if (left == null || right == null || chars) return
            kindOf(left)?.let { assign(right, it, "same value as ${describe(left)}") }
            kindOf(right)?.let { assign(left, it, "same value as ${describe(right)}") }
        }


        private fun noteResult(value: Expr, kind: ArgType, source: String) {
            val call = value as? OpCall ?: return
            if (call.op.kind == OpKind.PARAM || source !in AUTHORITATIVE) return
            val slot = resultSymbol(call.op.name, 0)
            if (global.kindOf(slot) != null || slot in global.conflicts) return
            vote(resultEvidence, slot, kind)
        }

        private fun noteArgument(op: String, index: Int, argument: Expr) {
            val symbol = symbolOf(argument) ?: return
            val kind = kindOf(symbol) ?: return
            val votes = vote(argumentEvidence, argumentSlot(op, index), kind)
            val origin = store(symbol).origins[symbol] ?: "unrecorded"
            votes.origins.add(origin)
            if (votes.examples.size < EXAMPLES) {
                votes.examples.add("script ${fn.scriptId} ${describe(symbol)} <- $origin")
            }
            if (directlyTyped(symbol)) votes.direct++
        }

        private fun directlyTyped(symbol: String): Boolean {
            val origin = store(symbol).origins[symbol] ?: return false
            return AUTHORITATIVE.any { origin.startsWith(it) } && origin.endsWith(" ${fn.scriptId}")
        }

        private fun vote(
            into: MutableMap<String, MutableMap<ArgType, Votes>>,
            slot: String,
            kind: ArgType,
        ): Votes {
            val votes = into.getOrPut(slot) { HashMap() }.getOrPut(kind) { Votes() }
            votes.uses++
            votes.scripts.add(fn.scriptId)
            return votes
        }

        private fun noteChar(char: Char, argument: Expr, callee: String?) {
            val seen = kindOf(symbolOf(argument)) ?: callee?.let { global.kindOf(it) } ?: return
            val votes = charEvidence.getOrPut(char) { HashMap() }.getOrPut(seen) { Votes() }
            votes.uses++
            votes.scripts.add(fn.scriptId)
        }
    }

    companion object {
        private const val ROUNDS = 12

        private const val LOCAL_ROUNDS = 6

        private const val LEARNING_USES = 3

        private const val LEARNING_SCRIPTS = 2

        private val ORDERINGS = setOf("<", ">", "<=", ">=")

        private val AUTHORITATIVE = setOf("opcode argument", "hook format", "enum type code")

        private const val EXAMPLES = 6

        fun analyse(
            context: Cs2Context,
            functions: List<Cs2Function>,
            log: (String) -> Unit = {},
        ): Cs2Types = Cs2Types(context, functions).apply { analyse(log) }
    }
}
