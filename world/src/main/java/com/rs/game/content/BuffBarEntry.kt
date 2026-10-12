package com.rs.game.content

class BuffBarEntry(val icon: Int, val isSprite: Boolean, val description: String)

fun objIcon(icon: Int, description: String) = BuffBarEntry(icon, false, description)

fun spriteIcon(icon: Int, description: String) = BuffBarEntry(icon, true, description)
