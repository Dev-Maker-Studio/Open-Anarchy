package com.rs.game.content.world;

import com.rs.game.model.entity.player.Player;
import com.rs.game.model.entity.player.actions.PlayerAction;
import org.darkan.core.model.Animation;
import org.darkan.core.Utils;

import java.util.Map;

public class RestMusician extends PlayerAction {

	private static final int[][] REST_DEFS = { { 5713, 1549, 5748 }, { 11786, 1550, 11788 }, { 5713, 1551, 2921 }

	};

    private final int musicId;
	private int index;

	public RestMusician(int musicianId) {
        Map<Integer, Integer> musicListing = Map.ofEntries(
                Map.entry(5439, 661),
                Map.entry(8705, 661),
                Map.entry(8698, 657),
                Map.entry(29, 661),
                Map.entry(8709, 660),
                Map.entry(8715, 664),
                Map.entry(8723, 659),
                Map.entry(8712, 665),
                Map.entry(8702, 661),
                Map.entry(8706, 657),
                Map.entry(8716, 662),
                Map.entry(8703, 661),
                Map.entry(8704, 657),
                Map.entry(8717, 662),
                Map.entry(8718, 662),
                Map.entry(5442, 661),
                Map.entry(30, 661),
                Map.entry(8699, 657),
                Map.entry(3463, 661),
                Map.entry(8708, 660),
                Map.entry(8707, 660),
                Map.entry(8701, 657),
                Map.entry(8700, 657),
                Map.entry(8713, 656)
        );
        musicId = musicListing.getOrDefault(musicianId, -1);
	}

	@Override
	public boolean start(Player player) {
		if (!process(player))
			return false;
		index = Utils.random(REST_DEFS.length);
		player.setResting(true);
		player.anim(REST_DEFS[index][0]);
		player.getAppearance().setBAS(REST_DEFS[index][1]);
		return true;
	}

	@Override
	public boolean process(Player player) {
		if(musicId != -1 && !player.getMusicsManager().isPlaying(musicId))
			player.musicTrack(musicId);
		if (player.getPoison().isPoisoned()) {
			player.sendMessage("You can't rest while you're poisoned.");
			return false;
		}
		if (player.inCombat(10000) || player.hasBeenHit(10000)) {
			player.sendMessage("You can't rest until 10 seconds after the end of combat.");
			return false;
		}
		return true;
	}

	@Override
	public int processWithDelay(Player player) {
		return 0;
	}

	@Override
	public void stop(Player player) {
		player.setResting(false);
		player.anim(REST_DEFS[index][2]);
		player.getEmotesManager().setNextEmoteEnd();
		player.getAppearance().setBAS(-1);
		if(musicId != -1)
			player.getMusicsManager().nextAmbientSong();
	}

}
