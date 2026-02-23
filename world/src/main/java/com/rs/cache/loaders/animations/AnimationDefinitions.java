package com.rs.cache.loaders.animations;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub AnimationDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public class AnimationDefinitions {

    private static final ConcurrentHashMap<Integer, AnimationDefinitions> animDefs = new ConcurrentHashMap<>();

    public int id;
    public int replayMode;
    public int[] frameDurations;
    public int[] frameHashes;
    public int[] frameSetIds;
    public int loopDelay = -1;
    public int priority = -1;
    public int leftHandItem = 65535;
    public int rightHandItem = 65535;
    public int animatingPrecedence;
    public int walkingPrecedence;
    public boolean tweened;
    public int maxLoops = 1;
    public HashMap<Integer, Object> clientScriptMap = new HashMap<>();

    public static final AnimationDefinitions getDefs(int emoteId) {
        AnimationDefinitions defs = animDefs.get(emoteId);
        if (defs != null) return defs;
        defs = new AnimationDefinitions();
        defs.id = emoteId;
        // TODO: Load from modern cache
        animDefs.put(emoteId, defs);
        return defs;
    }

    public static void init() {
        // TODO: Wire to modern cache
    }

    public static int getAnimationWithItem(int itemId) {
        return -1;
    }

    public int getEmoteTime() {
        if (frameDurations == null) return 0;
        int ms = 0;
        for (int i : frameDurations)
            ms += i * 20;
        return ms;
    }

    public int getEmoteGameTicks() {
        return getEmoteTime() / 600;
    }
}
