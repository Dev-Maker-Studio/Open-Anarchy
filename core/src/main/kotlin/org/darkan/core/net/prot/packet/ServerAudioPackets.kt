package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt

data class VorbisSound(val soundId: Int, val repeat: Int, val delay: Int, val volume: Int, val sampleRate: Int) : ServerProt

data class VorbisSpeechSound(val soundId: Int, val repeat: Int, val delay: Int, val volume: Int) : ServerProt

data class SoundSynth(val soundId: Int, val repeat: Int, val delay: Int, val volume: Int, val sampleRate: Int) : ServerProt

data class MusicEffect(val unused: Int, val volume: Int, val musicId: Int) : ServerProt

data class MusicTrack(val delay: Int, val volume: Int, val musicId: Int) : ServerProt

@JvmInline value class PreloadSong(val songId: Int) : ServerProt

@JvmInline value class StopVorbisStream(val soundId: Int) : ServerProt

data class VorbisPreloadSound(val soundId: Int, val priority: Int) : ServerProt

@JvmInline value class SetVorbisSound(val soundId: Int) : ServerProt

data class SoundMixbussSetLevel(val index: Int, val level: Int) : ServerProt

@JvmInline value class ResetSounds(val dummy: Int = 0) : ServerProt
