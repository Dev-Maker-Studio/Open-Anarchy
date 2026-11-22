package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.rs.engine.dialogue.Dialogue
import com.rs.engine.dialogue.HeadE
import com.rs.game.content.skills.magic.TeleType
import com.rs.game.model.entity.player.Controller
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import com.rs.plugin.kts.onPlayerStep
import com.rs.game.content.skills.prayer.PrayerBoosts
import com.rs.game.model.entity.npc.NPC
import java.util.Random
import com.rs.utils.Ticks
import org.darkan.core.Utils
import org.darkan.core.city.Building
import org.darkan.core.city.BuildingBuilder
import org.darkan.core.city.ChurchTemplate
import org.darkan.core.city.City
import org.darkan.core.city.Resident
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Midi
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.pathfinder.Direction
import world.gregs.voidps.type.Tile
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

const val CHURCH_MUSIC = Midi.midi_999
private const val SERMONS_PATH = "data/church/sermons"
private const val MAX_TITHE = 2_000_000_000
private const val GROUND = 0
private const val SERVICE_MINUTES = 2
private const val PASTOR_SALT = 3391L

private val PASTOR_NAMES = arrayOf(
    "Abel", "Amos", "Asher", "Boaz", "Caleb", "Cyrus", "Daniel", "Elias", "Enoch", "Ezra", "Gideon", "Isaac", "Jonah", "Josiah", "Levi",
    "Micah", "Nathan", "Noah", "Obed", "Phineas", "Reuben", "Samuel", "Silas", "Simeon", "Thaddeus", "Titus", "Tobias", "Uriah", "Zadok", "Zane",
)

private class Look(val name: String, val facing: Direction)

private val LOOKS = mapOf(
    Npc.hans to Look("Hans", Direction.SOUTHWEST),
    Npc.johnathon_fitzharmon to Look("Johnathan", Direction.SOUTH),
    Npc.atlum_explorer_jack to Look("Jack", Direction.SOUTH),
    Npc.lum_fish_shopkeeper to Look("Hank", Direction.SOUTH),
    Npc.fred_the_farmer to Look("Fred", Direction.SOUTH),
    Npc.build_imp_draynor_man to Look("Mike", Direction.SOUTHEAST),
)

class ChurchNpc(id: Int, tile: Tile, val facing: Direction) : CityResident(id, tile, facing)

class Church(val city: City, private val building: Building) {
    val serviceTile: Tile = Tile.of(building.x + ChurchTemplate.SERVICE_X, building.y + ChurchTemplate.SERVICE_Y, GROUND)
    val entrance = Array(ChurchTemplate.ENTRANCE_ROWS) { Tile.of(building.x, building.y + ChurchTemplate.ENTRANCE_Y + it, GROUND) }
    private val pews = CopyOnWriteArrayList<ChurchNpc>()
    private val pastor = Look("Pastor ${PASTOR_NAMES[Random(city.seed + PASTOR_SALT).nextInt(PASTOR_NAMES.size)]}", Direction.NORTH)
    var priest: ChurchNpc? = null
        private set
    var serviceStarted = false

    fun contains(tile: Tile) = building.touches(tile.x, tile.y, tile.x, tile.y)

    fun congregation(): List<ChurchNpc> = pews.filter { !it.hasFinished() }

    fun merchants(): List<CityShop> = city.residents.filter { it.role == BuildingBuilder.SHOPKEEPER }.map(CityShop::of)

    fun spawn(resident: Resident) {
        val look = if (resident.role == ChurchTemplate.PRIEST) pastor else LOOKS[resident.npc] ?: return
        val npc = ChurchNpc(resident.npc, Tile.of(resident.x, resident.y, resident.plane), look.facing)
        npc.setPermName(look.name)
        when (resident.role) {
            ChurchTemplate.PRIEST -> priest = npc
            ChurchTemplate.CONGREGANT -> {
                pews.removeIf { it.hasFinished() }
                pews += npc
            }
        }
    }
}

object Churches {
    val all: Map<String, Church> by lazy {
        GeneratedWorld.cities.mapNotNull { city -> city.buildings.firstOrNull { it.kind == ChurchTemplate.KIND }?.let { city.id to Church(city, it) } }.toMap()
    }
    var sermons: List<Array<String>> = emptyList()

    fun spawn(resident: Resident) {
        all.values.firstOrNull { it.contains(Tile.of(resident.x, resident.y, resident.plane)) }?.spawn(resident)
    }
}

@ServerStartupEvent
fun mapChurchService() {
    Churches.sermons = loadSermons(File(SERMONS_PATH))
    onObjectClick(Loc.church_lectern) { (player, obj, option) ->
        val church = Churches.all.values.firstOrNull { it.contains(obj.tile) } ?: return@onObjectClick
        val priest = church.priest ?: return@onObjectClick
        if (option == "Tithe")
            player.startConversation(Dialogue().addNPC(priest, HeadE.CALM_TALK, "How much will you give to the Lord?").addNext { askTithe(player, church, priest) })
        else if (option == "Attend-service")
            startService(player, church)
    }
    for (church in Churches.all.values) {
        onPlayerStep(church.entrance) { (player) ->
            if (player.controllerManager.controller == null)
                player.controllerManager.startController(ChurchService(church.city.id))
        }
    }
}

private fun loadSermons(directory: File): List<Array<String>> =
    directory.listFiles { _, name -> name.endsWith(".json") }.orEmpty().mapNotNull { file ->
        file.reader().use { Gson().fromJson(it, JsonObject::class.java) }
            .getAsJsonArray("Sermon")
            ?.map { it.asString }
            ?.toTypedArray()
    }

class ChurchService(private val cityId: String) : Controller() {
    val church: Church? get() = Churches.all[cityId]

    override fun playAmbientMusic() = false

    override fun start() {
        player.musicsManager.forcePlayMusic(CHURCH_MUSIC)
    }

    override fun login(): Boolean {
        if (church?.contains(player.tile) != true) return true
        start()
        return false
    }

    override fun logout() = false

    override fun moved() {
        if (church?.contains(player.tile) != true) removeController()
    }

    override fun onTeleported(type: TeleType) = removeController()
}

private fun startService(player: Player, church: Church) {
    if (church.serviceStarted) {
        player.startConversation(Dialogue().addPlayer(HeadE.HAPPY_TALKING, "There is a service right now. I will get my blessing at the end..."))
        return
    }
    if (!ChurchBlessing.ready(player)) {
        player.sendMessage("You have attended a service within the hour. Come back in ${ChurchBlessing.minutesLeft(player)} minutes.")
        return
    }
    if (church.priest == null || Churches.sermons.isEmpty()) return
    church.serviceStarted = true
    WorldTasks.delay(Ticks.fromMinutes(SERVICE_MINUTES)) { church.serviceStarted = false }
    player.playCutscene(Sermon(church, Churches.sermons[Utils.random(Churches.sermons.size)]))
}

private fun askTithe(player: Player, church: Church, priest: NPC) {
    player.sendInputInteger("How many coins would you like to give?") { coins ->
        when {
            coins < 1 -> player.sendMessage("The priest expects a real offering.")
            coins > MAX_TITHE -> player.startConversation(Dialogue()
                .addNPC(priest, HeadE.ANGRY, "Are you trying to bribe Christ?") {
                    player.sendMessage("The priest rejects too high an amount")
                })
            coins > player.inventory.getCoins() -> player.sendMessage("You don't have ${Utils.formatNumber(coins)} coins")
            !Tithes.give(player, church, coins) -> player.sendMessage("${church.city.name} has no merchants to share your offering with.")
            else -> player.sendMessage("You have now tithed ${Utils.formatLong(Tithes.total(player))} coins. The merchants of ${church.city.name} share your offering.")
        }
    }
}

object ChurchBlessing {
    private const val LAST_SERVICE = "churchLastService"
    private const val SERVICES = "ServicesAttended"
    private const val HOUR_MILLIS = 3_600_000L
    private const val MINUTE_MILLIS = 60_000L
    private const val POINT_BOOST = 100
    private const val SCALE_PER_SERVICE = 0.003

    private fun sinceService(player: Player) = System.currentTimeMillis() - player.getL(LAST_SERVICE, 0)

    fun ready(player: Player) = sinceService(player) >= HOUR_MILLIS

    fun minutesLeft(player: Player) = (HOUR_MILLIS - sinceService(player) + MINUTE_MILLIS - 1) / MINUTE_MILLIS

    fun pointBoost(player: Player) = if (ready(player)) 0 else POINT_BOOST

    fun prayerScale(player: Player) = 1.0 + SCALE_PER_SERVICE * player.getCounterValue(SERVICES)

    fun bless(player: Player): Boolean {
        if (!ready(player)) return false
        player.set(LAST_SERVICE, System.currentTimeMillis())
        player.incrementCount(SERVICES)
        player.prayer.points = player.prayer.maxPoints.toDouble()
        PrayerBoosts.send(player)
        return true
    }
}
