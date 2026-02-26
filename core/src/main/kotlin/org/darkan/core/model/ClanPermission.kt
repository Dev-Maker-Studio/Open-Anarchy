package org.darkan.core.model

enum class ClanPermission(val varc: Int) {
    ALLOW_CITADEL(1560),
    KEEP_CITADEL(1561),
    BATTLEFIELD(1562);

    fun hasPermission(clan: Clan, rank: ClanRank): Boolean {
        val settingName = "${rank.name}_${name}"
        val setting = ClanSetting.entries.find { it.name == settingName }
        if (setting != null) {
            val value = clan.getSetting(setting)
            return value is Number && value.toInt() == 1
        }
        return rank.ordinal >= ClanRank.ADMIN.ordinal
    }
}
