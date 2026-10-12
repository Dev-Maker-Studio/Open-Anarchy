package com.rs.game;

import org.darkan.core.model.GroundItem;
import org.darkan.core.model.GroundItemType;
import org.darkan.core.model.Item;
import world.gregs.voidps.type.Tile;

public final class GroundItemHelper {

    private GroundItemHelper() {}

    public static GroundItem of(Item item, Tile tile) {
        return new GroundItem(item, tile, null, GroundItemType.NORMAL, 25, -1, -1);
    }

    public static GroundItem of(Item item, Tile tile, String ownerUsername) {
        return new GroundItem(item, tile, ownerUsername, GroundItemType.NORMAL, 25, -1, -1);
    }

    public static GroundItem of(Item item, Tile tile, String ownerUsername, GroundItemType type) {
        return new GroundItem(item, tile, ownerUsername, type, 25, -1, -1);
    }
}
