package org.darkan.tools.util

import org.joml.Vector3f
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

object ColorUtil {
    private const val BRIGHTNESS = 0.7

    fun getVertexNoise(worldX: Float, worldZ: Float, variation: Float = 0.03f): Float {
        val seed = (worldX * 12.9898 + worldZ * 78.233)
        val hash = sin(seed) * 43758.5453
        val noise = (hash - floor(hash)).toFloat()
        return 1.0f - variation * 0.5f + noise * variation
    }

    fun applyBrightnessNoise(packedHsl: Int, noiseFactor: Float): Int {
        if (packedHsl == 0) return 0

        val hue = packedHsl and 0xfc00
        val saturation = packedHsl and 0x380
        val lightness = packedHsl and 0x7f

        var newLightness = (lightness * noiseFactor).toInt()

        if (newLightness < 2) {
            newLightness = 2
        } else if (newLightness > 126) {
            newLightness = 126
        }

        return hue or saturation or newLightness
    }

    fun hslToRgb(hsl: Int): Vector3f {
        if (hsl in 0 until HSL_TABLE_SIZE) {
            var entry = hslRgbTable.get(hsl)
            if (entry == null) {
                entry = computeHslToRgb(hsl)
                hslRgbTable.compareAndSet(hsl, null, entry)
            }
            return Vector3f(entry[0], entry[1], entry[2])
        }
        val rgb = computeHslToRgb(hsl)
        return Vector3f(rgb[0], rgb[1], rgb[2])
    }

    private const val HSL_TABLE_SIZE = 1 shl 16

    private val hslRgbTable = AtomicReferenceArray<FloatArray>(HSL_TABLE_SIZE)

    fun hslToRgbUncached(hsl: Int): Vector3f {
        val rgb = computeHslToRgb(hsl)
        return Vector3f(rgb[0], rgb[1], rgb[2])
    }

    private fun computeHslToRgb(hsl: Int): FloatArray {
        val hue = (hsl shr 10 and 0x3f) / 64.0 + 0.0078125
        val saturation = 0.0625 + (hsl shr 7 and 0x7) / 8.0
        val lightness = (hsl and 0x7f) / 128.0

        var r = lightness
        var g = lightness
        var b = lightness

        if (saturation != 0.0) {
            val q: Double = if (lightness < 0.5) {
                lightness * (saturation + 1.0)
            } else {
                saturation + lightness - lightness * saturation
            }

            val p = lightness * 2.0 - q

            var hueR = 0.3333333333333333 + hue
            if (hueR > 1.0) hueR -= 1.0

            var hueB = hue - 0.3333333333333333
            if (hueB < 0.0) hueB += 1.0

            r = if (hueR * 6.0 < 1.0) {
                p + (q - p) * 6.0 * hueR
            } else if (2.0 * hueR < 1.0) {
                q
            } else if (3.0 * hueR < 2.0) {
                p + 6.0 * (0.6666666666666666 - hueR) * (q - p)
            } else {
                p
            }

            g = if (6.0 * hue < 1.0) {
                p + 6.0 * (q - p) * hue
            } else if (hue * 2.0 < 1.0) {
                q
            } else if (3.0 * hue < 2.0) {
                6.0 * (0.6666666666666666 - hue) * (q - p) + p
            } else {
                p
            }

            b = if (hueB * 6.0 < 1.0) {
                hueB * 6.0 * (q - p) + p
            } else if (hueB * 2.0 < 1.0) {
                q
            } else if (hueB * 3.0 < 2.0) {
                6.0 * (0.6666666666666666 - hueB) * (q - p) + p
            } else {
                p
            }
        }

        val rGamma = r.pow(BRIGHTNESS).toFloat()
        val gGamma = g.pow(BRIGHTNESS).toFloat()
        val bGamma = b.pow(BRIGHTNESS).toFloat()

        return floatArrayOf(rGamma, gGamma, bGamma)
    }

    fun hsvToRgb(hsl: Int): Vector3f {
        val hueRaw = (hsl shr 10) and 0x3f
        val satRaw = (hsl shr 7) and 0x7
        val valueRaw = hsl and 0x7f

        val hueDegrees = 360.0f * (hueRaw / 64.0f + 0.0078125f)
        val saturation = satRaw / 8.0f + 0.0625f
        val value = valueRaw / 128.0f

        var r = 0.0f
        var g = 0.0f
        var b = 0.0f

        val hueNorm = hueDegrees / 60.0f
        val hueSector = hueNorm.toInt()
        val hueFraction = hueNorm - hueSector
        val p = value * (1.0f - saturation)
        val q = value * (1.0f - saturation * hueFraction)
        val t = (1.0f - saturation * (1.0f - hueFraction)) * value

        when (hueSector % 6) {
            0 -> { r = value; g = t; b = p }
            1 -> { r = q; g = value; b = p }
            2 -> { r = p; g = value; b = t }
            3 -> { r = p; g = q; b = value }
            4 -> { r = t; g = p; b = value }
            5 -> { r = value; g = p; b = q }
        }

        val rGamma = r.toDouble().pow(BRIGHTNESS).toFloat()
        val gGamma = g.toDouble().pow(BRIGHTNESS).toFloat()
        val bGamma = b.toDouble().pow(BRIGHTNESS).toFloat()

        return Vector3f(rGamma, gGamma, bGamma)
    }

    fun packHsl16(hue: Int, saturation: Int, lightness: Int): Int {
        var sat = saturation
        when {
            lightness > 243 -> sat = sat shr 4
            lightness > 217 -> sat = sat shr 3
            lightness > 192 -> sat = sat shr 2
            lightness > 179 -> sat = sat shr 1
        }
        return ((hue and 0xff) shr 2 shl 10) + (lightness shr 1) + (sat shr 5 shl 7)
    }

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

    fun rgbToVector(rgb: Int): Vector3f {
        val r = (rgb shr 16 and 0xff) / 255f
        val g = (rgb shr 8 and 0xff) / 255f
        val b = (rgb and 0xff) / 255f
        return Vector3f(r, g, b)
    }

    fun vectorToRgb(color: Vector3f): Int {
        val r = (color.x * 255).toInt().coerceIn(0, 255)
        val g = (color.y * 255).toInt().coerceIn(0, 255)
        val b = (color.z * 255).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    fun blendColorLightness(hslColor: Int, lightnessFactor: Int): Int {
        var blendedLightness = (hslColor and 0x7f) * lightnessFactor shr 7
        if (blendedLightness < 2) {
            blendedLightness = 2
        } else if (blendedLightness > 126) {
            blendedLightness = 126
        }
        return (hslColor and 0xff80) + blendedLightness
    }

    fun adjustBrightness(color: Vector3f, brightness: Int): Vector3f {
        val factor = 1f + brightness / 512f
        return Vector3f(
            (color.x * factor).coerceIn(0f, 1f),
            (color.y * factor).coerceIn(0f, 1f),
            (color.z * factor).coerceIn(0f, 1f)
        )
    }

    fun lerp(a: Vector3f, b: Vector3f, t: Float): Vector3f {
        return Vector3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
    }

    fun blendHsl(colorA: Int, colorB: Int, factor: Int): Int {
        if (colorA == colorB) return colorA

        val invFactor = 128 - factor
        val lightness = (invFactor * (colorA and 0x7f) + (colorB and 0x7f) * factor) shr 7
        val saturation = (invFactor * (colorA and 0x380) + factor * (colorB and 0x380)) shr 7
        val hue = ((colorB and 0xfc00) * factor + invFactor * (colorA and 0xfc00)) shr 7
        return (hue and 0xfc00) or (saturation and 0x380) or (lightness and 0x7f)
    }

    fun bilinearBlendHsl(sw: Int, se: Int, nw: Int, ne: Int, fx: Float, fy: Float): Int {
        val factorX = (fx * 128).toInt().coerceIn(0, 128)
        val factorY = (fy * 128).toInt().coerceIn(0, 128)

        val south = blendHsl(sw, se, factorX)
        val north = blendHsl(nw, ne, factorX)

        return blendHsl(south, north, factorY)
    }

    fun applyLightToHsl(packedHsl: Int, lightFactor: Float): Int {
        if (packedHsl == 0) return 0

        val hue = packedHsl and 0xfc00
        val saturation = packedHsl and 0x380
        val lightness = packedHsl and 0x7f

        var newLightness = (lightness * lightFactor).toInt()

        if (newLightness < 2) {
            newLightness = 2
        } else if (newLightness > 126) {
            newLightness = 126
        }

        return hue or saturation or newLightness
    }

    fun calculateTerrainLightFactor(
        normalX: Float, normalY: Float, normalZ: Float,
        lightDirX: Float = -0.53f, lightDirY: Float = -0.64f, lightDirZ: Float = -0.53f,
        sunBrightness: Float = 0.9f,
        brightFactor: Float = 0.7f,
        darkFactor: Float = 1.2f
    ): Float {
        val dot = normalX * lightDirX + normalY * lightDirY + normalZ * lightDirZ

        val factor = if (dot > 0f) {
            sunBrightness + dot * brightFactor
        } else {
            sunBrightness + dot * darkFactor
        }

        return factor.coerceIn(0.4f, 1.3f)
    }

    fun calculateTerrainLightFactorSimple(
        normalX: Float, normalY: Float, normalZ: Float,
        lightDirX: Float, lightDirY: Float, lightDirZ: Float,
        ambientFactor: Float,
        diffuseStrength: Float
    ): Float {
        val dot = normalX * lightDirX + normalY * lightDirY + normalZ * lightDirZ

        val diffuse = if (dot > 0) dot * diffuseStrength else dot * diffuseStrength * 0.5f

        return (ambientFactor + diffuse).coerceIn(0.4f, 1.2f)
    }
}
