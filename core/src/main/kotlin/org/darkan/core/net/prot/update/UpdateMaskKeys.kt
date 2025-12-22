package org.darkan.core.net.prot.update

/**
 * Revision-agnostic semantic mask keys.
 *
 * Presence logic is shared across revisions; revisions only map these keys to:
 * - mask bit ids
 * - encoding structure/transforms
 */

enum class PlayerMaskKey {
    /**
     * Legacy: `nextBodyGlow` / `applyBodyGlowMask`.
     */
    BODY_GLOW,
    /**
     * Legacy: unknown (commented in world-server); likely a var/varn delta without reset.
     */
    VARN_NO_CLEAR,
    /**
     * Legacy: `nextSpotAnim2` / `applyGraphicsMask2`.
     */
    GRAPHICS_2,
    /**
     * Legacy: `applyFaceDirectionMask`.
     */
    FACE_DIRECTION,
    /**
     * Legacy: unknown (commented in world-server); likely a var/varn delta with reset.
     */
    VARN_CLEAR,
    /**
     * Legacy: `0x10000` is commented as map dot related.
     */
    MAP_DOT_2,
    /**
     * Legacy: `bodyModelRotator.encodePlayer`.
     */
    BODY_MODEL_ROTATOR,
    /**
     * Legacy: `nextForceTalk` / `applyForceTalkMask`.
     */
    FORCE_TALK,
    /**
     * Legacy: commented as "Forcechat with optional display in chatbox".
     */
    FORCE_CHAT,
    /**
     * Legacy: `nextSpotAnim3` / `applyGraphicsMask3`.
     */
    GRAPHICS_3,
    /**
     * Legacy: `0x8000` is commented as map dot related.
     */
    MAP_DOT_1,
    /**
     * Legacy: `applyHitsMask`.
     */
    HITS,
    /**
     * Legacy: `applyAppearanceMask`.
     */
    APPEARANCE,
    /**
     * Legacy: `applyAnimationMask`.
     *
     * Note: in our model this maps to movement/BAS-like animation ids.
     */
    ANIMATION,
    /**
     * Legacy: `applyFaceEntityMask`.
     */
    FACE_ENTITY,
    /**
     * Legacy: `applyTemporaryMoveTypeMask`.
     */
    TEMPORARY_MOVE_TYPE,
    /**
     * Legacy: commented as animation related (0x2000).
     */
    ANIMATION_SEQUENCE,
    /**
     * Legacy: `applyMoveTypeMask`.
     */
    MOVE_TYPE,
    /**
     * Legacy: `nextSpotAnim4` / `applyGraphicsMask4`.
     */
    GRAPHICS_4,
    /**
     * Legacy: `applyForceMovementMask`.
     */
    FORCE_MOVEMENT,
    /**
     * Legacy: `nextSpotAnim1` / `applyGraphicsMask1`.
     */
    GRAPHICS_1,
}

enum class NpcMaskKey {
    /**
     * Legacy: `nextAnimation` / `applyAnimationMask`.
     */
    ANIMATION,
    /**
     * Legacy: `bodyMeshModifier` / `applyBodyMeshModifierMask`.
     */
    BODY_MESH_MODIFIER,
    /**
     * Legacy: `nextForceMovement` / `applyForceMovementMask`.
     */
    FORCE_MOVEMENT,
    /**
     * Legacy: `bas`.
     */
    BAS,
    /**
     * Legacy: commented as unused/outdated varn (0x200000).
     */
    VARN_SET,
    /**
     * Legacy: `nextSpotAnim4` / `applyGraphicsMask4`.
     */
    GRAPHICS_4,
    /**
     * Legacy: `applyFaceEntityMask`.
     */
    FACE_ENTITY,
    /**
     * Legacy: commented as animation related (0x8000).
     */
    ANIMATION_SEQUENCE,
    /**
     * Legacy: `nextSpotAnim2` / `applyGraphicsMask2`.
     */
    GRAPHICS_2,
    /**
     * Legacy: `applyHitMask`.
     */
    HITS,
    /**
     * Legacy: `applyTransformationMask`.
     */
    TRANSFORMATION,
    /**
     * Legacy: `nextSpotAnim3` / `applyGraphicsMask3`.
     */
    GRAPHICS_3,
    /**
     * Legacy: commented as unused (0x80000) – kept as a modelled stats payload.
     */
    STATS_UPDATE,
    /**
     * Legacy: commented as mesh modifier for npc chatheads (0x40000).
     */
    HEAD_MESH_MODIFIER,
    /**
     * Legacy: `applyChangeLevelMask`.
     */
    CHANGE_COMBAT_LEVEL,
    /**
     * Legacy: commented as unused/outdated varn (0x400000).
     */
    VARN_CLEAR,
    /**
     * Legacy: `applyFaceTileMask`.
     */
    FACE_TILE,
    /**
     * Legacy: `nextBodyGlow` / `applyBodyGlowMask`.
     */
    BODY_GLOW,
    /**
     * Legacy: `applyNameChangeMask`.
     */
    NAME_CHANGE,
    /**
     * Legacy: `nextForceTalk` / `applyForceTalkMask`.
     */
    FORCE_TALK,
    /**
     * Legacy: `bodyModelRotator.encodeNPC`.
     */
    BODY_MODEL_ROTATOR,
    /**
     * Legacy: `nextSpotAnim1` / `applyGraphicsMask1`.
     */
    GRAPHICS_1,
}


