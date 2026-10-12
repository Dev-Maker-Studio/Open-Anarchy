package org.darkan.tools.engine.input

import org.lwjgl.glfw.GLFW.*

data class KeyEvent(
    val key: Int,
    val scancode: Int,
    val mods: Int,
    val repeat: Boolean,
)

class Keyboard {
    private val keyStates = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyPressed = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyReleased = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyRepeated = BooleanArray(GLFW_KEY_LAST + 1)

    private val pressEvents = ArrayList<KeyEvent>()
    private val typedChars = ArrayList<Int>()

    var mods: Int = 0
        private set

    val presses: List<KeyEvent> get() = pressEvents

    val chars: List<Int> get() = typedChars

    fun onKeyEvent(key: Int, scancode: Int, action: Int, mods: Int) {
        if (key < 0 || key > GLFW_KEY_LAST) return

        this.mods = mods
        when (action) {
            GLFW_PRESS -> {
                keyStates[key] = true
                keyPressed[key] = true
                pressEvents.add(KeyEvent(key, scancode, mods, repeat = false))
            }
            GLFW_RELEASE -> {
                keyStates[key] = false
                keyReleased[key] = true
            }
            GLFW_REPEAT -> {
                keyRepeated[key] = true
                pressEvents.add(KeyEvent(key, scancode, mods, repeat = true))
            }
        }
    }

    fun onChar(codepoint: Int) {
        typedChars.add(codepoint)
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

    fun isKeyRepeated(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyRepeated[key] else false
    }

    fun isKeyTyped(key: Int): Boolean = isKeyPressed(key) || isKeyRepeated(key)

    val shift: Boolean get() = isKeyDown(GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW_KEY_RIGHT_SHIFT)

    val control: Boolean get() = isKeyDown(GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW_KEY_RIGHT_CONTROL)

    val alt: Boolean get() = isKeyDown(GLFW_KEY_LEFT_ALT) || isKeyDown(GLFW_KEY_RIGHT_ALT)

    fun update() {
        keyPressed.fill(false)
        keyReleased.fill(false)
        keyRepeated.fill(false)
        pressEvents.clear()
        typedChars.clear()
    }
}
