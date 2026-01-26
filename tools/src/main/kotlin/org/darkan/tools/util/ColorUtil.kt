package org.darkan.tools.util

import org.joml.Vector3f

object ColorUtil {
    /**
     * Convert RuneScape HSL color to RGB.
     * RS HSL format: 10 bits hue (0-63), 3 bits saturation (0-7), 7 bits lightness (0-127)
     */
    fun hslToRgb(hsl: Int): Vector3f {
        val h = (hsl shr 10 and 0x3f) / 64f
        val s = (hsl shr 7 and 0x7) / 8f
        val l = (hsl and 0x7f) / 128f

        return hslToRgb(h, s, l)
    }

    /**
     * Convert HSL to RGB.
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
