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
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import kotlin.time.Duration.Companion.seconds

/**
 * Watches the CI package registry for a newer build of [artifact] (`lobby-server` / `world-server`)
 * on the `AUTO_UPDATE_CHANNEL` branch than the one this process was started from, and calls
 * [onNewBuild] exactly once when one appears. Versions are `<version>-<branch>-<pipeline id>`, so
 * the newest build on a channel is the one with the highest pipeline id.
 * What happens next is the caller's business - the servers use it to start the same system-update
 * countdown an admin's `::update` triggers, after which the run script's restart loop pulls the build.
 *
 * Only runs when `AUTO_UPDATE=true`; production units carry the registry token too and must never
 * self-update.
 */
class ArtifactWatcher(
    private val artifact: String,
    private val scope: CoroutineScope,
    private val onNewBuild: (String) -> Unit,
) {
    private val client = HttpClient(CIO)
    private var job: Job? = null

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
                if (latest == EnvVars.buildVersion) continue
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

    /**
     * The newest build on the configured channel: the newest GitLab Release's tag for the `release`
     * channel, otherwise the highest-pipeline package published from that branch.
     */
    private suspend fun latestVersion(): String? {
        val project = "$GITLAB_API/projects/${EnvVars.gitlabProjectId}"
        val url = if (EnvVars.autoUpdateChannel == RELEASE_CHANNEL)
            "$project/releases?order_by=released_at&sort=desc&per_page=1"
        else
            "$project/packages/maven/org/darkan/$artifact/maven-metadata.xml"
        val response = client.get(url) { header("PRIVATE-TOKEN", EnvVars.gitlabAccessToken) }
        if (!response.status.isSuccess()) {
            // 404 simply means nothing has been published yet.
            if (response.status.value != 404) logWarn("Registry answered ${response.status} for $artifact")
            return null
        }
        val body = response.bodyAsText()
        return if (EnvVars.autoUpdateChannel == RELEASE_CHANNEL) newestReleaseTag(body) else newestOnChannel(body, EnvVars.autoUpdateChannel)
    }

    companion object {
        private const val GITLAB_API = "https://gitlab.com/api/v4"
        private val VERSION = Regex("<version>([^<]+)</version>")

        const val RELEASE_CHANNEL = "release"
        private val TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

        /** The tag of the first (newest) release in a GitLab releases listing. */
        fun newestReleaseTag(releasesJson: String): String? = TAG_NAME.find(releasesJson)?.groupValues?.get(1)

        /** Picks the highest-pipeline version tagged `-<channel>-` out of a maven-metadata document. */
        fun newestOnChannel(metadataXml: String, channel: String): String? =
            VERSION.findAll(metadataXml)
                .map { it.groupValues[1].trim() }
                .filter { it.contains("-$channel-") }
                .maxByOrNull { it.substringAfterLast('-').toLongOrNull() ?: -1L }
    }
}
