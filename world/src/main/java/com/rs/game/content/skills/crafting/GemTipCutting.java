package com.rs.game.content.skills.crafting;

import org.darkan.core.cache.ItemDefinitions;
import com.rs.game.model.entity.player.Player;
import com.rs.game.model.entity.player.actions.PlayerAction;
import org.darkan.core.Constants;
import org.darkan.core.model.Animation;

public class GemTipCutting extends PlayerAction {

	public enum GemTips {
		OPAL(1609, 1.5, 11, 886, 45),

		PEARL(411, 3.2, 41, 886, 46),

		JADE(1611, 2, 26, 886, 9187),

		RED_TOPAZ(1613, 3, 48, 887, 9188),

		SAPPHIRE(1607, 4, 56, 888, 9189),

		EMERALD(1605, 5.5, 58, 889, 9190),

		RUBY(1603, 6.5, 63, 887, 9191),

		DIAMOND(1601, 8, 65, 890, 9192),

		DRAGONSTONE(1615, 10, 71, 885, 9193),

		ONYX(6573, 25, 73, 2717, 9194);

		private final double experience;
		private final int levelRequired;
		private final int cut;

		private final int emote;
		private final int boltTips;

		private GemTips(int cut, double experience, int levelRequired, int emote, int boltTips) {
			this.cut = cut;
			this.experience = experience;
			this.levelRequired = levelRequired;
			this.emote = emote;
			this.boltTips = boltTips;
		}

		public int getLevelRequired() {
			return levelRequired;
		}

		public double getExperience() {
			return experience;
		}

		public int getCut() {
			return cut;
		}

		public int getEmote() {
			return emote;
		}

		public int getBoltTips() {
			return boltTips;
		}

	}

	public static void cut(Player player, GemTips gem) {
		player.getActionManager().setAction(new GemTipCutting(gem, player.getInventory().getNumberOf(gem.getCut())));
	}

	private final GemTips gem;
	private int quantity;

	public GemTipCutting(GemTips gem, int quantity) {
		this.gem = gem;
		this.quantity = quantity;
	}

	public boolean checkAll(Player player) {
		if (player.getSkills().getLevel(Constants.FLETCHING) < gem.getLevelRequired()) {
			player.simpleDialogue("You need a fletching level of " + gem.getLevelRequired() + " to cut that gem.");
			return false;
		}
		if (!player.getInventory().containsOneItem(gem.getCut())) {
			player.simpleDialogue("You don't have any " + ItemDefinitions.getDefs(gem.getCut()).getName().toLowerCase() + " to cut.");
			return false;
		}
		return true;
	}

	@Override
	public boolean start(Player player) {
		if (checkAll(player)) {
			setActionDelay(player, 2);
			player.setNextAnimation(new Animation(gem.getEmote()));
			return true;
		}
		return false;
	}

	@Override
	public boolean process(Player player) {
		return checkAll(player);
	}

	@Override
	public int processWithDelay(Player player) {
		player.getInventory().deleteItem(gem.getCut(), 1);
		if(gem.getCut() == 411){
			player.getInventory().addItem(gem.getBoltTips(), 6);
		}else {
			player.getInventory().addItem(gem.getBoltTips(), 12);
		}
		player.getSkills().addXp(Constants.FLETCHING, gem.getExperience());
		player.sendMessage("You cut the " + ItemDefinitions.getDefs(gem.getCut()).getName().toLowerCase() + ".", true);
		quantity--;
		if (quantity <= 0)
			return -1;
		player.setNextAnimation(new Animation(gem.getEmote()));
		return 2;
	}

	@Override
	public void stop(final Player player) {
		setActionDelay(player, 3);
	}
}
