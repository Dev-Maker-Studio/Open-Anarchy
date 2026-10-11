package com.rs.game.content.skills.sailing

object SailingTexts {
    private const val HIGHLIGHT = "ff981f"

    fun crewLine(crewmate: Crewmate): String =
        "<col=$HIGHLIGHT>${crewmate.name}</col><br>H${crewmate.helmsmanship} P${crewmate.privateering} D${crewmate.deckhandiness}  ${jobName(crewmate.job)}"

    fun recruitLine(crewmate: Crewmate): String =
        "<col=$HIGHLIGHT>${crewmate.name}</col><br>H${crewmate.helmsmanship} P${crewmate.privateering} D${crewmate.deckhandiness}  ${crewmate.wage} gp"

    fun taskCard(kind: TaskKind, crates: Int, from: String, to: String, xp: Int, bag: BagTier, status: String, monster: Int = ActiveTask.NO_MONSTER): String =
        if (kind == TaskKind.BOUNTY) {
            "<col=$HIGHLIGHT>BOUNTY</col><br>$crates trophies: ${BountyMonster.entries.getOrNull(monster)?.label ?: "monster"}<br>Port $from<br>$xp xp, ${bag.name.lowercase()} bag<br>$status"
        } else {
            "<col=$HIGHLIGHT>${kind.name}</col><br>$crates crate(s)<br>Port $from<br>to port $to<br>$xp xp, ${bag.name.lowercase()} bag<br>$status"
        }

    fun boatLine(boat: Boat): String =
        "<col=$HIGHLIGHT>${boat.name}</col> (${boat.type.name.lowercase()})<br>${boat.hitpoints}/${boat.maxHitpoints} HP, ${boat.state.name.lowercase()}${if (boat.dockPort.isNotEmpty()) ", port ${boat.dockPort}" else ""}"

    fun boardLine(index: Int, boat: Boat, site: String): String = "${index + 1}: ${boat.name} (${boat.type.name.lowercase()}) [port $site]"

    fun chartLine(sea: String, charted: Int, complete: Boolean): String =
        "<col=$HIGHLIGHT>$sea</col><br>$charted point(s) charted${if (complete) ", complete" else ""}"

    private fun jobName(job: CrewJob): String = when (job) {
        CrewJob.HELM -> "Helm"
        CrewJob.SALVAGE -> "Salvage"
        CrewJob.CANNON -> "Cannon"
        CrewJob.TRAWL -> "Trawl"
        CrewJob.REPAIR -> "Repair"
        CrewJob.NONE -> "Idle"
    }
}
