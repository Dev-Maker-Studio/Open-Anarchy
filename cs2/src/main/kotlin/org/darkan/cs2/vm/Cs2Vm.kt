package org.darkan.cs2.vm

import org.darkan.cs2.Cs2Cfg
import org.darkan.cs2.Cs2Context
import org.darkan.cs2.Cs2Op
import org.darkan.cs2.Cs2Script
import org.darkan.cs2.OpKind
import org.darkan.cs2.ReturnSignature
import kotlin.math.pow
import kotlin.random.Random

class Cs2Vm(
    private val context: Cs2Context,
    private val host: Cs2Host,
    private val instructionLimit: Int = 200_000,
) {
    private val intStack = ArrayList<Int>(256)
    private val stringStack = ArrayList<String>(64)
    private val longStack = ArrayList<Long>(32)

    private val globalArrays = Array(5) { IntArray(5000) }
    private val globalArrayLengths = IntArray(5)

    private class Frame(
        val id: Int,
        val script: Cs2Script,
        var pointer: Int,
        val ints: IntArray,
        val strings: Array<String>,
        val longs: LongArray,
    ) {
        fun label(): String = when {
            id >= 0 && script.name != null -> "$id (${script.name})"
            id >= 0 -> id.toString()
            script.name != null -> script.name
            else -> "?"
        }
    }

    private val frames = ArrayList<Frame>(16)

    private val random = Random(SEED)

    private val zeroDivisorsReported = HashSet<Cs2Op>()

    var instructionsRun: Int = 0
        private set

    fun execute(
        script: Cs2Script,
        intArgs: List<Int> = emptyList(),
        stringArgs: List<String> = emptyList(),
        longArgs: List<Long> = emptyList(),
        scriptId: Int = -1,
    ): Cs2Values {
        intStack.clear()
        stringStack.clear()
        longStack.clear()
        frames.clear()
        instructionsRun = 0
        zeroDivisorsReported.clear()

        frames.add(newFrame(scriptId, script, intArgs, stringArgs, longArgs))

        while (frames.isNotEmpty()) {
            if (++instructionsRun > instructionLimit) {
                host.onError(runawayMessage())
                break
            }
            val frame = frames.last()
            if (frame.pointer >= frame.script.size) break
            val instruction = frame.script[frame.pointer]
            frame.pointer++
            if (!step(frame, instruction.op, instruction.intOperand, instruction.strOperand, instruction.longOperand)) {
                break
            }
        }
        return Cs2Values(intStack.toList(), stringStack.toList(), longStack.toList())
    }

    private fun runawayMessage(): String {
        val frame = frames.lastOrNull()
            ?: return "Script ? exceeded $instructionLimit instructions"
        val instruction = frame.script.instructions.getOrNull(frame.pointer)
        val where = "at ${frame.pointer}/${frame.script.size}"
        val what = if (instruction == null) "past end of script" else "on $instruction"
        val callers = frames.dropLast(1).joinToString(" -> ") { it.label() }
        val via = if (callers.isEmpty()) "" else ", called from $callers"
        return "Script ${frame.label()} exceeded $instructionLimit instructions $where $what$via"
    }

    private fun newFrame(
        id: Int,
        script: Cs2Script,
        intArgs: List<Int>,
        stringArgs: List<String>,
        longArgs: List<Long>,
    ) = Frame(
        id = id,
        script = script,
        pointer = 0,
        ints = IntArray(script.intLocalsCount) { intArgs.getOrElse(it) { 0 } },
        strings = Array(script.stringLocalsCount) { stringArgs.getOrElse(it) { "" } },
        longs = LongArray(script.longLocalsCount) { longArgs.getOrElse(it) { 0L } },
    )


    private fun popInt(): Int =
        if (intStack.isEmpty()) 0 else intStack.removeAt(intStack.size - 1)

    private fun popString(): String =
        if (stringStack.isEmpty()) "" else stringStack.removeAt(stringStack.size - 1)

    private fun popLong(): Long =
        if (longStack.isEmpty()) 0L else longStack.removeAt(longStack.size - 1)

    private fun popInts(count: Int): List<Int> {
        val out = ArrayList<Int>(count)
        repeat(count) { out.add(popInt()) }
        out.reverse()
        return out
    }

    private fun popStrings(count: Int): List<String> {
        val out = ArrayList<String>(count)
        repeat(count) { out.add(popString()) }
        out.reverse()
        return out
    }

    private fun popLongs(count: Int): List<Long> {
        val out = ArrayList<Long>(count)
        repeat(count) { out.add(popLong()) }
        out.reverse()
        return out
    }


    private fun step(frame: Frame, op: Cs2Op, operand: Int, text: String?, long: Long): Boolean {
        when (op) {
            Cs2Op.PUSH_CONSTANT_INT -> intStack.add(operand)
            Cs2Op.PUSH_CONSTANT_STRING -> stringStack.add(text ?: "")
            Cs2Op.PUSH_LONG_CONSTANT -> longStack.add(long)

            Cs2Op.PUSH_INT_LOCAL -> intStack.add(frame.ints.getOrElse(operand) { 0 })
            Cs2Op.PUSH_STRING_LOCAL -> stringStack.add(frame.strings.getOrElse(operand) { "" })
            Cs2Op.PUSH_LONG_LOCAL -> longStack.add(frame.longs.getOrElse(operand) { 0L })

            Cs2Op.POP_INT_LOCAL -> frame.ints.setIfPresent(operand, popInt())
            Cs2Op.POP_STRING_LOCAL -> if (operand in frame.strings.indices) frame.strings[operand] = popString()
            Cs2Op.POP_LONG_LOCAL -> if (operand in frame.longs.indices) frame.longs[operand] = popLong()

            Cs2Op.POP_INT_DISCARD -> popInt()
            Cs2Op.POP_STRING_DISCARD -> popString()
            Cs2Op.POP_LONG_DISCARD -> popLong()

            Cs2Op.PUSH_VAR, Cs2Op.GET_VARP_OLD -> intStack.add(host.varp(operand))
            Cs2Op.POP_VAR -> host.setVarp(operand, popInt())
            Cs2Op.PUSH_VARBIT, Cs2Op.GET_VARPBIT_OLD -> intStack.add(host.varbit(operand))
            Cs2Op.POP_VARBIT -> host.setVarbit(operand, popInt())
            Cs2Op.GET_VARN_OLD, Cs2Op.GET_VARNBIT_OLD -> intStack.add(0)
            Cs2Op.PUSH_VARC_INT -> intStack.add(host.varc(operand))
            Cs2Op.POP_VARC_INT -> host.setVarc(operand, popInt())
            Cs2Op.PUSH_VARC_STRING -> stringStack.add(host.varcString(operand))
            Cs2Op.POP_VARC_STRING -> host.setVarcString(operand, popString())

            Cs2Op.JOIN_STRING -> stringStack.add(popStrings(operand).joinToString(""))

            Cs2Op.DEFINE_ARRAY -> defineArray(operand)
            Cs2Op.PUSH_ARRAY_INT -> intStack.add(readArray(operand, popInt()))
            Cs2Op.POP_ARRAY_INT -> {
                val value = popInt()
                writeArray(operand, popInt(), value)
            }

            Cs2Op.BRANCH -> frame.pointer += operand
            Cs2Op.SWITCH -> switch(frame, operand)
            Cs2Op.RETURN -> return returnFromFrame()
            Cs2Op.GOSUB_WITH_PARAMS -> return call(operand)

            else -> when {
                Cs2Cfg.isConditionalBranch(op) -> branch(frame, op, operand)
                op.kind == OpKind.HOOK -> setHook(op)
                !primitive(op) -> callHost(op, operand)
            }
        }
        return true
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun primitive(op: Cs2Op): Boolean {
        when (op) {
            Cs2Op.ADD -> binaryInt { left, right -> left + right }
            Cs2Op.SUB -> binaryInt { left, right -> left - right }
            Cs2Op.MULTIPLY -> binaryInt { left, right -> left * right }
            Cs2Op.DIVIDE -> divideBy(Cs2Op.DIVIDE) { left, right -> left / right }
            Cs2Op.MODULO -> divideBy(Cs2Op.MODULO) { left, right -> left % right }
            Cs2Op.AND -> binaryInt { left, right -> left and right }
            Cs2Op.OR -> binaryInt { left, right -> left or right }
            Cs2Op.MIN -> binaryInt { left, right -> minOf(left, right) }
            Cs2Op.MAX -> binaryInt { left, right -> maxOf(left, right) }
            Cs2Op.SETBIT -> binaryInt { value, bit -> value or (1 shl bit) }
            Cs2Op.CLEARBIT -> binaryInt { value, bit -> value and (1 shl bit).inv() }
            Cs2Op.TESTBIT -> binaryInt { value, bit -> if ((value and (1 shl bit)) != 0) 1 else 0 }
            Cs2Op.POW -> binaryInt { base, exponent ->
                if (base == 0) 0 else base.toDouble().pow(exponent.toDouble()).toInt()
            }
            Cs2Op.INVPOW -> binaryInt { base, exponent ->
                when {
                    base == 0 -> 0
                    exponent == 0 -> Int.MAX_VALUE
                    else -> base.toDouble().pow(1.0 / exponent).toInt()
                }
            }
            Cs2Op.ADDPERCENT -> binaryInt { value, percent ->
                (value + value.toLong() * percent.toLong() / 100L).toInt()
            }
            Cs2Op.NOT -> intStack.add(popInt().inv())

            Cs2Op.SCALE -> {
                val (numerator, denominator, value) = popInts(3)
                intStack.add(
                    if (denominator == 0) {
                        reportZeroDivisor(Cs2Op.SCALE)
                        0
                    } else {
                        (value.toLong() * numerator.toLong() / denominator.toLong()).toInt()
                    }
                )
            }

            Cs2Op.INTERPOLATE -> {
                val values = popInts(5)
                val (fromY, toY, fromX, toX) = values
                val at = values[4]
                if (toX == fromX) {
                    reportZeroDivisor(Cs2Op.INTERPOLATE)
                    intStack.add(fromY)
                } else {
                    intStack.add(fromY + (at - fromX) * (toY - fromY) / (toX - fromX))
                }
            }

            Cs2Op.RANDOM -> intStack.add(popInt().let { if (it <= 0) 0 else random.nextInt(it) })
            Cs2Op.RANDOMINC -> intStack.add(popInt().let { if (it < 0) 0 else random.nextInt(it + 1) })

            Cs2Op.CHAR_ISPRINTABLE -> intStack.add(boolean(!Character.isISOControl(popInt().toChar())))
            Cs2Op.CHAR_ISALPHANUMERIC -> intStack.add(boolean(popInt().toChar().isLetterOrDigit()))
            Cs2Op.CHAR_ISALPHA -> intStack.add(boolean(popInt().toChar().isLetter()))
            Cs2Op.CHAR_ISNUMERIC -> intStack.add(boolean(popInt().toChar().isDigit()))
            Cs2Op.CHAR_TOLOWERCASE -> intStack.add(popInt().toChar().lowercaseChar().code)
            Cs2Op.CHAR_TOUPPERCASE -> intStack.add(popInt().toChar().uppercaseChar().code)

            Cs2Op.STRING_LENGTH -> intStack.add(popString().length)
            Cs2Op.TOSTRING -> stringStack.add(popInt().toString())
            Cs2Op.LOWERCASE -> stringStack.add(popString().lowercase())
            Cs2Op.APPEND -> stringStack.add(popStrings(2).joinToString(""))
            Cs2Op.APPEND_NUM -> {
                val text = popString()
                stringStack.add(text + popInt())
            }
            Cs2Op.APPEND_SIGNNUM -> {
                val text = popString()
                stringStack.add(text + signed(popInt()))
            }
            Cs2Op.APPEND_CHAR -> {
                val text = popString()
                val char = popInt()
                stringStack.add(if (char == -1) text else text + char.toChar())
            }
            Cs2Op.SUBSTRING -> {
                val text = popString()
                val (start, end) = popInts(2)
                val from = start.coerceIn(0, text.length)
                val to = end.coerceIn(from, text.length)
                if (from != start || to != end) {
                    host.onError("$op $start..$end clamped to $from..$to of \"$text\"")
                }
                stringStack.add(text.substring(from, to))
            }
            Cs2Op.REMOVETAGS -> stringStack.add(removeTags(popString()))
            Cs2Op.ESCAPE -> stringStack.add(popString().replace("<", "<lt>").replace(">", "<gt>"))
            Cs2Op.GET_COL_TAG -> stringStack.add("<col=" + Integer.toHexString(popInt()) + ">")
            Cs2Op.COMPARE -> {
                val (left, right) = popStrings(2)
                intStack.add(left.compareTo(right))
            }
            Cs2Op.TEXT_SWITCH -> {
                val (whenTrue, whenFalse) = popStrings(2)
                stringStack.add(if (popInt() == 1) whenTrue else whenFalse)
            }
            Cs2Op.STRING_INDEXOF_CHAR -> {
                val text = popString()
                val (char, from) = popInts(2)
                intStack.add(text.indexOf(char.toChar(), from))
            }
            Cs2Op.STRING_INDEXOF_STRING -> {
                val (text, search) = popStrings(2)
                intStack.add(text.indexOf(search, popInt()))
            }

            else -> return false
        }
        return true
    }

    private fun boolean(value: Boolean) = if (value) 1 else 0

    private inline fun binaryInt(combine: (Int, Int) -> Int) {
        val right = popInt()
        val left = popInt()
        intStack.add(combine(left, right))
    }

    private inline fun divideBy(op: Cs2Op, combine: (Int, Int) -> Int) {
        val right = popInt()
        val left = popInt()
        if (right == 0) {
            reportZeroDivisor(op)
            intStack.add(0)
        } else {
            intStack.add(combine(left, right))
        }
    }

    private fun reportZeroDivisor(op: Cs2Op) {
        if (zeroDivisorsReported.add(op)) {
            host.onError("$op divided by zero; the divisor is 0 because some opcode this host does not implement returned it")
        }
    }

    private fun removeTags(text: String): String {
        val out = StringBuilder(text.length)
        var inTag = false
        for (char in text) {
            when {
                char == '<' -> inTag = true
                char == '>' -> inTag = false
                !inTag -> out.append(char)
            }
        }
        return out.toString()
    }

    private fun signed(value: Int): String = if (value >= 0) "+$value" else value.toString()

    private fun IntArray.setIfPresent(index: Int, value: Int) {
        if (index in indices) this[index] = value
    }

    private fun defineArray(operand: Int) {
        val array = ((operand shr 16) and 0x7).coerceAtMost(globalArrays.lastIndex)
        val size = popInt().coerceIn(0, 5000)
        globalArrayLengths[array] = size
        val fill = if ((operand and 0xFFFF) == 'i'.code) 0 else -1
        globalArrays[array].fill(fill, 0, size)
    }

    private fun readArray(array: Int, index: Int): Int {
        val slot = array.coerceIn(0, globalArrays.lastIndex)
        return if (index in 0 until globalArrayLengths[slot]) globalArrays[slot][index] else 0
    }

    private fun writeArray(array: Int, index: Int, value: Int) {
        val slot = array.coerceIn(0, globalArrays.lastIndex)
        if (index in 0 until globalArrayLengths[slot]) globalArrays[slot][index] = value
    }

    private fun branch(frame: Frame, op: Cs2Op, operand: Int) {
        val taken = when (op) {
            Cs2Op.BRANCH_IF_TRUE -> popInt() == 1
            Cs2Op.BRANCH_IF_FALSE -> popInt() == 0
            Cs2Op.BRANCH_EQUALS -> compareInts() == 0
            Cs2Op.BRANCH_NOT -> compareInts() != 0
            Cs2Op.BRANCH_LESS_THAN -> compareInts() < 0
            Cs2Op.BRANCH_GREATER_THAN -> compareInts() > 0
            Cs2Op.BRANCH_LESS_THAN_OR_EQUALS -> compareInts() <= 0
            Cs2Op.BRANCH_GREATER_THAN_OR_EQUALS -> compareInts() >= 0
            Cs2Op.LONG_BRANCH_EQUALS -> compareLongs() == 0
            Cs2Op.LONG_BRANCH_NOT -> compareLongs() != 0
            Cs2Op.LONG_BRANCH_LESS_THAN -> compareLongs() < 0
            Cs2Op.LONG_BRANCH_GREATER_THAN -> compareLongs() > 0
            Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS -> compareLongs() <= 0
            Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS -> compareLongs() >= 0
            else -> false
        }
        if (taken) frame.pointer += operand
    }

    private fun compareInts(): Int {
        val right = popInt()
        val left = popInt()
        return left.compareTo(right)
    }

    private fun compareLongs(): Int {
        val right = popLong()
        val left = popLong()
        return left.compareTo(right)
    }

    private fun switch(frame: Frame, table: Int) {
        val key = popInt()
        val cases = frame.script.switchTables.getOrNull(table) ?: return
        cases.firstOrNull { it.key == key }?.let { frame.pointer += it.offset }
    }

    private fun call(scriptId: Int): Boolean {
        val callee = context.script(scriptId)
        if (callee == null) {
            host.onError("Called missing script $scriptId")
            return false
        }
        if (frames.size >= 50) {
            host.onError("Call stack overflow at script $scriptId")
            return false
        }
        val longArgs = popLongs(callee.longArgsCount)
        val stringArgs = popStrings(callee.stringArgsCount)
        val intArgs = popInts(callee.intArgsCount)
        frames.add(newFrame(scriptId, callee, intArgs, stringArgs, longArgs))
        return true
    }

    private fun returnFromFrame(): Boolean {
        frames.removeAt(frames.size - 1)
        return frames.isNotEmpty()
    }

    private fun setHook(op: Cs2Op) {
        val component = if (op.popInt == 1) popInt() else -1
        val format = popString()

        var spec = format
        var triggers = emptyList<Int>()
        if (op.hasTriggerArray && spec.endsWith("Y")) {
            spec = spec.dropLast(1)
            triggers = popInts(popInt().coerceAtLeast(0))
        }

        val ints = ArrayList<Int>()
        val strings = ArrayList<String>()
        val longs = ArrayList<Long>()
        for (char in spec.reversed()) {
            when (char) {
                's' -> strings.add(0, popString())
                '§' -> longs.add(0, popLong())
                else -> ints.add(0, popInt())
            }
        }
        val scriptId = popInt()
        val hook = if (scriptId == -1) null else Cs2Hook(scriptId, Cs2Values(ints, strings, longs), triggers)
        host.setHook(op, component, hook)
    }

    private fun callHost(op: Cs2Op, operand: Int) {
        val longArgs = popLongs(op.popLong)
        val stringArgs = popStrings(op.popStr)
        val intArgs = popInts(op.popInt)
        val results = host.invoke(op, operand, Cs2Values(intArgs, stringArgs, longArgs))

        val expected = expectedResults(op, intArgs)
        repeat(expected.ints) { intStack.add(results.ints.getOrElse(it) { 0 }) }
        repeat(expected.strings) { stringStack.add(results.strings.getOrElse(it) { "" }) }
        repeat(expected.longs) { longStack.add(results.longs.getOrElse(it) { 0L }) }
    }

    private fun expectedResults(op: Cs2Op, intArgs: List<Int>): ReturnSignature {
        if (op.kind != OpKind.PARAM) {
            return ReturnSignature(op.pushInt, op.pushStr, op.pushLong)
        }
        val yieldsString = if (op == Cs2Op.ENUM) {
            intArgs.getOrNull(1) == 's'.code
        } else {
            intArgs.lastOrNull()?.let { context.paramIsString(it) } ?: false
        }
        return if (yieldsString) ReturnSignature(0, 1, 0) else ReturnSignature(1, 0, 0)
    }

    private companion object {
        const val SEED = 0x5CB1EL
    }
}
