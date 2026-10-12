package org.darkan.tools.cache.map.environment

class EnvironmentLayout(
    val blocks: List<EffectsBlock>,
    val tileSectionLength: Int = 0,
    val sourceLength: Int = -1,
) {

    fun environmentIndex(): Int = blocks.indexOfFirst { it is EnvironmentRecord }

    fun hdrIndex(): Int = blocks.indexOfFirst { it is HdrRecord }

    fun skyboxIndex(): Int = blocks.indexOfFirst { it is SkyboxRecord }

    fun encodedSize(): Int = blocks.sumOf { it.encodedSize() }

    companion object {
        val EMPTY = EnvironmentLayout(emptyList())
    }
}

sealed interface EffectsBlock {
    val opcode: Int

    fun encodedSize(): Int
}

class EnvironmentRecord(
    val sunColour: Int?,
    val sunAmbient: Int?,
    val sunLight: Int?,
    val sunBacklight: Int?,
    val sunPosition: IntArray?,
    val fogColour: Int?,
    val fogDepth: Int?,
    val cubeTexture: IntArray?,
) : EffectsBlock {

    override val opcode: Int get() = 0

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

    override fun encodedSize(): Int {
        var size = 2
        if (sunColour != null) size += 4
        if (sunAmbient != null) size += 2
        if (sunLight != null) size += 2
        if (sunBacklight != null) size += 2
        if (sunPosition != null) size += 6
        if (fogColour != null) size += 4
        if (fogDepth != null) size += 2
        if (cubeTexture != null) size += 12
        return size
    }

    fun copy(
        sunColour: Int? = this.sunColour,
        sunAmbient: Int? = this.sunAmbient,
        sunLight: Int? = this.sunLight,
        sunBacklight: Int? = this.sunBacklight,
        sunPosition: IntArray? = this.sunPosition,
        fogColour: Int? = this.fogColour,
        fogDepth: Int? = this.fogDepth,
        cubeTexture: IntArray? = this.cubeTexture,
    ) = EnvironmentRecord(
        sunColour, sunAmbient, sunLight, sunBacklight, sunPosition, fogColour, fogDepth, cubeTexture
    )

    companion object {
        const val FLAG_SUN_COLOUR = 0x1
        const val FLAG_SUN_AMBIENT = 0x2
        const val FLAG_SUN_LIGHT = 0x4
        const val FLAG_SUN_BACKLIGHT = 0x8
        const val FLAG_SUN_POSITION = 0x10
        const val FLAG_FOG_COLOUR = 0x20
        const val FLAG_FOG_DEPTH = 0x40
        const val FLAG_CUBE_TEXTURE = 0x80

        val EMPTY = EnvironmentRecord(null, null, null, null, null, null, null, null)
    }
}

class HdrRecord(val bloom: Int, val brightpass: Int, val whitePoint: Int) : EffectsBlock {
    override val opcode: Int get() = 2
    override fun encodedSize(): Int = 4
}

class SkyboxRecord(
    val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int,
) : EffectsBlock {
    override val opcode: Int get() = 128
    override fun encodedSize(): Int = 11
}

class PointLightsRecord(val lights: List<RawPointLight>) : EffectsBlock {
    override val opcode: Int get() = 1
    override fun encodedSize(): Int = 2 + lights.sumOf { it.encodedSize() }
}

class RawPointLight(
    val packedLevel: Int,
    val x: Int,
    val z: Int,
    val heightOffset: Int,
    val packedRadius: Int,
    val ranges: IntArray,
    val colourHsl: Int,
    val packedType: Int,
    val lightTypeId: Int,
) {
    fun encodedSize(): Int = 1 + 2 + 2 + 2 + 1 + ranges.size * 2 + 2 + 1 + (if (lightTypeId >= 0) 2 else 0)
}

class LightGridRecord(val planes: Array<RawLightGridPlane>) : EffectsBlock {
    override val opcode: Int get() = 129
    override fun encodedSize(): Int = 1 + planes.sumOf { it.encodedSize() }
}

class RawLightGridPlane(val mode: Int, val samples: ByteArray?) {
    fun encodedSize(): Int = 1 + (samples?.size ?: 0)

    companion object {
        const val MODE_UNLIT = 0
        const val MODE_SAMPLES = 1
        const val MODE_COPY_BELOW = 2

        const val BLOCK = 4
        const val SAMPLES_PER_AXIS = RegionEnvironment.SIZE / BLOCK
        const val SAMPLE_COUNT = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS
    }
}
