package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.rs.game.World
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import org.darkan.core.Logger.logInfo
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val DIRECTORY = "./data/dungeons/bosses"
private const val EXTENSION = "json"
private const val SLAM = "slam"
private const val SUMMON = "summon"
private const val ENRAGE = "enrage"
private const val HEAL = "heal"
private const val DEFAULT_EVERY = 25
private const val DEFAULT_RADIUS = 3
private const val DEFAULT_FACTOR = 1.0
private const val DEFAULT_COUNT = 2
private const val SLAM_WARNING_TICKS = 3
private const val MINION_FACTOR = 0.6
private const val MINION_REACH = 2
private const val MINION_ATTEMPTS = 12
private const val RESET_TICKS = 100
private const val PERCENT = 100
private const val GROUND_LEVEL = 0
private const val MISSING = -1

class BossAbility(
    val type: String, val everyTicks: Int?, val radius: Int?, val damage: Double?, val atHealth: List<Int>?, val belowHealth: Int?,
    val npc: String?, val count: Int?, val percent: Int?, val shout: String?
)

class BossDrop(val item: String, val min: Int?, val max: Int?, val chance: Double?, val perLevel: Boolean?)

class BossDefinition(
    val id: String, val name: String, val npc: String, val level: Double?, val hitpoints: Double?, val damage: Double?, val respawnMinutes: Int?,
    val abilities: List<BossAbility>?, val drops: List<BossDrop>?
) {
    val levelFactor get() = level ?: DEFAULT_FACTOR
    val hitpointFactor get() = hitpoints ?: DEFAULT_FACTOR
    val damageFactor get() = damage ?: DEFAULT_FACTOR
}

object DungeonBosses {
    private val gson = Gson()
    private val npcIds = ConcurrentHashMap<String, Int>()
    private val objIds = ConcurrentHashMap<String, Int>()

    val definitions: Map<String, BossDefinition> by lazy {
        File(DIRECTORY).listFiles { file -> file.isFile && file.extension == EXTENSION }.orEmpty().sortedBy { it.name }
            .map { gson.fromJson(it.readText(), BossDefinition::class.java) }.associateBy { it.id }
    }

    fun npcId(name: String): Int? = named(npcIds, Npc::class.java, name)

    fun objId(name: String): Int? = named(objIds, Obj::class.java, name)

    private fun named(known: ConcurrentHashMap<String, Int>, catalog: Class<*>, name: String): Int? =
        known.computeIfAbsent(name) { it.toIntOrNull() ?: runCatching { catalog.getField(it).getInt(null) }.getOrDefault(MISSING) }.takeIf { it != MISSING }

    fun problems(definition: BossDefinition): List<String> {
        val problems = ArrayList<String>()
        if (npcId(definition.npc) == null) problems += "npc '${definition.npc}' is no npc gameval"
        definition.abilities.orEmpty().forEach { ability ->
            if (ability.type !in setOf(SLAM, SUMMON, ENRAGE, HEAL)) problems += "ability type '${ability.type}' is unknown"
            if (ability.type == SUMMON && ability.npc?.let(::npcId) == null) problems += "summon npc '${ability.npc}' is no npc gameval"
        }
        definition.drops.orEmpty().forEach { if (objId(it.item) == null) problems += "drop item '${it.item}' is no obj gameval" }
        return problems
    }

    fun spawn(slot: DungeonSlot): NPC? {
        val dungeon = slot.dungeon
        val definition = definitions[dungeon.bossId]
        val id = definition?.let { npcId(it.npc) }
        if (definition == null || id == null) {
            logInfo("Dungeon ${dungeon.id} has no usable boss '${dungeon.bossId}'")
            slot.died(DungeonMobs.respawnTicks(null))
            return null
        }
        val level = DungeonMobs.bossLevel(dungeon, definition)
        return DungeonBoss(definition, id, slot.tile, level.toDouble() / DungeonMobs.native(id), level, slot)
    }
}

class DungeonBoss(private val definition: BossDefinition, id: Int, home: Tile, scale: Double, private val level: Int, slot: DungeonSlot) :
    DungeonMob(id, home, scale, level, slot) {

    private val hitpointFactor = definition.hitpointFactor
    private val damageFactor = definition.damageFactor
    private val abilities = definition.abilities.orEmpty()
    private val used = HashSet<Pair<BossAbility, Int>>()
    private val minions = ArrayList<NPC>()
    private var rage = DEFAULT_FACTOR
    private var fightTicks = 0
    private var idleTicks = 0

    init {
        setPermName(definition.name)
        setForceMultiAttacked(true)
        hitpoints = maxHitpoints
    }

    override fun getMaxHitpoints(): Int = (super.getMaxHitpoints() * if (hitpointFactor > 0.0) hitpointFactor else DEFAULT_FACTOR).toInt().coerceAtLeast(1)

    override fun getMaxHit(): Int = (super.getMaxHit() * (if (damageFactor > 0.0) damageFactor else DEFAULT_FACTOR) * if (rage > 0.0) rage else DEFAULT_FACTOR).toInt()

    override fun respawnTicks(): Int = DungeonMobs.respawnTicks(definition.respawnMinutes)

    override val dropsCoins: Boolean get() = true

    override fun processNPC() {
        super.processNPC()
        if (isDead || hasFinished()) return
        if (combat.target == null) {
            if (fightTicks > 0 && ++idleTicks >= RESET_TICKS) calm()
            return
        }
        idleTicks = 0
        fightTicks++
        val health = hitpoints * PERCENT / maxHitpoints
        for (ability in abilities) when (ability.type) {
            SLAM -> if (fightTicks % (ability.everyTicks ?: DEFAULT_EVERY) == 0) slam(ability)
            HEAL -> if (fightTicks % (ability.everyTicks ?: DEFAULT_EVERY) == 0) mend(ability)
            SUMMON -> ability.atHealth.orEmpty().forEach { threshold -> if (health <= threshold && used.add(ability to threshold)) summon(ability) }
            ENRAGE -> if (health <= (ability.belowHealth ?: 0) && used.add(ability to 0)) enrage(ability)
        }
    }

    private fun shout(ability: BossAbility) {
        ability.shout?.let(::forceTalk)
    }

    private fun slam(ability: BossAbility) {
        shout(ability)
        val centre = Tile.of(tile)
        val radius = ability.radius ?: DEFAULT_RADIUS
        WorldTasks.schedule(SLAM_WARNING_TICKS) {
            if (isDead || hasFinished()) return@schedule
            val damage = (maxHit * (ability.damage ?: DEFAULT_FACTOR)).toInt()
            World.players.filter { !it.hasFinished() && !it.isDead && it.plane == centre.plane && Utils.getDistance(it.tile, centre) <= radius }
                .forEach { it.applyHit(Hit.flat(this, Utils.random(damage / 2, damage + 1))) }
        }
    }

    private fun mend(ability: BossAbility) {
        shout(ability)
        heal(maxHitpoints * (ability.percent ?: 0) / PERCENT)
    }

    private fun enrage(ability: BossAbility) {
        shout(ability)
        rage = ability.damage ?: DEFAULT_FACTOR
    }

    private fun summon(ability: BossAbility) {
        val id = ability.npc?.let(DungeonBosses::npcId) ?: return
        shout(ability)
        val minionLevel = (level * MINION_FACTOR).toInt().coerceAtLeast(1)
        repeat(ability.count ?: DEFAULT_COUNT) {
            val spot = (0 until MINION_ATTEMPTS).map { tile.transform(Utils.randomInclusive(-MINION_REACH, MINION_REACH), Utils.randomInclusive(-MINION_REACH, MINION_REACH), 0) }
                .firstOrNull { World.floorAndWallsFree(it, 1) } ?: return@repeat
            minions += DungeonMob(id, spot, minionLevel.toDouble() / DungeonMobs.native(id), minionLevel, null)
        }
    }

    private fun dismiss() {
        minions.forEach { if (!it.hasFinished()) it.finish() }
        minions.clear()
    }

    private fun calm() {
        dismiss()
        used.clear()
        rage = DEFAULT_FACTOR
        fightTicks = 0
        idleTicks = 0
        hitpoints = maxHitpoints
    }

    override fun sendDeath(source: Entity) {
        dismiss()
        super.sendDeath(source)
    }

    override fun drop(killer: Player, verifyCombatDefs: Boolean) {
        super.drop(killer, verifyCombatDefs)
        killer.sendMessage("You have defeated ${definition.name}.")
        for (drop in definition.drops.orEmpty()) {
            val id = DungeonBosses.objId(drop.item) ?: continue
            if (Math.random() >= (drop.chance ?: DEFAULT_FACTOR)) continue
            val low = drop.min ?: 1
            val amount = Utils.randomInclusive(low, maxOf(low, drop.max ?: low)) * if (drop.perLevel == true) level else 1
            sendDrop(killer, Item(id, amount))
        }
    }
}
