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

    /**
     * Enable alpha-to-coverage for smoother alpha edges with MSAA.
     * Converts alpha values to MSAA coverage masks for anti-aliased transparency.
     */
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

    fun disableCullFace() {
        glDisable(GL_CULL_FACE)
    }

    fun setWireframe(enabled: Boolean) {
        wireframeMode = enabled
        glPolygonMode(GL_FRONT_AND_BACK, if (enabled) GL_LINE else GL_FILL)
    }

    fun toggleWireframe() {
        setWireframe(!wireframeMode)
    }

    fun cleanup() {
        // Cleanup any render system resources
    }
}
