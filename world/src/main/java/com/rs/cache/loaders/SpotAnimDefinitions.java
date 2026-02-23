package com.rs.cache.loaders;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub SpotAnimDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public class SpotAnimDefinitions {

    private static final ConcurrentHashMap<Integer, SpotAnimDefinitions> SPOT_ANIM_DEFS = new ConcurrentHashMap<>();

    public int id;
    public int defaultModel;
    public int animationId = -1;

    public SpotAnimDefinitions() {
    }

    public static final SpotAnimDefinitions getDefs(int spotId) {
        SpotAnimDefinitions defs = SPOT_ANIM_DEFS.get(spotId);
        if (defs != null) return defs;
        defs = new SpotAnimDefinitions();
        defs.id = spotId;
        // TODO: Load from modern cache
        SPOT_ANIM_DEFS.put(spotId, defs);
        return defs;
    }
}
