package org.darkan.core

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import java.io.File

object EnvVars {
    private val dotenv: Dotenv = dotenv {
        ignoreIfMissing = true
    }

    private fun tileEnv(key: String): IntArray? {
        val parts = dotenv.get(key, null)?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        if (parts.size != 3 || parts.any { it == null }) return null
        return parts.map { it!! }.toIntArray()
    }

    private const val CACHE_SOURCE_SUBMODULE = "./cache-source"

    //server settings
    val debug: Boolean = dotenv.get("DEBUG", "false").toBooleanStrict()
    val members: Boolean = dotenv.get("MEMBERS", "true").toBooleanStrict()
    val serverName: String = dotenv.get("SERVER_NAME", "Darkan")
    val multiLogWhitelist: String = dotenv.get("MULTILOG_WHITELIST", "localhost,127.0.0.1,::1,0:0:0:0:0:0:0:1")
    val multiLogLimit: Int = dotenv.get("MULTILOG_LIMIT", "3").toInt()
    val botIpWhitelist: String = dotenv.get("BOT_IP_WHITELIST", "")
    val trustedProxies: String = dotenv.get("TRUSTED_PROXIES", "")
    val js5ServerToken: String = dotenv.get("JS5_SERVER_TOKEN", "ev9+VAp5/tMKeNR/7MOuH6lKWS+rGkHK")
    val loginServerToken: String = dotenv.get("LOGIN_SERVER_TOKEN", "wwGlrZHF5gKN6D3mDdihco3oPeYN2KFybL9hUUFqOvk")
    val clientKey: String = dotenv.get("CLIENT_KEY", "29EDD9FDC775629058FBBF106C5B0E0A3A8028FE0037D1737B8EC3EA2F4E8B8FD6F54EF2F4E65862")
    val js5RsaModulus: String = dotenv.get("RSA_JS5_MODULUS", "117525752735533423040644219776209926525585489242340044375332234679786347045466594509203355398209678968096551043842518449703703964361320462967286756268851663407950384008240524570966471744081769815157355561961607944067477858512067883877129283799853947605780903005188603658779539811385137666347647991072028080201")
    val js5RsaExponent: String = dotenv.get("RSA_JS5_EXPONENT", "45769714620275867926001532284788836149236590657678028481492967724067121406860916606777808563536714166085238449913676219414798301454048585933351540049893959827785868628572203706265915752274580525376826724019249600701154664022299724373133271944352291456503171589594996734220177420375212353960806722706846977073")
    val worldRsaModulus: String = dotenv.get("RSA_WORLD_MODULUS", js5RsaModulus)
    val worldRsaExponent: String = dotenv.get("RSA_WORLD_EXPONENT", js5RsaExponent)
    val worldLoginTokenSecret: String = dotenv.get("WORLD_LOGIN_TOKEN_SECRET", loginServerToken)
    val worldLoginTokenTtlHours: Long = dotenv.get("WORLD_LOGIN_TOKEN_TTL_HOURS", "12").toLong()
    //val js5PrefetchKeys: String = dotenv.get("JS5_PREFETCH_KEYS", "1441,78700,44880,39771,363186,44375,0,16140,7316,271148,810710,216189,379672,454149,933950,21006,25367,17247,1244,1,14856,1494,119,882901,1818764,3963,3618")
    val lobbyPort: Int = dotenv.get("LOBBY_PORT", "43594").toInt()
    val worldPort: Int = dotenv.get("WORLD_PORT", "43595").toInt()
    val lobbyApiPort: Int = dotenv.get("LOBBY_API_PORT", "4040").toInt()
    //World number to list as "localhost" so developers can reach a world running on their own machine (0 = none)
    val localWorldId: Int = dotenv.get("LOCAL_WORLD_ID", "0").toInt()
    val apiKey: String = dotenv.get("API_KEY", "TEST_API_KEY")
    // The one account allowed to run the lobby's owner-tier commands (password recovery and the like),
    // and, while DEBUG is on, the only account allowed any administrative lobby feature at all: a dev
    // environment lets people grant themselves rights on their own worlds, so rights alone are no gate.
    val lobbyOwner: String = dotenv.get("LOBBY_OWNER", "trent").lowercase().replace(" ", "_")
    val worldOwner: String = dotenv.get("WORLD_OWNER", "").lowercase().replace(" ", "_")
    // The prepacked cache named in the environment; only served when no source tree is configured.
    val prepackedCachePath: String = dotenv.get("CACHE_PATH", "../cache/")
    // A cache source tree (docs/cache-source.md). When set, the server builds it into CACHE_BUILD_PATH on
    // startup and serves that; when unset, CACHE_PATH names a prepacked cache, and a clone with the
    // cache-source submodule but no prepacked cache serves the submodule.
    val cacheSourcePath: String? = dotenv.get("CACHE_SOURCE_PATH", null)?.takeIf { it.isNotBlank() }
        ?: CACHE_SOURCE_SUBMODULE.takeIf { !File(prepackedCachePath).isDirectory && File(it, "cache.json").isFile }
    val cacheBuildPath: String = dotenv.get("CACHE_BUILD_PATH", "./data/cache")
    // Generate flat grass for every map square the source tree has no terrain file for, and save each square into the
    // tree the first time a player explores it (org.darkan.core.cache.MapMaterializer).
    val generateMaps: Boolean = dotenv.get("CACHE_GENERATE_MAPS", "false").toBooleanStrict()
    // Dev subsample: build only a square of this many tiles around the spawn (4000 is 63 x 63 map squares) and pack every
    // other map square as empty void, which makes a cache build take seconds. 0 builds the whole world. Use a separate
    // CACHE_BUILD_PATH for it, so a subsample is never mistaken for a full build.
    val generateSize: Int = dotenv.get("CACHE_GENERATE_SIZE", "0").toInt()
    // Threads the cache packer may use; generating a world defaults to one so that it never takes the whole machine.
    val cachePackThreads: Int = dotenv.get("CACHE_PACK_THREADS", null)?.toIntOrNull() ?: if (generateMaps) 1 else 0
    // Only one process may generate or pack a cache at a time on this machine: they queue on this file.
    val cachePackLock: String = dotenv.get("CACHE_PACK_LOCK", System.getProperty("java.io.tmpdir") + "/darkan-cache-pack.lock")
    // Lobby: only hand a map square to a client once a world has reported it explored (org.darkan.core.map.MapDiscovery).
    // World: report the squares sent to players to the lobby. Both need to be set for the fog to work.
    // Run this world as one 15,000 x 15,000 sector of the 26 x 26 sector world (org.darkan.core.map.WorldWindow).
    // Both unset: the world is sector MM unless WORLD_SECTOR names another generated sector.
    val worldWindowX: Int? = dotenv.get("WORLD_WINDOW_X", null)?.toIntOrNull()
    val worldWindowY: Int? = dotenv.get("WORLD_WINDOW_Y", null)?.toIntOrNull()
    // The sector this world is, by name (row letter then column letter, MM is the centre). Unset: MM. Only sectors in GENERATED_SECTORS may run.
    val worldSector: String? = dotenv.get("WORLD_SECTOR", null)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    // Comma separated sector names that exist for now. Everything else is refused: no world, demand or handover for it.
    val generatedSectors: String = dotenv.get("GENERATED_SECTORS", "MM")
    val playerStartTile: IntArray? = tileEnv("PLAYER_START_TILE")
    val playerRespawnTile: IntArray? = tileEnv("PLAYER_RESPAWN_TILE")
    val windowed: Boolean = worldWindowX != null && worldWindowY != null
    // Lobby: start neighbouring windows on demand by running WINDOW_SCRIPT, at most WINDOW_MAX_RUNNING at a time.
    // World ids and ports of window worlds: a community lobby only takes ids 100 to 999, so a world on one sets a base in that range.
    val windowWorldIdBase: Int = dotenv.get("WINDOW_WORLD_ID_BASE", "2000").toInt()
    val windowPortBase: Int = dotenv.get("WINDOW_PORT_BASE", "44000").toInt()
    // The world id of window 0,0 when it is not the base (the world a player starts in).
    val windowHomeWorldId: Int = dotenv.get("WINDOW_HOME_WORLD_ID", "0").toInt()
    // A world that has no lobby orchestrator behind it starts the windows its players are heading for by running this script.
    val windowLaunchScript: String = dotenv.get("WINDOW_LAUNCH_SCRIPT", "")
    val windowOrchestrator: Boolean = dotenv.get("WINDOW_ORCHESTRATOR", "false").toBooleanStrict()
    val windowScript: String = dotenv.get("WINDOW_SCRIPT", "./scripts/window-world.sh")
    val windowMaxRunning: Int = dotenv.get("WINDOW_MAX_RUNNING", "8").toInt()
    // Window world: exit after this many minutes without a player (0 = never). The lobby starts it again on demand.
    val windowIdleExitMinutes: Int = dotenv.get("WINDOW_IDLE_EXIT_MINUTES", "0").toInt()
    val gateMaps: Boolean = dotenv.get("CACHE_GATE_MAPS", "false").toBooleanStrict()
    val discoveredMapsPath: String = dotenv.get("DISCOVERED_MAPS_PATH", "./data/map/discovered.txt")
    // The packed cache the server actually opens: the build of the source tree when one is configured
    // (org.darkan.core.cache.CacheSource builds it before Cache.get() opens it), else the prepacked one.
    val cachePath: String = if (cacheSourcePath != null) cacheBuildPath else prepackedCachePath
    val memCache: Boolean = dotenv.get("MEM_CACHE", "false").toBooleanStrict()
    // Folder of gamevals/<type>.json name catalogs (see docs/gameval.md): the source tree's gamevals/, which
    // is the only copy. Defaults to the tree named by CACHE_SOURCE_PATH, else the cache-source submodule.
    val gamevalPath: String = dotenv.get("GAMEVAL_PATH", (cacheSourcePath ?: CACHE_SOURCE_SUBMODULE) + "/gamevals")
    val majorVersion: Int = dotenv.get("MAJOR_VERSION", "727").toInt()
    val minorVersion: Int = dotenv.get("MINOR_VERSION", "1").toInt()
    val packetSizeLimit: Int = dotenv.get("PACKET_SIZE_LIMIT", "7500").toInt()
    val packetQueueCapacity: Int = dotenv.get("PACKET_QUEUE_CAPACITY", "50").toInt()
    // Per-session outbound backlog. A client that lets this many packets pile up unsent is dropped
    // rather than buffered forever. Needs headroom for the login burst, so it is far larger than
    // the inbound queue.
    val outboundQueueCapacity: Int = dotenv.get("OUTBOUND_QUEUE_CAPACITY", "4096").toInt()
    val packetValidateSizes: Boolean = dotenv.get("PACKET_VALIDATE_SIZES", debug.toString()).toBooleanStrict()
    val worldCycleNanos: Long = dotenv.get("WORLD_CYCLE_NANOS", "600000000").toLong()
    val worldCycleMillis: Long = worldCycleNanos / 1000000L
    val worldCycleWarnMillis: Long = dotenv.get("WORLD_CYCLE_WARN_MILLIS", "300").toLong()
    val staffWebhookUrl: String? = dotenv.get("STAFF_WEBHOOK_URL", null)
    val cacheThreadUsage: Double = dotenv.get("CACHE_THREAD_USAGE", "1.0").toDouble()

    val cryptoArgon2Memory: Int = dotenv.get("CRYPTO_ARGON2_MEMORY", "65536").toInt() // 64mb (1048576) for 1GB in KB
    val cryptoArgon2Iterations: Int = dotenv.get("CRYPTO_ARGON2_ITERATIONS", "5").toInt()
    val cryptoArgon2HashLength: Int = dotenv.get("CRYPTO_ARGON2_HASH_LENGTH", "32").toInt()
    val cryptoArgon2SaltLength: Int = dotenv.get("CRYPTO_ARGON2_SALT_LENGTH", "16").toInt()
    val cryptoArgon2Parallelism: Int = dotenv.get("CRYPTO_ARGON2_PARALLELISM", "4").toInt()

    val legacyCryptoSecret: String = dotenv.get("LEGACY_CRYPTO_SECRET", "secrettt")
    val legacyCryptoSalt: ByteArray = dotenv.get("LEGACY_CRYPTO_SALT", "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0").split(",").map { it.toByte() }.toByteArray()

    //mongo settings
    val mongoUri: String = dotenv.get("MONGO_URI", "mongodb://localhost:27017/darkan-server?retryWrites=true&w=majority")

    // SocialGateway (world <-> lobby) settings. The defaults join the dev lobby as a community world
    // (docs/community-worlds.md); the lobby and official worlds set SOCIAL_GATEWAY_TOKEN to their private one.
    val socialGatewayUrl: String = dotenv.get("SOCIAL_GATEWAY_URL", "ws://dev.darkan.org:4040/social/ws")
    val communityWorldToken: String = dotenv.get("COMMUNITY_WORLD_TOKEN", "darkan-community-world")
    val socialGatewayToken: String = dotenv.get("SOCIAL_GATEWAY_TOKEN", communityWorldToken)
    val communityWorld: Boolean = socialGatewayToken == communityWorldToken
    // Worlds 1 to OFFICIAL_WORLD_MAX are official; the lobby refuses them to community worlds.
    val officialWorldMax: Int = dotenv.get("OFFICIAL_WORLD_MAX", "99").toInt()
    // How often a world re-announces everyone it has in game, so lobby-side presence cannot drift
    // for longer than one interval after a dropped message or a restart on either side.
    val socialPresenceResyncSeconds: Long = dotenv.get("SOCIAL_PRESENCE_RESYNC_SECONDS", "120").toLong()
    val worldId: Int = dotenv.get("WORLD_ID", (officialWorldMax + 1).toString()).toInt()

    //Automatic updates from the CI package registry (dev servers only). BUILD_VERSION is exported by the
    //run scripts from the package they downloaded; a blank version means "not a CI build" and updates
    //to the first published one.
    val autoUpdate: Boolean = dotenv.get("AUTO_UPDATE", "false").toBooleanStrict()
    val buildVersion: String = dotenv.get("BUILD_VERSION", "")
    val autoUpdateChannel: String = dotenv.get("AUTO_UPDATE_CHANNEL", "dev")
    val autoUpdatePollSeconds: Int = dotenv.get("AUTO_UPDATE_POLL_SECONDS", "60").toInt()
    val autoUpdateCountdownMinutes: Int = dotenv.get("AUTO_UPDATE_COUNTDOWN_MINUTES", "5").toInt()
    val checkoutCommit: String = dotenv.get("CHECKOUT_COMMIT", "")
    val gitlabAccessToken: String = dotenv.get("GITLAB_ACCESS_TOKEN", "")
    val gitlabProjectId: String = dotenv.get("DARKAN_GITLAB_PROJECT_ID", "67255206")
    val worldName: String = dotenv.get("WORLD_NAME", serverName)
    val worldHost: String = dotenv.get("WORLD_HOST", "localhost")
    val worldPublicHost: String = dotenv.get("WORLD_PUBLIC_HOST", worldHost)
    val worldActivity: String = dotenv.get("WORLD_ACTIVITY", "")
    val worldMembers: Boolean = dotenv.get("WORLD_MEMBERS", members.toString()).toBooleanStrict()
    val worldQuickChat: Boolean = dotenv.get("WORLD_QUICKCHAT", "false").toBooleanStrict()
    val worldBotsAllowed: Boolean = dotenv.get("WORLD_BOTS_ALLOWED", "false").toBooleanStrict()
    val worldLootShare: Boolean = dotenv.get("WORLD_LOOTSHARE", "true").toBooleanStrict()
    val worldCountry: String = dotenv.get("WORLD_COUNTRY", "USA")
    val worldHighlighted: Boolean = dotenv.get("WORLD_HIGHLIGHTED", "false").toBooleanStrict()
    val worldXpRate: Int = dotenv.get("WORLD_XP_RATE", "1").toInt()
    val worldDropModifier: Double = dotenv.get("WORLD_DROP_MODIFIER", "1.0").toDouble()
    val worldToken: String = dotenv.get("WORLD_TOKEN", js5ServerToken)
}
