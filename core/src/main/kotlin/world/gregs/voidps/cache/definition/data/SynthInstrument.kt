package world.gregs.voidps.cache.definition.data

class SynthInstrument(
    var pitch: SynthEnvelope,
    var volume: SynthEnvelope,
    var pitchModifier: SynthEnvelope?,
    var pitchModifierAmplitude: SynthEnvelope?,
    var volumeMultiplier: SynthEnvelope?,
    var volumeAmplitude: SynthEnvelope?,
    var release: SynthEnvelope?,
    var attack: SynthEnvelope?,
    var oscillatorVolume: IntArray,
    var oscillatorPitch: IntArray,
    var oscillatorDelay: IntArray,
    var delayTime: Int,
    var delayDecay: Int,
    var duration: Int,
    var offset: Int,
    var filter: SynthFilter
) {

    companion object {
        const val OSCILLATORS = 5
    }
}
