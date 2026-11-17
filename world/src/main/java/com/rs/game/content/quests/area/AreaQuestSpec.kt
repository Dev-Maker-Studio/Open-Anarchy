package com.rs.game.content.quests.area

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.darkan.core.cache.ItemDefinitions
import com.rs.game.content.world.generated.DungeonBosses
import com.rs.game.content.world.generated.threatLevel
import org.darkan.core.Constants
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.city.BuildingBuilder
import org.darkan.core.city.City
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import java.io.File

const val STEP_KILL = "kill"
const val STEP_BOSS = "boss"
const val STEP_DUNGEONEERING = "dungeoneering"
const val STEP_GATHER = "gather"
const val STEP_DELIVER = "deliver"
const val STEP_TALK = "talk"
const val STEP_VISIT = "visit"
const val STEP_SERVICE = "service"
const val STEP_CHOICE = "choice"
const val RETURN_STEP = "return"
const val LENGTH_SHORT = "short"
const val LENGTH_MEDIUM = "medium"
const val LENGTH_LONG = "long"
const val PLAYER_LINE = "player:"

private const val DIRECTORY = "./data/quests"
private const val EXTENSION = "json"
private const val SHORT_MAX_STEPS = 2
private const val MEDIUM_MAX_STEPS = 4
private const val LONG_MAX_STEPS = 10
private const val MAX_OPTIONS = 4
private const val DEFAULT_AREA_RADIUS = 400

class Place(val area: String? = null, val dungeon: String? = null, val biome: String? = null, val x: Int? = null, val y: Int? = null, val radius: Int? = null) {
    val reach get() = radius ?: DEFAULT_AREA_RADIUS
}

data class Giver(val area: String, val giver: String)

class ChoiceOption(val text: String, val next: String)

class RewardItem(val item: String, val amount: Int? = null)

class Rewards(val xp: List<String>? = null, val items: List<RewardItem>? = null)

class Requires(val questPoints: Int? = null, val quests: List<String>? = null)

class Step(
    val id: String,
    val type: String,
    val journal: String? = null,
    val next: String? = null,
    val mob: String? = null,
    val amount: Int? = null,
    val where: Place? = null,
    val dungeon: String? = null,
    val item: String? = null,
    val to: Giver? = null,
    val lines: List<String>? = null,
    val place: Place? = null,
    val prompt: String? = null,
    val options: List<ChoiceOption>? = null,
) {
    val count get() = amount ?: 1
}

class AreaQuest(
    val id: String,
    val name: String,
    val area: String,
    val giver: String,
    val length: String,
    val summary: String,
    val intro: List<String>,
    val accept: String? = null,
    val outro: List<String>? = null,
    val requires: Requires? = null,
    val rewards: Rewards? = null,
    val steps: List<Step>,
) {
    val source get() = Giver(area, giver)

    fun step(id: String) = steps.firstOrNull { it.id == id }

    fun after(step: Step): String = step.next ?: steps.getOrNull(steps.indexOf(step) + 1)?.id ?: RETURN_STEP
}

class AreaQuestFile(val area: String? = null, val seed: Long? = null, val generatorVersion: Int? = null, val quests: List<AreaQuest>? = null)

object AreaQuestRegistry {
    val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    var quests: Map<String, AreaQuest> = emptyMap()
        private set

    @Volatile
    private var byGiver: Map<Giver, List<AreaQuest>> = emptyMap()

    val active get() = EnvVars.generateMaps

    fun directory(area: String) = File(DIRECTORY, area)

    fun offeredBy(giver: Giver): List<AreaQuest> = byGiver[giver].orEmpty()

    fun inArea(area: String): List<AreaQuest> = quests.values.filter { it.area == area }

    fun load() {
        val loaded = LinkedHashMap<String, AreaQuest>()
        val folders = File(DIRECTORY).listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
        for (file in folders.flatMap { folder -> folder.listFiles { file -> file.isFile && file.extension == EXTENSION }.orEmpty().sortedBy { it.name } }) {
            val read = runCatching { read(file) }.getOrElse {
                logInfo("Area quests: ${file.path} cannot be read: ${it.message}")
                emptyList()
            }
            for (quest in read) {
                val problems = runCatching { problems(quest) }.getOrElse { listOf("is malformed: ${it.message}") }
                when {
                    problems.isNotEmpty() -> logInfo("Area quest ${file.path} ${runCatching { quest.id }.getOrNull()}: ${problems.joinToString("; ")}")
                    quest.id in loaded -> logInfo("Area quest ${file.path} ${quest.id}: the id is already used")
                    else -> loaded[quest.id] = quest
                }
            }
        }
        quests = loaded
        byGiver = loaded.values.groupBy { it.source }.mapValues { (_, offered) -> offered.sortedBy { lengthOrder(it.length) } }
        logInfo("Area quests: ${loaded.size} loaded for ${loaded.values.map { it.area }.distinct().size} areas")
    }

    fun read(file: File): List<AreaQuest> {
        val text = file.readText()
        val asFile = gson.fromJson(text, AreaQuestFile::class.java)
        return asFile.quests ?: listOf(gson.fromJson(text, AreaQuest::class.java))
    }

    fun lengthOrder(length: String) = when (length) {
        LENGTH_SHORT -> 0
        LENGTH_MEDIUM -> 1
        else -> 2
    }

    fun city(area: String): City? = GeneratedWorld.cities.firstOrNull { it.id == area }

    fun tier(area: String): Int = city(area)?.let { threatLevel(it.centerX, it.centerY) } ?: 1

    fun givers(city: City): List<String> = city.residents.mapNotNull { resident ->
        when (resident.role) {
            BuildingBuilder.BANKER, BuildingBuilder.BARBER, BuildingBuilder.REALTOR -> resident.role
            BuildingBuilder.SHOPKEEPER -> resident.shop?.removePrefix("${city.id}_")
            else -> null
        }
    }.distinct()

    fun skill(name: String): Int? = Constants.SKILL_NAME.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 }

    fun objName(item: String): String = DungeonBosses.objId(item)?.let { ItemDefinitions.getDefs(it).name } ?: item

    private fun knownGiver(giver: Giver?): Boolean = giver != null && city(giver.area)?.let { giver.giver in givers(it) } == true

    private fun knownPlace(place: Place?): Boolean = when {
        place == null -> false
        place.dungeon != null -> GeneratedWorld.dungeons.any { it.id == place.dungeon }
        place.biome != null -> Biome.entries.any { it.name.equals(place.biome, ignoreCase = true) }
        place.area != null -> city(place.area) != null
        else -> place.x != null && place.y != null
    }

    fun problems(quest: AreaQuest): List<String> {
        val problems = ArrayList<String>()
        if (!knownGiver(quest.source)) problems += "giver '${quest.giver}' is not a service npc of '${quest.area}'"
        if (quest.intro.isEmpty()) problems += "intro is empty"
        val limit = when (quest.length) {
            LENGTH_SHORT -> 1..SHORT_MAX_STEPS
            LENGTH_MEDIUM -> SHORT_MAX_STEPS + 1..MEDIUM_MAX_STEPS
            LENGTH_LONG -> MEDIUM_MAX_STEPS + 1..LONG_MAX_STEPS
            else -> null
        }
        if (limit == null) problems += "length '${quest.length}' is unknown"
        val ids = quest.steps.map { it.id }
        if (ids.size != ids.toSet().size) problems += "step ids repeat"
        if (RETURN_STEP in ids) problems += "'$RETURN_STEP' is reserved"
        quest.steps.forEachIndexed { index, step -> problems += stepProblems(quest, step, index, ids) }
        val depth = longestPath(quest)
        if (depth == null) problems += "steps loop or a branch never returns"
        else if (limit != null && depth !in limit) problems += "a ${quest.length} quest takes ${limit.first} to ${limit.last} steps, this takes $depth"
        quest.rewards?.xp.orEmpty().forEach { if (skill(it) == null) problems += "xp skill '$it' is unknown" }
        quest.rewards?.items.orEmpty().forEach { if (DungeonBosses.objId(it.item) == null) problems += "reward item '${it.item}' is no obj gameval" }
        return problems
    }

    private fun stepProblems(quest: AreaQuest, step: Step, index: Int, ids: List<String>): List<String> {
        val problems = ArrayList<String>()
        fun later(target: String?) = target == null || target == RETURN_STEP || ids.indexOf(target) > index
        if (!later(step.next)) problems += "step ${step.id}: next '${step.next}' is not a later step"
        when (step.type) {
            STEP_KILL -> if (step.where != null && !knownPlace(step.where)) problems += "step ${step.id}: where is unknown"
            STEP_BOSS -> if (GeneratedWorld.dungeons.none { it.id == step.dungeon }) problems += "step ${step.id}: dungeon '${step.dungeon}' is unknown"
            STEP_DUNGEONEERING -> {}
            STEP_GATHER -> {
                if (step.item?.let(DungeonBosses::objId) == null) problems += "step ${step.id}: item '${step.item}' is no obj gameval"
                if (step.to != null && !knownGiver(step.to)) problems += "step ${step.id}: to is no service npc"
            }
            STEP_DELIVER -> {
                if (step.item?.let(DungeonBosses::objId) == null) problems += "step ${step.id}: item '${step.item}' is no obj gameval"
                if (!knownGiver(step.to)) problems += "step ${step.id}: to is no service npc"
                if (step.to == quest.source) problems += "step ${step.id}: a delivery cannot go back to the giver"
            }
            STEP_TALK -> {
                if (!knownGiver(step.to)) problems += "step ${step.id}: to is no service npc"
                if (step.lines.isNullOrEmpty()) problems += "step ${step.id}: lines are empty"
            }
            STEP_SERVICE -> if (!knownGiver(step.to)) problems += "step ${step.id}: to is no service npc"
            STEP_VISIT -> if (!knownPlace(step.place)) problems += "step ${step.id}: place is unknown"
            STEP_CHOICE -> {
                val options = step.options.orEmpty()
                if (options.size !in 2..MAX_OPTIONS) problems += "step ${step.id}: a choice has 2 to $MAX_OPTIONS options"
                if (step.to != null && !knownGiver(step.to)) problems += "step ${step.id}: to is no service npc"
                options.forEach { if (!later(it.next)) problems += "step ${step.id}: option '${it.text}' does not lead to a later step" }
            }
            else -> problems += "step ${step.id}: type '${step.type}' is unknown"
        }
        if (step.type in setOf(STEP_KILL, STEP_DUNGEONEERING, STEP_GATHER) && step.count < 1) problems += "step ${step.id}: amount must be at least 1"
        return problems
    }

    private fun longestPath(quest: AreaQuest): Int? {
        val first = quest.steps.firstOrNull() ?: return null
        val known = HashMap<String, Int?>()
        fun depth(id: String, trail: Set<String>): Int? {
            if (id == RETURN_STEP) return 0
            if (id in trail) return null
            known[id]?.let { return it }
            val step = quest.step(id) ?: return null
            val targets = if (step.type == STEP_CHOICE) step.options.orEmpty().map { it.next } else listOf(quest.after(step))
            val deepest = targets.map { depth(it, trail + id) ?: return null }.maxOrNull() ?: return null
            return (deepest + 1).also { known[id] = it }
        }
        return depth(first.id, emptySet())
    }
}
