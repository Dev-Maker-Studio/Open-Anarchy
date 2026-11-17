package com.rs.game.content.claims

import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.gameval.gen.Loc
import world.gregs.voidps.type.Tile

object BedRespawn {
    private const val BED_TILE = "respawnBedTile"
    private const val BED_LOC = "respawnBedLoc"
    private const val WAKE_TILE = "respawnWakeTile"
    private const val UNSET = -1

    fun set(player: Player, bed: GameObject) {
        if (ClaimManager.claimAt(bed.tile)?.owner != player.username) {
            player.sendMessage("You can only set your respawn point at a bed on land you have claimed or in a home you own.")
            return
        }
        player.set(BED_TILE, bed.tile.getTileHash())
        player.set(BED_LOC, bed.id)
        player.set(WAKE_TILE, player.tile.getTileHash())
        player.sendMessage("Your respawn point is set. You will wake up beside this bed when you die.")
    }

    fun wake(player: Player) {
        val bedTile = player.getI(BED_TILE, UNSET)
        if (bedTile == UNSET) return
        val bed = Tile.of(bedTile)
        if (World.getObjectWithId(bed, player.getI(BED_LOC, UNSET)) == null || ClaimManager.claimAt(bed)?.owner != player.username) {
            clear(player)
            player.sendMessage("The bed you set as your respawn point is no longer yours, so you wake up elsewhere.")
            return
        }
        player.tele(Tile.of(player.getI(WAKE_TILE, bedTile)))
        player.sendMessage("You wake up beside your bed.")
    }

    private fun clear(player: Player) {
        player.delete(BED_TILE)
        player.delete(BED_LOC)
        player.delete(WAKE_TILE)
    }
}

@ServerStartupEvent
fun mapBedRespawn() {
    onObjectClick(Loc.bed) { (player, obj) -> BedRespawn.set(player, obj) }
}
