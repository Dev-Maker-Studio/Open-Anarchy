package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.cs2.ArgType
import org.darkan.cs2.Cs2Packing
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
import org.darkan.cs2.ir.While

class Cs2Emitter(private val function: Cs2Function) {

    private val out = StringBuilder()
    private var indent = 0

    private val declaredInBody: List<Int> = declarationsIn(function.body)

    private val localNames: Map<Int, String> = nameLocals()

    fun emit(): String {
        out.setLength(0)
        emitHeader()
        emitSignature()
        indent = 1
        emitLocalDeclarations()
        emitBody(function.body)
        indent = 0
        line("}")
        return out.toString()
    }

    fun renderStatements(statements: List<Stmt>): String {
        out.setLength(0)
        indent = 0
        emitAll(statements)
        return out.toString()
    }

    fun renderCondition(value: Condition): String = condition(value)

    private fun emitHeader() {
        val up = Cs2Layout.referencePrefix(function.scriptId)
        line("/// <reference path=\"$up${Cs2Declarations.OPCODES_FILE}\" />")
        line("/// <reference path=\"$up${Cs2Declarations.GAMEVALS_FILE}\" />")
        line("/// <reference path=\"$up${Cs2Declarations.VARS_FILE}\" />")
        line("// ${Cs2Layout.headerOf(function.scriptId)}")
        if (function.name != null) line("// name: ${function.name}")
        line("")
    }

    private fun emitSignature() {
        val total = function.intArgs + function.stringArgs + function.longArgs
        val order = function.argumentOrder.takeIf { it.size == total }
            ?: List(function.intArgs) { Cs2Type.INT } +
            List(function.stringArgs) { Cs2Type.STRING } +
            List(function.longArgs) { Cs2Type.LONG }

        val next = HashMap<Cs2Type, Int>()
        val params = order.map { type ->
            val slot = next.getOrDefault(type, 0)
            next[type] = slot + 1
            val kind = if (type == Cs2Type.INT) function.intParamKinds.getOrNull(slot) else null
            "${localName(slot, type)}: ${tsType(type, kind)}"
        }

        val returns = when (function.returns.size) {
            0 -> "void"
            1 -> tsType(function.returns.first(), function.returnKinds.firstOrNull())
            else -> function.returns.mapIndexed { index, type ->
                tsType(type, function.returnKinds.getOrNull(index))
            }.joinToString(", ", "[", "]")
        }
        line("function ${Cs2Symbols.scriptName(function.scriptId)}(${params.joinToString(", ")}): $returns {")
    }

    private fun emitLocalDeclarations() {
        var declared = false
        for (slot in function.intArgs until function.intLocals) {
            if (key(slot, Cs2Type.INT) in declaredInBody) continue
            line("let ${localName(slot, Cs2Type.INT)}: ${localType(slot)};")
            declared = true
        }
        for (slot in function.stringArgs until function.stringLocals) {
            if (key(slot, Cs2Type.STRING) in declaredInBody) continue
            line("let ${localName(slot, Cs2Type.STRING)}: string;")
            declared = true
        }
        for (slot in function.longArgs until function.longLocals) {
            if (key(slot, Cs2Type.LONG) in declaredInBody) continue
            line("let ${localName(slot, Cs2Type.LONG)}: bigint;")
            declared = true
        }
        if (declared) line("")
    }

    private fun key(slot: Int, type: Cs2Type) = (slot shl 2) or type.ordinal

    private fun declarationsIn(body: List<Stmt>): List<Int> {
        val out = ArrayList<Int>()
        for (statement in body) {
            when {
                statement is LocalAssign && statement.declare ->
                    out.add(key(statement.slot, statement.type))
                statement is DestructureAssign && statement.declare ->
                    statement.targets.filterIsInstance<LocalRef>()
                        .forEach { out.add(key(it.slot, it.type)) }
            }
        }
        return out
    }

    private fun nameLocals(): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (type in Cs2Type.entries) {
            val prefix = prefixOf(type)
            val args = argumentsOf(type)
            val total = localsOf(type)
            for (slot in 0 until args) out[key(slot, type)] = "${prefix}Arg$slot"

            val inline = declaredInBody.filter { it and 3 == type.ordinal }
            val header = (args until total).map { key(it, type) }.filterNot { it in inline }
            var index = args
            for (local in header + inline) {
                out[local] = "$prefix$index"
                index++
            }
        }
        return out
    }

    private fun prefixOf(type: Cs2Type) = when (type) {
        Cs2Type.INT -> "int"
        Cs2Type.STRING -> "str"
        Cs2Type.LONG -> "long"
    }

    private fun argumentsOf(type: Cs2Type) = when (type) {
        Cs2Type.INT -> function.intArgs
        Cs2Type.STRING -> function.stringArgs
        Cs2Type.LONG -> function.longArgs
    }

    private fun localsOf(type: Cs2Type) = when (type) {
        Cs2Type.INT -> function.intLocals
        Cs2Type.STRING -> function.stringLocals
        Cs2Type.LONG -> function.longLocals
    }

    private fun tsType(type: Cs2Type, kind: ArgType? = null): String = when (type) {
        Cs2Type.INT -> kind?.let { Cs2Names.aliasFor(it) } ?: "number"
        Cs2Type.STRING -> "string"
        Cs2Type.LONG -> "bigint"
    }

    private fun localType(slot: Int) = tsType(Cs2Type.INT, function.intLocalKinds[slot])

    private fun localName(slot: Int, type: Cs2Type): String =
        localNames[key(slot, type)] ?: "${prefixOf(type)}$slot"


    private fun emitBody(body: List<Stmt>) {
        var leading = true
        for ((index, statement) in body.withIndex()) {
            val declaration = statement is LocalAssign && statement.declare
            if (index > 0) {
                if (statement is If || statement is While || statement is Switch) line("")
                else if (leading && !declaration) line("")
            }
            if (!declaration) leading = false
            emit(statement)
        }
    }

    private fun emitAll(statements: List<Stmt>) = statements.forEach(::emit)

    private fun emit(statement: Stmt) {
        when (statement) {
            is ExprStmt -> if (statement.call is TempRef) {
                line("discard(${expr(statement.call)});")
            } else {
                line("${expr(statement.call)};")
            }

            is LocalAssign -> {
                val name = localName(statement.slot, statement.type)
                val type = if (statement.type == Cs2Type.INT) localType(statement.slot)
                else tsType(statement.type)
                val declaration = if (statement.declare) "let $name: $type" else name
                line("$declaration = ${expr(statement.value)};")
            }

            is ParallelAssign -> {
                val targets = statement.targets.joinToString(", ") { expr(it) }
                val values = statement.values.joinToString(", ") { expr(it) }
                line("[$targets] = [$values];")
            }

            is DestructureAssign -> {
                val targets = statement.targets.joinToString(", ") { expr(it) }
                val keyword = if (statement.declare) "let " else ""
                line("$keyword[$targets] = ${expr(statement.call)};")
            }

            is VarAssign -> line("${expr(statement.target)} = ${expr(statement.value)};")

            is ArrayAssign ->
                line("array${statement.array}[${expr(statement.index)}] = ${expr(statement.value)};")

            is ArrayDefine -> {
                val element = Cs2TypeChars.nameOf(statement.elementType) ?: statement.elementType.toString()
                line("defineArray(${statement.array}, $element, ${expr(statement.size)});")
            }

            is MultiAssign -> {
                val names = statement.names.joinToString(", ") { it.name }
                line("const [$names] = ${expr(statement.call)};")
            }

            is Return -> when (statement.values.size) {
                0 -> line("return;")
                1 -> line("return ${expr(statement.values.first())};")
                else -> line("return [${statement.values.joinToString(", ") { expr(it) }}];")
            }

            is If -> emitIf(statement, "")

            is While -> {
                line("while (${statement.condition?.let { condition(it) } ?: "true"}) {")
                indented { emitAll(statement.body) }
                line("}")
            }

            is Switch -> {
                line("switch (${expr(statement.subject)}) {")
                indented {
                    for (case in statement.cases) {
                        case.keys.forEach { line("case ${caseKey(it, statement.keyKind)}:") }
                        indented {
                            emitAll(case.body)
                            if (!exits(case.body)) line("break;")
                        }
                    }
                    if (statement.default.isNotEmpty()) {
                        line("default:")
                        indented { emitAll(statement.default) }
                    }
                }
                line("}")
            }

            is Label -> {
                indent--
                line("${statement.name}: ;")
                indent++
            }

            is Goto -> line("goto(\"${statement.label}\");")
            is Break -> line(if (statement.label == null) "break;" else "break ${statement.label};")
            is Continue -> line(if (statement.label == null) "continue;" else "continue ${statement.label};")
        }
    }

    private fun emitIf(statement: If, prefix: String) {
        line("${prefix}if (${condition(statement.condition)}) {")
        indented { emitAll(statement.then) }
        val chained = statement.otherwise.singleOrNull() as? If
        when {
            statement.otherwise.isEmpty() -> line("}")
            chained != null -> emitIf(chained, "} else ")
            else -> {
                line("} else {")
                indented { emitAll(statement.otherwise) }
                line("}")
            }
        }
    }

    private fun exits(body: List<Stmt>): Boolean =
        when (body.lastOrNull()) {
            is Return, is Break, is Continue, is Goto -> true
            else -> false
        }

    private fun condition(condition: Condition, parentBinds: Boolean = false): String = when (condition) {
        is Compare -> {
            val subject = expr(condition.left, COMPARISON)
            when (condition.comparison) {
                Cs2Op.BRANCH_IF_TRUE -> if (condition.negated) "isFalsy($subject)" else "isTruthy($subject)"
                Cs2Op.BRANCH_IF_FALSE -> if (condition.negated) "isTruthy($subject)" else "isFalsy($subject)"
                else -> "$subject ${condition.symbol} ${expr(condition.right, COMPARISON)}"
            }
        }
        is AndAll -> condition.terms.joinToString(" && ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
        is OrAny -> condition.terms.joinToString(" || ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
    }


    private fun precedence(symbol: String) = when (symbol) {
        "*", "/", "%" -> MULTIPLICATIVE
        "+", "-" -> ADDITIVE
        "&" -> BITWISE_AND
        "|" -> BITWISE_OR
        else -> ATOM
    }

    private fun precedenceOf(value: Expr) = when (value) {
        is Binary -> precedence(value.symbol)
        is Join -> ADDITIVE
        else -> ATOM
    }

    private fun expr(value: Expr, minimum: Int = 0): String {
        val text = render(value)
        return if (precedenceOf(value) < minimum) "($text)" else text
    }

    private fun render(value: Expr): String = when (value) {
        is IntConst -> Cs2Events.nameOf(value.value) ?: value.value.toString()
        is TypedConst -> typedConst(value)
        is StrConst -> "\"${Cs2Strings.escape(value.value)}\""
        is LongConst -> "${value.value}n"
        is LocalRef -> localName(value.slot, value.type)
        is TempRef -> value.name
        is ArrayRef -> "array${value.array}[${expr(value.index)}]"
        is VarRef -> varAccess(value)
        is Binary -> binary(value)
        is Join -> value.parts.joinToString(" + ") { expr(it, ADDITIVE + 1) }
        is OpCall -> {
            val variant = if (value.operand != 0) "<${value.operand}>" else ""
            "${value.op.tsName}$variant(${value.args.joinToString(", ") { expr(it) }})"
        }
        is ScriptCall -> "${Cs2Symbols.scriptName(value.scriptId)}(${value.args.joinToString(", ") { expr(it) }})"
        is Spread -> "...${expr(value.call)}"
        is Callback -> callback(value)
    }

    private fun binary(value: Binary): String {
        val level = precedence(value.symbol)
        val associative = value.symbol in ASSOCIATIVE &&
            (value.right as? Binary)?.symbol == value.symbol
        val left = expr(value.left, level)
        val right = if (value.symbol in MASKS && value.right is IntConst) {
            hex(value.right.value)
        } else {
            expr(value.right, if (associative) level else level + 1)
        }
        return "$left ${value.symbol} $right"
    }

    private fun hex(value: Int) = "0x%X".format(value.toLong() and 0xFFFFFFFFL)

    private fun caseKey(key: Int, kind: ArgType): String =
        if (kind == ArgType.INT) key.toString() else typedConst(TypedConst(key, kind))

    private fun typedConst(value: TypedConst): String {
        Cs2Events.nameOf(value.value)?.let { return it }
        return when (value.kind) {
            ArgType.TYPECHAR -> Cs2TypeChars.nameOf(value.value) ?: value.value.toString()
            ArgType.COMPONENT -> component(value.value)
            ArgType.COORD ->
                if (Cs2Packing.isPackedCoord(value.value)) {
                    "coord(${Cs2Packing.xOf(value.value)}, ${Cs2Packing.yOf(value.value)}, " +
                        "${Cs2Packing.planeOf(value.value)})"
                } else {
                    value.value.toString()
                }
            ArgType.SCRIPT -> scriptReference(value.value)
            ArgType.BOOLEAN -> when (value.value) {
                0 -> "false"
                1 -> "true"
                else -> value.value.toString()
            }
            ArgType.COLOUR ->
                if (value.value in 0..0xFFFFFF) "colour(0x%06X)".format(value.value)
                else value.value.toString()
            else -> constant(value.kind, value.value)
        }
    }

    private fun constant(kind: ArgType, id: Int): String {
        val type = Cs2Names.catalogType(kind) ?: return id.toString()
        val name = Cs2Names.identifier(type, id) ?: return id.toString()
        return "${Cs2Names.NAMESPACES.getValue(type)}.$name"
    }

    private fun component(hash: Int): String {
        Cs2Names.componentPath(hash)?.let { (interfaceName, componentName) ->
            return "${Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)}.$interfaceName.$componentName"
        }
        return hash.toString()
    }

    private fun scriptReference(id: Int): String =
        Cs2Names.scriptIdentifiers()[id] ?: id.toString()

    private fun varAccess(value: VarRef): String = Cs2Symbols.variableName(value)

    private fun callback(value: Callback): String {
        if (value.scriptId == -1) return "noHook(\"${Cs2Strings.escape(value.format)}\")"
        val format = "\"${Cs2Strings.escape(value.format)}\""
        val args = "[${value.args.joinToString(", ") { expr(it) }}]"
        val script = Cs2Symbols.scriptName(value.scriptId)
        if (!value.hasTriggerList) return "hook($script, $format, $args)"
        val triggers = "[${value.triggers.joinToString(", ") { expr(it) }}]"
        return "hook($script, $format, $args, $triggers)"
    }


    private fun line(text: String) {
        if (text.isEmpty()) {
            if (out.isEmpty() || out.endsWith("\n\n")) return
            out.append('\n')
            return
        }
        repeat(indent) { out.append("    ") }
        out.append(text).append('\n')
    }

    private inline fun indented(body: () -> Unit) {
        indent++
        body()
        indent--
    }

    private companion object {
        const val BITWISE_OR = 1
        const val BITWISE_AND = 2
        const val COMPARISON = 3
        const val ADDITIVE = 4
        const val MULTIPLICATIVE = 5
        const val ATOM = 100

        val ASSOCIATIVE = setOf("+", "*", "&", "|")

        val MASKS = setOf("&", "|")
    }
}
