package com.rs.game.content.skills.sailing

private const val FIRST_GUST_TICKS = 49
private const val GUST_AFTER_BOOST_TICKS = 20
private const val TRIM_WINDOW_TICKS = 20
private const val TRIM_COOLDOWN_TICKS = 50
private const val WIND_CATCHER_XP_FACTOR = 0.75
private const val CREW_XP_BY_HELMSMANSHIP = 0.1
private const val MOTE_XP = 40.0
private const val GALE_MOTE_XP = 70.0
private const val MAX_MOTES = 2
private const val GALE_MAX_MOTES = 3

class SailTrim(private val boat: Boat, private val movement: BoatMovement) {
    var gustOpen = false
        private set
    var windCatcher = false
    var galeCatcher = false
    var motes = 0
        private set
    private var ticksToGust = FIRST_GUST_TICKS
    private var windowLeft = 0
    private var cooldown = 0
    var holdingCargo = false

    fun tick() {
        if (cooldown > 0) cooldown--
        if (!movement.sailsSet) return
        if (gustOpen) {
            if (--windowLeft <= 0) closeGust(GUST_AFTER_BOOST_TICKS)
            return
        }
        if (--ticksToGust <= 0 && cooldown == 0) {
            gustOpen = true
            windowLeft = TRIM_WINDOW_TICKS
        }
    }

    fun trim(sailingLevel: Int, isCaptain: Boolean): Double {
        if (!gustOpen || holdingCargo) return 0.0
        if (!isCaptain && sailingLevel < boat.sail.sailingLevel) return 0.0
        closeGust(boat.sail.boostTicks + GUST_AFTER_BOOST_TICKS)
        cooldown = TRIM_COOLDOWN_TICKS
        if (catcherActive() && motes < maxMotes()) {
            motes++
            return boat.sail.trimXp * WIND_CATCHER_XP_FACTOR
        }
        movement.addBurst(boat.sail.boostTicks)
        return boat.sail.trimXp
    }

    fun releaseMote(): Double {
        if (motes == 0) return 0.0
        motes--
        movement.addBurst(boat.sail.boostTicks)
        return if (galeCatcher) GALE_MOTE_XP else MOTE_XP
    }

    fun crewTrimXp(helmsmanship: Int): Double = boat.sail.trimXp * (helmsmanship * CREW_XP_BY_HELMSMANSHIP + CREW_XP_BASE)

    private fun catcherActive() = windCatcher || galeCatcher

    private fun maxMotes() = if (galeCatcher) GALE_MAX_MOTES else MAX_MOTES

    private fun closeGust(next: Int) {
        gustOpen = false
        ticksToGust = next
    }

    private companion object {
        const val CREW_XP_BASE = 0.3
    }
}
