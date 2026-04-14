package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL32.*
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT
import org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT
import org.lwjgl.opengl.GL44.glBufferStorage
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * A triple-buffered persistent mapped buffer for streaming per-frame data to the GPU
 * without driver-side allocation churn.
 *
 * Uses `glBufferStorage` + `glMapBufferRange` with `GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT`
 * to map the buffer once at creation time. The buffer is divided into [frames] sections;
 * each frame writes to a different section and a GL fence sync ensures the GPU has finished
 * reading a section before the CPU overwrites it.
 *
 * If persistent mapping is unavailable (no GL 4.4 / ARB_buffer_storage), the class falls back
 * to a traditional CPU-side ByteBuffer + `glBufferSubData` path.
 *
 * @param target         GL buffer target (`GL_SHADER_STORAGE_BUFFER` or `GL_DRAW_INDIRECT_BUFFER`)
 * @param sectionSize    Size in bytes of a single frame's section
 * @param frames         Number of buffered frames (default 3 for triple buffering)
 */
class PersistentBuffer(
    val target: Int,
    val sectionSize: Long,
    val frames: Int = BUFFER_FRAMES
) {
    /** GL buffer name. */
    var bufferId: Int = 0
        private set

    /** Persistently mapped memory spanning all [frames] sections, or null if using fallback. */
    private var persistentMap: ByteBuffer? = null

    /** CPU-side staging buffer used only in the fallback (non-persistent) path. */
    private var fallbackBuffer: ByteBuffer? = null

    /** True if we successfully created a persistent mapping. */
    var isPersistent: Boolean = false
        private set

    /** Index of the current frame section (0 until [frames]). */
    private var currentFrame: Int = 0

    /** GL fence sync objects, one per frame section. Null means no fence placed yet. */
    private val fences = arrayOfNulls<Long>(frames)

    /** Total buffer size in bytes (sectionSize * frames for persistent, sectionSize for fallback). */
    val totalSize: Long
        get() = if (isPersistent) sectionSize * frames else sectionSize

    // ====================================================================
    // Initialization
    // ====================================================================

    /**
     * Create the GL buffer and attempt persistent mapping.
     * Must be called on the GL thread after context creation.
     */
    fun init() {
        bufferId = glGenBuffers()

        if (tryPersistentInit()) {
            isPersistent = true
        } else {
            // Fallback: traditional mutable buffer + CPU staging
            initFallback()
            isPersistent = false
        }
    }

    /**
     * Attempt to create a persistent mapped buffer via glBufferStorage.
     * Returns true on success, false if the GL version/extension is unavailable
     * or the mapping call fails.
     */
    private fun tryPersistentInit(): Boolean {
        val caps: GLCapabilities = GL.getCapabilities()
        if (!caps.GL_ARB_buffer_storage && !caps.OpenGL44) {
            return false
        }

        val totalBytes = sectionSize * frames

        val storageFlags = GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT
        val mapFlags = GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT

        glBindBuffer(target, bufferId)

        // Clear any previous GL error
        GL11.glGetError()

        glBufferStorage(target, totalBytes, storageFlags)

        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            glBindBuffer(target, 0)
            return false
        }

        val mapped = glMapBufferRange(target, 0, totalBytes, mapFlags)
        glBindBuffer(target, 0)

        if (mapped == null) {
            // Mapping failed; caller will fall back
            return false
        }

        persistentMap = mapped
        return true
    }

    /**
     * Fallback initialization using glBufferData + CPU-side staging buffer.
     */
    private fun initFallback() {
        glBindBuffer(target, bufferId)
        glBufferData(target, sectionSize, GL_DYNAMIC_DRAW)
        glBindBuffer(target, 0)

        fallbackBuffer = MemoryUtil.memAlloc(sectionSize.toInt())
    }

    // ====================================================================
    // Per-frame API
    // ====================================================================

    /**
     * Begin writing data for the current frame.
     *
     * For persistent buffers this waits on the fence for the current section (ensuring
     * the GPU is done reading it) and returns a ByteBuffer positioned at the section start.
     *
     * For fallback buffers this simply clears and returns the CPU staging buffer.
     *
     * @return A ByteBuffer positioned at the start of the writable region. The caller
     *         should write up to [sectionSize] bytes starting from position 0 (relative).
     */
    fun beginFrame(): ByteBuffer {
        if (isPersistent) {
            waitForFence()
            val map = persistentMap!!
            val offset = currentFrame.toLong() * sectionSize
            // Return a slice positioned at the current section
            map.position(offset.toInt())
            map.limit((offset + sectionSize).toInt())
            val slice = map.slice()
            // Restore the parent buffer's full range
            map.position(0)
            map.limit(map.capacity())
            return slice
        } else {
            val fb = fallbackBuffer!!
            fb.clear()
            return fb
        }
    }

    /**
     * Finish writing for the current frame.
     *
     * For persistent buffers: places a fence sync and advances the frame counter.
     * For fallback buffers: uploads the staging buffer via glBufferSubData and resets.
     *
     * @param bytesWritten The number of bytes actually written this frame. For the fallback
     *                     path this limits the upload size. For persistent buffers it is
     *                     informational only (coherent mapping makes data visible automatically).
     */
    fun endFrame(bytesWritten: Long) {
        if (isPersistent) {
            placeFence()
            currentFrame = (currentFrame + 1) % frames
        } else {
            val fb = fallbackBuffer!!
            fb.flip()
            // Only upload the bytes that were actually written
            if (bytesWritten > 0) {
                fb.limit(bytesWritten.toInt())
                glBindBuffer(target, bufferId)
                glBufferSubData(target, 0, fb)
                glBindBuffer(target, 0)
            }
        }
    }

    /**
     * Bind the current frame's section of the buffer to an indexed binding point
     * using `glBindBufferRange`. This is used for SSBOs.
     *
     * For fallback buffers (single section, no offset), this binds the entire buffer.
     *
     * @param binding The indexed binding point
     * @param size    The number of bytes to bind (should match bytesWritten from endFrame)
     */
    fun bindRange(binding: Int, size: Long) {
        if (isPersistent) {
            val offset = currentFrameReadOffset()
            glBindBufferRange(target, binding, bufferId, offset, size)
        } else {
            glBindBufferRange(target, binding, bufferId, 0, size)
        }
    }

    /**
     * Bind the buffer as the current target (e.g., GL_DRAW_INDIRECT_BUFFER).
     * For persistent mapping, the caller must use an explicit byte offset in the draw call.
     * For fallback, offset is always 0.
     */
    fun bind() {
        glBindBuffer(target, bufferId)
    }

    /**
     * Unbind the buffer from its target.
     */
    fun unbind() {
        glBindBuffer(target, 0)
    }

    /**
     * Get the byte offset into the buffer for the most recently written frame section.
     * This is the section that was written during the last [beginFrame]/[endFrame] cycle.
     *
     * For persistent buffers, the "read" frame is the one that was just written (before
     * the frame counter advanced in endFrame). Since endFrame advances the counter,
     * the last-written section is (currentFrame - 1 + frames) % frames.
     *
     * For fallback buffers, this is always 0.
     */
    fun currentFrameReadOffset(): Long {
        return if (isPersistent) {
            val readFrame = (currentFrame - 1 + frames) % frames
            readFrame.toLong() * sectionSize
        } else {
            0L
        }
    }

    // ====================================================================
    // Fence synchronization (persistent path only)
    // ====================================================================

    /**
     * Wait for the GPU to finish reading the current frame's section.
     */
    private fun waitForFence() {
        val fence = fences[currentFrame] ?: return
        // Wait with a 1-second timeout (should be nearly instant if triple buffered)
        val result = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L)
        if (result == GL_WAIT_FAILED) {
            System.err.println("[PersistentBuffer] glClientWaitSync returned GL_WAIT_FAILED")
        }
        glDeleteSync(fence)
        fences[currentFrame] = null
    }

    /**
     * Place a fence after the current frame's draw calls.
     */
    private fun placeFence() {
        fences[currentFrame] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    /**
     * Release all GPU and CPU resources.
     */
    fun cleanup() {
        // Delete any outstanding fence syncs
        for (i in fences.indices) {
            fences[i]?.let { glDeleteSync(it) }
            fences[i] = null
        }

        // Unmap persistent buffer before deleting
        if (isPersistent && persistentMap != null) {
            glBindBuffer(target, bufferId)
            glUnmapBuffer(target)
            glBindBuffer(target, 0)
            persistentMap = null
        }

        // Free fallback CPU buffer
        fallbackBuffer?.let { MemoryUtil.memFree(it) }
        fallbackBuffer = null

        // Delete GL buffer
        if (bufferId != 0) {
            glDeleteBuffers(bufferId)
            bufferId = 0
        }

        isPersistent = false
        currentFrame = 0
    }

    companion object {
        /** Default number of buffered frames (triple buffering). */
        const val BUFFER_FRAMES = 3
    }
}
