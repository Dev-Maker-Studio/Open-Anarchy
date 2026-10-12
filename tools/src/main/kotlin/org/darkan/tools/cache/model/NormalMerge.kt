package org.darkan.tools.cache.model

import world.gregs.voidps.cache.definition.data.ModelDefinition

class NormalMerge(
    val vertexIndex: IntArray,
    val normalX: FloatArray,
    val normalY: FloatArray,
    val normalZ: FloatArray,
    val signature: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NormalMerge) return false
        return signature == other.signature
    }

    override fun hashCode(): Int = signature.hashCode()
}
