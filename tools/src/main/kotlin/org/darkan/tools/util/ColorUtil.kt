package org.darkan.tools.util

import org.joml.Vector3f
import kotlin.math.pow

object ColorUtil {
    /** Brightness/gamma value used by RS (approximately 0.7, with slight randomness in original) */
    private const val BRIGHTNESS = 0.7

    /**
     * Convert RuneScape HSL color to RGB.
     * RS HSL format: 6 bits hue (0-63), 3 bits saturation (0-7), 7 bits lightness (0-127)
     *
     * This matches the RS client's calculateHslToRgbTable() method, including:
     * - Proper offset values for hue and saturation
     * - Gamma correction (brightness ~0.7)
     */
    fun hslToRgb(hsl: Int): Vector3f {
        // RS uses these offsets to avoid pure black/white at extremes
        val h = (hsl shr 10 and 0x3f) / 64.0 + 0.0078125
        val s = (hsl shr 7 and 0x7) / 8.0 + 0.0625
        val l = (hsl and 0x7f) / 128.0

        return hslToRgbRS(h, s, l)
    }

    /**
     * Convert HSL to RGB using RS-style algorithm with gamma correction.
     * @param h Hue [0-1]
     * @param s Saturation [0-1]
     * @param l Lightness [0-1]
     */
    private fun hslToRgbRS(h: Double, s: Double, l: Double): Vector3f {
        var r = l
        var g = l
        var b = l

        if (s != 0.0) {
            val q = if (l < 0.5) l * (s + 1.0) else s + l - l * s
            val p = l * 2.0 - q

            // Red (hue + 1/3)
            var hueR = h + 1.0 / 3.0
            if (hueR > 1.0) hueR -= 1.0
            r = when {
                hueR * 6.0 < 1.0 -> p + (q - p) * 6.0 * hueR
                hueR * 2.0 < 1.0 -> q
                hueR * 3.0 < 2.0 -> p + 6.0 * (2.0 / 3.0 - hueR) * (q - p)
                else -> p
            }

            // Green (hue)
            g = when {
                h * 6.0 < 1.0 -> p + 6.0 * (q - p) * h
                h * 2.0 < 1.0 -> q
                h * 3.0 < 2.0 -> 6.0 * (2.0 / 3.0 - h) * (q - p) + p
                else -> p
            }

            // Blue (hue - 1/3)
            var hueB = h - 1.0 / 3.0
            if (hueB < 0.0) hueB += 1.0
            b = when {
                hueB * 6.0 < 1.0 -> hueB * 6.0 * (q - p) + p
                hueB * 2.0 < 1.0 -> q
                hueB * 3.0 < 2.0 -> 6.0 * (2.0 / 3.0 - hueB) * (q - p) + p
                else -> p
            }
        }

        // Apply gamma correction (brightness)
        r = r.pow(BRIGHTNESS)
        g = g.pow(BRIGHTNESS)
        b = b.pow(BRIGHTNESS)

        return Vector3f(r.toFloat(), g.toFloat(), b.toFloat())
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
