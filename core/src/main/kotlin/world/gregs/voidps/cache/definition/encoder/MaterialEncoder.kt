package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.MaterialDefinition

class MaterialEncoder {

    fun Writer.encode(materials: List<MaterialDefinition>) {
        require(materials.size <= LIMIT) { "${materials.size} materials do not fit in the blob's two byte count." }
        writeShort(materials.size)
        for (material in materials) {
            writeByte(material.defined)
        }
        val defined = materials.filter { it.defined }
        for (material in defined) {
            writeByte(if (material.detailsOnly) 0 else 1)
        }
        for (material in defined) {
            writeByte(material.isSmall)
        }
        for (material in defined) {
            writeByte(material.skipTriangles)
        }
        for (material in defined) {
            writeByte(material.brightness.toInt())
        }
        for (material in defined) {
            writeByte(material.alpha.toInt())
        }
        for (material in defined) {
            writeByte(material.effectId.toInt())
        }
        for (material in defined) {
            writeByte(material.effectParam1.toInt())
        }
        for (material in defined) {
            writeShort(material.colourHsl)
        }
        for (material in defined) {
            writeByte(material.textureSpeedU.toInt())
        }
        for (material in defined) {
            writeByte(material.textureSpeedV.toInt())
        }
        for (material in defined) {
            writeByte(material.unusedBoolean)
        }
        for (material in defined) {
            writeByte(material.isBrickTile)
        }
        for (material in defined) {
            writeByte(material.mipmapping.toInt())
        }
        for (material in defined) {
            writeByte(material.repeatS)
        }
        for (material in defined) {
            writeByte(material.repeatT)
        }
        for (material in defined) {
            writeByte(material.hdr)
        }
        for (material in defined) {
            writeByte(material.combineMode)
        }
        for (material in defined) {
            writeInt(material.effectParam2)
        }
        for (material in defined) {
            writeByte(material.effectCombiner)
        }
    }

    private companion object {
        private const val LIMIT = 0xffff
    }
}
