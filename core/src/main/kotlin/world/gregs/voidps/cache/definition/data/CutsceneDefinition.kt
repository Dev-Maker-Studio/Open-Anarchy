package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.DefinitionRecord

data class CutsceneDefinition(
    override var id: Int = -1,
    var viewportHeight: Int = -1,
    var viewportWidth: Int = -1,
    var areas: List<CutsceneAreaRecord>? = null,
    var cameraMovements: List<CutsceneCameraMovementRecord>? = null,
    var entities: List<CutsceneEntityRecord>? = null,
    var objects: List<CutsceneObjectRecord>? = null,
    var entityMovements: List<CutsceneEntityMovementRecord>? = null,
    var actions: List<CutsceneActionRecord>? = null
) : Definition

data class CutsceneAreaRecord(
    var position: Int = 0,
    var width: Int = 0,
    var length: Int = 0,
    var cutscenePlane: Int = 0,
    var chunkBaseX: Int = 0,
    var chunkBaseY: Int = 0,
    var rotation: Int = 0
) : DefinitionRecord

data class CutsceneCameraMovementRecord(
    var x: IntArray = IntArray(0),
    var z: IntArray = IntArray(0),
    var y: IntArray = IntArray(0),
    var targetX: IntArray = IntArray(0),
    var targetZ: IntArray = IntArray(0),
    var targetY: IntArray = IntArray(0),
    var timestamp: IntArray = IntArray(0)
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CutsceneCameraMovementRecord) {
            return false
        }
        return x.contentEquals(other.x) &&
            z.contentEquals(other.z) &&
            y.contentEquals(other.y) &&
            targetX.contentEquals(other.targetX) &&
            targetZ.contentEquals(other.targetZ) &&
            targetY.contentEquals(other.targetY) &&
            timestamp.contentEquals(other.timestamp)
    }

    override fun hashCode(): Int {
        var result = x.contentHashCode()
        result = 31 * result + z.contentHashCode()
        result = 31 * result + y.contentHashCode()
        result = 31 * result + targetX.contentHashCode()
        result = 31 * result + targetZ.contentHashCode()
        result = 31 * result + targetY.contentHashCode()
        result = 31 * result + timestamp.contentHashCode()
        return result
    }
}

data class CutsceneEntityRecord(
    var type: Int = 0,
    var npcId: Int? = null,
    var label: String = ""
) : DefinitionRecord

data class CutsceneObjectRecord(
    var locId: Int = -1,
    var locShape: Int = 0
) : DefinitionRecord

data class CutsceneEntityMovementRecord(
    var movementTypes: IntArray = IntArray(0),
    var x: IntArray = IntArray(0),
    var y: IntArray = IntArray(0)
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CutsceneEntityMovementRecord) {
            return false
        }
        return movementTypes.contentEquals(other.movementTypes) && x.contentEquals(other.x) && y.contentEquals(other.y)
    }

    override fun hashCode(): Int {
        var result = movementTypes.contentHashCode()
        result = 31 * result + x.contentHashCode()
        result = 31 * result + y.contentHashCode()
        return result
    }
}

data class CutsceneActionRecord(
    var type: Int = -1,
    var lengthInCycles: Int = 0,
    var localX: Int? = null,
    var moveZ: Int? = null,
    var localY: Int? = null,
    var angleX: Int? = null,
    var angleY: Int? = null,
    var positionMovementIndex: Int? = null,
    var lookAtMovementIndex: Int? = null,
    var positionKeyframe: Int? = null,
    var lookAtKeyframe: Int? = null,
    var splineSpeedStart: Int? = null,
    var splineSpeedEnd: Int? = null,
    var entityIndex: Int? = null,
    var movementIndex: Int? = null,
    var movementAnimationId: Int? = null,
    var seqFlag: Int? = null,
    var direction: Int? = null,
    var rotation: Int? = null,
    var hitmarkFlags: Int? = null,
    var hitsplatId: Int? = null,
    var hitText: Int? = null,
    var soakHitsplatId: Int? = null,
    var soakText: Int? = null,
    var currentHealth: Int? = null,
    var maxHealth: Int? = null,
    var objectIndex: Int? = null,
    var sequenceId: Int? = null,
    var packedPos: Int? = null,
    var packedTargetPos: Int? = null,
    var plane: Int? = null,
    var absX: Int? = null,
    var absY: Int? = null,
    var gfxId: Int? = null,
    var displayHeight: Int? = null,
    var spotAnimationIndex: Int? = null,
    var adjustmentType: Int? = null,
    var sourceEntityIndex: Int? = null,
    var targetEntityIndex: Int? = null,
    var startHeight: Int? = null,
    var endHeight: Int? = null,
    var angle: Int? = null,
    var slope: Int? = null,
    var musicId: Int? = null,
    var jingleId: Int? = null,
    var soundId: Int? = null,
    var volume: Int? = null,
    var sampleRate: Int? = null,
    var timesRepeated: Int? = null,
    var colour: Int? = null,
    var duration: Int? = null,
    var text: String? = null,
    var scriptIntParam: Int? = null,
    var variableId: Int? = null,
    var value: Int? = null
) : DefinitionRecord
