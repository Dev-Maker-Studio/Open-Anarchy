package world.gregs.voidps.cache.source.codec

import java.io.ByteArrayOutputStream
import java.io.IOException

class BaselineJpeg(
    var width: Int,
    var height: Int,
    val quantisation: Array<IntArray?> = arrayOfNulls(TABLES),
    val dcTables: Array<JpegHuffmanTable?> = arrayOfNulls(TABLES),
    val acTables: Array<JpegHuffmanTable?> = arrayOfNulls(TABLES),
    val components: MutableList<JpegComponent> = ArrayList(TABLES),
    var restartInterval: Int = 0
) {

    val maxHorizontal: Int
        get() = components.maxOf { it.horizontal }

    val maxVertical: Int
        get() = components.maxOf { it.vertical }

    val mcusAcross: Int
        get() = ceil(width, BLOCK * maxHorizontal)

    val mcusDown: Int
        get() = ceil(height, BLOCK * maxVertical)

    fun blocksAcross(component: JpegComponent): Int =
        ceil(ceil(width * component.horizontal, maxHorizontal), BLOCK)

    fun blocksDown(component: JpegComponent): Int =
        ceil(ceil(height * component.vertical, maxVertical), BLOCK)

    fun write(): ByteArray {
        require(components.isNotEmpty()) { "A JPEG frame has at least one component." }
        val out = ByteArrayOutputStream(width * height / 4 + HEADER)
        out.write(0xff)
        out.write(SOI)
        for (table in 0 until TABLES) {
            if (components.none { it.quantisation == table }) {
                continue
            }
            val values = quantisation[table] ?: throw IOException("A component uses quantisation table $table, which is not defined.")
            val payload = IntArray(1 + BLOCK_SIZE)
            payload[0] = table
            values.copyInto(payload, 1)
            segment(out, DQT, payload)
        }
        val frame = IntArray(6 + components.size * 3)
        frame[0] = PRECISION
        frame[1] = height shr 8
        frame[2] = height and 0xff
        frame[3] = width shr 8
        frame[4] = width and 0xff
        frame[5] = components.size
        for (index in components.indices) {
            val component = components[index]
            frame[6 + index * 3] = component.id
            frame[7 + index * 3] = (component.horizontal shl 4) or component.vertical
            frame[8 + index * 3] = component.quantisation
        }
        segment(out, SOF0, frame)
        for (table in 0 until TABLES) {
            if (components.any { it.dc == table }) {
                segment(out, DHT, huffman(DC_CLASS, table, dcTables[table], "DC"))
            }
            if (components.any { it.ac == table }) {
                segment(out, DHT, huffman(AC_CLASS, table, acTables[table], "AC"))
            }
        }
        if (restartInterval > 0) {
            segment(out, DRI, intArrayOf(restartInterval shr 8, restartInterval and 0xff))
        }
        val scan = IntArray(4 + components.size * 2)
        scan[0] = components.size
        for (index in components.indices) {
            val component = components[index]
            scan[1 + index * 2] = component.id
            scan[2 + index * 2] = (component.dc shl 4) or component.ac
        }
        scan[1 + components.size * 2] = 0
        scan[2 + components.size * 2] = BLOCK_SIZE - 1
        scan[3 + components.size * 2] = 0
        segment(out, SOS, scan)
        encode(out)
        out.write(0xff)
        out.write(EOI)
        return out.toByteArray()
    }

    private fun encode(out: ByteArrayOutputStream) {
        val writer = BitWriter(out)
        val interleaved = components.size > 1
        val predictors = IntArray(components.size)
        val units = if (interleaved) mcusAcross * mcusDown else components[0].across * components[0].down
        var restart = 0
        var since = 0
        for (unit in 0 until units) {
            if (restartInterval > 0 && since == restartInterval) {
                writer.flush()
                writer.marker(RST0 + (restart and 7))
                restart++
                predictors.fill(0)
                since = 0
            }
            if (interleaved) {
                val mcuX = unit % mcusAcross
                val mcuY = unit / mcusAcross
                for (index in components.indices) {
                    val component = components[index]
                    for (y in 0 until component.vertical) {
                        for (x in 0 until component.horizontal) {
                            val block = (mcuY * component.vertical + y) * component.across + mcuX * component.horizontal + x
                            predictors[index] = block(writer, component, predictors[index], block * BLOCK_SIZE)
                        }
                    }
                }
            } else {
                val component = components[0]
                predictors[0] = block(writer, component, predictors[0], unit * BLOCK_SIZE)
            }
            since++
        }
        writer.flush()
    }

    private fun block(writer: BitWriter, component: JpegComponent, predictor: Int, offset: Int): Int {
        val dc = dcTables[component.dc] ?: throw IOException("Component ${component.id} has no DC table ${component.dc}.")
        val ac = acTables[component.ac] ?: throw IOException("Component ${component.id} has no AC table ${component.ac}.")
        val value = component.coefficients[offset].toInt()
        writer.value(dc, 0, value - predictor)
        var run = 0
        for (index in 1 until BLOCK_SIZE) {
            val coefficient = component.coefficients[offset + index].toInt()
            if (coefficient == 0) {
                run++
                continue
            }
            while (run > MAX_RUN) {
                writer.code(ac, ZERO_RUN)
                run -= MAX_RUN + 1
            }
            writer.value(ac, run, coefficient)
            run = 0
        }
        if (run > 0) {
            writer.code(ac, END_OF_BLOCK)
        }
        return value
    }

    private fun huffman(type: Int, table: Int, huffman: JpegHuffmanTable?, kind: String): IntArray {
        val values = huffman ?: throw IOException("A component uses $kind Huffman table $table, which is not defined.")
        val payload = IntArray(1 + LENGTHS + values.values.size)
        payload[0] = (type shl 4) or table
        for (length in 1..LENGTHS) {
            payload[length] = values.counts[length]
        }
        values.values.copyInto(payload, 1 + LENGTHS)
        return payload
    }

    private fun segment(out: ByteArrayOutputStream, marker: Int, payload: IntArray) {
        out.write(0xff)
        out.write(marker)
        val length = payload.size + 2
        out.write(length shr 8)
        out.write(length and 0xff)
        for (value in payload) {
            out.write(value)
        }
    }

    companion object {

        const val TABLES = 4

        const val BLOCK = 8

        const val BLOCK_SIZE = BLOCK * BLOCK

        fun read(data: ByteArray): BaselineJpeg {
            if (data.size < 4 || (data[0].toInt() and 0xff) != 0xff || (data[1].toInt() and 0xff) != SOI) {
                throw IOException("Not a JPEG: no start of image marker.")
            }
            val image = BaselineJpeg(0, 0)
            var position = 2
            while (true) {
                if (position + 1 >= data.size) {
                    throw IOException("A JPEG ended before its scan.")
                }
                if ((data[position].toInt() and 0xff) != 0xff) {
                    throw IOException("A JPEG has a stray byte at $position where a marker should be.")
                }
                val marker = data[position + 1].toInt() and 0xff
                if (marker == EOI) {
                    throw IOException("A JPEG ended before its scan.")
                }
                if (position + 3 >= data.size) {
                    throw IOException("A JPEG marker ff${marker.toString(16)} has no length.")
                }
                val length = ((data[position + 2].toInt() and 0xff) shl 8) or (data[position + 3].toInt() and 0xff)
                val end = position + 2 + length
                if (end > data.size) {
                    throw IOException("A JPEG marker ff${marker.toString(16)} runs past the end of the file.")
                }
                when (marker) {
                    DQT -> quantisation(image, data, position + 4, end)
                    DHT -> huffman(image, data, position + 4, end)
                    DRI -> image.restartInterval =
                        ((data[position + 4].toInt() and 0xff) shl 8) or (data[position + 5].toInt() and 0xff)
                    SOF0 -> frame(image, data, position + 4)
                    SOS -> {
                        scan(image, data, position + 4, end)
                        return image
                    }
                    in UNSUPPORTED -> throw IOException(
                        "A JPEG here is baseline only; this one is ${UNSUPPORTED.getValue(marker)}."
                    )
                }
                position = end
            }
        }

        private fun quantisation(image: BaselineJpeg, data: ByteArray, start: Int, end: Int) {
            var position = start
            while (position < end) {
                val header = data[position].toInt() and 0xff
                position++
                val precision = header shr 4
                val table = header and 0xf
                if (precision != 0) {
                    throw IOException("A JPEG here is 8 bit; quantisation table $table is 16 bit.")
                }
                if (table >= TABLES || position + BLOCK_SIZE > end) {
                    throw IOException("A JPEG's quantisation table $table is malformed.")
                }
                val values = IntArray(BLOCK_SIZE) { data[position + it].toInt() and 0xff }
                position += BLOCK_SIZE
                image.quantisation[table] = values
            }
        }

        private fun huffman(image: BaselineJpeg, data: ByteArray, start: Int, end: Int) {
            var position = start
            while (position < end) {
                val header = data[position].toInt() and 0xff
                position++
                val type = header shr 4
                val table = header and 0xf
                if (table >= TABLES || (type != DC_CLASS && type != AC_CLASS)) {
                    throw IOException("A JPEG's Huffman table header 0x${header.toString(16)} is malformed.")
                }
                if (position + LENGTHS > end) {
                    throw IOException("A JPEG's Huffman table $table is truncated.")
                }
                val counts = IntArray(LENGTHS + 1)
                var total = 0
                for (length in 1..LENGTHS) {
                    counts[length] = data[position + length - 1].toInt() and 0xff
                    total += counts[length]
                }
                position += LENGTHS
                if (position + total > end) {
                    throw IOException("A JPEG's Huffman table $table is truncated.")
                }
                val values = IntArray(total) { data[position + it].toInt() and 0xff }
                position += total
                val huffman = JpegHuffmanTable(counts, values)
                if (type == DC_CLASS) {
                    image.dcTables[table] = huffman
                } else {
                    image.acTables[table] = huffman
                }
            }
        }

        private fun frame(image: BaselineJpeg, data: ByteArray, start: Int) {
            val precision = data[start].toInt() and 0xff
            if (precision != PRECISION) {
                throw IOException("A JPEG here is 8 bit; this one is $precision bit.")
            }
            image.height = ((data[start + 1].toInt() and 0xff) shl 8) or (data[start + 2].toInt() and 0xff)
            image.width = ((data[start + 3].toInt() and 0xff) shl 8) or (data[start + 4].toInt() and 0xff)
            if (image.width <= 0 || image.height <= 0) {
                throw IOException("A JPEG is ${image.width}x${image.height}.")
            }
            val count = data[start + 5].toInt() and 0xff
            if (count < 1) {
                throw IOException("A JPEG frame has no components.")
            }
            if (start + 6 + count * 3 > data.size) {
                throw IOException("A JPEG frame header says $count components but is too short for them.")
            }
            var position = start + 6
            for (index in 0 until count) {
                val id = data[position].toInt() and 0xff
                val sampling = data[position + 1].toInt() and 0xff
                val table = data[position + 2].toInt() and 0xf
                position += 3
                val horizontal = sampling shr 4
                val vertical = sampling and 0xf
                if (horizontal < 1 || vertical < 1) {
                    throw IOException("A JPEG component $id samples ${horizontal}x$vertical.")
                }
                image.components.add(JpegComponent(id, horizontal, vertical, table))
            }
        }

        private fun scan(image: BaselineJpeg, data: ByteArray, start: Int, end: Int) {
            if (image.components.isEmpty()) {
                throw IOException("A JPEG's scan comes before its frame header.")
            }
            val count = data[start].toInt() and 0xff
            var position = start + 1
            val scanned = ArrayList<JpegComponent>(count)
            for (index in 0 until count) {
                val selector = data[position].toInt() and 0xff
                val tables = data[position + 1].toInt() and 0xff
                position += 2
                val component = image.components.firstOrNull { it.id == selector }
                    ?: throw IOException("A JPEG's scan names component $selector, which its frame does not have.")
                component.dc = tables shr 4
                component.ac = tables and 0xf
                scanned.add(component)
            }
            val first = data[position].toInt() and 0xff
            val last = data[position + 1].toInt() and 0xff
            val approximation = data[position + 2].toInt() and 0xff
            if (first != 0 || last != BLOCK_SIZE - 1 || approximation != 0) {
                throw IOException("A JPEG here is baseline sequential; this scan is $first..$last, refinement $approximation.")
            }
            if (scanned.size != image.components.size) {
                throw IOException(
                    "A JPEG here is one scan of every component; this one scans ${scanned.size} of ${image.components.size}."
                )
            }
            val interleaved = image.components.size > 1
            for (component in image.components) {
                if (interleaved) {
                    component.across = image.mcusAcross * component.horizontal
                    component.down = image.mcusDown * component.vertical
                } else {
                    component.across = image.blocksAcross(component)
                    component.down = image.blocksDown(component)
                }
                component.coefficients = ShortArray(component.across * component.down * BLOCK_SIZE)
            }
            val reader = BitReader(data, end)
            val predictors = IntArray(image.components.size)
            val units =
                if (interleaved) image.mcusAcross * image.mcusDown
                else image.components[0].across * image.components[0].down
            var since = 0
            for (unit in 0 until units) {
                if (image.restartInterval > 0 && since == image.restartInterval) {
                    reader.restart()
                    predictors.fill(0)
                    since = 0
                }
                if (interleaved) {
                    val mcuX = unit % image.mcusAcross
                    val mcuY = unit / image.mcusAcross
                    for (index in image.components.indices) {
                        val component = image.components[index]
                        for (y in 0 until component.vertical) {
                            for (x in 0 until component.horizontal) {
                                val block =
                                    (mcuY * component.vertical + y) * component.across + mcuX * component.horizontal + x
                                predictors[index] =
                                    block(image, reader, component, predictors[index], block * BLOCK_SIZE)
                            }
                        }
                    }
                } else {
                    val component = image.components[0]
                    predictors[0] = block(image, reader, component, predictors[0], unit * BLOCK_SIZE)
                }
                since++
            }
        }

        private fun block(
            image: BaselineJpeg,
            reader: BitReader,
            component: JpegComponent,
            predictor: Int,
            offset: Int
        ): Int {
            val dc = image.dcTables[component.dc]
                ?: throw IOException("Component ${component.id} uses DC table ${component.dc}, which is not defined.")
            val ac = image.acTables[component.ac]
                ?: throw IOException("Component ${component.id} uses AC table ${component.ac}, which is not defined.")
            val size = reader.code(dc)
            val value = predictor + if (size == 0) 0 else reader.value(size)
            component.coefficients[offset] = value.toShort()
            var index = 1
            while (index < BLOCK_SIZE) {
                val symbol = reader.code(ac)
                val run = symbol shr 4
                val bits = symbol and 0xf
                if (bits == 0) {
                    if (run != MAX_RUN) {
                        break
                    }
                    index += MAX_RUN + 1
                    continue
                }
                index += run
                if (index >= BLOCK_SIZE) {
                    throw IOException("A JPEG block's coefficient run overflows the block.")
                }
                component.coefficients[offset + index] = reader.value(bits).toShort()
                index++
            }
            return value
        }

        internal fun ceil(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

        private const val SOI = 0xd8
        private const val EOI = 0xd9
        private const val SOF0 = 0xc0
        private const val DHT = 0xc4
        private const val SOS = 0xda
        private const val DQT = 0xdb
        private const val DRI = 0xdd
        private const val RST0 = 0xd0

        private val UNSUPPORTED = mapOf(
            0xc1 to "extended sequential",
            0xc2 to "progressive",
            0xc3 to "lossless",
            0xc5 to "differential sequential",
            0xc6 to "differential progressive",
            0xc7 to "differential lossless",
            0xc9 to "arithmetic coded extended sequential",
            0xca to "arithmetic coded progressive",
            0xcb to "arithmetic coded lossless",
            0xcd to "arithmetic coded differential sequential",
            0xce to "arithmetic coded differential progressive",
            0xcf to "arithmetic coded differential lossless"
        )

        private const val DC_CLASS = 0
        private const val AC_CLASS = 1

        internal const val LENGTHS = 16

        private const val PRECISION = 8

        private const val MAX_RUN = 15
        private const val ZERO_RUN = 0xf0
        private const val END_OF_BLOCK = 0x00

        private const val HEADER = 1024
    }
}

class JpegComponent(
    val id: Int,
    val horizontal: Int,
    val vertical: Int,
    val quantisation: Int
) {

    var dc: Int = 0
    var ac: Int = 0

    var across: Int = 0
    var down: Int = 0

    var coefficients: ShortArray = ShortArray(0)

    fun retable(id: Int, quantisation: Int, dc: Int, ac: Int): JpegComponent {
        val component = JpegComponent(id, horizontal, vertical, quantisation)
        component.dc = dc
        component.ac = ac
        component.across = across
        component.down = down
        component.coefficients = coefficients
        return component
    }
}

class JpegHuffmanTable(val counts: IntArray, val values: IntArray) {

    internal val minimum = IntArray(BaselineJpeg.LENGTHS + 1)
    internal val maximum = IntArray(BaselineJpeg.LENGTHS + 2)
    internal val pointer = IntArray(BaselineJpeg.LENGTHS + 1)
    internal val code = IntArray(SYMBOLS)
    internal val length = IntArray(SYMBOLS)

    init {
        require(counts.size == BaselineJpeg.LENGTHS + 1) { "A Huffman table has 16 code lengths." }
        val sizes = IntArray(values.size + 1)
        var position = 0
        for (bits in 1..BaselineJpeg.LENGTHS) {
            for (index in 0 until counts[bits]) {
                if (position >= values.size) {
                    throw IOException("A Huffman table has more codes than symbols.")
                }
                sizes[position++] = bits
            }
        }
        if (position != values.size) {
            throw IOException("A Huffman table has ${values.size} symbols but $position codes.")
        }
        val codes = IntArray(values.size + 1)
        var next = 0
        var bits = if (values.isEmpty()) 0 else sizes[0]
        position = 0
        while (position < values.size) {
            while (position < values.size && sizes[position] == bits) {
                codes[position] = next
                position++
                next++
            }
            next = next shl 1
            bits++
        }
        position = 0
        for (size in 1..BaselineJpeg.LENGTHS) {
            if (counts[size] > 0) {
                pointer[size] = position
                minimum[size] = codes[position]
                position += counts[size]
                maximum[size] = codes[position - 1]
            } else {
                maximum[size] = -1
            }
        }
        maximum[BaselineJpeg.LENGTHS + 1] = Int.MAX_VALUE
        for (index in values.indices) {
            val symbol = values[index]
            if (symbol >= SYMBOLS) {
                throw IOException("A Huffman table has symbol $symbol.")
            }
            code[symbol] = codes[index]
            length[symbol] = sizes[index]
        }
    }

    fun sameAs(other: JpegHuffmanTable?): Boolean =
        other != null && counts.contentEquals(other.counts) && values.contentEquals(other.values)

    private companion object {
        private const val SYMBOLS = 256
    }
}

private class BitReader(private val data: ByteArray, start: Int) {

    private var position = start
    private var buffer = 0
    private var count = 0

    private fun bit(): Int {
        if (count == 0) {
            if (position >= data.size) {
                throw IOException("A JPEG's scan ran out of data.")
            }
            val value = data[position].toInt() and 0xff
            position++
            if (value == 0xff) {
                val next = if (position < data.size) data[position].toInt() and 0xff else 0xd9
                if (next == 0x00) {
                    position++
                } else {
                    throw IOException("A JPEG's scan ended at marker ff${next.toString(16)} with blocks left to read.")
                }
            }
            buffer = value
            count = BITS
        }
        count--
        return (buffer shr count) and 1
    }

    fun bits(size: Int): Int {
        var value = 0
        for (index in 0 until size) {
            value = (value shl 1) or bit()
        }
        return value
    }

    fun code(table: JpegHuffmanTable): Int {
        var code = bit()
        var length = 1
        while (code > table.maximum[length]) {
            code = (code shl 1) or bit()
            length++
            if (length > BaselineJpeg.LENGTHS) {
                throw IOException("A JPEG's scan has a Huffman code longer than 16 bits.")
            }
        }
        return table.values[table.pointer[length] + code - table.minimum[length]]
    }

    fun value(size: Int): Int {
        val value = bits(size)
        return if (value < (1 shl (size - 1))) value - (1 shl size) + 1 else value
    }

    fun restart() {
        count = 0
        while (position < data.size && (data[position].toInt() and 0xff) != 0xff) {
            position++
        }
        if (position + 1 >= data.size) {
            throw IOException("A JPEG's scan ended where a restart marker was due.")
        }
        val marker = data[position + 1].toInt() and 0xff
        if (marker < 0xd0 || marker > 0xd7) {
            throw IOException("A JPEG's scan has ff${marker.toString(16)} where a restart marker was due.")
        }
        position += 2
    }

    private companion object {
        private const val BITS = 8
    }
}

private class BitWriter(private val out: ByteArrayOutputStream) {

    private var buffer = 0
    private var count = 0

    private fun byte(value: Int) {
        out.write(value)
        if (value == 0xff) {
            out.write(0)
        }
    }

    fun code(table: JpegHuffmanTable, symbol: Int) {
        val length = table.length[symbol]
        if (length == 0) {
            throw IOException("A JPEG's Huffman table has no code for symbol $symbol.")
        }
        bits(table.code[symbol], length)
    }

    fun value(table: JpegHuffmanTable, run: Int, value: Int) {
        var magnitude = value
        var bits = value
        if (magnitude < 0) {
            magnitude = -magnitude
            bits--
        }
        var size = 0
        while (magnitude != 0) {
            size++
            magnitude = magnitude shr 1
        }
        code(table, (run shl 4) or size)
        if (size != 0) {
            bits(bits and ((1 shl size) - 1), size)
        }
    }

    private fun bits(code: Int, size: Int) {
        for (index in size - 1 downTo 0) {
            buffer = (buffer shl 1) or ((code shr index) and 1)
            count++
            if (count == BITS) {
                byte(buffer and 0xff)
                buffer = 0
                count = 0
            }
        }
    }

    fun flush() {
        while (count != 0) {
            buffer = (buffer shl 1) or 1
            count++
            if (count == BITS) {
                byte(buffer and 0xff)
                buffer = 0
                count = 0
            }
        }
    }

    fun marker(value: Int) {
        out.write(0xff)
        out.write(value)
    }

    private companion object {
        private const val BITS = 8
    }
}
