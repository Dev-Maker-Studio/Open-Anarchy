package org.darkan.core.net.prot.dto

enum class InstanceLoadType(val index: Int) {
    STANDARD(1),
    STANDARD_NO_ENTITY_RESET(2),
    LARGE(3),
    LARGE_NO_UPDATEZONE_RESET(4)
}
