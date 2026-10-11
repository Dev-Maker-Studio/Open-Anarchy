package com.rs.game.content.skills.sailing

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.darkan.core.mongo.MongoDB
import java.util.concurrent.ConcurrentHashMap

private const val COLLECTION = "boats"
private const val ID = "_id"
private const val OWNER = "owner"
private const val DOCK_PORT = "dockPort"
private const val STATE = "state"
private const val CREW = "crew"

object BoatStore {
    private val boats by lazy { MongoDB.database.getCollection<Document>(COLLECTION) }
    private val cache = ConcurrentHashMap<String, MutableList<Boat>>()

    fun save(boat: Boat) {
        val owned = cache.computeIfAbsent(boat.owner) { load(boat.owner) }
        if (owned.none { it === boat }) {
            owned.removeIf { it.id == boat.id }
            owned.add(boat)
        }
        runCatching { runBlocking { boats.replaceOne(Filters.eq(ID, boat.id), toDocument(boat), ReplaceOptions().upsert(true)) } }
    }

    fun delete(boat: Boat) {
        cache[boat.owner]?.removeIf { it.id == boat.id }
        runCatching { runBlocking { boats.deleteOne(Filters.eq(ID, boat.id)) } }
    }

    fun ownedBy(owner: String): List<Boat> = cache.computeIfAbsent(owner) { load(owner) }

    fun dockedAt(port: String): List<Boat> {
        val documents = runCatching {
            runBlocking { boats.find(Filters.and(Filters.eq(DOCK_PORT, port), Filters.eq(STATE, BoatState.DOCKED.name))).toList() }
        }.getOrDefault(emptyList())
        return documents.mapNotNull { document ->
            val owner = document.getString(OWNER)
            if (cache.containsKey(owner)) cache[owner]?.firstOrNull { it.id == document.getLong(ID) } else fromDocument(document)
        }.filter { it.dockPort == port && it.state == BoatState.DOCKED }
    }

    fun prune(online: Set<String>) {
        cache.keys.removeIf { it !in online }
    }

    fun forget(owner: String) {
        cache.remove(owner)
    }

    private fun load(owner: String): MutableList<Boat> =
        runCatching { runBlocking { boats.find(Filters.eq(OWNER, owner)).toList().map(::fromDocument).toMutableList() } }.getOrDefault(mutableListOf())

    fun toDocument(boat: Boat): Document = Document(ID, boat.id)
        .append(OWNER, boat.owner)
        .append("type", boat.type.name)
        .append("name", boat.name)
        .append("hull", boat.hull.name)
        .append("helm", boat.helm.name)
        .append("sail", boat.sail.name)
        .append("keel", boat.keel?.name)
        .append("hitpoints", boat.hitpoints)
        .append(DOCK_PORT, boat.dockPort)
        .append("brazier", boat.hasBrazier)
        .append("inoculation", boat.hasInoculationStation)
        .append(STATE, boat.state.name)
        .append("globalX", boat.globalX)
        .append("globalY", boat.globalY)
        .append("heading", boat.heading)
        .append("cannon", boat.cannon?.name)
        .append("hook", boat.hook?.name)
        .append("facilities", boat.facilities.map { it.name })
        .append("nets", boat.nets.map { it.name })
        .append("keg", boat.keg?.name)
        .append("kegDoses", boat.kegDoses)
        .append("cannonballs", boat.hold.cannonballs)
        .append("sortingXp", boat.hold.sortingXp)
        .append("goods", boat.hold.goods.map { Document("item", it.key).append("amount", it.value) })
        .append("crates", boat.hold.crates.map { Document("task", it.key).append("amount", it.value) })
        .append(CREW, boat.crew.map { crewDocument(it) })

    fun fromDocument(document: Document): Boat {
        val boat = Boat(
            id = document.getLong(ID),
            owner = document.getString(OWNER),
            type = BoatType.valueOf(document.getString("type")),
            name = document.getString("name"),
            hull = HullTier.valueOf(document.getString("hull")),
            helm = HelmTier.valueOf(document.getString("helm")),
            sail = SailTier.valueOf(document.getString("sail")),
            keel = document.getString("keel")?.let { KeelTier.valueOf(it) },
            hitpoints = document.getInteger("hitpoints"),
            dockPort = document.getString(DOCK_PORT) ?: "",
            hasBrazier = document.getBoolean("brazier", false),
            hasInoculationStation = document.getBoolean("inoculation", false),
            state = document.getString(STATE)?.let { BoatState.valueOf(it) } ?: BoatState.DOCKED,
            globalX = document.getInteger("globalX", 0),
            globalY = document.getInteger("globalY", 0),
            heading = document.getInteger("heading", 0),
            cannon = document.getString("cannon")?.let { CannonTier.valueOf(it) },
            hook = document.getString("hook")?.let { HookTier.valueOf(it) },
            facilities = document.getList("facilities", String::class.java, emptyList()).map { Facility.valueOf(it) }.toMutableSet(),
            crew = document.getList(CREW, Document::class.java, emptyList()).map(::crewFrom).toMutableList(),
            nets = document.getList("nets", String::class.java, emptyList()).map { NetTier.valueOf(it) }.toMutableList(),
            keg = document.getString("keg")?.let { KegKind.valueOf(it) },
            kegDoses = document.getInteger("kegDoses", 0)
        )
        boat.hold.cannonballs = document.getInteger("cannonballs", 0)
        boat.hold.sortingXp = document.getDouble("sortingXp") ?: 0.0
        document.getList("goods", Document::class.java, emptyList()).forEach { boat.hold.goods[it.getInteger("item")] = it.getInteger("amount") }
        document.getList("crates", Document::class.java, emptyList()).forEach { boat.hold.crates[it.getLong("task")] = it.getInteger("amount") }
        return boat
    }

    private fun crewDocument(crewmate: Crewmate): Document = Document("name", crewmate.name)
        .append("helmsmanship", crewmate.helmsmanship)
        .append("deckhandiness", crewmate.deckhandiness)
        .append("privateering", crewmate.privateering)
        .append("wage", crewmate.wage)
        .append("sailingLevel", crewmate.sailingLevel)
        .append("job", crewmate.job.name)

    private fun crewFrom(document: Document): Crewmate = Crewmate(
        document.getString("name"),
        document.getInteger("helmsmanship"),
        document.getInteger("deckhandiness"),
        document.getInteger("privateering"),
        document.getInteger("wage"),
        document.getInteger("sailingLevel"),
        CrewJob.valueOf(document.getString("job"))
    )
}
