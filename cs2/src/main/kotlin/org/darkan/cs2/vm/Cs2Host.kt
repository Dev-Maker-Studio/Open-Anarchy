package org.darkan.cs2.vm

import org.darkan.cs2.Cs2Op

data class Cs2Values(
    val ints: List<Int> = emptyList(),
    val strings: List<String> = emptyList(),
    val longs: List<Long> = emptyList(),
) {
    companion object {
        val EMPTY = Cs2Values()
    }
}

data class Cs2Hook(
    val scriptId: Int,
    val args: Cs2Values,
    val triggers: List<Int> = emptyList(),
)

interface Cs2Host {

    fun invoke(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values

    fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?)

    fun varp(id: Int): Int

    fun setVarp(id: Int, value: Int)

    fun varbit(id: Int): Int

    fun setVarbit(id: Int, value: Int)

    fun varc(id: Int): Int

    fun setVarc(id: Int, value: Int)

    fun varcString(id: Int): String

    fun setVarcString(id: Int, value: String)

    fun onError(message: String)

    companion object {
        fun defaults(op: Cs2Op) = Cs2Values(
            ints = List(op.pushInt) { 0 },
            strings = List(op.pushStr) { "" },
            longs = List(op.pushLong) { 0L },
        )
    }
}

open class NoOpCs2Host : Cs2Host {

    private val varps = HashMap<Int, Int>()
    private val varbits = HashMap<Int, Int>()
    private val varcs = HashMap<Int, Int>()
    private val varcStrings = HashMap<Int, String>()

    override fun invoke(op: Cs2Op, operand: Int, args: Cs2Values) = Cs2Host.defaults(op)

    override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) = Unit

    override fun varp(id: Int) = varps[id] ?: 0

    override fun setVarp(id: Int, value: Int) {
        varps[id] = value
    }

    override fun varbit(id: Int) = varbits[id] ?: 0

    override fun setVarbit(id: Int, value: Int) {
        varbits[id] = value
    }

    override fun varc(id: Int) = varcs[id] ?: 0

    override fun setVarc(id: Int, value: Int) {
        varcs[id] = value
    }

    override fun varcString(id: Int) = varcStrings[id] ?: ""

    override fun setVarcString(id: Int, value: String) {
        varcStrings[id] = value
    }

    override fun onError(message: String) = Unit
}
