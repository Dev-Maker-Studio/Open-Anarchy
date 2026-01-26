package org.darkan.tools.engine.input

import org.lwjgl.glfw.GLFW.*

class Mouse {
    private val buttonStates = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val buttonPressed = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val buttonReleased = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)

    var x: Double = 0.0
        private set
    var y: Double = 0.0
        private set
    var deltaX: Double = 0.0
        private set
    var deltaY: Double = 0.0
        private set
    var scrollX: Double = 0.0
        private set
    var scrollY: Double = 0.0
        private set

    private var lastX: Double = 0.0
    private var lastY: Double = 0.0
    private var firstMove: Boolean = true

    fun onMouseButton(button: Int, action: Int, mods: Int) {
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) return

        when (action) {
            GLFW_PRESS -> {
                buttonStates[button] = true
                buttonPressed[button] = true
            }
            GLFW_RELEASE -> {
                buttonStates[button] = false
                buttonReleased[button] = true
            }
        }
    }

    fun onCursorPos(xpos: Double, ypos: Double) {
        if (firstMove) {
            lastX = xpos
            lastY = ypos
            firstMove = false
        }

        deltaX = xpos - lastX
        deltaY = ypos - lastY
        lastX = xpos
        lastY = ypos
        x = xpos
        y = ypos
    }

    fun onScroll(xoffset: Double, yoffset: Double) {
        scrollX = xoffset
        scrollY = yoffset
    }

    fun isButtonDown(button: Int): Boolean {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) buttonStates[button] else false
    }

    fun isButtonPressed(button: Int): Boolean {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) buttonPressed[button] else false
    }

    fun isButtonReleased(button: Int): Boolean {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) buttonReleased[button] else false
    }

    fun update() {
        buttonPressed.fill(false)
        buttonReleased.fill(false)
        deltaX = 0.0
        deltaY = 0.0
        scrollX = 0.0
        scrollY = 0.0
    }
}
