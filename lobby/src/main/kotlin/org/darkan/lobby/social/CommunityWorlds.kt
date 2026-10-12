package org.darkan.lobby.social

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.mongo.collections.Accounts
import org.darkan.core.net.login.LoginToken
import org.darkan.core.social.gateway.GatewayPlayerPresence
import org.darkan.core.social.gateway.LoginRefusal
import org.darkan.core.social.gateway.LoginTokenVerdict
import org.darkan.core.social.gateway.VerifyLoginToken
import org.darkan.core.worldlist.WorldMetadata
import java.util.concurrent.ConcurrentHashMap

class CommunityWorlds(
    private val enabled: Boolean = EnvVars.debug,
    private val communityToken: String = EnvVars.communityWorldToken,
    private val officialToken: String = EnvVars.socialGatewayToken,
    private val officialWorldMax: Int = EnvVars.officialWorldMax,
    private val localWorldId: Int = EnvVars.localWorldId,
    private val loginTokenSecret: String = EnvVars.worldLoginTokenSecret,
) {
    enum class Tier { OFFICIAL, COMMUNITY }

    private data class Admission(val worldId: Int, val displayName: String, val rightsCrown: Int)

    private val admissions = ConcurrentHashMap<String, Admission>()

    fun tierFor(token: String): Tier? = when {
        token == officialToken && officialToken != communityToken -> Tier.OFFICIAL
        enabled && token == communityToken -> Tier.COMMUNITY
        else -> null
    }

    fun refusalFor(world: WorldMetadata): String? = when {
        world.number <= officialWorldMax -> "Worlds 1-$officialWorldMax are reserved for official worlds"
        world.number == localWorldId -> "World ${world.number} is reserved for the local development world"
        world.isLobby -> "World ${world.number} is a lobby number"
        else -> null
    }

    suspend fun verify(worldId: Int, request: VerifyLoginToken): LoginTokenVerdict {
        val now = System.currentTimeMillis()
        val verified = LoginToken.verify(request.token, now, loginTokenSecret)
            ?: return refuse(worldId, request, LoginRefusal.TOKEN)
        val account = Accounts.find(verified.username)
            ?: return refuse(worldId, request, LoginRefusal.NO_ACCOUNT)
        if (account.checkBanned()) return refuse(worldId, request, LoginRefusal.BANNED)
        if (account.mustChooseName()) return refuse(worldId, request, LoginRefusal.NAME_REQUIRED)
        admissions[account.username] = Admission(worldId, account.displayName, account.rights.crown)
        return LoginTokenVerdict(request.requestId, account.username, account.displayName, account.view())
    }

    private fun refuse(worldId: Int, request: VerifyLoginToken, refusal: LoginRefusal): LoginTokenVerdict {
        logInfo("Refused world $worldId login for ${request.username}: $refusal")
        return LoginTokenVerdict(request.requestId, refusal = refusal)
    }

    fun admitted(worldId: Int, player: GatewayPlayerPresence): GatewayPlayerPresence? {
        val username = player.username.formatPlayerNameForProtocol()
        val admission = admissions[username]?.takeIf { it.worldId == worldId } ?: return null
        return player.copy(username = username, displayName = admission.displayName, rightsCrown = admission.rightsCrown)
    }

    fun forget(username: String, worldId: Int) {
        admissions.computeIfPresent(username) { _, admission -> admission.takeUnless { it.worldId == worldId } }
    }
}
