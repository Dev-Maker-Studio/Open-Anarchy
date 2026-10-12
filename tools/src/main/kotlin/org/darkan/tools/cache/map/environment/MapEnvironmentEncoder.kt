package org.darkan.tools.cache.map.environment

import world.gregs.voidps.buffer.write.BufferWriter
import kotlin.math.roundToInt

object MapEnvironmentEncoder {

    private const val LIGHT_SCALE = 256.0f

    private const val HDR_SCALE = 255.0f / 8.0f

    fun encodeEffects(layout: EnvironmentLayout): ByteArray {
        val writer = BufferWriter(layout.encodedSize().coerceAtLeast(1))
        for (block in layout.blocks) {
            writer.writeByte(block.opcode)
            when (block) {
                is EnvironmentRecord -> writeEnvironment(writer, block)
                is PointLightsRecord -> writeLights(writer, block)
                is HdrRecord -> {
                    writer.writeByte(block.bloom)
                    writer.writeByte(block.brightpass)
                    writer.writeByte(block.whitePoint)
                }
                is SkyboxRecord -> {
                    writer.writeShort(block.id)
                    writer.writeShort(block.x)
                    writer.writeShort(block.y)
                    writer.writeShort(block.z)
                    writer.writeShort(block.rotation)
                }
                is LightGridRecord -> writeLightGrid(writer, block)
            }
        }
        return writer.toArray()
    }

    fun encodeArchive(original: ByteArray, layout: EnvironmentLayout): ByteArray {
        val tileLength = layout.tileSectionLength
        require(tileLength in 0..original.size) {
            "Tile section length $tileLength outside the ${original.size}-byte archive"
        }
        val effects = encodeEffects(layout)
        val result = ByteArray(tileLength + effects.size)
        System.arraycopy(original, 0, result, 0, tileLength)
        System.arraycopy(effects, 0, result, tileLength, effects.size)
        return result
    }

    private fun writeEnvironment(writer: BufferWriter, record: EnvironmentRecord) {
        writer.writeByte(record.flags())
        record.sunColour?.let { writer.writeInt(it) }
        record.sunAmbient?.let { writer.writeShort(it) }
        record.sunLight?.let { writer.writeShort(it) }
        record.sunBacklight?.let { writer.writeShort(it) }
        record.sunPosition?.let { position ->
            writer.writeShort(position[0])
            writer.writeShort(position[1])
            writer.writeShort(position[2])
        }
        record.fogColour?.let { writer.writeInt(it) }
        record.fogDepth?.let { writer.writeShort(it) }
        record.cubeTexture?.let { cube -> for (material in cube) writer.writeShort(material) }
    }

    private fun writeLights(writer: BufferWriter, record: PointLightsRecord) {
        require(record.lights.size <= 255) {
            "A point-light block holds at most 255 lights, got ${record.lights.size}"
        }
        writer.writeByte(record.lights.size)
        for (light in record.lights) {
            writer.writeByte(light.packedLevel)
            writer.writeShort(light.x)
            writer.writeShort(light.z)
            writer.writeShort(light.heightOffset)
            writer.writeByte(light.packedRadius)
            for (range in light.ranges) writer.writeShort(range)
            writer.writeShort(light.colourHsl)
            writer.writeByte(light.packedType)
            if (light.lightTypeId >= 0) writer.writeShort(light.lightTypeId)
        }
    }

    private fun writeLightGrid(writer: BufferWriter, record: LightGridRecord) {
        for (plane in record.planes) {
            writer.writeByte(plane.mode)
            plane.samples?.let { samples -> for (sample in samples) writer.writeByte(sample.toInt()) }
        }
    }

    fun merge(layout: EnvironmentLayout, edit: EnvironmentEdit): EnvironmentLayout {
        val blocks = ArrayList(layout.blocks)

        var environmentIndex = layout.environmentIndex()
        val existing = if (environmentIndex >= 0) blocks[environmentIndex] as EnvironmentRecord else EnvironmentRecord.EMPTY
        val merged = EnvironmentRecord(
            sunColour = field(existing.sunColour, edit.sunColour, MapEnvironment.DEFAULT_SUN_COLOUR),
            sunAmbient = field(existing.sunAmbient, scaled(edit.sunAmbient), scaled(MapEnvironment.DEFAULT_SUN_AMBIENT)),
            sunLight = field(existing.sunLight, scaled(edit.sunLight), scaled(MapEnvironment.DEFAULT_SUN_LIGHT)),
            sunBacklight = field(
                existing.sunBacklight,
                scaled(edit.sunBacklight),
                scaled(MapEnvironment.DEFAULT_SUN_BACKLIGHT),
            ),
            sunPosition = sunPositionField(existing.sunPosition, edit),
            fogColour = field(existing.fogColour, edit.fogColour, MapEnvironment.DEFAULT_FOG_COLOUR),
            fogDepth = field(existing.fogDepth, edit.fogDepth.coerceIn(0, 0xffff), 0),
            cubeTexture = existing.cubeTexture,
        )

        if (environmentIndex >= 0) {
            blocks[environmentIndex] = merged
        } else if (merged.flags() != 0) {
            blocks.add(0, merged)
            environmentIndex = 0
        }

        mergeSkybox(blocks, environmentIndex, edit)

        return EnvironmentLayout(
            blocks = blocks,
            tileSectionLength = layout.tileSectionLength,
            sourceLength = layout.sourceLength,
        )
    }

    private fun mergeSkybox(
        blocks: MutableList<EffectsBlock>,
        environmentIndex: Int,
        edit: EnvironmentEdit,
    ) {
        val skyboxIndex = blocks.indexOfFirst { it is SkyboxRecord }
        val existing = if (skyboxIndex >= 0) blocks[skyboxIndex] as SkyboxRecord else null
        if (edit.skyboxId < 0) {
            if (skyboxIndex >= 0) blocks.removeAt(skyboxIndex)
            return
        }
        val record = SkyboxRecord(
            id = edit.skyboxId and 0xffff,
            x = existing?.x ?: 0,
            y = existing?.y ?: 0,
            z = existing?.z ?: 0,
            rotation = edit.skyboxRotation and 0xffff,
        )
        if (skyboxIndex >= 0) {
            blocks[skyboxIndex] = record
        } else {
            blocks.add(if (environmentIndex >= 0) environmentIndex + 1 else blocks.size, record)
        }
    }

    private fun field(stored: Int?, edited: Int, default: Int): Int? = when {
        stored != null && stored == edited -> stored
        stored == null && edited == default -> null
        else -> edited
    }

    private fun sunPositionField(stored: IntArray?, edit: EnvironmentEdit): IntArray? {
        val edited = intArrayOf(
            clampShort(edit.sunPositionX),
            clampShort(edit.sunPositionY),
            clampShort(edit.sunPositionZ),
        )
        if (stored != null && stored.contentEquals(edited)) return stored
        if (stored == null &&
            edited[0] == MapEnvironment.DEFAULT_SUN_POSITION_X.toInt() &&
            edited[1] == MapEnvironment.DEFAULT_SUN_POSITION_Y.toInt() &&
            edited[2] == MapEnvironment.DEFAULT_SUN_POSITION_Z.toInt()
        ) {
            return null
        }
        return edited
    }

    private fun scaled(value: Float): Int = (value * LIGHT_SCALE).roundToInt().coerceIn(0, 0xffff)

    private fun clampShort(value: Float): Int = value.roundToInt().coerceIn(-0x8000, 0x7fff)

    fun hdrByte(value: Float): Int = (value * HDR_SCALE).roundToInt().coerceIn(0, 0xff)
}

data class EnvironmentEdit(
    val sunColour: Int,
    val sunPositionX: Float,
    val sunPositionY: Float,
    val sunPositionZ: Float,
    val sunAmbient: Float,
    val sunLight: Float,
    val sunBacklight: Float,
    val fogColour: Int,
    val fogDepth: Int,
    val skyboxId: Int,
    val skyboxRotation: Int,
)
