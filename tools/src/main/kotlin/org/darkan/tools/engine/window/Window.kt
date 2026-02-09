package org.darkan.tools.engine.window

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL

class Window(private val config: WindowConfig = WindowConfig()) {
    var handle: Long = NULL
        private set
    var width: Int = config.width
        private set
    var height: Int = config.height
        private set
    var resized: Boolean = false

    private var keyCallback: ((Int, Int, Int, Int) -> Unit)? = null
    private var mouseButtonCallback: ((Int, Int, Int) -> Unit)? = null
    private var cursorPosCallback: ((Double, Double) -> Unit)? = null
    private var scrollCallback: ((Double, Double) -> Unit)? = null

    fun init() {
        GLFWErrorCallback.createPrint(System.err).set()

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, if (config.resizable) GLFW_TRUE else GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        if (config.samples > 0) {
            glfwWindowHint(GLFW_SAMPLES, config.samples)
        }

        handle = glfwCreateWindow(config.width, config.height, config.title, NULL, NULL)
        if (handle == NULL) {
            throw RuntimeException("Failed to create GLFW window")
        }

        glfwSetFramebufferSizeCallback(handle) { _, w, h ->
            width = w
            height = h
            resized = true
        }

        glfwSetKeyCallback(handle) { _, key, scancode, action, mods ->
            keyCallback?.invoke(key, scancode, action, mods)
        }

        glfwSetMouseButtonCallback(handle) { _, button, action, mods ->
            mouseButtonCallback?.invoke(button, action, mods)
        }

        glfwSetCursorPosCallback(handle) { _, xpos, ypos ->
            cursorPosCallback?.invoke(xpos, ypos)
        }

        glfwSetScrollCallback(handle) { _, xoffset, yoffset ->
            scrollCallback?.invoke(xoffset, yoffset)
        }

        MemoryStack.stackPush().use { stack ->
            val pWidth = stack.mallocInt(1)
            val pHeight = stack.mallocInt(1)
            glfwGetWindowSize(handle, pWidth, pHeight)
            val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor())
            if (vidmode != null) {
                glfwSetWindowPos(
                    handle,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
                )
            }
        }

        glfwMakeContextCurrent(handle)
        if (config.vsync) {
            glfwSwapInterval(1)
        }

        GL.createCapabilities()

        glEnable(GL_DEPTH_TEST)
        // Use GL_LEQUAL to reduce z-fighting between coplanar geometry.
        // GL_LEQUAL allows fragments at equal depth to pass, which is needed for
        // scenery objects that sit exactly on terrain surfaces (ground decorations,
        // walls on floors). With GL_LESS, these fragments fight because floating
        // point imprecision causes near-equal depths to alternate winning.
        glDepthFunc(GL_LEQUAL)
        // Culling disabled - RS models have inconsistent winding
        // glEnable(GL_CULL_FACE)
        // glCullFace(GL_BACK)
        if (config.samples > 0) {
            glEnable(GL_MULTISAMPLE)
        }

        glfwShowWindow(handle)
    }

    fun setKeyCallback(callback: (key: Int, scancode: Int, action: Int, mods: Int) -> Unit) {
        keyCallback = callback
    }

    fun setMouseButtonCallback(callback: (button: Int, action: Int, mods: Int) -> Unit) {
        mouseButtonCallback = callback
    }

    fun setCursorPosCallback(callback: (xpos: Double, ypos: Double) -> Unit) {
        cursorPosCallback = callback
    }

    fun setScrollCallback(callback: (xoffset: Double, yoffset: Double) -> Unit) {
        scrollCallback = callback
    }

    fun setCursorMode(mode: Int) {
        glfwSetInputMode(handle, GLFW_CURSOR, mode)
    }

    fun shouldClose(): Boolean = glfwWindowShouldClose(handle)

    fun update() {
        glfwSwapBuffers(handle)
        glfwPollEvents()
    }

    fun isKeyPressed(key: Int): Boolean = glfwGetKey(handle, key) == GLFW_PRESS

    fun isMouseButtonPressed(button: Int): Boolean = glfwGetMouseButton(handle, button) == GLFW_PRESS

    fun getCursorPos(): Pair<Double, Double> {
        val xPos = DoubleArray(1)
        val yPos = DoubleArray(1)
        glfwGetCursorPos(handle, xPos, yPos)
        return Pair(xPos[0], yPos[0])
    }

    fun cleanup() {
        glfwFreeCallbacks(handle)
        glfwDestroyWindow(handle)
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }
}
