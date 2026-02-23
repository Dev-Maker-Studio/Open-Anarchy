package com.rs.cache.loaders.interfaces;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub IComponentDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public class IComponentDefinitions {

    private static IComponentDefinitions[][] COMPONENT_DEFINITIONS;
    private static final IFEvents DEFAULT_EVENTS = new IFEvents(0, -1);

    public String name;
    public ComponentType type = ComponentType.CONTAINER;
    public int contentType = 0;
    public int basePositionX = 0;
    public int basePositionY = 0;
    public int baseWidth = 0;
    public int baseHeight = 0;
    public byte aspectWidthType = 0;
    public byte aspectHeightType = 0;
    public byte aspectXType = 0;
    public byte aspectYType = 0;
    public int parent = -1;
    public boolean hidden = false;
    public int scrollWidth = 0;
    public int scrollHeight = 0;
    public int spriteId = -1;
    public int modelId;
    public int fontId = -1;
    public String text = "";
    public int color = 0;
    public int transparency = 0;
    public String[] optionNames;
    public String opName;
    public String useOnName = "";
    public String useOptionString = "";
    public IFEvents events = DEFAULT_EVENTS;
    public int uid = -1;
    public int interfaceId = -1;
    public int componentId = -1;
    public int parentBase = -1;
    public int parentComponent = -1;
    public int animation = -1;
    public int mouseOverCursor = -1;
    public int targetOverCursor = -1;
    public int targetLeaveCursor = -1;
    public boolean usesScripts;
    public Object[] onLoadScript;
    public Object[] popupScript;
    public int[] varps;
    public List<IComponentDefinitions> children = new ArrayList<>();

    public static IComponentDefinitions getInterfaceComponent(int id, int component) {
        IComponentDefinitions[] inter = getInterface(id);
        if (inter == null || component >= inter.length) return null;
        return inter[component];
    }

    public static IComponentDefinitions[] getInterface(int id) {
        // TODO: Wire to modern cache
        if (COMPONENT_DEFINITIONS == null) COMPONENT_DEFINITIONS = new IComponentDefinitions[0][];
        if (id >= COMPONENT_DEFINITIONS.length) return null;
        return COMPONENT_DEFINITIONS[id];
    }
}
