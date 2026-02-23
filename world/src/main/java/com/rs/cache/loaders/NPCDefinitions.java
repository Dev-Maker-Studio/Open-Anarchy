package com.rs.cache.loaders;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub NPCDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public final class NPCDefinitions {

    private static final ConcurrentHashMap<Integer, NPCDefinitions> MAP = new ConcurrentHashMap<>();

    public int id;
    public HashMap<Integer, Object> parameters;
    public String[] options = new String[5];
    public String[] membersOptions = new String[5];
    public int[] modelIds = new int[0];
    public String name = "null";
    public int size = 1;
    public int basId = -1;
    public int combatLevel = -1;
    public int headIcons = -1;
    public int varpBit = -1;
    public int varp = -1;
    public int[] transformTo;
    public boolean visible = true;
    public boolean isClickable = true;
    public boolean drawMapdot = true;
    public int respawnDirection = 4;
    public int walkingAnimation = -1;
    public int rotate180Animation = -1;
    public int rotate90RightAnimation = -1;
    public int rotate90LeftAnimation = -1;
    public int height = -1;
    public MovementType movementType;
    public int[] quests;
    public int[] headModels;
    public boolean aBool4872;
    public int[] compatibleAnimations = new int[0];
    public short[] originalColors;
    public short[] modifiedColors;
    public short[] originalTextures;
    public short[] modifiedTextures;
    public byte walkMask = 0;
    public int specialByte = 0;

    private int[] bonuses = new int[10];
    private int[] strBonuses = new int[3];

    public NPCDefinitions(int id) {
        this.id = id;
    }

    public static final NPCDefinitions getDefs(int id) {
        NPCDefinitions def = MAP.get(id);
        if (def == null) {
            def = new NPCDefinitions(id);
            def.bonuses = new int[10];
            def.strBonuses = new int[3];
            // TODO: Load from modern cache
            MAP.put(id, def);
        }
        return def;
    }

    public static NPCDefinitions getDefs(int id, Object vars) {
        return getDefs(id);
    }

    public static final void clearNPCDefinitions() {
        MAP.clear();
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getName(Object vars) {
        return name;
    }

    public int getCombatLevel() {
        return combatLevel;
    }

    public int getSize() {
        return size;
    }

    public String getOption(int op) {
        if (options == null && membersOptions == null) return "null";
        if (op >= options.length) return "null";
        if (options[op] != null) return options[op];
        if (membersOptions[op] != null) return membersOptions[op];
        return "null";
    }

    public boolean hasOption(String op) {
        for (String option : options) {
            if (option != null && option.equalsIgnoreCase(op)) return true;
        }
        for (String option : membersOptions) {
            if (option != null && option.equalsIgnoreCase(op)) return true;
        }
        return false;
    }

    public boolean hasAttackOption() {
        for (String option : options) {
            if (option != null && (option.equalsIgnoreCase("attack") || option.equalsIgnoreCase("destroy")))
                return true;
        }
        for (String option : membersOptions) {
            if (option != null && (option.equalsIgnoreCase("attack") || option.equalsIgnoreCase("destroy")))
                return true;
        }
        return false;
    }

    public boolean transformsTo(int id) {
        if (transformTo == null) return false;
        for (int to : transformTo) {
            if (to == id) return true;
        }
        return false;
    }

    public int getIdForPlayer(Object vars) {
        if (transformTo == null || transformTo.length == 0) return id;
        return transformTo[transformTo.length - 1];
    }

    public int getBonus(int id) {
        if (parameters == null) return 0;
        Object val = parameters.get(id);
        if (val instanceof Integer) return (Integer) val;
        return 0;
    }

    public int getBonus(Bonus bonus) {
        return getBonus(bonus.ordinal());
    }

    public int getStabAtt() { return getBonus(0); }
    public int getSlashAtt() { return getBonus(1); }
    public int getCrushAtt() { return getBonus(2); }
    public int getMagicAtt() { return getBonus(3); }
    public int getRangeAtt() { return getBonus(4); }
    public int getStabDef() { return getBonus(5); }
    public int getSlashDef() { return getBonus(6); }
    public int getCrushDef() { return getBonus(7); }
    public int getMagicDef() { return getBonus(8); }
    public int getRangeDef() { return getBonus(9); }
    public int getMeleeStr() { return getBonus(641) / 10; }
    public int getRangeStr() { return getBonus(643) / 10; }
    public int getMagicStr() { return getBonus(965) / 10; }

    public int getAttackDelay() {
        int speed = getBonus(14);
        return speed <= 0 ? 4 : speed;
    }

    public boolean isUndead() {
        return false; // TODO: Wire to cache
    }

    public boolean isDungNPC() {
        return (id >= 9724 && id <= 11229) || (id >= 11708 && id <= 12187) || (id >= 12436 && id <= 13090) || hasOption("mark");
    }

    public boolean crawlWalkRender() {
        return false;
    }

    public int[] getBonuses() {
        return bonuses.clone();
    }

    public int[] getStrBonuses() {
        return strBonuses.clone();
    }

    public int getCSValue(int key) {
        if (parameters == null) return -1;
        Object val = parameters.get(key);
        if (val instanceof Integer) return (Integer) val;
        return -1;
    }

    public int getBoBSlots() { return getCSValue(379); }
    public int getSummoningReq() { return getCSValue(394); }
    public boolean isBeastOfBurden() { return getCSValue(1323) == 1; }

    public int[] getCompatibleAnimations() {
        return compatibleAnimations;
    }

    public String getConfigInfoString() {
        StringBuilder sb = new StringBuilder();
        sb.append("VarpBit: ").append(varpBit);
        sb.append(" Varp: ").append(varp);
        if (transformTo != null) {
            sb.append(" TransformTo: [");
            for (int i = 0; i < transformTo.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(transformTo[i]);
            }
            sb.append("]");
        }
        return sb.toString();
    }

    public enum MovementType {
        STATIONARY(-1),
        HALF_WALK(0),
        WALKING(1),
        RUNNING(2);

        public final int id;

        MovementType(int id) {
            this.id = id;
        }

        public static MovementType forId(int id) {
            for (MovementType type : MovementType.values()) {
                if (type.id == id) return type;
            }
            return null;
        }
    }
}
