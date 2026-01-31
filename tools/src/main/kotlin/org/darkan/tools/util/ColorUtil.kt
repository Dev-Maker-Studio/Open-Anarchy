package org.darkan.tools.util

import org.joml.Vector3f
import kotlin.math.pow

object ColorUtil {
    /** Brightness/gamma value used by RS (approximately 0.7, with slight randomness in original) */
    private const val BRIGHTNESS = 0.7

    /**
     * Convert RuneScape packed HSL color (16-bit) to RGB.
     * RS HSL format: 6 bits hue (0-63) at bits 10-15, 3 bits saturation (0-7) at bits 7-9, 7 bits lightness (0-127) at bits 0-6
     *
     * Uses the same algorithm as the RS client's calculateHslToRgbTable().
     */
    fun hslToRgb(hsl: Int): Vector3f {
        // Extract components matching client's bit layout
        val hueRaw = (hsl shr 10) and 0x3f    // 6 bits at position 10 (0-63)
        val satRaw = (hsl shr 7) and 0x7      // 3 bits at position 7 (0-7)
        val lightRaw = hsl and 0x7f           // 7 bits at position 0 (0-127)

        // Convert to normalized values matching client algorithm
        // Client uses: hue/64 + 0.0078125, sat/8 + 0.0625, lightness/128
        val h = hueRaw.toDouble() / 64.0 + 0.0078125
        val s = satRaw.toDouble() / 8.0 + 0.0625
        val l = lightRaw.toDouble() / 128.0

        return hslToRgbRS(h, s, l)
    }

    /**
     * Pack raw HSL values (0-255 range) into RS 16-bit HSL format.
     * This matches the client's hsl16to24() function used for underlay blending.
     *
     * @param hue Hue value (0-255, typically hueSum * 256 / divisorSum)
     * @param saturation Saturation value (0-255)
     * @param lightness Lightness value (0-255)
     */
    fun packHsl16(hue: Int, saturation: Int, lightness: Int): Int {
        var sat = saturation
        // Apply saturation reduction at high lightness (same as client)
        when {
            lightness > 243 -> sat = sat shr 4
            lightness > 217 -> sat = sat shr 3
            lightness > 192 -> sat = sat shr 2
            lightness > 179 -> sat = sat shr 1
        }
        // Pack into RS HSL format: hue(6 bits) << 10 | sat(3 bits) << 7 | lightness(7 bits)
        return ((hue and 0xff) shr 2 shl 10) + (lightness shr 1) + (sat shr 5 shl 7)
    }

    /**
     * Convert HSL to RGB using RS-style algorithm with gamma correction.
     * @param h Hue [0-1]
     * @param s Saturation [0-1]
     * @param l Lightness [0-1]
     */
    private fun hslToRgbRS(h: Double, s: Double, l: Double): Vector3f {
        val r: Double
        val g: Double
        val b: Double

        if (s == 0.0) {
            // Achromatic (grayscale)
            r = l
            g = l
            b = l
        } else {
            val q = if (l < 0.5) l * (1.0 + s) else l + s - l * s
            val p = 2.0 * l - q

            r = hueToRgbChannel(p, q, h + 1.0 / 3.0)
            g = hueToRgbChannel(p, q, h)
            b = hueToRgbChannel(p, q, h - 1.0 / 3.0)
        }

        // Apply gamma correction (brightness)
        val rGamma = r.pow(BRIGHTNESS)
        val gGamma = g.pow(BRIGHTNESS)
        val bGamma = b.pow(BRIGHTNESS)

        return Vector3f(rGamma.toFloat(), gGamma.toFloat(), bGamma.toFloat())
    }

    /**
     * Helper function for HSL to RGB conversion.
     * Converts a hue value to an RGB channel value.
     */
    private fun hueToRgbChannel(p: Double, q: Double, t: Double): Double {
        var hue = t
        if (hue < 0.0) hue += 1.0
        if (hue > 1.0) hue -= 1.0

        return when {
            hue < 1.0 / 6.0 -> p + (q - p) * 6.0 * hue
            hue < 1.0 / 2.0 -> q
            hue < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - hue) * 6.0
            else -> p
        }
    }

    /**
     * Convert HSL to RGB (standard, without gamma).
     * @param h Hue [0-1]
     * @param s Saturation [0-1]
     * @param l Lightness [0-1]
     */
    fun hslToRgb(h: Float, s: Float, l: Float): Vector3f {
        val r: Float
        val g: Float
        val b: Float

        if (s == 0f) {
            r = l
            g = l
            b = l
        } else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            r = hueToRgb(p, q, h + 1f / 3f)
            g = hueToRgb(p, q, h)
            b = hueToRgb(p, q, h - 1f / 3f)
        }

        return Vector3f(r, g, b)
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var hue = t
        if (hue < 0f) hue += 1f
        if (hue > 1f) hue -= 1f
        return when {
            hue < 1f / 6f -> p + (q - p) * 6f * hue
            hue < 1f / 2f -> q
            hue < 2f / 3f -> p + (q - p) * (2f / 3f - hue) * 6f
            else -> p
        }
    }

    /**
     * Convert RS packed RGB (24-bit) to Vector3f.
     */
    fun rgbToVector(rgb: Int): Vector3f {
        val r = (rgb shr 16 and 0xff) / 255f
        val g = (rgb shr 8 and 0xff) / 255f
        val b = (rgb and 0xff) / 255f
        return Vector3f(r, g, b)
    }

    /**
     * Convert Vector3f RGB to packed RGB (24-bit).
     */
    fun vectorToRgb(color: Vector3f): Int {
        val r = (color.x * 255).toInt().coerceIn(0, 255)
        val g = (color.y * 255).toInt().coerceIn(0, 255)
        val b = (color.z * 255).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * Adjust brightness of a color.
     */
    fun adjustBrightness(color: Vector3f, brightness: Int): Vector3f {
        val factor = 1f + brightness / 512f
        return Vector3f(
            (color.x * factor).coerceIn(0f, 1f),
            (color.y * factor).coerceIn(0f, 1f),
            (color.z * factor).coerceIn(0f, 1f)
        )
    }

    /**
     * Lerp between two colors.
     */
    fun lerp(a: Vector3f, b: Vector3f, t: Float): Vector3f {
        return Vector3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
    }
}
