package org.darkan.core.model

import world.gregs.voidps.type.Direction

data class WalkStep(
    val direction: Direction,
    val x: Int,
    val y: Int,
    val checkClip: Boolean = true
)
