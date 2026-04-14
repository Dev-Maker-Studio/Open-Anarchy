package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL30.glBindBufferBase
import org.lwjgl.opengl.GL31.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * Manages Uniform Buffer Objects (UBOs) for efficient shader data sharing.
 *
 * Uses std140 layout for cross-shader compatibility:
 * - CameraBlock (binding 0): view, projection, viewProjection matrices + camera position
 * - LightingBlock (binding 1): ambient, direction, color
 *
 * Using UBOs reduces per-draw-call uniform updates significantly when many
 * objects share the same camera/lighting data.
 */
class UniformBuffers {
    // UBO IDs
    private var cameraUBO: Int = 0
    private var lightingUBO: Int = 0

    // Buffer sizes (std140 aligned)
    // CameraBlock: 3 mat4 (192 bytes) + vec3 padded to vec4 (16 bytes) = 208 bytes, pad to 256 for alignment
    private val cameraBlockSize = 256

    // LightingBlock: 3 vec3 padded to vec4 each (48 bytes), pad to 64 for alignment
    private val lightingBlockSize = 64

    // Binding points
    private val cameraBinding = 0
    private val lightingBinding = 1

    // Cached values to avoid redundant updates
    private val cachedView = Matrix4f()
    private val cachedProjection = Matrix4f()
    private val cachedCameraPos = Vector3f()
    private val cachedAmbient = Vector3f()
    private val cachedLightDir = Vector3f()
    private val cachedLightColor = Vector3f()
    private var cameraDirty = true
    private var lightingDirty = true

    /**
     * Initialize the UBOs.
     */
    fun init() {
        // Create Camera UBO
        cameraUBO = glGenBuffers()
        glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO)
        glBufferData(GL_UNIFORM_BUFFER, cameraBlockSize.toLong(), GL_DYNAMIC_DRAW)
        glBindBufferBase(GL_UNIFORM_BUFFER, cameraBinding, cameraUBO)

        // Create Lighting UBO
        lightingUBO = glGenBuffers()
        glBindBuffer(GL_UNIFORM_BUFFER, lightingUBO)
        glBufferData(GL_UNIFORM_BUFFER, lightingBlockSize.toLong(), GL_DYNAMIC_DRAW)
        glBindBufferBase(GL_UNIFORM_BUFFER, lightingBinding, lightingUBO)

        glBindBuffer(GL_UNIFORM_BUFFER, 0)

        // Initialize with default values to avoid black rendering on first frame
        cameraDirty = true
        lightingDirty = true
        updateLighting(
            Vector3f(0.5f, 0.5f, 0.5f),           // ambient
            Vector3f(-0.5f, -1f, -0.5f).normalize(), // direction
            Vector3f(0.7f, 0.7f, 0.7f)            // color
        )
        updateCamera(
            Matrix4f().identity(),
            Matrix4f().identity(),
            Vector3f(0f, 0f, 0f)
        )
    }

    /**
     * Update camera UBO data.
     */
    fun updateCamera(view: Matrix4f, projection: Matrix4f, cameraPos: Vector3f) {
        // Check if data actually changed
        if (!cameraDirty &&
            cachedView.equals(view) &&
            cachedProjection.equals(projection) &&
            cachedCameraPos.equals(cameraPos)) {
            return
        }

        cachedView.set(view)
        cachedProjection.set(projection)
        cachedCameraPos.set(cameraPos)
        cameraDirty = false

        MemoryStack.stackPush().use { stack ->
            val buffer = stack.malloc(cameraBlockSize)

            // std140 layout:
            // mat4 view (64 bytes)
            val viewFb = stack.mallocFloat(16)
            view.get(viewFb)
            for (i in 0 until 16) {
                buffer.putFloat(viewFb.get(i))
            }

            // mat4 projection (64 bytes)
            val projFb = stack.mallocFloat(16)
            projection.get(projFb)
            for (i in 0 until 16) {
                buffer.putFloat(projFb.get(i))
            }

            // mat4 viewProjection (64 bytes)
            val viewProj = Matrix4f(projection).mul(view)
            val vpFb = stack.mallocFloat(16)
            viewProj.get(vpFb)
            for (i in 0 until 16) {
                buffer.putFloat(vpFb.get(i))
            }

            // vec3 cameraPos padded to vec4 (16 bytes)
            buffer.putFloat(cameraPos.x)
            buffer.putFloat(cameraPos.y)
            buffer.putFloat(cameraPos.z)
            buffer.putFloat(1.0f) // padding

            buffer.flip()

            glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO)
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer)
            glBindBuffer(GL_UNIFORM_BUFFER, 0)
        }
    }

    /**
     * Update lighting UBO data.
     */
    fun updateLighting(ambient: Vector3f, direction: Vector3f, color: Vector3f) {
        // Check if data actually changed
        if (!lightingDirty &&
            cachedAmbient.equals(ambient) &&
            cachedLightDir.equals(direction) &&
            cachedLightColor.equals(color)) {
            return
        }

        cachedAmbient.set(ambient)
        cachedLightDir.set(direction)
        cachedLightColor.set(color)
        lightingDirty = false

        MemoryStack.stackPush().use { stack ->
            val buffer = stack.malloc(lightingBlockSize)

            // std140 layout: each vec3 is padded to vec4 (16 bytes)

            // vec3 ambientLight
            buffer.putFloat(ambient.x)
            buffer.putFloat(ambient.y)
            buffer.putFloat(ambient.z)
            buffer.putFloat(0.0f) // padding

            // vec3 lightDirection
            buffer.putFloat(direction.x)
            buffer.putFloat(direction.y)
            buffer.putFloat(direction.z)
            buffer.putFloat(0.0f) // padding

            // vec3 lightColor
            buffer.putFloat(color.x)
            buffer.putFloat(color.y)
            buffer.putFloat(color.z)
            buffer.putFloat(0.0f) // padding

            buffer.flip()

            glBindBuffer(GL_UNIFORM_BUFFER, lightingUBO)
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer)
            glBindBuffer(GL_UNIFORM_BUFFER, 0)
        }
    }

    /**
     * Bind a shader's uniform block to the camera UBO.
     */
    fun bindCameraBlock(shader: ShaderProgram, blockName: String = "CameraBlock") {
        val blockIndex = glGetUniformBlockIndex(shader.id, blockName)
        if (blockIndex != GL_INVALID_INDEX) {
            glUniformBlockBinding(shader.id, blockIndex, cameraBinding)
        } else {
            System.err.println("UBO WARNING: $blockName not found in shader ${shader.id}")
        }
    }

    /**
     * Bind a shader's uniform block to the lighting UBO.
     */
    fun bindLightingBlock(shader: ShaderProgram, blockName: String = "LightingBlock") {
        val blockIndex = glGetUniformBlockIndex(shader.id, blockName)
        if (blockIndex != GL_INVALID_INDEX) {
            glUniformBlockBinding(shader.id, blockIndex, lightingBinding)
        } else {
            System.err.println("UBO WARNING: $blockName not found in shader ${shader.id}")
        }
    }

    /**
     * Bind both uniform blocks to a shader.
     */
    fun bindToShader(
        shader: ShaderProgram,
        cameraBlockName: String = "CameraBlock",
        lightingBlockName: String = "LightingBlock"
    ) {
        bindCameraBlock(shader, cameraBlockName)
        bindLightingBlock(shader, lightingBlockName)
    }

    /**
     * Force update on next call (use when camera/lighting changed externally).
     */
    fun invalidate() {
        cameraDirty = true
        lightingDirty = true
    }

    /**
     * Clean up GPU resources.
     */
    fun cleanup() {
        if (cameraUBO != 0) {
            glDeleteBuffers(cameraUBO)
            cameraUBO = 0
        }
        if (lightingUBO != 0) {
            glDeleteBuffers(lightingUBO)
            lightingUBO = 0
        }
    }

    companion object {
        /**
         * Get the GLSL code for the camera uniform block.
         */
        fun getCameraBlockGLSL(): String = """
            layout(std140) uniform CameraBlock {
                mat4 view;
                mat4 projection;
                mat4 viewProjection;
                vec3 cameraPos;
            };
        """.trimIndent()

        /**
         * Get the GLSL code for the lighting uniform block.
         */
        fun getLightingBlockGLSL(): String = """
            layout(std140) uniform LightingBlock {
                vec3 ambientLight;
                vec3 lightDirection;
                vec3 lightColor;
            };
        """.trimIndent()
    }
}
