package com.rs.cache.loaders;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub InventoryDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public final class InventoryDefinitions {

    private static final ConcurrentHashMap<Integer, InventoryDefinitions> maps = new ConcurrentHashMap<>();

    public int id;
    public int maxSize = 0;
    public int contentSize = 0;
    public int[] ids;
    public int[] amounts;

    public InventoryDefinitions(int id) {
        this.id = id;
        // TODO: Load from modern cache
    }

    public static final InventoryDefinitions getContainer(int id) {
        InventoryDefinitions def = maps.get(id);
        if (def != null) return def;
        def = new InventoryDefinitions(id);
        maps.put(id, def);
        return def;
    }

    public static final InventoryDefinitions getDefs(int id) {
        return getContainer(id);
    }
}
