package org.darkan.lobby.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.darkan.core.EnvVars
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val EDGE_HEADER = "x-edge-secret"
private const val CLIENT_IP_HEADER = "CF-Connecting-IP"
private const val LOGIN_WINDOW_MS = 60_000L
private const val LOGIN_ATTEMPTS = 10
private const val LIMITER_PRUNE_SIZE = 4096

private class Window(val start: Long, var count: Int)

private val attempts = ConcurrentHashMap<String, Window>()

fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

fun ApplicationCall.clientIp(): String =
    (if (EnvVars.lobbyEdgeSecret.isNotEmpty()) request.headers[CLIENT_IP_HEADER] else null) ?: request.local.remoteHost

fun loginAllowed(ip: String, now: Long = System.currentTimeMillis()): Boolean {
    if (attempts.size > LIMITER_PRUNE_SIZE) attempts.values.removeIf { now - it.start >= LOGIN_WINDOW_MS }
    val window = attempts.compute(ip) { _, current ->
        if (current == null || now - current.start >= LOGIN_WINDOW_MS) Window(now, 1) else current.also { it.count++ }
    }!!
    return window.count <= LOGIN_ATTEMPTS
}

fun Application.configureWebApi() {
    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.path().startsWith("/v1/")) return@intercept
        val secret = EnvVars.lobbyEdgeSecret
        if (secret.isNotEmpty() && !constantTimeEquals(call.request.headers[EDGE_HEADER].orEmpty(), secret)) {
            call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("message", "Forbidden") })
            return@intercept finish()
        }
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        if (call.request.httpMethod == HttpMethod.Options) {
            call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
            call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "content-type, authorization")
            call.response.headers.append(HttpHeaders.AccessControlMaxAge, "86400")
            call.respond(HttpStatusCode.NoContent)
            finish()
        }
    }
}
