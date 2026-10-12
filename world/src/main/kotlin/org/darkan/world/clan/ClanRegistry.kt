package org.darkan.world.clan

import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.darkan.core.Logger.logWarn
import org.darkan.core.clan.ClanAction
import org.darkan.core.clan.ClanChannelState
import org.darkan.core.clan.ClanDecision
import org.darkan.core.clan.ClanPackets
import org.darkan.core.clan.ClanRules
import org.darkan.core.formatPlayerNameForDisplay
import org.darkan.core.model.Clan
import org.darkan.core.model.ClanMemberData
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.packet.ClanSettingsFull
import org.darkan.core.net.prot.packet.VarclanDisable
import org.darkan.core.net.prot.packet.VarclanEnable
import org.darkan.core.social.gateway.ClanChannelUpdate
import org.darkan.core.social.gateway.ClanChatMessage
import org.darkan.core.social.gateway.ClanCommand
import org.darkan.core.social.gateway.ClanCommandResult
import org.darkan.core.social.gateway.ClanDeleted
import org.darkan.core.social.gateway.ClanFetch
import org.darkan.core.social.gateway.ClanMembership
import org.darkan.core.social.gateway.ClanQuickChatMessage
import org.darkan.core.social.gateway.ClanSnapshot
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import org.darkan.world.server.WorldServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

object ClanRegistry {

    const val REQUEST_TIMEOUT_TICKS = 17

    private const val TIMEOUT_MESSAGE = "The clan service did not respond. Please try again."
    private const val UNAVAILABLE_MESSAGE = "The clan service is currently unavailable. Please try again later."

    private const val CLAN_SETTINGS_INTERFACE = 1096

    private val clans = ConcurrentHashMap<String, Clan>()
    private val channels = ConcurrentHashMap<String, ClanChannelState>()
    private val fetching = ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<Clan?>>>()
    private val pending = ConcurrentHashMap<Int, Request>()
    private val nextRequestId = AtomicInteger(1)

    private class Request(
        val username: String,
        val action: ClanAction,
        val callback: Consumer<ClanCommandResult>?,
    )

    var transport: suspend (SocialGatewayWireMessage) -> Boolean = { WorldServer.sendToGateway(it) }

    var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    @JvmStatic
    fun clan(name: String?): Clan? = name?.let { clans[it] }

    @JvmStatic
    fun clanOrFetch(name: String?): Clan? {
        if (name.isNullOrEmpty()) return null
        val known = clans[name]
        if (known != null) return known
        fetch(name, null)
        return null
    }

    @JvmStatic
    fun fetch(name: String?, callback: Consumer<Clan?>?) {
        if (name.isNullOrEmpty()) {
            callback?.accept(null)
            return
        }
        val known = clans[name]
        if (known != null) {
            callback?.accept(known)
            return
        }
        var first = false
        fetching.compute(name) { _, existing ->
            val waiters = existing ?: CopyOnWriteArrayList<Consumer<Clan?>>().also { first = true }
            if (callback != null) waiters.add(callback)
            waiters
        }
        if (first) dispatch(ClanFetch(name))
    }

    @JvmStatic
    fun channel(clanName: String?): ClanChannelState? = clanName?.let { channels[it] }

    @JvmStatic
    fun settingsFull(clan: Clan, main: Boolean): ClanSettingsFull =
        ClanPackets.settingsFull(clan, main, 0, ::displayNameOf)

    @JvmStatic
    fun orderedMembers(clan: Clan): List<String> = clan.members.entries
        .sortedWith(compareBy<Map.Entry<String, ClanMemberData>> { it.value.joinDate }.thenBy { it.key })
        .map { it.key }

    @JvmStatic
    fun orderedBans(clan: Clan): List<String> = clan.bannedUsers.sorted()

    fun clear() {
        clans.clear()
        channels.clear()
        pending.clear()
        val orphaned = fetching.keys.toList().mapNotNull { fetching.remove(it) }.flatten()
        if (orphaned.isEmpty()) return
        WorldTasks.schedule(0) { orphaned.forEach { runCatching { it.accept(null) } } }
    }


    @JvmStatic
    fun send(player: Player, action: ClanAction): Boolean = send(player, action, null)

    @JvmStatic
    fun send(player: Player, action: ClanAction, callback: Consumer<ClanCommandResult>?): Boolean {
        val decision = precheck(player, action)
        if (decision is ClanDecision.Denied) {
            player.sendMessage(decision.message)
            return false
        }
        val username = player.username
        val requestId = nextRequestId.getAndIncrement()
        pending[requestId] = Request(username, action, callback)
        WorldTasks.schedule(REQUEST_TIMEOUT_TICKS) { fail(requestId, TIMEOUT_MESSAGE) }
        dispatch(ClanCommand(username = username, requestId = requestId, action = action)) {
            fail(requestId, UNAVAILABLE_MESSAGE)
        }
        return true
    }

    private fun fail(requestId: Int, message: String) {
        val request = pending.remove(requestId) ?: return
        deliver(
            ClanCommandResult(
                username = request.username,
                requestId = requestId,
                action = request.action.serialName,
                ok = false,
                message = message,
            ),
            request,
        )
    }

    fun precheck(player: Player, action: ClanAction): ClanDecision {
        val actor = player.username
        val social = player.social
        val own = clan(social.clanName)
        return when (action) {
            is ClanAction.CheckName -> ClanRules.checkName(social.clanName, action.name)
            is ClanAction.Create -> ClanRules.create(social.clanName, action.name)
            is ClanAction.Leave -> own?.let { ClanRules.leave(it, actor) }
                ?: if (social.clanName == null) ClanDecision.Denied("You aren't in a clan.") else ClanDecision.Allowed
            is ClanAction.AcceptInvite -> clan(action.clanName)
                ?.let { ClanRules.acceptInvite(it, actor, social.clanName, action.inviter) }
                ?: ClanDecision.Allowed
            is ClanAction.RemoveMember -> own?.let { ClanRules.removeMember(it, actor, action.target) }
                ?: ClanDecision.Allowed
            is ClanAction.UpdateMember -> own?.let { ClanRules.updateMember(it, actor, action) }
                ?: ClanDecision.Allowed
            is ClanAction.Ban -> own?.let { ClanRules.ban(it, actor, action.target) } ?: ClanDecision.Allowed
            is ClanAction.Unban -> own?.let { ClanRules.unban(it, actor, action.target) } ?: ClanDecision.Allowed
            is ClanAction.UpdateSettings -> own?.let { ClanRules.updateSettings(it, actor, action.settings) }
                ?: ClanDecision.Allowed
            is ClanAction.SetChannelRanks -> own?.let {
                ClanRules.setChannelRanks(it, actor, action.talkRank, action.kickRank)
            } ?: ClanDecision.Allowed
            is ClanAction.SetVar -> own?.let { ClanRules.setVar(it, actor) } ?: ClanDecision.Allowed
            is ClanAction.JoinChannel ->
                if (own == null && social.clanName != null) ClanDecision.Allowed
                else ClanRules.joinChannel(own, actor)
            is ClanAction.LeaveChannel ->
                if (own == null && social.clanName != null) ClanDecision.Allowed
                else ClanRules.leaveChannel(own, channel(social.clanName), actor)
            is ClanAction.JoinGuestChannel -> clan(action.clanName)
                ?.let { ClanRules.joinGuestChannel(it, actor, tempBanned = false) }
                ?: ClanDecision.Allowed
            is ClanAction.LeaveGuestChannel ->
                if (social.guestedClanChat == null) ClanRules.leaveGuestChannel(null, actor)
                else ClanDecision.Allowed
            is ClanAction.KickFromChannel -> own
                ?.let { ClanRules.kickFromChannel(it, channel(it.name), actor, action.target) }
                ?: ClanDecision.Allowed
            is ClanAction.Chat -> chatDecision(player, action.guest)
            is ClanAction.QuickChat -> chatDecision(player, action.guest)
        }
    }

    private fun chatDecision(player: Player, guest: Boolean): ClanDecision {
        val social = player.social
        val name = if (guest) social.guestedClanChat else social.clanName
        val clan = clan(name) ?: return ClanDecision.Allowed
        return ClanRules.chat(clan, channel(clan.name), player.username, player.account.checkMuted())
    }

    private fun dispatch(msg: SocialGatewayWireMessage, onDropped: () -> Unit = {}) {
        scope.launch {
            val delivered = try {
                transport(msg)
            } catch (e: Exception) {
                logWarn("ClanRegistry failed to send ${msg::class.simpleName}", e)
                false
            }
            if (!delivered) onDropped()
        }
    }


    fun handle(msg: SocialGatewayWireMessage): Boolean {
        when (msg) {
            is ClanSnapshot -> onSnapshot(msg.clan)
            is ClanDeleted -> onDeleted(msg.clanName)
            is ClanChannelUpdate -> onChannelUpdate(msg.state)
            is ClanMembership -> onMembership(msg)
            is ClanChatMessage -> onChannelLine(msg.clanName) { guest ->
                ClanPackets.chatMessage(guest, msg.rights, msg.sender, msg.message.message)
            }
            is ClanQuickChatMessage -> onChannelLine(msg.clanName) { guest ->
                ClanPackets.quickChatMessage(guest, msg.rights, msg.sender, msg.message)
            }
            is ClanCommandResult -> onResult(msg)
            else -> return false
        }
        return true
    }

    private fun onSnapshot(clan: Clan) {
        val name = clan.name
        if (name.isEmpty()) {
            logWarn("ClanRegistry received a ClanSnapshot with no clan name")
            return
        }
        clans[name] = clan
        val waiters = fetching.remove(name)
        WorldTasks.schedule(0) {
            waiters?.forEach { runCatching { it.accept(clan) } }
            val members = ClanPackets.settingsFull(clan, main = true, displayName = ::displayNameOf)
            val guestView = ClanPackets.settingsFull(clan, main = false, displayName = ::displayNameOf)
            val vars = ClanPackets.clanVarPackets(clan).filter { it !is VarclanEnable }
            for (username in clan.members.keys + clan.leaderUsername) {
                val player = localPlayer(username) ?: continue
                player.session.queuePacket(members)
                for (varPacket in vars) player.session.queuePacket(varPacket)
                player.appearance.generateAppearanceData()
                if (player.interfaceManager.topOpen(CLAN_SETTINGS_INTERFACE))
                    WorldTasks.delay(0) { player.packets.sendRunScript(4295) }
            }
            for (player in localPlayers()) {
                if (player.social.guestedClanChat != name) continue
                player.session.queuePacket(guestView)
            }
        }
    }

    private fun onDeleted(clanName: String) {
        clans.remove(clanName)
        channels.remove(clanName)
        val waiters = fetching.remove(clanName) ?: return
        WorldTasks.schedule(0) { waiters.forEach { runCatching { it.accept(null) } } }
    }

    private fun onChannelUpdate(state: ClanChannelState) {
        val previous = channels.put(state.clanName, state)
        val affected = LinkedHashSet<String>()
        previous?.chatters?.forEach { affected.add(it.username) }
        state.chatters.forEach { affected.add(it.username) }
        val clan = clanOrFetch(state.clanName)
        WorldTasks.schedule(0) {
            for (username in affected) {
                val player = localPlayer(username) ?: continue
                val was = previous != null && username in previous
                val now = username in state
                val main = player.social.clanName == state.clanName
                when {
                    !was && now -> {
                        player.session.queuePacket(ClanPackets.channelFull(state, main))
                        if (clan != null) {
                            for (varPacket in ClanPackets.clanVarPackets(clan)) player.session.queuePacket(varPacket)
                        } else {
                            player.session.queuePacket(VarclanEnable())
                        }
                    }
                    was && !now -> {
                        player.session.queuePacket(ClanPackets.channelLeft(main))
                        player.session.queuePacket(VarclanDisable())
                    }
                    now -> player.session.queuePacket(ClanPackets.channelFull(state, main))
                }
            }
        }
    }

    private fun onMembership(msg: ClanMembership) {
        if (msg.clanName != null) clanOrFetch(msg.clanName)
        WorldServer.onWorldThread(msg.username) { player ->
            val social = player.social
            val left = social.clanName != null && msg.clanName == null
            social.clanName = msg.clanName
            social.connectedToClan = msg.inChannel
            social.guestedClanChat = msg.guestChannel
            if (msg.clanName == null) {
                player.session.queuePacket(ClanPackets.settingsLeft(main = true))
                if (left) player.appearance.generateAppearanceData()
            }
        }
    }

    private fun onChannelLine(clanName: String, line: (guest: Boolean) -> ServerProt) {
        val state = channels[clanName] ?: return
        WorldTasks.schedule(0) {
            for (chatter in state.chatters) {
                val player = localPlayer(chatter.username) ?: continue
                player.session.queuePacket(line(player.social.clanName != clanName))
            }
        }
    }

    private fun onResult(result: ClanCommandResult) = deliver(result, pending.remove(result.requestId))

    private fun deliver(result: ClanCommandResult, request: Request?) {
        WorldTasks.schedule(0) {
            val player = localPlayer(result.username)
            val message = result.message
            if (player != null && !message.isNullOrBlank()) player.sendMessage(message)
            request?.callback?.let { runCatching { it.accept(result) } }
        }
    }


    private fun displayNameOf(username: String): String =
        World.getPlayerByUsername(username)?.displayName ?: username.formatPlayerNameForDisplay()

    private fun localPlayer(username: String): Player? {
        val player = World.getPlayerByUsername(username) ?: return null
        if (!player.hasStarted() || player.hasFinished()) return null
        return player
    }

    private fun localPlayers(): List<Player> = World.players.filterNotNull()
        .filter { it.hasStarted() && !it.hasFinished() }
}
