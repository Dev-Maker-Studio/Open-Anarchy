package com.rs.cache.loaders;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub VarBitDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public final class VarBitDefinitions {

    private static final ConcurrentHashMap<Integer, VarBitDefinitions> varpbitDefs = new ConcurrentHashMap<>();

    public int id;
    public int baseVar;
    public int startBit;
    public int endBit;

    private VarBitDefinitions() {
    }

    public static final VarBitDefinitions getDefs(int id) {
        VarBitDefinitions script = varpbitDefs.get(id);
        if (script != null) return script;
        script = new VarBitDefinitions();
        script.id = id;
        // TODO: Load from modern cache
        varpbitDefs.put(id, script);
        return script;
    }
}
