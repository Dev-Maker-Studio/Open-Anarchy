package org.darkan.tools.engine.input

import org.lwjgl.glfw.GLFW.*
import kotlin.math.abs

class Mouse {
    private val buttonStates = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val buttonPressed = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val buttonReleased = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)

    private val pressX = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val pressY = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val pressTime = LongArray(GLFW_MOUSE_BUTTON_LAST + 1)

    private val clickCounts = IntArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val lastClickTime = LongArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val lastClickX = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val lastClickY = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)

    var mods: Int = 0
        private set

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

    var moved: Boolean = false
        private set

    private var lastX: Double = 0.0
    private var lastY: Double = 0.0
    private var firstMove: Boolean = true

    fun onMouseButton(button: Int, action: Int, mods: Int) {
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) return

        this.mods = mods
        when (action) {
            GLFW_PRESS -> {
                buttonStates[button] = true
                buttonPressed[button] = true
                pressX[button] = x
                pressY[button] = y
                val now = System.nanoTime()
                pressTime[button] = now
                val quick = (now - lastClickTime[button]) < DOUBLE_CLICK_NANOS
                val still = abs(x - lastClickX[button]) <= DOUBLE_CLICK_SLOP &&
                    abs(y - lastClickY[button]) <= DOUBLE_CLICK_SLOP
                clickCounts[button] = if (quick && still) clickCounts[button] + 1 else 1
                lastClickTime[button] = now
                lastClickX[button] = x
                lastClickY[button] = y
            }
            GLFW_RELEASE -> {
                buttonStates[button] = false
                buttonReleased[button] = true
            }
        }
    }

    fun resetDeltaReference() {
        firstMove = true
    }

    fun onCursorPos(xpos: Double, ypos: Double) {
        if (firstMove) {
            lastX = xpos
            lastY = ypos
            firstMove = false
            deltaX = 0.0
            deltaY = 0.0
            x = xpos
            y = ypos
            moved = true
            return
        }

        deltaX = xpos - lastX
        deltaY = ypos - lastY
        lastX = xpos
        lastY = ypos
        x = xpos
        y = ypos
        moved = true
    }

    fun onScroll(xoffset: Double, yoffset: Double) {
        scrollX += xoffset
        scrollY += yoffset
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

    fun clickCount(button: Int): Int {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) clickCounts[button] else 0
    }

    fun isDoubleClick(button: Int): Boolean = isButtonPressed(button) && clickCount(button) == 2

    fun pressOriginX(button: Int): Double =
        if (button in 0..GLFW_MOUSE_BUTTON_LAST) pressX[button] else 0.0

    fun pressOriginY(button: Int): Double =
        if (button in 0..GLFW_MOUSE_BUTTON_LAST) pressY[button] else 0.0

    fun heldMillis(button: Int): Long {
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST || !buttonStates[button]) return 0
        return (System.nanoTime() - pressTime[button]) / 1_000_000
    }

    fun update() {
        buttonPressed.fill(false)
        buttonReleased.fill(false)
        deltaX = 0.0
        deltaY = 0.0
        scrollX = 0.0
        scrollY = 0.0
        moved = false
    }

    companion object {
        private const val DOUBLE_CLICK_NANOS = 400_000_000L
        private const val DOUBLE_CLICK_SLOP = 4.0
    }
}
