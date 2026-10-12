package com.rs;

import org.darkan.core.Globals;
import org.darkan.core.model.Item;
import world.gregs.voidps.type.Tile;
import com.rs.game.WorldInfo;
import org.darkan.core.Logger;
import org.darkan.core.EnvVars;
import org.darkan.core.map.GeneratedWorld;

import java.util.ArrayList;

public final class Settings {

	private static final Settings INSTANCE = new Settings();

	public static Settings getConfig() {
		return INSTANCE;
	}

	public static final long WORLD_CYCLE_NS = EnvVars.INSTANCE.getWorldCycleNanos();
	public static final long WORLD_CYCLE_MS = EnvVars.INSTANCE.getWorldCycleMillis();

	public static final int AIR_GUITAR_MUSICS_COUNT = 150;
	public static final int PLAYERS_LIMIT = 2000;
	public static final int LOCAL_PLAYERS_LIMIT = 250;
	public static final int NPCS_LIMIT = 64000;
	public static final int LOCAL_NPCS_LIMIT = 250;
	public static final double HIGH_MEM_USE_THRESHOLD = 85.0;

	public static ArrayList<String> COMMIT_HISTORY = new ArrayList<>();

	private String loginMessage = "";
	private boolean enableJFR = false;

	private final Tile playerStartTile = startTile(EnvVars.INSTANCE.getPlayerStartTile(), Tile.of(2889, 3528, 0));
	private final Tile playerRespawnTile = startTile(EnvVars.INSTANCE.getPlayerRespawnTile(), Tile.of(2887, 3535, 0));

	private final Item[] startItems = new Item[] {
		new Item(1351, 1), new Item(590, 1), new Item(303, 1), new Item(315, 1),
		new Item(1925, 1), new Item(1931, 1), new Item(2309, 1), new Item(1265, 1),
		new Item(1205, 1), new Item(1277, 1), new Item(1171, 1), new Item(841, 1),
		new Item(882, 25), new Item(556, 25), new Item(558, 15), new Item(555, 6),
		new Item(557, 4), new Item(559, 2),
	};

	private Settings() {}

	private static Tile startTile(int[] override, Tile vanilla) {
		if (override != null)
			return Tile.of(override[0], override[1], override[2]);
		if (EnvVars.INSTANCE.getGenerateMaps())
			return Tile.of(GeneratedWorld.INSTANCE.getSpawnX(), GeneratedWorld.INSTANCE.getSpawnY(), 0);
		return vanilla;
	}

	public static void loadConfig() {
		Logger.info(Settings.class, "loadConfig", "Loading config from .env...");
		Globals.DEBUG = EnvVars.INSTANCE.getDebug();
		loadCommitHistory();
		Logger.info(Settings.class, "loadConfig", "Server: " + getConfig().getServerName() +
			" | World: " + EnvVars.INSTANCE.getWorldId() +
			" | Debug: " + EnvVars.INSTANCE.getDebug() +
			" | Port: " + EnvVars.INSTANCE.getWorldPort());
	}

	private static void loadCommitHistory() {
		try {
			String line;
			ProcessBuilder builder = new ProcessBuilder("git", "log", "-n", "1");
			Process proc = builder.start();
			java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()));
			boolean markMsg = false;
			while ((line = in.readLine()) != null) {
				if (line.isEmpty()) continue;
				line = line.replace("commit ", "Hash: ");
				if (markMsg) {
					line = "Message: " + line.trim();
					markMsg = false;
				}
				if (line.contains("Date: "))
					markMsg = true;
				COMMIT_HISTORY.add(line);
			}
			proc.waitFor();
			in.close();
		} catch (Exception e) {
			Logger.handle(Settings.class, "loadCommitHistory", e);
		}
	}


	public String getServerName() {
		return EnvVars.INSTANCE.getServerName();
	}

	public String getOwnerName() {
		return "trent";
	}

	public boolean isDebug() {
		return EnvVars.INSTANCE.getDebug();
	}

	public boolean isEnableJFR() {
		return enableJFR;
	}

	public boolean isAllowHighMemUseOptimizations() {
		return false;
	}

	public String getLoginMessage() {
		return loginMessage;
	}

	public void setLoginMessage(String loginMessage) {
		this.loginMessage = loginMessage;
	}

	public Tile getPlayerStartTile() {
		return Tile.of(playerStartTile);
	}

	public Tile getPlayerRespawnTile() {
		return Tile.of(playerRespawnTile);
	}

	public int getXpRate() {
		return EnvVars.INSTANCE.getWorldXpRate();
	}

	public double getDropModifier() {
		return EnvVars.INSTANCE.getWorldDropModifier();
	}

	public String getCachePath() {
		return EnvVars.INSTANCE.getCachePath();
	}

	public static boolean isOwner(String string) {
		return string.equalsIgnoreCase(INSTANCE.getOwnerName());
	}

	public Item[] getStartItems() {
		return startItems;
	}

	public WorldInfo getWorldInfo() {
		return new WorldInfo(
			EnvVars.INSTANCE.getWorldId(),
			EnvVars.INSTANCE.getWorldPublicHost(),
			EnvVars.INSTANCE.getWorldPort(),
			EnvVars.INSTANCE.getWorldName(),
			1,
			EnvVars.INSTANCE.getWorldQuickChat(),
			EnvVars.INSTANCE.getWorldLootShare(),
			EnvVars.INSTANCE.getWorldMembers(),
			EnvVars.INSTANCE.getWorldBotsAllowed(),
			false
		);
	}

	public String getLobbyIp() {
		String url = EnvVars.INSTANCE.getSocialGatewayUrl();
		return url.replaceAll("ws://", "").replaceAll("/.*", "").replaceAll(":.*", "");
	}

	public String getLobbyApiKey() {
		return EnvVars.INSTANCE.getApiKey();
	}

	public String getMongoDBName() {
		String uri = EnvVars.INSTANCE.getMongoUri();
		int lastSlash = uri.lastIndexOf('/');
		int qMark = uri.indexOf('?', lastSlash);
		if (lastSlash >= 0) {
			return qMark >= 0 ? uri.substring(lastSlash + 1, qMark) : uri.substring(lastSlash + 1);
		}
		return "darkan-server";
	}

	public String getMongoDb() {
		return EnvVars.INSTANCE.getMongoUri();
	}

	public String getStaffWebhookUrl() {
		return EnvVars.INSTANCE.getStaffWebhookUrl();
	}

	public void setJFR(boolean b) {
		enableJFR = b;
	}

	public static void saveConfig() {
	}
}
