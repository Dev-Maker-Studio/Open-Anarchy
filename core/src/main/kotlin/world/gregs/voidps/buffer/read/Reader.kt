package world.gregs.voidps.buffer.read

interface Reader {

    /**
     * Starting length of the packet
     */
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

    /**
     * The signed smart the client calls `readSmart`: one byte biased by 64 when the top bit is clear,
     * two bytes biased by 49152 when it is set, so the range is -64..63 in one byte and -16384..16383
     * in two. [readSmart] is the unsigned form; only the synth sound format uses this one.
     */
    fun readSignedSmart(): Int {
        val peek = readUnsignedByte()
        return if (peek < 128) peek - 64 else ((peek shl 8) or readUnsignedByte()) - 49152
    }

    fun readBigSmart(): Int

    fun readLargeSmart(): Int

    fun readLong(): Long

    /**
     * Reads a 40-bit big-endian value, most significant byte first.
     *
     * Exact inverse of [world.gregs.voidps.buffer.write.Writer.write5].
     */
    fun read5(): Long = (readUnsignedByte().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)

    fun readString(): String

    /**
     * Reads a `gjstr2`: a 0 version byte followed by a null terminated cp1252 string.
     *
     * Exact inverse of [world.gregs.voidps.buffer.write.Writer.writePrefixedString].
     */
    fun readPrefixedString(): String {
        val version = readUnsignedByte()
        require(version == 0) { "Bad version number in gjstr2: $version" }
        return readString()
    }

    /**
     * Reads all bytes into [ByteArray]
     * @param value The array to be written to.
     */
    fun readBytes(value: ByteArray)

    /**
     * Reads [length] number of bytes starting at [offset] to [array].
     * @param array The [ByteArray] to be written to
     * @param offset Destination index
     * @param length Number of bytes to read
     */
    fun readBytes(array: ByteArray, offset: Int, length: Int = array.size)

    /**
     * Skips the [amount] bytes.
     * @param amount Number of bytes to skip
     */
    fun skip(amount: Int)

    fun position(): Int

    fun array(): ByteArray

    fun position(index: Int)

    /**
     * Returns the remaining number of readable bytes.
     * @return [Int]
     */
    fun readableBytes(): Int

    /**
     * Enables individual decoded byte writing aka 'bit access'
     */
    fun startBitAccess(): Reader

    /**
     * Disables 'bit access' mode
     */
    fun stopBitAccess(): Reader

    /**
     * Writes a bit during 'bit access'
     * @param bitCount number of bits to be written
     */
    fun readBits(bitCount: Int): Int
}