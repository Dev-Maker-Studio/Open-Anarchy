package com.rs.game.content.pets;

import org.darkan.core.cache.ItemDefinitions;
import org.darkan.core.cache.NPCDefinitions;
import com.rs.game.content.ItemConstants;
import com.rs.game.model.entity.ForceTalk;
import com.rs.game.model.entity.player.Player;
import org.darkan.core.Constants;
import org.darkan.core.model.Animation;
import org.darkan.core.model.Item;
import world.gregs.voidps.type.Tile;

import java.util.HashMap;
import java.util.Map;

public final class PetManager {

	private final Map<Integer, PetDetails> petDetails = new HashMap<>();

	private transient Player player;

	private int npcId;

	private int itemId;

	private String trollBabyName;

	public PetManager() {
	}

	public boolean spawnPet(int itemId, boolean deleteItem) {
		Pets pets = Pets.forId(itemId);
		if (pets == null)
			return false;
		if (player.getPet() != null || player.getFamiliar() != null) {
			player.sendMessage("You already have a follower.");
			return true;
		}
		if (!hasRequirements(player, pets)) {
			player.sendMessage("You need a Summoning level of at least " + pets.getSummoningLevel() + " to summon this pet.");
			return true;
		}
		int baseItemId = pets.getBabyItemId();
        PetDetails details = petDetails.computeIfAbsent(baseItemId, k -> new PetDetails(pets.getGrowthRate() == 0.0 ? 100.0 : 0.0));
        int id = pets.getItemId(details.getStage());
		if (itemId != id) {
			player.sendMessage("This is not the right pet, grow the pet correctly.");
			return true;
		}
		int npcId = pets.getNpcId(details.getStage());
		if (npcId > 0) {
			Tile spawnTile = player.getNearestTeleTile(NPCDefinitions.getDefs(npcId, player.getVars()).getSize());
			if (spawnTile == null) {
				player.sendMessage("There's no space to summon your pet.");
				return true;
			}
			Pet pet = new Pet(npcId, itemId, player, spawnTile, pets.getGrownNpcId() == -1 && pets.getOvergrownNpcId() == -1 ? null : details);
			this.npcId = npcId;
			this.itemId = itemId;
			pet.setGrowthRate(pets.getGrowthRate());
			player.setPet(pet);
			if (deleteItem) {
				player.setNextAnimation(new Animation(827));
				player.getInventory().deleteItem(itemId, 1);
			}
			return true;
		}
		return true;
	}

	private boolean hasRequirements(Player player, Pets pet) {
		return player.getSkills().getLevel(Constants.SUMMONING) >= pet.getSummoningLevel();
	}

	public void init() {
		if (npcId > 0 && itemId > 0)
			spawnPet(itemId, false);
	}

	public void eat(int foodId, Pet npc) {
		if (npc != player.getPet()) {
			player.sendMessage("This isn't your pet!");
			return;
		}
		Pets pets = Pets.forId(itemId);
		if (pets == null)
			return;
		if (pets == Pets.TROLL_BABY) {
			if (!ItemConstants.isTradeable(new Item(foodId))) {
				player.sendMessage("Your troll baby won't eat this item.");
				return;
			}
			player.sendOptionDialogue("Are you sure you want to feed your " + ItemDefinitions.getDefs(foodId).getName() + " to the troll?", ops -> {
				ops.add("Yes, I am sure. I know I won't get it back.", () -> {
					if (!player.getInventory().containsItem(foodId))
						return;
					trollBabyName = ItemDefinitions.getDefs(foodId).getName();
					npc.setName(trollBabyName);
					npc.setNextForceTalk(new ForceTalk("YUM! Me likes " + trollBabyName + "!"));
					player.getInventory().deleteItem(foodId, 1);
					player.sendMessage("Your pet happily eats the " + ItemDefinitions.getDefs(foodId).getName() + ".");
				});
				ops.add("Nevermind.");
			});
			return;
		}
		for (int food : pets.getFood())
			if (food == foodId) {
				player.getInventory().deleteItem(food, 1);
				player.sendMessage("Your pet happily eats the " + ItemDefinitions.getDefs(food).getName() + ".");
				player.setNextAnimation(new Animation(827));
				npc.getDetails().updateHunger(-15.0);
				return;
			}
		player.sendMessage("Nothing interesting happens.");
	}

	public void removeDetails(int itemId) {
		Pets pets = Pets.forId(itemId);
		if (pets == null)
			return;
		petDetails.remove(pets.getBabyItemId());
	}

	public Player getPlayer() {
		return player;
	}

	public void setPlayer(Player player) {
		this.player = player;
	}

	public int getNpcId() {
		return npcId;
	}

	public void setNpcId(int npcId) {
		this.npcId = npcId;
	}

	public int getItemId() {
		return itemId;
	}

	public void setItemId(int itemId) {
		this.itemId = itemId;
	}

	public String getTrollBabyName() {
		return trollBabyName;
	}

	public void setTrollBabyName(String trollBabyName) {
		this.trollBabyName = trollBabyName;
	}

}