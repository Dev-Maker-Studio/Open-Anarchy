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
package com.rs.game.model.entity.player.managers;

import org.darkan.core.cache.EnumDefinitions;
import com.rs.game.map.ChunkManager;
import com.rs.game.model.entity.player.Player;
import org.darkan.core.model.Rights;

import org.darkan.core.Utils;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ButtonClickHandler;
import com.rs.utils.music.Biome;
import com.rs.utils.music.BiomeMusic;
import com.rs.utils.music.Genre;
import com.rs.utils.music.Music;
import com.rs.utils.music.Song;

import java.util.*;
import java.util.stream.Collectors;
import com.rs.game.model.entity.npc.NPC;

@PluginEventHandler
public final class MusicsManager {

    private static final int[] CONFIG_IDS = {20, 21, 22, 23, 24, 25, 298, 311, 346, 414, 464, 598, 662, 721, 906, 1009, 1104, 1136, 1180, 1202, 1381, 1394, 1434, 1596, 1618, 1619, 1620, 1865, 1864, 2246, 2019, -1, 2430, 2559};
    private static final int[] PLAY_LIST_CONFIG_IDS = {1621, 1622, 1623, 1624, 1625, 1626};
	private static final int NULL_SOUND_TRACK = 147;

    private transient Player player;
    private transient Genre playingGenre;
    private transient int playingMusic;
    private transient long playingMusicDelay;
    private transient boolean settedMusic;
    private final ArrayList<Integer> unlockedMusics;
    private final ArrayList<Integer> playList;
    private final Deque<Integer> lastTenSongs = new ArrayDeque<>();

    private transient boolean playListOn;
    private transient int nextPlayListMusic;
    private transient boolean shuffleOn;
    private transient Biome playingBiome;
    private transient long combatMusicUntil;
    private final transient ArrayDeque<Integer> biomeQueue = new ArrayDeque<>();
    private static final long COMBAT_MUSIC_LINGER_MS = 15000;

    public MusicsManager() {
        unlockedMusics = new ArrayList<>();
        playList = new ArrayList<>(12);
        // auto unlocked musics
        unlockedMusics.add(62);
        unlockedMusics.add(400);
        unlockedMusics.add(16);
        unlockedMusics.add(466);
        unlockedMusics.add(321);
        unlockedMusics.add(547);
        unlockedMusics.add(621);
        unlockedMusics.add(207);
        unlockedMusics.add(401);
        unlockedMusics.add(457);
        unlockedMusics.add(552);
		unlockedMusics.add(621);
    }

    public static ButtonClickHandler handlePlaylistButtons = new ButtonClickHandler(187, e -> {
    	if (e.getComponentId() == 1) {
            if (e.getOpNum() == 1)
                e.getPlayer().getMusicsManager().playAnotherMusic(e.getSlotId() / 2);
            else if (e.getOpNum() == 2)
                e.getPlayer().getMusicsManager().sendHint(e.getSlotId() / 2);
            else if (e.getOpNum() == 3)
                e.getPlayer().getMusicsManager().addToPlayList(e.getSlotId() / 2);
            else if (e.getOpNum() == 4)
                e.getPlayer().getMusicsManager().removeFromPlayList(e.getSlotId() / 2);
        } else if (e.getComponentId() == 4)
            e.getPlayer().getMusicsManager().addPlayingMusicToPlayList();
        else if (e.getComponentId() == 10)
            e.getPlayer().getMusicsManager().switchPlayListOn();
        else if (e.getComponentId() == 11)
            e.getPlayer().getMusicsManager().clearPlayList();
        else if (e.getComponentId() == 13)
            e.getPlayer().getMusicsManager().switchShuffleOn();
    });

    /**
     * Only for debug use
     *
     */
    public void clearUnlocked() {
        unlockedMusics.clear();
        unlockedMusics.add(62);
        unlockedMusics.add(400);
        unlockedMusics.add(16);
        unlockedMusics.add(466);
        unlockedMusics.add(321);
        unlockedMusics.add(547);
        unlockedMusics.add(621);
        unlockedMusics.add(207);
        unlockedMusics.add(401);
        unlockedMusics.add(457);
        unlockedMusics.add(552);
    }

    private void unlockAllMusic() {
        for (int musicId : Music.getSongIds())
            if (!unlockedMusics.contains(musicId))
                unlockedMusics.add(musicId);
    }

    public boolean combatMusicActive() {
        return combatMusicUntil > System.currentTimeMillis();
    }

    public void onTargetedBy(NPC npc) {
        if (!player.hasStarted())
            return;
        int song = BiomeMusic.combatSong(npc.getDefinitions().getCombatLevel(), player.getSkills().getCombatLevelWithSummoning());
        if (song == BiomeMusic.NO_SONG)
            return;
        combatMusicUntil = System.currentTimeMillis() + COMBAT_MUSIC_LINGER_MS;
        if (playingMusic != song)
            playSongWithoutUnlocking(song);
    }

    public void processBiomeAndCombat() {
        if (combatMusicUntil != 0 && !combatMusicActive()) {
            combatMusicUntil = 0;
            playingBiome = null;
        }
        if (combatMusicActive() || playListOn || settedMusic || !player.hasStarted())
            return;
        if (player.getControllerManager().getController() != null && player.getControllerManager().getController().playAmbientStrictlyBackgroundMusic())
            return;
        Genre genre = player.getControllerManager().getController() == null ? Music.getGenre(player) : player.getControllerManager().getController().getGenre();
        if (Biome.Companion.of(genre) != playingBiome)
            nextAmbientSong();
    }

    public void passMusics(Player p) {
        for (int musicId : p.getMusicsManager().unlockedMusics)
            if (!unlockedMusics.contains(musicId))
                unlockedMusics.add(musicId);
    }

    public boolean hasMusic(int id) {
        return unlockedMusics.contains(id);
    }

    public void setPlayer(Player player) {
        this.player = player;
        playingMusic = ChunkManager.getChunk(player.getChunkId()).getMusicId();
        unlockAllMusic();
    }

    public void switchShuffleOn() {
        if (shuffleOn) {
            playListOn = false;
            refreshPlayListConfigs();
        }
        shuffleOn = !shuffleOn;
    }

    public int getPlayingMusicId() {
        return playingMusic;
    }

    public void switchPlayListOn() {
        if (playListOn) {
            playListOn = false;
            shuffleOn = false;
            refreshPlayListConfigs();
        } else {
            playListOn = true;
            nextPlayListMusic = 0;
            nextAmbientSong();
        }
    }

    public void clearPlayList() {
        if (playList.isEmpty())
            return;
        playList.clear();
        refreshPlayListConfigs();
    }

    public void addPlayingMusicToPlayList() {
        addToPlayList((int) EnumDefinitions.getEnum(1351).getKeyForValue(playingMusic));
    }

    public void addToPlayList(int musicIndex) {
        if (playList.size() == 12)
            return;
        int musicId = EnumDefinitions.getEnum(1351).getIntValue(musicIndex);
        if (musicId != -1 && unlockedMusics.contains(musicId) && !playList.contains(musicId)) {
            playList.add(musicId);
            if (playListOn)
                switchPlayListOn();
            else
                refreshPlayListConfigs();
        }
    }

    public void removeFromPlayList(int musicIndex) {
        Integer musicId = EnumDefinitions.getEnum(1351).getIntValue(musicIndex);
        if (musicId != -1 && unlockedMusics.contains(musicId) && playList.contains(musicId)) {
            playList.remove(musicId);
            if (playListOn)
                switchPlayListOn();
            else
                refreshPlayListConfigs();
        }
    }

    public void refreshPlayListConfigs() {
        int[] configValues = new int[PLAY_LIST_CONFIG_IDS.length];
        Arrays.fill(configValues, -1);
        for (int i = 0; i < playList.size(); i += 2) {
            Integer musicId1 = playList.get(i);
            Integer musicId2 = (i + 1) >= playList.size() ? null : playList.get(i + 1);
            if (musicId1 == null && musicId2 == null)
                break;
            int musicIndex = (int) EnumDefinitions.getEnum(1351).getKeyForValue(musicId1);
            int configValue;
            if (musicId2 != null) {
                int musicIndex2 = (int) EnumDefinitions.getEnum(1351).getKeyForValue(musicId2);
                configValue = musicIndex | musicIndex2 << 15;
            } else
                configValue = musicIndex | -1 << 15;
            configValues[i / 2] = configValue;
        }
        for (int i = 0; i < PLAY_LIST_CONFIG_IDS.length; i++)
            if (PLAY_LIST_CONFIG_IDS[i] == -1)
                player.getVars().setVar(PLAY_LIST_CONFIG_IDS[i], configValues[i]);
    }

    public void refreshListConfigs() {
        int[] configValues = new int[CONFIG_IDS.length];
        for (int musicId : unlockedMusics) {
            int musicIndex = (int) EnumDefinitions.getEnum(1351).getKeyForValue(musicId);
            if (musicIndex == -1)
                continue;
            int index = getConfigIndex(musicIndex);
            if (index >= CONFIG_IDS.length)
                continue;
            configValues[index] |= 1 << (musicIndex - (index * 32));
        }
        for (int i = 0; i < CONFIG_IDS.length; i++)
            if (CONFIG_IDS[i] != -1 && configValues[i] != 0)
                player.getVars().setVar(CONFIG_IDS[i], configValues[i]);
    }

    public void addMusicToUnlockedList(int musicId) {
        unlockedMusics.add(musicId);
        refreshListConfigs();
    }

    public int unlockedMusicCount() {
        return unlockedMusics.size();
    }

    public Genre getPlayingGenre() {
        return playingGenre;
    }

    public int getConfigIndex(int musicId) {
        return (musicId + 1) / 32;
    }

    public void unlockMusicPlayer() {
        player.getPackets().setIFRightClickOps(187, 1, 0, CONFIG_IDS.length * 64, 0, 1, 2, 3);
    }

    public void init() {
        refreshListConfigs();
        refreshPlayListConfigs();
    }

    public boolean musicEnded() {
        return playingMusic != NULL_SOUND_TRACK && playingMusicDelay + (180000) < System.currentTimeMillis();
    }

	/**
	 * Next ambient song by genre or region.
	 */
    public void nextAmbientSong() {
        if (player.getCutsceneManager().hasCutscene() || combatMusicActive())
            return;
        if (player.getControllerManager().getController() != null && !player.getControllerManager().getController().playAmbientMusic())
            return;
        if (playListOn && playList.size() > 0)//playlist
            pickPlaylistSong();
        else if (unlockedMusics.size() > 0) {//ambient music at random
            lastTenSongs.addFirst(playingMusic);
			if(lastTenSongs.size() > 10)
				lastTenSongs.removeLast();
            if (player.getControllerManager().getController() != null && player.getControllerManager().getController().playAmbientStrictlyBackgroundMusic())
				pickAmbientStrictlyBackgroundMusic();
            else
				pickAmbientSong();
        }
        playSongWithoutUnlocking(playingMusic);
    }

	public void playSpecificAmbientSong(int song) {
		playSpecificAmbientSong(song, false);
	}

	/**
	 * Play song but not
	 * 1. if in the last ten
	 * 2. if a playlist is on
	 * @param song
	 */
	public void playSpecificAmbientSong(int song, boolean unlock) {
		if(playListOn && playList.size() > 0)
			return;
		lastTenSongs.addFirst(playingMusic);
		if(lastTenSongs.size() > 10)
			lastTenSongs.removeLast();
		if(lastTenSongs.contains(song))
			return;
		if(unlock) {
			playSongAndUnlock(song);
			return;
		}
		playSongWithoutUnlocking(song);
	}

    /**
     * Only for use in nextAmbientSong
     */
    private void pickPlaylistSong() {
        if (shuffleOn)
            playingMusic = playList.get(Utils.getRandomInclusive(playList.size() - 1));
        else {
            if (nextPlayListMusic >= playList.size())
                nextPlayListMusic = 0;
            playingMusic = playList.get(nextPlayListMusic++);
        }
    }

    /**
     * Only for use with dungeoneering. It does not include unlocked musics
     */
    private void pickAmbientStrictlyBackgroundMusic() {
		playingGenre = player.getControllerManager().getController().getGenre();
		if (playingGenre == null) {
			playingMusic = NULL_SOUND_TRACK;//don't play music
			return;
		}
		List<Integer> genreSongs = Arrays.stream(playingGenre.getSongs()).boxed().collect(Collectors.toList());
		cycleMusic(null, genreSongs);
    }

    /**
     * Only for use in nextAmbientSong.
     */
    private void pickAmbientSong() {
        playingGenre = player.getControllerManager().getController() == null ?
                Music.getGenre(player) : player.getControllerManager().getController().getGenre();
        Biome biome = Biome.Companion.of(playingGenre);
        if (biome != playingBiome)
            biomeQueue.clear();
        playingBiome = biome;
        int song = BiomeMusic.nextSong(biome, biomeQueue, lastTenSongs);
        playingMusic = song == BiomeMusic.NO_SONG ? NULL_SOUND_TRACK : song;
    }

	private void cycleMusic(List<Integer> regionSongs, List<Integer> genreSongs) {
		if(regionSongs != null && regionSongs.size() > 0) {
			int attempts = regionSongs.size();
			for (int i = 0; i < attempts; i++) {
				int random = Utils.random(regionSongs.size());
				if(!lastTenSongs.contains(regionSongs.get(random))) {
					playingMusic = regionSongs.get(random);
					return;
				} else
					regionSongs.remove(random);
			}
		}
		if(genreSongs == null || genreSongs.size() <= 0)
			return;

		int attempts = genreSongs.size();
		for (int i = 0; i < attempts; i++) {
			int random = Utils.random(genreSongs.size());
			if(!lastTenSongs.contains(genreSongs.get(random))) {
				playingMusic = genreSongs.get(random);
				return;
			} else
				genreSongs.remove(random);
		}
		playingMusic = NULL_SOUND_TRACK;//Don't play music.
	}

    public void playSongWithoutUnlocking(int musicId) {
        if (!player.hasStarted())
            return;
        playingMusicDelay = System.currentTimeMillis();
        if (musicId == NULL_SOUND_TRACK || Music.getSong(musicId) == null) {
            playingMusic = musicId;
            player.musicTrack(NULL_SOUND_TRACK);
            player.getPackets().setIFText(187, 4, "");
            return;
        }
        Song song = Music.getSong(musicId);
        player.getPackets().setIFText(187, 4, song.getName() != null ? song.getName() : "");
        player.musicTrack(musicId, playingMusic == NULL_SOUND_TRACK ? 0 : 100);
        playingMusic = musicId;
    }

    /**
     * Plays music if checked music is not on but includes hints
     *
     * @param requestMusicId
     */
    public void checkMusic(int requestMusicId) {
        if (playListOn || settedMusic && playingMusicDelay + (180000) >= System.currentTimeMillis())
            return;
        settedMusic = false;
        processBiomeAndCombat();
    }

    public void forcePlayMusic(int musicId) {
        settedMusic = true;
		playSongWithoutUnlocking(musicId);
    }

    public boolean isPlaying(int requestMusicId) {
        return playingMusic == requestMusicId;
    }

    public void reset() {
        settedMusic = false;
        player.getMusicsManager().checkMusic(ChunkManager.getChunk(player.getChunkId()).getMusicId());
    }

    public void sendHint(int slotId) {
        int musicId = EnumDefinitions.getEnum(1351).getIntValue(slotId);
        Song song = Music.getSong(musicId);
        if (song == null) {
            player.sendMessage("Error handling song: " + musicId);
            return;
        }
        if (player.hasRights(Rights.DEVELOPER))
            player.sendMessage("Music id: " + musicId);
        if (musicId != NULL_SOUND_TRACK)
            player.sendMessage("This track " + (unlockedMusics.contains(musicId) ? "was unlocked" : "unlocks") + " " + song.getHint());
    }

    public void playAnotherMusic(int musicIndex) {
        int musicId = EnumDefinitions.getEnum(1351).getIntValue(musicIndex);
        if (musicId != -1 && unlockedMusics.contains(musicId)) {
            settedMusic = true;
            if (playListOn)
                switchPlayListOn();
            playSongWithoutUnlocking(musicId);
        }
    }

    public void playSongAndUnlock(int musicId) {
        if (!player.hasStarted())
            return;
        playingMusicDelay = System.currentTimeMillis();
        if (musicId == NULL_SOUND_TRACK) {
            playingMusic = musicId;
            player.musicTrack(NULL_SOUND_TRACK);
            player.getPackets().setIFText(187, 4, "");
            return;
        }
        player.musicTrack(musicId, playingMusic == NULL_SOUND_TRACK ? 0 : 100);
        playingMusic = musicId;
        Song song = Music.getSong(musicId);
        if (song != null) {
            player.getPackets().setIFText(187, 4, song.getName() != null ? song.getName() : "");
            if (!unlockedMusics.contains(musicId)) {
                addMusicToUnlockedList(musicId);
            }
        }
    }

    public void unlockMusic(int musicId) {
        Song song = Music.getSong(musicId);
        if (song != null)
            if (!unlockedMusics.contains(musicId)) {
                addMusicToUnlockedList(musicId);
            }
    }

    public boolean isUnlocked(int musicId) {
        Song song = Music.getSong(musicId);
        if (song != null)
            return unlockedMusics.contains(musicId);
        return false;
    }

}
