package com.rs.cache.loaders.map;

import com.rs.lib.game.WorldObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub Region. Returns placeholder values until wired to the modern cache/map system.
 */
public class Region {
    public static final int WIDTH = 64;
    public static final int HEIGHT = 64;

    protected int regionId;

    private List<WorldObject> objects = new ArrayList<>();
    private int[][][] clipFlags;
    private int[][][] heights;
    private int[][][] overlayIds;
    private int[][][] underlayIds;
    private byte[][][] overlayPathShapes;
    private byte[][][] overlayRotations;
    private byte[][][] renderFlags;
    private boolean hasData;
    private boolean xtea;

    public Region(int regionId) {
        this.regionId = regionId;
        this.clipFlags = new int[4][64][64];
    }

    public int getBaseX() {
        return (regionId >> 8 & 0xFF) << 6;
    }

    public int getBaseY() {
        return (regionId & 0xFF) << 6;
    }

    public int getRegionId() {
        return regionId;
    }

    public void loadRegionMap(boolean loadExtraData) {
        // TODO: Wire to modern cache/map loading
    }

    public void loadMapTiles(boolean loadExtraData) {
        // TODO: Wire to modern cache/map loading
    }

    public void loadObjects() {
        // TODO: Wire to modern cache/map loading
    }

    public void loadUnderwaterObjects() {
        // TODO: Wire to modern cache/map loading
    }

    public int getRotation(int plane, int localX, int localY) {
        return 0;
    }

    public final boolean isLinkedBelow(final int z, final int x, final int y) {
        return false;
    }

    public final boolean isVisibleBelow(final int z, final int x, final int y) {
        return false;
    }

    public int getRenderFlags(int z, int x, int y) {
        return renderFlags != null ? renderFlags[z][x][y] : 0;
    }

    public int getUnderlayId(int z, int x, int y) {
        return underlayIds != null ? underlayIds[z][x][y] & 0x7fff : -1;
    }

    public int getOverlayId(int z, int x, int y) {
        return overlayIds != null ? overlayIds[z][x][y] & 0x7fff : -1;
    }

    public int getOverlayPathShape(int z, int x, int y) {
        return overlayPathShapes != null ? overlayPathShapes[z][x][y] & 0x7fff : -1;
    }

    public int getOverlayRotation(int z, int x, int y) {
        return overlayRotations != null ? overlayRotations[z][x][y] : -1;
    }

    public boolean hasData() {
        return hasData;
    }

    public boolean hasXtea() {
        return xtea;
    }

    public boolean isMissingXtea() {
        return hasData && !xtea;
    }

    public int[][][] getClipFlags() {
        return clipFlags;
    }

    public List<WorldObject> getObjects() {
        return objects;
    }

    public boolean checkXtea(int[] xteas) {
        // TODO: Wire to modern cache
        return false;
    }
}
