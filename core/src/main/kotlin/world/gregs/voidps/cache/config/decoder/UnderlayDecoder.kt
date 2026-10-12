package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.FLOOR_UNDERLAY
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.config.encoder.UnderlayEncoder

class UnderlayDecoder : ConfigDecoder<UnderlayDefinition>(FLOOR_UNDERLAY) {

    private val encoder = UnderlayEncoder()

    override fun create(size: Int) = Array(size) { UnderlayDefinition(it) }

    override fun canonicalOpcodes(definition: UnderlayDefinition): IntArray = encoder.opcodes(definition)

    override fun UnderlayDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                colour = buffer.readUnsignedMedium()
                computeHsl(colour)
            }
            2 -> {
                texture = buffer.readShort()
                if (texture == 65535) {
                    texture = -1
                }
            }
            3 -> scale = buffer.readShort() shl 2
            4 -> blockShadow = false
            5 -> aBoolean2892 = false
            else -> error("Unhandled underlay definition opcode: $opcode for def $id")
        }
    }


    private fun UnderlayDefinition.computeHsl(rgb: Int) {
        val r = (rgb shr 16 and 0xff).toDouble() / 256.0
        val g = (rgb shr 8 and 0xff).toDouble() / 256.0
        val b = (rgb and 0xff).toDouble() / 256.0

        var minimum = r
        if (g < minimum) minimum = g
        if (b < minimum) minimum = b

        var maximum = r
        if (g > maximum) maximum = g
        if (b > maximum) maximum = b

        val l = (minimum + maximum) / 2.0

        var h = 0.0
        var s = 0.0
        if (minimum != maximum) {
            s = if (l < 0.5) {
                (maximum - minimum) / (maximum + minimum)
            } else {
                (maximum - minimum) / (2.0 - maximum - minimum)
            }

            h = when (maximum) {
                r -> (g - b) / (maximum - minimum)
                g -> 2.0 + (b - r) / (maximum - minimum)
                else -> 4.0 + (r - g) / (maximum - minimum)
            }
        }

        saturation = (s * 256.0).toInt()
        lightness = (l * 256.0).toInt()

        h /= 6.0

        chroma = if (l > 0.5) {
            (s * (1.0 - l) * 512.0).toInt()
        } else {
            (s * l * 512.0).toInt()
        }

        if (saturation < 0) saturation = 0
        else if (saturation > 255) saturation = 255

        if (lightness < 0) lightness = 0
        else if (lightness > 255) lightness = 255

        if (chroma < 1) chroma = 1

        hue = (h * chroma.toDouble()).toInt()
    }
}