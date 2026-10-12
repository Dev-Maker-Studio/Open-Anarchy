package com.rs.game.model.entity

import org.darkan.core.update.ModelOverrides

class ModelRotator {

    private val targetData = IntArray(15)
    private val slotHashes = IntArray(15)
    private var curr = 0

    fun addRotator(rotator: Rotation): ModelRotator {
        targetData[curr] = rotator.data
        slotHashes[curr++] = rotator.mask
        return this
    }

    fun toModelOverrides(): ModelOverrides {
        val keys = IntArray(curr)
        val values = IntArray(curr)
        for (i in 0 until curr) {
            keys[i] = targetData[i]
            values[i] = slotHashes[i]
        }
        return ModelOverrides(keys, values)
    }

    companion object {
        @JvmField
        val RESET: ModelRotator = ModelRotator().addRotator(Rotation().enableAll())
    }
}
