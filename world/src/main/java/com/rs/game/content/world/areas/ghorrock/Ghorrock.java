// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.game.content.world.areas.ghorrock;

import com.rs.engine.quest.Quest;
import world.gregs.voidps.type.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.LoginHandler;
import com.rs.plugin.handlers.ObjectClickHandler;
import org.darkan.core.gameval.gen.Varbit;
import org.darkan.core.gameval.gen.Seq;

@PluginEventHandler
public class Ghorrock {
	//Ghorrock
	public static ObjectClickHandler iceblocks = new ObjectClickHandler(new Object[] { 47130 }, e -> {
		if (e.getPlayer().isQuestComplete(Quest.RITUAL_OF_MAHJARRAT)) {
			if (e.getPlayer().getX() < e.getObj().getX())
				e.getPlayer().tele(e.getPlayer().transform(e.getObj().getRotation() == 1 ? 4 : e.getObj().getRotation() == 3 ? 4 : 0, e.getObj().getRotation() == 1 ? 0 : e.getObj().getRotation() == 3 ? -0 : 0, 0));
			if (e.getPlayer().getX() > e.getObj().getX())
				e.getPlayer().tele(e.getPlayer().transform(e.getObj().getRotation() == 1 ? -4 : e.getObj().getRotation() == 3 ? -4 : 0, e.getObj().getRotation() == 1 ? 0 : e.getObj().getRotation() == 3 ? -0 : 0, 0));
		}else
			e.getPlayer().sendMessage("You do not meet the requirements for Ritual of Mahjarrat.");
	});

	public static ObjectClickHandler handleGhorrockstairs = new ObjectClickHandler(new Object[] { 47142, 47144 }, e -> {
    	if (e.getObjectId() == 47142) {
        	e.getPlayer().tele(e.getPlayer().transform(e.getObj().getRotation() == 3 ? 0 : e.getObj().getRotation() == 0 ? -1 : 0, e.getObj().getRotation() == 3 ? 0 : e.getObj().getRotation() == 0 ? -4 : 0, 1));
    } 
		else if (e.getObjectId() == 47144) {
        	if (e.objectAt(2920, 3932, 1)) {
            	e.getPlayer().tele(Tile.of(2920, 3935, 0));
            	return;
        	}

        	e.getPlayer().tele(e.getPlayer().transform( e.getObj().getRotation() == 3 ? 0 : e.getObj().getRotation() == 0 ? 1 : 0, e.getObj().getRotation() == 3 ? 0 : e.getObj().getRotation() == 0 ? 4 : 0,-1));
    	}
	});

	public static ObjectClickHandler handleGhorrockstairsuponly = new ObjectClickHandler(new Object[] { 47143 }, e -> e.getPlayer().tele(Tile.of(2921, 3931, 1)));
	public static LoginHandler makeghorrockcanoe = new LoginHandler(e -> e.getPlayer().getVars().setVarBit(Varbit.mah1_canoe_state, 1));
	public static ObjectClickHandler handlecanoetoghorrock = new ObjectClickHandler(new Object[] { 42886 }, e -> {
		if (e.getPlayer().isQuestComplete(Quest.TALE_OF_MUSPAH)) {
		e.getPlayer().tele(Tile.of(2856, 3933, 0));
		} else
			e.getPlayer().sendMessage("You do not meet the requirements for Ritual of Mahjarrat.");
	});

	public static ObjectClickHandler handlecanoeleaveghorrock = new ObjectClickHandler(new Object[] { 42858 }, e -> {
		if (e.getPlayer().isQuestComplete(Quest.TALE_OF_MUSPAH)) {
		e.getPlayer().tele(Tile.of(2692, 3793, 0));
		} else
			e.getPlayer().sendMessage("You do not meet the requirements for Ritual of Mahjarrat.");
	});

	public static ObjectClickHandler handlemahjarratritualsitecavernentrance = new ObjectClickHandler(new Object[] { 42859 }, e -> {
		if (e.getPlayer().isQuestComplete(Quest.TALE_OF_MUSPAH)) {
			e.getPlayer().tele(Tile.of(2843, 10364, 0));
		} else
			e.getPlayer().sendMessage("You do not meet the requirements for The Tale of the Muspah.");
	});
	public static ObjectClickHandler handlemahjarratritualsitecavernexit = new ObjectClickHandler(new Object[] { 42892 }, e -> {
		if (e.getPlayer().isQuestComplete(Quest.TALE_OF_MUSPAH)) {
			e.getPlayer().tele(Tile.of(2866, 3927, 0));
		} else
			e.getPlayer().sendMessage("You do not meet the requirements for The Tale of the Muspah.");
	});

	//Zemouregal's fort
	public static ObjectClickHandler handleZemouregalstairs = new ObjectClickHandler(new Object[] { 44253, 44255 }, e -> {
		if (e.getObjectId() == 44253)
			e.getPlayer().tele(e.getPlayer().transform(e.getObj().getRotation() == 3 ? -3 : e.getObj().getRotation() == 0 ? -0 : 0, e.getObj().getRotation() == 3 ? -0 : e.getObj().getRotation() == 0 ? 3 : 0, 1));
		else if (e.getObjectId() == 44255)
			e.getPlayer().tele(e.getPlayer().transform(e.getObj().getRotation() == 3 ? 3 : e.getObj().getRotation() == 0 ? -0 : 0, e.getObj().getRotation() == 3 ? 0 : e.getObj().getRotation() == 0 ? -3 : 0, -1));
	});
	public static ObjectClickHandler handleZemouregalrightstairsuponly = new ObjectClickHandler(new Object[] { 44254 }, e -> e.getPlayer().tele(Tile.of(2836, 3868, 1)));

	//Ghorrock
	public static ObjectClickHandler handleGhorrockDungeon = new ObjectClickHandler(
        new Object[] { 47160, 47157, 47159, 47162 },
        e -> {
            int x = e.getObj().getX();
            int y = e.getObj().getY();
            int plane = e.getObj().getPlane();

            // Courtyard trapdoor -> upper dungeon
            if (e.getObjectId() == 47160 && x == 2914 && y == 3942 && plane == 0) {
                e.getPlayer().tele(Tile.of(2909, 10417, 1));
                return;
            }

            // Upper dungeon stairs -> courtyard
            if (e.getObjectId() == 47157 && x == 2908 && y == 10418 && plane == 1) {
                e.getPlayer().tele(Tile.of(2914, 3941, 0));
                return;
            }

            // NW trapdoor -> bottom floor
            if (e.getObjectId() == 47159 && x == 2893 && y == 10417 && plane == 1) {
                e.getPlayer().tele(Tile.of(2893, 10354, 0));
                return;
            }

            // NW bottom ladder -> upper floor
            if (e.getObjectId() == 47162 && x == 2893 && y == 10353 && plane == 0) {
                e.getPlayer().tele(Tile.of(2894, 10419, 1));
                return;
            }

            // SE trapdoor -> bottom floor
            if (e.getObjectId() == 47159 && x == 2929 && y == 10381 && plane == 1) {
                e.getPlayer().tele(Tile.of(2929, 10318, 0));
                return;
            }

            // SE bottom ladder -> upper floor
            if (e.getObjectId() == 47162 && x == 2929 && y == 10317 && plane == 0) {
                e.getPlayer().tele(Tile.of(2929, 10380, 1));
            }
        }
	);

	//Handler for the doors ontop of Ghorrock since they aren't suppose to open.
	public static ObjectClickHandler handleGhorrockWallTrapdoors = new ObjectClickHandler(new Object[] { 47129 }, e ->
        	e.getPlayer().sendMessage("Many centuries of rust have fused the hinges in place. There's no hope of opening it.")
	);

	//ROTM Site
	public static ObjectClickHandler handleGhorrockPillar = new ObjectClickHandler(new Object[] { 61853 }, e -> {
    	if (e.getPlayer().getY() < e.getObj().getY())
        	e.getPlayer().forceMove(Tile.of(2913, 3918, 0), Seq.human_crossing_stile, 5, 60);
    	else
        	e.getPlayer().forceMove(Tile.of(2913, 3916, 0), Seq.human_crossing_stile, 5, 60);
	});

	public static ObjectClickHandler handleGhorrockWallClimbs = new ObjectClickHandler(new Object[] { 47137, 47139 }, e -> {
    	if (e.objectAt(2921, 3937, 0)) {
        	e.getPlayer().forceMove(Tile.of(2920, 3937, 0), 2049, 25, 60);
        	return;
    	}

    	if (e.objectAt(2909, 3942, 1)) {
        	e.getPlayer().forceMove(Tile.of(2908, 3942, 1), 2049, 25, 60);
        	return;
    	}

    	if (e.objectAt(2909, 3942, 0)) {
        	e.getPlayer().forceMove(Tile.of(2910, 3942, 0), 2050, 25, 60);
    	}
	});
}
