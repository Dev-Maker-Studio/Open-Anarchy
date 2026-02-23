package com.rs.cache.loaders;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub EnumDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public final class EnumDefinitions {

    private static final ConcurrentHashMap<Integer, EnumDefinitions> ENUMS_CACHE = new ConcurrentHashMap<>();

    private String defaultStringValue = "null";
    private int defaultIntValue;
    private HashMap<Long, Object> values = new HashMap<>();

    private EnumDefinitions() {
    }

    public static final EnumDefinitions getEnum(int enumId) {
        EnumDefinitions script = ENUMS_CACHE.get(enumId);
        if (script != null) return script;
        script = new EnumDefinitions();
        // TODO: Load from modern cache
        ENUMS_CACHE.put(enumId, script);
        return script;
    }

    public int getDefaultIntValue() {
        return defaultIntValue;
    }

    public String getDefaultStringValue() {
        return defaultStringValue;
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
        if (values == null) return defaultIntValue;
        Object value = values.get(key);
        if (value == null || !(value instanceof Integer)) return defaultIntValue;
        return (Integer) value;
    }

    public int getKeyIndex(long key) {
        if (values == null) return -1;
        int i = 0;
        for (long k : values.keySet()) {
            if (k == key) return i;
            i++;
        }
        return -1;
    }

    public int getIntValueAtIndex(int i) {
        if (values == null) return -1;
        return (int) values.values().toArray()[i];
    }

    public String getStringValue(long key) {
        if (values == null) return defaultStringValue;
        Object value = values.get(key);
        if (value == null || !(value instanceof String)) return defaultStringValue;
        return (String) value;
    }
}
