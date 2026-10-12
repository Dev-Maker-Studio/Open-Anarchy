package com.rs.game.content.skills.herblore;

import com.rs.engine.dialogue.Conversation;
import com.rs.engine.dialogue.statements.MakeXStatement;
import com.rs.game.model.entity.player.Player;
import com.rs.game.model.entity.player.actions.PlayerAction;
import org.darkan.core.Constants;
import org.darkan.core.model.Animation;
import org.darkan.core.model.Item;
import org.darkan.core.Utils;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ItemOnItemHandler;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

@PluginEventHandler
public class Herblore extends PlayerAction {

	private final CraftablePotion potion;
	private int ticks;

	public Herblore(CraftablePotion potion, int amount) {
		this.potion = potion;
		ticks = amount;
	}

	@Override
	public boolean start(Player player) {
		if (player == null || potion == null)
			return false;
		if (player.getSkills().getLevel(Constants.HERBLORE) < potion.getReq()) {
			player.simpleDialogue("You need a herblore level of " + potion.getReq() + " to combine these ingredients.");
			return false;
		}
		return true;
	}

	@Override
	public boolean process(Player player) {
		if (!player.getInventory().containsItem(potion.getPrimary()) || !player.getInventory().containsItems(potion.getSecondaries())) {
			player.sendMessage("You don't have enough ingredients left.", true);
			return false;
		}
		return true;
	}

	@Override
	public int processWithDelay(Player player) {
		player.setNextAnimation(new Animation(363));
		ticks--;
		player.getInventory().deleteItem(potion.getPrimary());
		List<Item> secondaries = new LinkedList<>(Arrays.asList(potion.getSecondaries()));
		boolean cleansingProc = player.hasScrollOfCleansing && Utils.random(100) <= 10;
		if (cleansingProc)
			player.sendMessage("Your scroll of cleansing saves " + Utils.addArticle(secondaries.remove(Utils.random(secondaries.size())).getName().toLowerCase()) + ".");
		if (secondaries.size() > 0)
			player.getInventory().removeItems(secondaries);
		player.getInventory().addItemDrop(potion.getProduct().clone());
		player.incrementCount(potion.getProduct().getName() + " mixed");
		String potName = potion.getProduct().getName().toLowerCase();
		if (potion.getProduct().getId() != 3265) {
			player.sendMessage("You mix " + Utils.addArticle(potName) + ".", true);
		} else {
			player.sendMessage("You add the ground Troll Thistle to the potion.");
		}
		player.getSkills().addXp(Constants.HERBLORE, potion.getXp());
		if (ticks > 0)
			return cleansingProc ? 0 : 1;
		return -1;
	}

	@Override
	public void stop(final Player player) {
		setActionDelay(player, 3);
	}

	public static ItemOnItemHandler craftPotion = new ItemOnItemHandler(true, CraftablePotion.MAP.keySet().toArray(), e -> {
		CraftablePotion potion = CraftablePotion.forCombo(e.getItem1().getId(), e.getItem2().getId());
		if (potion == null)
			return;
		if (potion == CraftablePotion.ANCHOVY_OIL && !e.getPlayer().getInventory().containsItem(6097)) {
			e.getPlayer().sendMessage("You need a sieve to make anchovy oil.");
			return;
		}
		e.getPlayer().startConversation(new Conversation(e.getPlayer())
			.addNext(new MakeXStatement(new int[]{potion.getProduct().getId()}, 28))
			.addNext(() -> e.getPlayer().getActionManager().setAction(new Herblore(potion, MakeXStatement.getQuantity(e.getPlayer())))));
	});
}