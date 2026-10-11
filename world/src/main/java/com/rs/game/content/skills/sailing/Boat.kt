package com.rs.game.content.skills.sailing

enum class BoatState { DOCKED, SAILING, CAPSIZED }

class Boat(
    val id: Long,
    val owner: String,
    val type: BoatType,
    var name: String,
    var hull: HullTier = HullTier.WOODEN,
    var helm: HelmTier = HelmTier.BRONZE,
    var sail: SailTier = SailTier.WOODEN,
    var keel: KeelTier? = if (type.hasKeel) KeelTier.BRONZE else null,
    var hitpoints: Int = 0,
    var dockPort: String = "",
    var hasBrazier: Boolean = false,
    var hasInoculationStation: Boolean = false,
    var state: BoatState = BoatState.DOCKED,
    var globalX: Int = 0,
    var globalY: Int = 0,
    var heading: Int = 0,
    var cannon: CannonTier? = null,
    var hook: HookTier? = null,
    val facilities: MutableSet<Facility> = mutableSetOf(),
    val crew: MutableList<Crewmate> = mutableListOf()
) {
    val hold = CargoHold(0)

    val maxHitpoints: Int get() = hull.hitpoints(type) + (keel?.hitpoints(type) ?: 0)

    val speedCap: Double get() = hull.speedCap(type)

    val baseSpeed: Double get() = hull.baseSpeed

    val defence: Int get() = hull.defence(type)

    val flatArmour: Int get() = keel?.flatArmour ?: 0

    init {
        if (hitpoints <= 0 && state != BoatState.CAPSIZED) hitpoints = maxHitpoints
        hold.capacity = holdCapacity
    }

    val holdCapacity: Int get() = type.holdBase + hull.ordinal * type.holdPerTier

    val hotspotsUsed: Int get() = facilities.size + (if (cannon != null) 1 else 0) + (if (hook != null) 1 else 0)

    val crewSlots: Int get() = type.crewSlots

    fun capsize() {
        hitpoints = 0
        state = BoatState.CAPSIZED
        hold.clearLost()
    }

    fun recover() {
        repairFully()
        state = BoatState.DOCKED
    }

    fun repairFully() {
        hitpoints = maxHitpoints
    }

    fun damage(raw: Int): Int {
        val dealt = (raw - flatArmour).coerceAtLeast(MIN_DAMAGE)
        hitpoints = (hitpoints - dealt).coerceAtLeast(0)
        return dealt
    }

    val capsized: Boolean get() = hitpoints <= 0 || state == BoatState.CAPSIZED

    fun upgradeHull(tier: HullTier) {
        hull = tier
        hold.capacity = holdCapacity
        repairFully()
    }

    fun upgradeKeel(tier: KeelTier) {
        if (!type.hasKeel) return
        keel = tier
        repairFully()
    }

    fun footprintClear(x: Int, y: Int, heading: Int, isWater: (Int, Int) -> Boolean): Boolean {
        val radians = Math.toRadians(heading.toDouble())
        val forwardX = Math.sin(radians)
        val forwardY = Math.cos(radians)
        val sideX = forwardY
        val sideY = -forwardX
        return (0 until type.length).all { along ->
            (0 until type.width).all { across ->
                val ox = (along - type.length / 2) * forwardX + (across - type.width / 2) * sideX
                val oy = (along - type.length / 2) * forwardY + (across - type.width / 2) * sideY
                isWater(x + Math.round(ox).toInt(), y + Math.round(oy).toInt())
            }
        }
    }

    private companion object {
        const val MIN_DAMAGE = 0
    }
}
