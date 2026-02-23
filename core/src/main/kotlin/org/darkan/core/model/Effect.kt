package org.darkan.core.model

/**
 * Timed effects that can be applied to entities.
 * Unlike the legacy monolith, behavior callbacks are registered externally via the event system.
 */
enum class Effect(val displayName: String? = null, val removeOnDeath: Boolean = true) {
    SKULL,
    ANTIPOISON("poison immunity"),
    STUN,
    FREEZE,
    FREEZE_BLOCK,
    MIASMIC_SLOWDOWN,
    MIASMIC_BLOCK,
    TELEBLOCK("teleblock"),
    DOUBLE_XP("double xp", removeOnDeath = false),
    ANTIFIRE("antifire"),
    SUPER_ANTIFIRE("super-antifire"),
    PRAYER_RENEWAL("prayer renewal"),
    BARON_SHARK,
    STAFF_OF_LIGHT_SPEC("staff of light protection"),
    JUJU_MINING("juju mining potion", removeOnDeath = false),
    JUJU_MINE_BANK,
    JUJU_WOODCUTTING("juju woodcutting potion", removeOnDeath = false),
    JUJU_WC_BANK,
    JUJU_FARMING("juju farming potion", removeOnDeath = false),
    JUJU_FISHING("juju fishing potion", removeOnDeath = false),
    SCENTLESS("scentless potion", removeOnDeath = false),
    JUJU_HUNTER("juju hunter potion", removeOnDeath = false),
    SARA_BLESSING("Saradomin's blessing", removeOnDeath = false),
    GUTHIX_GIFT("Guthix's gift", removeOnDeath = false),
    ZAMMY_FAVOR("Zamorak's favour", removeOnDeath = false),
    MAMBO_WARDING("Papa Mambo's Warding", removeOnDeath = false),
    REV_IMMUNE("immunity to revenants", removeOnDeath = false),
    REV_AGGRO_IMMUNE("revenant aggression immunity", removeOnDeath = false),
    CHARGED("god charge", removeOnDeath = false),
    DUNG_HS_SCROLL_BOOST("hoardstalker boost"),
    AGGRESSION_POTION("aggression potion", removeOnDeath = false),
    OVERLOAD_PVP_REDUCTION(removeOnDeath = true),
    MELEE_IMMUNE(removeOnDeath = true),
    BONFIRE("bonfire boost", removeOnDeath = false),
    BLOOD_NECKLACE("blood necklace"),
    STONE_OF_JAS("Stone of Jas"),
    EXCALIBUR_HEAL,
    OVERLOAD("overload"),
    OOG_BANDOS_POOL("Bandos pool"),
    OOG_SALTWATER_POOL("saltwater spring"),
    OOG_THERMAL_POOL("thermal bath"),
    FARMERS_AFFINITY("farmer's affinity"),
    PATCH_BOMB("patch bomb"),
    SHOOTING_STAR_MINING_BUFF("star sprite's power", removeOnDeath = false),
    EVIL_TREE_WOODCUTTING_BUFF("evil tree magic", removeOnDeath = false),
    LIVID_FISH_COOKING_BOOST("Reduce The Fish I Burn Wish"),
    LIVID_PLANKS_BOOST("More Planks Please Wish"),
    LUNARS_SPELLBOOK_SWAP("Spellbook Swap"),
    LUNARS_DISRUPTION_SHIELD("disruption shield"),
    LUNARS_DREAM("dream");

    val sendWarnings get() = displayName != null
    fun get30SecWarning() = "<col=FF0000>Your $displayName is going to run out in 30 seconds!"
    fun getExpiryMessage() = "<col=FF0000>Your $displayName has run out!"
}
