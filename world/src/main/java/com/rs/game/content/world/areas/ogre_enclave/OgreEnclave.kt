package com.rs.game.content.world.areas.ogre_enclave

import com.rs.engine.dialogue.Dialogue
import com.rs.engine.dialogue.HeadE
import com.rs.engine.quest.Quest
import com.rs.game.World
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.plugin.annotations.PluginEventHandler
import com.rs.plugin.handlers.NPCClickHandler
import com.rs.plugin.handlers.ObjectClickHandler
import org.darkan.core.model.MoveType
import world.gregs.voidps.type.Tile

@PluginEventHandler
class OgreEnclave {
    companion object {
        private const val ENCLAVE_GUARD = 870
        private val OGRE_SHAMANS = arrayOf<Any>(5176, 5181, 5184, 5187, 5190, 5193)
        private val OGRE_SHAMAN_VARBITS = intArrayOf(3131, 3132, 3133, 3134, 3135, 3136)
        private const val CUTSCENE_OGRE_SHAMAN = 5176
        private const val CUTSCENE_BLUE_DRAGON = 55
        private const val ROCK_OF_DALGROTH = 2816
        private const val GREEN_CRYSTAL = 2383
        private const val DALGROTH_PILLAR_VARBIT = 3130
        private const val STAGE_FIND_SHAMAN_ENCLAVE = 8
        private const val STAGE_DISCOVER_SHAMANS = 9
        private const val STAGE_RECOVER_CRYSTALS = 12
        private const val SHAMAN_CAVE_GUARD_DISTRACTED = "watchtower_shaman_cave_guard_distracted"
        private const val ENCLAVE_SCENE_SEEN = "watchtower_enclave_scene_seen"
        private const val DALGROTH_CRYSTAL_OBTAINED = "watchtower_dalgroth_crystal_obtained"
        private val ENCLAVE_ENTRANCE = Tile.of(2589, 9409, 0)
        private val ENCLAVE_EXIT = Tile.of(2541, 3054, 0)

        @JvmField
        val handleogreenclaveentrance = ObjectClickHandler(arrayOf<Any>(2804)) { e ->
            val player = e.player
            if (player.isQuestComplete(Quest.WATCHTOWER)) {
                player.tele(ENCLAVE_ENTRANCE)
                return@ObjectClickHandler
            }
            val stage = player.getQuestStage(Quest.WATCHTOWER)
            if (stage < STAGE_FIND_SHAMAN_ENCLAVE) {
                player.sendMessage("You do not meet the requirements for Watchtower.")
                return@ObjectClickHandler
            }
            if (stage == STAGE_FIND_SHAMAN_ENCLAVE && !player.getBool(SHAMAN_CAVE_GUARD_DISTRACTED)) {
                player.startConversation(Dialogue().addNPC(ENCLAVE_GUARD, HeadE.CALM_TALK, "No you don't!"))
                return@ObjectClickHandler
            }
            if (stage == STAGE_FIND_SHAMAN_ENCLAVE) {
                player.fadeScreen {
                    player.tele(ENCLAVE_ENTRANCE)
                    player.setQuestStage(Quest.WATCHTOWER, STAGE_DISCOVER_SHAMANS)
                    if (!player.getBool(ENCLAVE_SCENE_SEEN)) {
                        player.set(ENCLAVE_SCENE_SEEN, true)
                        playEnclaveScene(player)
                    }
                }
                return@ObjectClickHandler
            }
            player.tele(ENCLAVE_ENTRANCE)
        }

        private fun playEnclaveScene(player: Player) {
            player.playCutscene { cs ->
                cs.setEndTile(ENCLAVE_ENTRANCE)
                cs.fadeIn(5)
                cs.npcCreate("watchtowerShaman", CUTSCENE_OGRE_SHAMAN, 2592, 9436, 0)
                cs.npcCreate("watchtowerDragon", CUTSCENE_BLUE_DRAGON, 2592, 9431, 0)
                cs.playerMove(2589, 9409, 0, MoveType.TELEPORT)
                cs.npcFaceNPC("watchtowerShaman", "watchtowerDragon")
                cs.npcFaceNPC("watchtowerDragon", "watchtowerShaman")
                cs.camPos(2583, 9433, 3000)
                cs.camLook(2592, 9434, 0)
                cs.fadeOut(5)
                cs.dialogue(Dialogue().addSimple("Meanwhile, in the Shaman's enclave..."))
                cs.npcAnim("watchtowerDragon", 81)
                cs.action(1) {
                    World.sendProjectile(
                        cs.getNPC("watchtowerDragon"),
                        cs.getNPC("watchtowerShaman"),
                        393,
                        Pair(34, 16),
                        30,
                        5,
                        16
                    )
                }
                cs.delay(2)
                cs.npcTalk("watchtowerShaman", "Hur, hut, hur. Stoopid dragon!")
                cs.delay(2)
                cs.npcTalk("watchtowerShaman", "Take dis!")
                cs.action(1) {
                    val dragon = cs.getNPC("watchtowerDragon")
                    val shaman = cs.getNPC("watchtowerShaman")
                    if (dragon != null && shaman != null && !dragon.isDead) {
                        dragon.applyHit(Hit(shaman, dragon.hitpoints, Hit.HitLook.TRUE_DAMAGE))
                    }
                }
                cs.delay(3)
                cs.npcDestroy("watchtowerDragon")
                cs.delay(1)
                cs.npcTalk("watchtowerShaman", "Hur, hur, hur, hur!")
                cs.delay(3)
                cs.fadeIn(5)
                cs.npcDestroy("watchtowerShaman")
                cs.camPosReset()
                cs.fadeOut(5)
            }
        }

        @JvmField
        val handleogreenclaveexit = ObjectClickHandler(arrayOf<Any>(32494)) { e ->
            e.player.tele(ENCLAVE_EXIT)
        }

        @JvmField
        val talkToOgreShaman = NPCClickHandler(OGRE_SHAMANS, arrayOf("Talk-to")) { e ->
            val player = e.player
            if (player.isQuestComplete(Quest.WATCHTOWER)) return@NPCClickHandler
            val stage = player.getQuestStage(Quest.WATCHTOWER)
            if (stage < STAGE_DISCOVER_SHAMANS || stage >= STAGE_RECOVER_CRYSTALS) return@NPCClickHandler
            if (isShamanDestroyedForPlayer(player, e.npc.id)) return@NPCClickHandler
            player.startConversation(
                Dialogue()
                    .addNPC(e.npc.id, HeadE.ANGRY, "Grr! How dare you talk to us?")
                    .addNPC(e.npc.id, HeadE.ANGRY, "Grr! How dare you talk to us?")
                    .addNext { player.applyHit(Hit(e.npc, 200, Hit.HitLook.MAGIC_DAMAGE)) }
            )
        }

        @JvmField
        val attackOgreShaman = NPCClickHandler(OGRE_SHAMANS, arrayOf("Attack")) { e ->
            val player = e.player
            if (player.isQuestComplete(Quest.WATCHTOWER)) return@NPCClickHandler
            val stage = player.getQuestStage(Quest.WATCHTOWER)
            if (stage < STAGE_DISCOVER_SHAMANS || stage >= STAGE_RECOVER_CRYSTALS) return@NPCClickHandler
            if (isShamanDestroyedForPlayer(player, e.npc.id)) return@NPCClickHandler
            player.sendMessage("You attempt to attack the shaman...")
            player.startConversation(
                Dialogue()
                    .addNPC(e.npc.id, HeadE.ANGRY, "You are no match for our magics!")
                    .addNPC(e.npc.id, HeadE.ANGRY, "You are no match for our magics!")
                    .addNext {
                        player.sendMessage("A magic blast comes from the shaman.")
                        player.sendMessage("You are badly injured by the blast.")
                        val damage = maxOf(1, player.maxHitpoints / 2)
                        player.applyHit(Hit(e.npc, damage, Hit.HitLook.MAGIC_DAMAGE))
                    }
            )
        }

        @JvmField
        val rockOfDalgroth = ObjectClickHandler(arrayOf<Any>(ROCK_OF_DALGROTH)) { e ->
            val player = e.player
            if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_DISCOVER_SHAMANS) return@ObjectClickHandler
            when {
                e.option.equals("Prospect", ignoreCase = true) -> prospectRock(player)
                e.option.equals("Mine", ignoreCase = true) -> mineRock(player)
            }
        }

        private fun prospectRock(player: Player) {
            player.sendMessage("You examine the rock for ores...")
            if (player.getBool(DALGROTH_CRYSTAL_OBTAINED)) {
                player.sendMessage("The rock has a gap in which a crystal used to sit.")
                return
            }
            player.sendMessage("The rock contains a crystal!")
        }

        private fun mineRock(player: Player) {
            if (player.isQuestComplete(Quest.WATCHTOWER)) return
            val stage = player.getQuestStage(Quest.WATCHTOWER)
            if (player.skills.getLevel(Skills.MINING) < 40) {
                player.sendMessage("You need a Mining level of 40 to mine this rock.")
                return
            }
            if (stage < STAGE_RECOVER_CRYSTALS) {
                player.startConversation(
                    Dialogue().addPlayer(
                        HeadE.CONFUSED,
                        "I can't touch it... Perhaps it is linked with the shamans in some strange way?"
                    )
                )
                return
            }
            if (player.inventory.containsItem(GREEN_CRYSTAL, 1) ||
                player.bank.containsItem(GREEN_CRYSTAL, 1) ||
                player.vars.getVarBit(DALGROTH_PILLAR_VARBIT) != 0
            ) {
                player.startConversation(
                    Dialogue().addPlayer(
                        HeadE.CALM_TALK,
                        "I have already this crystal. There is no benefit to getting another."
                    )
                )
                return
            }
            player.set(DALGROTH_CRYSTAL_OBTAINED, true)
            player.inventory.addItemDrop(GREEN_CRYSTAL, 1)
            player.sendMessage("A crack appears in the rock and you prise a crystal out.")
        }

        private fun isShamanDestroyedForPlayer(player: Player, npcId: Int): Boolean {
            OGRE_SHAMANS.forEachIndexed { index, id ->
                if (id as Int == npcId) return player.vars.getVarBit(OGRE_SHAMAN_VARBITS[index]) != 0
            }
            return false
        }
    }
}
