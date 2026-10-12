package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt

data class NpcUpdate(val data: ByteArray) : ServerProt

data class PlayerUpdate(val data: ByteArray) : ServerProt

data class NpcUpdateLarge(val data: ByteArray) : ServerProt

@JvmInline value class UpdateUid192(val data: ByteArray) : ServerProt

data class UpdateStat(val xp: Int, val skillId: Int, val level: Int) : ServerProt

@JvmInline value class SetDrawOrder(val playerFirst: Boolean) : ServerProt

@JvmInline value class ResetAllAnimations(val dummy: Int = 0) : ServerProt

@JvmInline value class PlayerWeight(val weight: Int) : ServerProt

data class PlayerOption(val option: String, val slot: Int, val top: Boolean, val cursor: Int) : ServerProt

@JvmInline value class RunEnergy(val energy: Int) : ServerProt

@JvmInline value class ReduceAttackPriority(val enabled: Boolean) : ServerProt

@JvmInline value class SetTarget(val target: Int) : ServerProt
