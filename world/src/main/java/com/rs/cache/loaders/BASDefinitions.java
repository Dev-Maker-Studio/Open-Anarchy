package com.rs.cache.loaders;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub BASDefinitions (Body Animation Set). Returns placeholder values until wired to the modern cache system.
 */
public class BASDefinitions {

    private static final ConcurrentHashMap<Integer, BASDefinitions> RENDER_ANIM_CACHE = new ConcurrentHashMap<>();

    public int standAnimation = -1;
    public int walkAnimation = -1;
    public int runningAnimation = -1;
    public int teleportingAnimation = -1;
    public int standTurn1 = -1;
    public int standTurn2 = -1;
    public int walkDir1 = -1;
    public int walkDir2 = -1;
    public int walkDir3 = -1;
    public int walkTurn1 = -1;
    public int walkTurn2 = -1;
    public int runDir1 = -1;
    public int runDir2 = -1;
    public int runDir3 = -1;
    public int runTurn1 = -1;
    public int runTurn2 = -1;
    public int teleDir1 = -1;
    public int teleDir2 = -1;
    public int teleDir3 = -1;
    public int teleTurn1 = -1;
    public int teleTurn2 = -1;
    public int numStandAnimations = 0;
    public int[] standAnimations;

    public BASDefinitions() {
    }

    public static final BASDefinitions getDefs(int emoteId) {
        if (emoteId == -1) return null;
        BASDefinitions defs = RENDER_ANIM_CACHE.get(emoteId);
        if (defs != null) return defs;
        defs = new BASDefinitions();
        // TODO: Load from modern cache
        RENDER_ANIM_CACHE.put(emoteId, defs);
        return defs;
    }
}
