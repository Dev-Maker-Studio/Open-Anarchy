package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.net.prot.*
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.secure.CRC

internal fun Codec.registerRev727UpdatePackets() {
    serverProt<PlayerUpdate>(opcode = 43, size = ProtSize.VarShort) { out ->
        out.writeBytes(Rev727UpdateProtocol.encodePlayerUpdate(snapshot, state))
    }

    serverProt<NpcUpdateLarge>(opcode = 47, size = ProtSize.VarShort) { out ->
        out.writeBytes(Rev727UpdateProtocol.encodeNpcUpdate(snapshot, state))
    }

    serverProt<NpcUpdate>(opcode = 6, size = ProtSize.VarShort) { out ->
        out.writeBytes(Rev727UpdateProtocol.encodeNpcUpdate(snapshot, state))
    }

    serverProt<UpdateInvPartial>(opcode = 4, size = ProtSize.VarShort) { out ->
        out.writeShort(containerId)
        out.writeByte(if (negativeKey) 1 else 0)
        for (update in updates) {
            out.writeSmart(update.slot)
            if (update.itemId == -1) {
                out.writeShort(0)
            } else {
                out.writeShort(update.itemId + 1)
                if (update.amount >= 255) {
                    out.writeByte(255)
                    out.writeInt(update.amount)
                } else {
                    out.writeByte(update.amount)
                }
            }
        }
    }

    serverProt<UpdateInvFull>(opcode = 5, size = ProtSize.VarShort) { out ->
        out.writeShort(containerId)
        out.writeByte(if (negativeKey) 1 else 0)
        out.writeShort(itemIds.size)
        for (i in itemIds.indices) {
            val amount = amounts[i]
            if (amount >= 255) {
                out.writeByteSubtract(255)
                out.writeIntInverseMiddle(amount)
            } else {
                out.writeByteSubtract(amount)
            }
            out.writeShortAddLittle(itemIds[i] + 1)
        }
    }

    serverProt<UpdateInvStopTransmit>(opcode = 42, size = 3) { out ->
        out.writeByteInverse(if (negativeKey) 1 else 0)
        out.writeShortLittle(containerId)
    }

    serverProt<UpdateStat>(opcode = 140, size = 6) { out ->
        out.writeInt(xp)
        out.writeByte(skillId)
        out.writeByte(level)
    }

    serverProt<UpdateGESlot>(opcode = 57, size = 20) { out ->
        out.writeByte(slot)
        val offer = offer
        if (offer == null) {
            out.writeByte(0)
            out.writeBytes(ByteArray(18))
        } else {
            out.writeByte(offer.progress)
            out.writeShort(offer.itemId)
            out.writeInt(offer.price)
            out.writeInt(offer.amount)
            out.writeInt(offer.currentAmount)
            out.writeInt(offer.totalPrice)
        }
    }

    serverProt<UpdateUid192>(opcode = 90, size = 28) { out ->
        out.writeBytes(data)
        out.writeInt(CRC.calculate(data))
    }

    serverProt<ClanChannelDelta>(opcode = 28, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        out.writeBytes(data)
    }

    serverProt<ClanChannelFull>(opcode = 49, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        if (clanName != null) {
            val chatterList = chatters ?: emptyArray()
            out.writeByte(2) // nameTypeFlag: names as string
            out.writeLong(4062231702422979939L) // fixed pointer UID
            out.writeLong(updateNum)
            out.writeRSString(clanName)
            out.writeByte(0) // unused
            out.writeByte(kickRank)
            out.writeByte(talkRank)
            out.writeShort(chatterList.size)
            for (chatter in chatterList) {
                out.writeRSString(chatter.displayName)
                out.writeByte(chatter.rank)
                out.writeShort(chatter.worldId)
            }
        }
    }

    serverProt<ClanSettingsDelta>(opcode = 46, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        out.writeBytes(data)
    }

    serverProt<ClanSettingsFull>(opcode = 137, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        if (clanName != null) {
            val memberList = members ?: emptyArray()
            val bannedList = bannedUsers ?: emptyArray()
            val settingsList = settings ?: emptyArray()
            val fillerCount = (5 - memberList.size).coerceAtLeast(0)
            val totalSlots = memberList.size + fillerCount

            out.writeByte(3) // version
            out.writeByte(2) // attr: names as string
            out.writeInt(updateCount)
            out.writeInt(0) // creationDay
            out.writeShort(totalSlots)
            out.writeByte(bannedList.size)
            out.writeRSString(clanName)
            out.writeByte(if (allowGuests) 1 else 0)
            out.writeByte(talkRank)
            out.writeByte(kickRank)
            out.writeByte(0) // lootshareRank
            out.writeByte(0) // coinShare

            for (member in memberList) {
                out.writeRSString(member.displayName)
                out.writeByte(member.rank)
                out.writeInt(member.flags)
            }

            for (i in 0 until fillerCount) {
                out.writeRSString("FILLER_CHARACTER$i")
                out.writeByte(0) // rank
                out.writeInt(0) // flags
            }

            for (banned in bannedList) {
                out.writeRSString(banned)
            }

            out.writeShort(settingsList.size)
            for (setting in settingsList) {
                when {
                    setting.stringValue != null -> {
                        out.writeInt((setting.key and 0x3FFFFFFF) or (2 shl 30))
                        out.writeRSString(setting.stringValue)
                    }
                    setting.longValue != null -> {
                        out.writeInt((setting.key and 0x3FFFFFFF) or (1 shl 30))
                        out.writeLong(setting.longValue)
                    }
                    else -> {
                        out.writeInt(setting.key and 0x3FFFFFFF)
                        out.writeInt(setting.intValue ?: 0)
                    }
                }
            }
        }
    }

    serverProt<RunEnergy>(opcode = 64, size = 1) { out ->
        out.writeByte(energy)
    }

    serverProt<PlayerWeight>(opcode = 14, size = 2) { out ->
        out.writeShort(weight)
    }

    serverProt<PlayerOption>(opcode = 111, size = ProtSize.VarByte) { out ->
        out.writeRSString(option)
        out.writeByteAdd(slot)
        out.writeShortAddLittle(cursor)
        out.writeByteInverse(if (top) 1 else 0)
    }

    serverProt<ReduceAttackPriority>(opcode = 61, size = 1) { out ->
        out.writeBooleanAdd(enabled)
    }

    serverProt<SetTarget>(opcode = 129, size = 2) { out ->
        out.writeShortLittle(target)
    }

    serverProt<UpdateDob>(opcode = 157, size = 4) { out ->
        out.writeMedium(dob)
        out.writeBoolean(quickChatOnly)
    }
}
