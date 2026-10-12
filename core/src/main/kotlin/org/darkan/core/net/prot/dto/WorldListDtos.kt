package org.darkan.core.net.prot.dto

data class WorldListEntry(
    val number: Int,
    val index: Int,
    val countryId: Int,
    val activity: String,
    val address: String,
    val port: Int,
    val members: Boolean,
    val quickChat: Boolean,
    val botsAllowed: Boolean,
    val lootShare: Boolean,
    val highlighted: Boolean,
    val playersOnline: Int,
    val customCache: Boolean = false,
)

data class WorldListSnapshot(
    val entries: List<WorldListEntry>,
    val maxWorlds: Int,
    val revision: Int,
)
