package com.rs.utils.music;

import com.google.gson.JsonIOException;
import org.darkan.core.cache.ItemDefinitions;
import com.rs.game.World;
import com.rs.game.content.world.generated.GeneratedMusicKt;
import com.rs.game.model.entity.player.Player;
import org.darkan.core.JsonFileManager;
import org.darkan.core.model.Item;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.annotations.ServerStartupEvent;
import com.rs.plugin.annotations.ServerStartupEvent.Priority;
import com.rs.utils.spawns.ItemSpawn;

import java.io.File;
import java.io.IOException;
import java.util.*;

@PluginEventHandler
public class Music {
	private static final Map<Integer, Song> MUSICS = new HashMap<>();
	private static final Map<Integer, int[]> MUSICS_REGION = new HashMap<>();

	private static final Map<Integer, Genre> GENRE_CHUNKS = new HashMap<>();
	private static final Map<Integer, Genre> GENRE_REGION = new HashMap<>();
	private static final List<Genre> genres = new ArrayList<>();
	private static Genre[] parentGenres;
	private static final ArrayList<Integer> allowAmbientMusic = new ArrayList<>();

	@ServerStartupEvent(Priority.FILE_IO)
	public static void init() {
		try {
			Song[] songs = JsonFileManager.loadJsonFile(new File("./data/music/songs.json"), Song[].class);
			for (Song s : songs) {
				MUSICS.put(s.getId(), s);
				for (int regionId : s.getRegionIds())
					if (MUSICS_REGION.get(regionId) == null)
						MUSICS_REGION.put(regionId, new int[] { s.getId() });
					else {
						int[] musicIds = MUSICS_REGION.get(regionId);
						int[] newMusicIds = new int[musicIds.length+1];
                        System.arraycopy(musicIds, 0, newMusicIds, 0, musicIds.length);
						newMusicIds[musicIds.length] = s.getId();
						MUSICS_REGION.put(regionId, newMusicIds);
					}
			}

			parentGenres = JsonFileManager.loadJsonFile(new File("./data/music/parent-genres.json"), Genre[].class);
			File[] genreFiles = new File("./data/music/regions/").listFiles();
			for (File f : genreFiles)
				genres.addAll(Arrays.asList(JsonFileManager.loadJsonFile(f, Genre[].class)));
			addGenresToChunkMap();
			addGenresToRegionMap();

			for(Song s : songs)
				if(s.isAllowAmbient())
					allowAmbientMusic.add(s.getId());

		} catch (JsonIOException e) {
			e.printStackTrace();
		}
	}

	private static void addGenresToRegionMap() {
		for(Genre g : genres)
			if(g.isActive())
				for (int regionId : g.getRegionIds()) {
					if (GENRE_REGION.containsKey(regionId))
						throw new java.lang.Error("Error, duplicate key at: " + regionId);
					GENRE_REGION.put(regionId, g);
				}
	}
	private static void addGenresToChunkMap() {
		for(Genre g : genres)
			if(g.isActive())
				for (int chunkId : g.getChunkIds()) {
					if (GENRE_CHUNKS.containsKey(chunkId))
						throw new java.lang.Error("Error, duplicate key at: " + chunkId);
					GENRE_CHUNKS.put(chunkId, g);
				}
	}

	public static int[] getRegionMusics(int regionId) {
		return MUSICS_REGION.get(regionId);
	}

	public static Song getSong(int musicId) {
		return MUSICS.get(musicId);
	}

	public static List<Genre> getGenres() {
		return genres;
	}

	public static Set<Integer> getSongIds() {
		return MUSICS.keySet();
	}

	public static Genre getGenre(Player player) {
		Genre biomeGenre = GeneratedMusicKt.biomeGenre(player);
		if(biomeGenre != null)
			return biomeGenre;
		if(!GENRE_REGION.containsKey(player.getRegionId()))
			return null;
		Genre g = GENRE_REGION.get(player.getRegionId());
		if(GENRE_CHUNKS.containsKey(player.getChunkId()))
			g = GENRE_CHUNKS.get(player.getChunkId());
		if(g.isActive())
			return g;
		return null;
	}

	public static Genre getParent(String name) {
		for(Genre g : parentGenres)
			if(g.getGenreName().equalsIgnoreCase(name))
				return g;
		return null;
	}

	public static String[] getSongGenres(int musicId) {
		List<String> genreNames = new ArrayList<>();
		for(Genre g : genres)
			for(int songId : g.getSongs())
				if(songId == musicId)
					genreNames.add(g.getGenreName());
		return genreNames.stream().toArray(String[]::new);
	}

    public static Genre getGenreByName(String name) {
        for(Genre g : genres)
            if(g.getGenreName().equalsIgnoreCase(name))
                return g;
        return null;
    }

	public static ArrayList<Integer> getAllowAmbientMusic() {
		return allowAmbientMusic;
	}
}
