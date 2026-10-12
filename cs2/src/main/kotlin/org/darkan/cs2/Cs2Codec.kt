package org.darkan.cs2

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter

object Cs2Codec {

    private const val FIXED_HEADER_BYTES = 16
    private const val TRAILER_BYTES = 2

    fun decode(data: ByteArray): Cs2Script {
        require(data.size > TRAILER_BYTES + FIXED_HEADER_BYTES) {
            "Clientscript too small to contain a header (${data.size} bytes)"
        }

        val reader = BufferReader(data)
        reader.position(data.size - TRAILER_BYTES)
        val switchBlockSize = reader.readUnsignedShort()
        val headerStart = data.size - TRAILER_BYTES - switchBlockSize - FIXED_HEADER_BYTES
        require(headerStart in 1 until data.size) {
            "Corrupt clientscript: headerStart=$headerStart, size=${data.size}"
        }

        reader.position(headerStart)
        val instructionCount = reader.readInt()
        val intLocalsCount = reader.readUnsignedShort()
        val stringLocalsCount = reader.readUnsignedShort()
        val longLocalsCount = reader.readUnsignedShort()
        val intArgsCount = reader.readUnsignedShort()
        val stringArgsCount = reader.readUnsignedShort()
        val longArgsCount = reader.readUnsignedShort()

        val switchTableCount = reader.readUnsignedByte()
        val switchTables = ArrayList<List<Cs2SwitchCase>>(switchTableCount)
        repeat(switchTableCount) {
            val caseCount = reader.readUnsignedShort()
            val cases = ArrayList<Cs2SwitchCase>(caseCount)
            repeat(caseCount) {
                cases.add(Cs2SwitchCase(reader.readInt(), reader.readInt()))
            }
            switchTables.add(cases)
        }

        reader.position(0)
        val name = readNullString(reader)

        val instructions = ArrayList<Cs2Instruction>(instructionCount)
        while (reader.position() < headerStart) {
            val opcodeId = reader.readUnsignedShort()
            val op = Cs2Op.find(opcodeId)
                ?: error("Unknown CS2 opcode $opcodeId at byte ${reader.position() - 2}")
            instructions.add(
                when {
                    op == Cs2Op.PUSH_CONSTANT_STRING ->
                        Cs2Instruction(op, strOperand = reader.readString())
                    op == Cs2Op.PUSH_LONG_CONSTANT ->
                        Cs2Instruction(op, longOperand = reader.readLong())
                    op.largeOperand -> Cs2Instruction(op, intOperand = reader.readInt())
                    else -> Cs2Instruction(op, intOperand = reader.readUnsignedByte())
                },
            )
        }

        require(reader.position() == headerStart) {
            "Instruction stream overran the header (${reader.position()} > $headerStart)"
        }
        require(instructions.size == instructionCount) {
            "Decoded ${instructions.size} instructions but the header declares $instructionCount"
        }

        return Cs2Script(
            name = name,
            instructions = instructions,
            intLocalsCount = intLocalsCount,
            stringLocalsCount = stringLocalsCount,
            longLocalsCount = longLocalsCount,
            intArgsCount = intArgsCount,
            stringArgsCount = stringArgsCount,
            longArgsCount = longArgsCount,
            switchTables = switchTables,
        )
    }

    fun encode(script: Cs2Script): ByteArray {
        val writer = BufferWriter(estimateSize(script))

        if (script.name == null) writer.writeByte(0) else writer.writeString(script.name)

        for (instruction in script.instructions) {
            val op = instruction.op
            writer.writeShort(op.id)
            when {
                op == Cs2Op.PUSH_CONSTANT_STRING -> writer.writeString(instruction.strOperand ?: "")
                op == Cs2Op.PUSH_LONG_CONSTANT -> writer.writeLong(instruction.longOperand)
                op.largeOperand -> writer.writeInt(instruction.intOperand)
                else -> writer.writeByte(instruction.intOperand)
            }
        }

        writer.writeInt(script.instructions.size)
        writer.writeShort(script.intLocalsCount)
        writer.writeShort(script.stringLocalsCount)
        writer.writeShort(script.longLocalsCount)
        writer.writeShort(script.intArgsCount)
        writer.writeShort(script.stringArgsCount)
        writer.writeShort(script.longArgsCount)

        val switchBlockStart = writer.position()
        writer.writeByte(script.switchTables.size)
        for (table in script.switchTables) {
            writer.writeShort(table.size)
            for (case in table) {
                writer.writeInt(case.key)
                writer.writeInt(case.offset)
            }
        }
        writer.writeShort(writer.position() - switchBlockStart)

        return writer.toArray()
    }

    private fun estimateSize(script: Cs2Script): Int {
        var size = (script.name?.length ?: 0) + 1
        for (instruction in script.instructions) {
            val op = instruction.op
            size += 2 + when {
                op == Cs2Op.PUSH_CONSTANT_STRING -> (instruction.strOperand?.length ?: 0) + 1
                op == Cs2Op.PUSH_LONG_CONSTANT -> 8
                op.largeOperand -> 4
                else -> 1
            }
        }
        size += FIXED_HEADER_BYTES + 1
        for (table in script.switchTables) size += 2 + table.size * 8
        return size + TRAILER_BYTES
    }

    private fun readNullString(reader: BufferReader): String? {
        if (reader.peekByte() == 0) {
            reader.position(reader.position() + 1)
            return null
        }
        return reader.readString()
    }

    private fun BufferReader.peekByte(): Int {
        val at = position()
        val value = readUnsignedByte()
        position(at)
        return value
    }
}
