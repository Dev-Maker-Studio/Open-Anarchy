package org.darkan.cs2.compile

import org.darkan.core.gameval.Gameval
import org.darkan.cs2.Cs2Identifiers
import org.darkan.cs2.Cs2Layout
import org.darkan.cs2.Cs2Names
import org.darkan.cs2.Cs2Op
import org.darkan.cs2.ArgType
import org.darkan.cs2.Cs2SymbolTable
import org.darkan.cs2.Cs2Events
import org.darkan.cs2.Cs2Symbols
import org.darkan.cs2.Cs2TypeChars
import org.darkan.cs2.Cs2Packing
import org.darkan.cs2.OpKind
import org.darkan.cs2.Cs2Strings
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
import org.darkan.cs2.ir.negate
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
import org.darkan.cs2.ir.SwitchCase
import org.darkan.cs2.ir.TempRef
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.ir.VarAssign
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace
import org.darkan.cs2.ir.While

class Cs2Parser(
    source: String,
    private val paramTypes: (Int) -> Boolean = { false },
    private val symbols: Cs2SymbolTable = Cs2SymbolTable.EMPTY,
    private val relativePath: String? = null,
    private val returnTypes: (Int) -> List<Cs2Type> = { emptyList() },
) {

    private val headerId: Int? = Cs2Layout.scriptIdOf(source, relativePath)

    private val gamevalHeader: String? = Cs2Layout.gamevalHeader(source)

    private val nameComment: String? =
        Regex("^// name: (.*)$", RegexOption.MULTILINE).find(source)?.groupValues?.get(1)

    private val tokens = Cs2Lexer(source).tokenise()
    private var at = 0

    private class Local(val slot: Int, val type: Cs2Type)

    private val locals = HashMap<String, Local>()
    private var intCount = 0
    private var stringCount = 0
    private var longCount = 0
    private var intArgs = 0
    private var stringArgs = 0
    private var longArgs = 0
    private var scriptId = -1
    private var scriptName: String? = null
    private var returns = emptyList<Cs2Type>()

    private val argumentTypes = ArrayList<Cs2Type>()

    fun parse(): Cs2Function {
        parseSignature()
        val body = parseBlock()
        return Cs2Function(
            scriptId = scriptId,
            name = scriptName,
            intArgs = intArgs,
            stringArgs = stringArgs,
            longArgs = longArgs,
            intLocals = intCount,
            stringLocals = stringCount,
            longLocals = longCount,
            body = body,
            returns = returns,
        )
    }


    private fun parseSignature() {
        val name = parseHeader()
        if (headerId == null && gamevalHeader != null) {
            error(
                "'$gamevalHeader' is not a clientscript in gamevals/clientscript.json; " +
                    "the catalog was renamed without `cs2 apply-names`",
            )
        }
        scriptId = headerId ?: Cs2Symbols.scriptOf(name) ?: symbols.script(name)
            ?: error("Cannot tell which clientscript '$name' is")
    }

    private fun parseHeader(): String {
        scriptName = nameComment
        while (peek().kind == TokenKind.IDENT && peek().text != "function") skip()
        expectIdent("function")
        val name = expect(TokenKind.IDENT).text
        expectPunct("(")
        while (!atPunct(")")) {
            val parameter = expect(TokenKind.IDENT).text
            expectPunct(":")
            val type = parseType()
            declare(parameter, type, argument = true)
            argumentTypes.add(type)
            if (atPunct(",")) skip()
        }
        expectPunct(")")
        expectPunct(":")
        returns = parseReturnType()
        expectPunct("{")
        return name
    }

    private fun parseType(): Cs2Type = typeNamed(expect(TokenKind.IDENT).text)

    private fun parseReturnType(): List<Cs2Type> {
        if (atPunct("[")) {
            skip()
            val types = ArrayList<Cs2Type>()
            while (!atPunct("]")) {
                types.add(parseType())
                if (atPunct(",")) skip()
            }
            skip()
            return types
        }
        val text = peek().text
        if (text == "void") {
            skip()
            return emptyList()
        }
        return listOf(parseType())
    }

    private fun declare(name: String, type: Cs2Type, argument: Boolean): Local {
        val slot = when (type) {
            Cs2Type.INT -> intCount++
            Cs2Type.STRING -> stringCount++
            Cs2Type.LONG -> longCount++
        }
        if (argument) {
            when (type) {
                Cs2Type.INT -> intArgs++
                Cs2Type.STRING -> stringArgs++
                Cs2Type.LONG -> longArgs++
            }
        }
        val local = Local(slot, type)
        locals[name] = local
        return local
    }


    private fun parseBlock(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (!atPunct("}")) {
            if (peek().kind == TokenKind.EOF) error("Unexpected end of input, expected '}'")
            out.add(parseStatement() ?: continue)
        }
        skip()
        return out
    }

    private fun parseStatement(): Stmt? {
        if (atPunct(";")) {
            skip()
            return null
        }
        val token = peek()
        if (token.kind == TokenKind.IDENT) {
            when (token.text) {
                "let", "const" -> return parseDeclaration()
                "if" -> return parseIf()
                "while" -> return parseWhile()
                "switch" -> return parseSwitch()
                "return" -> return parseReturn()
                "goto" -> {
                    skip()
                    expectPunct("(")
                    val label = expect(TokenKind.STRING).text
                    expectPunct(")")
                    expectPunct(";")
                    return Goto(label)
                }
                "break" -> { skip(); expectPunct(";"); return Break() }
                "continue" -> { skip(); expectPunct(";"); return Continue() }
            }
            if (tokens.getOrNull(at + 1)?.text == ":" && token.text.startsWith("L")) {
                skip()
                skip()
                return Label(token.text)
            }
        }
        if (atPunct("[")) return parseParallelAssign()
        return parseExpressionStatement()
    }

    private fun parseDeclaration(): Stmt? {
        val keyword = expect(TokenKind.IDENT).text
        if (atPunct("[")) {
            return if (keyword == "const") parseTemporaries() else parseDeclaredDestructuring()
        }

        val name = expect(TokenKind.IDENT).text
        expectPunct(":")
        val type = parseType()
        val local = declare(name, type, argument = false)
        if (!atPunct("=")) {
            expectPunct(";")
            return null
        }
        skip()
        val value = parseExpression()
        expectPunct(";")
        return LocalAssign(local.slot, local.type, value, declare = true)
    }

    private fun parseDestructuring(): Stmt {
        expectPunct("[")
        val targets = ArrayList<Expr>()
        while (!atPunct("]")) {
            val target = parseExpression()
            require(target is LocalRef || target is VarRef) { "Cannot assign to $target" }
            targets.add(target)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        val call = parseExpression()
        expectPunct(";")
        return DestructureAssign(targets, call, declare = false)
    }

    private fun parseDeclaredDestructuring(): Stmt {
        expectPunct("[")
        val names = ArrayList<String>()
        while (!atPunct("]")) {
            names.add(expect(TokenKind.IDENT).text)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        val call = parseExpression()
        expectPunct(";")
        val types = resultTypes(call)
        if (types.size != names.size) {
            error("${names.size} names but the call leaves ${types.size} values")
        }
        val targets = names.mapIndexed { index, name ->
            val local = declare(name, types[index], argument = false)
            LocalRef(local.slot, local.type) as Expr
        }
        return DestructureAssign(targets, call, declare = true)
    }

    private fun resultTypes(call: Expr): List<Cs2Type> = when (call) {
        is ScriptCall -> returnTypes(call.scriptId)
        is OpCall -> List(call.op.pushInt) { Cs2Type.INT } +
            List(call.op.pushStr) { Cs2Type.STRING } +
            List(call.op.pushLong) { Cs2Type.LONG }
        else -> error("$call does not leave several values")
    }

    private fun parseTemporaries(): Stmt {
        expectPunct("[")
        val names = ArrayList<String>()
        while (!atPunct("]")) {
            names.add(expect(TokenKind.IDENT).text)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        val call = parseExpression()
        expectPunct(";")
        val temps = names.map { TempRef(it, tempType(it)) }
        temps.forEach { tempTypes[it.name] = it }
        return MultiAssign(temps, call)
    }

    private val tempTypes = HashMap<String, TempRef>()

    private fun opResultType(op: Cs2Op, args: List<Expr>): Cs2Type {
        if (op.kind == OpKind.PARAM) {
            val yieldsString = if (op == Cs2Op.ENUM) {
                (args.getOrNull(1) as? IntConst)?.value == 's'.code
            } else {
                val paramId = (args.lastOrNull { it.type == Cs2Type.INT } as? IntConst)?.value
                paramId != null && paramTypes(paramId)
            }
            return if (yieldsString) Cs2Type.STRING else Cs2Type.INT
        }
        return when {
            op.pushStr == 1 && op.pushInt == 0 && op.pushLong == 0 -> Cs2Type.STRING
            op.pushLong == 1 && op.pushInt == 0 && op.pushStr == 0 -> Cs2Type.LONG
            else -> Cs2Type.INT
        }
    }

    private fun packedConstant(name: String, args: List<Expr>): Expr? {
        val values = args.map {
            when (it) {
                is IntConst -> it.value
                is TypedConst -> it.value
                is ScriptCall -> if (it.args.isEmpty()) it.scriptId else return null
                else -> return null
            }
        }
        val kind = Cs2Names.argTypeOf(name) ?: return null
        val packed = when {
            kind == ArgType.COMPONENT && values.size == 2 -> Cs2Packing.packComponent(values[0], values[1])
            kind == ArgType.COORD && values.size == 3 -> Cs2Packing.packCoord(values[0], values[1], values[2])
            values.size == 1 -> values[0]
            else -> error("$name takes ${values.size} arguments")
        }
        return TypedConst(packed, kind)
    }

    private fun tempType(name: String) = when {
        name.startsWith("tmpStr") -> Cs2Type.STRING
        name.startsWith("tmpLong") -> Cs2Type.LONG
        else -> Cs2Type.INT
    }

    private fun parseIf(): Stmt {
        expectIdent("if")
        expectPunct("(")
        val condition = parseCondition()
        expectPunct(")")
        expectPunct("{")
        val then = parseBlock()
        var otherwise = emptyList<Stmt>()
        if (peek().text == "else") {
            skip()
            if (peek().text == "if") {
                otherwise = listOf(parseIf())
            } else {
                expectPunct("{")
                otherwise = parseBlock()
            }
        }
        return If(condition, then, otherwise)
    }

    private fun parseWhile(): Stmt {
        expectIdent("while")
        expectPunct("(")
        val condition = if (peek().text == "true") skip().let { null } else parseCondition()
        expectPunct(")")
        expectPunct("{")
        return While(condition, parseBlock())
    }

    private fun parseSwitch(): Stmt {
        expectIdent("switch")
        expectPunct("(")
        val subject = parseExpression()
        expectPunct(")")
        expectPunct("{")

        val cases = ArrayList<SwitchCase>()
        var default = emptyList<Stmt>()
        while (!atPunct("}")) {
            if (peek().text == "default") {
                skip()
                expectPunct(":")
                default = parseCaseBody()
                continue
            }
            val keys = ArrayList<Int>()
            while (peek().text == "case") {
                skip()
                keys.add(parseCaseKey())
                expectPunct(":")
            }
            cases.add(SwitchCase(keys, parseCaseBody()))
        }
        skip()
        return Switch(subject, cases, default, table = -1)
    }

    private fun constantValue(value: Expr): Int = when (value) {
        is IntConst -> value.value
        is TypedConst -> value.value
        else -> error("Expected a constant, but found $value")
    }

    private fun parseCaseKey(): Int = when (val key = parseExpression()) {
        is IntConst -> key.value
        is TypedConst -> key.value
        else -> error("A case key has to be a constant, but found $key")
    }

    private fun parseCaseBody(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (!atPunct("}") && peek().text != "case" && peek().text != "default") {
            if (peek().text == "break" && tokens.getOrNull(at + 1)?.text == ";") {
                skip()
                skip()
                break
            }
            out.add(parseStatement() ?: continue)
        }
        return out
    }

    private fun parseReturn(): Stmt {
        expectIdent("return")
        if (atPunct(";")) {
            skip()
            return Return(emptyList())
        }
        val values = ArrayList<Expr>()
        if (atPunct("[")) {
            skip()
            while (!atPunct("]")) {
                values.add(parseExpression())
                if (atPunct(",")) skip()
            }
            skip()
        } else {
            values.add(parseExpression())
        }
        expectPunct(";")
        return Return(values)
    }

    private fun parseParallelAssign(): Stmt {
        val mark = at
        expectPunct("[")
        val targets = ArrayList<Expr>()
        while (!atPunct("]")) {
            val target = parseExpression()
            require(target is LocalRef || target is VarRef) { "Cannot assign to $target" }
            targets.add(target)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        if (!atPunct("[")) {
            at = mark
            return parseDestructuring()
        }
        expectPunct("[")
        val values = ArrayList<Expr>()
        while (!atPunct("]")) {
            values.add(parseExpression())
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct(";")
        return ParallelAssign(targets, values)
    }

    private fun parseExpressionStatement(): Stmt {
        val target = parseExpression()
        if (atPunct("=")) {
            skip()
            val value = parseExpression()
            expectPunct(";")
            return when (target) {
                is LocalRef -> LocalAssign(target.slot, target.type, value, declare = false)
                is VarRef -> VarAssign(target, value)
                is ArrayRef -> ArrayAssign(target.array, target.index, value)
                else -> error("Cannot assign to $target")
            }
        }
        expectPunct(";")
        if (target is OpCall && target.op == Cs2Op.DEFINE_ARRAY) {
            val array = constantValue(target.args[0])
            val elementType = constantValue(target.args[1])
            return ArrayDefine(array, elementType, target.args[2])
        }
        return ExprStmt(target)
    }


    private fun parseCondition(): Condition = parseOr()

    private fun parseOr(): Condition {
        val terms = ArrayList<Condition>()
        terms.add(parseAnd())
        while (atPunct("||")) {
            skip()
            terms.add(parseAnd())
        }
        return if (terms.size == 1) terms.first() else OrAny(terms)
    }

    private fun parseAnd(): Condition {
        val terms = ArrayList<Condition>()
        terms.add(parseComparison())
        while (atPunct("&&")) {
            skip()
            terms.add(parseComparison())
        }
        return if (terms.size == 1) terms.first() else AndAll(terms)
    }

    private fun parseComparison(): Condition {
        if (atPunct("!")) {
            skip()
            expectPunct("(")
            val inner = parseOr()
            expectPunct(")")
            return when (inner) {
                is Compare -> inner.copy(negated = true)
                else -> inner.negate()
            }
        }
        if (atPunct("(")) {
            val mark = at
            try {
                skip()
                val inner = parseOr()
                if (atPunct(")")) {
                    skip()
                    return inner
                }
            } catch (e: Exception) {
            }
            at = mark
        }

        val left = parseExpression()
        if (left is OpCall && (left.op == Cs2Op.BRANCH_IF_TRUE || left.op == Cs2Op.BRANCH_IF_FALSE)) {
            return Compare(left.op, left.args.first(), IntConst(0), negated = false)
        }
        val symbol = expect(TokenKind.PUNCT).text
        val right = parseExpression()
        val longs = left.type == Cs2Type.LONG || right.type == Cs2Type.LONG
        val op = when (symbol) {
            "==", "===" -> if (longs) Cs2Op.LONG_BRANCH_EQUALS else Cs2Op.BRANCH_EQUALS
            "!=", "!==" -> if (longs) Cs2Op.LONG_BRANCH_NOT else Cs2Op.BRANCH_NOT
            "<" -> if (longs) Cs2Op.LONG_BRANCH_LESS_THAN else Cs2Op.BRANCH_LESS_THAN
            ">" -> if (longs) Cs2Op.LONG_BRANCH_GREATER_THAN else Cs2Op.BRANCH_GREATER_THAN
            "<=" -> if (longs) Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS else Cs2Op.BRANCH_LESS_THAN_OR_EQUALS
            ">=" -> if (longs) Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS else Cs2Op.BRANCH_GREATER_THAN_OR_EQUALS
            else -> error("Unsupported comparison '$symbol'")
        }
        return Compare(op, left, right, negated = false)
    }

    private val precedenceLevels = listOf(
        setOf("|"), setOf("&"), setOf("+", "-"), setOf("*", "/", "%"),
    )

    private fun parseExpression(): Expr = parseBinary(0)

    private fun parseBinary(level: Int): Expr {
        if (level >= precedenceLevels.size) return parseUnary()
        val operators = precedenceLevels[level]
        var left = parseBinary(level + 1)
        var parts: MutableList<Expr>? = null
        while (peek().kind == TokenKind.PUNCT && peek().text in operators) {
            val symbol = skip().text
            val right = parseBinary(level + 1)
            if (left.type == Cs2Type.STRING || right.type == Cs2Type.STRING) {
                val joined = parts ?: ArrayList<Expr>().also { it.add(left) }
                joined.add(right)
                parts = joined
                left = Join(joined.toList())
            } else {
                left = Binary(symbol, left, right, Cs2Type.INT)
                parts = null
            }
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (atPunct("-")) {
            skip()
            return when (val value = parsePrimary()) {
                is IntConst -> IntConst(-value.value)
                is LongConst -> LongConst(-value.value)
                else -> Binary("-", IntConst(0), value, Cs2Type.INT)
            }
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val token = peek()
        if (atPunct("(")) {
            skip()
            val inner = parseExpression()
            expectPunct(")")
            return inner
        }
        return when (token.kind) {
            TokenKind.NUMBER -> IntConst(skip().text.let(::parseIntLiteral))
            TokenKind.BIGINT -> LongConst(skip().text.toLong())
            TokenKind.STRING -> StrConst(Cs2Strings.unescape(skip().text))
            TokenKind.IDENT -> parseIdentifier()
            else -> error("Unexpected token $token")
        }
    }

    private fun parseIntLiteral(text: String): Int =
        if (text.startsWith("0x") || text.startsWith("0X")) text.substring(2).toLong(16).toInt()
        else text.toLong().toInt()

    private fun parseSignedInt(): Int {
        val negative = atPunct("-").also { if (it) skip() }
        val value = parseIntLiteral(expect(TokenKind.NUMBER).text)
        return if (negative) -value else value
    }

    private fun parseIdentifier(): Expr {
        val name = skip().text

        if (name == "true") return TypedConst(1, ArgType.BOOLEAN)
        if (name == "false") return TypedConst(0, ArgType.BOOLEAN)
        Cs2TypeChars.charNamed(name)?.let { return TypedConst(it.code, ArgType.TYPECHAR) }
        Cs2Events.valueOf(name)?.let { return IntConst(it) }

        if (atPunct(".") && name in Cs2Names.NAMESPACES.values) return gamevalConstant(name)

        locals[name]?.let { return LocalRef(it.slot, it.type) }
        tempTypes[name]?.let { return it }

        (symbols.variable(name) ?: Cs2Symbols.variableOf(name))?.let { return it }

        if (name.startsWith("array") && name.drop(5).toIntOrNull() != null) {
            expectPunct("[")
            val index = parseExpression()
            expectPunct("]")
            return ArrayRef(name.drop(5).toInt(), index)
        }

        val referenced = Cs2Symbols.scriptOf(name) ?: symbols.script(name)
        if (referenced != null && !atPunct("(")) {
            return TypedConst(referenced, ArgType.SCRIPT)
        }
        var operand = 0
        if (atPunct("<")) {
            skip()
            operand = parseSignedInt()
            expectPunct(">")
        }
        if (atPunct("(")) return parseCall(name, operand)
        error("Unknown identifier '$name'")
    }

    private fun gamevalConstant(namespace: String): Expr {
        val path = StringBuilder(namespace)
        expectPunct(".")
        val first = expect(TokenKind.IDENT).text
        path.append('.').append(first)
        var second: String? = null
        if (namespace == COMPONENT_NAMESPACE && atPunct(".")) {
            skip()
            second = expect(TokenKind.IDENT).text
            path.append('.').append(second)
        }
        val text = path.toString()
        val kind = Cs2Names.NAMESPACE_KINDS[namespace] ?: ArgType.INT
        symbols.gameval(text)?.let { return TypedConst(it, kind) }
        val id = when {
            namespace != COMPONENT_NAMESPACE -> {
                val type = Cs2Names.NAMESPACES.entries.first { it.value == namespace }.key
                Cs2Names.idOf(type, first)
            }
            second == null -> error("'$text' is an interface group, not a component")
            second == Cs2Identifiers.IFACE_MEMBER -> Cs2Names.componentInterfaceId(first)
            else -> Cs2Names.componentOf(first, second)
        }
        return TypedConst(id ?: error("Unknown gameval '$text'"), kind)
    }

    private val COMPONENT_NAMESPACE = Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)

    private fun parseCall(name: String, operand: Int = 0): Expr {
        expectPunct("(")
        val args = ArrayList<Expr>()
        val lists = ArrayList<List<Expr>>()
        while (!atPunct(")")) {
            if (atPunct("[")) {
                skip()
                val items = ArrayList<Expr>()
                while (!atPunct("]")) {
                    items.add(parseExpression())
                    if (atPunct(",")) skip()
                }
                skip()
                lists.add(items)
                args.add(IntConst(lists.size - 1))
            } else if (atPunct("...")) {
                skip()
                args.add(Spread(parseExpression()))
            } else {
                args.add(parseExpression())
            }
            if (atPunct(",")) skip()
        }
        skip()

        if (name == "hook") {
            val scriptRef = args.first()
            val callee = (scriptRef as? ScriptCall)?.scriptId
                ?: (scriptRef as? TypedConst)?.value
                ?: (scriptRef as? IntConst)?.value
                ?: error("hook() needs a script reference")
            val format = (args.getOrNull(1) as? StrConst)?.value
                ?: error("hook() needs its type spec")
            return Callback(callee, format, lists.getOrNull(0).orEmpty(), lists.getOrNull(1).orEmpty())
        }
        val named = Cs2Op.byTsName(name)
        val arity = named?.let { it.popInt + it.popStr + it.popLong }
        if (arity != args.size) packedConstant(name, args)?.let { return it }
        if (name == "discard") return args.first()
        if (name == "noHook") {
            return Callback(-1, (args.firstOrNull() as? StrConst)?.value ?: "", emptyList(), emptyList())
        }
        if (name == "defineArray") {
            return OpCall(Cs2Op.DEFINE_ARRAY, args, Cs2Type.INT)
        }
        if (name == "isTruthy") {
            return OpCall(Cs2Op.BRANCH_IF_TRUE, args, Cs2Type.INT)
        }
        if (name == "isFalsy") {
            return OpCall(Cs2Op.BRANCH_IF_FALSE, args, Cs2Type.INT)
        }
        val callee = Cs2Symbols.scriptOf(name) ?: symbols.script(name)
        if (callee != null) return ScriptCall(callee, args, Cs2Type.INT)
        val op = Cs2Op.byTsName(name) ?: error("Unknown opcode function '$name'")
        return OpCall(op, args, opResultType(op, args), operand)
    }


    private fun peek() = tokens[at]

    private fun skip() = tokens[at++]

    private fun atPunct(text: String) = peek().kind == TokenKind.PUNCT && peek().text == text

    private fun expectPunct(text: String) {
        if (!atPunct(text)) error("Expected '$text' but found ${peek()}")
        at++
    }

    private fun expectIdent(text: String) {
        if (peek().kind != TokenKind.IDENT || peek().text != text) error("Expected '$text' but found ${peek()}")
        at++
    }

    private fun expect(kind: TokenKind): Token {
        if (peek().kind != kind) error("Expected $kind but found ${peek()}")
        return tokens[at++]
    }

    class Signature(
        val name: String,
        val arguments: List<Cs2Type>,
        val returns: List<Cs2Type>,
    )

    companion object {

        fun signature(source: String): Signature? {
            val declaration = DECLARATION.find(source) ?: return null
            val body = source.indexOf('{', declaration.range.first)
            val header = if (body < 0) source else source.substring(0, body + 1)
            return read(header) ?: if (body < 0) null else read(source)
        }

        fun typeNamed(text: String): Cs2Type = when {
            text == "number" -> Cs2Type.INT
            text == "string" -> Cs2Type.STRING
            text == "bigint" -> Cs2Type.LONG
            Cs2Names.isAlias(text) -> Cs2Type.INT
            else -> error("Unsupported type '$text'")
        }

        private fun read(header: String): Signature? = try {
            Cs2Parser(header).headerOnly()
        } catch (e: Exception) {
            null
        }

        private val DECLARATION = Regex("""^function \w+\s*\(""", RegexOption.MULTILINE)
    }

    private fun headerOnly(): Signature =
        Signature(name = parseHeader(), arguments = argumentTypes.toList(), returns = returns)
}
