package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL30.glBindBufferBase
import org.lwjgl.opengl.GL31.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

class UniformBuffers {
    private var cameraUBO: Int = 0
    private var environmentUBO: Int = 0

    private val cameraBlockSize = 256

    private val environmentBlockSize = EnvironmentSettings.STD140_FLOATS * 4

    private val cameraBinding = 0
    private val environmentBinding = 1

    private val cachedView = Matrix4f()
    private val cachedProjection = Matrix4f()
    private val cachedCameraPos = Vector3f()
    private var cameraDirty = true

    private val environmentScratch = FloatArray(EnvironmentSettings.STD140_FLOATS)
    private val environmentUploaded = FloatArray(EnvironmentSettings.STD140_FLOATS)
    private val environmentDirection = Vector3f()
    private var environmentDirty = true

    fun init() {
        cameraUBO = glGenBuffers()
        glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO)
        glBufferData(GL_UNIFORM_BUFFER, cameraBlockSize.toLong(), GL_DYNAMIC_DRAW)
        glBindBufferBase(GL_UNIFORM_BUFFER, cameraBinding, cameraUBO)

        environmentUBO = glGenBuffers()
        glBindBuffer(GL_UNIFORM_BUFFER, environmentUBO)
        glBufferData(GL_UNIFORM_BUFFER, environmentBlockSize.toLong(), GL_DYNAMIC_DRAW)
        glBindBufferBase(GL_UNIFORM_BUFFER, environmentBinding, environmentUBO)

        glBindBuffer(GL_UNIFORM_BUFFER, 0)

        cameraDirty = true
        environmentDirty = true
        updateEnvironment(EnvironmentSettings())
        updateCamera(
            Matrix4f().identity(),
            Matrix4f().identity(),
            Vector3f(0f, 0f, 0f)
        )
    }

    fun updateCamera(view: Matrix4f, projection: Matrix4f, cameraPos: Vector3f) {
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

            val viewFb = stack.mallocFloat(16)
            view.get(viewFb)
            for (i in 0 until 16) {
                buffer.putFloat(viewFb.get(i))
            }

            val projFb = stack.mallocFloat(16)
            projection.get(projFb)
            for (i in 0 until 16) {
                buffer.putFloat(projFb.get(i))
            }

            val viewProj = Matrix4f(projection).mul(view)
            val vpFb = stack.mallocFloat(16)
            viewProj.get(vpFb)
            for (i in 0 until 16) {
                buffer.putFloat(vpFb.get(i))
            }

            buffer.putFloat(cameraPos.x)
            buffer.putFloat(cameraPos.y)
            buffer.putFloat(cameraPos.z)
            buffer.putFloat(1.0f)

            buffer.flip()

            glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO)
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer)
            glBindBuffer(GL_UNIFORM_BUFFER, 0)
        }
    }

    fun updateEnvironment(settings: EnvironmentSettings) {
        settings.writeStd140(environmentScratch, environmentDirection)

        if (!environmentDirty && environmentScratch.contentEquals(environmentUploaded)) {
            return
        }
        System.arraycopy(environmentScratch, 0, environmentUploaded, 0, environmentScratch.size)
        environmentDirty = false

        MemoryStack.stackPush().use { stack ->
            val buffer = stack.malloc(environmentBlockSize)
            for (value in environmentScratch) {
                buffer.putFloat(value)
            }
            buffer.flip()

            glBindBuffer(GL_UNIFORM_BUFFER, environmentUBO)
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer)
            glBindBuffer(GL_UNIFORM_BUFFER, 0)
        }
    }

    fun bindCameraBlock(shader: ShaderProgram, blockName: String = "CameraBlock") {
        val blockIndex = glGetUniformBlockIndex(shader.id, blockName)
        if (blockIndex != GL_INVALID_INDEX) {
            glUniformBlockBinding(shader.id, blockIndex, cameraBinding)
        } else {
            System.err.println("UBO WARNING: $blockName not found in shader ${shader.id}")
        }
    }

    fun bindEnvironmentBlock(shader: ShaderProgram, blockName: String = "EnvironmentBlock") {
        val blockIndex = glGetUniformBlockIndex(shader.id, blockName)
        if (blockIndex != GL_INVALID_INDEX) {
            glUniformBlockBinding(shader.id, blockIndex, environmentBinding)
        } else {
            System.err.println("UBO WARNING: $blockName not found in shader ${shader.id}")
        }
    }

    fun bindToShader(
        shader: ShaderProgram,
        cameraBlockName: String = "CameraBlock",
        environmentBlockName: String = "EnvironmentBlock"
    ) {
        bindCameraBlock(shader, cameraBlockName)
        bindEnvironmentBlock(shader, environmentBlockName)
    }

    fun invalidate() {
        cameraDirty = true
        environmentDirty = true
    }

    fun cleanup() {
        if (cameraUBO != 0) {
            glDeleteBuffers(cameraUBO)
            cameraUBO = 0
        }
        if (environmentUBO != 0) {
            glDeleteBuffers(environmentUBO)
            environmentUBO = 0
        }
    }

    companion object {
        fun getCameraBlockGLSL(): String = """
            layout(std140) uniform CameraBlock {
                mat4 view;
                mat4 projection;
                mat4 viewProjection;
                vec3 cameraPos;
            };
        """.trimIndent()

        fun getEnvironmentBlockGLSL(): String = """
            layout(std140) uniform EnvironmentBlock {
                vec4 sunDirection;
                vec4 sunColour;
                vec4 sunTerms;
                vec4 fogColour;
                vec4 fogRange;
            };
        """.trimIndent()
    }
}
