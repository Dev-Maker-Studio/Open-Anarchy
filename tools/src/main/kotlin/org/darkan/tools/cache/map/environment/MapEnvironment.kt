package org.darkan.tools.cache.map.environment

import org.joml.Vector3f

data class MapEnvironment(
    val sunColour: Int,
    val sunPositionX: Float,
    val sunPositionY: Float,
    val sunPositionZ: Float,
    val sunAmbient: Float,
    val sunLight: Float,
    val sunBacklight: Float,
    val fogColour: Int,
    val fogDepth: Int,
    val cubeTexture: CubeTextureRef?,
    val bloom: Float,
    val brightpass: Float,
    val whitePoint: Float,
    val skybox: SkyboxRef?,
) {

    fun sunPosition(dest: Vector3f): Vector3f = dest.set(sunPositionX, sunPositionY, sunPositionZ)

    val fogDensity: Int
        get() = (fogDepth + 256) shl 2

    companion object {
        const val DEFAULT_SUN_COLOUR = 16777215
        const val DEFAULT_FOG_COLOUR = 13156520

        const val DEFAULT_SUN_POSITION_X = -50.0f
        const val DEFAULT_SUN_POSITION_Y = -60.0f
        const val DEFAULT_SUN_POSITION_Z = -50.0f

        const val DEFAULT_SUN_AMBIENT = 1.1523438f
        const val DEFAULT_SUN_LIGHT = 0.69921875f
        const val DEFAULT_SUN_BACKLIGHT = 1.2f

        const val DEFAULT_BLOOM = 1.0f
        const val DEFAULT_BRIGHTPASS = 0.25f
        const val DEFAULT_WHITE_POINT = 1.0f

        val DEFAULT = MapEnvironment(
            sunColour = DEFAULT_SUN_COLOUR,
            sunPositionX = DEFAULT_SUN_POSITION_X,
            sunPositionY = DEFAULT_SUN_POSITION_Y,
            sunPositionZ = DEFAULT_SUN_POSITION_Z,
            sunAmbient = DEFAULT_SUN_AMBIENT,
            sunLight = DEFAULT_SUN_LIGHT,
            sunBacklight = DEFAULT_SUN_BACKLIGHT,
            fogColour = DEFAULT_FOG_COLOUR,
            fogDepth = 0,
            cubeTexture = null,
            bloom = DEFAULT_BLOOM,
            brightpass = DEFAULT_BRIGHTPASS,
            whitePoint = DEFAULT_WHITE_POINT,
            skybox = null,
        )
    }
}

data class CubeTextureRef(
    val positiveX: Int,
    val negativeX: Int,
    val positiveY: Int,
    val negativeY: Int,
    val positiveZ: Int,
    val negativeZ: Int,
)

data class SkyboxRef(
    val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int,
)

data class LightFlicker(
    val effect: Int,
    val duration: Int,
    val ticker: Int,
    val surrounding: Int,
) {
    companion object {
        val NONE = LightFlicker(effect = 0, duration = 2048, ticker = 2048, surrounding = 0)

        fun forType(type: Int): LightFlicker = when (type) {
            2 -> LightFlicker(1, 2048, 2048, 0)
            3 -> LightFlicker(1, 4096, 2048, 0)
            4 -> LightFlicker(4, 2048, 2048, 0)
            5 -> LightFlicker(4, 8192, 2048, 0)
            6 -> LightFlicker(3, 2048, 768, 1280)
            7 -> LightFlicker(3, 4096, 768, 1280)
            8 -> LightFlicker(3, 2048, 1024, 1024)
            9 -> LightFlicker(3, 4096, 1024, 1024)
            10 -> LightFlicker(3, 2048, 512, 1536)
            11 -> LightFlicker(3, 4096, 512, 1536)
            12 -> LightFlicker(2, 2048, 2048, 0)
            13 -> LightFlicker(2, 8192, 2048, 0)
            14 -> LightFlicker(1, 2048, 768, 1280)
            15 -> LightFlicker(1, 4096, 512, 1536)
            16 -> LightFlicker(1, 8192, 256, 1792)
            else -> NONE
        }
    }
}

class MapPointLight(
    val plane: Int,
    val growsUpwards: Boolean,
    val growsDownwards: Boolean,
    val x: Int,
    val heightOffset: Int,
    val z: Int,
    val radius: Int,
    val ranges: ShortArray,
    val colourHsl: Int,
    val colour: Int,
    val type: Int,
    val rotationOffset: Int,
    val lightTypeId: Int,
    val flicker: LightFlicker,
) {
    val tileX: Int
        get() = x shr TILE_SHIFT

    val tileZ: Int
        get() = z shr TILE_SHIFT

    override fun toString(): String =
        "MapPointLight(plane=$plane, tile=($tileX,$tileZ), pos=($x,$heightOffset,$z), radius=$radius, " +
            "colour=0x${colour.toString(16).padStart(6, '0')}, hsl=$colourHsl, type=$type, " +
            "lightTypeId=$lightTypeId, flicker=$flicker, up=$growsUpwards, down=$growsDownwards, " +
            "slices=${ranges.size})"

    companion object {
        const val TILE_SHIFT = 9

        const val TILE_UNITS = 1 shl TILE_SHIFT
    }
}

class RegionEnvironment(
    val regionX: Int,
    val regionY: Int,
    val chunkEnvironments: Array<MapEnvironment?>,
    val lights: List<MapPointLight>,
    val lightGrid: Array<ByteArray?>,
    val environmentBlocks: Int,
    val layout: EnvironmentLayout = EnvironmentLayout.EMPTY,
) {

    fun environment(chunkX: Int, chunkY: Int): MapEnvironment? {
        if (chunkX !in 0 until CHUNKS || chunkY !in 0 until CHUNKS) return null
        return chunkEnvironments[chunkX * CHUNKS + chunkY]
    }

    fun environmentAtTile(localX: Int, localY: Int): MapEnvironment =
        environment(localX shr 3, localY shr 3) ?: MapEnvironment.DEFAULT

    fun lightIntensity(plane: Int, localX: Int, localY: Int): Int {
        if (plane !in 0 until PLANES) return 0
        if (localX !in 0 until SIZE || localY !in 0 until SIZE) return 0
        val grid = lightGrid[plane] ?: return 0
        return grid[localX * SIZE + localY].toInt()
    }

    fun isEmpty(): Boolean =
        environmentBlocks == 0 && lights.isEmpty() && lightGrid.all { it == null }

    companion object {
        const val SIZE = 64
        const val CHUNKS = 8
        const val PLANES = 4

        fun empty(regionX: Int, regionY: Int) = RegionEnvironment(
            regionX = regionX,
            regionY = regionY,
            chunkEnvironments = arrayOfNulls(CHUNKS * CHUNKS),
            lights = emptyList(),
            lightGrid = arrayOfNulls(PLANES),
            environmentBlocks = 0,
        )
    }
}
