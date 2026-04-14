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
package com.rs.game.model.gameobject;

import org.darkan.core.cache.ObjectDefinitions;
import org.darkan.core.model.ObjectShape;
import world.gregs.voidps.cache.definition.data.ObjectDefinition;
import com.rs.game.World;
import com.rs.game.map.Chunk;
import com.rs.game.map.ChunkManager;
import com.rs.game.model.entity.player.Player;
import com.rs.game.tasks.TaskManager;
import org.darkan.core.model.Animation;
import world.gregs.voidps.type.Tile;
import org.darkan.core.model.WorldObject;
import org.darkan.core.model.AttribMap;

public class GameObject extends WorldObject {
	protected transient AttribMap attribs;

	public enum RouteType {
		NORMAL, WALK_ONTO
	}

	protected RouteType routeType = RouteType.NORMAL;
	private transient ObjectMeshModifier meshModifier;
	private transient TaskManager tasks;

	private transient int originalId;
	private transient int idChangeTicks = -1;
	private final int hashCode;

	public GameObject(int id, ObjectShape type, int rotation, Tile tile) {
		super(id, type, rotation, tile);
		this.originalId = id;
		this.routeType = World.getRouteType(id);
		this.hashCode = genHashCode();
	}

	public GameObject(int id, int rotation, int x, int y, int plane) {
		super(id, rotation, x, y, plane);
		this.originalId = id;
		this.routeType = World.getRouteType(id);
		this.hashCode = genHashCode();
	}

	public GameObject(int id, ObjectShape type, int rotation, int x, int y, int plane) {
		super(id, type, rotation, x, y, plane);
		this.originalId = id;
		this.routeType = World.getRouteType(id);
		this.hashCode = genHashCode();
	}

	public GameObject(WorldObject object) {
		super(object);
		this.originalId = getId();
		this.routeType = World.getRouteType(getId());
		this.hashCode = genHashCode();
	}

	public GameObject(GameObject object) {
		super(object);
		this.originalId = getId();
		routeType = object.getRouteType();
		this.hashCode = genHashCode();
	}

	public GameObject(WorldObject object, int newId) {
		super(object);
		setId(newId);
		this.originalId = newId;
		this.routeType = World.getRouteType(newId);
		this.hashCode = genHashCode();
	}

	public GameObject(GameObject object, int newId) {
		super(object);
		setId(newId);
		this.originalId = newId;
		this.routeType = object.getRouteType();
		this.hashCode = genHashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof GameObject obj))
			return false;
		return obj.hashCode() == hashCode();
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	public int genHashCode() {
		int hash = getTile().getTileHash();
		hash = ((hash << 5) - hash) + getId();
		hash = ((hash << 5) - hash) + getRotation();
		hash = ((hash << 5) - hash) + getType().getId();
		return hash;
	}

	public int positionHashCode() {
		int hash = getTile().getTileHash();
		hash = ((hash << 5) - hash) + getType().getId();
		return hash;
	}

	@Override
	public String toString() {
		return "[id:"+getId()+" loc:("+getX()+","+getY()+","+getPlane()+") type:"+getType()+" rot:"+getRotation()+" name:"+getDefinitions().getName()+"]";
	}

	public boolean process() {
		boolean continueProcessing = false;
		if (tasks != null) {
			if (tasks.getSize() > 0) {
				tasks.processTasks();
				continueProcessing = true;
			} else
				tasks = null;
		}
		if (idChangeTicks == -2)
			return true;
		if (idChangeTicks > -1) {
			if (idChangeTicks-- == 0)
				changeId(originalId);
			else
				continueProcessing = true;
		}
		return continueProcessing;
	}

	public TaskManager getTasks() {
		if (tasks == null) {
			tasks = new TaskManager();
			flagForProcess();
		}
		return tasks;
	}

	public void changeId(int newId) {
		int lastId = getId();
		setId(newId);
		if (lastId != newId)
			World.refreshObject(this);
		if (newId != originalId) {
			ChunkManager.getChunk(getTile().getChunkId(), true).flagForProcess(this);
			idChangeTicks = -2;
		} else {
			ChunkManager.getChunk(getTile().getChunkId(), true).unflagForProcess(this);
			idChangeTicks = -1;
		}
	}

	public ObjectMeshModifier modifyMesh() {
		this.meshModifier = new ObjectMeshModifier(this);
		return this.meshModifier;
	}

	public void refresh() {
		World.refreshObject(this);
	}

	public void setIdTemporary(int newId, int ticks) {
		if (getId() == newId)
			return;
		Chunk chunk = ChunkManager.getChunk(getTile().getChunkId(), true);
		chunk.flagForProcess(this);
		changeId(newId);
		idChangeTicks = ticks;
	}

	public GameObject setRouteType(RouteType routeType) {
		this.routeType = routeType;
		return this;
	}

	public boolean exists() {
		return ChunkManager.getChunk(getTile().getChunkId()).objectExists(new GameObject(this, getId()));
	}

	public void flagForProcess() {
		ChunkManager.getChunk(getTile().getChunkId(), true).flagForProcess(this);
	}

	public void unflagForProcess() {
		ChunkManager.getChunk(getTile().getChunkId(), true).unflagForProcess(this);
	}

	public ObjectDefinition getDefinitions(Player player) {
		return ObjectDefinitions.getDefs(getId(), player.getVars());
	}

	public void animate(Animation animation) {
		World.sendObjectAnimation(this, animation);
	}

	public void animate(int animation) {
		World.sendObjectAnimation(this, new Animation(animation));
	}

	public RouteType getRouteType() {
		return routeType;
	}

	public AttribMap getAttribs() {
		if (attribs == null)
			attribs = new AttribMap();
		return attribs;
	}

	public ObjectMeshModifier getMeshModifier() {
		return meshModifier;
	}

	public int getOriginalId() {
		return originalId;
	}
}
