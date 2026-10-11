package com.rs.game.content.skills.sailing

enum class Wood(val nails: String) {
    WOODEN("bronze"), OAK("iron"), TEAK("steel"), MAHOGANY("mithril"), CAMPHOR("adamantite"), IRONWOOD("rune"), ROSEWOOD("dragon")
}

enum class HullTier(
    val wood: Wood,
    val sailingLevel: Int,
    val constructionLevel: Int,
    private val raftHp: Int,
    private val skiffHp: Int,
    private val sloopHp: Int,
    val baseSpeed: Double,
    private val raftCap: Double,
    private val skiffCap: Double,
    private val sloopCap: Double,
    private val raftDefence: Int,
    private val skiffDefence: Int,
    private val sloopDefence: Int
) {
    WOODEN(Wood.WOODEN, 1, 1, 20, 30, 40, 1.5, 2.5, 3.0, 3.5, 1, 10, 15),
    OAK(Wood.OAK, 20, 8, 30, 45, 60, 1.5, 2.5, 3.0, 3.5, 5, 20, 25),
    TEAK(Wood.TEAK, 31, 23, 40, 60, 80, 2.0, 2.5, 3.0, 3.5, 10, 30, 35),
    MAHOGANY(Wood.MAHOGANY, 48, 41, 50, 75, 100, 2.0, 2.5, 3.0, 3.5, 15, 40, 45),
    CAMPHOR(Wood.CAMPHOR, 67, 59, 80, 120, 160, 2.5, 3.0, 3.0, 3.5, 20, 50, 55),
    IRONWOOD(Wood.IRONWOOD, 81, 75, 100, 150, 200, 2.5, 3.0, 3.0, 3.5, 25, 60, 65),
    ROSEWOOD(Wood.ROSEWOOD, 93, 84, 120, 180, 240, 3.0, 3.5, 3.5, 3.5, 30, 70, 75);

    fun hitpoints(type: BoatType): Int = when (type) {
        BoatType.RAFT -> raftHp
        BoatType.SKIFF -> skiffHp
        BoatType.SLOOP -> sloopHp
    }

    fun speedCap(type: BoatType): Double = when (type) {
        BoatType.RAFT -> raftCap
        BoatType.SKIFF -> skiffCap
        BoatType.SLOOP -> sloopCap
    }

    fun defence(type: BoatType): Int = when (type) {
        BoatType.RAFT -> raftDefence
        BoatType.SKIFF -> skiffDefence
        BoatType.SLOOP -> sloopDefence
    }
}

enum class HelmTier(val sailingLevel: Int, val constructionLevel: Int, val rapids: RapidsClass) {
    BRONZE(1, 1, RapidsClass.NONE),
    IRON(17, 14, RapidsClass.GENTLE),
    STEEL(38, 30, RapidsClass.GENTLE),
    MITHRIL(55, 47, RapidsClass.STRONG),
    ADAMANT(72, 59, RapidsClass.STRONG),
    RUNE(87, 81, RapidsClass.POWERFUL),
    DRAGON(96, 86, RapidsClass.DEADLY)
}

enum class RapidsClass { NONE, GENTLE, STRONG, POWERFUL, DEADLY }

enum class StormResistance { NONE, PARTIAL, FULL }

enum class SailTier(
    val sailingLevel: Int,
    val constructionLevel: Int,
    val boostTicks: Int,
    val accelerationBonus: Int,
    val storm: StormResistance,
    val trimXp: Double
) {
    WOODEN(1, 1, 20, 0, StormResistance.NONE, 10.5),
    OAK(24, 11, 22, 0, StormResistance.PARTIAL, 19.5),
    TEAK(36, 26, 24, 0, StormResistance.PARTIAL, 30.0),
    MAHOGANY(52, 45, 26, 0, StormResistance.PARTIAL, 48.0),
    CAMPHOR(68, 60, 30, 64, StormResistance.FULL, 64.0),
    IRONWOOD(83, 77, 33, 64, StormResistance.FULL, 80.0),
    ROSEWOOD(94, 85, 36, 64, StormResistance.FULL, 125.0);

    val acceleration: Double get() = accelerationBonus / ACCELERATION_DIVISOR + BASE_ACCELERATION

    private companion object {
        const val ACCELERATION_DIVISOR = 128.0
        const val BASE_ACCELERATION = 0.5
    }
}

enum class KeelTier(
    val sailingLevel: Int,
    val constructionLevel: Int,
    private val skiffHp: Int,
    private val sloopHp: Int,
    val armour: Int
) {
    BRONZE(1, 1, 50, 70, 100),
    IRON(22, 17, 50, 70, 200),
    STEEL(39, 32, 60, 90, 300),
    MITHRIL(54, 50, 60, 90, 400),
    ADAMANT(66, 62, 70, 100, 600),
    RUNE(85, 78, 80, 120, 800),
    DRAGON(97, 87, 80, 120, 1000);

    val flatArmour: Int get() = armour / ARMOUR_PER_FLAT

    fun hitpoints(type: BoatType): Int = when (type) {
        BoatType.RAFT -> 0
        BoatType.SKIFF -> skiffHp
        BoatType.SLOOP -> sloopHp
    }

    private companion object {
        const val ARMOUR_PER_FLAT = 100
    }
}
