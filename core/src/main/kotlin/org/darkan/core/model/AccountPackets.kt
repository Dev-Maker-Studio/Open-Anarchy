package org.darkan.core.model

import org.darkan.core.net.prot.packet.LobbyLoginDetails


fun Account.toLobbyLoginDetails(
    worldLoginToken: String,
    defaultWorldId: Int = LobbyLoginDetails.DEFAULT_WORLD_ID,
    defaultWorldAddress: String = LobbyLoginDetails.DEFAULT_WORLD_ADDRESS,
) = LobbyLoginDetails(
    crown = rights.crown,
    displayName = displayName,
    lastIpHash = packIpv4(lastIp),
    worldLoginToken = worldLoginToken,
    defaultWorldId = defaultWorldId,
    defaultWorldAddress = defaultWorldAddress,
)

private fun packIpv4(address: String?): Int {
    val octets = address?.removeSurrounding("[", "]")?.split('.') ?: return 0
    if (octets.size != 4) return 0
    var packed = 0
    for (octet in octets) {
        val value = octet.toIntOrNull() ?: return 0
        if (value !in 0..255) return 0
        packed = (packed shl 8) or value
    }
    return packed
}
