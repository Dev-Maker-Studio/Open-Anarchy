package world.gregs.voidps.cache.definition.data

sealed interface MapEffect {

    val opcode: Int
}

class MapEnvironmentEffect(
    val sunColour: Int? = null,
    val sunAmbient: Int? = null,
    val sunLight: Int? = null,
    val sunBacklight: Int? = null,
    val sunPosition: IntArray? = null,
    val fogColour: Int? = null,
    val fogDepth: Int? = null,
    val cubeTexture: IntArray? = null
) : MapEffect {

    override val opcode: Int
        get() = 0

    fun flags(): Int {
        var flags = 0
        if (sunColour != null) flags = flags or FLAG_SUN_COLOUR
        if (sunAmbient != null) flags = flags or FLAG_SUN_AMBIENT
        if (sunLight != null) flags = flags or FLAG_SUN_LIGHT
        if (sunBacklight != null) flags = flags or FLAG_SUN_BACKLIGHT
        if (sunPosition != null) flags = flags or FLAG_SUN_POSITION
        if (fogColour != null) flags = flags or FLAG_FOG_COLOUR
        if (fogDepth != null) flags = flags or FLAG_FOG_DEPTH
        if (cubeTexture != null) flags = flags or FLAG_CUBE_TEXTURE
        return flags
    }

    companion object {
        const val FLAG_SUN_COLOUR = 0x1
        const val FLAG_SUN_AMBIENT = 0x2
        const val FLAG_SUN_LIGHT = 0x4
        const val FLAG_SUN_BACKLIGHT = 0x8
        const val FLAG_SUN_POSITION = 0x10
        const val FLAG_FOG_COLOUR = 0x20
        const val FLAG_FOG_DEPTH = 0x40
        const val FLAG_CUBE_TEXTURE = 0x80

        const val CUBE_TEXTURE_SIZE = 6

        const val SUN_POSITION_SIZE = 3
    }
}

class MapLightsEffect(val lights: List<MapLight>) : MapEffect {

    override val opcode: Int
        get() = 1

    companion object {
        const val MAX_LIGHTS = 255
    }
}

class MapLight(
    val packedLevel: Int,
    val x: Int,
    val z: Int,
    val heightOffset: Int,
    val packedRadius: Int,
    val ranges: IntArray,
    val colour: Int,
    val packedType: Int,
    val lightType: Int
) {

    companion object {
        const val CONFIG_TYPE = 31
    }
}

class MapHdrEffect(val bloom: Int, val brightpass: Int, val whitePoint: Int) : MapEffect {

    override val opcode: Int
        get() = 2
}

class MapSkyboxEffect(
    val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int
) : MapEffect {

    override val opcode: Int
        get() = 128
}

class MapLightGridEffect(val levels: List<MapLightGridLevel>) : MapEffect {

    override val opcode: Int
        get() = 129

    companion object {
        const val LEVELS = 4
    }
}

class MapLightGridLevel(val mode: Int, val samples: ByteArray?) {

    companion object {
        const val MODE_UNLIT = 0
        const val MODE_SAMPLES = 1
        const val MODE_COPY_BELOW = 2

        const val BLOCK = 4

        const val SAMPLE_COUNT = (64 / BLOCK) * (64 / BLOCK)
    }
}