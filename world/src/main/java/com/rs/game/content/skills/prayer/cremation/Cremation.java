package com.rs.game.content.skills.prayer.cremation;

import com.rs.engine.dialogue.Conversation;
import com.rs.engine.dialogue.statements.MakeXStatement;
import com.rs.game.model.entity.player.Player;
import org.darkan.core.Constants;
import org.darkan.core.model.Animation;
import org.darkan.core.model.Item;
import world.gregs.voidps.type.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ItemOnItemHandler;
import com.rs.plugin.handlers.ItemOnObjectHandler;
import com.rs.plugin.handlers.LoginHandler;
import com.rs.plugin.handlers.ObjectClickHandler;
import com.rs.utils.DropSets;
import com.rs.utils.drop.DropTable;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.darkan.core.gameval.gen.Varbit;

@PluginEventHandler
public class Cremation {

	public static ItemOnItemHandler handlePyreLogCreation = new ItemOnItemHandler(new int[] { 3430, 3432, 3434, 3436 }, new int[] { 1511, 1521, 1519, 6333, 10810, 1517, 6332, 12581, 1515, 1513 }, e -> {
		PyreLog log = PyreLog.forBaseLog(e.getUsedWith(3430, 3432, 3434, 3436).getId());
		if (e.getPlayer().getSkills().getLevel(Constants.FIREMAKING) < log.level) {
			e.getPlayer().sendMessage("You need a Firemaking level of " + log.level + " to pyre these logs.");
			return;
		}
		e.getPlayer().startConversation(new Conversation(e.getPlayer())
				.addNext(new MakeXStatement(new int[] { log.itemId }, e.getPlayer().getInventory().getNumberOf(log.baseLog)))
				.addNext(() -> e.getPlayer().getActionManager().setAction(new CraftPyreLogs(log))));
	});

	public static LoginHandler unlockColumbariumStairs = new LoginHandler(e -> {
		e.getPlayer().getVars().setVarBit(Varbit.myq4_columbarium_entrance_vis, 1);
		e.getPlayer().getVars().setVarBit(Varbit.myq4_columbarium_tally_vis, 1);
	});

	public static ObjectClickHandler handleColumbariumStairs = new ObjectClickHandler(new Object[] { 30621, 30534 }, e -> e.getPlayer().useStairs(e.getObjectId() == 30621 ? Tile.of(3422, 9965, 0) : Tile.of(3425, 9899, 0)));

	public static ItemOnObjectHandler handlePyreLogSetup = new ItemOnObjectHandler(new Object[] { 4093, 30467 }, Arrays.stream(PyreLog.values()).map(log -> log.itemId).toArray(), e -> {
		PyreLog log = PyreLog.forId(e.getItem().getId());
		if (log != null) {
			e.getPlayer().getInventory().deleteItem(log.itemId, 1);
			new Pyre(e.getPlayer(), e.getObject(), log, e.getObjectId() == 4093).createReplace();
			e.getPlayer().setNextAnimation(new Animation(833));
		}
	});

	public static ItemOnObjectHandler handlePyreLogCorpse = new ItemOnObjectHandler(new Object[] { 4094, 4095, 4096, 4097, 4098, 9006, 9007, 21271, 29166, 29181, 30468, 30469, 30470, 30471, 30472, 30473, 30474, 30475, 30476, 30477 }, Arrays.stream(Corpse.values()).flatMap(corpse -> Arrays.stream(corpse.itemIds).boxed()).collect(Collectors.toList()).toArray(), e -> {
		if (!(e.getObject() instanceof Pyre pyre))
			return;
		Corpse corpse = Corpse.forId(e.getItem().getId());
		if (corpse == null) {
			e.getPlayer().sendMessage("Nothing interesting happens.");
			return;
		}
        if (!pyre.ownedBy(e.getPlayer())) {
			e.getPlayer().sendMessage("That's not your pyre!");
			return;
		}
		if (!pyre.setCorpse(corpse))
			e.getPlayer().sendMessage("The logs aren't strong enough to burn this type of corpse.");
		else {
			e.getPlayer().getInventory().deleteItem(e.getItem().getId(), 1);
			e.getPlayer().setNextAnimation(new Animation(833));
		}
	});

	public static ObjectClickHandler handleLightPyre = new ObjectClickHandler(new Object[] { 4100, 4101, 4102, 4103, 4104, 9008, 9009, 21272, 29167, 29182, 30478, 30479, 30480, 30481, 30482, 30483, 30484, 30485, 30486, 30487 }, e -> {
		if (!(e.getObj() instanceof Pyre pyre))
			return;
        if (!pyre.ownedBy(e.getPlayer())) {
			e.getPlayer().sendMessage("That's not your pyre!");
			return;
		}
		pyre.light(e.getPlayer());
	});

	public static ObjectClickHandler handleColumbariumKey = new ObjectClickHandler(new Object[] { 30537, 30538 }, e -> {
		if (!e.getPlayer().getInventory().containsItem(13158)) {
			e.getPlayer().sendMessage("It looks securely locked.");
			return;
		}
		openRecess(e.getPlayer());
	});

	public static ItemOnObjectHandler handleColumbariumKeyOnRecess = new ItemOnObjectHandler(new Object[] { 30537, 30538 }, new Object[] { 13158 }, e -> openRecess(e.getPlayer()));

	public static void openRecess(Player player) {
		player.lock(2);
		player.getInventory().deleteItem(13158, 1);
		player.setNextAnimation(new Animation(833));
		for (Item item : DropTable.calculateDrops(player, DropSets.getDropSet("columbarium_safe")))
			if (item != null)
				player.getInventory().addItemDrop(item);
	}

}
