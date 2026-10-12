package org.darkan.tools.engine.render.sky

import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.render.Camera
import org.darkan.tools.engine.render.ShaderProgram
import org.joml.Vector3f
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_CULL_FACE
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK
import org.lwjgl.opengl.GL11.GL_LEQUAL
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_REPEAT
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDepthFunc
import org.lwjgl.opengl.GL11.glDepthMask
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetBoolean
import org.lwjgl.opengl.GL11.glIsEnabled
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glUniform2f
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.system.MemoryUtil
import world.gregs.voidps.cache.Cache

class SkyRenderer(private val cache: Cache? = null) {

    var stateRestore: SkyStateRestore = SkyStateRestore.QUERY

    var settings: SkySettings = SkySettings.DEFAULT

    private var backgroundShader: ShaderProgram? = null
    private var vaoId: Int = 0

    private val textures = HashMap<Int, SkyMaterial?>()

    private val solidColor = Vector3f()
    private val topColor = Vector3f()
    private val bottomColor = Vector3f()

    private var uSkyViewport = -1
    private var uSkyOffset = -1

    fun init() {
        if (backgroundShader != null) {
            return
        }
        val background = ShaderProgram.createWithUniforms(
            source("/shaders/sky_background.vert"),
            source("/shaders/sky_background.frag"),
            "skyMode", "skyColor", "skyTopColor", "skyBottomColor", "skyAlpha", "skySampler"
        )
        uSkyViewport = glGetUniformLocation(background.id, "skyViewport")
        uSkyOffset = glGetUniformLocation(background.id, "skyOffset")
        backgroundShader = background
        vaoId = glGenVertexArrays()
    }

    fun render(camera: Camera, viewportWidth: Int, viewportHeight: Int) {
        render(camera.yaw, camera.pitch, viewportWidth, viewportHeight)
    }

    fun render(
        yawDegrees: Float,
        pitchDegrees: Float,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val shader = backgroundShader ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return
        }

        val width = viewportWidth.toFloat()
        val height = viewportHeight.toFloat()

        val rotationX = wrap(-toAngleUnits(yawDegrees) + settings.rotation.toFloat(), ANGLE_UNITS)
        val rotationY = -toAngleUnits(pitchDegrees)
        val xOffset = wrap(rotationX * height / 4096f + (width - height) * 0.5f, height)
        val yOffset = wrap(rotationY * height / -4096f, height)

        val material = if (settings.materialId >= 0) material(settings.materialId) else null
        val mode = when {
            material == null -> MODE_SOLID
            settings.backgroundMode == SkyBackgroundMode.COLOR_TRANSITION -> MODE_TRANSITION
            else -> MODE_REPEAT
        }

        val alpha = settings.alpha.coerceIn(0f, 1f)
        val blended = alpha < 1f

        val query = stateRestore == SkyStateRestore.QUERY
        val cullWasEnabled = if (query) glIsEnabled(GL_CULL_FACE) else true
        val blendWasEnabled = if (query) glIsEnabled(GL_BLEND) else false
        val depthWasEnabled = if (query) glIsEnabled(GL_DEPTH_TEST) else true
        val depthWriteWasEnabled = if (query) glGetBoolean(GL_DEPTH_WRITEMASK) else true

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        if (blended) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        } else {
            glDisable(GL_BLEND)
        }

        glBindVertexArray(vaoId)
        shader.bind()
        shader.setUniform("skyMode", mode)
        shader.setUniform("skyAlpha", alpha)
        shader.setUniform("skySampler", 0)
        unpack(settings.backgroundColor, solidColor)
        shader.setUniform("skyColor", solidColor)

        if (material != null) {
            topColor.set(material.topColor)
            bottomColor.set(material.bottomColor)
            if (!material.topOpaque) {
                topColor.set(solidColor)
            }
            if (!material.bottomOpaque) {
                bottomColor.set(solidColor)
            }
            shader.setUniform("skyTopColor", topColor)
            shader.setUniform("skyBottomColor", bottomColor)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, material.textureId)
        }
        glUniform2f(uSkyViewport, width, height)
        glUniform2f(uSkyOffset, xOffset, yOffset)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        shader.unbind()
        glBindVertexArray(0)
        glBindTexture(GL_TEXTURE_2D, 0)

        glDepthMask(depthWriteWasEnabled)
        if (!depthWasEnabled) {
            glDisable(GL_DEPTH_TEST)
        }
        if (cullWasEnabled) {
            glEnable(GL_CULL_FACE)
        }
        if (blendWasEnabled) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        } else {
            glDisable(GL_BLEND)
        }
    }

    private fun material(materialId: Int): SkyMaterial? {
        if (materialId < 0) {
            return null
        }
        if (textures.containsKey(materialId)) {
            return textures[materialId]
        }
        val built = buildMaterial(materialId)
        textures[materialId] = built
        return built
    }

    private fun buildMaterial(materialId: Int): SkyMaterial? {
        val cache = this.cache ?: return null
        val pixels = try {
            TextureLoader.getTexturePixelsForMaterial(cache, materialId)
        } catch (e: Exception) {
            System.err.println("SkyRenderer: failed to generate skybox material $materialId: ${e.message}")
            null
        } ?: return null

        val (argb, width, height) = pixels
        if (argb.isEmpty() || width <= 0 || height <= 0) {
            return null
        }

        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            for (i in 0 until (width * height)) {
                val pixel = argb[i]
                buffer.put(((pixel shr 16) and 0xFF).toByte())
                buffer.put(((pixel shr 8) and 0xFF).toByte())
                buffer.put((pixel and 0xFF).toByte())
                buffer.put(((pixel shr 24) and 0xFF).toByte())
            }
            buffer.flip()
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }
        glBindTexture(GL_TEXTURE_2D, 0)

        val first = argb[0]
        val last = argb[argb.size - 1]
        return SkyMaterial(
            textureId = textureId,
            topColor = unpack(first, Vector3f()),
            bottomColor = unpack(last, Vector3f()),
            topOpaque = ((first shr 24) and 0xFF) != 0,
            bottomOpaque = ((last shr 24) and 0xFF) != 0
        )
    }

    fun clearTextureCache() {
        textures.values.forEach { it?.let { m -> glDeleteTextures(m.textureId) } }
        textures.clear()
    }

    fun cleanup() {
        clearTextureCache()
        backgroundShader?.cleanup()
        backgroundShader = null
        if (vaoId != 0) {
            glDeleteVertexArrays(vaoId)
            vaoId = 0
        }
    }

    private fun source(path: String): String =
        SkyRenderer::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: throw RuntimeException("Could not load shader: $path")

    private class SkyMaterial(
        val textureId: Int,
        val topColor: Vector3f,
        val bottomColor: Vector3f,
        val topOpaque: Boolean,
        val bottomOpaque: Boolean
    )

    companion object {
        private const val ANGLE_UNITS = 16384f
        private const val MODE_SOLID = 0
        private const val MODE_REPEAT = 1
        private const val MODE_TRANSITION = 2

        private fun toAngleUnits(degrees: Float): Float = degrees / 360f * ANGLE_UNITS

        private fun wrap(value: Float, range: Float): Float {
            val remainder = value % range
            return if (remainder < 0f) remainder + range else remainder
        }

        fun unpack(color: Int, out: Vector3f): Vector3f = out.set(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f
        )
    }
}
