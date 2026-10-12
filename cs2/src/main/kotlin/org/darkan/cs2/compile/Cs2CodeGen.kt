package org.darkan.cs2.compile

import org.darkan.cs2.Cs2Instruction
import org.darkan.cs2.Cs2Op
import org.darkan.cs2.Cs2Context
import org.darkan.cs2.Cs2Script
import org.darkan.cs2.Cs2SwitchCase
import org.darkan.cs2.ReturnSignature
import org.darkan.cs2.OpKind
import org.darkan.cs2.ir.ArrayAssign
import org.darkan.cs2.ir.ArrayDefine
import org.darkan.cs2.ir.ArrayRef
import org.darkan.cs2.ir.Binary
import org.darkan.cs2.ir.Break
import org.darkan.cs2.ir.Callback
import org.darkan.cs2.ir.AndAll
import org.darkan.cs2.ir.Compare
import org.darkan.cs2.ir.Condition
import org.darkan.cs2.ir.OrAny
import org.darkan.cs2.ir.Continue
import org.darkan.cs2.ir.Cs2Function
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
import org.darkan.cs2.ir.ParallelAssign
import org.darkan.cs2.ir.Return
import org.darkan.cs2.ir.ScriptCall
import org.darkan.cs2.ir.Spread
import org.darkan.cs2.ir.Stmt
import org.darkan.cs2.ir.StrConst
import org.darkan.cs2.ir.Switch
import org.darkan.cs2.ir.TempRef
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.ir.VarAssign
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace
import org.darkan.cs2.ir.While

class Cs2CodeGen(
    private val function: Cs2Function,
    private val context: Cs2Context? = null,
) {

    private val instructions = ArrayList<Cs2Instruction>()
    private val switchTables = ArrayList<MutableList<Cs2SwitchCase>>()

    private class Fixup(val at: Int, val label: String)

    private val fixups = ArrayList<Fixup>()

    private class TableFixup(val table: MutableList<Cs2SwitchCase>, val slot: Int, val from: Int, val label: String)

    private val tableFixups = ArrayList<TableFixup>()
    private val labelPositions = HashMap<String, Int>()
    private var labelCounter = 0

    private val breakLabels = ArrayDeque<String>()
    private val continueLabels = ArrayDeque<String>()

    fun generate(): Cs2Script {
        emitAll(function.body)
        emitDefaultReturn()
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

    private fun emitDefaultReturn() {
        if (function.body.lastOrNull() is Return) return
        for (type in function.returns) {
            when (type) {
                Cs2Type.INT -> emit(Cs2Op.PUSH_CONSTANT_INT, 0)
                Cs2Type.STRING -> emitString("")
                Cs2Type.LONG -> emitLong(0L)
            }
        }
        emit(Cs2Op.RETURN)
    }


    private fun emit(op: Cs2Op, operand: Int = 0) {
        instructions.add(Cs2Instruction(op, intOperand = operand))
    }

    private fun emitString(value: String) {
        instructions.add(Cs2Instruction(Cs2Op.PUSH_CONSTANT_STRING, strOperand = value))
    }

    private fun emitLong(value: Long) {
        instructions.add(Cs2Instruction(Cs2Op.PUSH_LONG_CONSTANT, longOperand = value))
    }

    private fun emitJump(op: Cs2Op, label: String) {
        fixups.add(Fixup(instructions.size, label))
        emit(op)
    }

    private fun newLabel(prefix: String) = "$prefix${labelCounter++}"

    private fun placeLabel(label: String) {
        labelPositions[label] = instructions.size
    }

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


    private fun emitAll(statements: List<Stmt>) = statements.forEach(::emitStatement)

    private fun emitStatement(statement: Stmt) {
        when (statement) {
            is LocalAssign -> {
                emitExpr(statement.value)
                emit(storeLocalOpcode(statement.type), statement.slot)
            }

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
                emit(Cs2Op.POP_ARRAY_INT, statement.array)
            }

            is ArrayDefine -> {
                emitExpr(statement.size)
                emit(Cs2Op.DEFINE_ARRAY, (statement.array shl 16) or statement.elementType)
            }

            is MultiAssign -> emitExpr(statement.call)

            is DestructureAssign -> {
                emitExpr(statement.call)
                for (target in statement.targets.asReversed()) {
                    when (target) {
                        is LocalRef -> emit(storeLocalOpcode(target.type), target.slot)
                        is VarRef -> emit(storeOpcode(target), target.id)
                        else -> error("Cannot assign to $target")
                    }
                }
            }

            is ExprStmt -> {
                emitExpr(statement.call)
                discardResults(statement.call)
            }

            is Return -> {
                statement.values.forEach(::emitExpr)
                emit(Cs2Op.RETURN)
            }

            is If -> emitIf(statement)
            is While -> emitWhile(statement)
            is Switch -> emitSwitch(statement)

            is Label -> placeLabel(statement.name)
            is Goto -> emitJump(Cs2Op.BRANCH, statement.label)
            is Break -> emitJump(Cs2Op.BRANCH, statement.label ?: breakLabels.last())
            is Continue -> emitJump(Cs2Op.BRANCH, statement.label ?: continueLabels.last())
        }
    }

    private fun leaves(body: List<Stmt>) =
        body.lastOrNull().let { it is Goto || it is Break || it is Continue }

    private fun storeLocalOpcode(type: Cs2Type) = when (type) {
        Cs2Type.INT -> Cs2Op.POP_INT_LOCAL
        Cs2Type.STRING -> Cs2Op.POP_STRING_LOCAL
        Cs2Type.LONG -> Cs2Op.POP_LONG_LOCAL
    }

    private fun storeOpcode(target: VarRef): Cs2Op = when (target.space) {
        VarSpace.VARP -> Cs2Op.POP_VAR
        VarSpace.VARBIT -> Cs2Op.POP_VARBIT
        VarSpace.VARC -> Cs2Op.POP_VARC_INT
        VarSpace.VARC_STRING -> Cs2Op.POP_VARC_STRING
        else -> error("${target.space} is read-only")
    }

    private fun loadOpcode(target: VarRef): Cs2Op = when {
        target.variant == "old" -> when (target.space) {
            VarSpace.VARP -> Cs2Op.GET_VARP_OLD
            VarSpace.VARBIT -> Cs2Op.GET_VARPBIT_OLD
            VarSpace.VARN -> Cs2Op.GET_VARN_OLD
            VarSpace.VARNBIT -> Cs2Op.GET_VARNBIT_OLD
            else -> error("No 'old' accessor for ${target.space}")
        }
        else -> when (target.space) {
            VarSpace.VARP -> Cs2Op.PUSH_VAR
            VarSpace.VARBIT -> Cs2Op.PUSH_VARBIT
            VarSpace.VARC -> Cs2Op.PUSH_VARC_INT
            VarSpace.VARC_STRING -> Cs2Op.PUSH_VARC_STRING
            VarSpace.CLAN -> Cs2Op.PUSH_VARCLAN
            VarSpace.CLAN_SETTING -> Cs2Op.PUSH_VARCLANSETTING
            else -> error("No accessor for ${target.space}")
        }
    }

    private fun discardResults(value: Expr) {
        val (ints, strings, longs) = resultCounts(value)
        repeat(ints) { emit(Cs2Op.POP_INT_DISCARD) }
        repeat(strings) { emit(Cs2Op.POP_STRING_DISCARD) }
        repeat(longs) { emit(Cs2Op.POP_LONG_DISCARD) }
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
        val onlyJump = if (statement.otherwise.isEmpty()) statement.then.singleOrNull() as? Goto else null
        if (onlyJump != null) {
            emitConditionalJump(statement.condition, onlyJump.label)
            return
        }

        val test = statement.condition
        if (statement.then.isEmpty() && statement.otherwise.isEmpty()) {
            val next = newLabel("empty")
            if (test is Compare && !test.negated) {
                emitComparison(test, next)
            } else {
                emitBranch(test, next)
                emitJump(Cs2Op.BRANCH, next)
            }
            placeLabel(next)
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
        emitJump(Cs2Op.BRANCH, endLabel)
        placeLabel(elseLabel)
        emitAll(statement.otherwise)
        placeLabel(endLabel)
    }

    private fun emitWhile(statement: While) {
        val testLabel = newLabel("test")
        val exitLabel = newLabel("exit")
        placeLabel(testLabel)
        statement.condition?.let { emitBranch(it, exitLabel) }

        breakLabels.addLast(exitLabel)
        continueLabels.addLast(testLabel)
        emitAll(statement.body)
        continueLabels.removeLast()
        breakLabels.removeLast()

        emitJump(Cs2Op.BRANCH, testLabel)
        placeLabel(exitLabel)
    }

    private fun emitBranch(condition: Condition, skipLabel: String) {
        when (condition) {
            is Compare -> if (condition.negated) {
                emitComparison(condition, skipLabel)
            } else {
                val bodyLabel = newLabel("body")
                emitComparison(condition, bodyLabel)
                emitJump(Cs2Op.BRANCH, skipLabel)
                placeLabel(bodyLabel)
            }
            is AndAll -> condition.terms.forEach { emitBranch(it, skipLabel) }
            is OrAny -> emitAlternatives(condition, newLabel("body"), skipLabel)
        }
    }

    private fun emitConditionalJump(condition: Condition, target: String) {
        when (condition) {
            is Compare -> if (condition.negated) {
                val skip = newLabel("skip")
                emitComparison(condition, skip)
                emitJump(Cs2Op.BRANCH, target)
                placeLabel(skip)
            } else {
                emitComparison(condition, target)
            }
            is OrAny -> condition.terms.forEach { emitConditionalJump(it, target) }
            is AndAll -> {
                val skip = newLabel("skip")
                condition.terms.forEach { emitBranch(it, skip) }
                emitJump(Cs2Op.BRANCH, target)
                placeLabel(skip)
            }
        }
    }

    private fun emitEnterBody(condition: Condition, bodyLabel: String, skipLabel: String) {
        when (condition) {
            is Compare -> if (condition.negated) {
                val next = newLabel("or")
                emitComparison(condition, next)
                emitJump(Cs2Op.BRANCH, bodyLabel)
                placeLabel(next)
            } else {
                emitComparison(condition, bodyLabel)
            }
            is AndAll -> {
                condition.terms.dropLast(1).forEach { emitBranch(it, skipLabel) }
                emitEnterBody(condition.terms.last(), bodyLabel, skipLabel)
            }
            is OrAny -> emitAlternatives(condition, bodyLabel, skipLabel, close = false)
        }
    }

    private fun emitAlternatives(
        condition: OrAny,
        bodyLabel: String,
        skipLabel: String,
        close: Boolean = true,
    ) {
        for ((index, term) in condition.terms.withIndex()) {
            if (index == condition.terms.lastIndex) {
                emitEnterBody(term, bodyLabel, skipLabel)
                break
            }
            val next = newLabel("or")
            emitEnterBody(term, bodyLabel, next)
            placeLabel(next)
        }
        if (!close) return
        emitJump(Cs2Op.BRANCH, skipLabel)
        placeLabel(bodyLabel)
    }

    private fun emitComparison(condition: Compare, target: String) {
        when (condition.comparison) {
            Cs2Op.BRANCH_IF_TRUE, Cs2Op.BRANCH_IF_FALSE -> emitExpr(condition.left)
            else -> {
                emitExpr(condition.left)
                emitExpr(condition.right)
            }
        }
        emitJump(condition.comparison, target)
    }

    private fun tableOnly(statement: Switch): Boolean =
        statement.default.isEmpty() && statement.cases.isNotEmpty() &&
            statement.cases.all { it.body.singleOrNull() is Goto }

    private fun emitSwitch(statement: Switch) {
        emitExpr(statement.subject)

        val table = ArrayList<Cs2SwitchCase>()
        val tableIndex = switchTables.size
        switchTables.add(table)
        val switchAt = instructions.size
        emit(Cs2Op.SWITCH, tableIndex)

        val defaultLabel = newLabel("switchDefault")
        val endLabel = newLabel("switchEnd")
        breakLabels.addLast(endLabel)

        if (statement.cases.isEmpty() && statement.default.isEmpty()) {
            breakLabels.removeLast()
            placeLabel(endLabel)
            return
        }

        if (tableOnly(statement)) {
            emitAll(statement.default)
            if (statement.default.isNotEmpty() && !leaves(statement.default)) {
                emitJump(Cs2Op.BRANCH, endLabel)
            }
            for ((index, case) in statement.cases.withIndex()) {
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
                if (index != statement.cases.lastIndex && !leaves(case.body)) {
                    emitJump(Cs2Op.BRANCH, endLabel)
                }
            }
        } else {
            emitJump(Cs2Op.BRANCH, defaultLabel)
            for ((index, case) in statement.cases.withIndex()) {
                val start = instructions.size
                case.keys.forEach { table.add(Cs2SwitchCase(it, start - switchAt - 1)) }
                emitAll(case.body)
                if (!leaves(case.body) &&
                    (index != statement.cases.lastIndex || statement.default.isNotEmpty())
                ) {
                    emitJump(Cs2Op.BRANCH, endLabel)
                }
            }
            placeLabel(defaultLabel)
            emitAll(statement.default)
        }

        breakLabels.removeLast()
        placeLabel(endLabel)
    }


    private fun emitExpr(value: Expr) {
        when (value) {
            is IntConst -> emit(Cs2Op.PUSH_CONSTANT_INT, value.value)
            is TypedConst -> emit(Cs2Op.PUSH_CONSTANT_INT, value.value)
            is StrConst -> emitString(value.value)
            is LongConst -> emitLong(value.value)

            is LocalRef -> emit(
                when (value.type) {
                    Cs2Type.INT -> Cs2Op.PUSH_INT_LOCAL
                    Cs2Type.STRING -> Cs2Op.PUSH_STRING_LOCAL
                    Cs2Type.LONG -> Cs2Op.PUSH_LONG_LOCAL
                },
                value.slot,
            )

            is TempRef -> Unit

            is Spread -> emitExpr(value.call)

            is VarRef -> emit(loadOpcode(value), value.id)

            is ArrayRef -> {
                emitExpr(value.index)
                emit(Cs2Op.PUSH_ARRAY_INT, value.array)
            }

            is Binary -> {
                emitExpr(value.left)
                emitExpr(value.right)
                emit(binaryOpcode(value.symbol))
            }

            is Join -> {
                value.parts.forEach(::emitExpr)
                emit(Cs2Op.JOIN_STRING, value.parts.size)
            }

            is ScriptCall -> {
                value.args.forEach(::emitExpr)
                emit(Cs2Op.GOSUB_WITH_PARAMS, value.scriptId)
            }

            is Callback -> emitCallback(value)

            is OpCall -> {
                value.args.forEach(::emitExpr)
                instructions.add(Cs2Instruction(value.op, intOperand = value.operand))
            }
        }
    }

    private fun binaryOpcode(symbol: String) = when (symbol) {
        "+" -> Cs2Op.ADD
        "-" -> Cs2Op.SUB
        "*" -> Cs2Op.MULTIPLY
        "/" -> Cs2Op.DIVIDE
        "%" -> Cs2Op.MODULO
        "&" -> Cs2Op.AND
        "|" -> Cs2Op.OR
        else -> error("No opcode for operator '$symbol'")
    }

    private fun emitCallback(value: Callback) {
        if (value.scriptId == -1) {
            emit(Cs2Op.PUSH_CONSTANT_INT, -1)
            emitString(value.format)
            return
        }
        emit(Cs2Op.PUSH_CONSTANT_INT, value.scriptId)
        value.args.forEach(::emitExpr)
        if (value.hasTriggerList) {
            value.triggers.forEach(::emitExpr)
            emit(Cs2Op.PUSH_CONSTANT_INT, value.triggers.size)
        }
        emitString(value.format)
    }
}
