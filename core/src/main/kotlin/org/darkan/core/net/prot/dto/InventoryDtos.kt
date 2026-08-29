/**
 * Payload types carried by the inventory and Grand Exchange packets.
 */
package org.darkan.core.net.prot.dto

data class InvSlotUpdate(val slot: Int, val itemId: Int, val amount: Int)

data class GrandExchangeOffer(
    val progress: Int,
    val itemId: Int,
    val price: Int,
    val amount: Int,
    val currentAmount: Int,
    val totalPrice: Int,
)
