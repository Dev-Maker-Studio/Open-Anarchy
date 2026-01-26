package org.darkan.lobby.web

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.darkan.core.EnvVars
import org.darkan.core.social.gateway.AnnouncementBroadcast
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.controller.AccountsController
import org.darkan.lobby.web.controller.WorldListController

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(buildJsonObject {
                put("status", "OK")
                put("version", this.javaClass.`package`.implementationVersion ?: "dev")
                put("timestamp", System.currentTimeMillis())
            })
        }

        webSocket("/social/ws") {
            SocialGateway.handleWorldSocket(this)
        }

        post("/admin/announcements") {
            val auth = call.request.headers["Authorization"]
            val apiKey = call.request.headers["X-API-Key"]
            val ok = auth == "Bearer ${EnvVars.apiKey}" || apiKey == EnvVars.apiKey
            if (!ok) {
                return@post call.respond(buildJsonObject { put("error", "Unauthorized") })
            }
            val req = call.receive<AnnouncementBroadcast>()
            SocialGateway.broadcastAnnouncement(req)
            call.respond(buildJsonObject { put("ok", true) })
        }

        AccountsController.registerRoutes(this)
        WorldListController.registerRoutes(this)
    }
}