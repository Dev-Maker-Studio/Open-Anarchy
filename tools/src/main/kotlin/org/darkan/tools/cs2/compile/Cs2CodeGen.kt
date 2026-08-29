package org.darkan.tools.cs2.compile

import org.darkan.tools.cs2.Cs2Instruction
import org.darkan.tools.cs2.Cs2Op
import org.darkan.tools.cs2.Cs2Context
import org.darkan.tools.cs2.Cs2Opcodes
import org.darkan.tools.cs2.Cs2Script
import org.darkan.tools.cs2.Cs2SwitchCase
import org.darkan.tools.cs2.ReturnSignature
import org.darkan.tools.cs2.OpKind
import org.darkan.tools.cs2.ir.ArrayAssign
import org.darkan.tools.cs2.ir.ArrayDefine
import org.darkan.tools.cs2.ir.ArrayRef
import org.darkan.tools.cs2.ir.Binary
import org.darkan.tools.cs2.ir.Break
import org.darkan.tools.cs2.ir.Callback
import org.darkan.tools.cs2.ir.AndAll
import org.darkan.tools.cs2.ir.Compare
import org.darkan.tools.cs2.ir.Condition
import org.darkan.tools.cs2.ir.OrAny
import org.darkan.tools.cs2.ir.Continue
import org.darkan.tools.cs2.ir.Cs2Function
import org.darkan.tools.cs2.ir.Cs2Type
import org.darkan.tools.cs2.ir.Expr
import org.darkan.tools.cs2.ir.ExprStmt
import org.darkan.tools.cs2.ir.Goto
import org.darkan.tools.cs2.ir.If
import org.darkan.tools.cs2.ir.IntConst
import org.darkan.tools.cs2.ir.Join
import org.darkan.tools.cs2.ir.Label
import org.darkan.tools.cs2.ir.LocalAssign
import org.darkan.tools.cs2.ir.LocalRef
import org.darkan.tools.cs2.ir.LongConst
import org.darkan.tools.cs2.ir.MultiAssign
import org.darkan.tools.cs2.ir.OpCall
import org.darkan.tools.cs2.ir.ParallelAssign
import org.darkan.tools.cs2.ir.Return
import org.darkan.tools.cs2.ir.ScriptCall
import org.darkan.tools.cs2.ir.Stmt
import org.darkan.tools.cs2.ir.StrConst
import org.darkan.tools.cs2.ir.Switch
import org.darkan.tools.cs2.ir.TempRef
import org.darkan.tools.cs2.ir.TypedConst
import org.darkan.tools.cs2.ir.VarAssign
import org.darkan.tools.cs2.ir.VarRef
import org.darkan.tools.cs2.ir.VarSpace
import org.darkan.tools.cs2.ir.While

/**
 * Lowers a parsed function back into CS2 instructions.
 *
 * The layout mirrors what Jagex's compiler emits, because matching it is what
 * makes an untouched script re-compile to the same bytes:
 *  - a conditional jumps *into* its body over a single unconditional branch;
 *  - `if`/`else` puts the else-arm after the then-arm's jump to the join point;
 *  - `while` re-tests at the top and jumps back from the bottom;
 *  - `switch` puts the default arm first, then the cases in table order.
 */
class Cs2CodeGen(
    private val function: Cs2Function,
    /**
     * Used to look up what a called script leaves on the stacks, so a call used
     * as a statement discards exactly as many results as it produced.
     */
    private val context: Cs2Context? = null,
) {

    private val instructions = ArrayList<Cs2Instruction>()
    private val switchTables = ArrayList<MutableList<Cs2SwitchCase>>()

    /** Jump sites to patch once every label position is known. */
    private class Fixup(val at: Int, val label: String)

    private val fixups = ArrayList<Fixup>()

    /** Switch table entries whose target is a label rather than an inline arm. */
    private class TableFixup(val table: MutableList<Cs2SwitchCase>, val slot: Int, val from: Int, val label: String)

    private val tableFixups = ArrayList<TableFixup>()
    private val labelPositions = HashMap<String, Int>()
    private var labelCounter = 0

    /** Where `break` and `continue` go inside the current loop or switch. */
    private val breakLabels = ArrayDeque<String>()
    private val continueLabels = ArrayDeque<String>()

    fun generate(): Cs2Script {
        emitAll(function.body)
        patchJumps()
        return Cs2Script(
            name = function.name,
            instructions = instructions,
            intLocalsCount = function.intLocals,
            stringLocalsCount = function.stringLocals,
            longLocalsCount = function.longLocals,
            intArgsCount = function.intArgs,
            stringArgsCount = function.stringArgs,
            longArgsCount = function.longArgs,
            switchTables = switchTables,
        )
    }

    // ---------------------------------------------------------------- output

    private fun op(name: String): Cs2Op =
        Cs2Opcodes.byName(name) ?: error("Missing opcode $name")

    private fun emit(name: String, operand: Int = 0) {
        instructions.add(Cs2Instruction(op(name), intOperand = operand))
    }

    private fun emitString(value: String) {
        instructions.add(Cs2Instruction(op("PUSH_CONSTANT_STRING"), strOperand = value))
    }

    private fun emitLong(value: Long) {
        instructions.add(Cs2Instruction(op("PUSH_LONG_CONSTANT"), longOperand = value))
    }

    private fun emitJump(name: String, label: String) {
        fixups.add(Fixup(instructions.size, label))
        emit(name)
    }

    private fun newLabel(prefix: String) = "$prefix${labelCounter++}"

    private fun placeLabel(label: String) {
        labelPositions[label] = instructions.size
    }

    /** Rewrites each jump operand to the relative distance the interpreter expects. */
    private fun patchJumps() {
        for (fixup in tableFixups) {
            val target = labelPositions[fixup.label]
                ?: error("Switch case jumps to undefined label '${fixup.label}'")
            val current = fixup.table[fixup.slot]
            fixup.table[fixup.slot] = current.copy(offset = target - fixup.from - 1)
        }
        for (fixup in fixups) {
            val target = labelPositions[fixup.label]
                ?: error("Jump to undefined label '${fixup.label}'")
            val current = instructions[fixup.at]
            instructions[fixup.at] = current.copy(intOperand = target - fixup.at - 1)
        }
    }

    // ------------------------------------------------------------ statements

    private fun emitAll(statements: List<Stmt>) = statements.forEach(::emitStatement)

    private fun emitStatement(statement: Stmt) {
        when (statement) {
            // Declarations only reserve a slot; they produce no instructions.
            is LocalAssign -> if (!statement.declare) {
                emitExpr(statement.value)
                emit(
                    when (statement.type) {
                        Cs2Type.INT -> "POP_INT_LOCAL"
                        Cs2Type.STRING -> "POP_STRING_LOCAL"
                        Cs2Type.LONG -> "POP_LONG_LOCAL"
                    },
                    statement.slot,
                )
            }

            // Every value is computed first, then stored in reverse - the last
            // one pushed is the first one popped.
            is ParallelAssign -> {
                statement.values.forEach(::emitExpr)
                for (target in statement.targets.asReversed()) {
                    when (target) {
                        is LocalRef -> emit(storeLocalOpcode(target.type), target.slot)
                        is VarRef -> emit(storeOpcode(target), target.id)
                        else -> error("Cannot assign to $target")
                    }
                }
            }

            is VarAssign -> {
                emitExpr(statement.value)
                emit(storeOpcode(statement.target), statement.target.id)
            }

            is ArrayAssign -> {
                emitExpr(statement.index)
                emitExpr(statement.value)
                emit("POP_ARRAY_INT", statement.array)
            }

            is ArrayDefine -> {
                emitExpr(statement.size)
                emit("DEFINE_ARRAY", (statement.array shl 16) or statement.elementType)
            }

            is MultiAssign -> emitExpr(statement.call)

            is ExprStmt -> {
                emitExpr(statement.call)
                discardResults(statement.call)
            }

            is Return -> {
                statement.values.forEach(::emitExpr)
                emit("RETURN")
            }

            is If -> emitIf(statement)
            is While -> emitWhile(statement)
            is Switch -> emitSwitch(statement)

            is Label -> placeLabel(statement.name)
            is Goto -> emitJump("BRANCH", statement.label)
            is Break -> emitJump("BRANCH", statement.label ?: breakLabels.last())
            is Continue -> emitJump("BRANCH", statement.label ?: continueLabels.last())
        }
    }

    /** True when a body already transfers control, so no trailing jump is needed. */
    private fun leaves(body: List<Stmt>) =
        body.lastOrNull().let { it is Goto || it is Break || it is Continue }

    private fun storeLocalOpcode(type: Cs2Type) = when (type) {
        Cs2Type.INT -> "POP_INT_LOCAL"
        Cs2Type.STRING -> "POP_STRING_LOCAL"
        Cs2Type.LONG -> "POP_LONG_LOCAL"
    }

    private fun storeOpcode(target: VarRef): String = when (target.space) {
        VarSpace.VARP -> "POP_VAR"
        VarSpace.VARBIT -> "POP_VARBIT"
        VarSpace.VARC -> "STORE_VARC"
        VarSpace.VARC_STRING -> "STORE_VARC_STRING"
        else -> error("${target.space} is read-only")
    }

    private fun loadOpcode(target: VarRef): String = when {
        target.variant == "old" -> when (target.space) {
            VarSpace.VARP -> "GET_VARP_OLD"
            VarSpace.VARBIT -> "GET_VARPBIT_OLD"
            VarSpace.VARN -> "GET_VARN_OLD"
            VarSpace.VARNBIT -> "GET_VARNBIT_OLD"
            else -> error("No 'old' accessor for ${target.space}")
        }
        else -> when (target.space) {
            VarSpace.VARP -> "PUSH_VAR"
            VarSpace.VARBIT -> "PUSH_VARBIT"
            VarSpace.VARC -> "LOAD_VARC"
            VarSpace.VARC_STRING -> "LOAD_VARC_STRING"
            VarSpace.CLAN -> "LOAD_CLAN_VAR"
            VarSpace.CLAN_SETTING -> "LOAD_CLAN_SETTING_VAR"
            else -> error("No accessor for ${target.space}")
        }
    }

    /**
     * A call used as a statement still pushes its results, so they have to be
     * dropped explicitly.
     */
    private fun discardResults(value: Expr) {
        val (ints, strings, longs) = resultCounts(value)
        repeat(ints) { emit("POP_INT_DISCARD") }
        repeat(strings) { emit("POP_STRING_DISCARD") }
        repeat(longs) { emit("POP_LONG_DISCARD") }
    }

    private fun resultCounts(value: Expr): Triple<Int, Int, Int> = when (value) {
        is OpCall -> if (value.op.kind == OpKind.PARAM) {
            if (value.type == Cs2Type.STRING) Triple(0, 1, 0) else Triple(1, 0, 0)
        } else {
            Triple(value.op.pushInt, value.op.pushStr, value.op.pushLong)
        }
        is TempRef -> when (value.type) {
            Cs2Type.INT -> Triple(1, 0, 0)
            Cs2Type.STRING -> Triple(0, 1, 0)
            Cs2Type.LONG -> Triple(0, 0, 1)
        }
        is ScriptCall -> {
            val returns = context?.script(value.scriptId)?.returnSignature ?: ReturnSignature.EMPTY
            Triple(returns.ints, returns.strings, returns.longs)
        }
        else -> Triple(0, 0, 0)
    }

    private fun emitIf(statement: If) {
        // `if (c) goto X;` compiles as a jump guarded by the condition, not as
        // a branch around a body - the terms jump straight to X.
        val onlyJump = if (statement.otherwise.isEmpty()) statement.then.singleOrNull() as? Goto else null
        if (onlyJump != null) {
            emitConditionalJump(statement.condition, onlyJump.label)
            return
        }

        val elseLabel = newLabel("else")
        emitBranch(statement.condition, elseLabel)
        emitAll(statement.then)
        if (statement.otherwise.isEmpty()) {
            placeLabel(elseLabel)
            return
        }
        val endLabel = newLabel("end")
        emitJump("BRANCH", endLabel)
        placeLabel(elseLabel)
        emitAll(statement.otherwise)
        placeLabel(endLabel)
    }

    private fun emitWhile(statement: While) {
        val testLabel = newLabel("test")
        val exitLabel = newLabel("exit")
        placeLabel(testLabel)
        emitBranch(statement.condition, exitLabel)

        breakLabels.addLast(exitLabel)
        continueLabels.addLast(testLabel)
        emitAll(statement.body)
        continueLabels.removeLast()
        breakLabels.removeLast()

        emitJump("BRANCH", testLabel)
        placeLabel(exitLabel)
    }

    /**
     * Emits `if (condition) fall through, else jump to [skipLabel]`, using the
     * trampoline shape the game's compiler produces: the comparison jumps over
     * a single unconditional branch.
     */
    private fun emitBranch(condition: Condition, skipLabel: String) {
        when (condition) {
            is Compare -> if (condition.negated) {
                // The comparison itself jumps past the body.
                emitComparison(condition, skipLabel)
            } else {
                val bodyLabel = newLabel("body")
                emitComparison(condition, bodyLabel)
                emitJump("BRANCH", skipLabel)
                placeLabel(bodyLabel)
            }
            // Each term guards the rest, so failing any one skips the body.
            is AndAll -> condition.terms.forEach { emitBranch(it, skipLabel) }
            // Each term jumps straight into the body; falling off the end skips it.
            is OrAny -> {
                val bodyLabel = newLabel("body")
                condition.terms.forEach { emitEnterBody(it, bodyLabel, skipLabel) }
                emitJump("BRANCH", skipLabel)
                placeLabel(bodyLabel)
            }
        }
    }

    /**
     * Jumps to [target] when [condition] holds, falling through when it does
     * not. This is the mirror of [emitBranch], which falls through on success.
     */
    private fun emitConditionalJump(condition: Condition, target: String) {
        when (condition) {
            is Compare -> if (condition.negated) {
                // The opcode branches when the term is *false*, so hop over the jump.
                val skip = newLabel("skip")
                emitComparison(condition, skip)
                emitJump("BRANCH", target)
                placeLabel(skip)
            } else {
                emitComparison(condition, target)
            }
            // Any term being true is enough.
            is OrAny -> condition.terms.forEach { emitConditionalJump(it, target) }
            // Every term must hold, so a failure skips past the jump.
            is AndAll -> {
                val skip = newLabel("skip")
                condition.terms.forEach { emitBranch(it, skip) }
                emitJump("BRANCH", target)
                placeLabel(skip)
            }
        }
    }

    /**
     * Emits one alternative of an `||`: jump to [bodyLabel] when it holds, and
     * fall through to try the next when it does not. A nested `&&` bails out to
     * [skipLabel] as soon as one of its terms fails, and its final term is the
     * one that jumps into the body.
     */
    private fun emitEnterBody(condition: Condition, bodyLabel: String, skipLabel: String) {
        when (condition) {
            is Compare -> if (condition.negated) {
                val next = newLabel("or")
                emitComparison(condition, next)
                emitJump("BRANCH", bodyLabel)
                placeLabel(next)
            } else {
                emitComparison(condition, bodyLabel)
            }
            is AndAll -> {
                condition.terms.dropLast(1).forEach { emitBranch(it, skipLabel) }
                emitEnterBody(condition.terms.last(), bodyLabel, skipLabel)
            }
            is OrAny -> condition.terms.forEach { emitEnterBody(it, bodyLabel, skipLabel) }
        }
    }

    /** Pushes a comparison's operands and branches to [target] when it holds. */
    private fun emitComparison(condition: Compare, target: String) {
        when (condition.comparison.opName) {
            "BRANCH_IF_TRUE", "BRANCH_IF_FALSE" -> emitExpr(condition.left)
            else -> {
                emitExpr(condition.left)
                emitExpr(condition.right)
            }
        }
        emitJump(condition.comparison.opName, target)
    }

    private fun emitSwitch(statement: Switch) {
        emitExpr(statement.subject)

        val table = ArrayList<Cs2SwitchCase>()
        val tableIndex = switchTables.size
        switchTables.add(table)
        val switchAt = instructions.size
        emit("SWITCH", tableIndex)

        val defaultLabel = newLabel("switchDefault")
        val endLabel = newLabel("switchEnd")
        breakLabels.addLast(endLabel)

        if (statement.cases.isEmpty() && statement.default.isEmpty()) {
            // A switch with an empty table is just the opcode; there is nothing
            // to jump around.
            breakLabels.removeLast()
            placeLabel(endLabel)
            return
        }

        if (statement.defaultFirst) {
            // Control falls straight out of the switch into the default arm,
            // which then jumps to the join point like any other arm. With no
            // default arm at all it simply falls through to what follows.
            emitAll(statement.default)
            if (statement.default.isNotEmpty() && !leaves(statement.default)) {
                emitJump("BRANCH", endLabel)
            }
            for ((index, case) in statement.cases.withIndex()) {
                // An arm that is nothing but a jump is really a table entry
                // pointing at a labelled block; record it and emit nothing.
                val jump = case.body.singleOrNull() as? Goto
                if (jump != null) {
                    case.keys.forEach {
                        table.add(Cs2SwitchCase(it, 0))
                        tableFixups.add(TableFixup(table, table.lastIndex, switchAt, jump.label))
                    }
                    continue
                }
                val start = instructions.size
                case.keys.forEach { table.add(Cs2SwitchCase(it, start - switchAt - 1)) }
                emitAll(case.body)
                // The final arm falls straight into the join point.
                if (index != statement.cases.lastIndex && !leaves(case.body)) {
                    emitJump("BRANCH", endLabel)
                }
            }
        } else {
            emitJump("BRANCH", defaultLabel)
            for ((index, case) in statement.cases.withIndex()) {
                val start = instructions.size
                case.keys.forEach { table.add(Cs2SwitchCase(it, start - switchAt - 1)) }
                emitAll(case.body)
                if (!leaves(case.body) &&
                    (index != statement.cases.lastIndex || statement.default.isNotEmpty())
                ) {
                    emitJump("BRANCH", endLabel)
                }
            }
            placeLabel(defaultLabel)
            emitAll(statement.default)
        }

        breakLabels.removeLast()
        placeLabel(endLabel)
    }

    // ----------------------------------------------------------- expressions

    private fun emitExpr(value: Expr) {
        when (value) {
            is IntConst -> emit("PUSH_CONSTANT_INT", value.value)
            // A typed constant only reads differently; it is the same integer.
            is TypedConst -> emit("PUSH_CONSTANT_INT", value.value)
            is StrConst -> emitString(value.value)
            is LongConst -> emitLong(value.value)

            is LocalRef -> emit(
                when (value.type) {
                    Cs2Type.INT -> "PUSH_INT_LOCAL"
                    Cs2Type.STRING -> "PUSH_STRING_LOCAL"
                    Cs2Type.LONG -> "PUSH_LONG_LOCAL"
                },
                value.slot,
            )

            // Already on the stack from the call that produced it.
            is TempRef -> Unit

            is VarRef -> emit(loadOpcode(value), value.id)

            is ArrayRef -> {
                emitExpr(value.index)
                emit("PUSH_ARRAY_INT", value.array)
            }

            is Binary -> {
                emitExpr(value.left)
                emitExpr(value.right)
                emit(binaryOpcode(value.symbol))
            }

            is Join -> {
                value.parts.forEach(::emitExpr)
                emit("JOIN_STRING", value.parts.size)
            }

            is TempRef -> when (value.type) {
            Cs2Type.INT -> Triple(1, 0, 0)
            Cs2Type.STRING -> Triple(0, 1, 0)
            Cs2Type.LONG -> Triple(0, 0, 1)
        }
        is ScriptCall -> {
                value.args.forEach(::emitExpr)
                emit("GOSUB_WITH_PARAMS", value.scriptId)
            }

            is Callback -> emitCallback(value)

            is OpCall -> {
                value.args.forEach(::emitExpr)
                instructions.add(Cs2Instruction(value.op, intOperand = value.operand))
            }
        }
    }

    private fun binaryOpcode(symbol: String) = when (symbol) {
        "+" -> "ADD"
        "-" -> "SUB"
        "*" -> "MULTIPLY"
        "/" -> "DIVIDE"
        "%" -> "MODULO"
        "&" -> "AND"
        "|" -> "OR"
        else -> error("No opcode for operator '$symbol'")
    }

    /**
     * A hook callback is pushed as: script id, bound arguments, the trigger list
     * and its size, then the format string describing the argument types.
     */
    private fun emitCallback(value: Callback) {
        if (value.scriptId == -1) {
            emit("PUSH_CONSTANT_INT", -1)
            emitString(value.format)
            return
        }
        emit("PUSH_CONSTANT_INT", value.scriptId)
        value.args.forEach(::emitExpr)
        if (value.hasTriggerList) {
            value.triggers.forEach(::emitExpr)
            emit("PUSH_CONSTANT_INT", value.triggers.size)
        }
        emitString(value.format)
    }
}
