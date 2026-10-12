package org.darkan.cs2.ir

import org.darkan.cs2.ArgType
import org.darkan.cs2.Cs2Op

enum class Cs2Type { INT, STRING, LONG }


sealed interface Expr {
    val type: Cs2Type
}

data class IntConst(val value: Int) : Expr {
    override val type get() = Cs2Type.INT
}

data class StrConst(val value: String) : Expr {
    override val type get() = Cs2Type.STRING
}

data class LongConst(val value: Long) : Expr {
    override val type get() = Cs2Type.LONG
}

data class TypedConst(val value: Int, val kind: ArgType) : Expr {
    override val type get() = Cs2Type.INT
}

data class LocalRef(val slot: Int, override val type: Cs2Type) : Expr

data class ArrayRef(val array: Int, val index: Expr) : Expr {
    override val type get() = Cs2Type.INT
}

enum class VarSpace { VARP, VARBIT, VARC, VARC_STRING, VARN, VARNBIT, CLAN, CLAN_SETTING }

data class VarRef(
    val space: VarSpace,
    val id: Int,
    override val type: Cs2Type,
    val variant: String? = null,
) : Expr

data class Binary(val symbol: String, val left: Expr, val right: Expr, override val type: Cs2Type) : Expr

data class Join(val parts: List<Expr>) : Expr {
    override val type get() = Cs2Type.STRING
}

data class OpCall(
    val op: Cs2Op,
    val args: List<Expr>,
    override val type: Cs2Type,
    val operand: Int = 0,
) : Expr

data class ScriptCall(val scriptId: Int, val args: List<Expr>, override val type: Cs2Type) : Expr

data class TempRef(val name: String, override val type: Cs2Type) : Expr

data class Spread(val call: Expr) : Expr {
    override val type get() = call.type
}

data class Callback(
    val scriptId: Int,
    val format: String,
    val args: List<Expr>,
    val triggers: List<Expr>,
) : Expr {
    val hasTriggerList: Boolean get() = format.endsWith("Y")

    override val type get() = Cs2Type.INT
}


sealed interface Stmt

data class ExprStmt(val call: Expr) : Stmt

data class LocalAssign(val slot: Int, val type: Cs2Type, val value: Expr, val declare: Boolean) : Stmt

data class VarAssign(val target: VarRef, val value: Expr) : Stmt

data class ArrayAssign(val array: Int, val index: Expr, val value: Expr) : Stmt

data class ArrayDefine(val array: Int, val elementType: Int, val size: Expr) : Stmt

data class ParallelAssign(
    val targets: List<Expr>,
    val values: List<Expr>,
) : Stmt

data class MultiAssign(val names: List<TempRef>, val call: Expr) : Stmt

data class DestructureAssign(
    val targets: List<Expr>,
    val call: Expr,
    val declare: Boolean = false,
) : Stmt

data class Return(val values: List<Expr>) : Stmt

data class If(val condition: Condition, val then: List<Stmt>, val otherwise: List<Stmt>) : Stmt

data class While(val condition: Condition?, val body: List<Stmt>) : Stmt

data class SwitchCase(val keys: List<Int>, val body: List<Stmt>)

data class Switch(
    val subject: Expr,
    val cases: List<SwitchCase>,
    val default: List<Stmt>,
    val table: Int,
    val keyKind: ArgType = ArgType.INT,
) : Stmt

data class Goto(val label: String) : Stmt

data class Label(val name: String) : Stmt

data class Break(val label: String? = null) : Stmt

data class Continue(val label: String? = null) : Stmt

sealed interface Condition

data class Compare(
    val comparison: Cs2Op,
    val left: Expr,
    val right: Expr,
    val negated: Boolean,
) : Condition {
    val symbol: String get() = if (negated) invert(naturalSymbol) else naturalSymbol

    val naturalSymbol: String
        get() {
            return when (comparison) {
                Cs2Op.BRANCH_EQUALS, Cs2Op.LONG_BRANCH_EQUALS -> "=="
                Cs2Op.BRANCH_NOT, Cs2Op.LONG_BRANCH_NOT -> "!="
                Cs2Op.BRANCH_LESS_THAN, Cs2Op.LONG_BRANCH_LESS_THAN -> "<"
                Cs2Op.BRANCH_GREATER_THAN, Cs2Op.LONG_BRANCH_GREATER_THAN -> ">"
                Cs2Op.BRANCH_LESS_THAN_OR_EQUALS, Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS -> "<="
                Cs2Op.BRANCH_GREATER_THAN_OR_EQUALS, Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS -> ">="
                Cs2Op.BRANCH_IF_TRUE -> "=="
                Cs2Op.BRANCH_IF_FALSE -> "=="
                else -> error("Not a comparison: ${comparison.name}")
            }
        }

    private fun invert(symbol: String) = when (symbol) {
        "==" -> "!="
        "!=" -> "=="
        "<" -> ">="
        ">" -> "<="
        "<=" -> ">"
        ">=" -> "<"
        else -> error("Cannot invert $symbol")
    }
}

data class AndAll(val terms: List<Condition>) : Condition

data class OrAny(val terms: List<Condition>) : Condition

fun Condition.negate(): Condition = when (this) {
    is Compare -> copy(negated = !negated)
    is AndAll -> OrAny(terms.map { it.negate() })
    is OrAny -> AndAll(terms.map { it.negate() })
}

class Cs2Function(
    val scriptId: Int,
    val name: String?,
    val intArgs: Int,
    val stringArgs: Int,
    val longArgs: Int,
    val intLocals: Int,
    val stringLocals: Int,
    val longLocals: Int,
    val body: List<Stmt>,
    val returns: List<Cs2Type>,
    val argumentOrder: List<Cs2Type> = emptyList(),
    val intParamKinds: List<ArgType> = emptyList(),
    val intLocalKinds: Map<Int, ArgType> = emptyMap(),
    val returnKinds: List<ArgType> = emptyList(),
)
