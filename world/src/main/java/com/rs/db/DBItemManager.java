package com.rs.db;

import com.mongodb.client.MongoCollection;
import com.rs.engine.thread.CatchExceptionRunnable;
import org.bson.Document;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    protected void dropIndexIfPresent(String name) {
        for (Document index : getDocs().listIndexes())
            if (name.equals(index.getString("name"))) {
                getDocs().dropIndex(name);
                return;
            }
    }

    public void execute(Runnable task) {
        executor.execute(new CatchExceptionRunnable(task));
    }
}
