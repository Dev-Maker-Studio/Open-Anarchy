package org.darkan.core.clan

import org.darkan.core.model.Clan
import org.darkan.core.model.ClanMemberData
import org.darkan.core.model.ClanPermission
import org.darkan.core.model.ClanRank
import org.darkan.core.model.ClanSetting

sealed interface ClanDecision {
    data object Allowed : ClanDecision
    data class Denied(val message: String) : ClanDecision

    val allowed: Boolean get() = this is Allowed
}

object ClanRules {

    const val MAX_NAME_LENGTH = 12

    private const val NOT_IN_CLAN = "You must be in a clan to do that."
    private const val NOT_A_MEMBER = "That player is not a clan member."
    private const val NOT_IN_CHANNEL = "You are not currently connected to a clan chat."
    private const val ADMIN_ONLY_SETTINGS = "Only clan admins can edit the clan settings."


    fun rankOf(clan: Clan, username: String): ClanRank {
        if (username == clan.leaderUsername) return ClanRank.OWNER
        return clan.members[username]?.rank ?: ClanRank.NONE
    }

    fun isMember(clan: Clan, username: String): Boolean =
        username == clan.leaderUsername || clan.members.containsKey(username)

    private fun isAdmin(rank: ClanRank): Boolean = rank.ordinal >= ClanRank.ADMIN.ordinal

    private fun meetsChannelRank(rank: ClanRank, required: ClanRank): Boolean = when (rank) {
        ClanRank.NONE -> false
        ClanRank.OWNER, ClanRank.JMOD -> true
        else -> rank.ordinal >= required.ordinal
    }

    private fun settingOn(clan: Clan, setting: ClanSetting): Boolean =
        (clan.getSetting(setting) as? Number)?.toInt() == 1

    fun successor(clan: Clan): String? = clan.members.entries
        .filter { (username, data) -> username != clan.leaderUsername && data.rank != ClanRank.JMOD }
        .sortedWith(
            compareByDescending<Map.Entry<String, ClanMemberData>> { it.value.rank.ordinal }
                .thenBy { it.value.joinDate }
                .thenBy { it.key },
        )
        .firstOrNull()?.key


    fun validateName(name: String): ClanDecision {
        val valid = name.isNotEmpty() &&
            name.length <= MAX_NAME_LENGTH &&
            name.all { it.isLetterOrDigit() && it.code < 128 || it == ' ' } &&
            !name.startsWith(' ') && !name.endsWith(' ') && !name.contains("  ")
        return if (valid) ClanDecision.Allowed
        else ClanDecision.Denied("Clan names must be 12 or fewer characters and be alphanumeric.")
    }

    fun settingsDelta(clan: Clan, settings: Map<String, Any>): Result<Map<ClanSetting, Any>> {
        val delta = LinkedHashMap<ClanSetting, Any>()
        for ((key, value) in settings) {
            val setting = ClanSetting.forName(key)
                ?: return Result.failure(IllegalArgumentException("Unknown clan setting: $key."))
            val typed: Any = when (setting.defaultValue) {
                is Int -> value as? Int
                is Long -> (value as? Long) ?: (value as? Int)?.toLong()
                is String -> value as? String
                else -> null
            } ?: return Result.failure(
                IllegalArgumentException("Clan setting $key expects a ${setting.defaultValue::class.simpleName} value."),
            )
            if (clan.getSetting(setting) != typed) delta[setting] = typed
        }
        return Result.success(delta)
    }


    fun checkName(actorClanName: String?, name: String): ClanDecision {
        if (actorClanName != null) return alreadyInClan(actorClanName)
        return validateName(name)
    }

    fun create(actorClanName: String?, name: String): ClanDecision = checkName(actorClanName, name)

    fun leave(clan: Clan, actor: String): ClanDecision {
        if (!isMember(clan, actor)) return ClanDecision.Denied("You aren't in a clan.")
        return ClanDecision.Allowed
    }

    fun acceptInvite(clan: Clan, actor: String, actorClanName: String?, inviter: String): ClanDecision {
        if (actorClanName != null) return alreadyInClan(actorClanName)
        if (actor in clan.bannedUsers) return ClanDecision.Denied("You have been banned from that clan.")
        if (!isMember(clan, inviter) || !ClanPermission.INVITE.hasPermission(clan, rankOf(clan, inviter))) {
            return ClanDecision.Denied("That player does not have permission to invite you to the clan.")
        }
        if (clan.members.size >= Clan.MAX_MEMBERS) return ClanDecision.Denied("That clan is full.")
        return ClanDecision.Allowed
    }

    fun removeMember(clan: Clan, actor: String, target: String): ClanDecision {
        val actorRank = rankOf(clan, actor)
        if (!isMember(clan, actor)) return ClanDecision.Denied(NOT_IN_CLAN)
        if (!isAdmin(actorRank)) return ClanDecision.Denied("Only clan admins can kick clan members.")
        if (!isMember(clan, target)) return ClanDecision.Denied(NOT_A_MEMBER)
        if (target == clan.leaderUsername) return ClanDecision.Denied("The clan owner cannot be kicked from the clan.")
        if (rankOf(clan, target).ordinal >= actorRank.ordinal) {
            return ClanDecision.Denied("You can only kick players with lesser rank than you.")
        }
        return ClanDecision.Allowed
    }

    fun updateMember(clan: Clan, actor: String, action: ClanAction.UpdateMember): ClanDecision {
        val actorRank = rankOf(clan, actor)
        if (!isMember(clan, actor)) return ClanDecision.Denied(NOT_IN_CLAN)
        if (!isAdmin(actorRank)) return ClanDecision.Denied("Only clan admins can edit ranks and jobs.")
        if (!isMember(clan, action.target)) return ClanDecision.Denied(NOT_A_MEMBER)
        val newRank = action.rank
        if (newRank == ClanRank.NONE || newRank == ClanRank.OWNER || newRank == ClanRank.JMOD) {
            return ClanDecision.Denied("That rank cannot be assigned.")
        }
        val targetRank = rankOf(clan, action.target)
        if (action.target == actor) {
            if (actorRank != ClanRank.OWNER || newRank == null) {
                return ClanDecision.Denied("You can only edit the rank of players with lesser rank than you.")
            }
            val hasDeputy = clan.members.any { (username, data) ->
                username != actor && data.rank == ClanRank.DEPUTY_OWNER
            }
            if (!hasDeputy) {
                return ClanDecision.Denied("You are currently the clan owner, you must at least have one deputy owner in the clan to demote yourself.")
            }
            return ClanDecision.Allowed
        }
        if (targetRank.ordinal >= actorRank.ordinal) {
            return ClanDecision.Denied("You can only edit the rank of players with lesser rank than you.")
        }
        if (newRank != null && newRank.ordinal >= actorRank.ordinal) {
            return ClanDecision.Denied("You cannot promote someone to your own rank or higher.")
        }
        return ClanDecision.Allowed
    }

    fun ban(clan: Clan, actor: String, target: String): ClanDecision {
        val actorRank = rankOf(clan, actor)
        if (!meetsChannelRank(actorRank, clan.ccKickRank)) {
            return ClanDecision.Denied("You do not have permission to ban users.")
        }
        if (target == actor) return ClanDecision.Denied("You cannot ban yourself.")
        if (target == clan.leaderUsername) return ClanDecision.Denied("The clan owner cannot be banned.")
        if (target in clan.bannedUsers) return ClanDecision.Denied("That player is already banned.")
        if (isMember(clan, target) && rankOf(clan, target).ordinal >= actorRank.ordinal) {
            return ClanDecision.Denied("You cannot ban that user.")
        }
        return ClanDecision.Allowed
    }

    fun unban(clan: Clan, actor: String, target: String): ClanDecision {
        if (!meetsChannelRank(rankOf(clan, actor), clan.ccKickRank)) {
            return ClanDecision.Denied("You do not have permission to unban users.")
        }
        if (target !in clan.bannedUsers) return ClanDecision.Denied("That player is not banned.")
        return ClanDecision.Allowed
    }

    fun updateSettings(clan: Clan, actor: String, settings: Map<String, Any>): ClanDecision {
        if (!isAdmin(rankOf(clan, actor))) return ClanDecision.Denied(ADMIN_ONLY_SETTINGS)
        val delta = settingsDelta(clan, settings)
        val error = delta.exceptionOrNull() ?: return ClanDecision.Allowed
        return ClanDecision.Denied(error.message ?: "Invalid clan settings.")
    }

    fun setChannelRanks(clan: Clan, actor: String, talkRank: ClanRank?, kickRank: ClanRank?): ClanDecision {
        if (!isAdmin(rankOf(clan, actor))) return ClanDecision.Denied(ADMIN_ONLY_SETTINGS)
        if (talkRank == ClanRank.JMOD || kickRank == ClanRank.JMOD) {
            return ClanDecision.Denied("That rank cannot be required.")
        }
        return ClanDecision.Allowed
    }

    fun setVar(clan: Clan, actor: String): ClanDecision {
        if (!isAdmin(rankOf(clan, actor))) {
            return ClanDecision.Denied("You do not have permission to change clan settings.")
        }
        return ClanDecision.Allowed
    }

    fun joinChannel(clan: Clan?, actor: String): ClanDecision {
        if (clan == null || !isMember(clan, actor)) return ClanDecision.Denied(NOT_IN_CLAN)
        return ClanDecision.Allowed
    }

    fun leaveChannel(clan: Clan?, channel: ClanChannelState?, actor: String): ClanDecision {
        if (clan == null || !isMember(clan, actor)) return ClanDecision.Denied(NOT_IN_CLAN)
        if (channel == null || actor !in channel) return ClanDecision.Denied(NOT_IN_CHANNEL)
        return ClanDecision.Allowed
    }

    fun joinGuestChannel(clan: Clan?, actor: String, tempBanned: Boolean): ClanDecision {
        if (clan == null) return ClanDecision.Denied("The clan chat you tried to join does not exist.")
        if (isMember(clan, actor)) return ClanDecision.Denied("You are already a member of that clan.")
        if (!settingOn(clan, ClanSetting.GUESTS_CAN_ENTER_CC)) return ClanDecision.Denied("This clan doesn't allow guests.")
        if (actor in clan.bannedUsers) return ClanDecision.Denied("You have been banned from that clan.")
        if (tempBanned) return ClanDecision.Denied("You are temporarily banned from this clan channel.")
        return ClanDecision.Allowed
    }

    fun leaveGuestChannel(channel: ClanChannelState?, actor: String): ClanDecision {
        if (channel == null || actor !in channel) return ClanDecision.Denied(NOT_IN_CHANNEL)
        return ClanDecision.Allowed
    }

    fun kickFromChannel(clan: Clan, channel: ClanChannelState?, actor: String, target: String): ClanDecision {
        val actorRank = rankOf(clan, actor)
        if (channel == null || actor !in channel) return ClanDecision.Denied(NOT_IN_CHANNEL)
        if (!meetsChannelRank(actorRank, clan.ccKickRank)) {
            return ClanDecision.Denied("You do not have permission to kick from this clan chat.")
        }
        if (target == actor) return ClanDecision.Denied("You cannot kick yourself.")
        if (target !in channel) return ClanDecision.Denied("That player is not in the clan chat.")
        if (isMember(clan, target) && rankOf(clan, target).ordinal >= actorRank.ordinal) {
            return ClanDecision.Denied("You cannot kick that user.")
        }
        return ClanDecision.Allowed
    }

    fun chat(clan: Clan, channel: ClanChannelState?, actor: String, muted: Boolean): ClanDecision {
        if (muted) return ClanDecision.Denied("You are muted.")
        if (channel == null || actor !in channel) return ClanDecision.Denied(NOT_IN_CHANNEL)
        val allowed = if (isMember(clan, actor)) {
            meetsChannelRank(rankOf(clan, actor), clan.ccChatRank)
        } else {
            settingOn(clan, ClanSetting.GUESTS_CAN_TALK_CC)
        }
        if (!allowed) return ClanDecision.Denied("You are not allowed to speak in that clan chat.")
        return ClanDecision.Allowed
    }

    private fun alreadyInClan(clanName: String) =
        ClanDecision.Denied("You are already in the clan $clanName. You must leave before joining a new one.")
}
