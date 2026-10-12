package com.rs.utils.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rs.game.model.entity.player.Controller;
import world.gregs.voidps.type.Tile;

import java.util.Date;

public final class WorldGson {

	private WorldGson() {
	}

	public static Gson create() {
		return new GsonBuilder()
				.registerTypeAdapter(Controller.class, new ControllerAdapter())
				.registerTypeAdapter(Tile.class, new TileAdapter())
				.registerTypeAdapter(Date.class, new DateAdapter())
				.disableHtmlEscaping()
				.setPrettyPrinting()
				.create();
	}
}
