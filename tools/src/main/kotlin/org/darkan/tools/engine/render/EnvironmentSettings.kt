package org.darkan.tools.engine.render

import org.darkan.tools.cache.map.environment.MapEnvironment
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import kotlin.math.sqrt

class EnvironmentSettings {

    var sunColour: Int = MapEnvironment.DEFAULT_SUN_COLOUR

    var sunPositionX: Float = MapEnvironment.DEFAULT_SUN_POSITION_X
    var sunPositionY: Float = MapEnvironment.DEFAULT_SUN_POSITION_Y
    var sunPositionZ: Float = MapEnvironment.DEFAULT_SUN_POSITION_Z

    var sunAmbient: Float = MapEnvironment.DEFAULT_SUN_AMBIENT

    var sunLight: Float = MapEnvironment.DEFAULT_SUN_LIGHT

    var sunBacklight: Float = MapEnvironment.DEFAULT_SUN_BACKLIGHT

    var fogColour: Int = MapEnvironment.DEFAULT_FOG_COLOUR

    var fogDepth: Int = 0

    var skyboxId: Int = -1

    var skyboxRotation: Int = 0

    var fogEnabled: Boolean = System.getProperty("darkan.fog", "false").toBoolean()

    val backgroundColour: Int
        get() = if (fogEnabled) fogColour else EDITOR_SKY_COLOUR

    var dynamicSunLighting: Boolean = System.getProperty("darkan.dynamicSun", "true").toBoolean()

    var fogDistanceMultiplier: Float = DEFAULT_FOG_DISTANCE_MULTIPLIER

    var nearClipDistance: Float = 1.0f

    var fromCache: Boolean = false
        private set

    fun applyFrom(environment: MapEnvironment, fromCache: Boolean) {
        sunColour = environment.sunColour
        sunPositionX = environment.sunPositionX
        sunPositionY = environment.sunPositionY
        sunPositionZ = environment.sunPositionZ
        sunAmbient = environment.sunAmbient
        sunLight = environment.sunLight
        sunBacklight = environment.sunBacklight
        fogColour = environment.fogColour
        fogDepth = environment.fogDepth
        skyboxId = environment.skybox?.id ?: -1
        skyboxRotation = environment.skybox?.rotation ?: 0
        this.fromCache = fromCache
    }

    fun sunDirection(dest: Vector3f): Vector3f {
        val x = sunPositionX
        val y = -sunPositionY
        val z = -sunPositionZ
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1e-6f) {
            return dest.set(0f, 1f, 0f)
        }
        return dest.set(x / length, y / length, z / length)
    }

    val sunBrightness: Float
        get() = SUN_BRIGHTNESS_BASE * sunAmbient

    val fogDensityUnits: Float
        get() = ((fogDepth + 256) shl 2) * RS_TO_ENGINE

    val viewRangeEndRs: Float
        get() = CLIENT_VIEW_RANGE_BASE + CLIENT_FOG_DISTANCE_STEP * fogDistanceMultiplier

    val fogEndUnits: Float
        get() = viewRangeEndRs * RS_TO_ENGINE

    val fogStartUnits: Float
        get() {
            val start = fogEndUnits - fogDensityUnits * DENSITY_FACTOR
            return if (start < nearClipDistance) nearClipDistance else start
        }

    fun writeStd140(out: FloatArray, direction: Vector3f) {
        sunDirection(direction)
        out[0] = direction.x
        out[1] = direction.y
        out[2] = direction.z
        out[3] = 0f

        out[4] = red(sunColour)
        out[5] = green(sunColour)
        out[6] = blue(sunColour)
        out[7] = 0f

        out[8] = sunBrightness
        out[9] = sunLight
        out[10] = sunBacklight
        out[11] = if (dynamicSunLighting) 1f else 0f

        out[12] = red(fogColour)
        out[13] = green(fogColour)
        out[14] = blue(fogColour)
        out[15] = if (fogEnabled) 1f else 0f

        val start = fogStartUnits
        val end = fogEndUnits
        val span = end - start
        out[16] = start
        out[17] = end
        out[18] = if (span > 1e-4f) 1f / span else 0f
        out[19] = 0f
    }

    companion object {
        const val STD140_FLOATS = 20

        const val SUN_BRIGHTNESS_BASE = 0.7f

        const val DENSITY_FACTOR = 1.0f

        const val EDITOR_SKY_COLOUR = 0x6699CC

        const val CLIENT_VIEW_RANGE_BASE = 14332f

        const val CLIENT_FOG_DISTANCE_STEP = 3072f

        const val DEFAULT_FOG_DISTANCE_MULTIPLIER = 10f

        const val RS_TO_ENGINE = CoordinateUtil.DEFAULT_SCALE

        fun red(packed: Int): Float = ((packed shr 16) and 0xFF) / 255f
        fun green(packed: Int): Float = ((packed shr 8) and 0xFF) / 255f
        fun blue(packed: Int): Float = (packed and 0xFF) / 255f

        fun pack(r: Float, g: Float, b: Float): Int {
            val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (ri shl 16) or (gi shl 8) or bi
        }
    }
}
