package com.rs.game.content.minigames.mage_training_arena;

import world.gregs.voidps.type.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ObjectClickHandler;

@PluginEventHandler
public class MageTrainingArena {
	public static ObjectClickHandler handlemagetrainladderup = new ObjectClickHandler(new Object[] { 10775 }, e -> e.getPlayer().tele(Tile.of(3357, 3307, 1)));

	public static ObjectClickHandler handlemagetrainladderdown = new ObjectClickHandler(new Object[] { 10776 }, e -> e.getPlayer().tele(Tile.of(3360, 3306, 0)));

	public static ObjectClickHandler handlemagetrainladderup2 = new ObjectClickHandler(new Object[] { 10771 }, e -> e.getPlayer().tele(Tile.of(3369, 3307, 1)));

	public static ObjectClickHandler handlemagetrainladderdown2 = new ObjectClickHandler(new Object[] { 10773 }, e -> e.getPlayer().tele(Tile.of(3367, 3306, 0)));

	public static ObjectClickHandler handlerunemechanicup = new ObjectClickHandler(new Object[] { 528 }, e -> e.getPlayer().tele(Tile.of(3358, 3305, 0)));

	public static ObjectClickHandler handlerunemechanicdown = new ObjectClickHandler(new Object[] { 527 }, e -> e.getPlayer().tele(Tile.of(3619, 4814, 0)));



}
