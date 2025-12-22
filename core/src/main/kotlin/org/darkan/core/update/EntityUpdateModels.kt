package org.darkan.core.update

/**
 * Revision-agnostic entity update models shared across Player/NPC update protocols.
 *
 * These are semantic models (not on-wire encodings). Each revision maps these masks to:
 * - mask bit ids (scrambled per revision)
 * - encoding order / transforms
 */

/**
 * A single spot animation (gfx) update.
 *
 * @param slot Revision-defined logical slot (commonly 0..3). The revision encoder maps slot->mask + write order.
 * @param id SpotAnim id, or -1 to clear.
 * @param settingsHash Packed settings (commonly delay/height/etc) as used by the update protocol.
 * @param settings2Hash Packed rotation/slot/bool bits as used by the update protocol.
 */
data class SpotAnim(
    val slot: Int,
    val id: Int,
    val settingsHash: Int,
    val settings2Hash: Int,
)

/**
 * A common “tint/glow” style update used by both players and NPCs (body glow in legacy naming).
 */
data class Tint(
    val hue: Int,
    val saturation: Int,
    val lightness: Int,
    val opacity: Int,
    val startCycles: Int,
    val endCycles: Int,
)

/**
 * Forced movement between two offsets over time.
 *
 * Offsets are relative to the entity base tile in the revision protocol.
 */
data class ForceMovement(
    val diffX1: Int,
    val diffY1: Int,
    val diffX2: Int,
    val diffY2: Int,
    val startClientCycles: Int,
    val speedClientCycles: Int,
    val direction: Int,
)

/**
 * Model/config override pairs used by some update blocks (client calls `method15797`).
 *
 * @param keys Packed keys (sometimes `ushort` or `ushort<<16|ushort` depending on high bits).
 * @param values Short values corresponding to keys.
 */
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

/**
 * Update blocks shared by Player+NPC where the semantics match.
 */
data class CommonUpdateBlocks(
    val tint: Tint? = null,
    /**
     * Spot animation updates by slot (commonly 0..3).
     */
    val spotAnims: Array<SpotAnim?> = arrayOfNulls(4),
    val forceMovement: ForceMovement? = null,
    /**
     * Face entity index, or -1 to clear.
     */
    val faceEntity: Int? = null,
    val faceTile: FaceTile? = null,
    val hits: HitsUpdate? = null,
    val modelOverrides: ModelOverrides? = null,
)

data class PlayerUpdateBlocks(
    val common: CommonUpdateBlocks = CommonUpdateBlocks(),
    val appearance: PlayerAppearance? = null,
    val movementAnimations: MovementAnimations? = null,
    /**
     * Player “face direction” (yaw/angle).
     *
     * On-wire in 727 this is `readUShortAdd`.
     */
    val faceDirection: Int? = null,
    val movementTypeId: Int? = null,
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
    val name: String? = null,
    val say: String? = null,
    val sequences: SequencesUpdate? = null,
)

data class PlayerAppearance(
    val hash: ByteArray,
    val data: ByteArray,
)

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
    /**
     * Standard hitsplat with numeric value.
     */
    data class Standard(val id: Int, val value: Int, val delay: Int) : HitSplat

    /**
     * Missed hitsplat.
     */
    data class Miss(val missValue: Int, val delay: Int) : HitSplat

    /**
     * Standard hitsplat with numeric value plus soaking.
     */
    data class Soak(val id: Int, val value: Int, val soakId: Int, val soakValue: Int, val delay: Int) : HitSplat

    /**
     * Custom text hitsplat (with optional soaking custom).
     */
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
    /**
     * Lifetime timer in client cycles; 32767 means hide.
     */
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


