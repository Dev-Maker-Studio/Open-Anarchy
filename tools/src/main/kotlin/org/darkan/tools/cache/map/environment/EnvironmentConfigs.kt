package org.darkan.tools.cache.map.environment

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import java.util.concurrent.ConcurrentHashMap

object EnvironmentConfigs {

    const val SKYBOX_ARCHIVE = 29

    const val SUN_ARCHIVE = 30

    const val LIGHT_ARCHIVE = 31

    const val SCENE_CUBE_TEXTURES_ARCHIVE = 1

    private val skyboxes = ConcurrentHashMap<Int, SkyboxType>()
    private val suns = ConcurrentHashMap<Int, SunType>()
    private val lights = ConcurrentHashMap<Int, LightFlicker>()

    @Volatile
    private var defaultCubeTextureLoaded = false

    @Volatile
    private var defaultCubeTexture: CubeTextureRef? = null

    fun fileCount(cache: Cache, archive: Int): Int =
        runCatching { cache.files(Index.CONFIGS, archive).size }.getOrDefault(0)

    fun fileIds(cache: Cache, archive: Int): IntArray =
        runCatching { cache.files(Index.CONFIGS, archive) }.getOrDefault(IntArray(0))

    fun skybox(cache: Cache, id: Int): SkyboxType = skyboxes.getOrPut(id) {
        val data = cache.data(Index.CONFIGS, SKYBOX_ARCHIVE, id)
        if (data == null) SkyboxType() else decodeSkybox(BufferReader(data))
    }

    fun sun(cache: Cache, id: Int): SunType = suns.getOrPut(id) {
        val data = cache.data(Index.CONFIGS, SUN_ARCHIVE, id)
        if (data == null) SunType() else decodeSun(BufferReader(data))
    }

    fun light(cache: Cache, id: Int): LightFlicker = lights.getOrPut(id) {
        val data = cache.data(Index.CONFIGS, LIGHT_ARCHIVE, id)
        if (data == null) LightFlicker.NONE else decodeLight(BufferReader(data))
    }

    fun defaultCubeTexture(cache: Cache): CubeTextureRef? {
        if (defaultCubeTextureLoaded) return defaultCubeTexture
        val data = cache.data(Index.DEFAULTS, SCENE_CUBE_TEXTURES_ARCHIVE, 0)
        defaultCubeTexture = if (data == null) null else decodeCubeTextures(BufferReader(data))
        defaultCubeTextureLoaded = true
        return defaultCubeTexture
    }

    private fun decodeCubeTextures(buffer: BufferReader): CubeTextureRef? {
        var result: CubeTextureRef? = null
        while (buffer.remaining > 0) {
            when (buffer.readUnsignedByte()) {
                0 -> return result
                1 -> result = CubeTextureRef(
                    positiveX = buffer.readUnsignedShort(),
                    negativeX = buffer.readUnsignedShort(),
                    positiveY = buffer.readUnsignedShort(),
                    negativeY = buffer.readUnsignedShort(),
                    positiveZ = buffer.readUnsignedShort(),
                    negativeZ = buffer.readUnsignedShort(),
                )
                else -> return result
            }
        }
        return result
    }

    private fun decodeSkybox(buffer: BufferReader): SkyboxType {
        var materialId = -1
        var sunIds: IntArray? = null
        var defaultSunIndex = -1
        var backgroundMode = BackgroundMode.SINGLE_COLOUR
        var archiveId = -1
        while (buffer.remaining > 0) {
            when (buffer.readUnsignedByte()) {
                0 -> break
                1 -> materialId = buffer.readUnsignedShort()
                2 -> sunIds = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedShort() }
                3 -> defaultSunIndex = buffer.readUnsignedByte()
                4 -> backgroundMode = BackgroundMode.of(buffer.readUnsignedByte())
                5 -> archiveId = buffer.readBigSmart()
                else -> break
            }
        }
        return SkyboxType(materialId, sunIds, defaultSunIndex, backgroundMode, archiveId)
    }

    private fun decodeSun(buffer: BufferReader): SunType {
        var positionOffsetStep = 8
        var invertPosition = false
        var positionX = 0
        var positionY = 0
        var positionZ = 0
        var actionType = 0
        var materialId = 0
        var displayColour = MapEnvironment.DEFAULT_SUN_COLOUR
        var rotationX = 0
        var rotationY = 0
        var rotationZ = 0
        while (buffer.remaining > 0) {
            when (buffer.readUnsignedByte()) {
                0 -> break
                1 -> positionOffsetStep = buffer.readUnsignedShort()
                2 -> invertPosition = true
                3 -> {
                    positionX = buffer.readShort()
                    positionY = buffer.readShort()
                    positionZ = buffer.readShort()
                }
                4 -> actionType = buffer.readUnsignedByte()
                5 -> materialId = buffer.readBigSmart()
                6 -> displayColour = buffer.readUnsignedMedium()
                7 -> {
                    rotationX = buffer.readShort()
                    rotationY = buffer.readShort()
                    rotationZ = buffer.readShort()
                }
                else -> break
            }
        }
        return SunType(
            positionOffsetStep, invertPosition, positionX, positionY, positionZ,
            actionType, materialId, displayColour, rotationX, rotationY, rotationZ,
        )
    }

    private fun decodeLight(buffer: BufferReader): LightFlicker {
        var effect = 0
        var duration = 2048
        var ticker = 2048
        var surrounding = 0
        while (buffer.remaining > 0) {
            when (buffer.readUnsignedByte()) {
                0 -> break
                1 -> effect = buffer.readUnsignedByte()
                2 -> duration = buffer.readUnsignedShort()
                3 -> ticker = buffer.readUnsignedShort()
                4 -> surrounding = buffer.readShort()
                else -> break
            }
        }
        return LightFlicker(effect, duration, ticker, surrounding)
    }

    fun clear() {
        skyboxes.clear()
        suns.clear()
        lights.clear()
        defaultCubeTextureLoaded = false
        defaultCubeTexture = null
    }
}

enum class BackgroundMode(val id: Int) {
    SINGLE_COLOUR(0),
    COLOUR_TRANSITION(1);

    companion object {
        fun of(id: Int): BackgroundMode = entries.firstOrNull { it.id == id } ?: SINGLE_COLOUR
    }
}

data class SkyboxType(
    val materialId: Int = -1,
    val sunIds: IntArray? = null,
    val defaultSunIndex: Int = -1,
    val backgroundMode: BackgroundMode = BackgroundMode.SINGLE_COLOUR,
    val archiveId: Int = -1,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkyboxType) return false
        return materialId == other.materialId &&
            sunIds.contentEquals(other.sunIds) &&
            defaultSunIndex == other.defaultSunIndex &&
            backgroundMode == other.backgroundMode &&
            archiveId == other.archiveId
    }

    override fun hashCode(): Int {
        var result = materialId
        result = 31 * result + sunIds.contentHashCode()
        result = 31 * result + defaultSunIndex
        result = 31 * result + backgroundMode.hashCode()
        result = 31 * result + archiveId
        return result
    }
}

data class SunType(
    val positionOffsetStep: Int = 8,
    val invertPosition: Boolean = false,
    val positionX: Int = 0,
    val positionY: Int = 0,
    val positionZ: Int = 0,
    val actionType: Int = 0,
    val materialId: Int = 0,
    val displayColour: Int = MapEnvironment.DEFAULT_SUN_COLOUR,
    val rotationX: Int = 0,
    val rotationY: Int = 0,
    val rotationZ: Int = 0,
)
