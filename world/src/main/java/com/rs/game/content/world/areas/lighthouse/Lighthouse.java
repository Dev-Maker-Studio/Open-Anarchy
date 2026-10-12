package com.rs.game.content.world.areas.lighthouse;

import com.rs.game.content.world.doors.Doors;
import world.gregs.voidps.type.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ObjectClickHandler;

@PluginEventHandler
public class Lighthouse {
	public static ObjectClickHandler handleEntranceLadders = new ObjectClickHandler(new Object[] { 4383, 4412 }, e -> e.getPlayer().useLadder(e.getObjectId() == 4412 ? Tile.of(2510, 3644, 0) : Tile.of(2519, 9995, 1)));
	public static ObjectClickHandler handleDoors = new ObjectClickHandler(new Object[] { 4545, 4546 }, e -> Doors.handleDoor(e.getPlayer(), e.getObj()));
	public static ObjectClickHandler handleLadders = new ObjectClickHandler(new Object[] { 4413, 4485 }, e -> e.getPlayer().useLadder(e.getPlayer().transform(0, e.getObjectId() == 4485 ? 3 : -3, e.getObjectId() == 4485 ? -1 : 1)));
	public static ObjectClickHandler handleBridge = new ObjectClickHandler(new Object[] { 73681 }, e -> e.getPlayer().useStairs(-1, e.getPlayer().transform(e.getPlayer().getX() < e.getObj().getX() ? 3 : -3, 0, 0), 1, 1));
}
