package org.darkan.tools.engine.input

import org.lwjgl.glfw.GLFW.*

class Keyboard {
    private val keyStates = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyPressed = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyReleased = BooleanArray(GLFW_KEY_LAST + 1)

    fun onKeyEvent(key: Int, scancode: Int, action: Int, mods: Int) {
        if (key < 0 || key > GLFW_KEY_LAST) return

        when (action) {
            GLFW_PRESS -> {
                keyStates[key] = true
                keyPressed[key] = true
            }
            GLFW_RELEASE -> {
                keyStates[key] = false
                keyReleased[key] = true
            }
        }
    }

    fun isKeyDown(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyStates[key] else false
    }

    fun isKeyPressed(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyPressed[key] else false
    }

    fun isKeyReleased(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyReleased[key] else false
    }

    fun update() {
        keyPressed.fill(false)
        keyReleased.fill(false)
    }
}
