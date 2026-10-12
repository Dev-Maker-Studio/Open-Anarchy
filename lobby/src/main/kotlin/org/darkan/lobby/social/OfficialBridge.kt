package org.darkan.lobby.social

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import org.darkan.core.social.FriendsChatMember
import org.darkan.core.social.PublicChatMessage
import org.darkan.core.social.SocialAction
import org.darkan.core.social.SocialEvent
import org.darkan.core.social.SocialJson
import java.util.concurrent.atomic.AtomicReference

private class Rejected : Exception()

object OfficialBridge {
    const val RELAY_PREFIX = "[make]"
    private const val KEEP_ALIVE_MS = 5000L
    private const val RECONNECT_MAX_MS = 300_000L

    private val client = HttpClient(CIO) { install(WebSockets) }
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val members = AtomicReference<List<FriendsChatMember>>(emptyList())

    val enabled: Boolean get() = EnvVars.officialUsername.isNotBlank() && EnvVars.officialPassword.isNotBlank()

    fun members(): List<FriendsChatMember> = members.get()

    fun start(scope: CoroutineScope) {
        if (!enabled) {
            logInfo("OfficialBridge: no official credentials configured")
            return
        }
        scope.launch(Dispatchers.IO + SupervisorJob()) { connectLoop() }
    }

    fun say(text: String) {
        if (!enabled) return
        outbound.trySend(SocialJson.json.encodeToString(SocialAction.serializer(), SocialAction.SayInFriendsChat(PublicChatMessage(text))))
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (true) {
            try {
                val token = login()
                attempt = 0
                run(token)
            } catch (e: Rejected) {
                logWarn("OfficialBridge: the official lobby rejected the credentials, bridge stopped until the lobby restarts")
                return
            } catch (e: Exception) {
                logWarn("OfficialBridge: ${e.javaClass.simpleName} ${e.message}")
            }
            members.set(emptyList())
            SocialGateway.officialMembersChanged()
            delay(minOf(5000L * (1L shl minOf(attempt++, 6)), RECONNECT_MAX_MS))
        }
    }

    private suspend fun login(): String {
        val body = JsonObject(mapOf(
            "username" to JsonPrimitive(EnvVars.officialUsername),
            "password" to JsonPrimitive(EnvVars.officialPassword),
        )).toString()
        val response = client.post("${EnvVars.officialApi.trimEnd('/')}/v1/accounts/login") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.value == 401) throw Rejected()
        val text = response.bodyAsText()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: error("login failed: status ${response.status.value}")
        val data = (root["data"] as? JsonObject) ?: root
        return data["access_token"]?.jsonPrimitive?.content
            ?: error("login failed: status ${response.status.value} ${(root["message"] ?: root["error"])?.toString().orEmpty().take(120)}")
    }

    private suspend fun run(token: String) {
        val url = EnvVars.officialApi.trimEnd('/').replaceFirst("http", "ws") + "/v1/social/ws?token=$token"
        client.webSocket(url) {
            logInfo("OfficialBridge: connected to ${EnvVars.officialApi}")
            val ticker = launch {
                while (isActive) {
                    delay(KEEP_ALIVE_MS)
                    send(Frame.Text("0"))
                }
            }
            val sender = launch { for (text in outbound) send(Frame.Text(text)) }
            try {
                for (frame in incoming) if (frame is Frame.Text) handle(frame.readText())
            } finally {
                ticker.cancel()
                sender.cancel()
            }
        }
    }

    private suspend fun handle(text: String) {
        val event = runCatching { SocialJson.json.decodeFromString(SocialEvent.serializer(), text) }.getOrNull() ?: return
        when (event) {
            is SocialEvent.Snapshot -> updateMembers(event.friendsChat?.members)
            is SocialEvent.FriendsChatChanged -> updateMembers(event.channel?.members)
            is SocialEvent.FriendsChatLine -> {
                val line = event.message.message
                if (line.startsWith(RELAY_PREFIX)) return
                SocialGateway.officialLine(event.speaker.username, event.speaker.displayName, line)
            }
            else -> Unit
        }
    }

    private suspend fun updateMembers(list: List<FriendsChatMember>?) {
        members.set(list.orEmpty().filter { it.username != EnvVars.officialUsername.lowercase() })
        SocialGateway.officialMembersChanged()
    }
}
