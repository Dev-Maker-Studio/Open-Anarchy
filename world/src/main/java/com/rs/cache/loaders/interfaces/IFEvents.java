package com.rs.cache.loaders.interfaces;

import java.util.Arrays;

/**
 * Interface event flags for configuring component interactivity.
 * This is a full copy of the legacy class - it contains no cache-dependent logic.
 */
public class IFEvents {

    public enum UseFlag {
        GROUND_ITEM(0x1),
        NPC(0x2),
        WORLD_OBJECT(0x4),
        PLAYER(0x8),
        SELF(0x10),
        ICOMPONENT(0x20),
        WORLD_TILE(0x40);

        private final int flag;

        UseFlag(int flag) {
            this.flag = flag;
        }

        public int getFlag() {
            return flag;
        }
    }

    private int interfaceId;
    private int componentId;
    private int fromSlot;
    private int toSlot;
    private int eventsHash;

    public IFEvents(int interfaceId, int componentId, int fromSlot, int toSlot, int eventsHash) {
        this.interfaceId = interfaceId;
        this.componentId = componentId;
        this.fromSlot = fromSlot;
        this.toSlot = toSlot;
        this.eventsHash = eventsHash;
    }

    public IFEvents(int settings, int interfaceId) {
        this.eventsHash = settings;
        this.interfaceId = interfaceId;
    }

    public IFEvents(int interfaceId, int componentId, int fromSlot, int toSlot) {
        this(interfaceId, componentId, fromSlot, toSlot, 0);
    }

    public boolean clickOptionEnabled(int i) {
        return 0 != (eventsHash >> 1 + i & 0x1);
    }

    public IFEvents enableRightClickOptions(int... ids) {
        Arrays.stream(ids).forEach(this::enableRightClickOption);
        return this;
    }

    public IFEvents enableRightClickOption(int id) {
        if (id < 0 || id > 9) return null;
        eventsHash &= ~(0x1 << (id + 1));
        eventsHash |= (0x1 << (id + 1));
        return this;
    }

    public final boolean useOptionEnabled(UseFlag flag) {
        return ((eventsHash >> 11 & 0x7F) & flag.getFlag()) != 0;
    }

    public IFEvents enableUseOptions(UseFlag... flags) {
        Arrays.stream(flags).forEach(this::enableUseOption);
        return this;
    }

    public final int getUseOptionFlags() {
        return IFEvents.getUseOptionFlags(this.eventsHash);
    }

    static final int getUseOptionFlags(int settings) {
        return settings >> 11 & 0x7f;
    }

    public IFEvents enableUseOption(UseFlag flag) {
        int useOptions = eventsHash >> 11 & 0x7F;
        useOptions |= flag.flag;
        eventsHash &= ~(useOptions << 11);
        eventsHash |= (useOptions << 11);
        return this;
    }

    public boolean dragEnabled() {
        return (eventsHash >> 21 & 0x1) != 0;
    }

    public IFEvents enableDrag() {
        eventsHash &= ~(1 << 21);
        eventsHash |= (1 << 21);
        return this;
    }

    public boolean continueOptionEnabled() {
        return (eventsHash & 0x1) != 0;
    }

    public IFEvents enableContinueButton() {
        eventsHash |= 0x1;
        return this;
    }

    public boolean ignoresDepthFlags() {
        return 0 != (eventsHash >> 23 & 0x1);
    }

    public IFEvents enableDepthFlagIgnoring() {
        eventsHash &= ~(1 << 23);
        eventsHash |= (1 << 23);
        return this;
    }

    public boolean isTargetableByUse() {
        return 0 != (eventsHash >> 22 & 0x1);
    }

    public IFEvents enableUseTargetability() {
        eventsHash &= ~(1 << 22);
        eventsHash |= (1 << 22);
        return this;
    }

    public int getDepth() {
        return eventsHash >> 18 & 0x7;
    }

    public IFEvents setDepth(int depth) {
        if (depth < 0 || depth > 7) return null;
        eventsHash &= ~(0x7 << 18);
        eventsHash |= (depth << 18);
        return this;
    }

    public int getInterfaceId() {
        return interfaceId;
    }

    public int getComponentId() {
        return componentId;
    }

    public int getFromSlot() {
        return fromSlot;
    }

    public int getToSlot() {
        return toSlot;
    }

    public int getSettings() {
        return eventsHash;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof IFEvents)
            return ((IFEvents) other).eventsHash == eventsHash;
        return false;
    }
}
