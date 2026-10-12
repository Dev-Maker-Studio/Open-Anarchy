package world.gregs.voidps.cache.definition.data

class SynthFilter(
    var unity: IntArray,
    var varying: Int,
    var forward: Array<SynthFilterPair>,
    var backward: Array<SynthFilterPair>,
    var envelope: SynthShape?
)
