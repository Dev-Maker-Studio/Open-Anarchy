package com.rs.game.content

import com.rs.game.content.combat.CombatDefinitions
import com.rs.game.content.minigames.herblorehabitat.JadinkoType
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.player.Player
import com.rs.utils.Ticks
import org.darkan.core.Constants
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.gameval.gen.Sound
import org.darkan.core.gameval.gen.Spotanim

enum class Effect(private val displayName: String? = null, val isRemoveOnDeath: Boolean = true) {
    SKULL {
        override fun apply(entity: Entity) {
            if (entity is Player) entity.appearance.generateAppearanceData()
        }

        override fun expire(entity: Entity) {
            if (entity is Player) entity.appearance.generateAppearanceData()
        }
    },
    ANTIPOISON("poison immunity") {
        override fun apply(entity: Entity) {
            entity.poison.reset()
        }
    },
    STUN,
    FREEZE,
    FREEZE_BLOCK,
    MIASMIC_SLOWDOWN,
    MIASMIC_BLOCK,
    TELEBLOCK("teleblock"),
    LIVID_FISH_COOKING_BOOST("Reduce The Fish I Burn Wish"),
    LIVID_PLANKS_BOOST("More Planks Please Wish"),
    LUNARS_SPELLBOOK_SWAP("Spellbook Swap") {
        override fun sendWarnings() = false

        override fun expire(entity: Entity) {
            if (entity !is Player) return
            entity.combatDefinitions.setSpellbook(CombatDefinitions.Spellbook.LUNAR)
            entity.combatDefinitions.setAutoCastSpell(null)
            entity.sendMessage("Your spellbook has returned to Lunar Spellbook.")
        }
    },
    LUNARS_DISRUPTION_SHIELD("disruption shield") {
        override fun sendWarnings() = false

        override fun apply(entity: Entity) {
            if (entity is Player) entity.sendMessage("You are protected by a Disruption Shield.")
        }
    },
    LUNARS_DREAM("dream") {
        override fun sendWarnings() = false
    },
    DOUBLE_XP("double xp", isRemoveOnDeath = false),
    ANTIFIRE("antifire"),
    SUPER_ANTIFIRE("super-antifire"),
    PRAYER_RENEWAL("prayer renewal") {
        override fun tick(entity: Entity, tickNumber: Long) {
            if (entity !is Player || entity.prayer.hasFullPoints()) return
            entity.prayer.restorePrayer(entity.skills.getLevelForXp(Constants.PRAYER) * 4.3 / 600.0 + 0.2)
            if (tickNumber % 25 == 0L) entity.spotAnim(Spotanim.lore_boost_receive)
        }
    },
    BARON_SHARK {
        override fun tick(entity: Entity, tickNumber: Long) {
            if (tickNumber % 2 == 0L) entity.heal(10)
        }
    },
    STAFF_OF_LIGHT_SPEC("staff of light protection"),
    JUJU_MINING("juju mining potion", isRemoveOnDeath = false),
    JUJU_MINE_BANK,
    JUJU_WOODCUTTING("juju woodcutting potion", isRemoveOnDeath = false),
    JUJU_WC_BANK,
    JUJU_FARMING("juju farming potion", isRemoveOnDeath = false),
    JUJU_FISHING("juju fishing potion", isRemoveOnDeath = false),
    SCENTLESS("scentless potion", isRemoveOnDeath = false),
    JUJU_HUNTER("juju hunter potion", isRemoveOnDeath = false),
    SARA_BLESSING("Saradomin's blessing", isRemoveOnDeath = false),
    GUTHIX_GIFT("Guthix's gift", isRemoveOnDeath = false),
    ZAMMY_FAVOR("Zamorak's favour", isRemoveOnDeath = false),
    MAMBO_WARDING("Papa Mambo's Warding", isRemoveOnDeath = false) {
        override fun sendWarnings() = false

        override fun expire(entity: Entity) {
            if (entity !is Player) return
            val type = entity.savingAttributes.remove("MAMBO_WARDING_TYPE") as? JadinkoType ?: return
            entity.sendMessage("<col=FF0000>Papa Mambo has stopped warding off ${type.name.lowercase().replace('_', ' ')} jadinkos!")
        }
    },
    REV_IMMUNE("immunity to revenants", isRemoveOnDeath = false),
    REV_AGGRO_IMMUNE("revenant aggression immunity", isRemoveOnDeath = false),
    CHARGED("god charge", isRemoveOnDeath = false),
    DUNG_HS_SCROLL_BOOST("hoardstalker boost"),
    AGGRESSION_POTION("aggression potion", isRemoveOnDeath = false),
    OVERLOAD_PVP_REDUCTION,
    MELEE_IMMUNE,
    BONFIRE("bonfire boost", isRemoveOnDeath = false) {
        override fun apply(entity: Entity) {
            if (entity is Player) entity.equipment.refreshConfigs(false)
        }

        override fun expire(entity: Entity) {
            if (entity is Player) entity.equipment.refreshConfigs(false)
        }
    },
    BLOOD_NECKLACE("blood necklace") {
        override fun sendWarnings() = false

        override fun tick(entity: Entity, tickNumber: Long) {
            if (tickNumber % Ticks.fromSeconds(10) != 0L || entity !is Player || !entity.dungManager.isInsideDungeon) return
            val targets = entity.queryNearbyNPCsByTileRange(1) { npc ->
                !npc.isDead && npc.withinDistance(entity, 1) && npc.definitions.hasAttackOption() &&
                    entity.controllerManager.canHit(npc) && npc.combatTarget is Player
            }
            for (npc in targets) {
                val damage = 40 * entity.skills.combatLevelWithSummoning / 138
                npc.applyHit(Hit(entity, damage, Hit.HitLook.TRUE_DAMAGE))
                entity.heal(damage)
            }
        }
    },
    STONE_OF_JAS("Stone of Jas") {
        override fun apply(entity: Entity) {
            entity.sync(Seq.mah5_player_receive_boost, Spotanim.mah5_player_receive_boost_spotanim)
            if (entity is Player) entity.sendMessage("The residual energy from the Stone of Jas empowers you, increasing your damage.")
        }

        override fun tick(entity: Entity, tickNumber: Long) {
            if (tickNumber % 50 == 0L) entity.spotAnim(Spotanim.mah5_player_receive_boost_spotanim)
        }
    },
    EXCALIBUR_HEAL {
        override fun tick(entity: Entity, tickNumber: Long) {
            if (tickNumber % 7 != 0L) return
            entity.heal(40)
            entity.spotAnim(Spotanim.lore_spinner_heal_spot)
        }
    },
    OVERLOAD("overload") {
        override fun apply(entity: Entity) {
            if (entity is Player) Potion.applyOverloadEffect(entity)
        }

        override fun tick(entity: Entity, tickNumber: Long) {
            if (tickNumber % 25 == 0L && entity is Player) Potion.applyOverloadEffect(entity)
        }

        override fun expire(entity: Entity) {
            if (entity !is Player) return
            if (!entity.isDead) {
                for (stat in intArrayOf(Constants.ATTACK, Constants.STRENGTH, Constants.DEFENSE, Constants.MAGIC, Constants.RANGE)) {
                    val base = entity.skills.getLevelForXp(stat)
                    if (entity.skills.getLevel(stat) > base) entity.skills.set(stat, base)
                }
                entity.heal(500)
            }
            entity.soundEffect(Sound.sound_2607, false)
            entity.sendMessage("<col=480000>The effects of overload have worn off and you feel normal again.")
        }
    },
    OOG_BANDOS_POOL("Bandos pool"),
    OOG_SALTWATER_POOL("saltwater spring") {
        override fun apply(entity: Entity) {
            if (entity is Player) entity.restoreRunEnergy(entity.skills.getLevel(Constants.AGILITY).toDouble())
        }

        override fun tick(entity: Entity, tickNumber: Long) {
            if (tickNumber % Ticks.fromSeconds(3) == 0L && entity is Player)
                entity.restoreRunEnergy(entity.skills.getLevel(Constants.AGILITY).toDouble())
        }
    },
    OOG_THERMAL_POOL("thermal bath") {
        override fun apply(entity: Entity) {
            if (entity !is Player) return
            entity.refreshHitPoints()
            entity.addEffect(ANTIPOISON, Ticks.fromMinutes(15).toLong())
        }

        override fun expire(entity: Entity) {
            if (entity is Player) entity.refreshHitPoints()
        }
    },
    FARMERS_AFFINITY("farmer's affinity"),
    PATCH_BOMB("patch bomb"),
    SHOOTING_STAR_MINING_BUFF("star sprite's power", isRemoveOnDeath = false),
    EVIL_TREE_WOODCUTTING_BUFF("evil tree magic", isRemoveOnDeath = false);

    open fun apply(entity: Entity) {}

    open fun tick(entity: Entity, tickNumber: Long) {}

    open fun expire(entity: Entity) {}

    open fun sendWarnings() = displayName != null

    fun get30SecWarning() = "<col=FF0000>Your $displayName is going to run out in 30 seconds!"

    fun getExpiryMessage() = "<col=FF0000>Your $displayName has run out!"
}
