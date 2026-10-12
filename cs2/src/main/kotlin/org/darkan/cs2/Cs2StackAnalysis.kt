package org.darkan.cs2

import org.darkan.cs2.ir.Cs2Type

data class StackEffect(
    val popInt: Int = 0,
    val popStr: Int = 0,
    val popLong: Int = 0,
    val pushInt: Int = 0,
    val pushStr: Int = 0,
    val pushLong: Int = 0,
)

interface Cs2Context {
    fun script(id: Int): Cs2Script?

    fun paramIsString(paramId: Int): Boolean

    fun paramType(paramId: Int): Char = 0.toChar()

    fun returnTypes(scriptId: Int): List<Cs2Type> {
        val script = script(scriptId) ?: return emptyList()
        val signature = script.returnSignature ?: ReturnSignature.EMPTY
        script.returnOrder.takeIf { it.size == signature.total }?.let { return it }
        return List(signature.ints) { Cs2Type.INT } +
            List(signature.strings) { Cs2Type.STRING } +
            List(signature.longs) { Cs2Type.LONG }
    }
}

fun effectiveSignature(
    instruction: Cs2Instruction,
    context: Cs2Context,
    stacks: SymbolicStacks,
): StackEffect {
    val op = instruction.op
    return when (op.kind) {
        OpKind.NORMAL -> StackEffect(
            op.popInt, op.popStr, op.popLong, op.pushInt, op.pushStr, op.pushLong,
        )

        OpKind.JOIN_STRING -> StackEffect(popStr = instruction.intOperand, pushStr = 1)

        OpKind.GOSUB -> {
            val callee = context.script(instruction.intOperand)
                ?: error("GOSUB to missing script ${instruction.intOperand}")
            val returns = callee.returnSignature ?: ReturnSignature.EMPTY
            StackEffect(
                popInt = callee.intArgsCount,
                popStr = callee.stringArgsCount,
                popLong = callee.longArgsCount,
                pushInt = returns.ints,
                pushStr = returns.strings,
                pushLong = returns.longs,
            )
        }

        OpKind.PARAM -> {
            val yieldsString = if (op == Cs2Op.ENUM) enumYieldsString(stacks) else {
                val paramId = stacks.topInt()
                    ?: error("${op.name} needs a constant param id to resolve its result type")
                context.paramIsString(paramId)
            }
            if (yieldsString) {
                StackEffect(op.popInt, op.popStr, op.popLong, pushStr = 1)
            } else {
                StackEffect(op.popInt, op.popStr, op.popLong, pushInt = 1)
            }
        }

        OpKind.HOOK -> hookSignature(op, stacks)
    }
}

private fun enumYieldsString(stacks: SymbolicStacks): Boolean {
    val valueType = stacks.intBelowTop(2)
        ?: error("ENUM needs a constant value type to resolve its result type")
    return valueType == 's'.code
}

private fun hookSignature(op: Cs2Op, stacks: SymbolicStacks): StackEffect {
    val format = stacks.topString()
        ?: error("${op.name} needs a constant format string")

    var popInt = op.popInt + 1
    var popStr = 1
    var popLong = 0

    var spec = format
    if (op.hasTriggerArray && spec.endsWith("Y")) {
        spec = spec.dropLast(1)
        val count = stacks.intBelowTop(op.popInt)
            ?: error("${op.name} needs a constant trigger-list size")
        popInt += 1 + count
    }
    for (char in spec) {
        when (char) {
            's' -> popStr++
            '§' -> popLong++
            else -> popInt++
        }
    }
    return StackEffect(popInt, popStr, popLong)
}

class SymbolicStacks {
    private val ints = ArrayList<Int?>()
    private val strings = ArrayList<String?>()
    private var longDepth = 0

    private val live = ArrayList<Cs2Type>()

    fun order(): List<Cs2Type> = live.toList()

    val intDepth: Int get() = ints.size
    val stringDepth: Int get() = strings.size
    val longs: Int get() = longDepth

    val isEmpty: Boolean get() = ints.isEmpty() && strings.isEmpty() && longDepth == 0

    fun topInt(): Int? = ints.lastOrNull()

    fun intBelowTop(depth: Int): Int? = ints.getOrNull(ints.size - 1 - depth)

    fun topString(): String? = strings.lastOrNull()

    fun apply(effect: StackEffect, pushedIntConstant: Int? = null, pushedString: String? = null) {
        repeat(effect.popInt) {
            require(ints.isNotEmpty()) { "int stack underflow" }
            ints.removeAt(ints.size - 1)
            live.removeLastOfType(Cs2Type.INT)
        }
        repeat(effect.popStr) {
            require(strings.isNotEmpty()) { "string stack underflow" }
            strings.removeAt(strings.size - 1)
            live.removeLastOfType(Cs2Type.STRING)
        }
        repeat(effect.popLong) {
            longDepth--
            live.removeLastOfType(Cs2Type.LONG)
        }
        require(longDepth >= 0) { "long stack underflow" }

        repeat(effect.pushInt) {
            ints.add(pushedIntConstant)
            live.add(Cs2Type.INT)
        }
        repeat(effect.pushStr) {
            strings.add(pushedString)
            live.add(Cs2Type.STRING)
        }
        repeat(effect.pushLong) {
            longDepth++
            live.add(Cs2Type.LONG)
        }
    }

    private fun MutableList<Cs2Type>.removeLastOfType(type: Cs2Type) {
        val at = indexOfLast { it == type }
        if (at >= 0) removeAt(at)
    }

    fun clear() {
        ints.clear()
        strings.clear()
        longDepth = 0
        live.clear()
    }

    fun copy(): SymbolicStacks {
        val copy = SymbolicStacks()
        copy.ints.addAll(ints)
        copy.strings.addAll(strings)
        copy.longDepth = longDepth
        copy.live.addAll(live)
        return copy
    }

    fun sameDepthAs(other: SymbolicStacks): Boolean =
        intDepth == other.intDepth && stringDepth == other.stringDepth && longs == other.longs

    fun mergeConstantsFrom(other: SymbolicStacks) {
        for (i in ints.indices) if (ints[i] != other.ints[i]) ints[i] = null
        for (i in strings.indices) if (strings[i] != other.strings[i]) strings[i] = null
    }

    override fun toString() = "int=$intDepth str=$stringDepth long=$longDepth"
}
