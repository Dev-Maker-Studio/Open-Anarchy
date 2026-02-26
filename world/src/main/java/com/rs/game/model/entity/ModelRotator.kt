package com.rs.game.model.entity

import world.gregs.voidps.buffer.write.Writer

class ModelRotator {

    private val targetData = IntArray(15)
    private val slotHashes = IntArray(15)
    private var curr = 0

    fun addRotator(rotator: Rotation): ModelRotator {
        targetData[curr] = rotator.data
        slotHashes[curr++] = rotator.mask
        return this
    }

    fun encodeNPC(stream: Writer) {
        stream.writeByteSubtract(curr)
        for (i in 0 until curr) {
            if ((targetData[i] and 0xc000) == 0xc000) {
                stream.writeShortAddLittle(targetData[i] shr 16)
                stream.writeShort(targetData[i] and 0xFFFF)
            } else {
                stream.writeShortAddLittle(targetData[i])
            }
            stream.writeShortAddLittle(slotHashes[i])
        }
    }

    fun encodePlayer(stream: Writer) {
        stream.writeByteSubtract(curr)
        for (i in 0 until curr) {
            if ((targetData[i] and 0xc000) == 0xc000) {
                stream.writeShortAdd(targetData[i] shr 16)
                stream.writeShortAdd(targetData[i] and 0xFFFF)
            } else {
                stream.writeShortAdd(targetData[i])
            }
            stream.writeShort(slotHashes[i])
        }
    }

    companion object {
        @JvmField
        val RESET: ModelRotator = ModelRotator().addRotator(Rotation().enableAll())
    }
}
