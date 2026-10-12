package org.darkan.core.update

import java.security.MessageDigest


data class SpotAnim(
    val id: Int,
    val height: Int = 0,
    val speed: Int = 0,
    val rotation: Int = 0,
    val adjustReplayMode: Boolean = false,
)

data class Tint(
    val hue: Int,
    val saturation: Int,
    val lightness: Int,
    val opacity: Int,
    val startCycles: Int,
    val endCycles: Int,
)

data class ForceMovement(
    val diffX1: Int,
    val diffY1: Int,
    val diffX2: Int,
    val diffY2: Int,
    val startClientCycles: Int,
    val speedClientCycles: Int,
    val direction: Int,
)

data class ModelOverrides(
    val keys: IntArray,
    val values: IntArray,
) {
    init {
        require(keys.size == values.size) { "ModelOverrides keys/values size mismatch" }
    }
}

data class FaceTile(
    val x: Int,
    val y: Int,
)

data class PublicChat(
    val message: String,
    val chatFlags: Int,
)

data class CommonUpdateBlocks(
    val tint: Tint? = null,
    val spotAnims: Array<SpotAnim?> = arrayOfNulls(4),
    val forceMovement: ForceMovement? = null,
    val faceEntity: Int? = null,
    val persistentFaceEntity: Int? = null,
    val faceTile: FaceTile? = null,
    val hits: HitsUpdate? = null,
    val modelOverrides: ModelOverrides? = null,
)

data class PlayerUpdateBlocks(
    val common: CommonUpdateBlocks = CommonUpdateBlocks(),
    val appearance: PlayerAppearance? = null,
    val movementAnimations: MovementAnimations? = null,
    val faceDirection: Int? = null,
    val persistentFaceDirection: Int? = null,
    val movementTypeId: Int? = null,
    val persistentMovementTypeId: Int? = null,
    val temporaryMoveTypeId: Int? = null,
    val reduceAttackPriority: Boolean? = null,
    val mapDot: Boolean? = null,
    val playerConfigsNoClear: KeyIntMapDelta? = null,
    val playerConfigsClear: KeyIntMapDelta? = null,
    val forceTalk: String? = null,
    val publicChat: PublicChat? = null,
    val sequences: SequencesUpdate? = null,
)

data class NpcUpdateBlocks(
    val common: CommonUpdateBlocks = CommonUpdateBlocks(),
    val movementAnimations: MovementAnimations? = null,
    val customisation: NpcCustomisation? = null,
    val headCustomisation: NpcCustomisation? = null,
    val basId: Int? = null,
    val varnsNoClear: KeyIntMapDelta? = null,
    val varnsClear: KeyIntMapDelta? = null,
    val transformNpcId: Int? = null,
    val updateStats: NpcStatsUpdate? = null,
    val combatLevel: Int? = null,
    val persistentCombatLevel: Int? = null,
    val name: String? = null,
    val persistentName: String? = null,
    val say: String? = null,
    val sequences: SequencesUpdate? = null,
)

data class PlayerAppearance(
    val hash: ByteArray,
    val data: ByteArray,
) {
    companion object {
        private const val HASH_ALGORITHM = "MD5"

        fun of(data: ByteArray): PlayerAppearance =
            PlayerAppearance(hash = MessageDigest.getInstance(HASH_ALGORITHM).digest(data), data = data)
    }
}

data class MovementAnimations(
    val ids: IntArray,
    val speed: Int,
) {
    init {
        require(ids.size == 4) { "MovementAnimations.ids must be length 4" }
    }
}

data class KeyIntMapDelta(
    val clear: Boolean,
    val keys: IntArray,
    val values: IntArray,
) {
    init {
        require(keys.size == values.size) { "KeyIntMapDelta keys/values size mismatch" }
    }
}

data class SequencesUpdate(
    val ids: IntArray,
    val delays: IntArray,
    val flags: IntArray,
) {
    init {
        require(ids.size == delays.size && ids.size == flags.size) { "SequencesUpdate arrays must match length" }
    }
}

data class HitsUpdate(
    val hitSplats: Array<HitSplat>,
    val hitBars: Array<HitBarUpdate>,
)

sealed interface HitSplat {
    data class Standard(val id: Int, val value: Int, val delay: Int) : HitSplat

    data class Miss(val missValue: Int, val delay: Int) : HitSplat

    data class Soak(val id: Int, val value: Int, val soakId: Int, val soakValue: Int, val delay: Int) : HitSplat

    data class CustomText(
        val id: Int,
        val text: String,
        val soakId: Int? = null,
        val soakText: String? = null,
        val delay: Int,
    ) : HitSplat
}

data class HitBarUpdate(
    val type: Int,
    val timer: Int,
    val delay: Int = 0,
    val fromPercentage: Int = 0,
    val toPercentage: Int = fromPercentage,
)

data class NpcStatsUpdate(
    val indices: IntArray,
    val maxValues: IntArray,
    val currentValues: IntArray,
) {
    init {
        require(indices.size == maxValues.size && indices.size == currentValues.size) { "NpcStatsUpdate arrays must match length" }
    }
}

data class NpcCustomisation(
    val clear: Boolean,
    val models: IntArray? = null,
    val recolors: ShortArray? = null,
    val retextures: ShortArray? = null,
)


