package com.rs.game.model.entity.player;

import org.darkan.core.map.pathfinder.Direction;
import com.rs.game.content.Potion;
import com.rs.game.content.skills.cooking.Foods.Food;
import com.rs.game.content.skills.magic.TeleType;
import com.rs.game.model.entity.Entity;
import com.rs.game.model.entity.Hit;
import com.rs.game.model.entity.Teleport;
import com.rs.game.model.entity.npc.NPC;
import com.rs.game.model.gameobject.GameObject;
import org.darkan.core.model.GroundItem;
import org.darkan.core.model.Item;
import com.rs.utils.music.Genre;
import com.rs.utils.music.Music;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public abstract class Controller {

	@SuppressWarnings("NotNullFieldNotInitialized")
	@NotNull
	protected transient Player player;

	public final void setPlayer(@NotNull Player player) {
		this.player = player;
	}

	@NotNull
	@Contract(pure = true)
	public Player getPlayer() {
		return player;
	}

	public final void removeController() {
		player.getControllerManager().removeControllerWithoutCheck();
	}

	public abstract void start();

	public boolean canEat(Food food) {
		return true;
	}

	public boolean canTakeItem(GroundItem item) {
		return true;
	}

	public void processIncomingHit(final Hit hit) {

	}

    public boolean playAmbientMusic() {
        return true;
    }

	public boolean playAmbientStrictlyBackgroundMusic() {
		return false;
	}

    public Genre getGenre() {
        return Music.getGenre(player);
    }

    public boolean playAmbientOnControllerRegionEnter() {
        return true;
    }

	public void processOutgoingHit(final Hit hit, Entity target) {

	}

	public boolean processItemOnPlayer(Player p2, Item item, int slot) {
		return true;
	}

	public boolean canPot(Potion pot) {
		return true;
	}

	public boolean keepCombating(Entity target) {
		return true;
	}

	public boolean canEquip(int slotId, int itemId) {
		return true;
	}

	public boolean canAttack(Entity target) {
		return true;
	}

	public void trackXP(int skillId, int addedXp) {

	}

	public boolean gainXP(int skillId, double exp) {
		return true;
	}

	public boolean processButtonClick(int interfaceId, int componentId, int slotId, int slotId2, int opNum) {
		return true;
	}

	public boolean canDeleteInventoryItem(int itemId, int amount) {
		return true;
	}

	public boolean canUseItemOnItem(Item itemUsed, Item usedWith) {
		return true;
	}

	public boolean canAddInventoryItem(int itemId, int amount) {
		return true;
	}

	public boolean canPlayerOption1(Player target) {
		return true;
	}

	public boolean canPlayerOption2(Player target) {
		return true;
	}

	public boolean canPlayerOption3(Player target) {
		return true;
	}

	public boolean canPlayerOption4(Player target) {
		return true;
	}

	public void processNPCDeath(int id) {

	}

	public boolean canHit(Entity entity) {
		return true;
	}

	public void process() {

	}

	public void moved() {

	}

	public void onTeleported(TeleType type) {

	}

	public void sendInterfaces() {

	}

	public boolean useDialogueScript(Object key) {
		return true;
	}

	public boolean processTeleport(Teleport tele) {
		return true;
	}

	public boolean processObjectClick1(GameObject object) {
		return true;
	}

	public boolean processNPCClick1(NPC npc) {
		return true;
	}

	public boolean processNPCClick2(NPC npc) {
		return true;
	}

	public boolean processNPCClick3(NPC npc) {
		return true;
	}

	public boolean processObjectClick2(GameObject object) {
		return true;
	}

	public boolean processObjectClick3(GameObject object) {
		return true;
	}

	public boolean processObjectClick5(GameObject object) {
		return true;
	}

	public boolean sendDeath() {
		return true;
	}

	public boolean canMove(Direction dir) {
		return true;
	}

	public boolean checkWalkStep(int lastX, int lastY, int nextX, int nextY) {
		return true;
	}

	public boolean login() {
		return true;
	}

	public boolean logout() {
		return true;
	}

	public void forceClose() {
	}
	
	public void onRemove() {
		
	}

	public boolean processItemOnObject(GameObject object, Item item) {
		return true;
	}
	public boolean processObjectClick4(GameObject object) {
		return true;
	}

	public boolean processItemOnNPC(NPC npc, Item item) {
		return true;
	}

	public boolean canDropItem(Item item) {
		return true;
	}

	public boolean canSummonFamiliar() {
		return true;
	}

	public void processNPCDeath(NPC npc) {

	}

	public boolean canTrade() {
		return true;
	}

	public boolean allowsBotItemTransfer() {
		return false;
	}

    public boolean canDepositItem(Item item) {
		return true;
    }
}
