package org.darkan.tools.engine

import org.darkan.tools.engine.input.InputSystem
import org.darkan.tools.engine.render.RenderMetrics
import org.darkan.tools.engine.render.RenderSystem
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.scene.Scene
import org.darkan.tools.engine.window.Window
import org.darkan.tools.engine.window.WindowConfig
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.GL_BACK
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glReadBuffer
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

abstract class Engine(config: WindowConfig = WindowConfig()) {
    private companion object {
        const val LOADING_DRAIN_SLICE_NANOS = 25_000_000L

        const val LOADING_RENDER_COST_MULTIPLE = 4
        const val LOADING_RENDER_MIN_INTERVAL_NANOS = 50_000_000L
        const val LOADING_RENDER_MAX_INTERVAL_NANOS = 150_000_000L

        const val LOADING_IDLE_ITERATION_NANOS = 1_000_000L
        const val LOADING_IDLE_SLEEP_MILLIS = 1L
    }

    val window = Window(config)
    val input = InputSystem(window)
    val renderSystem = RenderSystem(window)

    protected lateinit var colorShader: ShaderProgram
    protected lateinit var simpleShader: ShaderProgram
    protected lateinit var texturedShader: ShaderProgram

    var scene = Scene()
        protected set

    protected var isInitialLoading = false

    private var lastFrameTime: Long = 0
    var deltaTime: Float = 0f
        private set
    var fps: Int = 0
        private set

    private var frameCount = 0
    private var fpsTimer = 0f

    private var loadingRenderCostNanos = 0L
    private var lastLoadingRenderNanos = 0L

    fun run() {
        try {
            init()
            loop()
        } finally {
            cleanup()
        }
    }

    private fun init() {
        window.init()
        input.init()

        val colorVertSrc = loadShaderSource("/shaders/color.vert")
        val colorFragSrc = loadShaderSource("/shaders/color.frag")
        colorShader = ShaderProgram.createWithUniforms(
            colorVertSrc, colorFragSrc,
            "model", "view", "projection",
            "ambientLight", "lightDirection", "lightColor"
        )

        val simpleVertSrc = loadShaderSource("/shaders/simple.vert")
        val simpleFragSrc = loadShaderSource("/shaders/simple.frag")
        simpleShader = ShaderProgram.createWithUniforms(
            simpleVertSrc, simpleFragSrc,
            "model", "view", "projection"
        )

        val texturedVertSrc = loadShaderSource("/shaders/textured.vert")
        val texturedFragSrc = loadShaderSource("/shaders/textured.frag")
        texturedShader = ShaderProgram.createWithUniforms(
            texturedVertSrc, texturedFragSrc,
            "model", "view", "projection",
            "ambientLight", "lightDirection", "lightColor",
            "textureSampler", "useTexture"
        )

        lastFrameTime = System.nanoTime()

        onInit()
    }

    private var loopFrameCount = 0

    private val capturePath: String? = System.getProperty("darkan.capture")
    private val captureAfterSeconds: Float = System.getProperty("darkan.captureAfter", "12").toFloat()
    private var captureElapsed = 0f
    private var captureDone = false

    protected fun captureBackBuffer(path: String) {
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        val buffer = MemoryUtil.memAlloc(w * h * 4)
        try {
            glReadBuffer(GL_BACK)
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) {
                val srcRow = (h - 1 - y) * w * 4
                for (x in 0 until w) {
                    val i = srcRow + x * 4
                    val r = buffer.get(i).toInt() and 0xFF
                    val g = buffer.get(i + 1).toInt() and 0xFF
                    val b = buffer.get(i + 2).toInt() and 0xFF
                    image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            val file = File(path)
            file.parentFile?.mkdirs()
            ImageIO.write(image, "png", file)
            println("[ENGINE] Captured back buffer to $path (${w}x$h)")
            System.out.flush()
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun loop() {
        while (!window.shouldClose()) {
            val logFrame = loopFrameCount < 5

            val currentTime = System.nanoTime()
            deltaTime = (currentTime - lastFrameTime) / 1_000_000_000f
            lastFrameTime = currentTime

            fpsTimer += deltaTime
            if (fpsTimer >= 1f) {
                fps = frameCount
                frameCount = 0
                fpsTimer -= 1f
            }

            if (window.resized) {
                renderSystem.updateViewport()
                window.resized = false
            }

            val gpuBudgetNanos = if (isInitialLoading) {
                LOADING_DRAIN_SLICE_NANOS
            } else {
                when {
                    deltaTime < 0.010f -> 6_000_000L
                    deltaTime < 0.014f -> 4_000_000L
                    else -> 2_000_000L
                }
            }
            onDrainGpuUploads(gpuBudgetNanos)

            handleInput()
            onUpdate(deltaTime)

            val renderNow = !isInitialLoading ||
                (currentTime - lastLoadingRenderNanos) >= loadingRenderIntervalNanos()

            if (renderNow) {
                frameCount++
                renderSystem.clear()
                if (isInitialLoading) {
                    val renderStart = System.nanoTime()
                    onRenderLoadingScreen()
                    loadingRenderCostNanos = System.nanoTime() - renderStart
                    lastLoadingRenderNanos = renderStart
                } else {
                    onRender()
                }
            }

            if (logFrame && renderNow) {
                println("[ENGINE] Frame $loopFrameCount: onRender() returned, calling window.update()")
                System.out.flush()
            }

            if (capturePath != null && !captureDone) {
                captureElapsed += deltaTime
                if (renderNow && captureElapsed >= captureAfterSeconds) {
                    onCapture()
                    captureBackBuffer(capturePath)
                    captureDone = true
                    window.requestClose()
                }
            }

            input.update()
            if (renderNow) {
                val presentStart = System.nanoTime()
                window.update()
                RenderMetrics.presentTimeMs = (System.nanoTime() - presentStart) / 1_000_000.0
            } else {
                glfwPollEvents()
            }

            if (isInitialLoading && System.nanoTime() - currentTime < LOADING_IDLE_ITERATION_NANOS) {
                Thread.sleep(LOADING_IDLE_SLEEP_MILLIS)
            }

            if (logFrame && renderNow) {
                println("[ENGINE] Frame $loopFrameCount: window.update() returned, frame complete")
                System.out.flush()
            }
            loopFrameCount++
        }
    }

    private fun loadingRenderIntervalNanos(): Long =
        (loadingRenderCostNanos * LOADING_RENDER_COST_MULTIPLE)
            .coerceIn(LOADING_RENDER_MIN_INTERVAL_NANOS, LOADING_RENDER_MAX_INTERVAL_NANOS)

    private fun handleInput() {
        if (input.keyboard.isKeyPressed(GLFW_KEY_ESCAPE)) {
            if (input.mouseCaptured) {
                input.releaseMouse()
            }
        }

        if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_RIGHT)) {
            input.captureMouse()
        }

        onInput()
    }

    protected open fun onInit() {}
    protected open fun onUpdate(deltaTime: Float) {}
    protected open fun onRender() {}
    protected open fun onRenderLoadingScreen() {}
    protected open fun onDrainGpuUploads(budgetNanos: Long) {}
    protected open fun onInput() {}
    protected open fun onCleanup() {}

    protected open fun onCapture() {}

    private fun cleanup() {
        onCleanup()
        scene.cleanup()
        colorShader.cleanup()
        simpleShader.cleanup()
        texturedShader.cleanup()
        renderSystem.cleanup()
        window.cleanup()
    }

    protected fun loadShaderSource(resourcePath: String): String {
        return this::class.java.getResourceAsStream(resourcePath)?.bufferedReader()?.readText()
            ?: throw RuntimeException("Could not load shader: $resourcePath")
    }

    protected fun getAspectRatio(): Float {
        return if (window.height > 0) window.width.toFloat() / window.height.toFloat() else 1f
    }
}
