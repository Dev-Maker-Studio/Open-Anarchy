package com.rs.cache.loaders;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.rs.lib.net.ClientPacket;

/**
 * Stub ObjectDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public class ObjectDefinitions {

    private static final ConcurrentHashMap<Integer, ObjectDefinitions> objectDefinitions = new ConcurrentHashMap<>();

    public int id;
    public String name = "null";
    public int type = -1;
    public int sizeX = 1;
    public int sizeY = 1;
    public String[] options = new String[5];
    public String[] hideableOptions = new String[5];
    public String[] ingameVisibleOptions = new String[5];
    public int clipType = 2;
    public boolean blocks = true;
    public int interactable = -1;
    public int[] transformTo;
    public int varpBit = -1;
    public int varp = -1;
    public int mapSpriteRotation = 0;
    public int mapIcon = -1;
    public boolean obstructsGround = false;
    public boolean ignoresPathfinder = false;
    public int supportsItems = -1;
    public boolean members = false;
    public boolean hidden = false;
    public int accessBlockFlag = 0;
    public HashMap<Integer, Object> parameters;
    public ObjectType[] types;
    public int[][] modelIds;
    public int[] animations;
    public boolean hasAnimation = false;
    public short[] originalColors;
    public short[] modifiedColors;
    public short[] originalTextures;
    public short[] modifiedTextures;
    public int ambient = 0;
    public int contrast = 0;
    public int ambientSoundId = -1;
    public boolean inverted = false;
    public boolean castsShadow = true;
    public int scaleX = 128;
    public int scaleY = 128;
    public int scaleZ = 128;
    public int offsetX = 0;
    public int offsetY = 0;
    public int offsetZ = 0;
    public int decorDisplacement = 64;

    public ObjectDefinitions() {
    }

    public static ObjectDefinitions getDefs(int id) {
        return getDefs(id, true);
    }

    public static ObjectDefinitions getDefs(int id, boolean init) {
        ObjectDefinitions def = objectDefinitions.get(id);
        if (def == null) {
            def = new ObjectDefinitions();
            def.id = id;
            // TODO: Load from modern cache
            objectDefinitions.put(id, def);
        }
        return def;
    }

    public static ObjectDefinitions getDefs(int id, Object player) {
        return getDefs(id);
    }

    public static void clearObjectDefinitions() {
        objectDefinitions.clear();
    }

    public String getName() {
        return name;
    }

    public String getName(Object player) {
        return name;
    }

    public int getClipType() {
        return clipType;
    }

    public boolean blocks() {
        return blocks;
    }

    public boolean isClipped() {
        return clipType != 0;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getAccessBlockFlag() {
        return accessBlockFlag;
    }

    public int getIdForPlayer(Object vars) {
        if (transformTo == null || transformTo.length == 0) return id;
        return transformTo[transformTo.length - 1];
    }

    public ObjectDefinitions getRealDefs() {
        return getDefs(getIdForPlayer(null));
    }

    public String getFirstOption() {
        if (ingameVisibleOptions == null || ingameVisibleOptions.length < 1 || ingameVisibleOptions[0] == null)
            return "";
        return ingameVisibleOptions[0];
    }

    public String getSecondOption() {
        if (ingameVisibleOptions == null || ingameVisibleOptions.length < 2 || ingameVisibleOptions[1] == null)
            return "";
        return ingameVisibleOptions[1];
    }

    public String getThirdOption() {
        if (ingameVisibleOptions == null || ingameVisibleOptions.length < 3 || ingameVisibleOptions[2] == null)
            return "";
        return ingameVisibleOptions[2];
    }

    public String getOption(int option) {
        if (ingameVisibleOptions == null || ingameVisibleOptions.length < option || option == 0 || ingameVisibleOptions[option - 1] == null)
            return "";
        return ingameVisibleOptions[option - 1];
    }

    public String getOption(ClientPacket option) {
        int op = -1;
        switch(option) {
            case OBJECT_OP1 -> op = 0;
            case OBJECT_OP2 -> op = 1;
            case OBJECT_OP3 -> op = 2;
            case OBJECT_OP4 -> op = 3;
            case OBJECT_OP5 -> op = 4;
            default -> throw new IllegalArgumentException("Unexpected value: " + option);
        }
        if (ingameVisibleOptions == null || op < 0 || ingameVisibleOptions.length <= op || ingameVisibleOptions[op] == null)
            return "";
        return ingameVisibleOptions[op];
    }

    public boolean containsOption(int i, String option) {
        if (ingameVisibleOptions == null || ingameVisibleOptions.length <= i || ingameVisibleOptions[i] == null)
            return false;
        return ingameVisibleOptions[i].equals(option);
    }

    public boolean containsOption(String o) {
        if (ingameVisibleOptions == null) return false;
        for (String option : ingameVisibleOptions) {
            if (option != null && option.equalsIgnoreCase(o)) return true;
        }
        return false;
    }

    public boolean containsOptionIgnoreCase(String string) {
        if (ingameVisibleOptions == null) return false;
        for (String option : ingameVisibleOptions) {
            if (option != null && option.toLowerCase().contains(string.toLowerCase())) return true;
        }
        return false;
    }

    public boolean containsOption(int i) {
        if (ingameVisibleOptions == null || ingameVisibleOptions.length <= i || ingameVisibleOptions[i] == null)
            return false;
        return !ingameVisibleOptions[i].equals("null");
    }

    public ObjectType getType(int i) {
        if (types != null && i < types.length) return types[i];
        return ObjectType.SCENERY_INTERACT;
    }

    public int getSlot() {
        return getType(0).slot;
    }

    public int[] getModels(ObjectType type) {
        if (modelIds == null || types == null)
            return new int[0];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == type && i < modelIds.length)
                return modelIds[i];
        }
        if (modelIds.length > 0)
            return modelIds[0];
        return new int[0];
    }
}
