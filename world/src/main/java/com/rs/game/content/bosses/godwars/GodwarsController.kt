package com.rs.game.content.bosses.godwars

import com.rs.engine.dialogue.startConversation
import com.rs.game.World.sendProjectile
import com.rs.game.content.bosses.godwars.factions.GodFaction
import com.rs.game.content.bosses.godwars.factions.zaros.NexArena
import com.rs.game.content.bosses.godwars.factions.zaros.NexController
import com.rs.game.content.skills.magic.Magic.sendNormalTeleportNoType
import com.rs.game.content.skills.magic.TeleType
import com.rs.game.content.world.generated.GodwarsDungeon
import com.rs.game.content.world.generated.GodwarsDungeons
import com.rs.game.model.entity.async.schedule
import com.rs.game.model.entity.player.Controller
import com.rs.game.model.entity.player.Skills
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.Constants
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Seq
import world.gregs.voidps.type.Tile
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.gameval.gen.Varbit

@ServerStartupEvent
fun mapGWDEntranceAndExit() {
    onObjectClick(26342) { (player, obj, option) ->
        if (option == "Tie-rope") {
            if (player.inventory.containsItem(954)) {
                player.anim(832)
                player.inventory.deleteItem(954, 1)
                player.vars.saveVarBit(Varbit.godwars_lore_book_saradomin_amulet, 1)
                player.sendMessage("You carefully secure the rope and throw it into the dark hole.")
            } else
                player.sendMessage("A rope would help here.")
            return@onObjectClick
        }
        val dungeon = GodwarsDungeons.entranceAt(obj.x, obj.y)
        if (dungeon != null) {
            player.faceObject(obj)
            GodwarsDungeons.enter(player, dungeon)
            return@onObjectClick
        }
        player.schedule {
            player.lock()
            player.faceObject(obj)
            wait(1)
            player.anim(828)
            wait(2)
            player.tele(Tile.of(2881, 5310, 2))
            player.controllerManager.startController(GodwarsController())
            wait(1)
            player.unlock()
        }
    }

    onObjectClick(26293) { (player) -> player.useStairs(828, Tile.of(2916, 3746, 0)) }
}

class GodwarsController(private val dungeon: String? = null) : Controller() {
    private val killcount = IntArray(GodFaction.entries.size)
    private var lastPrayerRecharge: Long = 0

    private val place: GodwarsDungeon? get() = dungeon?.let(GodwarsDungeons::byId)

    val sourceX: Int get() = place?.sourceX(player.x) ?: player.x
    val sourceY: Int get() = place?.sourceY(player.y) ?: player.y

    fun tile(x: Int, y: Int, plane: Int): Tile = place?.tile(x, y, plane) ?: Tile.of(x, y, plane)

    override fun start() {
        if (dungeon != null) {
            player.getO<List<Number>>(KILLS_KEY + dungeon)?.forEachIndexed { faction, kills -> if (faction < killcount.size) killcount[faction] = kills.toInt() }
            player.sendMessage("Your kills in this dungeon are remembered between visits.")
        }
        sendInterfaces()
    }

    override fun logout(): Boolean {
        place?.let { player.setTile(it.surface) }
        return false
    }

    override fun login(): Boolean {
        if (dungeon != null) {
            place?.let { player.tele(it.surface) }
            player.interfaceManager.removeOverlay(true)
            return true
        }
        sendInterfaces()
        return false
    }

    private fun inside(tile: Tile): Boolean = if (dungeon != null) place?.contains(tile) == true else isAtGodwars(tile)

    private fun storeKills() {
        if (dungeon != null) player.set(KILLS_KEY + dungeon, killcount.toList())
    }

    private fun spend(faction: GodFaction) {
        killcount[faction.ordinal] -= BOSS_ROOM_KILLS
        storeKills()
        updateKillcount()
    }

    private fun instanceDoor(obj: GameObject): Boolean {
        if (dungeon == null || obj.id !in INSTANCE_DOORS) return true
        player.sendMessage("Boss instances are not offered in this dungeon.")
        return false
    }

    override fun processObjectClick3(obj: GameObject): Boolean = instanceDoor(obj)

    override fun processObjectClick4(obj: GameObject): Boolean = instanceDoor(obj)

    override fun process() {
        if (!inside(player.nextTile ?: player.tile)) {
            remove()
            removeController()
        }
        updateKillcount()
    }

    override fun processObjectClick1(obj: GameObject): Boolean {
        val exit = place
        if (exit != null && obj.id == Loc.godwars_entrance_rope_bottom) {
            player.useStairs(Seq.human_reachforladder, exit.surface)
            return false
        }
        if (obj.id == 26287 || obj.id == 26286 || obj.id == 26288 || obj.id == 26289) {
            if (lastPrayerRecharge >= System.currentTimeMillis()) {
                player.sendMessage("You must wait a total of 10 minutes before being able to recharge your prayer points.")
                return false
            } else if (player.inCombat()) {
                player.sendMessage("You cannot recharge your prayer while engaged in combat.")
                return false
            }
            player.prayer.restorePrayer((player.skills.getLevelForXp(Constants.PRAYER) * 10).toDouble())
            player.anim(645)
            player.sendMessage("Your prayer points feel rejuvenated.")
            lastPrayerRecharge = 600000 + System.currentTimeMillis()
            return false
        }
        if (obj.id == 26444) {
            if (player.skills.getLevel(Constants.AGILITY) >= 70) player.useStairs(828, tile(2914, 5300, 1), 1, 2)
            else player.sendMessage("You need an Agility level of 70 to maneuver this obstacle.")
            return false
        }

        if (obj.id == 26445) {
            if (player.skills.getLevel(Constants.AGILITY) >= 70) player.useStairs(828, tile(2920, 5274, 0), 1, 2)
            else player.sendMessage("You need an Agility level of 70 to maneuver this obstacle.")
            return false
        }

        if (obj.id == 26427 && sourceX >= 2908) {
            if (killcount[GodFaction.SARADOMIN.ordinal] >= 40) {
                player.tele(tile(2907, 5265, 0))
                spend(GodFaction.SARADOMIN)
            } else player.sendMessage("This door is locked by Saradomin and requires that you kill 40 of his minions before it is unlocked.")
            return false
        }

        if (obj.id == 26303) {
            if (player.skills.getLevel(Skills.RANGE) >= 70) {
                val withinArmadyl = sourceY < 5276
                val tile: Tile = this.tile(2871, if (withinArmadyl) 5279 else 5269, 2)
                player.lock()
                player.schedule {
                    wait(1)
                    player.setNextFaceTile(tile)
                    player.anim(385)
                    wait(2)
                    player.anim(16635)
                    wait(1)
                    player.appearance.isHidden = true
                    sendProjectile(player.tile, tile, 1185, 18 to 18, 30, 15, 30)
                    player.forceMove(tile, 0, 180, false) {
                        player.appearance.isHidden = false
                        player.anim(16672)
                        player.unlock()
                        player.resetReceivedHits()
                    }
                }
            } else player.sendMessage("You need a Ranged level of 70 to cross this obstacle.")
            return false
        }


        if (obj.id == 26426 && sourceY <= 5295) {
            if (killcount[GodFaction.ARMADYL.ordinal] >= 40) {
                player.tele(tile(2839, 5296, 2))
                spend(GodFaction.ARMADYL)
            } else player.sendMessage("This door is locked by Armadyl and requires that you kill 40 of his minions before it is unlocked.")
            return false
        }

        if (obj.id == 26428 && sourceY >= 5332) {
            if (killcount[GodFaction.ZAMORAK.ordinal] >= 40) {
                player.tele(tile(2925, 5331, 2))
                spend(GodFaction.ZAMORAK)
            } else player.sendMessage("This door is locked by Zamorak and requires that you kill 40 of his minions before it is unlocked.")
            return false
        }

        if (obj.id == 26425 && sourceX <= 2863) {
            if (killcount[GodFaction.BANDOS.ordinal] >= 40) {
                player.tele(tile(2864, 5354, 2))
                spend(GodFaction.BANDOS)
            } else player.sendMessage("This door is locked by Bandos and requires that you kill 40 of his minions before it is unlocked.")
            return false
        }

        if (obj.id == 26384) {
            if (player.skills.getLevel(Constants.STRENGTH) >= 70) {
                if (player.inventory.containsItem(2347, 1)) {
                    if (sourceX == 2851) {
                        player.sendMessage("You bang on the door with your hammer.")
                        player.useStairs(11033, tile(2850, 5333, 2), 1, 2)
                    } else if (sourceX == 2850) {
                        player.sendMessage("You bang on the door with your hammer.")
                        player.useStairs(11033, tile(2851, 5333, 2), 1, 2)
                    }
                } else player.sendMessage("You need a hammer to be able to hit the gong to request entry.")
            } else player.sendMessage("You need a Strength level of 70 to enter this area.")
            return false
        }

        if (obj.id == 57211) {
            if (sourceY == 5279) {
                val key = player.inventory.getItemById(20120)
                if (key != null && key.getMetaData("frozenKeyCharges") != null && key.getMetaDataI("frozenKeyCharges") > 1) {
                    player.useStairs(828, tile(2885, 5275, 2), 1, 2)
                    key.addMetaData("frozenKeyCharges", key.getMetaDataI("frozenKeyCharges") - 1)
                    if (key.getMetaData("frozenKeyCharges") as Int == 1) player.sendMessage("Your frozen key breaks. You will have to repair it on a repair stand.")
                    else player.sendMessage("A part of your key chips off. It looks like it will still work.")
                } else player.sendMessage("You require a frozen key with enough charges to enter.")
            } else player.useStairs(828, tile(2885, 5279, 2), 1, 2)
            return false
        }

        if (obj.id == 57260) {
            player.useStairs(828, tile(2886, 5274, 2), 1, 2)
            return false
        }

        if (obj.id == 57254) {
            player.useStairs(828, tile(2855, 5221, 0), 1, 2)
            return false
        }

        if (obj.id == 57234) {
            if (sourceX == 2859) player.tele(player.transform(3, 0, 0))
            else if (sourceX == 2862) player.tele(player.transform(-3, 0, 0))
            return false
        }

        if (obj.id == 57258) {
            if (killcount[GodFaction.ZAROS.ordinal] >= 40 || player.equipment.wearingFullCeremonial()) {
                if (player.equipment.wearingFullCeremonial()) player.sendMessage("The door somehow recognizes your relevance to the area and allows you to pass through.")
                player.tele(tile(2900, 5204, 0))
            } else player.sendMessage("This door is locked by the power of Zaros. You will need to kill at least 40 of his followers before the door will open.")
            return false
        }

        if (obj.id == 57225) {
            val globalInstance: NexArena = if (dungeon == null) NexArena.globalInstance else place?.nexArena ?: return false
            player.startConversation {
                simple("The room beyond this point is a prison! There is no way out other than death or teleport. Only those who endure dangerous encounters should proceed.")
                options("There are currently " + globalInstance.playersCount + " people fighting.<br>Do you wish to join them?") {
                    opExec("Climb down.") {
                        player.tele(tile(2911, 5204, 0))
                        player.controllerManager.startController(NexController(globalInstance, dungeon))
                    }
                    op("Stay here.")
                }
            }
            return false
        }
        return true
    }

    override fun processObjectClick2(obj: GameObject): Boolean {
        if (!instanceDoor(obj)) return false
        if (obj.id == 26286) sendNormalTeleportNoType(player, tile(2922, 5345, 2))
        else if (obj.id == 26287) sendNormalTeleportNoType(player, tile(2912, 5268, 0))
        else if (obj.id == 26288) sendNormalTeleportNoType(player, tile(2842, 5266, 2))
        else if (obj.id == 26289) sendNormalTeleportNoType(player, tile(2837, 5355, 2))
        return true
    }

    override fun sendInterfaces() {
        player.interfaceManager.sendOverlay(601, true)
        player.packets.sendRunScriptReverse(1171)
    }

    override fun sendDeath(): Boolean {
        remove()
        removeController()
        return true
    }

    override fun onTeleported(type: TeleType) {
        if (dungeon != null) return
        remove()
        removeController()
    }

    override fun forceClose() = remove()

    fun updateKillcount() {
        GodFaction.entries.forEach {
            player.vars.setVarBit(it.kcVarbit, killcount[it.ordinal])
        }
    }

    fun sendKill(faction: GodFaction) {
        killcount[faction.ordinal]++
        storeKills()
        updateKillcount()
    }

    fun remove() {
        player.interfaceManager.removeOverlay(true)
        player.sendMessage(if (dungeon == null) "The souls of those you have slain leave you as you exit the dungeon." else "The souls of those you have slain here will await your return to this dungeon.")
    }

    companion object {
        val GWD_REGIONS = setOf(11345, 11346, 11347, 11601, 11602, 11603)
        @JvmStatic
        fun isAtGodwars(teleTile: Tile) = !EnvVars.generateMaps && GWD_REGIONS.contains(teleTile.getRegionId())

        private const val KILLS_KEY = "godwarsKills:"
        private const val BOSS_ROOM_KILLS = 40
        private val INSTANCE_DOORS = setOf(Loc.loc_26425, Loc.loc_26426, Loc.loc_26427, Loc.loc_26428, Loc.loc_57225)
    }
}

@ServerStartupEvent
fun mapFrozenKeyTooltip() {
    ItemTooltips.metadata("frozenKeyCharges") { _, item, _ -> ItemTooltips.count("Uses", (item.getMetaDataI("frozenKeyCharges") - 1).coerceAtLeast(0), 99) }
}
