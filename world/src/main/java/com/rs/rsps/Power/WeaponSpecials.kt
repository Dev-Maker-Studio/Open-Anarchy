package com.rs.rsps.Power

import com.rs.game.World
import com.rs.game.content.combat.CombatSpell
import com.rs.game.content.combat.CombatStyle
import com.rs.game.content.combat.RangedWeapon
import com.rs.game.content.combat.calculateHit
import com.rs.game.content.combat.calculateMagicHit
import com.rs.game.content.combat.delayHit
import com.rs.game.content.combat.delayMagicHit
import com.rs.game.content.combat.dropAmmo
import com.rs.game.content.combat.getRangeCombatDelay
import com.rs.game.content.combat.special_attacks.SpecialAttack
import com.rs.game.content.combat.special_attacks.addSpec
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.utils.ItemConfig
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Spotanim

private const val BOLT_SOUND = 1080

@ServerStartupEvent
fun mapWeaponSpecials() {
    addSpec(intArrayOf(Obj.obj_18355), SpecialAttack(SpecialAttack.Type.MAGIC, 25) { player, target ->
        val hit = calculateMagicHit(player, target, 400, true)
        delayMagicHit(target, CombatSpell.FIRE_SURGE.cast(player, target), hit, {
            target.spotAnim(CombatSpell.WIND_RUSH.hitSpotAnim)
        }, null, null)
        1
    })

    addSpec(RangedWeapon.RUNE_CROSSBOW.ids, SpecialAttack(SpecialAttack.Type.RANGE, 100) { player, target ->
        empoweredBolt(player, target, heal = false)
    })

    addSpec(RangedWeapon.ARMADYL_CROSSBOW.ids, SpecialAttack(SpecialAttack.Type.RANGE, 50) { player, target ->
        empoweredBolt(player, target, heal = true)
    })

    addSpec(RangedWeapon.ROYAL_CROSSBOW.ids, SpecialAttack(SpecialAttack.Type.RANGE, 25) { player, target ->
        dorgeshuunBoltAnim(player)
        val hit = calculateHit(player, target, CombatStyle.RANGE, true, 1.0, 1.3)
        if (hit.damage > 0)
            target.lowerStat(Skills.DEFENSE, hit.damage / 10, 0.0)
        delayHit(target, boltProjectile(player, target), hit)
        dropAmmo(player, target, Equipment.AMMO, 1)
        player.soundEffect(target, BOLT_SOUND, true)
        getRangeCombatDelay(player)
    })
}

private fun dorgeshuunBoltAnim(player: Player) {
    player.anim(ItemConfig.get(RangedWeapon.DORGESHUUN_CBOW.ids[0]).getAttackAnim(0))
    player.spotAnim(RangedWeapon.DORGESHUUN_CBOW.getAttackSpotAnim(player.equipment.ammoId))
}

private fun boltProjectile(player: Player, target: Entity): Int =
    World.sendProjectile(player, target, Spotanim.spotanim_698, delay = 50, speed = 5, angle = 20).taskDelay

private fun empoweredBolt(player: Player, target: Entity, heal: Boolean): Int {
    dorgeshuunBoltAnim(player)
    val delay = boltProjectile(player, target)
    val hit = calculateHit(player, target, CombatStyle.RANGE)
    hit.setDamage((hit.damage * 1.35).toInt())
    delayHit(target, delay, hit)
    dropAmmo(player, target, Equipment.AMMO, 1)
    if (heal)
        player.heal(hit.damage)
    player.soundEffect(target, BOLT_SOUND, true)
    return getRangeCombatDelay(player)
}
