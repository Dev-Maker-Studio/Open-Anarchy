package com.rs.game.content.world.areas.mos_le_harmless;

import com.rs.game.content.world.areas.dungeons.UndergroundDungeonController;
import world.gregs.voidps.type.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ObjectClickHandler;

@PluginEventHandler
public class MosLeHarmless {
    public static ObjectClickHandler handleGangplanks = new ObjectClickHandler(new Object[] { 11211, 11212 }, e -> {
        boolean entering = e.getPlayer().getY() > e.getObj().getY();
        e.getPlayer().useStairs(-1, e.getPlayer().transform(0, entering ? -3 : 3, entering ? 1 : -1), 0, 1);
    });

    public static ObjectClickHandler handleUndergroundDungeonEntrance = new ObjectClickHandler(new Object[] { 15767 }, e -> {
            e.getPlayer().useStairs(-1, Tile.of(3748, 9373, 0), 0, 1);
            e.getPlayer().getControllerManager().startController(new UndergroundDungeonController(false, true));
    });

    public static ObjectClickHandler handleUndergroundDungeonStairs = new ObjectClickHandler(new Object[] { 15791 }, e -> {
            switch(e.getObj().getX()) {
                case 3829 -> e.getPlayer().useStairs(-1, Tile.of(3830, 9461, 0));
                case 3814 -> e.getPlayer().useStairs(-1, Tile.of(3815, 9461, 0));
            }
            e.getPlayer().getControllerManager().startController(new UndergroundDungeonController(false, true));
        }
    );

    public static ObjectClickHandler handleUndergroundDungeonStairsExit = new ObjectClickHandler(new Object[] { 15811, 15812 }, e -> {
        e.getPlayer().useStairs(-1, Tile.of(3749, 2973, 0), 0, 1);
    });


}
