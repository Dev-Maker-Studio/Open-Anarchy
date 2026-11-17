package com.rs.game.content.quests.area

import com.rs.Settings
import com.rs.engine.command.Commands
import com.rs.engine.dialogue.DialogueBuilder
import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.World
import com.rs.game.content.world.generated.CityResident
import com.rs.game.content.world.generated.DungeonBoss
import com.rs.game.content.world.generated.DungeonBosses
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.PluginManager
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.events.NPCClickEvent
import com.rs.plugin.kts.interceptNpcClick
import com.rs.plugin.kts.onLogin
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.city.BuildingBuilder
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.Rights

private const val TALK_OPTION = "Talk-to"
private const val MAX_MENU_QUESTS = 4
private const val PLACE_CHECK_TICKS = 5
private const val CITY_MARGIN = 2
private const val UPDATE_COLOUR = "<col=00CCFF>"
private const val FIRST_OPTION = 1
private const val LAST_OPTION = 5
private const val SHOP_SUFFIX = "keeper"

object AreaQuests {

    val active get() = AreaQuestRegistry.active

    fun giverOf(npc: NPC): Giver? {
        if (npc !is CityResident) return null
        val tile = npc.respawnTile
        val city = GeneratedWorld.cityAt(tile.x, tile.y) ?: return null
        val resident = city.residents.firstOrNull { it.x == tile.x && it.y == tile.y && it.plane == tile.plane } ?: return null
        val role = when (resident.role) {
            BuildingBuilder.BANKER, BuildingBuilder.BARBER, BuildingBuilder.REALTOR -> resident.role
            BuildingBuilder.SHOPKEEPER -> resident.shop?.removePrefix("${city.id}_") ?: return null
            else -> return null
        }
        return Giver(city.id, role)
    }

    fun role(giver: String): String = when (giver) {
        BuildingBuilder.BANKER, BuildingBuilder.BARBER, BuildingBuilder.REALTOR -> giver
        else -> "${giver.replace('_', ' ')} $SHOP_SUFFIX"
    }

    fun cityName(area: String): String = AreaQuestRegistry.city(area)?.name ?: area

    fun title(giver: Giver): String = "the ${role(giver.giver)} in ${cityName(giver.area)}"

    private fun placeName(place: Place?): String = when {
        place == null -> "the wilds"
        place.dungeon != null -> GeneratedWorld.dungeons.firstOrNull { it.id == place.dungeon }?.name ?: place.dungeon
        place.biome != null -> "the ${place.biome.lowercase().replace('_', ' ')}"
        place.area != null -> cityName(place.area)
        else -> "the marked spot (${place.x}, ${place.y})"
    }

    private fun bossName(dungeon: String?): String =
        GeneratedWorld.dungeons.firstOrNull { it.id == dungeon }?.let { DungeonBosses.definitions[it.bossId]?.name } ?: "the dungeon's master"

    fun describe(quest: AreaQuest, step: Step): String = step.journal ?: when (step.type) {
        STEP_KILL -> "Kill ${step.count} ${step.mob ?: "hostile creatures"}${step.where?.let { " in ${placeName(it)}" } ?: ""}."
        STEP_BOSS -> "Defeat ${bossName(step.dungeon)} in ${placeName(Place(dungeon = step.dungeon))}."
        STEP_DUNGEONEERING -> "Complete ${step.count} dungeoneering ${if (step.count == 1) "floor" else "floors"} at a dungeoneering cave."
        STEP_GATHER -> "Bring ${step.count} ${AreaQuestRegistry.objName(step.item.orEmpty()).lowercase()} to ${title(step.to ?: quest.source)}."
        STEP_DELIVER -> "Carry the ${AreaQuestRegistry.objName(step.item.orEmpty()).lowercase()} to ${title(step.to ?: quest.source)}."
        STEP_TALK -> "Speak to ${title(step.to ?: quest.source)}."
        STEP_SERVICE -> "Call on ${title(step.to ?: quest.source)} and use their service."
        STEP_VISIT -> "Travel to ${placeName(step.place)}."
        STEP_CHOICE -> "Decide with ${title(step.to ?: quest.source)}: ${step.prompt ?: "how to go on"}"
        else -> step.type
    }

    fun describeReturn(quest: AreaQuest) = "Return to ${title(quest.source)}."

    private fun inPlace(place: Place, x: Int, y: Int): Boolean = when {
        place.dungeon != null -> GeneratedWorld.dungeonAt(x, y)?.id == place.dungeon
        place.biome != null -> !GeneratedWorld.inDungeon(x, y) && GeneratedWorld.generator.biomeAt(x, y).name.equals(place.biome, ignoreCase = true)
        place.area != null -> AreaQuestRegistry.city(place.area)?.let { city ->
            if (place.radius == null) city.covers(x, y, CITY_MARGIN) else maxOf(Math.abs(city.centerX - x), Math.abs(city.centerY - y)) <= place.reach
        } == true
        place.x != null && place.y != null -> maxOf(Math.abs(place.x - x), Math.abs(place.y - y)) <= place.reach
        else -> false
    }

    private fun meets(player: Player, quest: AreaQuest): Boolean {
        val log = AreaQuestLog.of(player)
        val requires = quest.requires ?: return true
        return log.points >= (requires.questPoints ?: 0) && requires.quests.orEmpty().all { it in log.completed }
    }

    private fun current(player: Player): List<Triple<AreaQuest, QuestProgress, Step?>> = AreaQuestLog.of(player).active.mapNotNull { (id, progress) ->
        AreaQuestRegistry.quests[id]?.let { Triple(it, progress, it.step(progress.step)) }
    }

    private fun changed(player: Player) {
        AreaQuestLog.save(player)
        AreaQuestTab.send(player)
    }

    private fun DialogueBuilder.speak(npc: NPC, lines: List<String>) {
        for (line in lines) {
            if (line.startsWith(PLAYER_LINE, ignoreCase = true)) player(HeadE.CALM_TALK, line.substring(PLAYER_LINE.length).trim())
            else npc(npc.id, HeadE.CALM_TALK, line)
        }
    }

    private fun begin(player: Player, quest: AreaQuest, progress: QuestProgress, target: String) {
        progress.step = target
        progress.count = 0
        val step = quest.step(target)
        if (step?.type == STEP_DELIVER) handParcel(player, step)
        player.sendMessage("$UPDATE_COLOUR${quest.name}:</col> ${step?.let { describe(quest, it) } ?: describeReturn(quest)}")
        changed(player)
        if (step?.type == STEP_VISIT) checkPlace(player)
    }

    private fun handParcel(player: Player, step: Step) {
        val id = step.item?.let(DungeonBosses::objId) ?: return
        if (!player.inventory.containsItem(id, 1)) player.inventory.addItemDrop(id, 1)
    }

    private fun advance(player: Player, quest: AreaQuest, progress: QuestProgress, step: Step, target: String = quest.after(step)) {
        progress.done += step.id
        begin(player, quest, progress, target)
    }

    private fun start(player: Player, quest: AreaQuest) {
        val log = AreaQuestLog.of(player)
        if (quest.id in log.active || quest.id in log.completed) return
        val progress = QuestProgress(quest.steps.first().id)
        log.discovered += quest.id
        log.active[quest.id] = progress
        begin(player, quest, progress, progress.step)
    }

    private fun complete(player: Player, quest: AreaQuest) {
        val log = AreaQuestLog.of(player)
        if (log.active.remove(quest.id) == null) return
        val reward = AreaQuestRewards.of(quest)
        log.completed[quest.id] = reward.questPoints
        AreaQuestRewards.give(player, reward)
        changed(player)
        AreaQuestTab.showComplete(player, quest, reward)
    }

    fun onKill(player: Player, npc: NPC) {
        if (!active) return
        for ((quest, progress, step) in current(player)) {
            step ?: continue
            when (step.type) {
                STEP_BOSS -> if (npc is DungeonBoss && GeneratedWorld.dungeonAt(npc.x, npc.y)?.id == step.dungeon) advance(player, quest, progress, step)
                STEP_KILL -> {
                    val named = step.mob == null || NPCDefinitions.getDefs(npc.id).name.equals(step.mob, ignoreCase = true)
                    if (named && (step.where == null || inPlace(step.where, npc.x, npc.y))) count(player, quest, progress, step)
                }
            }
        }
    }

    @JvmStatic
    fun onDungeoneeringFloor(player: Player) {
        if (!active) return
        for ((quest, progress, step) in current(player))
            if (step?.type == STEP_DUNGEONEERING) count(player, quest, progress, step)
    }

    private fun count(player: Player, quest: AreaQuest, progress: QuestProgress, step: Step) {
        progress.count++
        if (progress.count >= step.count) advance(player, quest, progress, step)
        else {
            player.sendMessage("$UPDATE_COLOUR${quest.name}:</col> ${progress.count} of ${step.count}.")
            AreaQuestLog.save(player)
        }
    }

    private fun checkPlace(player: Player) {
        for ((quest, progress, step) in current(player))
            if (step?.type == STEP_VISIT && step.place != null && inPlace(step.place, player.x, player.y)) advance(player, quest, progress, step)
    }

    private fun usedService(player: Player, npc: NPC) {
        val giver = giverOf(npc) ?: return
        for ((quest, progress, step) in current(player))
            if (step?.type == STEP_SERVICE && step.to == giver) advance(player, quest, progress, step)
    }

    private fun passOn(player: Player, npc: NPC) {
        val option = (FIRST_OPTION..LAST_OPTION).firstOrNull { npc.getDefinitions(player).getOption(it) == TALK_OPTION } ?: return
        PluginManager.handle(NPCClickEvent(player, npc, option, true))
    }

    private fun talk(player: Player, npc: NPC): Boolean {
        val giver = giverOf(npc) ?: return false
        val log = AreaQuestLog.of(player)
        for ((quest, progress, step) in current(player)) {
            if (step == null && progress.step == RETURN_STEP && quest.source == giver) return handIn(player, npc, quest)
            if (step != null && runStep(player, npc, giver, quest, progress, step)) return true
        }
        val offers = AreaQuestRegistry.offeredBy(giver).filter { it.id !in log.active && it.id !in log.completed && meets(player, it) }
        val ongoing = current(player).filter { it.first.source == giver }
        if (offers.isEmpty() && ongoing.isEmpty()) return false
        if (log.discovered.addAll(offers.map { it.id })) {
            player.sendMessage("${UPDATE_COLOUR}New quests have been added to your quest list.")
            changed(player)
        }
        player.startConversation {
            if (offers.isNotEmpty()) npc(npc.id, HeadE.HAPPY_TALKING, "You look capable. I have work that needs doing, if you want it.")
            else npc(npc.id, HeadE.CALM_TALK, "Back again? How is the work coming along?")
            options("What would you like to ask about?") {
                for ((quest, progress, step) in ongoing.take(MAX_MENU_QUESTS))
                    op("${quest.name} (in progress)") {
                        npc(npc.id, HeadE.CALM_TALK, step?.let { describe(quest, it) } ?: describeReturn(quest))
                        if (step?.type == STEP_DELIVER) exec { handParcel(player, step) }
                        if (step?.type == STEP_KILL || step?.type == STEP_DUNGEONEERING) npc(npc.id, HeadE.CALM_TALK, "By my count you are at ${progress.count} of ${step.count}.")
                    }
                for (quest in offers.take(MAX_MENU_QUESTS - minOf(ongoing.size, MAX_MENU_QUESTS)))
                    op(quest.name) { pitch(player, npc, quest) }
                op("Something else.") { exec { passOn(player, npc) } }
            }
        }
        return true
    }

    private fun DialogueBuilder.pitch(player: Player, npc: NPC, quest: AreaQuest) {
        speak(npc, quest.intro)
        val reward = AreaQuestRewards.of(quest)
        npc(npc.id, HeadE.CALM_TALK, "It is a ${quest.length} job. I can pay ${reward.lines().drop(1).joinToString(", ")}.")
        options("Start ${quest.name}?") {
            op(quest.accept ?: "I'll do it.") {
                player(HeadE.CHEERFUL, quest.accept ?: "I'll do it.")
                exec { start(player, quest) }
            }
            op("Not right now.") { player(HeadE.CALM_TALK, "Not right now.") }
        }
    }

    private fun handIn(player: Player, npc: NPC, quest: AreaQuest): Boolean {
        player.startConversation {
            speak(npc, quest.outro ?: listOf("Well done. You have earned this."))
            exec { complete(player, quest) }
        }
        return true
    }

    private fun runStep(player: Player, npc: NPC, giver: Giver, quest: AreaQuest, progress: QuestProgress, step: Step): Boolean {
        val target = step.to ?: quest.source
        if (target != giver) return false
        when (step.type) {
            STEP_TALK -> player.startConversation {
                speak(npc, step.lines.orEmpty())
                exec { advance(player, quest, progress, step) }
            }
            STEP_DELIVER, STEP_GATHER -> {
                val id = step.item?.let(DungeonBosses::objId) ?: return false
                val needed = if (step.type == STEP_DELIVER) 1 else step.count
                val held = player.inventory.getAmountOf(id)
                val name = AreaQuestRegistry.objName(step.item).lowercase()
                player.startConversation {
                    if (held < needed) npc(npc.id, HeadE.CALM_TALK, "I am waiting on $needed $name for ${quest.name}. You have $held with you.")
                    else {
                        speak(npc, step.lines ?: listOf("player: Here you are, $needed $name.", "Just what was needed. Thank you."))
                        exec {
                            if (player.inventory.getAmountOf(id) >= needed) {
                                player.inventory.deleteItem(id, needed)
                                advance(player, quest, progress, step)
                            }
                        }
                    }
                }
            }
            STEP_CHOICE -> player.startConversation {
                speak(npc, step.lines.orEmpty())
                options(step.prompt) {
                    for (option in step.options.orEmpty())
                        op(option.text) {
                            player(HeadE.CALM_TALK, option.text)
                            exec { advance(player, quest, progress, step, option.next) }
                        }
                }
            }
            else -> return false
        }
        return true
    }

    fun reset(player: Player) {
        val log = AreaQuestLog.of(player)
        log.discovered.clear()
        log.active.clear()
        log.completed.clear()
        changed(player)
    }

    @ServerStartupEvent
    @JvmStatic
    fun init() {
        if (!active) return
        AreaQuestPlanner.planMissing()
        AreaQuestRegistry.load()
        AreaQuestPlanner.exportWorld()
        interceptNpcClick { player, npc ->
            usedService(player, npc)
            false
        }
        interceptNpcClick(TALK_OPTION) { player, npc -> talk(player, npc) }
        onLogin { e -> AreaQuestLog.of(e.player) }
        WorldTasks.scheduleTimer(PLACE_CHECK_TICKS, PLACE_CHECK_TICKS) {
            World.players.forEach { player -> if (player.hasStarted() && !player.hasFinished() && AreaQuestLog.of(player).active.isNotEmpty()) checkPlace(player) }
            true
        }
        val rights = if (Settings.getConfig().isDebug) Rights.PLAYER else Rights.ADMIN
        Commands.add(rights, "areaquests [reload|reset|make (city)]", "Reloads the area quest files, clears your area quest log, or remakes a city's generated quests.") { player, args ->
            when (args.getOrNull(0)) {
                "reload" -> AreaQuestRegistry.load()
                "reset" -> reset(player)
                "make" -> AreaQuestRegistry.city(args.getOrNull(1).orEmpty())?.let {
                    AreaQuestPlanner.write(it)
                    AreaQuestRegistry.load()
                }
            }
            val log = AreaQuestLog.of(player)
            player.sendMessage("Area quests: ${AreaQuestRegistry.quests.size} loaded; you have ${log.active.size} active, ${log.completed.size} complete, ${log.points} quest points.")
            AreaQuestTab.send(player)
        }
    }
}
