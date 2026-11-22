package com.rs.game.content.quests.area

import com.rs.game.content.world.generated.DungeonBosses
import com.rs.game.content.world.generated.DungeonMobs
import com.rs.game.content.world.generated.hostileIdsAt
import org.darkan.core.Logger.logInfo
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.city.BuildingBuilder
import org.darkan.core.city.City
import org.darkan.core.dungeon.Dungeon
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import java.io.File
import java.util.Random
import kotlin.math.roundToInt

private const val GENERATED_FILE = "generated.json"
private const val WORLD_FILE = "./data/quests/world.json"
private const val GENERATOR_VERSION = 1
private const val SEED_SALT = 0x51E57L
private const val CITY_ORIGIN = "city:"
private const val TIER_GROWTH = 0.2
private const val GOODS_CHOICES = 3
private const val WILD_DUNGEONS = 2
private const val LONG_EVERY = 3
private const val QUESTS_PER_CITY = 20
private const val LONG_SHARE = 5
private const val FLOOR_TIERS = 4
private const val KILL_BASE = 5
private const val SWEEP_BASE = 8
private const val SLAUGHTER_BASE = 12
private const val BONES_BASE = 5
private const val HUNT_RADIUS = 300
private val SAMPLE_DISTANCES = intArrayOf(80, 160, 240)
private val SAMPLE_DIRECTIONS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
private val PARCELS = listOf("ra3_package", "sailing_courier_crate", "royal_letter", "burgh_generalstore_crate")
private const val GENERAL_STORE = "general_store"

private class Goods(val item: String, val skill: String, val minTier: Int, val base: Int)

private val GOODS = listOf(
    Goods("logs", "woodcutting", 1, 8), Goods("oak_logs", "woodcutting", 2, 8), Goods("willow_logs", "woodcutting", 4, 10),
    Goods("maple_logs", "woodcutting", 8, 10), Goods("yew_logs", "woodcutting", 10, 8), Goods("magic_logs", "woodcutting", 14, 6),
    Goods("copper_ore", "mining", 1, 6), Goods("tin_ore", "mining", 1, 6), Goods("clay", "mining", 1, 6), Goods("iron_ore", "mining", 3, 8),
    Goods("coal", "mining", 5, 10), Goods("mithril_ore", "mining", 9, 8), Goods("adamantite_ore", "mining", 12, 6), Goods("runite_ore", "mining", 15, 4),
    Goods("bones", "prayer", 1, 6), Goods("big_bones", "prayer", 4, 6),
    Goods("raw_trout", "fishing", 3, 8), Goods("raw_salmon", "fishing", 5, 8), Goods("raw_tuna", "fishing", 7, 8),
    Goods("raw_lobster", "fishing", 8, 8), Goods("raw_swordfish", "fishing", 10, 6), Goods("raw_shark", "fishing", 13, 5),
    Goods("bronze_bar", "smithing", 1, 4), Goods("iron_bar", "smithing", 4, 5), Goods("steel_bar", "smithing", 7, 5),
)

private val TRADES = mapOf(
    "food_shop" to setOf("fishing"), "fishing_shop" to setOf("fishing"),
    "sword_shop" to setOf("mining", "smithing"), "armour_shop" to setOf("mining", "smithing"),
    "archery_shop" to setOf("woodcutting"), "staff_shop" to setOf("woodcutting"), "magic_shop" to setOf("prayer"),
)

private class Facts(
    val city: City, val tier: Int, val givers: List<String>, val sewer: Dungeon?, val wild: List<Dungeon>, val neighbours: List<City>,
    val localMobs: List<String>, val dungeonMobs: Map<String, List<String>>,
)

object AreaQuestPlanner {

    private fun mobName(id: Int) = NPCDefinitions.getDefs(id).name.lowercase()

    private fun distance(city: City, x: Int, y: Int) = maxOf(Math.abs(city.centerX - x), Math.abs(city.centerY - y))

    private fun mobsIn(dungeon: Dungeon): List<String> = DungeonMobs.slotsOf(dungeon).filter { !it.point.boss }
        .map { mobName(DungeonMobs.mobId(dungeon, it.point)) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    private fun localMobs(city: City): List<String> = SAMPLE_DISTANCES.flatMap { reach ->
        SAMPLE_DIRECTIONS.flatMap { (dx, dy) ->
            val x = city.centerX + dx * (city.halfSize + reach)
            val y = city.centerY + dy * (city.halfSize + reach)
            val biome = GeneratedWorld.generator.biomeAt(x, y)
            if (biome == Biome.OCEAN) emptyList() else hostileIdsAt(biome, x, y).map(::mobName)
        }
    }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    private fun facts(city: City): Facts {
        val dungeons = GeneratedWorld.dungeons
        val wild = dungeons.filter { !it.origin.startsWith(CITY_ORIGIN) }.sortedBy { distance(city, it.entranceX, it.entranceY) }.take(WILD_DUNGEONS)
        val sewer = dungeons.firstOrNull { it.origin == "$CITY_ORIGIN${city.id}" }
        return Facts(
            city, AreaQuestRegistry.tier(city.id), AreaQuestRegistry.givers(city), sewer, wild,
            GeneratedWorld.cities.filter { it.id != city.id }.sortedBy { distance(city, it.centerX, it.centerY) },
            localMobs(city), (listOfNotNull(sewer) + wild).associate { it.id to mobsIn(it) },
        )
    }

    private class Maker(val facts: Facts, val giver: String, val random: Random) {
        val city = facts.city
        val steps = ArrayList<Step>()
        val skills = LinkedHashSet<String>()

        fun <T> pick(options: List<T>): T = options[random.nextInt(options.size)]

        fun scaled(base: Int) = (base * (1 + (facts.tier - 1) * TIER_GROWTH)).roundToInt().coerceAtLeast(1)

        fun id() = "s${steps.size + 1}"

        fun here(role: String) = Giver(city.id, role)

        fun colleague(): Giver = here(pick(facts.givers.filter { it != giver }.ifEmpty { facts.givers }))

        fun abroad(): Giver? = facts.neighbours.firstOrNull()?.let { town ->
            val roles = AreaQuestRegistry.givers(town)
            Giver(town.id, if (giver in roles && random.nextBoolean()) giver else if (BuildingBuilder.BANKER in roles) BuildingBuilder.BANKER else roles.first())
        }

        fun goods(): Goods {
            val trade = TRADES[giver]
            val fitting = GOODS.filter { it.minTier <= facts.tier && (trade == null || it.skill in trade) }.ifEmpty { GOODS.filter { it.minTier <= facts.tier } }
            return pick(fitting.sortedByDescending { it.minTier }.take(GOODS_CHOICES))
        }

        fun kill(base: Int, dungeon: Dungeon? = null, next: String? = null): Step {
            val mob = if (dungeon != null) facts.dungeonMobs[dungeon.id].orEmpty().let { if (it.isEmpty()) null else pick(it.take(GOODS_CHOICES)) }
            else facts.localMobs.let { if (it.isEmpty()) null else pick(it.take(GOODS_CHOICES)) }
            val where = if (dungeon != null) Place(dungeon = dungeon.id) else Place(area = city.id, radius = HUNT_RADIUS)
            skills += "slayer"
            return Step(id(), STEP_KILL, mob = mob, amount = scaled(base), where = where, next = next).also { steps += it }
        }

        fun gather(next: String? = null): Step {
            val goods = goods()
            skills += goods.skill
            return Step(id(), STEP_GATHER, item = goods.item, amount = scaled(goods.base), next = next).also { steps += it }
        }

        fun deliver(to: Giver, lines: List<String>) {
            skills += "agility"
            steps += Step(id(), STEP_DELIVER, item = pick(PARCELS), to = to, lines = lines)
        }

        fun talk(to: Giver, lines: List<String>) {
            steps += Step(id(), STEP_TALK, to = to, lines = lines)
        }

        fun visit(dungeon: Dungeon) {
            skills += "agility"
            steps += Step(id(), STEP_VISIT, place = Place(dungeon = dungeon.id))
        }

        fun service(to: Giver) {
            steps += Step(id(), STEP_SERVICE, to = to)
        }

        fun boss(dungeon: Dungeon) {
            skills += listOf("slayer", "constitution")
            steps += Step(id(), STEP_BOSS, dungeon = dungeon.id)
        }

        fun floors() {
            skills += "dungeoneering"
            steps += Step(id(), STEP_DUNGEONEERING, amount = 1 + facts.tier / FLOOR_TIERS)
        }

        fun role(of: Giver = here(giver)) = AreaQuests.role(of.giver)

        fun town(of: Giver) = AreaQuests.cityName(of.area)

        fun mobLabel(step: Step) = step.mob ?: "hostile creatures"

        fun itemLabel(step: Step) = AreaQuestRegistry.objName(step.item.orEmpty()).lowercase()

        fun quest(slug: String, name: String, length: String, summary: String, intro: List<String>, outro: List<String>, requires: Requires? = null) = AreaQuest(
            id = "${city.id}_${giver}_$slug", name = name, area = city.id, giver = giver, length = length, summary = summary, intro = intro,
            accept = pick(listOf("I'll see it done.", "Leave it with me.", "Consider it handled.")), outro = outro, requires = requires,
            rewards = Rewards(xp = skills.toList().ifEmpty { null }), steps = steps.toList(),
        )
    }

    private fun words(text: String) = text.split(' ', '_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun short(facts: Facts, giver: String, variant: Int, random: Random): AreaQuest = with(Maker(facts, giver, random)) {
        val sewer = facts.sewer
        when {
            variant == 0 -> {
                val step = kill(KILL_BASE)
                quest(
                    "cull", "Trouble with ${words(mobLabel(step))}s", LENGTH_SHORT, "The ${role()} wants the ${mobLabel(step)} near ${city.name} thinned out.",
                    listOf("The ${mobLabel(step)} beyond the walls grow bolder every week. Customers will not walk the road.", "Put down ${step.count} of them close to ${city.name} and I will make it worth your while."),
                    listOf("The road is quieter already. Here, as promised."),
                )
            }
            variant == 1 -> {
                val step = gather()
                quest(
                    "stock", "Short on ${words(itemLabel(step))}", LENGTH_SHORT, "The ${role()} of ${city.name} has run out of ${itemLabel(step)}.",
                    listOf("My stores are bare. I need ${step.count} ${itemLabel(step)} and nobody in ${city.name} has any to spare.", "Bring them to me, however you come by them."),
                    listOf("That will keep me going for a good while. Take this for your trouble."),
                )
            }
            variant == 2 -> {
                val to = colleague()
                deliver(to, listOf("player: A delivery from the ${role()}.", "At last. I had begun to think it lost. Tell them the account is settled."))
                quest(
                    "parcel", "A Parcel for the ${words(role(to))}", LENGTH_SHORT, "Carry a parcel across ${city.name} to the ${role(to)}.",
                    listOf("This should have gone to the ${role(to)} days ago and I cannot leave my post.", "Take it over for me and come back when it is done."),
                    listOf("Delivered and settled? Good. Here is something for the legwork."),
                )
            }
            variant == 3 || sewer == null -> {
                val to = colleague()
                service(to)
                talk(to, listOf("player: The ${role()} sent me to ask after the ledgers.", "Tell them the figures balance, for once. There is nothing owed between us this season."))
                quest(
                    "word", "Word to the ${words(role(to))}", LENGTH_SHORT, "Call on the ${role(to)} of ${city.name} and bring back their answer.",
                    listOf("I need an answer from the ${role(to)} and I would rather not shout it across the street.", "Call on them as a customer, then ask how our accounts stand."),
                    listOf("Balanced? Then I can sleep tonight. My thanks."),
                )
            }
            else -> {
                visit(sewer)
                quest(
                    "scout", "Eyes on the ${words(sewer.name)}", LENGTH_SHORT, "Climb down into ${sewer.name} and report what you see.",
                    listOf("Something has been scratching under the streets at night. It comes from ${sewer.name}.", "Go down the entrance, take a look around and come back in one piece."),
                    listOf("So it is as bad as I feared. Thank you for looking, that took nerve."),
                )
            }
        }
    }

    private fun medium(facts: Facts, giver: String, variant: Int, random: Random): AreaQuest = with(Maker(facts, giver, random)) {
        val sewer = facts.sewer
        val far = abroad()
        when {
            variant == 0 && sewer != null -> {
                visit(sewer)
                val step = kill(SWEEP_BASE, sewer)
                val to = colleague()
                talk(to, listOf("player: ${sewer.name} is clearer than it was. ${step.count} ${mobLabel(step)} fewer.", "Then the cellars on this street are safe a while longer. I will tell the others."))
                quest(
                    "sweep", "Sweeping the ${words(sewer.name)}", LENGTH_MEDIUM, "Clear ${mobLabel(step)} out of ${sewer.name} and tell the ${role(to)}.",
                    listOf("${sewer.name} is crawling with ${mobLabel(step)}, and they come up through the drains.", "Go down, kill ${step.count} of them, then let the ${role(to)} know their cellar is safe."),
                    listOf("Both done? ${city.name} owes you more than I can pay, but take this."),
                )
            }
            variant == 1 && far != null -> {
                val step = gather()
                deliver(far, listOf("player: Goods from the ${role()} in ${city.name}.", "From ${city.name}? Then the roads are open after all. Set it down there."))
                service(far)
                quest(
                    "supply", "Supply Run to ${town(far)}", LENGTH_MEDIUM, "Gather ${itemLabel(step)} for the ${role()}, then carry a shipment to ${town(far)}.",
                    listOf("I have an order from ${town(far)} and nothing to fill it with.", "Bring me ${step.count} ${itemLabel(step)}, then take the packed shipment to the ${role(far)} there and do some business while you are in town."),
                    listOf("They took the lot? Then we have a trade route. Your share, as agreed."),
                )
            }
            variant == 2 -> {
                val first = kill(KILL_BASE)
                val second = kill(SWEEP_BASE, sewer)
                val step = gather()
                quest(
                    "hunt", "The Long Hunt", LENGTH_MEDIUM, "Hunt the creatures around ${city.name} and bring back ${itemLabel(step)}.",
                    listOf("The wilds around ${city.name} need a firm hand. Start with ${first.count} ${mobLabel(first)} outside the walls, then ${second.count} ${mobLabel(second)}${if (sewer != null) " down in ${sewer.name}" else ""}.", "When that is done, bring me ${step.count} ${itemLabel(step)} to show for it."),
                    listOf("A proper hunt. The town will eat and sleep easier for it."),
                )
            }
            else -> {
                val first = colleague()
                val second = colleague()
                talk(first, listOf("player: The ${role()} asks whether you will stand with them at the next council.", "I will, if the ${role(second)} does. Take this sealed note over and we shall see."))
                if (second != first) deliver(second, listOf("player: A sealed note, from the ${role(first)}.", "Hm. They want my vote. Very well, tell your employer it is agreed."))
                else service(second)
                service(here(BuildingBuilder.BANKER).takeIf { BuildingBuilder.BANKER in facts.givers && giver != BuildingBuilder.BANKER } ?: first)
                quest(
                    "council", "Votes for the Council", LENGTH_MEDIUM, "Gather support around ${city.name} for the ${role()}.",
                    listOf("The council meets soon and I am short of friends.", "Speak to the ${role(first)}, carry whatever they give you, and see that the paperwork is lodged."),
                    listOf("Agreed, all of them? You have a talent for this. Here."),
                )
            }
        }
    }

    private fun long(facts: Facts, giver: String, variant: Int, random: Random, requires: Requires): AreaQuest = with(Maker(facts, giver, random)) {
        val sewer = facts.sewer
        val wild = facts.wild.firstOrNull()
        val far = abroad()
        val bank = here(BuildingBuilder.BANKER).takeIf { BuildingBuilder.BANKER in facts.givers && giver != BuildingBuilder.BANKER } ?: colleague()
        when {
            variant == 0 && wild != null -> {
                val scout = colleague()
                val boss = DungeonBosses.definitions[wild.bossId]?.name ?: "its master"
                talk(scout, listOf("player: What do you know of ${wild.name}?", "Only that those who go in come back thin and quiet. $boss rules it now."))
                visit(wild)
                val step = kill(SLAUGHTER_BASE, wild)
                boss(wild)
                val proof = gather()
                if (far != null) deliver(far, listOf("player: Word from ${city.name}: $boss is dead.", "Dead? Then the caravans can leave at dawn. This news is worth more than gold."))
                quest(
                    "expedition", "The Fall of ${boss.replaceFirstChar { it.lowercase() }}", LENGTH_LONG, "March on ${wild.name}, break its creatures and bring down $boss.",
                    listOf("${wild.name} has stood against ${city.name} for too long. $boss sits at the bottom of it.", "Learn what you can from the ${role(scout)}, fight your way through ${step.count} ${mobLabel(step)} and end $boss.", "Bring ${proof.count} ${itemLabel(proof)} back so the town can rebuild."),
                    listOf("It is done, then. They will sing about this in every tavern from here to the coast.", "Take your reward. You have more than earned it."), requires,
                )
            }
            variant == 1 && far != null -> {
                deliver(far, listOf("player: A contract from the ${role()} in ${city.name}.", "A fair offer. But the road between us is not safe, and you know it."))
                service(far)
                val step = kill(SLAUGHTER_BASE)
                floors()
                talk(bank, listOf("player: The route to ${town(far)} is open and the contract is signed.", "Then I will underwrite it. ${city.name} has not had news this good in years."))
                quest(
                    "route", "The ${city.name} Trade Route", LENGTH_LONG, "Open a trade route between ${city.name} and ${town(far)}.",
                    listOf("I mean to open a trade route to ${town(far)}. It needs a contract carried, a road made safe and a courier who is proven underground.", "Deliver the contract, trade there, cut down ${step.count} ${mobLabel(step)} near our walls and prove yourself in the dungeoneering caves.", "Then have the ${role(bank)} underwrite the venture."),
                    listOf("Signed, safe and funded. You have changed the fortunes of this town.", "Here is your cut of the first season."), requires,
                )
            }
            sewer != null -> {
                val scout = colleague()
                val boss = DungeonBosses.definitions[sewer.bossId]?.name ?: "its master"
                talk(scout, listOf("player: Have you heard anything from below the streets?", "Every night. Something large is down there, and the small ones answer to it. They call it $boss."))
                visit(sewer)
                val choice = Step(id(), STEP_CHOICE, prompt = "How will you weaken the nest?", lines = listOf("So you have seen it. We can thin them by force, or I can have the guard hold the drains if they are supplied."), options = emptyList())
                steps += choice
                val fight = kill(SLAUGHTER_BASE, sewer)
                val supply = gather()
                val bossId = id()
                steps[steps.indexOf(fight)] = Step(fight.id, fight.type, mob = fight.mob, amount = fight.amount, where = fight.where, next = bossId)
                steps[steps.indexOf(choice)] = Step(
                    choice.id, STEP_CHOICE, prompt = choice.prompt, lines = choice.lines,
                    options = listOf(ChoiceOption("I will cut them down myself.", fight.id), ChoiceOption("Supply the guard and let them hold the drains.", supply.id)),
                )
                boss(sewer)
                talk(bank, listOf("player: $boss is dead. ${sewer.name} is quiet.", "Then the vaults are safe and so is everyone above them. The town will hear of this."))
                quest(
                    "nest", "The Thing Beneath ${city.name}", LENGTH_LONG, "Find what rules ${sewer.name}, weaken its nest and destroy $boss.",
                    listOf("Something rules ${sewer.name} and it is getting stronger. I want it gone.", "Ask the ${role(scout)} what they have heard, see it for yourself, then come back and we will decide how to break the nest.", "When it is weak, kill it and tell the ${role(bank)}."),
                    listOf("I felt the ground go still. ${city.name} is yours to walk freely, friend.", "Take this. It is everything I promised and a little more."), requires,
                )
            }
            else -> {
                val step = kill(SLAUGHTER_BASE)
                val second = kill(SLAUGHTER_BASE)
                val proof = gather()
                floors()
                talk(bank, listOf("player: The wilds around ${city.name} are tamed.", "For now. But for now is all any of us get. Well done."))
                quest(
                    "wardens", "Warden of ${city.name}", LENGTH_LONG, "Tame the wilds around ${city.name}.",
                    listOf("${city.name} needs a warden. Kill ${step.count} ${mobLabel(step)} and ${second.count} ${mobLabel(second)}, bring ${proof.count} ${itemLabel(proof)}.", "Then prove yourself in the dungeoneering caves and report to the ${role(bank)}."),
                    listOf("Warden it is. Wear the title well."), requires,
                )
            }
        }
    }

    private fun handWritten(city: City): Int = AreaQuestRegistry.directory(city.id).listFiles { file -> file.isFile && file.name != GENERATED_FILE }.orEmpty()
        .sumOf { file -> runCatching { AreaQuestRegistry.read(file).count { it.area == city.id } }.getOrDefault(0) }

    private fun fill(quests: MutableList<AreaQuest>, wanted: Int, givers: List<String>, variants: Int, offset: Int, build: (String, Int) -> AreaQuest?) {
        val target = quests.size + wanted
        var attempt = 0
        while (quests.size < target && attempt < givers.size * variants) {
            val quest = build(givers[attempt % givers.size], (attempt + offset) % variants)
            if (quest != null && quests.none { it.id == quest.id }) quests += quest
            attempt++
        }
    }

    fun make(city: City): List<AreaQuest> {
        val facts = facts(city)
        val random = Random(city.seed xor SEED_SALT)
        val offset = random.nextInt(LONG_EVERY)
        val quests = ArrayList<AreaQuest>()
        if (facts.givers.isEmpty()) return quests
        val wanted = (QUESTS_PER_CITY - handWritten(city)).coerceAtLeast(0)
        val longs = wanted / LONG_SHARE
        val mediums = (wanted - longs) / 2
        fill(quests, wanted - longs - mediums, facts.givers, SHORT_VARIANTS, offset) { giver, variant -> short(facts, giver, variant, random) }
        val shorts = quests.toList()
        fill(quests, mediums, facts.givers, MEDIUM_VARIANTS, offset) { giver, variant -> medium(facts, giver, variant, random) }
        val patrons = shorts.map { it.giver }.distinct().let { givers -> givers.indices.map { givers[(it * LONG_EVERY + offset) % givers.size] }.distinct() + givers }
        fill(quests, longs, patrons, LONG_VARIANTS, offset) { giver, variant ->
            long(facts, giver, variant, random, Requires(quests = listOf(shorts.first { it.giver == giver }.id)))
        }
        val names = HashSet<String>()
        return quests.map { quest ->
            if (names.add(quest.name)) quest
            else AreaQuest(
                quest.id, "${quest.name} (${words(AreaQuests.role(quest.giver))})".also { names += it }, quest.area, quest.giver, quest.length, quest.summary, quest.intro,
                quest.accept, quest.outro, quest.requires, quest.rewards, quest.steps,
            )
        }
    }

    fun write(city: City) {
        val quests = make(city)
        val file = File(AreaQuestRegistry.directory(city.id), GENERATED_FILE)
        file.parentFile.mkdirs()
        file.writeText(AreaQuestRegistry.gson.toJson(AreaQuestFile(city.id, city.seed, GENERATOR_VERSION, quests)))
        logInfo("Area quests: made ${quests.size} for ${city.name}")
    }

    fun planMissing() {
        for (city in GeneratedWorld.cities)
            if (!File(AreaQuestRegistry.directory(city.id), GENERATED_FILE).isFile) runCatching { write(city) }.onFailure { logInfo("Area quests: cannot make ${city.id}: $it") }
    }

    fun exportWorld() {
        val lengths = listOf(LENGTH_SHORT, LENGTH_MEDIUM, LENGTH_LONG)
        val areas = GeneratedWorld.cities.map { city ->
            val facts = facts(city)
            mapOf(
                "id" to city.id, "name" to city.name, "tier" to facts.tier, "x" to city.centerX, "y" to city.centerY, "givers" to facts.givers,
                "sewer" to facts.sewer?.id, "wildDungeons" to facts.wild.map { it.id }, "neighbours" to facts.neighbours.map { it.id }, "localMobs" to facts.localMobs,
                "rewards" to lengths.associateWith { length ->
                    val reward = AreaQuestRewards.of(AreaQuest("", "", city.id, "", length, "", emptyList(), steps = emptyList()))
                    mapOf("questPoints" to reward.questPoints, "coins" to reward.coins, "xp" to reward.xp)
                },
            )
        }
        val dungeons = GeneratedWorld.dungeons.map {
            mapOf(
                "id" to it.id, "name" to it.name, "origin" to it.origin, "x" to it.entranceX, "y" to it.entranceY, "level" to DungeonMobs.baseLevel(it),
                "boss" to DungeonBosses.definitions[it.bossId]?.name, "mobs" to mobsIn(it),
            )
        }
        val world = mapOf(
            "areas" to areas, "dungeons" to dungeons, "biomes" to Biome.entries.map { it.name.lowercase() },
            "gatherItems" to GOODS.map { mapOf("item" to it.item, "skill" to it.skill, "minTier" to it.minTier) }, "parcels" to PARCELS,
            "skills" to org.darkan.core.Constants.SKILL_NAME.map { it.lowercase() },
        )
        File(WORLD_FILE).also { it.parentFile.mkdirs() }.writeText(AreaQuestRegistry.gson.toJson(world))
    }

    private const val SHORT_VARIANTS = 5
    private const val MEDIUM_VARIANTS = 4
    private const val LONG_VARIANTS = 3
}
