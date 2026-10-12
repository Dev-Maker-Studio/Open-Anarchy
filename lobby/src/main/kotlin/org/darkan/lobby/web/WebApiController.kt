package org.darkan.lobby.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.darkan.core.EnvVars
import org.darkan.core.crypt.Crypto
import org.darkan.core.model.Account
import org.darkan.core.mongo.collections.Accounts
import org.darkan.core.net.login.LoginToken
import org.darkan.lobby.Lobby
import org.darkan.lobby.server.passwordMatches
import org.darkan.lobby.social.PlayerSocket
import java.util.concurrent.TimeUnit

private const val CLOSE_INVALID_TOKEN: Short = 4001
private const val CLOSE_NOT_READY: Short = 4010
private const val INVALID_CREDENTIALS = "Invalid username or password"

@Serializable
private data class LoginRequest(val username: String = "", val password: String = "")

private val decoyHash by lazy { Crypto.hashPasswordArgon2("decoy-password") }

private fun message(text: String): JsonObject = buildJsonObject { put("message", text) }

private suspend fun authenticate(request: LoginRequest): Account? {
    val account = Accounts.find(request.username)?.takeIf { !it.bot }
    if (account == null) {
        Crypto.verifyPasswordArgon2(request.password, decoyHash)
        return null
    }
    return account.takeIf { it.passwordMatches(request.password) }
}

object WebApiController {
    fun registerRoutes(routing: Routing) {
        routing.route("/v1") {
            post("/accounts/login") {
                if (!loginAllowed(call.clientIp())) return@post call.respond(HttpStatusCode.TooManyRequests, message("Too many attempts, try again later"))
                val request = runCatching { call.receive<LoginRequest>() }.getOrNull()
                    ?.takeIf { it.username.isNotBlank() && it.password.isNotEmpty() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, message("Username and password are required"))
                val account = authenticate(request) ?: return@post call.respond(HttpStatusCode.Unauthorized, message(INVALID_CREDENTIALS))
                val token = LoginToken.issue(
                    account.username,
                    System.currentTimeMillis(),
                    TimeUnit.HOURS.toMillis(EnvVars.lobbyApiTokenTtlHours),
                    EnvVars.lobbyApiTokenSecret,
                )
                call.respond(buildJsonObject { put("data", buildJsonObject { put("access_token", token) }) })
            }

            webSocket("/social/ws") {
                if (!Lobby.ready) return@webSocket close(CloseReason(CLOSE_NOT_READY, "Lobby unavailable"))
                val verified = call.request.queryParameters["token"]?.let { LoginToken.verify(it, System.currentTimeMillis(), EnvVars.lobbyApiTokenSecret) }
                val account = verified?.let { Accounts.find(it.username) }?.takeIf { !it.bot }
                    ?: return@webSocket close(CloseReason(CLOSE_INVALID_TOKEN, "Invalid token"))
                PlayerSocket.serve(account, this)
            }
        }
    }
}
