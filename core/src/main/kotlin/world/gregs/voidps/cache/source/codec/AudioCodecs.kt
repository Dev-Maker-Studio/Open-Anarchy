package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.NativeLibrary
import world.gregs.voidps.cache.audio.JagexMidi
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition
import world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder
import world.gregs.voidps.cache.definition.encoder.SynthSoundEncoder

object AudioCodecs {

    val synthSounds: DefinitionJsonCodec<SynthSoundDefinition> = run {
        val decoder = SynthSoundDecoder()
        val encoder = SynthSoundEncoder()
        DefinitionJsonCodec(
            id = "json-synth",
            layout = DefinitionLayout.Packed(0),
            create = { SynthSoundDefinition(it) },
            decode = { definition, bytes -> decoder.read(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    val midi = TranscodedArchiveCodec("midi", MIDI_EXTENSION, JagexMidi::toStandard, JagexMidi::toCompact)

    val midiInstruments = BinaryArchiveCodec("jagpatch", PATCH_EXTENSION)

    val nativeLibraries = NamedBytesCodec("dlls") { hash -> NativeLibrary.name(hash) }

    fun register() {
        SourceCodecs.builtin(Index.SYNTH_SOUNDS, synthSounds)
        SourceCodecs.builtin(Index.MIDI_SONGS, midi)
        SourceCodecs.builtin(Index.MIDI_JINGLES, midi)
        SourceCodecs.builtin(Index.VORBIS, VorbisCodec)
        SourceCodecs.builtin(Index.MIDI_INSTRUMENTS, midiInstruments)
        SourceCodecs.builtin(Index.NATIVE_LIBRARIES, nativeLibraries)
        SourceCodecs.builtin(Index.SHADERS, ShaderCodec)
    }

    const val MIDI_EXTENSION = ".mid"

    const val PATCH_EXTENSION = ".jagpatch"
}
