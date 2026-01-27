package org.darkan.tools.engine

import org.darkan.tools.engine.input.InputSystem
import org.darkan.tools.engine.render.RenderSystem
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.scene.Scene
import org.darkan.tools.engine.window.Window
import org.darkan.tools.engine.window.WindowConfig
import org.lwjgl.glfw.GLFW.*

abstract class Engine(config: WindowConfig = WindowConfig()) {
    val window = Window(config)
    val input = InputSystem(window)
    val renderSystem = RenderSystem(window)

    protected lateinit var colorShader: ShaderProgram
    protected lateinit var simpleShader: ShaderProgram
    protected lateinit var texturedShader: ShaderProgram

    var scene = Scene()
        protected set

    private var lastFrameTime: Long = 0
    var deltaTime: Float = 0f
        private set
    var fps: Int = 0
        private set

    private var frameCount = 0
    private var fpsTimer = 0f

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

    private fun loop() {
        while (!window.shouldClose()) {
            val currentTime = System.nanoTime()
            deltaTime = (currentTime - lastFrameTime) / 1_000_000_000f
            lastFrameTime = currentTime

            frameCount++
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

            handleInput()
            onUpdate(deltaTime)

            renderSystem.clear()
            onRender()

            input.update()
            window.update()
        }
    }

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
    protected open fun onInput() {}
    protected open fun onCleanup() {}

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
