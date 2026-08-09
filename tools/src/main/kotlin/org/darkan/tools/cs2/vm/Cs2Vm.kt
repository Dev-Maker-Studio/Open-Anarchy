package org.darkan.tools.cs2.vm

import org.darkan.tools.cs2.Cs2Cfg
import org.darkan.tools.cs2.Cs2Context
import org.darkan.tools.cs2.Cs2Op
import org.darkan.tools.cs2.Cs2Script
import org.darkan.tools.cs2.OpKind
import org.darkan.tools.cs2.ReturnSignature

/**
 * Executes CS2 bytecode.
 *
 * This mirrors the client's interpreter: three typed operand stacks, per-call
 * locals, five global int arrays, and a call stack of saved frames. Only the
 * core instruction set is implemented here; everything else goes to [Cs2Host],
 * which is what lets the interface editor answer component opcodes while the
 * machine still tracks the stacks exactly.
 */
class Cs2Vm(
    private val context: Cs2Context,
    private val host: Cs2Host,
    /** Guards against runaway scripts, as the client's own limit does. */
    private val instructionLimit: Int = 200_000,
) {
    private val intStack = ArrayList<Int>(256)
    private val stringStack = ArrayList<String>(64)
    private val longStack = ArrayList<Long>(32)

    private val globalArrays = Array(5) { IntArray(5000) }
    private val globalArrayLengths = IntArray(5)

    private class Frame(
        val script: Cs2Script,
        var pointer: Int,
        val ints: IntArray,
        val strings: Array<String>,
        val longs: LongArray,
    )

    private val frames = ArrayList<Frame>(16)

    /** Instructions executed by the last [execute] call. */
    var instructionsRun: Int = 0
        private set

    /**
     * Runs [script] with the given arguments and returns whatever it leaves on
     * the stacks.
     */
    fun execute(
        script: Cs2Script,
        intArgs: List<Int> = emptyList(),
        stringArgs: List<String> = emptyList(),
        longArgs: List<Long> = emptyList(),
    ): Cs2Values {
        intStack.clear()
        stringStack.clear()
        longStack.clear()
        frames.clear()
        instructionsRun = 0

        frames.add(newFrame(script, intArgs, stringArgs, longArgs))

        while (frames.isNotEmpty()) {
            if (++instructionsRun > instructionLimit) {
                host.onError("Script ${script.name ?: "?"} exceeded $instructionLimit instructions")
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

    private fun newFrame(
        script: Cs2Script,
        intArgs: List<Int>,
        stringArgs: List<String>,
        longArgs: List<Long>,
    ) = Frame(
        script = script,
        pointer = 0,
        ints = IntArray(script.intLocalsCount) { intArgs.getOrElse(it) { 0 } },
        strings = Array(script.stringLocalsCount) { stringArgs.getOrElse(it) { "" } },
        longs = LongArray(script.longLocalsCount) { longArgs.getOrElse(it) { 0L } },
    )

    // ------------------------------------------------------------ stack access

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

    // ------------------------------------------------------------- execution

    /** Runs one instruction. Returns false to stop the whole script. */
    private fun step(frame: Frame, op: Cs2Op, operand: Int, text: String?, long: Long): Boolean {
        when (op.opName) {
            "PUSH_CONSTANT_INT" -> intStack.add(operand)
            "PUSH_CONSTANT_STRING" -> stringStack.add(text ?: "")
            "PUSH_LONG_CONSTANT" -> longStack.add(long)

            "PUSH_INT_LOCAL" -> intStack.add(frame.ints.getOrElse(operand) { 0 })
            "PUSH_STRING_LOCAL" -> stringStack.add(frame.strings.getOrElse(operand) { "" })
            "PUSH_LONG_LOCAL" -> longStack.add(frame.longs.getOrElse(operand) { 0L })

            "POP_INT_LOCAL" -> frame.ints.setIfPresent(operand, popInt())
            "POP_STRING_LOCAL" -> if (operand in frame.strings.indices) frame.strings[operand] = popString()
            "POP_LONG_LOCAL" -> if (operand in frame.longs.indices) frame.longs[operand] = popLong()

            "POP_INT_DISCARD" -> popInt()
            "POP_STRING_DISCARD" -> popString()
            "POP_LONG_DISCARD" -> popLong()

            "PUSH_VAR", "GET_VARP_OLD" -> intStack.add(host.varp(operand))
            "POP_VAR" -> host.setVarp(operand, popInt())
            "PUSH_VARBIT", "GET_VARPBIT_OLD" -> intStack.add(host.varbit(operand))
            "POP_VARBIT" -> host.setVarbit(operand, popInt())
            "GET_VARN_OLD", "GET_VARNBIT_OLD" -> intStack.add(0)
            "LOAD_VARC" -> intStack.add(host.varc(operand))
            "STORE_VARC" -> host.setVarc(operand, popInt())
            "LOAD_VARC_STRING" -> stringStack.add(host.varcString(operand))
            "STORE_VARC_STRING" -> host.setVarcString(operand, popString())

            "JOIN_STRING" -> stringStack.add(popStrings(operand).joinToString(""))

            "DEFINE_ARRAY" -> defineArray(operand)
            "PUSH_ARRAY_INT" -> intStack.add(readArray(operand, popInt()))
            "POP_ARRAY_INT" -> {
                val value = popInt()
                writeArray(operand, popInt(), value)
            }

            "BRANCH" -> frame.pointer += operand
            "SWITCH" -> switch(frame, operand)
            "RETURN" -> return returnFromFrame()
            "GOSUB_WITH_PARAMS" -> return call(operand)

            else -> when {
                Cs2Cfg.isConditionalBranch(op) -> branch(frame, op, operand)
                op.kind == OpKind.HOOK -> setHook(op)
                else -> callHost(op, operand)
            }
        }
        return true
    }

    private fun IntArray.setIfPresent(index: Int, value: Int) {
        if (index in indices) this[index] = value
    }

    private fun defineArray(operand: Int) {
        val array = ((operand shr 16) and 0x7).coerceAtMost(globalArrays.lastIndex)
        val size = popInt().coerceIn(0, 5000)
        globalArrayLengths[array] = size
        val fill = if ((operand and 0xFFFF) == 's'.code) 0 else -1
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
        val taken = when (op.opName) {
            "BRANCH_IF_TRUE" -> popInt() == 1
            "BRANCH_IF_FALSE" -> popInt() == 0
            "BRANCH_EQUALS" -> compareInts() == 0
            "BRANCH_NOT" -> compareInts() != 0
            "BRANCH_LESS_THAN" -> compareInts() < 0
            "BRANCH_GREATER_THAN" -> compareInts() > 0
            "BRANCH_LESS_THAN_OR_EQUALS" -> compareInts() <= 0
            "BRANCH_GREATER_THAN_OR_EQUALS" -> compareInts() >= 0
            "LONG_BRANCH_EQUALS" -> compareLongs() == 0
            "LONG_BRANCH_NOT" -> compareLongs() != 0
            "LONG_BRANCH_LESS_THAN" -> compareLongs() < 0
            "LONG_BRANCH_GREATER_THAN" -> compareLongs() > 0
            "LONG_BRANCH_LESS_THAN_OR_EQUALS" -> compareLongs() <= 0
            "LONG_BRANCH_GREATER_THAN_OR_EQUALS" -> compareLongs() >= 0
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
        frames.add(newFrame(callee, intArgs, stringArgs, longArgs))
        return true
    }

    private fun returnFromFrame(): Boolean {
        frames.removeAt(frames.size - 1)
        return frames.isNotEmpty()
    }

    /**
     * Hook setters pop a format string describing the callback's arguments,
     * optionally a trigger list, then the arguments and the script id.
     */
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

        // The host may not know the opcode; pad so the stacks stay consistent
        // with the signature the rest of the script was compiled against.
        val expected = expectedResults(op, intArgs)
        repeat(expected.ints) { intStack.add(results.ints.getOrElse(it) { 0 }) }
        repeat(expected.strings) { stringStack.add(results.strings.getOrElse(it) { "" }) }
        repeat(expected.longs) { longStack.add(results.longs.getOrElse(it) { 0L }) }
    }

    /** What an opcode pushes, resolving the int-or-string param opcodes. */
    private fun expectedResults(op: Cs2Op, intArgs: List<Int>): ReturnSignature {
        if (op.kind != OpKind.PARAM) {
            return ReturnSignature(op.pushInt, op.pushStr, op.pushLong)
        }
        val yieldsString = if (op.opName == "ENUM") {
            intArgs.getOrNull(1) == 's'.code
        } else {
            intArgs.lastOrNull()?.let { context.paramIsString(it) } ?: false
        }
        return if (yieldsString) ReturnSignature(0, 1, 0) else ReturnSignature(1, 0, 0)
    }
}
