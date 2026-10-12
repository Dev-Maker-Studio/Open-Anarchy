package org.darkan.core.deploy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import kotlin.time.Duration.Companion.seconds

class ArtifactWatcher(
    private val artifact: String,
    private val scope: CoroutineScope,
    private val onNewBuild: (String) -> Unit,
) {
    private val client = HttpClient(CIO)
    private var job: Job? = null
    private var ignored: String? = null

    fun start() {
        if (!EnvVars.autoUpdate) return
        if (EnvVars.gitlabAccessToken.isEmpty()) {
            logWarn("AUTO_UPDATE is on but GITLAB_ACCESS_TOKEN is not set; not watching for builds")
            return
        }
        val running = EnvVars.buildVersion.ifEmpty { "<local build>" }
        logInfo("Watching the ${EnvVars.autoUpdateChannel} channel for new $artifact builds every ${EnvVars.autoUpdatePollSeconds}s (running $running)")
        job = scope.launch {
            while (true) {
                delay(EnvVars.autoUpdatePollSeconds.seconds)
                val latest = try {
                    latestVersion()
                } catch (e: Exception) {
                    logWarn("Could not check for a new $artifact build: ${e.message}")
                    continue
                } ?: continue
                if (latest == EnvVars.buildVersion || latest == ignored) continue
                if (changesNothingHere(latest)) {
                    logInfo("Build $latest changes nothing that $artifact runs with; staying on $running")
                    ignored = latest
                    continue
                }
                logInfo("New $artifact build $latest detected (running $running)")
                onNewBuild(latest)
                break
            }
            client.close()
        }
    }

    fun stop() {
        job?.cancel()
        client.close()
    }

    private suspend fun latestVersion(): String? {
        val project = "$GITLAB_API/projects/${EnvVars.gitlabProjectId}"
        val url = if (EnvVars.autoUpdateChannel == RELEASE_CHANNEL)
            "$project/releases?order_by=released_at&sort=desc&per_page=1"
        else
            "$project/packages/maven/org/darkan/$artifact/maven-metadata.xml"
        val response = client.get(url) { header("PRIVATE-TOKEN", EnvVars.gitlabAccessToken) }
        if (!response.status.isSuccess()) {
            if (response.status.value != 404) logWarn("Registry answered ${response.status} for $artifact")
            return null
        }
        val body = response.bodyAsText()
        return if (EnvVars.autoUpdateChannel == RELEASE_CHANNEL) newestReleaseTag(body) else newestOnChannel(body, EnvVars.autoUpdateChannel)
    }

    private suspend fun changesNothingHere(version: String): Boolean {
        if (EnvVars.checkoutCommit.isEmpty()) return false
        val project = "$GITLAB_API/projects/${EnvVars.gitlabProjectId}"
        val ref = if (EnvVars.autoUpdateChannel == RELEASE_CHANNEL) version else EnvVars.autoUpdateChannel
        val candidate = treeIds(fetch("$project/repository/tree?ref=$ref&per_page=100") ?: return false)
        val running = treeIds(fetch("$project/repository/tree?ref=${EnvVars.checkoutCommit}&per_page=100") ?: return false)
        return inputsOf(artifact).all { candidate[it] != null && candidate[it] == running[it] }
    }

    private suspend fun fetch(url: String): String? {
        val response = client.get(url) { header("PRIVATE-TOKEN", EnvVars.gitlabAccessToken) }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    companion object {
        private const val GITLAB_API = "https://gitlab.com/api/v4"
        private val VERSION = Regex("<version>([^<]+)</version>")

        const val RELEASE_CHANNEL = "release"
        private val TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

        fun newestReleaseTag(releasesJson: String): String? = TAG_NAME.find(releasesJson)?.groupValues?.get(1)

        private val SHARED_INPUTS = listOf("core", "buildSrc", "gradle", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "cache-source")

        fun inputsOf(artifact: String): List<String> = SHARED_INPUTS + when (artifact) {
            "world-server" -> listOf("world", "data")
            else -> listOf(artifact.removeSuffix("-server"))
        }

        fun treeIds(treeJson: String): Map<String, String> =
            Json.parseToJsonElement(treeJson).jsonArray.map { it.jsonObject }
                .associate { it.getValue("name").jsonPrimitive.content to it.getValue("id").jsonPrimitive.content }

        fun newestOnChannel(metadataXml: String, channel: String): String? =
            VERSION.findAll(metadataXml)
                .map { it.groupValues[1].trim() }
                .filter { it.contains("-$channel-") }
                .maxByOrNull { it.substringAfterLast('-').toLongOrNull() ?: -1L }
    }
}
