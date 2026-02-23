package com.rs.cache.loaders;

import java.util.HashMap;

/**
 * Stub ItemDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public final class ItemDefinitions {

    private static final HashMap<Integer, ItemDefinitions> ITEM_DEFINITIONS = new HashMap<>();
    private static final HashMap<Integer, Integer> EQUIP_IDS = new HashMap<>();

    // Bonus index constants
    public static final int STAB_ATTACK = 0, SLASH_ATTACK = 1, CRUSH_ATTACK = 2, RANGE_ATTACK = 4, MAGIC_ATTACK = 3;
    public static final int STAB_DEF = 5, SLASH_DEF = 6, CRUSH_DEF = 7, RANGE_DEF = 9, MAGIC_DEF = 8, SUMMONING_DEF = 10;
    public static final int STRENGTH_BONUS = 14, RANGED_STR_BONUS = 15, MAGIC_DAMAGE = 17, PRAYER_BONUS = 16;
    public static final int ABSORVE_MELEE_BONUS = 11, ABSORVE_RANGE_BONUS = 13, ABSORVE_MAGE_BONUS = 12;

    public int id;
    public boolean loaded;
    public int value = 1;
    public int modelId;
    public int modelZoom = 2000;
    public int modelRotationX = 0;
    public int modelRotationY = 0;
    public int modelRotationZ = 0;
    public int modelOffsetX = 0;
    public int modelOffsetY = 0;
    public int[] originalModelColors;
    public int[] modifiedModelColors;
    public byte[] spriteRecolorIndices;
    public int[] originalTextureIds;
    public int[] modifiedTextureIds;
    public String name = "null";
    public boolean membersOnly = false;
    public int wearPos = -1;
    public int wearPos2 = -1;
    public int wearPos3 = -1;
    public int maleEquip1 = -1;
    public int maleEquip2 = -1;
    public int maleEquip3 = -1;
    public int femaleEquip1 = -1;
    public int femaleEquip2 = -1;
    public int femaleEquip3 = -1;
    public int maleWearXOffset = 0;
    public int femaleWearXOffset = 0;
    public int maleWearYOffset = 0;
    public int femaleWearYOffset = 0;
    public int maleWearZOffset = 0;
    public int femaleWearZOffset = 0;
    public int maleHead1 = -1;
    public int maleHead2 = -1;
    public int femaleHead1 = -1;
    public int femaleHead2 = -1;
    public int teamId = 0;
    public String[] groundOptions;
    public int stackable = 0;
    public String[] inventoryOptions;
    public int multiStackSize = -1;
    public int certId = -1;
    public int certTemplateId = -1;
    public int[] stackIds;
    public int[] stackAmounts;
    public int lendId = -1;
    public int lendTemplateId = -1;
    public int bindId = -1;
    public int bindTemplateId = -1;
    public int[] bonuses = new int[18];
    public int pickSizeShift = 0;
    public boolean noted;
    public boolean lended;
    public int[] quests;
    public int resizeX = 128;
    public int resizeY = 128;
    public int resizeZ = 128;

    private HashMap<Integer, Object> params;
    private HashMap<Integer, Integer> wieldRequirements;

    public ItemDefinitions() {
        setDefaultOptions();
    }

    public ItemDefinitions(int id) {
        this.id = id;
        setDefaultOptions();
        // TODO: Load from modern cache
    }

    public static final ItemDefinitions getDefs(int itemId) {
        return getItemDefinitions(itemId, true);
    }

    public static final ItemDefinitions getItemDefinitions(int itemId, boolean loadTemplates) {
        ItemDefinitions def = ITEM_DEFINITIONS.get(itemId);
        if (def == null) {
            def = new ItemDefinitions(itemId);
            ITEM_DEFINITIONS.put(itemId, def);
        }
        return def;
    }

    public static final void clearItemsDefinitions() {
        ITEM_DEFINITIONS.clear();
    }

    public static void mapEquipIds() {
        // TODO: Wire to modern cache
    }

    private void setDefaultOptions() {
        groundOptions = new String[] { null, null, "take", null, null };
        inventoryOptions = new String[] { null, null, null, null, "drop" };
    }

    public boolean isLoaded() {
        return loaded;
    }

    public String getName() {
        return name;
    }

    public int getId() {
        return id;
    }

    public int getValue() {
        return value;
    }

    public int getSellPrice() {
        return (int) (value / (10.0 / 3.0));
    }

    public int getHighAlchPrice() {
        return (int) (value / (10.0 / 6.0));
    }

    public boolean isStackable() {
        return stackable == 1;
    }

    public boolean isNoted() {
        return noted;
    }

    public boolean isLended() {
        return lended;
    }

    public boolean isMembersOnly() {
        return membersOnly;
    }

    public int getCertId() {
        return certId;
    }

    public int getLendId() {
        return lendId;
    }

    public int getEquipSlot() {
        return wearPos;
    }

    public boolean isEquipType(int type) {
        return wearPos3 == type || wearPos2 == type;
    }

    public boolean isWearItem() {
        return wearPos != -1;
    }

    public boolean isWearItem(boolean male) {
        if (id == 4285) return true;
        if (wearPos < 12 && (male ? getMaleWornModelId1() == -1 : getFemaleWornModelId1() == -1))
            return false;
        return wearPos != -1;
    }

    public int getMaleWornModelId1() {
        return maleEquip1;
    }

    public int getMaleWornModelId2() {
        return maleEquip2;
    }

    public int getFemaleWornModelId1() {
        return femaleEquip1;
    }

    public int getFemaleWornModelId2() {
        return femaleEquip2;
    }

    public int getModelZoom() {
        return modelZoom;
    }

    public int getModelOffset1() {
        return modelOffsetX;
    }

    public int getModelOffset2() {
        return modelOffsetY;
    }

    public boolean isOverSized() {
        return modelZoom > 5000;
    }

    public int getEquipId() {
        if (EQUIP_IDS.isEmpty()) mapEquipIds();
        Integer equipId = EQUIP_IDS.get(id);
        return equipId == null ? -1 : equipId;
    }

    public int getRenderAnimId() {
        if (params == null) return 1426;
        Object animId = params.get(644);
        if (animId instanceof Integer) return (Integer) animId;
        return 1426;
    }

    public boolean hasSpecialBar() {
        if (params == null) return false;
        Object specialBar = params.get(686);
        if (specialBar instanceof Integer) return (Integer) specialBar == 1;
        return false;
    }

    public boolean faceMask() {
        if (id == 4168) return true;
        return getParamVal(625) == 1;
    }

    public int getParamVal(int id) {
        return getParam(0, id);
    }

    public int getParam(int defaultVal, int id) {
        if (params == null || params.get(id) == null) return defaultVal;
        return (int) params.get(id);
    }

    public HashMap<Integer, Object> getClientScriptData() {
        return params;
    }

    public HashMap<Integer, Integer> getWearingSkillRequiriments() {
        if (params == null) return null;
        if (wieldRequirements == null) {
            wieldRequirements = new HashMap<>();
            // TODO: Parse params for skill requirements when cache is wired
        }
        return wieldRequirements;
    }

    public int getWieldQuestReq() {
        return getParam(-1, 743);
    }

    public int getQuestId() {
        return getParam(-1, 861);
    }

    public int getItemCategory() {
        return getParam(-1, 2195);
    }

    public int getIdk() {
        return getParam(-1, 1397);
    }

    public int getStageOnDeath() {
        return getParam(0, 1397);
    }

    public double getDungShopValueMultiplier() {
        if (params == null) return 1;
        Object value = params.get(1046);
        if (value instanceof Integer) return ((Integer) value).doubleValue() / 100;
        return 1;
    }

    public boolean isDestroyItem() {
        if (inventoryOptions == null) return false;
        for (String option : inventoryOptions) {
            if (option != null && option.equalsIgnoreCase("destroy")) return true;
        }
        return false;
    }

    public boolean canExchange() {
        return false; // TODO: Wire to cache
    }

    public boolean containsOption(int i, String option) {
        if (inventoryOptions == null || inventoryOptions.length <= i || inventoryOptions[i] == null)
            return false;
        return inventoryOptions[i].equals(option);
    }

    public boolean containsOption(String option) {
        if (inventoryOptions == null) return false;
        for (String o : inventoryOptions) {
            if (o != null && o.equals(option)) return true;
        }
        return false;
    }

    public boolean containsInventoryOption(int i, String string) {
        if (inventoryOptions == null || i >= inventoryOptions.length || inventoryOptions[i] == null)
            return false;
        return inventoryOptions[i].equalsIgnoreCase(string);
    }

    public boolean containsEquipmentOption(int optionId, String option) {
        if (params == null) return false;
        Object wearingOption = params.get(528 + optionId);
        if (wearingOption instanceof String) return wearingOption.equals(option);
        return false;
    }

    public String getEquipmentOption(int optionId) {
        if (params == null) return "null";
        Object wearingOption = params.get(optionId == 4 ? 1211 : (528 + optionId));
        if (wearingOption instanceof String) return (String) wearingOption;
        return "null";
    }

    public String getInventoryOption(int optionId) {
        if (inventoryOptions == null) return "null";
        if (optionId >= inventoryOptions.length) return "null";
        if (inventoryOptions[optionId] == null) return "null";
        return inventoryOptions[optionId];
    }

    public boolean isBinded() {
        return (id >= 15775 && id <= 16272) || (id >= 19865 && id <= 19866);
    }

    public boolean isBindItem() {
        if (inventoryOptions == null) return false;
        for (String option : inventoryOptions) {
            if (option != null && option.equalsIgnoreCase("bind")) return true;
        }
        return false;
    }

    public int[] getBonuses() {
        return bonuses;
    }

    public void setBonuses(int[] bonuses) {
        this.bonuses = bonuses;
    }
}
