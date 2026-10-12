package org.darkan.tools.engine.render.sky

import org.joml.Vector3f

enum class SkyBackgroundMode {
    SINGLE_COLOR,

    COLOR_TRANSITION;

    companion object {
        fun of(value: Int): SkyBackgroundMode =
            if (value == 1) COLOR_TRANSITION else SINGLE_COLOR
    }
}

enum class SkyStateRestore {
    QUERY,

    ASSUME_SCENE_DEFAULTS
}

data class SkySun(
    val direction: Vector3f,
    val materialId: Int,
    val sizePixels: Float,
    val alpha: Float = 1.0f
)

data class SkySettings(
    val materialId: Int = -1,
    val backgroundMode: SkyBackgroundMode = SkyBackgroundMode.SINGLE_COLOR,
    val rotation: Int = 0,
    val backgroundColor: Int = DEFAULT_FOG_COLOUR,
    val alpha: Float = 1.0f,
    val suns: List<SkySun> = emptyList()
) {
    companion object {
        const val DEFAULT_FOG_COLOUR = 13156520

        val DEFAULT = SkySettings()
    }
}
