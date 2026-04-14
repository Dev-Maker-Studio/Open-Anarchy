package org.darkan.core.model

/**
 * Per-tick movement type for a player or NPC.
 *
 * The [id] field is the wire value used by the player MOVE_TYPE (0x4) and
 * TEMPORARY_MOVE_TYPE (0x1000) masks. Players only ever have [WALK], [RUN],
 * or [TELEPORT] as their [id]. NPC movement is bit-encoded separately in the
 * update protocol and does not use [id].
 *
 * [HALF_WALK] (a.k.a. "crawl") is an NPC-only movement style for slow-walking
 * NPCs whose cache definition marks them as such; its [id] value is not
 * written to any wire format.
 */
enum class MoveType(val id: Int) {
    WALK(1),
    RUN(2),
    HALF_WALK(0),
    TELEPORT(127),
}
