package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15.glBeginQuery
import org.lwjgl.opengl.GL15.glDeleteQueries
import org.lwjgl.opengl.GL15.glEndQuery
import org.lwjgl.opengl.GL15.glGenQueries
import org.lwjgl.opengl.GL15.glGetQueryObjectui
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE
import org.lwjgl.opengl.GL33.GL_TIME_ELAPSED
import org.lwjgl.opengl.GL33.glGetQueryObjectui64

class GpuTimerPool(val passNames: List<String>) {

    val passMillis = DoubleArray(passNames.size)

    val totalMillis: Double
        get() = passMillis.sum()

    var supported: Boolean = false
        private set

    var resultAgeFrames: Int = 0
        private set

    private var queryIds: Array<IntArray> = emptyArray()

    private var pending: Array<BooleanArray> = emptyArray()

    private var currentSlot = 0
    private var activePass = -1
    private var initialized = false

    fun init(): Boolean {
        val caps = GL.getCapabilities()
        supported = caps.OpenGL33 || caps.GL_ARB_timer_query
        if (!supported) {
            println("[GpuTimerPool] GL_TIME_ELAPSED queries unavailable; GPU pass timing disabled")
            return false
        }

        queryIds = Array(DEPTH) { IntArray(passNames.size) { glGenQueries() } }
        pending = Array(DEPTH) { BooleanArray(passNames.size) }
        initialized = true
        return true
    }

    fun beginFrame() {
        if (!initialized) return
        harvest(currentSlot)
    }

    fun begin(pass: Int) {
        if (!initialized || pass !in passNames.indices) return
        if (activePass >= 0) end()
        glBeginQuery(GL_TIME_ELAPSED, queryIds[currentSlot][pass])
        activePass = pass
    }

    fun end() {
        if (!initialized || activePass < 0) return
        glEndQuery(GL_TIME_ELAPSED)
        pending[currentSlot][activePass] = true
        activePass = -1
    }

    fun endFrame() {
        if (!initialized) return
        if (activePass >= 0) end()
        currentSlot = (currentSlot + 1) % DEPTH
    }

    private fun harvest(slot: Int) {
        val ids = queryIds[slot]
        val slotPending = pending[slot]
        for (pass in ids.indices) {
            if (!slotPending[pass]) continue
            slotPending[pass] = false
            if (glGetQueryObjectui(ids[pass], GL_QUERY_RESULT_AVAILABLE) == 0) continue
            val nanos = glGetQueryObjectui64(ids[pass], GL_QUERY_RESULT)
            passMillis[pass] = nanos / 1_000_000.0
        }
        resultAgeFrames = DEPTH
    }

    fun cleanup() {
        if (!initialized) return
        if (activePass >= 0) {
            glEndQuery(GL_TIME_ELAPSED)
            activePass = -1
        }
        for (ids in queryIds) for (id in ids) glDeleteQueries(id)
        queryIds = emptyArray()
        pending = emptyArray()
        initialized = false
    }

    companion object {
        const val DEPTH = 3
    }
}
