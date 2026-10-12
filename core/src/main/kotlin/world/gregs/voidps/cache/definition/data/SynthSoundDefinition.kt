package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

class SynthSoundDefinition(
    override var id: Int = -1,
    var instruments: Array<SynthInstrument?> = arrayOfNulls(SLOTS),
    var loopBegin: Int = 0,
    var loopEnd: Int = 0
) : Definition {

    companion object {
        const val SLOTS = 10
    }
}
