package com.rs.game.content.skills.sailing

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.darkan.core.mongo.MongoDB
import java.util.concurrent.ConcurrentHashMap

private const val COLLECTION = "sailors"
private const val ID = "_id"

class ActiveTask(
    val id: Long,
    val kind: TaskKind,
    val from: String,
    val to: String,
    val crates: Int,
    val baseXp: Int,
    val coins: Int,
    var collected: Boolean = false,
    val monster: Int = NO_MONSTER
) {
    companion object {
        const val NO_MONSTER = -1
    }
}

class SailorProfile(val username: String) {
    val log = CaptainsLog()
    val tasks = mutableListOf<ActiveTask>()
    var lastDock = ""
    var chartedSeas = mutableSetOf<String>()
    var wrecksSalvaged = 0
    var tasksCompleted = 0
    val unlocks = mutableSetOf<String>()
    val trialRanks = mutableMapOf<String, Int>()
    val trialBests = mutableMapOf<String, Int>()

    fun rank(course: TrialCourse): TrialRank = trialRanks[course.name]?.let { TrialRank.entries.getOrNull(it) } ?: TrialRank.NONE

    fun unlocked(schematic: Schematic): Boolean = schematic.name in unlocks
}

object SailorStore {
    private val collection by lazy { MongoDB.database.getCollection<Document>(COLLECTION) }
    private val cache = ConcurrentHashMap<String, SailorProfile>()

    fun of(username: String): SailorProfile = cache.computeIfAbsent(username) { load(it) }

    fun save(profile: SailorProfile) {
        runCatching { runBlocking { collection.replaceOne(Filters.eq(ID, profile.username), toDocument(profile), ReplaceOptions().upsert(true)) } }
    }

    fun forget(username: String) {
        cache.remove(username)
    }

    fun prune(online: Set<String>) {
        cache.keys.removeIf { it !in online }
    }

    private fun load(username: String): SailorProfile {
        val document = runCatching { runBlocking { collection.find(Filters.eq(ID, username)).firstOrNull() } }.getOrNull()
        return if (document == null) SailorProfile(username) else fromDocument(document)
    }

    private fun toDocument(profile: SailorProfile): Document = Document(ID, profile.username)
        .append("lastDock", profile.lastDock)
        .append("charted", profile.log.charted.toList())
        .append("chartedSeas", profile.chartedSeas.toList())
        .append("wrecksSalvaged", profile.wrecksSalvaged)
        .append("tasksCompleted", profile.tasksCompleted)
        .append("unlocks", profile.unlocks.toList())
        .append("trialRanks", Document(profile.trialRanks.toMap()))
        .append("trialBests", Document(profile.trialBests.toMap()))
        .append("tasks", profile.tasks.map {
            Document("id", it.id).append("kind", it.kind.name).append("from", it.from).append("to", it.to)
                .append("crates", it.crates).append("baseXp", it.baseXp).append("coins", it.coins).append("collected", it.collected)
                .append("monster", it.monster)
        })

    private fun fromDocument(document: Document): SailorProfile {
        val profile = SailorProfile(document.getString(ID))
        profile.lastDock = document.getString("lastDock") ?: ""
        profile.log.charted.addAll(document.getList("charted", String::class.java, emptyList()))
        profile.chartedSeas.addAll(document.getList("chartedSeas", String::class.java, emptyList()))
        profile.wrecksSalvaged = document.getInteger("wrecksSalvaged", 0)
        profile.tasksCompleted = document.getInteger("tasksCompleted", 0)
        profile.unlocks.addAll(document.getList("unlocks", String::class.java, emptyList()))
        document.get("trialRanks", Document::class.java)?.forEach { (course, rank) -> profile.trialRanks[course] = (rank as Number).toInt() }
        document.get("trialBests", Document::class.java)?.forEach { (course, ticks) -> profile.trialBests[course] = (ticks as Number).toInt() }
        document.getList("tasks", Document::class.java, emptyList()).forEach {
            profile.tasks.add(
                ActiveTask(
                    it.getLong("id"), TaskKind.valueOf(it.getString("kind")), it.getString("from"), it.getString("to"),
                    it.getInteger("crates"), it.getInteger("baseXp"), it.getInteger("coins"), it.getBoolean("collected", false),
                    it.getInteger("monster", ActiveTask.NO_MONSTER)
                )
            )
        }
        return profile
    }
}
