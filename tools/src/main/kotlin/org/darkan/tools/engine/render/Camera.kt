package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

class Camera(
    val position: Vector3f = Vector3f(0f, 0f, 3f),
    var yaw: Float = -90f,
    var pitch: Float = 0f
) {
    val front: Vector3f = Vector3f(0f, 0f, -1f)
    val up: Vector3f = Vector3f(0f, 1f, 0f)
    val right: Vector3f = Vector3f(1f, 0f, 0f)

    private val worldUp = Vector3f(0f, 1f, 0f)

    var fov: Float = 45f
    var nearPlane: Float = 0.1f
    var farPlane: Float = 1000f
    var moveSpeed: Float = 5.0f
    var mouseSensitivity: Float = 0.1f

    var orthographic: Boolean = false
    var orthoSize: Float = 10f

    init {
        updateVectors()
    }

    fun getViewMatrix(): Matrix4f {
        val target = Vector3f()
        position.add(front, target)
        return Matrix4f().lookAt(position, target, up)
    }

    fun getProjectionMatrix(aspectRatio: Float): Matrix4f {
        return if (orthographic) {
            val halfHeight = orthoSize / 2
            val halfWidth = halfHeight * aspectRatio
            Matrix4f().ortho(-halfWidth, halfWidth, -halfHeight, halfHeight, nearPlane, farPlane)
        } else {
            Matrix4f().perspective(Math.toRadians(fov.toDouble()).toFloat(), aspectRatio, nearPlane, farPlane)
        }
    }

    fun moveForward(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(front).mul(velocity)
        position.add(movement)
    }

    fun moveBackward(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(front).mul(velocity)
        position.sub(movement)
    }

    fun moveLeft(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(right).mul(velocity)
        position.sub(movement)
    }

    fun moveRight(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        val movement = Vector3f(right).mul(velocity)
        position.add(movement)
    }

    fun moveUp(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        position.y += velocity
    }

    fun moveDown(deltaTime: Float) {
        val velocity = moveSpeed * deltaTime
        position.y -= velocity
    }

    fun rotate(xOffset: Float, yOffset: Float, constrainPitch: Boolean = true) {
        yaw += xOffset * mouseSensitivity
        pitch -= yOffset * mouseSensitivity

        if (constrainPitch) {
            pitch = pitch.coerceIn(-89f, 89f)
        }

        updateVectors()
    }

    fun zoom(yOffset: Float) {
        fov -= yOffset
        fov = fov.coerceIn(1f, 120f)
    }

    private fun updateVectors() {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())

        front.x = (cos(yawRad) * cos(pitchRad)).toFloat()
        front.y = sin(pitchRad).toFloat()
        front.z = (sin(yawRad) * cos(pitchRad)).toFloat()
        front.normalize()

        front.cross(worldUp, right)
        right.normalize()

        right.cross(front, up)
        up.normalize()
    }

    fun lookAt(target: Vector3f) {
        val direction = Vector3f()
        target.sub(position, direction)
        direction.normalize()

        pitch = Math.toDegrees(Math.asin(direction.y.toDouble())).toFloat()
        yaw = Math.toDegrees(Math.atan2(direction.z.toDouble(), direction.x.toDouble())).toFloat()

        updateVectors()
    }

    fun setPosition(x: Float, y: Float, z: Float) {
        position.set(x, y, z)
    }
}
