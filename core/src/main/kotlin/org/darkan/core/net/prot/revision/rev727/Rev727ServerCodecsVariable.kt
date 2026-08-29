package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev727VariablePackets() {
    serverProt<VarpSmall>(opcode = 115, size = 3) { out ->
        out.writeByte(value)
        out.writeShortAddLittle(id)
    }

    serverProt<VarpLarge>(opcode = 8, size = 6) { out ->
        out.writeIntInverseMiddle(value)
        out.writeShortAddLittle(id)
    }

    serverProt<ClientSetVarcSmall>(opcode = 116, size = 3) { out ->
        out.writeByteSubtract(value)
        out.writeShortLittle(id)
    }

    serverProt<ClientSetVarcLarge>(opcode = 12, size = 6) { out ->
        out.writeShortLittle(id)
        out.writeIntInverseMiddle(value)
    }

    serverProt<VarbitSmall>(opcode = 68, size = 3) { out ->
        out.writeShort(id)
        out.writeByteAdd(value)
    }

    serverProt<VarbitLarge>(opcode = 108, size = 6) { out ->
        out.writeIntInverseMiddle(value)
        out.writeShortAdd(id)
    }

    serverProt<ClientSetVarcStrSmall>(opcode = 54, size = ProtSize.VarByte) { out ->
        out.writeRSString(value)
        out.writeShortAddLittle(id)
    }

    serverProt<ClientSetVarcStrLarge>(opcode = 119, size = ProtSize.VarShort) { out ->
        out.writeShortLittle(id)
        out.writeRSString(value)
    }

    serverProt<VarclanSetLong>(opcode = 26, size = 10) { out ->
        out.writeShort(id)
        out.writeLong(value)
    }

    serverProt<VarclanSetByte>(opcode = 123, size = 3) { out ->
        out.writeShort(id)
        out.writeByte(value)
    }

    serverProt<VarclanSetInt>(opcode = 141, size = 6) { out ->
        out.writeShort(id)
        out.writeInt(value)
    }

    serverProt<VarclanSetString>(opcode = 50, size = ProtSize.VarByte) { out ->
        out.writeShort(id)
        out.writeRSString(value)
    }

    serverProt<VarclanEnable>(opcode = 45)

    serverProt<VarclanDisable>(opcode = 94)

    serverProt<ClearVarps>(opcode = 100)
}
