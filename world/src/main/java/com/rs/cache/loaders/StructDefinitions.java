package com.rs.cache.loaders;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub StructDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public final class StructDefinitions {

    private static final ConcurrentHashMap<Integer, StructDefinitions> maps = new ConcurrentHashMap<>();

    private HashMap<Long, Object> values;

    private StructDefinitions() {
    }

    public static final StructDefinitions getStruct(int structId) {
        StructDefinitions script = maps.get(structId);
        if (script != null) return script;
        script = new StructDefinitions();
        // TODO: Load from modern cache
        maps.put(structId, script);
        return script;
    }

    public HashMap<Long, Object> getValues() {
        return values;
    }

    public Object getValue(long key) {
        if (values == null) return null;
        return values.get(key);
    }

    public long getKeyForValue(Object value) {
        if (values == null) return -1;
        for (Long key : values.keySet()) {
            if (values.get(key).equals(value)) return key;
        }
        return -1;
    }

    public int getSize() {
        if (values == null) return 0;
        return values.size();
    }

    public int getIntValue(long key) {
        if (values == null) return 0;
        Object value = values.get(key);
        if (value == null || !(value instanceof Integer)) return 0;
        return (Integer) value;
    }

    public int getIntValue(long key, int defaultVal) {
        if (values == null) return defaultVal;
        Object value = values.get(key);
        if (value == null || !(value instanceof Integer)) return defaultVal;
        return (Integer) value;
    }

    public String getStringValue(long key) {
        if (values == null) return "";
        Object value = values.get(key);
        if (value == null || !(value instanceof String)) return "";
        return (String) value;
    }
}
