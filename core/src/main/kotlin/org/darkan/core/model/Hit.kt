package org.darkan.core.model

import org.darkan.core.update.HitBarUpdate
import org.darkan.core.update.HitSplat

enum class HitLook(val mark: Int) {
    MISSED(8),
    TRUE_DAMAGE(3),
    MELEE_DAMAGE(0),
    RANGE_DAMAGE(1),
    MAGIC_DAMAGE(2),
    REFLECTED_DAMAGE(4),
    ABSORB_DAMAGE(5),
    POISON_DAMAGE(6),
    DISEASE_DAMAGE(7),
    HEALED_DAMAGE(9),
    CANNON_DAMAGE(13);

    val critMark get() = mark + 10
    val otherMark get() = mark + 14
}

/**
 * Game-level hit event representing damage dealt between entities.
 * Converts to [HitSplat] + [HitBarUpdate] for rendering via the update protocol.
 */
class Hit(
    var sourceIndex: Int = -1,
    var damage: Int = 0,
    var look: HitLook = HitLook.MISSED,
    val delay: Int = 0,
) {
    var maxHit: Int = 0
    var critical: Boolean = false
    var text: String? = null
    private var soaking: Hit? = null
    val data: MutableMap<String, Any> = mutableMapOf()

    fun missed() = damage == 0

    fun getMark(isInteracting: Boolean): Int {
        if (look == HitLook.HEALED_DAMAGE) return look.mark
        if (damage == 0) return HitLook.MISSED.mark
        var mark = look.mark
        if (critical) mark += 10
        if (!isInteracting) mark += 14
        return mark
    }

    fun addSoaking(amount: Int): Hit {
        val soak = soaking
        if (soak == null) {
            soaking = Hit(sourceIndex, amount, HitLook.ABSORB_DAMAGE)
        } else {
            soak.damage += amount
        }
        return this
    }

    fun soakDamage(reductionPerc: Double) {
        val reduction = (damage * reductionPerc).toInt()
        if (reduction <= 0) return
        damage -= reduction
        addSoaking(reduction)
    }

    fun toHitSplat(isInteracting: Boolean): HitSplat {
        val mark = getMark(isInteracting)
        val soak = soaking
        return if (soak != null) {
            HitSplat.Soak(mark, damage, soak.look.mark, soak.damage, delay)
        } else if (text != null) {
            HitSplat.CustomText(mark, text!!, delay = delay)
        } else {
            HitSplat.Standard(mark, damage, delay)
        }
    }

    companion object {
        fun miss(sourceIndex: Int = -1) = Hit(sourceIndex, 0, HitLook.MISSED)
        fun melee(sourceIndex: Int, damage: Int) = Hit(sourceIndex, damage, HitLook.MELEE_DAMAGE)
        fun range(sourceIndex: Int, damage: Int) = Hit(sourceIndex, damage, HitLook.RANGE_DAMAGE)
        fun magic(sourceIndex: Int, damage: Int) = Hit(sourceIndex, damage, HitLook.MAGIC_DAMAGE)
        fun flat(sourceIndex: Int, damage: Int) = Hit(sourceIndex, damage, HitLook.TRUE_DAMAGE)
        fun heal(sourceIndex: Int, damage: Int) = Hit(sourceIndex, damage, HitLook.HEALED_DAMAGE)
    }
}
