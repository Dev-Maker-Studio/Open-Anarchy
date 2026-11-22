package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.rs.engine.dialogue.Dialogue
import com.rs.engine.dialogue.HeadE
import com.rs.game.content.skills.magic.TeleType
import com.rs.game.model.entity.player.Controller
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onPlayerStep
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
private const val PRAISE_START_TICKS = 6
private const val PRAISE_SECONDS = 10
private const val SERVICE_MINUTES = 2

private val PRAISES = arrayOf(
    "Hallelujah.", "Thank you Jesus.", "Have mercy on us.", "We praise you Lord.", "Praise God.", "In Jesus name.",
    "I will dance like David.", "Amen", "Glory to God.", "Bless the Lord, O my soul.", "Holy, holy, holy.", "Jesus is Lord.",
    "We exalt your name.", "Your grace is sufficient.", "The Lord is my shepherd.", "He is worthy of all praise.",
    "The Lord reigns forever.", "Blessed be your name.", "Great is your faithfulness.", "Thank you for your mercy.",
    "The Lord is my rock and salvation.", "Your love endures forever.", "We lift your name on high.",
    "You are the King of kings.", "All glory to your name.",
)

private class Look(val name: String, val facing: Direction)

private val LOOKS = mapOf(
    Npc.father_aereck to Look("Father Aereck", Direction.NORTH),
    Npc.agrith_reen to Look("Father John", Direction.NORTH),
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
    var priest: ChurchNpc? = null
        private set
    var serviceStarted = false

    fun contains(tile: Tile) = building.touches(tile.x, tile.y, tile.x, tile.y)

    fun congregation(): List<ChurchNpc> = pews.filter { !it.hasFinished() }

    fun merchants(): List<CityShop> = city.residents.filter { it.role == BuildingBuilder.SHOPKEEPER }.map(CityShop::of)

    fun spawn(resident: Resident) {
        val look = LOOKS[resident.npc] ?: return
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

    fun praise() {
        if (!serviceStarted) congregation().randomOrNull()?.forceTalk(PRAISES[Utils.random(PRAISES.size)])
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
    WorldTasks.scheduleTimer(PRAISE_START_TICKS, Ticks.fromSeconds(PRAISE_SECONDS)) {
        Churches.all.values.forEach(Church::praise)
        true
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

    override fun processObjectClick1(obj: GameObject): Boolean {
        val church = church
        if (obj.id != Loc.loc_36972 || church == null) return true
        player.startConversation(Dialogue().addOptions("Pray") { options ->
            if (!player.getDailyB("HasDoneSermon"))
                options.add("Service").addNext { startService(church) }
            options.add("Tithe")
                .addNPC(Npc.father_aereck, HeadE.CALM_TALK, "How much will you give to the Lord?")
                .addNext { askTithe(church) }
        })
        return false
    }

    private fun startService(church: Church) {
        if (church.serviceStarted) {
            player.startConversation(Dialogue().addPlayer(HeadE.HAPPY_TALKING, "There is a service right now. I will get my blessing at the end..."))
            return
        }
        if (church.priest == null || Churches.sermons.isEmpty()) return
        church.serviceStarted = true
        WorldTasks.delay(Ticks.fromMinutes(SERVICE_MINUTES)) { church.serviceStarted = false }
        player.playCutscene(Sermon(church, Churches.sermons[Utils.random(Churches.sermons.size)]))
    }

    private fun askTithe(church: Church) {
        player.sendInputInteger("How many coins would you like to give?") { coins ->
            when {
                coins < 1 -> player.sendMessage("The priest expects a real offering.")
                coins > MAX_TITHE -> player.startConversation(Dialogue()
                    .addNPC(Npc.father_aereck, HeadE.ANGRY, "Are you trying to bribe Christ?") {
                        player.sendMessage("The priest rejects too high an amount")
                    })
                coins > player.inventory.getCoins() -> player.sendMessage("You don't have ${Utils.formatNumber(coins)} coins")
                !Tithes.give(player, church, coins) -> player.sendMessage("${church.city.name} has no merchants to share your offering with.")
                else -> player.sendMessage("You have now tithed ${Utils.formatLong(Tithes.total(player))} coins. The merchants of ${church.city.name} share your offering.")
            }
        }
    }
}
