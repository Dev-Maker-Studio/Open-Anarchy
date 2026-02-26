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
package com.rs.game.model;

import com.rs.game.model.entity.Entity;
import com.rs.game.model.entity.player.Player;
import com.rs.game.model.gameobject.GameObject;
import com.rs.game.tasks.WorldTasks;
import org.darkan.core.model.Projectile;
import world.gregs.voidps.type.Tile;
import org.darkan.core.Utils;

import java.util.function.Consumer;

public class WorldProjectile extends Projectile {

	public WorldProjectile(Object from, Object to, int spotAnimId, int startHeight, int endHeight, int startDelayClientCycles, int inAirClientCycles, int offset, int angle, Consumer<WorldProjectile> task) {
		super(tileOf(from), computeSourceId(from), tileOf(to), computeLockOnId(to), spotAnimId, startHeight, endHeight, startDelayClientCycles, inAirClientCycles, offset, angle);
		if (task != null)
			WorldTasks.schedule(getTaskDelay(), () -> task.accept(WorldProjectile.this));
	}

	private static Tile tileOf(Object obj) {
		return switch (obj) {
			case Tile t -> t;
			case Entity e -> e.getMiddleTile();
			case GameObject g -> g.getTile();
			default -> throw new IllegalArgumentException("Unexpected value: " + obj);
		};
	}

	private static int computeSourceId(Object from) {
		if (from instanceof Entity e)
			return e instanceof Player ? -(e.getIndex() + 1) : e.getIndex() + 1;
		return 0;
	}

	private static int computeLockOnId(Object to) {
		if (to instanceof Entity e)
			return e instanceof Player ? -(e.getIndex() + 1) : e.getIndex() + 1;
		return 0;
	}

	public int getTaskDelay() {
		return Utils.clampI(Utils.projInAirToTicks(getStartDelayClientCycles(), getInAirClientCycles()), 0, Integer.MAX_VALUE);
	}
}
