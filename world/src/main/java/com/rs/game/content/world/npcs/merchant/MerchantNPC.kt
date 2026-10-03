package com.rs.game.content.world.npcs.merchant

import com.rs.engine.pathfinder.DumbRouteFinder
import com.rs.game.World
import com.rs.game.content.combat.RangedWeapon
import com.rs.game.content.skills.cooking.Foods
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.utils.maleEquip1
import com.rs.utils.maleEquip2
import org.darkan.core.Utils
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Bonus
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile

private const val STARTING_COINS = 250
private const val NO_MODEL = -1
private const val SPARE_MODELS = 16
private const val NO_RENDER_ANIM = -1
private const val WANDER_CHANCE = 0.3
private const val WANDER_RADIUS = 12
private const val WANDER_STEPS = 8
private const val WANDER_PAUSE_TICKS = 15
private const val EAT_BELOW_FRACTION = 0.6
private const val EAT_DELAY_TICKS = 3
private const val BASE_MAX_HIT = 5
private const val LEVEL_BOOST = 8
private const val STRENGTH_BASE = 64.0

private val GEAR_SLOTS = setOf(
    Equipment.HEAD, Equipment.CAPE, Equipment.NECK, Equipment.WEAPON, Equipment.CHEST, Equipment.SHIELD,
    Equipment.LEGS, Equipment.HANDS, Equipment.FEET, Equipment.RING, Equipment.AMMO
)

class MerchantNPC(id: Int, tile: Tile) : NPC(id, tile, null, true) {
    var coins = STARTING_COINS
        private set
    var weaponId = -1
        private set

    val shop = MerchantShop(this)

    private var bonuses = IntArray(Bonus.entries.size)
    private var dressed = false
    private var renderAnim = NO_RENDER_ANIM
    private var pauseWanderUntil = 0L
    private var nextEatTick = 0L

    init {
        isNoDistanceCheck = true
    }

    fun canTrade() = !isDead && !hasFinished()

    fun earn(amount: Int) {
        coins = (coins.toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun pay(amount: Int) {
        coins = (coins - amount).coerceAtLeast(0)
    }

    fun trade(player: Player) {
        pauseWandering()
        if (player.bank.checkPin()) shop.addPlayer(player)
    }

    fun pauseWandering() {
        pauseWanderUntil = tickCounter.toLong() + WANDER_PAUSE_TICKS
    }

    fun stockChanged() {
        val worn = bestGear(shop.stock())
        bonuses = IntArray(Bonus.entries.size) { index -> worn.sumOf { Equipment.getBonus(it, Bonus.entries[index]) } }
        weaponId = worn.firstOrNull { Equipment.getItemSlot(it.id) == Equipment.WEAPON }?.id ?: -1
        dress(worn)
    }

    override fun getCombatBonus(bonus: Bonus): Int = bonuses[bonus.ordinal]

    override fun getMaxHit(): Int {
        val strength = getCombatBonus(Bonus.MELEE_STR)
        return (BASE_MAX_HIT + (strengthLevel + LEVEL_BOOST) * (strength + STRENGTH_BASE) / STRENGTH_BASE).toInt()
    }

    override fun processNPC() {
        super.processNPC()
        if (isDead || isLocked) return
        eatWhenHurt()
    }

    override fun randomWalk() {
        if (hasWalkSteps() || tickCounter < pauseWanderUntil || Math.random() >= WANDER_CHANCE) return
        val target = tile.transform(Utils.randomInclusive(-WANDER_RADIUS, WANDER_RADIUS), Utils.randomInclusive(-WANDER_RADIUS, WANDER_RADIUS), 0)
        resetWalkSteps()
        DumbRouteFinder.addDumbPathfinderSteps(this, target, WANDER_STEPS, collisionStrategy)
    }

    override fun sendDeath(source: Entity) {
        shop.closeForViewers()
        super.sendDeath(source)
    }

    override fun drop() {
        val killer = mostDamageReceivedSourcePlayer
        val loot = shop.stock().map { Item(it.id, it.amount) } + Item(Obj.coins, coins)
        loot.filter { it.amount > 0 }.forEach { World.addGroundItem(it, tile, killer, killer != null) }
    }

    private fun eatWhenHurt() {
        if (hitpoints >= maxHitpoints * EAT_BELOW_FRACTION || tickCounter < nextEatTick) return
        val meal = shop.stock()
            .mapNotNull { item -> Foods.Food.forId(item.id)?.takeIf { it.heal > 0 }?.let { item.id to it.heal } }
            .maxByOrNull { it.second } ?: return
        if (!shop.consume(meal.first)) return
        anim(Foods.EAT_ANIM)
        heal(meal.second)
        nextEatTick = tickCounter.toLong() + EAT_DELAY_TICKS
    }

    private fun bestGear(stock: List<Item>): List<Item> {
        val bySlot = stock
            .filter { isWearable(it) }
            .groupBy { Equipment.getItemSlot(it.id) }
            .mapValues { (_, items) -> items.maxWith(compareBy<Item>({ tier(it) }, { it.definitions.getValue() })) }
        val twoHanded = bySlot[Equipment.WEAPON]?.let { Equipment.isTwoHandedWeapon(it) } == true
        return bySlot.filterKeys { !(twoHanded && it == Equipment.SHIELD) }.values.toList()
    }

    private fun isWearable(item: Item) =
        Equipment.getItemSlot(item.id) in GEAR_SLOTS && item.definitions.isWearItem(true) && RangedWeapon.forId(item.id) == null

    private fun tier(item: Item) = Bonus.entries.sumOf { maxOf(0, Equipment.getBonus(item, it)) }

    private fun dress(worn: List<Item>) {
        val base = definitions.modelIds
        val models = worn.flatMap { listOf(it.definitions.maleEquip1, it.definitions.maleEquip2) }.filter { it != NO_MODEL }.take(SPARE_MODELS)
        if (base != null && models.isNotEmpty()) {
            val ids = base.copyOf()
            val firstSpare = ids.size - SPARE_MODELS
            models.forEachIndexed { index, model -> ids[firstSpare + index] = model }
            modifyMesh().addModels(*ids)
            dressed = true
        } else if (dressed) {
            resetMesh()
            dressed = false
        }
        val render = if (weaponId == -1) NO_RENDER_ANIM else ItemDefinitions.getDefs(weaponId).getRenderAnimId()
        if (render != renderAnim) {
            bas = render
            renderAnim = render
        }
    }
}
