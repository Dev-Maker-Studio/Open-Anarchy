package org.darkan.tools.engine.render

import org.darkan.tools.engine.window.Window
import org.joml.Vector4f
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_SAMPLE_ALPHA_TO_COVERAGE

class RenderSystem(private val window: Window) {
    var clearColor: Vector4f = Vector4f(0.1f, 0.1f, 0.1f, 1.0f)
    var wireframeMode: Boolean = false

    fun clear() {
        glClearColor(clearColor.x, clearColor.y, clearColor.z, clearColor.w)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        glViewport(x, y, width, height)
    }

    fun updateViewport() {
        setViewport(0, 0, window.width, window.height)
    }

    fun enableDepthTest() {
        glEnable(GL_DEPTH_TEST)
    }

    fun disableDepthTest() {
        glDisable(GL_DEPTH_TEST)
    }

    fun enableBlending() {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
    }

    fun disableBlending() {
        glDisable(GL_BLEND)
    }

    fun enableAlphaToCoverage() {
        glEnable(GL_SAMPLE_ALPHA_TO_COVERAGE)
    }

    fun disableAlphaToCoverage() {
        glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE)
    }

    fun enableDepthWrite() {
        glDepthMask(true)
    }

    fun disableDepthWrite() {
        glDepthMask(false)
    }

    fun enableCullFace() {
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
    }

    fun enableModelCullFace() {
        if (!modelCullEnabled) {
            glDisable(GL_CULL_FACE)
            return
        }
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(if (System.getProperty("darkan.cullwind") == "cw") GL_CW else GL_CCW)
    }

    private val modelCullEnabled = System.getProperty("darkan.modelCull", "true").toBoolean()

    fun resetFrontFace() {
        glFrontFace(GL_CCW)
    }

    fun disableCullFace() {
        glDisable(GL_CULL_FACE)
    }

    fun enablePolygonOffset(factor: Float = -1.0f, units: Float = -1.0f) {
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(factor, units)
    }

    fun disablePolygonOffset() {
        glDisable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(0f, 0f)
    }

    fun setWireframe(enabled: Boolean) {
        wireframeMode = enabled
        glPolygonMode(GL_FRONT_AND_BACK, if (enabled) GL_LINE else GL_FILL)
    }

    fun toggleWireframe() {
        setWireframe(!wireframeMode)
    }

    fun cleanup() {
    }
}
