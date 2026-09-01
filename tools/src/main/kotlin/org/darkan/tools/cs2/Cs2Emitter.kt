package org.darkan.tools.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.tools.cs2.ArgType
import org.darkan.tools.cs2.Cs2Packing
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
import org.darkan.tools.cs2.ir.While

/**
 * Renders a decompiled script as TypeScript.
 *
 * Locals are declared up front in slot order, so they can be renamed freely
 * without changing which slot they compile back to: the compiler allocates
 * slots by declaration order within each type.
 */
class Cs2Emitter(private val function: Cs2Function) {

    private val out = StringBuilder()
    private var indent = 0

    fun emit(): String {
        out.setLength(0)
        emitHeader()
        emitSignature()
        indent = 1
        emitLocalDeclarations()
        emitAll(function.body)
        indent = 0
        line("}")
        return out.toString()
    }

    private fun emitHeader() {
        // A triple-slash reference makes a single file resolve the opcode and
        // gameval names on its own; cross-script calls still need the tsconfig.
        // The prefix walks back out of the category folder the file sits in.
        val up = Cs2Layout.referencePrefix(function.scriptId)
        line("/// <reference path=\"$up${Cs2Declarations.OPCODES_FILE}\" />")
        line("/// <reference path=\"$up${Cs2Declarations.GAMEVALS_FILE}\" />")
        line("/// <reference path=\"$up${Cs2Declarations.VARS_FILE}\" />")
        // The gameval name is what this script *is*: `gamevals/clientscript.json`
        // is what turns it into the archive id the bytecode is written to, and the
        // path this file sits at says the same thing.
        line("// ${Cs2Layout.headerOf(function.scriptId)}")
        if (function.name != null) line("// name: ${function.name}")
        line("")
    }

    private fun emitSignature() {
        // Parameters follow the order callers push them, which can interleave the
        // stacks. Slots stay correct because they are assigned per type in
        // declaration order, so only the reading order changes.
        val total = function.intArgs + function.stringArgs + function.longArgs
        val order = function.argumentOrder.takeIf { it.size == total }
            ?: List(function.intArgs) { Cs2Type.INT } +
            List(function.stringArgs) { Cs2Type.STRING } +
            List(function.longArgs) { Cs2Type.LONG }

        val next = HashMap<Cs2Type, Int>()
        val params = order.map { type ->
            val slot = next.getOrDefault(type, 0)
            next[type] = slot + 1
            "${localName(slot, type)}: ${tsType(type)}"
        }

        val returns = when (function.returns.size) {
            0 -> "void"
            1 -> tsType(function.returns.first())
            else -> "[" + function.returns.joinToString(", ") { tsType(it) } + "]"
        }
        // Not exported: clientscripts share one flat namespace, and keeping the
        // files as scripts rather than modules is what lets one call another
        // without an import.
        line("function ${Cs2Symbols.scriptName(function.scriptId)}(${params.joinToString(", ")}): $returns {")
    }

    /**
     * Declares every local that is not a parameter, in slot order. Keeping the
     * order explicit is what makes renaming safe.
     */
    private fun emitLocalDeclarations() {
        var declared = false
        for (slot in function.intArgs until function.intLocals) {
            line("let ${localName(slot, Cs2Type.INT)}: number = 0;")
            declared = true
        }
        for (slot in function.stringArgs until function.stringLocals) {
            line("let ${localName(slot, Cs2Type.STRING)}: string = \"\";")
            declared = true
        }
        for (slot in function.longArgs until function.longLocals) {
            line("let ${localName(slot, Cs2Type.LONG)}: bigint = 0n;")
            declared = true
        }
        if (declared) line("")
    }

    private fun tsType(type: Cs2Type) = when (type) {
        Cs2Type.INT -> "number"
        Cs2Type.STRING -> "string"
        Cs2Type.LONG -> "bigint"
    }

    private fun localName(slot: Int, type: Cs2Type): String {
        val prefix = when (type) {
            Cs2Type.INT -> "int"
            Cs2Type.STRING -> "str"
            Cs2Type.LONG -> "long"
        }
        val args = when (type) {
            Cs2Type.INT -> function.intArgs
            Cs2Type.STRING -> function.stringArgs
            Cs2Type.LONG -> function.longArgs
        }
        return if (slot < args) "${prefix}Arg$slot" else "$prefix$slot"
    }

    // ----------------------------------------------------------- statements

    private fun emitAll(statements: List<Stmt>) = statements.forEach(::emit)

    private fun emit(statement: Stmt) {
        when (statement) {
            // A bare result that is thrown away reads better as an explicit
            // discard than as a lone identifier.
            is ExprStmt -> if (statement.call is TempRef) {
                line("discard(${expr(statement.call)});")
            } else {
                line("${expr(statement.call)};")
            }

            is LocalAssign ->
                line("${localName(statement.slot, statement.type)} = ${expr(statement.value)};")

            is ParallelAssign -> {
                val targets = statement.targets.joinToString(", ") { expr(it) }
                val values = statement.values.joinToString(", ") { expr(it) }
                line("[$targets] = [$values];")
            }

            is VarAssign -> line("${expr(statement.target)} = ${expr(statement.value)};")

            is ArrayAssign ->
                line("array${statement.array}[${expr(statement.index)}] = ${expr(statement.value)};")

            is ArrayDefine ->
                line("defineArray(${statement.array}, ${statement.elementType}, ${expr(statement.size)});")

            is MultiAssign -> {
                val names = statement.names.joinToString(", ") { it.name }
                line("const [$names] = ${expr(statement.call)};")
            }

            is Return -> when (statement.values.size) {
                0 -> line("return;")
                1 -> line("return ${expr(statement.values.first())};")
                else -> line("return [${statement.values.joinToString(", ") { expr(it) }}];")
            }

            is If -> {
                line("if (${condition(statement.condition)}) {")
                indented { emitAll(statement.then) }
                if (statement.otherwise.isEmpty()) {
                    line("}")
                } else {
                    line("} else {")
                    indented { emitAll(statement.otherwise) }
                    line("}")
                }
            }

            is While -> {
                line("while (${condition(statement.condition)}) {")
                indented { emitAll(statement.body) }
                line("}")
            }

            is Switch -> {
                line("switch (${expr(statement.subject)}) {")
                indented {
                    // `default:` keeps the position it had in the bytecode:
                    // first when it falls out of the switch directly, last when
                    // a jump leads to it.
                    if (statement.defaultFirst && statement.default.isNotEmpty()) {
                        line("default:")
                        indented {
                            emitAll(statement.default)
                            if (!exits(statement.default)) line("break;")
                        }
                    }
                    for (case in statement.cases) {
                        case.keys.forEach { line("case $it:") }
                        indented {
                            emitAll(case.body)
                            if (!exits(case.body)) line("break;")
                        }
                    }
                    if (!statement.defaultFirst && statement.default.isNotEmpty()) {
                        line("default:")
                        indented { emitAll(statement.default) }
                    }
                }
                line("}")
            }

            is Label -> {
                indent--
                // The empty statement keeps a label legal even when it lands at
                // the end of a block, which TypeScript otherwise rejects.
                line("${statement.name}: ;")
                indent++
            }

            is Goto -> line("goto(\"${statement.label}\");")
            is Break -> line(if (statement.label == null) "break;" else "break ${statement.label};")
            is Continue -> line(if (statement.label == null) "continue;" else "continue ${statement.label};")
        }
    }

    /** True when a body already transfers control, so no `break` is needed. */
    private fun exits(body: List<Stmt>): Boolean =
        when (body.lastOrNull()) {
            is Return, is Break, is Continue, is Goto -> true
            else -> false
        }

    /**
     * `BRANCH_IF_TRUE` / `BRANCH_IF_FALSE` test a single value against a literal
     * rather than comparing two. Rendering them as `x == 1` would re-compile to
     * a different opcode, so they keep a form of their own.
     */
    private fun condition(condition: Condition, parentBinds: Boolean = false): String = when (condition) {
        is Compare -> {
            val subject = expr(condition.left)
            when (condition.comparison.opName) {
                "BRANCH_IF_TRUE" -> if (condition.negated) "isFalsy($subject)" else "isTruthy($subject)"
                "BRANCH_IF_FALSE" -> if (condition.negated) "isTruthy($subject)" else "isFalsy($subject)"
                // A negated comparison is one the compiler used to *skip* the
                // body, so the opcode is kept and the sense written out.
                else -> {
                    val test = "$subject ${condition.naturalSymbol} ${expr(condition.right)}"
                    if (condition.negated) "!($test)" else test
                }
            }
        }
        is AndAll -> condition.terms.joinToString(" && ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
        is OrAny -> condition.terms.joinToString(" || ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
    }

    // ---------------------------------------------------------- expressions

    private fun expr(value: Expr): String = when (value) {
        is IntConst -> value.value.toString()
        is TypedConst -> typedConst(value)
        is StrConst -> "\"${Cs2Strings.escape(value.value)}\""
        is LongConst -> "${value.value}n"
        is LocalRef -> localName(value.slot, value.type)
        is TempRef -> value.name
        is ArrayRef -> "array${value.array}[${expr(value.index)}]"
        is VarRef -> varAccess(value)
        is Binary -> "(${expr(value.left)} ${value.symbol} ${expr(value.right)})"
        is Join -> value.parts.joinToString(" + ") { expr(it) }
        is OpCall -> {
            // The instruction's own operand rides along as a type argument, so
            // it survives editing without cluttering the call's arguments.
            val variant = if (value.operand != 0) "<${value.operand}>" else ""
            "${value.op.tsName}$variant(${value.args.joinToString(", ") { expr(it) }})"
        }
        is ScriptCall -> "${Cs2Symbols.scriptName(value.scriptId)}(${value.args.joinToString(", ") { expr(it) }})"
        is Callback -> callback(value)
    }

    /**
     * Spells out a composite constant. A component hash becomes the interface and
     * component it packs, by their gameval names where they have them; every other
     * id becomes its gameval constant. Values that carry no such structure - `-1`
     * and other sentinels - and ids the catalogs have no proven name for keep the
     * bare number, so they still round-trip unchanged.
     */
    private fun typedConst(value: TypedConst): String = when (value.kind) {
        ArgType.COMPONENT -> component(value.value)
        ArgType.COORD ->
            if (Cs2Packing.isPackedCoord(value.value)) {
                "tile(${Cs2Packing.xOf(value.value)}, ${Cs2Packing.yOf(value.value)}, " +
                    "${Cs2Packing.planeOf(value.value)})"
            } else {
                "tile(${value.value})"
            }
        // A script referred to by id names the function it is, so renaming the
        // function renames the reference too.
        ArgType.SCRIPT -> "scriptId(${scriptReference(value.value)})"
        // A flag reads as a flag; anything outside 0/1 keeps its number so the
        // value still round-trips.
        ArgType.BOOLEAN -> when (value.value) {
            0 -> "false"
            1 -> "true"
            else -> value.value.toString()
        }
        ArgType.COLOUR -> "colour(0x%06X)".format(value.value and 0xFFFFFF)
        ArgType.INT -> value.value.toString()
        else -> {
            val helper = Cs2Names.helperFor(value.kind)
            if (helper == null) value.value.toString() else "$helper(${constant(value.kind, value.value)})"
        }
    }

    /** The gameval constant for an id, or its number when the catalog has no name. */
    private fun constant(kind: ArgType, id: Int): String {
        val type = Cs2Names.catalogType(kind) ?: return id.toString()
        val name = Cs2Names.identifier(type, id) ?: return id.toString()
        return "${Cs2Names.NAMESPACES.getValue(type)}.$name"
    }

    private fun component(hash: Int): String {
        Cs2Names.componentPath(hash)?.let { (interfaceName, componentName) ->
            return "component(${Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)}.$interfaceName.$componentName)"
        }
        if (Cs2Packing.isPackedComponent(hash)) {
            return "component(${Cs2Packing.interfaceOf(hash)}, ${Cs2Packing.componentOf(hash)})"
        }
        return "component($hash)"
    }

    /** The function name of a clientscript, or its id when the cache has no such script. */
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

    // --------------------------------------------------------------- output

    private fun line(text: String) {
        if (text.isEmpty()) {
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
}
