package com.rs.db.collection;

import com.google.gson.JsonIOException;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.Indexes;
import com.rs.game.World;
import com.rs.game.model.entity.player.Player;
import com.rs.db.DBItemManager;
import org.darkan.core.JsonFileManager;
import org.darkan.core.Logger;
import org.bson.Document;

import java.io.IOException;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.eq;

public class PlayerManager extends DBItemManager {

	public PlayerManager() {
		super("players");
	}

	@Override
	public void initCollection() {
		getDocs().createIndex(Indexes.ascending("username"));
		dropIndexIfPresent("username_text");
	}

	public void getByUsername(String username, Consumer<Player> func) {
		execute(() -> func.accept(getSyncUsername(username)));
	}

	public void save(Player player) {
		save(player, null);
	}

	public void save(Player player, Runnable done) {
		String username = player.getUsername();
		String json = snapshot(player);
		execute(() -> {
			if (json != null)
				write(username, json);
			if (done != null)
				done.run();
		});
	}

	public void saveSync(Player account) {
		String json = snapshot(account);
		if (json != null)
			write(account.getUsername(), json);
	}

	private String snapshot(Player player) {
		try {
			return JsonFileManager.toJson(player);
		} catch (Throwable e) {
			Logger.handle(PlayerManager.class, "snapshot", "Error serializing player: " + player.getUsername(), e);
			return null;
		}
	}

	protected void write(String username, String json) {
		try {
			getDocs().findOneAndReplace(eq("username", username), Document.parse(json), new FindOneAndReplaceOptions().upsert(true));
		} catch(Throwable e) {
			Logger.handle(PlayerManager.class, "write", "Error saving player: " + username, e);
		}
	}

	public record PlayerResult(Player player, boolean loadError) {
		public static PlayerResult of(Player player) {
			return new PlayerResult(player, false);
		}

		public static PlayerResult error() {
			return new PlayerResult(null, true);
		}
	}

	public PlayerResult loadForLogin(String username) {
		Player loggedIn = World.getPlayerByUsername(username);
		if (loggedIn != null)
			return PlayerResult.of(loggedIn);
		Document accDoc = getDocs().find(eq("username", username)).first();
		if (accDoc == null)
			return PlayerResult.of(null);
		try {
			return PlayerResult.of(decode(accDoc));
		} catch (Throwable e) {
			Logger.handle(PlayerManager.class, "loadForLogin", "Failed to load player: " + username, e);
			return PlayerResult.error();
		}
	}

	private Player decode(Document accDoc) throws JsonIOException, IOException {
		return JsonFileManager.fromJSONString(JsonFileManager.toJson(accDoc), Player.class);
	}

	public Player getSyncUsername(String username) {
		Player loggedIn = World.getPlayerByUsername(username);
		if (loggedIn != null)
			return loggedIn;
		Document accDoc = getDocs().find(eq("username", username)).first();
		if (accDoc == null)
			return null;
		try {
			return decode(accDoc);
		} catch (Throwable e) {
			Logger.handle(PlayerManager.class, "getSyncUsername", "Failed to load player: " + username, e);
			return null;
		}
	}

	public boolean usernameExists(String username) {
		return getSyncUsername(username) == null;
	}

}
