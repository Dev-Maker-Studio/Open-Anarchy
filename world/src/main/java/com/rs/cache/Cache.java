package com.rs.cache;

/**
 * Stub cache access class. Will be wired to the modern cache system later.
 */
public final class Cache {

    public static Store STORE;

    private Cache() {
    }

    // TODO: Wire to modern cache system (world.gregs.voidps.cache)
    public static void init(String path) {
        STORE = new Store();
    }
}
