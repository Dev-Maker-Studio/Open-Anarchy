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

    /**
     * Enable polygon offset to prevent z-fighting between coplanar geometry.
     * Applies a depth bias so that subsequently drawn geometry is nudged slightly
     * closer to the camera, preventing it from fighting with previously drawn
     * geometry at the same depth.
     *
     * Typical usage: enable before rendering objects that sit on terrain (ground
     * decorations, walls on floors, decals), disable after.
     *
     * @param factor Scales the maximum depth slope of the polygon (use -1.0f)
     * @param units Adds a constant depth offset (use -1.0f)
     */
    fun enablePolygonOffset(factor: Float = -1.0f, units: Float = -1.0f) {
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(factor, units)
    }

    /**
     * Disable polygon offset and reset bias to zero.
     */
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
        // Cleanup any render system resources
    }
}
