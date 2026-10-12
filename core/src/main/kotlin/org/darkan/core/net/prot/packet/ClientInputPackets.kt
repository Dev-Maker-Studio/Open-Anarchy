package org.darkan.core.net.prot.packet

import org.darkan.core.clientwatch.MouseTrailStep
import org.darkan.core.clientwatch.ReflectionResponseCode
import org.darkan.core.net.prot.ClientProt

@JvmInline value class Ping(val dummy: Int = 0) : ClientProt

data class ReflectionResponse(val id: Int, val data: ByteArray) : ClientProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReflectionResponse

        if (id != other.id) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + data.contentHashCode()
        return result
    }
}

@JvmInline value class ClientFocus(val focused: Boolean) : ClientProt

data class ClientScreenSize(val displayMode: Int, val width: Int, val height: Int, val switchDisplayMode: Boolean) : ClientProt

data class MoveCamera(val angleX: Int, val angleY: Int) : ClientProt

data class SendFps(val key1: Int, val key2: Int, val fps: Int) : ClientProt

data class MouseClick(val mouseButton: Int, val time: Int, val x: Int, val y: Int) : ClientProt

data class MouseButtonClick(val mouseButton: Int, val time: Int, val x: Int, val y: Int, val hw: Boolean) : ClientProt

data class MoveMouseData(val frameCount: Int, val frameSteps: Int, val steps: Array<MouseTrailStep>) : ClientProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MoveMouseData
        if (frameCount != other.frameCount) return false
        if (frameSteps != other.frameSteps) return false
        if (!steps.contentEquals(other.steps)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = frameCount
        result = 31 * result + frameSteps
        result = 31 * result + steps.contentHashCode()
        return result
    }
}

data class KeyPress(val keyCode: Int, val time: Int) : ClientProt

data class ClientCheat(val client: Boolean, val save: Boolean, val command: String) : ClientProt

@JvmInline value class AppletLoadingPleaseWait(val data: Int) : ClientProt
