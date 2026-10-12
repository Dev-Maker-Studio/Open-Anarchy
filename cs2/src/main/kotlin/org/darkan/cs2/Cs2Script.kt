package org.darkan.cs2

import org.darkan.cs2.ir.Cs2Type

data class Cs2Instruction(
    val op: Cs2Op,
    val intOperand: Int = 0,
    val strOperand: String? = null,
    val longOperand: Long = 0,
) {
    override fun toString(): String = when {
        strOperand != null -> "${op.name} \"${Cs2Strings.escape(strOperand)}\""
        op == Cs2Op.PUSH_LONG_CONSTANT -> "${op.name} $longOperand"
        else -> "${op.name} $intOperand"
    }
}

data class Cs2SwitchCase(val key: Int, val offset: Int)

class Cs2Script(
    val name: String?,
    val instructions: List<Cs2Instruction>,
    val intLocalsCount: Int,
    val stringLocalsCount: Int,
    val longLocalsCount: Int,
    val intArgsCount: Int,
    val stringArgsCount: Int,
    val longArgsCount: Int,
    val switchTables: List<List<Cs2SwitchCase>>,
) {
    val size: Int get() = instructions.size

    operator fun get(index: Int): Cs2Instruction = instructions[index]

    var returnSignature: ReturnSignature? = null

    var returnOrder: List<Cs2Type> = emptyList()

    var argumentOrder: List<Cs2Type> = emptyList()

    override fun toString(): String =
        "Cs2Script(name=$name, instructions=${instructions.size}, " +
            "args=$intArgsCount/$stringArgsCount/$longArgsCount, " +
            "locals=$intLocalsCount/$stringLocalsCount/$longLocalsCount, " +
            "switches=${switchTables.size})"
}

data class ReturnSignature(val ints: Int, val strings: Int, val longs: Int) {
    val total: Int get() = ints + strings + longs

    companion object {
        val EMPTY = ReturnSignature(0, 0, 0)
    }
}
