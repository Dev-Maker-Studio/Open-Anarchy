package com.rs.game;

import world.gregs.voidps.type.Tile;
import world.gregs.voidps.type.Tiles;

public final class TileHelper {

    private TileHelper() {}

    public static Tile of(int x, int y, int level) {
        return (Tile) Tiles.of(x, y, level);
    }

    public static Tile of(int x, int y) {
        return (Tile) Tiles.of(x, y);
    }

    public static Tile ofId(int id) {
        return (Tile) Tiles.ofId(id);
    }

    public static Tile of(Tile tile) {
        return (Tile) Tiles.copy(tile);
    }

    public static Tile of(Tile tile, int randomize) {
        return (Tile) Tiles.ofRandom(tile, randomize);
    }

    public static Tile transform(Tile tile, int dx, int dy, int dl) {
        return (Tile) Tiles.transform(tile, dx, dy, dl);
    }

    public static Tile transform(Tile tile, int dx, int dy) {
        return (Tile) Tiles.transform(tile, dx, dy);
    }

    public static int getXInScene(Tile tile, int baseChunkId) {
        return Tiles.getXInScene(tile, baseChunkId);
    }

    public static int getYInScene(Tile tile, int baseChunkId) {
        return Tiles.getYInScene(tile, baseChunkId);
    }

    public static int getChunkX(Tile tile) {
        return Tiles.getChunkX(tile);
    }

    public static int getChunkY(Tile tile) {
        return Tiles.getChunkY(tile);
    }

    public static boolean isAt(Tile tile, int x, int y) {
        return Tiles.isAt(tile, x, y);
    }
}
