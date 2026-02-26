package com.rs.game.map;

import org.darkan.core.model.WorldObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy Region stub for ChunkManager compatibility.
 * Wraps the modern RegionLoader/RegionData system.
 */
public class Region {
    private final int regionId;
    private final int regionX;
    private final int regionY;
    private boolean loaded;
    private int[][][] clipFlags;
    private List<WorldObject> objects;

    public Region(int regionId) {
        this.regionId = regionId;
        this.regionX = regionId >> 8;
        this.regionY = regionId & 0xff;
    }

    public void loadRegionMap(boolean force) {
        // Stub: will be wired to modern RegionLoader
        if (loaded && !force) return;
        loaded = true;
        clipFlags = new int[4][64][64];
        objects = new ArrayList<>();
    }

    public boolean hasData() {
        return loaded;
    }

    public int getBaseX() {
        return regionX << 6;
    }

    public int getBaseY() {
        return regionY << 6;
    }

    public int getRegionId() {
        return regionId;
    }

    public int[][][] getClipFlags() {
        return clipFlags;
    }

    public List<WorldObject> getObjects() {
        return objects;
    }
}
