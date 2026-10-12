package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER
import org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT
import org.lwjgl.opengl.GL32.*
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT
import org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT
import org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT
import org.lwjgl.opengl.GL44.glBufferStorage
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PersistentBuffer(
    val target: Int,
    requestedSectionSize: Long,
    val frames: Int = BUFFER_FRAMES
) {
    val sectionSize: Long = alignUp(requestedSectionSize, bindingAlignment(target))

    var bufferId: Int = 0
        private set

    private var persistentMap: ByteBuffer? = null

    private var fallbackBuffer: ByteBuffer? = null

    var isPersistent: Boolean = false
        private set

    private var currentFrame: Int = 0

    private var frameOpen: Boolean = false

    private val fences = arrayOfNulls<Long>(frames)

    val totalSize: Long
        get() = if (isPersistent) sectionSize * frames else sectionSize


    fun init() {
        bufferId = glGenBuffers()

        if (tryPersistentInit()) {
            isPersistent = true
        } else {
            initFallback()
            isPersistent = false
        }
    }

    private fun tryPersistentInit(): Boolean {
        if (!PERSISTENT_MAPPING_ENABLED) return false
        val caps: GLCapabilities = GL.getCapabilities()
        if (!caps.GL_ARB_buffer_storage && !caps.OpenGL44) {
            return false
        }

        val totalBytes = sectionSize * frames

        val storageFlags = GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT
        val mapFlags = GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT

        glBindBuffer(target, bufferId)

        GL11.glGetError()

        glBufferStorage(target, totalBytes, storageFlags)

        val storageError = GL11.glGetError()
        if (storageError != GL11.GL_NO_ERROR) {
            System.err.println(
                "[PersistentBuffer] glBufferStorage($totalBytes bytes) failed with GL error " +
                        "0x${storageError.toString(16)}; falling back to glBufferSubData"
            )
            glBindBuffer(target, 0)
            glDeleteBuffers(bufferId)
            bufferId = glGenBuffers()
            return false
        }

        val mapped = glMapBufferRange(target, 0, totalBytes, mapFlags)
        glBindBuffer(target, 0)

        if (mapped == null) {
            System.err.println("[PersistentBuffer] glMapBufferRange returned null; falling back to glBufferSubData")
            glDeleteBuffers(bufferId)
            bufferId = glGenBuffers()
            return false
        }

        persistentMap = mapped.order(ByteOrder.nativeOrder())
        return true
    }

    private fun initFallback() {
        glBindBuffer(target, bufferId)
        glBufferData(target, sectionSize, GL_DYNAMIC_DRAW)
        glBindBuffer(target, 0)

        fallbackBuffer = MemoryUtil.memAlloc(sectionSize.toInt()).order(ByteOrder.nativeOrder())
    }


    fun beginFrame(): ByteBuffer {
        frameOpen = true
        if (isPersistent) {
            waitForFence()
            val map = persistentMap!!
            val offset = currentFrame * sectionSize
            map.position(offset.toInt())
            map.limit((offset + sectionSize).toInt())
            val slice = map.slice().order(ByteOrder.nativeOrder())
            map.position(0)
            map.limit(map.capacity())
            return slice
        } else {
            val fb = fallbackBuffer!!
            fb.clear()
            return fb
        }
    }

    fun endWrite(bytesWritten: Long) {
        if (isPersistent) return
        val fb = fallbackBuffer!!
        if (bytesWritten > 0) {
            fb.position(0)
            fb.limit(bytesWritten.toInt())
            glBindBuffer(target, bufferId)
            glBufferSubData(target, 0, fb)
            glBindBuffer(target, 0)
        }
        fb.clear()
    }

    fun endFrame() {
        if (!frameOpen) return
        frameOpen = false
        if (!isPersistent) return
        placeFence()
        currentFrame = (currentFrame + 1) % frames
    }

    fun bindRange(binding: Int, size: Long) {
        if (size <= 0L) return
        glBindBufferRange(target, binding, bufferId, readOffset(), size)
    }

    fun bind() {
        glBindBuffer(target, bufferId)
    }

    fun unbind() {
        glBindBuffer(target, 0)
    }

    fun readOffset(): Long = if (isPersistent) currentFrame * sectionSize else 0L


    private fun waitForFence() {
        val fence = fences[currentFrame] ?: return
        val result = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L)
        if (result == GL_WAIT_FAILED) {
            System.err.println("[PersistentBuffer] glClientWaitSync returned GL_WAIT_FAILED")
        }
        glDeleteSync(fence)
        fences[currentFrame] = null
    }

    private fun placeFence() {
        fences[currentFrame]?.let { glDeleteSync(it) }
        fences[currentFrame] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
    }


    fun cleanup() {
        for (i in fences.indices) {
            fences[i]?.let { glDeleteSync(it) }
            fences[i] = null
        }

        if (isPersistent && persistentMap != null) {
            glBindBuffer(target, bufferId)
            glUnmapBuffer(target)
            glBindBuffer(target, 0)
            persistentMap = null
        }

        fallbackBuffer?.let { MemoryUtil.memFree(it) }
        fallbackBuffer = null

        if (bufferId != 0) {
            glDeleteBuffers(bufferId)
            bufferId = 0
        }

        isPersistent = false
        currentFrame = 0
        frameOpen = false
    }

    companion object {
        const val BUFFER_FRAMES = 3

        private val PERSISTENT_MAPPING_ENABLED: Boolean =
            System.getProperty("darkan.persistentMapping", "true").toBoolean()

        private fun alignUp(value: Long, alignment: Long): Long =
            if (alignment <= 1L) value else ((value + alignment - 1L) / alignment) * alignment

        private fun bindingAlignment(target: Int): Long = when (target) {
            GL_SHADER_STORAGE_BUFFER -> GL11.glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT).toLong().coerceAtLeast(4L)
            GL_UNIFORM_BUFFER -> GL11.glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT).toLong().coerceAtLeast(4L)
            else -> 4L
        }
    }
}
