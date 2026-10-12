package world.gregs.voidps.cache.audio

import java.io.ByteArrayOutputStream

object JagexMidi {

    private const val HEADER = 0x4d546864

    private const val TRACK = 0x4d54726b

    private const val END = 0x07

    private const val TEMPO = 0x17

    private const val NOTE_ON = 0
    private const val NOTE_OFF = 1
    private const val CONTROLLER = 2
    private const val PITCH_BEND = 3
    private const val CHANNEL_PRESSURE = 4
    private const val KEY_PRESSURE = 5
    private const val PROGRAM_CHANGE = 6

    private val STATUS = intArrayOf(0x90, 0x80, 0xb0, 0xe0, 0xd0, 0xa0, 0xc0)

    private const val COLUMN_SWITCH = 0
    private const val COLUMN_KEY_PRESSURE = 1
    private const val COLUMN_CHANNEL_PRESSURE = 2
    private const val COLUMN_PITCH_HIGH = 3
    private const val COLUMN_MOD_MSB = 4
    private const val COLUMN_VOLUME_MSB = 5
    private const val COLUMN_PAN_MSB = 6
    private const val COLUMN_NOTE_KEY = 7
    private const val COLUMN_NOTE_ON_VELOCITY = 8
    private const val COLUMN_OTHER = 9
    private const val COLUMN_NOTE_OFF_VELOCITY = 10
    private const val COLUMN_MOD_LSB = 11
    private const val COLUMN_VOLUME_LSB = 12
    private const val COLUMN_PAN_LSB = 13
    private const val COLUMN_PROGRAM = 14
    private const val COLUMN_PITCH_LOW = 15
    private const val COLUMN_NRPN_MSB = 16
    private const val COLUMN_NRPN_LSB = 17
    private const val COLUMN_RPN_MSB = 18
    private const val COLUMN_RPN_LSB = 19
    private const val COLUMN_TEMPO = 20
    private const val COLUMNS = 21

    private fun column(controller: Int): Int = when (controller) {
        0, 32 -> COLUMN_PROGRAM
        1 -> COLUMN_MOD_MSB
        33 -> COLUMN_MOD_LSB
        7 -> COLUMN_VOLUME_MSB
        39 -> COLUMN_VOLUME_LSB
        10 -> COLUMN_PAN_MSB
        42 -> COLUMN_PAN_LSB
        99 -> COLUMN_NRPN_MSB
        98 -> COLUMN_NRPN_LSB
        101 -> COLUMN_RPN_MSB
        100 -> COLUMN_RPN_LSB
        64, 65, 120, 121, 123 -> COLUMN_SWITCH
        else -> COLUMN_OTHER
    }


    fun toStandard(data: ByteArray): ByteArray {
        val compact = Compact(data)
        val out = Output(data.size * 2 + 64)
        out.int(HEADER)
        out.int(6)
        out.short(if (compact.tracks > 1) 1 else 0)
        out.short(compact.tracks)
        out.short(compact.division)
        val state = State()
        for (track in 0 until compact.tracks) {
            val body = Output(1024)
            var previous = -1
            while (true) {
                body.varInt(compact.delta())
                val opcode = compact.opcode()
                val fresh = opcode != previous
                previous = opcode and 0xf
                if (opcode == END) {
                    if (fresh) {
                        body.byte(0xff)
                    }
                    body.byte(0x2f)
                    body.byte(0)
                    break
                }
                event(compact, body, state, opcode, fresh)
            }
            out.int(TRACK)
            out.int(body.length())
            out.bytes(body)
        }
        return out.toArray()
    }

    private fun event(compact: Compact, body: Output, state: State, opcode: Int, fresh: Boolean) {
        if (opcode == TEMPO) {
            if (fresh) {
                body.byte(0xff)
            }
            body.byte(0x51)
            body.byte(3)
            for (byte in 0 until 3) {
                body.byte(compact.value(COLUMN_TEMPO))
            }
            return
        }
        val type = opcode and 0xf
        state.channel = state.channel xor (opcode shr 4)
        if (fresh) {
            body.byte(STATUS[type] + state.channel)
        }
        when (type) {
            NOTE_ON -> {
                state.key += compact.value(COLUMN_NOTE_KEY)
                state.noteOnVelocity += compact.value(COLUMN_NOTE_ON_VELOCITY)
                body.byte(state.key and 0x7f)
                body.byte(state.noteOnVelocity and 0x7f)
            }
            NOTE_OFF -> {
                state.key += compact.value(COLUMN_NOTE_KEY)
                state.noteOffVelocity += compact.value(COLUMN_NOTE_OFF_VELOCITY)
                body.byte(state.key and 0x7f)
                body.byte(state.noteOffVelocity and 0x7f)
            }
            CONTROLLER -> {
                state.controller = (state.controller + compact.controller()) and 0x7f
                body.byte(state.controller)
                state.values[state.controller] += compact.signed(column(state.controller))
                body.byte(state.values[state.controller] and 0x7f)
            }
            PITCH_BEND -> {
                state.bend += compact.value(COLUMN_PITCH_LOW)
                state.bend += compact.value(COLUMN_PITCH_HIGH) shl 7
                body.byte(state.bend and 0x7f)
                body.byte((state.bend shr 7) and 0x7f)
            }
            CHANNEL_PRESSURE -> {
                state.channelPressure += compact.value(COLUMN_CHANNEL_PRESSURE)
                body.byte(state.channelPressure and 0x7f)
            }
            KEY_PRESSURE -> {
                state.key += compact.value(COLUMN_NOTE_KEY)
                state.keyPressure += compact.value(COLUMN_KEY_PRESSURE)
                body.byte(state.key and 0x7f)
                body.byte(state.keyPressure and 0x7f)
            }
            PROGRAM_CHANGE -> body.byte(compact.value(COLUMN_PROGRAM))
            else -> throw IllegalStateException("A compact MIDI event type of $type is not a MIDI event.")
        }
    }

    private class State {
        var channel = 0
        var key = 0
        var noteOnVelocity = 0
        var noteOffVelocity = 0
        var bend = 0
        var channelPressure = 0
        var keyPressure = 0
        var controller = 0
        val values = IntArray(128)
    }

    private class Compact(private val data: ByteArray) {

        val tracks: Int
        val division: Int
        private val positions = IntArray(COLUMNS)
        private var opcodes = 0
        private var deltas = 0
        private var controllers = 0

        init {
            require(data.size >= 3) { "A compact MIDI file is at least three bytes long." }
            tracks = data[data.size - 3].toInt() and 0xff
            division = ((data[data.size - 2].toInt() and 0xff) shl 8) or (data[data.size - 1].toInt() and 0xff)
            val counts = IntArray(8)
            var position = 0
            for (track in 0 until tracks) {
                while (true) {
                    val opcode = data[position++].toInt() and 0xff
                    if (opcode == END) {
                        break
                    }
                    if (opcode == TEMPO) {
                        counts[7]++
                    } else {
                        val type = opcode and 0xf
                        require(type in NOTE_ON..PROGRAM_CHANGE) {
                            "A compact MIDI opcode of $opcode is not an event."
                        }
                        counts[type]++
                    }
                }
            }
            deltas = position
            var events = tracks + counts[7]
            for (type in NOTE_ON..PROGRAM_CHANGE) {
                events += counts[type]
            }
            position = deltas
            for (event in 0 until events) {
                position = skipVarInt(position)
            }
            controllers = position
            val lengths = IntArray(COLUMNS)
            var controller = 0
            for (change in 0 until counts[CONTROLLER]) {
                controller = (controller + (data[position++].toInt() and 0xff)) and 0x7f
                lengths[column(controller)]++
            }
            lengths[COLUMN_KEY_PRESSURE] += counts[KEY_PRESSURE]
            lengths[COLUMN_CHANNEL_PRESSURE] += counts[CHANNEL_PRESSURE]
            lengths[COLUMN_PITCH_HIGH] += counts[PITCH_BEND]
            lengths[COLUMN_PITCH_LOW] += counts[PITCH_BEND]
            lengths[COLUMN_NOTE_KEY] += counts[NOTE_ON] + counts[NOTE_OFF] + counts[KEY_PRESSURE]
            lengths[COLUMN_NOTE_ON_VELOCITY] += counts[NOTE_ON]
            lengths[COLUMN_NOTE_OFF_VELOCITY] += counts[NOTE_OFF]
            lengths[COLUMN_PROGRAM] += counts[PROGRAM_CHANGE]
            lengths[COLUMN_TEMPO] += counts[7] * 3
            for (column in 0 until COLUMNS) {
                positions[column] = position
                position += lengths[column]
            }
            check(position == data.size - 3) {
                "A compact MIDI file's columns end at $position, not ${data.size - 3}."
            }
        }

        fun opcode(): Int = data[opcodes++].toInt() and 0xff

        fun delta(): Int {
            var value = 0
            while (true) {
                val byte = data[deltas++].toInt()
                value = (value shl 7) or (byte and 0x7f)
                if (byte >= 0) {
                    return value
                }
            }
        }

        fun controller(): Int = data[controllers++].toInt() and 0xff

        fun value(index: Int): Int = data[positions[index]++].toInt() and 0xff

        fun signed(index: Int): Int = data[positions[index]++].toInt()

        private fun skipVarInt(from: Int): Int {
            var position = from
            while (data[position++] < 0) {
            }
            return position
        }
    }


    fun toCompact(midi: ByteArray): ByteArray {
        val file = Standard(midi)
        val opcodes = Output(1024)
        val deltas = Output(1024)
        val controllers = Output(256)
        val columns = Array(COLUMNS) { Output(256) }
        val state = State()
        for (track in file.tracks) {
            for (event in track) {
                deltas.varInt(event.delta)
                if (event.status == 0xff) {
                    if (event.meta == 0x2f) {
                        opcodes.byte(END)
                        continue
                    }
                    opcodes.byte(TEMPO)
                    for (byte in event.data) {
                        columns[COLUMN_TEMPO].byte(byte)
                    }
                    continue
                }
                val type = STATUS.indexOf(event.status and 0xf0)
                check(type >= 0) { "A status byte of ${event.status} has no compact MIDI opcode." }
                val channel = event.status and 0xf
                opcodes.byte(((channel xor state.channel) shl 4) or type)
                state.channel = channel
                encode(columns, controllers, state, type, event)
            }
        }
        val out = Output(midi.size)
        out.bytes(opcodes)
        out.bytes(deltas)
        out.bytes(controllers)
        for (column in columns) {
            out.bytes(column)
        }
        out.byte(file.trackCount)
        out.short(file.division)
        return out.toArray()
    }

    private fun encode(columns: Array<Output>, controllers: Output, state: State, type: Int, event: Event) {
        when (type) {
            NOTE_ON -> {
                key(columns, state, event.data[0])
                columns[COLUMN_NOTE_ON_VELOCITY].byte(delta(event.data[1], state.noteOnVelocity))
                state.noteOnVelocity = event.data[1]
            }
            NOTE_OFF -> {
                key(columns, state, event.data[0])
                columns[COLUMN_NOTE_OFF_VELOCITY].byte(delta(event.data[1], state.noteOffVelocity))
                state.noteOffVelocity = event.data[1]
            }
            CONTROLLER -> {
                val controller = event.data[0]
                controllers.byte(delta(controller, state.controller))
                state.controller = controller
                columns[column(controller)].byte(delta(event.data[1], state.values[controller]))
                state.values[controller] = event.data[1]
            }
            PITCH_BEND -> {
                val bend = (event.data[1] shl 7) or event.data[0]
                val difference = (bend - state.bend) and 0x3fff
                columns[COLUMN_PITCH_LOW].byte(difference and 0x7f)
                columns[COLUMN_PITCH_HIGH].byte(difference shr 7)
                state.bend = bend
            }
            CHANNEL_PRESSURE -> {
                columns[COLUMN_CHANNEL_PRESSURE].byte(delta(event.data[0], state.channelPressure))
                state.channelPressure = event.data[0]
            }
            KEY_PRESSURE -> {
                key(columns, state, event.data[0])
                columns[COLUMN_KEY_PRESSURE].byte(delta(event.data[1], state.keyPressure))
                state.keyPressure = event.data[1]
            }
            PROGRAM_CHANGE -> columns[COLUMN_PROGRAM].byte(event.data[0])
        }
    }

    private fun key(columns: Array<Output>, state: State, key: Int) {
        columns[COLUMN_NOTE_KEY].byte(delta(key, state.key))
        state.key = key
    }

    private fun delta(value: Int, previous: Int): Int = (value - previous) and 0x7f

    private class Event(val delta: Int, val status: Int, val meta: Int, val data: IntArray)

    private class Standard(private val data: ByteArray) {

        val trackCount: Int
        val division: Int
        val tracks: List<List<Event>>
        private var position = 0

        init {
            require(int() == HEADER) { "This is not a standard MIDI file." }
            val length = int()
            require(length == 6) { "A MIDI header chunk is 6 bytes, not $length." }
            short()
            trackCount = short()
            division = short()
            tracks = List(trackCount) { track() }
            require(position == data.size) { "A MIDI file has ${data.size - position} bytes after its tracks." }
        }

        private fun track(): List<Event> {
            require(int() == TRACK) { "A MIDI track does not start with MTrk." }
            val length = int()
            val end = position + length
            val events = ArrayList<Event>()
            var status = 0
            while (position < end) {
                val delta = varInt()
                var byte = data[position].toInt() and 0xff
                if (byte >= 0x80) {
                    position++
                    status = byte
                } else {
                    byte = status
                }
                if (status == 0xff) {
                    val meta = data[position++].toInt() and 0xff
                    val size = varInt()
                    val payload = IntArray(size) { data[position + it].toInt() and 0xff }
                    position += size
                    events.add(Event(delta, 0xff, meta, payload))
                    continue
                }
                val arguments = if (status and 0xf0 == 0xc0 || status and 0xf0 == 0xd0) 1 else 2
                val payload = IntArray(arguments) { data[position + it].toInt() and 0xff }
                position += arguments
                events.add(Event(delta, status, 0, payload))
            }
            require(position == end) { "A MIDI track's events run ${position - end} bytes past its length." }
            return events
        }

        private fun int(): Int {
            var value = 0
            for (byte in 0 until 4) {
                value = (value shl 8) or (data[position++].toInt() and 0xff)
            }
            return value
        }

        private fun short(): Int = ((data[position++].toInt() and 0xff) shl 8) or (data[position++].toInt() and 0xff)

        private fun varInt(): Int {
            var value = 0
            while (true) {
                val byte = data[position++].toInt()
                value = (value shl 7) or (byte and 0x7f)
                if (byte >= 0) {
                    return value
                }
            }
        }
    }

    private class Output(capacity: Int) : ByteArrayOutputStream(capacity) {

        fun byte(value: Int) = write(value and 0xff)

        fun short(value: Int) {
            byte(value shr 8)
            byte(value)
        }

        fun int(value: Int) {
            byte(value shr 24)
            byte(value shr 16)
            byte(value shr 8)
            byte(value)
        }

        fun varInt(value: Int) {
            var shift = 28
            while (shift > 0 && (value ushr shift) == 0) {
                shift -= 7
            }
            while (shift > 0) {
                byte(((value ushr shift) and 0x7f) or 0x80)
                shift -= 7
            }
            byte(value and 0x7f)
        }

        fun bytes(other: Output) = write(other.buf, 0, other.count)

        fun length(): Int = count

        fun toArray(): ByteArray = toByteArray()
    }
}
