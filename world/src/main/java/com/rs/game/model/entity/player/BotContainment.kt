package com.rs.game.model.entity.player

object BotContainment {
    @JvmStatic
    fun inTransferZone(player: Player) = player.controllerManager.controller?.allowsBotItemTransfer() == true

    @JvmStatic
    fun holdsItems(player: Player) = player.isBot && !inTransferZone(player)

    @JvmStatic
    fun refuseRelease(player: Player): Boolean {
        if (!holdsItems(player)) return false
        player.sendMessage("Bots can't let go of items here.")
        return true
    }

    @JvmStatic
    fun refuseExchange(player: Player, other: Player): Boolean {
        if (!player.isBot && !other.isBot) return false
        if (inTransferZone(player) && inTransferZone(other)) return false
        player.sendMessage("You can't exchange items with a bot.")
        return true
    }

    @JvmStatic
    fun refuseEconomy(player: Player): Boolean {
        if (!player.isBot) return false
        player.sendMessage("Bots can't take part in the economy.")
        return true
    }
}
