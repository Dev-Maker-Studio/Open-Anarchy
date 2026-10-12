package org.darkan.core.update


sealed interface AppearanceSlot {
    data object Empty : AppearanceSlot

    @JvmInline value class Body(val style: Int) : AppearanceSlot

    @JvmInline value class Worn(val itemId: Int) : AppearanceSlot
}

data class AppearanceTitle(val text: String, val after: Boolean)

data class NpcRenderAnimations(
    val walk: Int,
    val rotate180: Int,
    val rotate90Right: Int,
    val rotate90Left: Int,
    val special: Int,
)

data class BodyModelOverride(
    val male: IntArray,
    val female: IntArray,
    val includeSecondary: Boolean,
    val includeTertiary: Boolean,
) {
    init {
        require(male.size == 3 && female.size == 3) { "BodyModelOverride requires 3 male and 3 female model ids" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BodyModelOverride
        if (includeSecondary != other.includeSecondary) return false
        if (includeTertiary != other.includeTertiary) return false
        if (!male.contentEquals(other.male)) return false
        return female.contentEquals(other.female)
    }

    override fun hashCode(): Int {
        var result = male.contentHashCode()
        result = 31 * result + female.contentHashCode()
        result = 31 * result + includeSecondary.hashCode()
        result = 31 * result + includeTertiary.hashCode()
        return result
    }
}

data class HeadModelOverride(
    val male: IntArray,
    val female: IntArray,
    val includeSecondary: Boolean,
) {
    init {
        require(male.size == 2 && female.size == 2) { "HeadModelOverride requires 2 male and 2 female model ids" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HeadModelOverride
        if (includeSecondary != other.includeSecondary) return false
        if (!male.contentEquals(other.male)) return false
        return female.contentEquals(other.female)
    }

    override fun hashCode(): Int {
        var result = male.contentHashCode()
        result = 31 * result + female.contentHashCode()
        result = 31 * result + includeSecondary.hashCode()
        return result
    }
}

data class ItemMeshOverride(
    val slot: Int,
    val bodyModels: BodyModelOverride? = null,
    val headModels: HeadModelOverride? = null,
    val colours: IntArray? = null,
    val textures: IntArray? = null,
) {
    val isEmpty: Boolean
        get() = bodyModels == null && headModels == null && colours == null && textures == null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ItemMeshOverride
        if (slot != other.slot) return false
        if (bodyModels != other.bodyModels) return false
        if (headModels != other.headModels) return false
        if (!colours.contentEquals(other.colours)) return false
        return textures.contentEquals(other.textures)
    }

    override fun hashCode(): Int {
        var result = slot
        result = 31 * result + (bodyModels?.hashCode() ?: 0)
        result = 31 * result + (headModels?.hashCode() ?: 0)
        result = 31 * result + (colours?.contentHashCode() ?: 0)
        result = 31 * result + (textures?.contentHashCode() ?: 0)
        return result
    }
}

data class PlayerAppearanceData(
    val male: Boolean,
    val npcTransformSizeFlag: Boolean,
    val showSkillTotal: Boolean,
    val title: AppearanceTitle?,
    val skullIcon: Int,
    val headIcon: Int,
    val hidden: Boolean,
    val transformedNpcId: Int,
    val slots: List<AppearanceSlot>,
    val meshOverrides: List<ItemMeshOverride>,
    val colours: IntArray,
    val renderEmote: Int,
    val displayName: String,
    val combatLevel: Int,
    val totalLevel: Int,
    val summoningCombatLevel: Int,
    val pvpCombatLevelThreshold: Int,
    val npcRenderAnimations: NpcRenderAnimations?,
) {
    init {
        require(colours.size == COLOUR_COUNT) { "PlayerAppearanceData.colours must be length $COLOUR_COUNT" }
        require(transformedNpcId < 0 || npcRenderAnimations != null) {
            "npcRenderAnimations are required while transformed into an NPC"
        }
    }

    val transformed: Boolean
        get() = transformedNpcId >= 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PlayerAppearanceData
        if (male != other.male) return false
        if (npcTransformSizeFlag != other.npcTransformSizeFlag) return false
        if (showSkillTotal != other.showSkillTotal) return false
        if (title != other.title) return false
        if (skullIcon != other.skullIcon) return false
        if (headIcon != other.headIcon) return false
        if (hidden != other.hidden) return false
        if (transformedNpcId != other.transformedNpcId) return false
        if (slots != other.slots) return false
        if (meshOverrides != other.meshOverrides) return false
        if (!colours.contentEquals(other.colours)) return false
        if (renderEmote != other.renderEmote) return false
        if (displayName != other.displayName) return false
        if (combatLevel != other.combatLevel) return false
        if (totalLevel != other.totalLevel) return false
        if (summoningCombatLevel != other.summoningCombatLevel) return false
        if (pvpCombatLevelThreshold != other.pvpCombatLevelThreshold) return false
        return npcRenderAnimations == other.npcRenderAnimations
    }

    override fun hashCode(): Int {
        var result = male.hashCode()
        result = 31 * result + npcTransformSizeFlag.hashCode()
        result = 31 * result + showSkillTotal.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + skullIcon
        result = 31 * result + headIcon
        result = 31 * result + hidden.hashCode()
        result = 31 * result + transformedNpcId
        result = 31 * result + slots.hashCode()
        result = 31 * result + meshOverrides.hashCode()
        result = 31 * result + colours.contentHashCode()
        result = 31 * result + renderEmote
        result = 31 * result + displayName.hashCode()
        result = 31 * result + combatLevel
        result = 31 * result + totalLevel
        result = 31 * result + summoningCombatLevel
        result = 31 * result + pvpCombatLevelThreshold
        result = 31 * result + (npcRenderAnimations?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val COLOUR_COUNT = 10
    }
}
