package org.darkan.world.harness

import com.rs.Settings
import com.rs.engine.thread.AsyncTaskExecutor
import com.rs.engine.thread.WorldThread
import com.rs.game.World
import com.rs.game.map.ChunkManager
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.PluginManager
import org.darkan.core.model.MachineInformation
import com.rs.utils.MapXTEAs
import com.rs.utils.json.WorldGson
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.darkan.core.JsonFileManager
import org.darkan.core.Logger
import org.darkan.core.map.MapXteas
import org.darkan.core.model.Account
import org.darkan.core.model.Rights
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.handler.PacketHandlers
import org.darkan.core.net.prot.revision.rev727.register727
import org.darkan.world.server.WorldServer
import world.gregs.voidps.cache.Cache
import java.util.concurrent.Executors
import java.util.logging.Level

object WorldHarness {

    @Volatile
    private var booted = false

    @Volatile
    private var cacheDefinitions = false

    private const val OPTIONAL_CACHE = "darkan.cache.optional"

    lateinit var codec: Codec
        private set

    @Synchronized
    fun boot() {
        if (booted) return

        Logger.setupFormat()
        Logger.setLevel(Level.WARNING)
        JsonFileManager.setGSON(WorldGson.create())

        System.setProperty(OPTIONAL_CACHE, "true")
        Settings.loadConfig()
        if (!cacheDefinitions) TestDefinitions.install()
        Cache.init(Settings.getConfig().cachePath)
        MapXTEAs.loadKeys()
        MapXteas.load("./data/map/xteaKeys.json")

        register727()
        codec = requireNotNull(Codec.get(727)) { "rev727 codec did not register" }

        AsyncTaskExecutor.initExecutors()

        installWorldServerDispatcher()

        PluginManager.loadPlugins()
        PluginManager.executeStartupHooks()
        PacketHandlers.loadHandlersFromPackage("org.darkan.world.server.packet")

        WorldThread.WORLD_CYCLE = System.currentTimeMillis() / 600L
        booted = true
    }

    fun login(
        username: String = "harness",
        rights: Rights = Rights.OWNER,
        screenMode: Int = 2,
        chosenAccountType: Boolean = true,
    ): HarnessPlayer {
        boot()
        val account = Account(username, "$username@test.invalid", "hash").apply {
            this.rights = rights
        }.view()
        val session = RecordingSession(codec)
        val player = Player(account)
        player.init(session, account, screenMode, 1024, 768, MachineInformation())
        player.isChosenAccountType = chosenAccountType
        player.start()
        player.setStarted()
        return HarnessPlayer(player, session)
    }

    @Synchronized
    fun bootWithCacheDefinitions() {
        check(!booted || cacheDefinitions) { "the harness has already booted on test definitions" }
        cacheDefinitions = true
        boot()
    }

    fun logout(player: HarnessPlayer) {
        World.removePlayer(player.player)
    }

    fun tick() {
        ChunkManager.processChunks()
        WorldTasks.processTasks()

        for (npc in World.NPCs) {
            if (npc == null || npc.hasFinished()) continue
            npc.processTasks()
        }
        for (player in World.players) {
            player?.processTasks()
        }
        for (npc in World.NPCs) {
            if (npc == null || npc.hasFinished()) continue
            npc.processEntity()
        }
        for (player in startedPlayers()) {
            player.processEntity()
        }
        for (npc in World.NPCs) {
            if (npc == null || npc.hasFinished()) continue
            npc.processMovement()
        }
        for (player in startedPlayers()) {
            player.processMovement()
        }
        for (player in startedPlayers()) {
            player.packets.sendLocalPlayersUpdate()
            player.packets.sendLocalNPCsUpdate()
            player.postSync()
        }
        ChunkManager.processUpdateZones()
        for (player in startedPlayers()) {
            player.session.requestFlush()
        }
        WorldThread.WORLD_CYCLE++
    }

    fun tick(count: Int) {
        repeat(count) { tick() }
    }

    fun tickUntil(timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met within ${timeoutMillis}ms of ticking" }
            tick()
            Thread.sleep(10)
        }
    }

    private fun startedPlayers(): List<Player> =
        World.players.filterNotNull().filter { it.hasStarted() && !it.hasFinished() }

    private fun installWorldServerDispatcher() {
        val dispatcher: ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "harness-worldserver").apply { isDaemon = true } }
                .asCoroutineDispatcher()
        val field = WorldServer::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }
        field.set(WorldServer, dispatcher)
    }

    private val Player.packets get() = getPackets()
}

data class HarnessPlayer(val player: Player, val session: RecordingSession) {
    val sent get() = session.sent

    fun clearSent() = session.clearSent()

    fun push(packet: ClientProt) = session.pushClientPacket(packet)
}
