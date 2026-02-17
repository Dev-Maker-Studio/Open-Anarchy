package org.darkan.core.mongo.collections

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Sorts.ascending
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.darkan.core.model.Clan
import org.darkan.core.mongo.MongoDB.database

object Clans {
    private val collection by lazy {
        val col = database.getCollection<Clan>("clans")
        runBlocking {
            col.createIndex(ascending(Clan::name.name), IndexOptions().unique(true))
            col.createIndex(ascending(Clan::leaderUsername.name))
        }
        return@lazy col
    }

    suspend fun find(name: String): Clan? =
        collection.find(eq(Clan::name.name, name)).firstOrNull()

    suspend fun findByLeader(username: String): Clan? =
        collection.find(eq(Clan::leaderUsername.name, username)).firstOrNull()

    suspend fun save(clan: Clan) {
        collection.replaceOne(eq(Clan::name.name, clan.name), clan, com.mongodb.client.model.ReplaceOptions().upsert(true))
    }

    suspend fun delete(name: String) {
        collection.deleteOne(eq(Clan::name.name, name))
    }
}
