package world.gregs.voidps.cache.definition

interface OpcodeOrdered {

    var opcodeOrder: IntArray?

    var shadowedPayloads: Array<ByteArray?>?

    fun repeats(opcode: Int): Boolean = false
}
