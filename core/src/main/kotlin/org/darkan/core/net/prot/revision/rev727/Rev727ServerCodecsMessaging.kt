package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import org.darkan.core.social.QuickChatMessage
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache

internal fun Codec.registerRev727MessagingPackets() {
    serverProt<MessagePrivate>(opcode = 10, size = ProtSize.VarShort) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeHashedMessageTimestamp(message.take(210))
        out.writeByte(crown)
        out.writeBytes(Cache.huffman.compress(message.take(210)))
    }

    serverProt<MessageQuickChatPrivateEcho>(opcode = 22, size = ProtSize.VarByte) { out ->
        out.writeRSString(senderDisplayName)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }

    serverProt<MessageFriendsChat>(opcode = 25, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeLong(chatName)
        out.writeHashedMessageTimestamp(message.take(210))
        out.writeByte(crown)
        out.writeBytes(Cache.huffman.compress(message.take(210)))
    }

    serverProt<MessageQuickChatPrivate>(opcode = 31, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeHashedQCMessageTimestamp(message.fileId)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }

    serverProt<MessageClanChannel>(opcode = 81, size = ProtSize.VarByte) { out ->
        out.writeBoolean(!guest)
        out.writeRSString(displayName)
        out.writeHashedMessageTimestamp(message.take(210))
        out.writeByte(crown)
        out.writeBytes(Cache.huffman.compress(message.take(210)))
    }

    serverProt<MessageQuickChatPlayerGroup>(opcode = 83, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }

    serverProt<MessagePrivateEcho>(opcode = 92, size = ProtSize.VarShort) { out ->
        out.writeRSString(senderDisplayName)
        out.writeBytes(Cache.huffman.compress(message.take(210)))
    }

    serverProt<MessageQuickChatFriendsChat>(opcode = 142, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeLong(chatName)
        out.writeHashedQCMessageTimestamp(message.fileId)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }

    serverProt<MessageQuickChatClanChannel>(opcode = 131, size = ProtSize.VarByte) { out ->
        out.writeBoolean(!guest)
        out.writeRSString(displayName)
        out.writeHashedQCMessageTimestamp(message.fileId)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }

    serverProt<MessagePlayerGroup>(opcode = 133, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeByte(crown)
        out.writeBytes(Cache.huffman.compress(message.take(210)))
    }

    serverProt<MessagePublic>(opcode = 152, size = ProtSize.VarByte) { out ->
        out.writeShort(pid)
        if (message is QuickChatMessage) {
            out.writeShort(message.effects or 0x8000)
            out.writeByte(messageIcon)
            out.writeShort(message.fileId)
            message.data?.let { out.writeBytes(it) }
        } else {
            out.writeShort(message.effects and 0x7FFF)
            out.writeByte(messageIcon)
            out.writeBytes(Cache.huffman.compress(message.message.take(210)))
        }
    }

    serverProt<GameMessage>(opcode = 160, size = ProtSize.VarByte) { out ->
        var maskData = 0
        if (targetDisplayName != null) {
            maskData = maskData or 0x1
            //maskData |= 0x2;
        }
        out.writeSmart(type.value)
        out.writeInt(effectFlags)
        out.writeByte(maskData)
        if (targetDisplayName != null) {
            out.writeRSString(targetDisplayName)
            //stream.writeRSString(target.getDisplayName());
        }
        out.writeRSString(message.take(248))
    }

    serverProt<FriendStatus>(opcode = 74, size = ProtSize.VarShort) { out ->
        for (update in updates) {
            out.writeBoolean(update.warnMessage)
            out.writeRSString(update.displayName)
            out.writeRSString(update.previousDisplayName)
            out.writeShort(update.world)
            out.writeByte(update.friendsChatRank)
            out.writeBoolean(update.referrer)
            if (update.world > 0) {
                out.writeRSString(update.worldName ?: "")
                out.writeByte(update.platform)
                out.writeInt(update.worldFlags)
            }
        }
    }

    serverProt<FriendlistLoaded>(opcode = 101)

    serverProt<AddIgnore>(opcode = 138, size = ProtSize.VarByte) { out ->
        var flags = 0
        if (editDisplayName) flags = flags or 0x1
        if (temporary) flags = flags or 0x2
        out.writeByte(flags)
        out.writeRSString(username)
        out.writeRSString(displayName)
    }

    serverProt<FriendsChatChannel>(opcode = 127, size = ProtSize.VarShort) { out ->
        if (clear) {
            return@serverProt
        }
        out.writeRSString(ownerDisplayName ?: "")
        val nameChanged = ownerUsername != null && ownerUsername != ownerDisplayName
        out.writeBoolean(nameChanged)
        if (nameChanged) {
            out.writeRSString(ownerUsername ?: ownerDisplayName ?: "")
        }
        out.writeLong(chatName ?: "")
        out.writeByte(minRankCanKick)
        if (players == null) {
            out.writeByte(255)
        } else {
            out.writeByte(players.size)
            for (player in players) {
                out.writeRSString(player.displayName)
                val changed = player.username != player.displayName
                out.writeBoolean(changed)
                if (changed) {
                    out.writeRSString(player.username)
                }
                out.writeShort(player.worldId)
                out.writeByte(player.rank)
                out.writeRSString(player.worldName)
            }
        }
    }

    serverProt<UpdateFriendchatChannelSingleUser>(opcode = 19, size = ProtSize.VarByte) { out ->
        out.writeRSString(displayName)
        val changedName = username != displayName
        out.writeBoolean(changedName)
        if (changedName) {
            out.writeRSString(username)
        }
        out.writeShort(worldId)
        out.writeByte(rank)
        if (rank != -128) {
            out.writeRSString(worldName ?: "")
        }
    }

    serverProt<UpdateIgnoreList>(opcode = 97, size = ProtSize.VarShort) { out ->
        out.writeByte(ignores.size)
        for (ignore in ignores) {
            out.writeRSString(ignore.username)
            out.writeRSString(ignore.displayName)
        }
    }

    serverProt<ChatFilterSettings>(opcode = 30, size = 2) { out ->
        out.writeByteInverse(tradeFilter)
        out.writeByteAdd(publicFilter)
    }

    serverProt<ChatFilterSettingsPrivateChat>(opcode = 67, size = 1) { out ->
        out.writeByte(privateFilter)
    }

    serverProt<Pong>(opcode = 29)
}
