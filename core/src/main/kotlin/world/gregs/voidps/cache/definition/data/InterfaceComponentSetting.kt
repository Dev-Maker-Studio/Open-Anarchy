package world.gregs.voidps.cache.definition.data

data class InterfaceComponentSetting(var setting: Int, var anInt7413: Int) {

    companion object {
        fun useOptionFlags(setting: Int) = (setting shr 11) and 0x7f
    }
}