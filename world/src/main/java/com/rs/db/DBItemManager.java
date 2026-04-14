package com.rs.db;

import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stub replacement for the deleted library DBItemManager class.
 * TODO: Migrate to core MongoDB module (org.darkan.core.mongo).
 */
public abstract class DBItemManager {
    private final String collectionName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public DBItemManager(String collectionName) {
        this.collectionName = collectionName;
    }

    public void init() {
        initCollection();
    }

    public abstract void initCollection();

    protected String getCollectionName() {
        return collectionName;
    }

    public MongoCollection<Document> getDocs() {
        return DBConnection.getCollection(collectionName);
    }

    public void execute(Runnable task) {
        executor.submit(task);
    }
}
