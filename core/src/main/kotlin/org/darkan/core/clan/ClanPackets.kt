package org.darkan.core.clan

import org.darkan.core.formatPlayerNameForDisplay
import org.darkan.core.model.Clan
import org.darkan.core.model.ClanMemberData
import org.darkan.core.model.ClanSetting
import org.darkan.core.model.NO_CLIENT_ID
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.ClanChannelChatter
import org.darkan.core.net.prot.dto.ClanSettingsMember
import org.darkan.core.net.prot.dto.ClanVarSetting
import org.darkan.core.net.prot.packet.ClanChannelFull
import org.darkan.core.net.prot.packet.ClanSettingsFull
import org.darkan.core.net.prot.packet.MessageClanChannel
import org.darkan.core.net.prot.packet.MessageQuickChatClanChannel
import org.darkan.core.net.prot.packet.VarclanEnable
import org.darkan.core.net.prot.packet.VarclanSetByte
import org.darkan.core.net.prot.packet.VarclanSetInt
import org.darkan.core.net.prot.packet.VarclanSetLong
import org.darkan.core.net.prot.packet.VarclanSetString
import org.darkan.core.social.QuickChatMessage
import world.gregs.voidps.cache.Cache

enum class ClanVarKind { INT, LONG, STRING }

object ClanPackets {

    fun settingsFull(
        clan: Clan,
        main: Boolean,
        updateCount: Int = 0,
        displayName: (String) -> String = { it.formatPlayerNameForDisplay() },
    ): ClanSettingsFull {
        val members = clan.members.entries
            .sortedWith(compareBy<Map.Entry<String, ClanMemberData>> { it.value.joinDate }.thenBy { it.key })
            .map { (username, data) -> ClanSettingsMember(displayName = displayName(username), rank = data.rank.iconId) }
            .toTypedArray()
        val banned = clan.bannedUsers.sorted().map(displayName).toTypedArray()
        val settings = clan.settings.mapNotNull { (key, value) -> clientSetting(key, value) }.toTypedArray()
        return ClanSettingsFull(
            main = main,
            clanName = clan.name,
            updateCount = updateCount,
            allowGuests = (clan.getSetting(ClanSetting.GUESTS_CAN_ENTER_CC) as? Number)?.toInt() == 1,
            talkRank = clan.ccChatRank.iconId,
            kickRank = clan.ccKickRank.iconId,
            members = members,
            bannedUsers = banned,
            settings = settings,
        )
    }

    fun settingsLeft(main: Boolean): ClanSettingsFull = ClanSettingsFull(main = main)

    fun channelFull(state: ClanChannelState, main: Boolean): ClanChannelFull = ClanChannelFull(
        main = main,
        clanName = state.clanName,
        updateNum = state.updateNum,
        kickRank = state.kickRank.iconId,
        talkRank = state.talkRank.iconId,
        chatters = state.chatters
            .map { ClanChannelChatter(displayName = it.displayName, rank = it.rank.iconId, worldId = it.worldId) }
            .toTypedArray(),
    )

    fun channelLeft(main: Boolean): ClanChannelFull = ClanChannelFull(main = main)

    fun clanVarPackets(clan: Clan, varKind: (Int) -> ClanVarKind? = ::cacheVarKind): List<ServerProt> {
        val packets = ArrayList<ServerProt>(clan.vars.size + 1)
        packets += VarclanEnable()
        for ((key, value) in clan.vars) {
            val id = key.toIntOrNull() ?: continue
            packets += clanVarPacket(id, value, varKind(id) ?: continue) ?: continue
        }
        return packets
    }

    fun clanVarPacket(id: Int, value: Any, kind: ClanVarKind): ServerProt? = when (kind) {
        ClanVarKind.STRING -> VarclanSetString(id, value.toString())
        ClanVarKind.LONG -> (value as? Number)?.let { VarclanSetLong(id, it.toLong()) }
        ClanVarKind.INT -> (value as? Number)?.toInt()?.let {
            if (it < Byte.MIN_VALUE || it > Byte.MAX_VALUE) VarclanSetInt(id, it) else VarclanSetByte(id, it)
        }
    }

    fun chatMessage(guest: Boolean, crown: Int, displayName: String, text: String): MessageClanChannel =
        MessageClanChannel(guest = guest, crown = crown, displayName = displayName, message = text)

    fun quickChatMessage(guest: Boolean, crown: Int, displayName: String, message: QuickChatMessage): MessageQuickChatClanChannel =
        MessageQuickChatClanChannel(guest = guest, crown = crown, displayName = displayName, message = message)

    fun cacheVarKind(id: Int): ClanVarKind? {
        val type = Cache.clanVars.getOrNull(id)?.type ?: return null
        return when {
            type.stringStackSize > 0 -> ClanVarKind.STRING
            type.longStackSize > 0 -> ClanVarKind.LONG
            else -> ClanVarKind.INT
        }
    }

    private fun clientSetting(name: String, value: Any): ClanVarSetting? {
        val id = ClanSetting.forName(name)?.id ?: return null
        if (id == NO_CLIENT_ID) return null
        return when (value) {
            is Int -> ClanVarSetting(key = id, intValue = value)
            is Long -> ClanVarSetting(key = id, longValue = value)
            is String -> ClanVarSetting(key = id, stringValue = value)
            is Number -> ClanVarSetting(key = id, intValue = value.toInt())
            else -> null
        }
    }
}
