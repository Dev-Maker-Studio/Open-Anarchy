package world.gregs.voidps.buffer

object Cp1252 {

    const val SUBSTITUTE: Char = '?'

    private val SUBSTITUTE_BYTE: Byte = SUBSTITUTE.code.toByte()

    private val UNICODE_TABLE = charArrayOf(
        '\u20ac',
        '\u0000',
        '\u201a',
        '\u0192',
        '\u201e',
        '\u2026',
        '\u2020',
        '\u2021',
        '\u02c6',
        '\u2030',
        '\u0160',
        '\u2039',
        '\u0152',
        '\u0000',
        '\u017d',
        '\u0000',
        '\u0000',
        '\u2018',
        '\u2019',
        '\u201c',
        '\u201d',
        '\u2022',
        '\u2013',
        '\u2014',
        '\u02dc',
        '\u2122',
        '\u0161',
        '\u203a',
        '\u0153',
        '\u0000',
        '\u017e',
        '\u0178'
    )

    private val ENCODE_TABLE: Map<Char, Byte> = buildMap(UNICODE_TABLE.size) {
        for (index in UNICODE_TABLE.indices) {
            val char = UNICODE_TABLE[index]
            if (char.code != 0) {
                put(char, (index + 128).toByte())
            }
        }
    }

    fun decode(value: Int): Char {
        val code = value and 0xff
        if (code in 128..159) {
            val char = UNICODE_TABLE[code - 128]
            return if (char.code == 0) SUBSTITUTE else char
        }
        return code.toChar()
    }

    fun decode(value: Byte): Char = decode(value.toInt())

    fun encode(char: Char): Byte {
        val code = char.code
        if (code in 1..127 || code in 160..255) {
            return code.toByte()
        }
        return ENCODE_TABLE[char] ?: SUBSTITUTE_BYTE
    }

    fun encode(value: CharSequence): ByteArray = ByteArray(value.length) { encode(value[it]) }
}
