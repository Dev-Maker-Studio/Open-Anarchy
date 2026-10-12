package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.CheckEmailReplyOpcode
import org.darkan.core.net.prot.dto.CreateAccountReplyOpcode

@JvmInline value class CreateCheckEmailReply(val code: CheckEmailReplyOpcode) : ServerProt

@JvmInline value class CreateAccountReply(val code: CreateAccountReplyOpcode) : ServerProt

data class UpdateDob(val dob: Int, val quickChatOnly: Boolean) : ServerProt

@JvmInline value class DisplayNameChangeState(val state: Int) : ServerProt

data class LoyaltyUpdate(val balance: Int, val enabled: Boolean) : ServerProt

@JvmInline value class JCoinsUpdate(val amount: Int) : ServerProt

data class WorldLoginDetails(
    val rights: Int,
    val modLevel: Int,
    val quickChat: Boolean,
    val verifiedEmail: Boolean,
    val aBool7322: Boolean,
    val quickChatOnly: Boolean,
    val playerIndex: Int,
    val members: Boolean,
    val dob: Int,
    val memberWorld: Boolean,
    val worldName: String,
) : ServerProt

data class LobbyLoginDetails(
    val crown: Int,
    val displayName: String,
    val lastIpHash: Int,
    val worldLoginToken: String,
    val defaultWorldId: Int = DEFAULT_WORLD_ID,
    val defaultWorldAddress: String = DEFAULT_WORLD_ADDRESS,
) : ServerProt {
    companion object {
        const val DEFAULT_WORLD_ID = 0

        const val DEFAULT_WORLD_ADDRESS = "127.0.0.1"
    }
}
