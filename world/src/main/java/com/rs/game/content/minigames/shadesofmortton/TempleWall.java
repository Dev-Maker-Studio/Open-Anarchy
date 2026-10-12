package com.rs.game.content.minigames.shadesofmortton;

import com.rs.game.World;
import com.rs.game.model.gameobject.GameObject;
import org.darkan.core.Utils;

public class TempleWall extends GameObject {

	private final int baseId;
	private int buildProgress;

	public TempleWall(GameObject object) {
		super(object);
		baseId = getId();
		buildProgress = 5;
		ShadesOfMortton.addWall(this);
	}

	public void increaseProgress() {
		buildProgress += 4;
		update();
	}

	public void decreaseProgress() {
		buildProgress--;
		if (buildProgress <= 0) {
			destroy();
			return;
		}
		update();
	}

	public void destroy() {
		ShadesOfMortton.deleteWall(this);
	}

	public void update() {
		setId(baseId + Utils.clampI(buildProgress / 10, 0, 10));
		World.spawnObject(new GameObject(this));
	}

	public int getRepairPerc() {
		return Utils.clampI(buildProgress, 0, 100);
	}

	public void setRepairPerc(int perc) {
		this.buildProgress = perc;
	}

}
