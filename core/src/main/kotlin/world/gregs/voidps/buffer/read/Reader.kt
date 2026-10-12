package world.gregs.voidps.buffer.read

interface Reader {

    val length: Int

    val remaining: Int

    fun readBoolean() = readByte() == 1

    fun readBooleanAdd() = readByteAdd() == 1

    fun readBooleanInverse() = readByteInverse() == 1

    fun readBooleanSubtract() = readByteSubtract() == 1

    fun readUnsignedBoolean() = readUnsignedByte() == 1

    fun readByte(): Int

    fun readByteAdd(): Int

    fun readByteInverse(): Int

    fun readByteSubtract(): Int

    fun readUnsignedByte(): Int

    fun readUnsignedByteAdd(): Int

    fun readShort(): Int

    fun readShortAdd(): Int

    fun readShortLittle(): Int

    fun readShortAddLittle(): Int

    fun readUnsignedShort(): Int

    fun readUnsignedShortLittle(): Int

    fun readUnsignedShortAdd(): Int

    fun readMedium(): Int

    fun readUnsignedMedium(): Int

    fun readInt(): Int

    fun readIntInverseMiddle(): Int

    fun readIntLittle(): Int

    fun readUnsignedIntMiddle(): Int

    fun readSmart(): Int

    fun readSignedSmart(): Int {
        val peek = readUnsignedByte()
        return if (peek < 128) {
            peek - 64
        } else {
            ((peek shl 8) or readUnsignedByte()) - 49152
        }
    }

    fun readBigSmart(): Int

    fun readLargeSmart(): Int

    fun readLong(): Long

    fun read5(): Long = (readUnsignedByte().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)

    fun readString(): String

    fun readPrefixedString(): String {
        val version = readUnsignedByte()
        require(version == 0) { "Bad version number in gjstr2: $version" }
        return readString()
    }

    fun readBytes(value: ByteArray)

    fun readBytes(array: ByteArray, offset: Int, length: Int = array.size)

    fun skip(amount: Int)

    fun position(): Int

    fun array(): ByteArray

    fun position(index: Int)

    fun readableBytes(): Int

    fun startBitAccess(): Reader

    fun stopBitAccess(): Reader

    fun readBits(bitCount: Int): Int
}