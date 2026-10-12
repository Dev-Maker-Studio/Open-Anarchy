package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.DefinitionRecord
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class DefaultsDefinition(
    override var id: Int = -1,
    var cubeTextures: IntArray? = null,
    var maximumHits: Int? = null,
    var hitOffsetsX: IntArray? = null,
    var hitOffsetsY: IntArray? = null,
    var profilingMesh: Int? = null,
    var alwaysShowContextMenu: Boolean? = null,
    var loginInterface: Int? = null,
    var lobbyWindow: Int? = null,
    var recolours: List<RecolourRecord>? = null,
    var npcMessages: Boolean? = null,
    var npcMessageDuration: Int? = null,
    var playerMessages: Boolean? = null,
    var playerMessageDuration: Int? = null,
    var gameWidth: Int? = null,
    var gameHeight: Int? = null,
    var maxHitbars: Int? = null,
    var maxVisibleHitbars: Int? = null,
    var hitbarHeight: Int? = null,
    var titleScreenMusic: Int? = null,
    var inputs: MutableList<InputSubscriberRecord>? = null,
    var minimenuCursor: Boolean? = null,
    var tooltipColourMembers: Int? = null,
    var tooltipColourFree: Int? = null,
    override var opcodeOrder: IntArray? = null,
    override var shadowedPayloads: Array<ByteArray?>? = null
) : Definition, OpcodeOrdered {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DefaultsDefinition
        if (id != other.id) return false
        if (!contentEquals(cubeTextures, other.cubeTextures)) return false
        if (maximumHits != other.maximumHits) return false
        if (!contentEquals(hitOffsetsX, other.hitOffsetsX)) return false
        if (!contentEquals(hitOffsetsY, other.hitOffsetsY)) return false
        if (profilingMesh != other.profilingMesh) return false
        if (alwaysShowContextMenu != other.alwaysShowContextMenu) return false
        if (loginInterface != other.loginInterface) return false
        if (lobbyWindow != other.lobbyWindow) return false
        if (recolours != other.recolours) return false
        if (npcMessages != other.npcMessages) return false
        if (npcMessageDuration != other.npcMessageDuration) return false
        if (playerMessages != other.playerMessages) return false
        if (playerMessageDuration != other.playerMessageDuration) return false
        if (gameWidth != other.gameWidth) return false
        if (gameHeight != other.gameHeight) return false
        if (maxHitbars != other.maxHitbars) return false
        if (maxVisibleHitbars != other.maxVisibleHitbars) return false
        if (hitbarHeight != other.hitbarHeight) return false
        if (titleScreenMusic != other.titleScreenMusic) return false
        if (inputs != other.inputs) return false
        if (minimenuCursor != other.minimenuCursor) return false
        if (tooltipColourMembers != other.tooltipColourMembers) return false
        if (tooltipColourFree != other.tooltipColourFree) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (cubeTextures?.contentHashCode() ?: 0)
        result = 31 * result + (maximumHits ?: 0)
        result = 31 * result + (hitOffsetsX?.contentHashCode() ?: 0)
        result = 31 * result + (hitOffsetsY?.contentHashCode() ?: 0)
        result = 31 * result + (profilingMesh ?: 0)
        result = 31 * result + (recolours?.hashCode() ?: 0)
        result = 31 * result + (titleScreenMusic ?: 0)
        result = 31 * result + (inputs?.hashCode() ?: 0)
        return result
    }

    private fun contentEquals(left: IntArray?, right: IntArray?): Boolean {
        if (left == null) {
            return right == null
        }
        return right != null && left.contentEquals(right)
    }

    companion object {
        const val SCENE_CUBE_TEXTURES = 1
        const val ENTITY = 3
        const val TITLE_SCREEN_MUSIC = 4
        const val EQUIPMENT = 6
        const val INTERACTIONS = 7
        const val CUTSCENE = 8

        const val DEFAULT_MAXIMUM_HITS = 4

        const val PALETTES = 10
        const val PALETTE_COLOURS = 4
    }
}

data class RecolourRecord(
    var source: Int = -1,
    var destinations: IntArray = IntArray(0)
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RecolourRecord
        return source == other.source && destinations.contentEquals(other.destinations)
    }

    override fun hashCode(): Int = 31 * source + destinations.contentHashCode()
}

data class InputSubscriberRecord(
    var opcode: Int = -1,
    var type: Int? = null,
    var clickType: Int? = null,
    var minimumClicks: Int? = null,
    var code: Int? = null,
    var flags: Int? = null,
    var keys: IntArray? = null
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as InputSubscriberRecord
        if (opcode != other.opcode || type != other.type) return false
        if (clickType != other.clickType || minimumClicks != other.minimumClicks) return false
        if (code != other.code || flags != other.flags) return false
        val left = keys
        val right = other.keys
        if (left == null) {
            return right == null
        }
        return right != null && left.contentEquals(right)
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + (type ?: -1)
        result = 31 * result + (code ?: -1)
        result = 31 * result + (keys?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val COMBINED = 0
        const val KEY_PRESS = 1
        const val KEY_HOLD = 2
    }
}
