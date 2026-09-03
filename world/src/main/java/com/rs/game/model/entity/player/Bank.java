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
package com.rs.game.model.entity.player;

import com.rs.Settings;
import org.darkan.core.cache.EnumDefinitions;
import org.darkan.core.cache.ItemDefinitions;
import world.gregs.voidps.cache.definition.data.ItemDefinition;
import org.darkan.core.model.IFEvents;
import org.darkan.core.model.UseFlag;
import com.rs.game.content.Potion;
import com.rs.game.content.skills.cooking.Foods;
import com.rs.game.content.skills.runecrafting.Runecrafting;
import com.rs.game.content.skills.summoning.Familiar;
import com.rs.game.model.entity.player.managers.InterfaceManager.Sub;
import org.darkan.core.model.Item;
import org.darkan.core.Utils;
import com.rs.plugin.PluginManager;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.events.ItemAddedToBankEvent;
import com.rs.plugin.events.ItemClickEvent;
import com.rs.plugin.handlers.ButtonClickHandler;
import com.rs.plugin.handlers.ItemAddedToBankHandler;
import com.rs.utils.ItemConfig;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.darkan.core.gameval.gen.Varbit;
import org.darkan.core.gameval.gen.Varp;

@PluginEventHandler
public class Bank {
	public static Set<Integer> BLOCKED_BANK_ITEMS = new IntOpenHashSet(List.of(2528, 4447, 6543, 6796, 7498, 10586, 10889, 11137, 11139, 11141, 11157, 11185, 11186, 11187, 11188, 11189, 11679, 11753, 11754, 11755, 12627, 12628, 13227, 13439, 13446, 13447, 13448, 13463, 14574, 14575, 14576, 14580, 14581, 14582, 14633, 14634, 14635, 14711, 14740, 15346, 15348, 15350, 15351, 15389, 15390, 18782, 18783, 18808, 19750, 19751, 19752, 19755, 19756, 19758, 19759, 19761, 19764, 19765, 19767, 19768, 19775, 21770, 21771, 21772, 23085, 23086, 23087, 23088, 23089, 23090, 23645, 23648, 24138, 24139, 24140, 24141, 24142, 24143, 24151, 24300, 24503, 24504, 24506, 25070, 25119, 25131, 25131, 25132, 25133, 20935, 20936, 20960));

	static {
		for (int id : Utils.range(23713, 23771))
			BLOCKED_BANK_ITEMS.add(id);
		for (int id : Utils.range(23773, 23817))
			BLOCKED_BANK_ITEMS.add(id);
	}

	public static ItemAddedToBankHandler blockXpItems = new ItemAddedToBankHandler(BLOCKED_BANK_ITEMS.toArray(), e -> {
		e.getPlayer().sendMessage("A magical force prevents you from banking this item.");
		e.cancel();
	});

	private static final long PIN_VALIDITY_TIME = 6 * 60 * 60 * 1000; //6 hours

	private Item[][] bankTabs;
	private short bankPin;
	private int lastX;
	/**
	 * Whether withdrawing the last of an item leaves a placeholder - the same id with amount 0 -
	 * in its slot instead of releasing the slot.
	 *
	 * Persisted with the bank, and deliberately never consulted when a placeholder is released or
	 * moved: turning the setting off keeps the placeholders that already exist, exactly as it does
	 * in the live game.
	 */
	private boolean placeholders;

	private transient Player player;
	private transient int currentTab;
	private transient Item[] lastContainerCopy;
	private transient boolean withdrawNotes;
	private transient boolean insertItems;
	private transient boolean sessionPin;
	private Map<String, Long> enteredPin;

	public static final int MAX_BANK_SIZE = 800;

	/** Interface 762's two placeholder buttons: the toggle frame, and the release-all frame. */
	private static final int PLACEHOLDER_TOGGLE_COMPONENT = 124;
	private static final int RELEASE_ALL_COMPONENT = 126;
	/** The bank's toggle-frame sprites, the same pair the note and insert toggles use. */
	private static final int TOGGLE_SPRITE_OFF = 1431;
	private static final int TOGGLE_SPRITE_ON = 1433;
	/** Right-click op index of "Release" on the bank's item component (762:95). */
	private static final int OP_RELEASE = 9;

	public Bank() {
		bankTabs = new Item[1][0];
	}

	public static ButtonClickHandler handleDepositBox = new ButtonClickHandler(11, e -> {
		if (e.getComponentId() == 17) {
			if (e.getOpNum() == 1)
				e.getPlayer().getBank().depositItem(e.getSlotId(), 1, false);
			else if (e.getOpNum() == 2)
				e.getPlayer().getBank().depositItem(e.getSlotId(), 5, false);
			else if (e.getOpNum() == 3)
				e.getPlayer().getBank().depositItem(e.getSlotId(), 10, false);
			else if (e.getOpNum() == 4)
				e.getPlayer().getBank().depositItem(e.getSlotId(), Integer.MAX_VALUE, false);
			else if (e.getOpNum() == 5)
				e.getPlayer().sendInputInteger("How many would you like to deposit?", (integer) -> e.getPlayer().getBank().depositItem(e.getSlotId(), integer, true));
			else if (e.getOpNum() == 6)
				e.getPlayer().getInventory().sendExamine(e.getSlotId());
		} else if (e.getComponentId() == 18)
			e.getPlayer().getBank().depositAllInventory(false);
		else if (e.getComponentId() == 22)
			e.getPlayer().getBank().depositAllEquipment(false);
	});

	public static ButtonClickHandler handleBankButtons = new ButtonClickHandler(762, e -> {
		if (e.getPlayer().getTempAttribs().getB("viewingOtherBank"))
			return;
		if (e.getComponentId() == 15)
			e.getPlayer().getBank().switchInsertItems();
		else if (e.getComponentId() == 19)
			e.getPlayer().getBank().switchWithdrawNotes();
		else if (e.getComponentId() == PLACEHOLDER_TOGGLE_COMPONENT)
			e.getPlayer().getBank().togglePlaceholders();
		else if (e.getComponentId() == RELEASE_ALL_COMPONENT)
			e.getPlayer().getBank().promptReleaseAllPlaceholders();
		else if (e.getComponentId() == 33)
			e.getPlayer().getBank().depositAllInventory(true);
		else if (e.getComponentId() == 37)
			e.getPlayer().getBank().depositAllEquipment(true);
		else if (e.getComponentId() == 39)
			e.getPlayer().getBank().depositAllBob(true);
		else if (e.getComponentId() == 35) {
			int amount = e.getPlayer().getInventory().getPouchCoinsAsInt();
			if (amount <= 0)
				return;
			Item bankCoins = e.getPlayer().getBank().getItem(995);
			int bankAmt = bankCoins == null ? 0 : bankCoins.getAmount();
			if (amount + bankAmt <= 0)
				amount = Integer.MAX_VALUE - bankAmt;
			if (amount == 0) {
				e.getPlayer().sendMessage("You can't deposit any coins.");
				return;
			}
			e.getPlayer().getInventory().removeCoins(amount, true);
			e.getPlayer().getBank().addItem(new Item(995, amount), true);
		} else if (e.getComponentId() == 46) {
			e.getPlayer().closeInterfaces();
			e.getPlayer().getInterfaceManager().sendInterface(767);
			e.getPlayer().setCloseInterfacesEvent(() -> {
				e.getPlayer().abortDialogue();
				e.getPlayer().getPackets().sendRunScript(571);
			});
		} else if (e.getComponentId() >= 46 && e.getComponentId() <= 64) {
			int tabId = 9 - ((e.getComponentId() - 46) / 2);
			if (e.getOpNum() == 1)
				e.getPlayer().getBank().setCurrentTab(tabId);
			else if (e.getOpNum() == 2)
				e.getPlayer().getBank().collapse(tabId);
		} else if (e.getComponentId() == 95) {
			if (e.getOpNum() == 1)
				e.getPlayer().getBank().withdrawItem(e.getSlotId(), 1);
			else if (e.getOpNum() == 2)
				e.getPlayer().getBank().withdrawItem(e.getSlotId(), 5);
			else if (e.getOpNum() == 3)
				e.getPlayer().getBank().withdrawItem(e.getSlotId(), 10);
			else if (e.getOpNum() == 4)
				e.getPlayer().getBank().withdrawLastAmount(e.getSlotId());
			else if (e.getOpNum() == 5)
				e.getPlayer().sendInputInteger("How many would you like to withdraw?", amount -> {
					if (amount < 0)
						return;
					e.getPlayer().getBank().setLastX(amount);
					e.getPlayer().getBank().refreshLastX();
					e.getPlayer().getBank().withdrawItem(e.getSlotId(), amount);
				});
			else if (e.getOpNum() == 6)
				e.getPlayer().getBank().withdrawItem(e.getSlotId(), Integer.MAX_VALUE);
			else if (e.getOpNum() == 7)
				e.getPlayer().getBank().withdrawItemButOne(e.getSlotId());
			else if (e.getOpNum() == OP_RELEASE)
				e.getPlayer().getBank().releasePlaceholder(e.getSlotId());
			else if (e.getOpNum() == 10)
				e.getPlayer().getBank().sendExamine(e.getSlotId());

		} else if (e.getComponentId() == 119)
			Equipment.openEquipmentBonuses(e.getPlayer(), true);
	});

	public static ButtonClickHandler handleInvButtons = new ButtonClickHandler(763, e -> {
		if (e.getPlayer().getTempAttribs().getB("viewingOtherBank"))
			return;
		if (e.getComponentId() == 0)
			if (e.getOpNum() == 1)
				e.getPlayer().getBank().depositItem(e.getSlotId(), 1, true);
			else if (e.getOpNum() == 2)
				e.getPlayer().getBank().depositItem(e.getSlotId(), 5, true);
			else if (e.getOpNum() == 3)
				e.getPlayer().getBank().depositItem(e.getSlotId(), 10, true);
			else if (e.getOpNum() == 4)
				e.getPlayer().getBank().depositLastAmount(e.getSlotId());
			else if (e.getOpNum() == 5)
				e.getPlayer().sendInputInteger("How many would you like to deposit?", amount -> {
					if (amount < 0)
						return;
					e.getPlayer().getBank().setLastX(amount);
					e.getPlayer().getBank().refreshLastX();
					e.getPlayer().getBank().depositItem(e.getSlotId(), amount, !e.getPlayer().getInterfaceManager().topOpen(11));
				});
			else if (e.getOpNum() == 6)
				e.getPlayer().getBank().depositItem(e.getSlotId(), Integer.MAX_VALUE, true);
			else if (e.getOpNum() == 8) {
				Item item = e.getPlayer().getInventory().getItem(e.getSlotId());
				if (item == null)
					return;
				Potion potion = Potion.forId(item.getId());
				if (Foods.Food.forId(item.getId()) != null)
					Foods.eat(e.getPlayer(), item, e.getSlotId(), null);
				else if (potion != null)
					potion.drink(e.getPlayer(), item.getId(), e.getSlotId());
				else
					PluginManager.handle(new ItemClickEvent(e.getPlayer(), item, e.getSlotId(), item.getDefinitions().getInventoryOption(1)));
			} else if (e.getOpNum() == 10)
				e.getPlayer().getInventory().sendExamine(e.getSlotId());
			else
				e.getPlayer().sendMessage(String.valueOf(e.getOpNum()));
	});

	/**
	 * Removes every entry with this id, placeholders included.
	 *
	 * This is an explicit "take it back" (the loyalty shop, the barbarian guard's bar-crawl card),
	 * not a withdrawal, so it passes forceDestroy and never leaves a placeholder behind.
	 */
	public void removeItem(int id) {
		if (bankTabs != null)
			for (int i = 0; i < bankTabs.length; i++)
				for (int i2 = 0; i2 < bankTabs[i].length; i2++)
					if (bankTabs[i][i2].getId() == id)
						removeItem(new int[] { i, i2 }, Integer.MAX_VALUE, true, true);
	}

	public void deleteItem(int itemId, int amount) {
		if (bankTabs != null)
			for (Item[] bankTab : bankTabs)
				for (Item element : bankTab)
					// A placeholder holds nothing, so there is nothing here to delete: skipping it
					// also keeps setAmount's negative guard from being the only thing standing
					// between a subtraction and a real stack.
					if (element.getId() == itemId && !isPlaceholder(element)) {
						element.setAmount(element.getAmount() - amount);
						refreshItems();
					}
	}

	/** Whether the bank holds at least [amount] of this item. Placeholders hold nothing. */
	public boolean containsItem(int itemId, int amount) {
		if (bankTabs != null)
			for (Item[] bankTab : bankTabs)
				for (Item element : bankTab)
					if (element.getId() == itemId && !isPlaceholder(element))
						if (element.getAmount() >= amount)
							return true;
		return false;
	}

	/**
	 * Whether the bank holds this item.
	 *
	 * A placeholder is not the item - roughly forty quest and content callers read this as "the
	 * player has one", so an empty slot that merely remembers the id must answer false.
	 */
	public boolean containsItem(int itemId) {
		if (bankTabs != null) {
			for (Item[] bankTab : bankTabs) {
				for (Item element : bankTab) {
					if (element.getId() == itemId && !isPlaceholder(element)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public void setPlayer(Player player) {
		this.player = player;
		if (bankTabs == null || bankTabs.length == 0)
			bankTabs = new Item[1][0];
		// A save written before placeholders existed can carry a negative amount from an
		// over-subtraction (Item.setAmount ignores negatives, it does not clamp them). Normalise
		// those to a placeholder; nothing else about a loaded bank is rewritten, because turning
		// the setting off must not delete the placeholders a player already has.
		for (Item[] tab : bankTabs)
			for (Item item : tab)
				if (item != null && item.getAmount() < 0)
					item.setAmount(0);
	}

	public boolean sortCurrentTab() {
		if (currentTab < 0 || currentTab >= bankTabs.length)
			return false;
		Item[] tab = bankTabs[currentTab];
		Arrays.sort(tab, (o1, o2) -> {
			int o1Id = o1 == null ? Integer.MAX_VALUE : o1.getId();
			int o2Id = o2 == null ? Integer.MAX_VALUE : o2.getId();
			return o1Id - o2Id;
		});
		refreshItems();
		refreshTabs();
		refreshViewingTab();
		return true;
	}

	@SuppressWarnings("null")
	public void setItem(int slotId, int amt) {
		Item item = getItem(slotId);
		if (item == null) {
			item.setAmount(amt);
			refreshItems();
			refreshTabs();
			refreshViewingTab();
		}
	}

	public void refreshTabs(Player player) {
		for (int slot = 1; slot < 9; slot++)
			refreshTab(player, slot);
	}

	public void refreshTabs() {
		for (int slot = 1; slot < 9; slot++)
			refreshTab(slot);
	}

	public int getTabSize(int slot) {
		if (slot >= bankTabs.length)
			return 0;
		return bankTabs[slot].length;
	}

	public int getTabSize(Player other, int slot) {
		if (slot >= other.getBank().getBankTabs().length)
			return 0;
		return other.getBank().getBankTabs()[slot].length;
	}

	public Item[][] getBankTabs() {
		return bankTabs;
	}

	public void withdrawLastAmount(int bankSlot) {
		withdrawItem(bankSlot, lastX);
	}

	public void withdrawItemButOne(int fakeSlot) {
		int[] fromRealSlot = getRealSlot(fakeSlot);
		Item item = getItem(fromRealSlot);
		if (item == null)
			return;
		// Nothing is ever handed out of a placeholder, whichever withdraw op the client sent.
		if (isPlaceholder(item))
			return;
		if (item.getAmount() <= 1) {
			player.sendMessage("You only have one of this item in your bank");
			return;
		}
		withdrawItem(fakeSlot, item.getAmount() - 1);
	}

	public void depositLastAmount(int bankSlot) {
		depositItem(bankSlot, lastX, true);
	}

	public void depositAllInventory(boolean banking) {
		if (player.getTempAttribs().getB("viewingOtherBank"))
			return;
		if (Bank.MAX_BANK_SIZE - getBankSize() < player.getInventory().getItems().getSize()) {
			player.sendMessage("Not enough space in your bank.");
			return;
		}
		for (int i = 0; i < 28; i++) {
			depositItem(i, Integer.MAX_VALUE, true);
			refreshTab(currentTab);
			refreshItems();
		}
	}

	public void depositAllBob(boolean banking) {
		if (player.getTempAttribs().getB("viewingOtherBank"))
			return;
		Familiar familiar = player.getFamiliar();
		if (familiar == null || familiar.getInventory() == null)
			return;
		for (int i = 0; i < familiar.getInventory().getSize(); i++) {
			Item item = familiar.getInventory().get(i);
			if (item == null) continue;
			int originalId = unnote(item);
			Item bankedItem = getItem(item.getId());
			if (bankedItem != null) {
				if (bankedItem.getAmount() + item.getAmount() <= 0) {
					item.setAmount(Integer.MAX_VALUE - bankedItem.getAmount());
					player.sendMessage("Not enough space in your bank.");
				}
			} else if (!hasBankSpace()) {
				player.sendMessage("Not enough space in your bank.");
				continue;
			}
			if (addItem(new Item(originalId, item.getAmount(), item.getMetaData()), banking))
				familiar.getInventory().set(i, null);
		}
	}

	public void depositAllEquipment(boolean banking) {
		if (player.getTempAttribs().getB("viewingOtherBank"))
			return;
		for (int i = 0;i < Equipment.SIZE;i++) {
			Item prev = player.getEquipment().setSlot(i, null);
			if (prev == null || prev.getId() == -1)
				continue;
			if (!addItem(prev, banking)) {
				player.sendMessage("Not enough space in your bank.");
				break;
			}
		}
		player.getAppearance().generateAppearanceData();
	}

	public void collapse(int tabId) {
		if (tabId == 0 || tabId >= bankTabs.length)
			return;
		// The whole tab goes at once rather than one removeItem(getItemSlot(id)) per entry: that
		// lookup finds the *first* entry with the id, which - now that a placeholder and a real
		// stack of one id can coexist, as two metadata stacks always could - may live in another
		// tab, and the re-add below would then merge the collapsed item into the copy that was
		// never removed.
		Item[] items = bankTabs[tabId];
		destroyTab(tabId);
		for (Item item : items)
			addItem(item, 0, false);
		refreshTabs();
		refreshItems();
	}

	public void switchItem(int fromSlot, int toSlot, int fromComponentId, int toComponentId) {
		if (toSlot == -1) {
			int toTab = toComponentId >= 76 ? 8 - (84 - toComponentId) : 9 - ((toComponentId - 46) / 2);
			if (toTab < 0 || toTab > 9)
				return;
			if (bankTabs.length == toTab) {
				int[] fromRealSlot = getRealSlot(fromSlot);
				if (fromRealSlot == null)
					return;
				if (toTab == fromRealSlot[0]) {
					switchItem(fromSlot, getStartSlot(toTab));
					return;
				}
				Item item = getItem(fromRealSlot);
				if (item == null)
					return;
				removeItem(fromSlot, item.getAmount(), false, true);
				createTab();
				bankTabs[bankTabs.length - 1] = new Item[] { item };
				refreshTab(fromRealSlot[0]);
				refreshTab(toTab);
				refreshItems();
			} else if (bankTabs.length > toTab) {
				int[] fromRealSlot = getRealSlot(fromSlot);
				if (fromRealSlot == null)
					return;
				if (toTab == fromRealSlot[0]) {
					switchItem(fromSlot, getStartSlot(toTab));
					return;
				}
				Item item = getItem(fromRealSlot);
				if (item == null)
					return;
				boolean removed = removeItem(fromSlot, item.getAmount(), false, true);
				if (!removed)
					refreshTab(fromRealSlot[0]);
				else if (fromRealSlot[0] != 0 && toTab >= fromRealSlot[0])
					toTab -= 1;
				refreshTab(fromRealSlot[0]);
				addItem(item, toTab, true);
			}
		} else if (!insertItems)
			switchItem(fromSlot, toSlot);
		else
			insert(fromSlot, toSlot);
	}

	public void insert(int fromSlot, int toSlot) {
		int[] fromRealSlot = getRealSlot(fromSlot);
		Item fromItem = getItem(fromRealSlot);
		if (fromItem == null)
			return;

		int[] toRealSlot = getRealSlot(toSlot);
		Item toItem = getItem(toRealSlot);
		if ((toItem == null) || (toRealSlot[0] != fromRealSlot[0]))
			return;

		if (toRealSlot[1] > fromRealSlot[1])
			for (int slot = fromRealSlot[1]; slot < toRealSlot[1]; slot++) {
				Item temp = bankTabs[toRealSlot[0]][slot];
				bankTabs[toRealSlot[0]][slot] = bankTabs[toRealSlot[0]][slot + 1];
				bankTabs[toRealSlot[0]][slot + 1] = temp;
			}
		else if (fromRealSlot[1] > toRealSlot[1])
			for (int slot = fromRealSlot[1]; slot > toRealSlot[1]; slot--) {
				Item temp = bankTabs[toRealSlot[0]][slot];
				bankTabs[toRealSlot[0]][slot] = bankTabs[toRealSlot[0]][slot - 1];
				bankTabs[toRealSlot[0]][slot - 1] = temp;
			}
		refreshItems();
	}

	public void switchItem(int fromSlot, int toSlot) {
		int[] fromRealSlot = getRealSlot(fromSlot);
		Item fromItem = getItem(fromRealSlot);
		if (fromItem == null)
			return;
		int[] toRealSlot = getRealSlot(toSlot);
		Item toItem = getItem(toRealSlot);
		if (toItem == null)
			return;
		bankTabs[fromRealSlot[0]][fromRealSlot[1]] = toItem;
		bankTabs[toRealSlot[0]][toRealSlot[1]] = fromItem;
		refreshTab(fromRealSlot[0]);
		if (fromRealSlot[0] != toRealSlot[0])
			refreshTab(toRealSlot[0]);
		refreshItems();
	}

	public void openDepositBox() {
		player.getTempAttribs().setB("viewingOtherBank", false);
		player.getTempAttribs().setB("viewingDepositBox", true);
		player.getInterfaceManager().sendInterface(11);
		player.getInterfaceManager().removeSubs(Sub.TAB_INVENTORY, Sub.TAB_EQUIPMENT);
		player.getInterfaceManager().openTab(Sub.TAB_FRIENDS);
		sendBoxInterItems();
		player.getPackets().setIFText(11, 13, "Bank Of " + Settings.getConfig().getServerName() + " - Deposit Box");
		player.setCloseInterfacesEvent(() -> {
			player.getPackets().sendRunScript(571);
			player.abortDialogue();
			player.getInterfaceManager().sendSubDefaults(Sub.TAB_INVENTORY, Sub.TAB_EQUIPMENT);
			player.getInterfaceManager().openTab(Sub.TAB_INVENTORY);
			player.getTempAttribs().setB("viewingDepositBox", false);
			Familiar.sendLeftClickOption(player);
		});
	}

	public void sendBoxInterItems() {
		player.getPackets().sendInterSetItemsOptionsScript(11, 17, 93, 6, 5, "Deposit-1", "Deposit-5", "Deposit-10", "Deposit-All", "Deposit-X", "Examine");
		player.getPackets().setIFRightClickOps(11, 17, 0, 27, 0, 1, 2, 3, 4, 5);
	}

	public void setPin(byte num1, byte num2, byte num3, byte num4) {
		bankPin = (short) ((num1 << 12) + (num2 << 8) + (num3 << 4) + num4);
	}

	public boolean pinCorrect(byte num1, byte num2, byte num3, byte num4) {
		return bankPin == (short) ((num1 << 12) + (num2 << 8) + (num3 << 4) + num4);
	}

	public void openPinSettings() {
		if (!checkPin())
			return;
		player.endConversation();
		player.getTempAttribs().setB("settingPin", false);
		player.getTempAttribs().setB("cancellingPin", false);
		player.getTempAttribs().setB("changingPin", false);
		player.getPackets().sendVarc(98, bankPin == 0 ? 0 : 1);
		player.getPackets().sendVarcString(344, "Please set a secure pin.<br>0, 0, 0, 0 will be considered as no pin.");
		player.getInterfaceManager().sendInterface(14);
	}

	public void sendPinConfirmScreen(String question, String op1, String op2) {
		player.getPackets().sendVarc(98, -1);
		player.getPackets().setIFText(14, 32, question);
		player.getPackets().setIFText(14, 34, op1);
		player.getPackets().setIFText(14, 36, op2);
		player.getInterfaceManager().sendInterface(14);
	}

	public void confirmSetPin() {
		player.getTempAttribs().setB("settingPin", true);
		sendPinConfirmScreen("Do you really wish to set a PIN on your bank account?", "Yes, I really want a bank PIN. I will never forget it!", "No, I might forget it!");
	}

	public void confirmCancelPin() {
		player.getTempAttribs().setB("cancellingPin", true);
		sendPinConfirmScreen("Do you still want your PIN?", "Yes, I asked to cancel my PIN.", "No, I didn't ask to cancel my PIN.");
	}

	public void confirmChangePin() {
		player.getTempAttribs().setB("changingPin", true);
		sendPinConfirmScreen("Do you really want to change your PIN?", "Yes, I asked to change my PIN.", "No, I didn't ask to change my PIN.");
	}

	public void openPin() {
		player.getTempAttribs().setI("pinStage", 0);
		player.getTempAttribs().setI("enteredPin", 0);
		player.getTempAttribs().setI("prevPin", -1);
		player.getPackets().setIFText(13, 27, "Bank of " + Settings.getConfig().getServerName());
		player.getInterfaceManager().sendInterface(13);
		player.getInterfaceManager().sendSubSpecific(false, 13, 5, 759);
		player.getPackets().sendVarc(98, bankPin == 0 ? 0 : 1);
		player.getVars().setVarBit(Varbit.bankpin_counter, 1, true);
		player.getVars().syncVarsToClient();
		player.getVars().setVarBit(Varbit.bankpin_counter, player.getTempAttribs().getI("pinStage"), true);
		player.getVars().syncVarsToClient();
		player.getPackets().sendRunScriptBlank(1107);
	}

	public static ButtonClickHandler handlePin = new ButtonClickHandler(new Object[] { 13, 14, 759 }, e -> {
		switch(e.getInterfaceId()) {
		case 14:
			switch(e.getComponentId()) {
			case 18:
				if (e.getPlayer().getBank().bankPin == 0)
					e.getPlayer().getBank().confirmSetPin();
				else
					e.getPlayer().getBank().confirmChangePin();
				break;
			case 33:
				if (e.getPlayer().getBank().bankPin == 0)
					e.getPlayer().getBank().openPin();
				else if (e.getPlayer().getTempAttribs().getB("changingPin")) {
					e.getPlayer().getBank().bankPin = 0;
					e.getPlayer().sendMessage("Your pin has been removed. Please set a new one.");
					e.getPlayer().getBank().openPin();
				} else {
					e.getPlayer().getBank().bankPin = 0;
					e.getPlayer().sendMessage("Your pin has been removed.");
				}
				break;
			case 19:
				e.getPlayer().getBank().confirmCancelPin();
				break;
			case 35:
				e.getPlayer().getBank().openPinSettings();
				break;
			default:
				e.getPlayer().getBank().openPinSettings();
				break;
			}
			break;
		case 13:
			break;
		case 759:
			int num = (e.getComponentId() / 4) - 1;
			e.getPlayer().getTempAttribs().setI("enteredPin", e.getPlayer().getTempAttribs().getI("enteredPin") + (num << ((3 - e.getPlayer().getTempAttribs().getI("pinStage")) * 4)));
			e.getPlayer().getTempAttribs().incI("pinStage");
			if (e.getPlayer().getTempAttribs().getI("pinStage") == 3)
				for (Object ifComp : EnumDefinitions.getEnum(3554).getValues().values())
					e.getPlayer().getPackets().setIFHidden(Utils.interfaceIdFromHash((int) ifComp), Utils.componentIdFromHash((int) ifComp), false);
			else if (e.getPlayer().getTempAttribs().getI("pinStage") == 4) {
				e.getPlayer().getVars().setVarBit(Varbit.bankpin_counter, 4, true);
				e.getPlayer().getVars().syncVarsToClient();
				if (e.getPlayer().getBank().bankPin == 0) {
					if (e.getPlayer().getTempAttribs().getI("prevPin", -1) == -1) {
						e.getPlayer().getPackets().setIFText(13, 26, "Please repeat that PIN again.");
						int prev = e.getPlayer().getTempAttribs().getI("enteredPin");
						e.getPlayer().getBank().openPin();
						e.getPlayer().getTempAttribs().setI("prevPin", prev);
						e.getPlayer().getTempAttribs().setI("enteredPin", 0);
						e.getPlayer().getTempAttribs().setI("pinStage", 0);
						e.getPlayer().getVars().setVarBit(Varbit.bankpin_counter, 0, true);
						e.getPlayer().getVars().syncVarsToClient();
					} else if (e.getPlayer().getTempAttribs().getI("prevPin") != e.getPlayer().getTempAttribs().getI("enteredPin")) {
						e.getPlayer().sendMessage("The PIN you entered did not match the first one.");
						e.getPlayer().closeInterfaces();
						e.getPlayer().getBank().openPinSettings();
					} else {
						e.getPlayer().getBank().bankPin = (short) e.getPlayer().getTempAttribs().getI("prevPin");
						e.getPlayer().sendMessage("Your PIN has been set.");
						e.getPlayer().closeInterfaces();
					}
				} else {
					if (e.getPlayer().getTempAttribs().getI("enteredPin") != e.getPlayer().getBank().bankPin)
						e.getPlayer().sendMessage("That PIN was incorrect.");
					else {
						e.getPlayer().sendMessage("You have entered your PIN successfully.");
						e.getPlayer().getBank().setEnteredPIN();
					}
					e.getPlayer().closeInterfaces();
				}
			}
			break;
		}
	});

	public void open() {
		if (!checkPin())
			return;
		player.getTempAttribs().removeB("viewingOtherBank");
		player.getVars().setVar(Varp.swansong_temp, 0);
		player.getVars().setVarBit(Varbit.bank_show_equipscreen, 0);
		refreshTabs();
		refreshViewingTab();
		refreshLastX();
		refreshTab(currentTab);
		player.getVars().syncVarsToClient();
		player.getInterfaceManager().sendInterface(762);
		player.getInterfaceManager().sendInventoryInterface(763);
		player.getPackets().sendRunScript(2319);
		player.getPackets().setIFText(762, 47, "Bank of " + Settings.getConfig().getServerName());
		refreshOpVars();
		unlockButtons();
		refreshPlaceholderButton();
		sendItems();
		refreshItems();
		player.setCloseInterfacesEvent(() -> {
			player.getPackets().sendRunScript(571);
			player.abortDialogue();
			Familiar.sendLeftClickOption(player);
		});
	}

	public void openBankOther(Player other) {
		player.getVars().setVarBit(Varbit.bank_show_equipscreen, 0);
		player.getVars().syncVarsToClient();
		player.getInterfaceManager().sendInterface(762);
		player.getPackets().sendRunScript(2319);
		refreshTabs(other);
		sendItemsOther(other);
		unlockButtons();
		player.getPackets().sendItems(93, other.getInventory().getItems());
		player.getPackets().sendItems(94, other.getEquipment().getItemsCopy());
		player.getTempAttribs().setB("viewingOtherBank", true);
		player.setCloseInterfacesEvent(() -> {
			player.getPackets().sendRunScript(571);
			player.abortDialogue();
			player.getInventory().refresh();
			player.getEquipment().refresh();
			Familiar.sendLeftClickOption(player);
		});
	}

	public void refreshLastX() {
		player.getVars().setVar(1249, lastX);
	}

	public void createTab() {
		int slot = bankTabs.length;
		Item[][] tabs = new Item[slot + 1][];
		System.arraycopy(bankTabs, 0, tabs, 0, slot);
		tabs[slot] = new Item[0];
		bankTabs = tabs;
	}

	public void destroyTab(int slot) {
		Item[][] tabs = new Item[bankTabs.length - 1][];
		System.arraycopy(bankTabs, 0, tabs, 0, slot);
		System.arraycopy(bankTabs, slot + 1, tabs, slot, bankTabs.length - slot - 1);
		bankTabs = tabs;
		if (currentTab != 0 && currentTab >= slot)
			currentTab--;
	}

	public boolean hasBankSpace() {
		return getBankSize() < MAX_BANK_SIZE;
	}

	public void withdrawItem(int bankSlot, int quantity) {
		if (player.getTempAttribs().getB("viewingOtherBank") || (quantity < 1))
			return;
		Item item = getItem(getRealSlot(bankSlot));
		if (item == null)
			return;
		// Refuse before any inventory mutation: the client's op index is not trusted, so ops 1-7
		// aimed at a placeholder are no-ops rather than a source of free items.
		if (isPlaceholder(item))
			return;
		if (item.getMetaData() != null)
			quantity = 1;
		if (item.getAmount() < quantity)
			item = new Item(item.getId(), item.getAmount(), item.getMetaData());
		else
			item = new Item(item.getId(), quantity, item.getMetaData());
		boolean noted = false;
		ItemDefinition defs = item.getDefinitions();
		if (withdrawNotes)
			if (!defs.isNoted() && defs.getCertId() != -1 && item.getMetaData() == null) {
				item.setId(defs.getCertId());
				noted = true;
			} else
				player.sendMessage("You cannot withdraw this item as a note.");
		if ((noted || defs.isStackable()) && item.getMetaData() == null) {
			if (player.getInventory().getItems().containsOne(item)) {
				int slot = player.getInventory().getItems().getThisItemSlot(item);
				Item invItem = player.getInventory().getItems().get(slot);
				if (invItem.getAmount() + item.getAmount() <= 0) {
					item.setAmount(Integer.MAX_VALUE - invItem.getAmount());
					player.sendMessage("Not enough space in your inventory.");
					return;
				}
			} else if (!player.getInventory().hasFreeSlots()) {
				player.sendMessage("Not enough space in your inventory.");
				return;
			}
		} else {
			int freeSlots = player.getInventory().getFreeSlots();
			if (freeSlots == 0) {
				player.sendMessage("Not enough space in your inventory.");
				return;
			}
			if (freeSlots < item.getAmount()) {
				item.setAmount(freeSlots);
				player.sendMessage("Not enough space in your inventory.");
			}
		}
		removeItem(bankSlot, item.getAmount(), true, false);
		player.getInventory().addItem(item);
		refreshOpVars();
		if (item.getId() == Runecrafting.RUNE_ESS || item.getId() == Runecrafting.PURE_ESS)
			Runecrafting.fillPouchesFromBank(player, item.getId());
	}

	public void sendExamine(int fakeSlot) {
		int[] slot = getRealSlot(fakeSlot);
		if (slot == null)
			return;
		Item item = bankTabs[slot[0]][slot[1]];
		player.sendMessage(ItemConfig.get(item.getId()).getExamine(item));
		if (item.getMetaData("combatCharges") != null)
			player.sendMessage("<col=FF0000>It looks like it will last another " + Utils.ticksToTime(item.getMetaDataI("combatCharges")));
	}

	public boolean depositItem(int invSlot, int quantity, boolean refresh) {
		if (player.getTempAttribs().getB("viewingOtherBank") || quantity < 1 || invSlot < 0 || invSlot > 27)
			return false;
		Item item = player.getInventory().getItem(invSlot);
		if (item == null)
			return false;
		if (item.getMetaData() != null)
			quantity = 1;
		int amt = player.getInventory().getItems().getNumberOf(item);
		if (amt < quantity)
			item = new Item(item.getId(), amt, item.getMetaData());
		else
			item = new Item(item.getId(), quantity, item.getMetaData());
		int originalId = unnote(item);
		Item bankedItem = getItem(item.getId());
		if (bankedItem != null) {
			if (bankedItem.getAmount() + item.getAmount() <= 0) {
				item.setAmount(Integer.MAX_VALUE - bankedItem.getAmount());
				player.sendMessage("Not enough space in your bank.");
			}
		} else if (getPlaceholderSlot(item.getId()) == null && !hasBankSpace()) {
			// A deposit that lands in this item's placeholder needs no new slot.
			player.sendMessage("Not enough space in your bank.");
			return false;
		}
		if (addItem(item, refresh)) {
			player.getInventory().deleteItem(invSlot, new Item(originalId, item.getAmount(), item.getMetaData()));
			return true;
		}
		return false;
	}

	//	public void addItem(Item item, boolean refresh) {
	//		addItem(item.getId(), item.getAmount(), refresh);
	//	}

	private static int unnote(Item item) {
		ItemDefinition defs = item.getDefinitions();
		int originalId = item.getId();
		if (defs.isNoted() && defs.getCertId() != -1)
			item.setId(defs.getCertId());
		return originalId;
	}

	public boolean addItem(Item item, boolean refresh) {
		return addItem(item, currentTab, refresh);
	}

	public boolean addItem(Item item, int creationTab, boolean refresh) {
		if (item == null || item.getId() == -1)
			return false;
		if (!player.getControllerManager().canDepositItem(item))
			return false;
		var event = new ItemAddedToBankEvent(player, item);
		PluginManager.handle(event);
		if (event.isCancelled())
			return false;
		// A placeholder for this id is filled in place, which keeps the slot the player arranged
		// and works for a metadata item too (the merge below cannot take one). This is the only
		// way an amount-0 entry ever gains an amount.
		int[] placeholderSlot = getPlaceholderSlot(item.getId());
		if (placeholderSlot != null) {
			bankTabs[placeholderSlot[0]][placeholderSlot[1]] = item;
			if (refresh) {
				refreshTab(placeholderSlot[0]);
				refreshItems();
			}
			return true;
		}
		int[] slotInfo = getItemSlot(item.getId());
		if (slotInfo == null || item.getMetaData() != null || bankTabs[slotInfo[0]][slotInfo[1]].getMetaData() != null) {
			if (creationTab >= bankTabs.length)
				creationTab = bankTabs.length - 1;
			if (creationTab < 0) // fixed now, alex
				creationTab = 0;
			int slot = bankTabs[creationTab].length;
			Item[] tab = new Item[slot + 1];
			System.arraycopy(bankTabs[creationTab], 0, tab, 0, slot);
			tab[slot] = item;
			bankTabs[creationTab] = tab;
			if (refresh)
				refreshTab(creationTab);
		} else {
			Item existingItem = bankTabs[slotInfo[0]][slotInfo[1]];
			// Neither side has metadata here (the branch above takes those), so the merged stack
			// can go through the amt0 constructor: a computed 0 must stay 0 rather than be clamped
			// up to 1 by the ordinary one.
			bankTabs[slotInfo[0]][slotInfo[1]] = new Item(item.getId(), existingItem.getAmount() + item.getAmount(), true);
		}
		if (refresh)
			refreshItems();
		return true;
	}

	public boolean removeItem(int fakeSlot, int quantity, boolean refresh, boolean forceDestroy) {
		return removeItem(getRealSlot(fakeSlot), quantity, refresh, forceDestroy);
	}

	/**
	 * The bank's only slot-release path, and so the only place a placeholder is created or
	 * destroyed.
	 *
	 * With placeholders on, emptying a slot leaves the id behind with amount 0 instead of shrinking
	 * the tab; an entry that is already a placeholder is untouched unless [forceDestroy], which
	 * only an explicit release or take-back passes.
	 */
	public boolean removeItem(int[] slot, int quantity, boolean refresh, boolean forceDestroy) {
		if (slot == null)
			return false;
		Item item = bankTabs[slot[0]][slot[1]];
		boolean destroyed = false;
		if (quantity >= item.getAmount()) {
			if (isPlaceholder(item) && !forceDestroy)
				return false;
			else if (placeholders && !forceDestroy && !isPlaceholder(item) && item.getMetaData() == null)
				// Item(id, 0, true) is the only constructor that keeps the 0; the ordinary ones
				// clamp it up to 1.
				bankTabs[slot[0]][slot[1]] = new Item(item.getId(), 0, true);
			else if (bankTabs[slot[0]].length == 1 && bankTabs.length != 1) {
				// The last remaining tab is never destroyed, forceDestroy or not: bankTabs would
				// be left empty, and getRealSlot/generateContainer both assume a tab 0 exists.
				// Legacy let forceDestroy through here, which is how an id-based removal of a
				// one-item, one-tab bank could leave the bank unusable.
				destroyTab(slot[0]);
				if (refresh)
					refreshTabs();
				destroyed = true;
			} else {
				Item[] tab = new Item[bankTabs[slot[0]].length - 1];
				System.arraycopy(bankTabs[slot[0]], 0, tab, 0, slot[1]);
				System.arraycopy(bankTabs[slot[0]], slot[1] + 1, tab, slot[1], bankTabs[slot[0]].length - slot[1] - 1);
				bankTabs[slot[0]] = tab;
				if (refresh)
					refreshTab(slot[0]);
			}
		} else
			// quantity is strictly less than the amount here, so the remainder is at least 1 and
			// the clamping constructor cannot turn it into something it is not.
			bankTabs[slot[0]][slot[1]] = new Item(item.getId(), item.getAmount() - quantity, item.getMetaData());
		if (refresh)
			refreshItems();
		return destroyed;
	}

	/** Whether this entry is a placeholder: an id kept in its slot with nothing in it. */
	public static boolean isPlaceholder(Item item) {
		return item != null && item.getAmount() == 0;
	}

	/** Whether new placeholders are being created for this bank. */
	public boolean isPlaceholdersEnabled() {
		return placeholders;
	}

	/**
	 * Sets the toggle without touching the bank's contents.
	 *
	 * Turning it off keeps every placeholder that already exists - only Release and "release all"
	 * remove them.
	 */
	public void setPlaceholdersEnabled(boolean enabled) {
		placeholders = enabled;
	}

	/** Whether the bank holds at least one placeholder. */
	public boolean hasPlaceholders() {
		for (Item[] tab : bankTabs)
			for (Item item : tab)
				if (isPlaceholder(item))
					return true;
		return false;
	}

	/** How many placeholders the bank holds, across every tab. */
	public int countPlaceholders() {
		int count = 0;
		for (Item[] tab : bankTabs)
			count += countPlaceholders(tab);
		return count;
	}

	private static int countPlaceholders(Item[] tab) {
		int count = 0;
		for (Item item : tab)
			if (isPlaceholder(item))
				count++;
		return count;
	}

	/** The tab/slot of this item's placeholder, or null when it has none. */
	public int[] getPlaceholderSlot(int id) {
		for (int tab = 0; tab < bankTabs.length; tab++)
			for (int slot = 0; slot < bankTabs[tab].length; slot++)
				if (bankTabs[tab][slot].getId() == id && isPlaceholder(bankTabs[tab][slot]))
					return new int[] { tab, slot };
		return null;
	}

	/** Destroys this item's placeholder, if it has one. Returns whether one was found. */
	public boolean destroyPlaceholder(int id, boolean refresh) {
		int[] slot = getPlaceholderSlot(id);
		if (slot == null)
			return false;
		removeItem(slot, 1, refresh, true);
		return true;
	}

	/**
	 * The Release op (762:95 op 9).
	 *
	 * The op index the client sent is not trusted: this only ever releases a placeholder, so
	 * Release on a real item is a no-op.
	 */
	public void releasePlaceholder(int fakeSlot) {
		if (player.getTempAttribs().getB("viewingOtherBank"))
			return;
		int[] slot = getRealSlot(fakeSlot);
		if (slot == null)
			return;
		if (!isPlaceholder(bankTabs[slot[0]][slot[1]]))
			return;
		removeItem(slot, 1, true, true);
	}

	/** Asks before releasing everything - the button is next to the withdraw-all buttons. */
	public void promptReleaseAllPlaceholders() {
		if (!hasPlaceholders()) {
			player.sendMessage("You have no placeholders to release.");
			return;
		}
		player.sendOptionDialogue("Release all placeholders?", ops -> {
			ops.add("Yes", this::releaseAllPlaceholders);
			ops.add("No");
		});
	}

	/** Removes every placeholder, destroying any tab left empty by it. */
	public void releaseAllPlaceholders() {
		if (!hasPlaceholders())
			return;
		// Downwards, so destroying a tab only shifts tabs that have already been handled.
		for (int tab = bankTabs.length - 1; tab >= 0; tab--) {
			Item[] items = bankTabs[tab];
			int keep = items.length - countPlaceholders(items);
			if (keep == items.length)
				continue;
			if (keep == 0 && tab != 0) {
				destroyTab(tab);
				continue;
			}
			Item[] kept = new Item[keep];
			int index = 0;
			for (Item item : items)
				if (!isPlaceholder(item))
					kept[index++] = item;
			bankTabs[tab] = kept;
		}
		refreshTabs();
		// The container is shorter than the client's copy, so this takes refreshItems' full-send
		// path rather than a slot diff.
		refreshItems();
	}

	/** Flips the toggle and repaints its button. The flag is persisted with the bank. */
	public void togglePlaceholders() {
		placeholders = !placeholders;
		refreshPlaceholderButton();
	}

	/** Lights the toggle button for the current setting. The client has no var for it. */
	public void refreshPlaceholderButton() {
		player.getPackets().setIFGraphic(762, PLACEHOLDER_TOGGLE_COMPONENT, placeholders ? TOGGLE_SPRITE_ON : TOGGLE_SPRITE_OFF);
	}

	/**
	 * The tab/slot holding this item, skipping placeholders.
	 *
	 * Callers (the rune pouch filler, the house servant) look the slot up to take items out of it,
	 * so it has to find the entry that actually holds them - see [getPlaceholderSlot] for the
	 * empty ones.
	 */
	public int[] getSlot(int itemId) {
		if (bankTabs != null)
			for (int i = 0; i < bankTabs.length; i++)
				for (int i2 = 0; i2 < bankTabs[i].length; i2++)
					if (bankTabs[i][i2].getId() == itemId && !isPlaceholder(bankTabs[i][i2]))
						return new int[] { i, i2 };
		return null;
	}

	/**
	 * Takes items out of the bank by id, for a withdrawal the player is not driving directly (the
	 * house servant's fetch).
	 *
	 * This was a copy of [removeItem]'s body; it delegates now so there is exactly one slot-release
	 * path and this one cannot drift out of step with placeholder handling. It is a withdrawal, so
	 * it does not force the slot away - emptying it leaves a placeholder when the toggle is on.
	 */
	public boolean withdrawItemDel(int itemId, int amount) {
		return removeItem(getSlot(itemId), amount, true, false);
	}

	/**
	 * The bank's stack of this item, skipping placeholders.
	 *
	 * Every caller reads an amount off what comes back or mutates it, so an empty slot that merely
	 * remembers the id must not be handed over.
	 */
	public Item getItem(int id) {
		for (Item[] bankTab : bankTabs)
			for (Item item : bankTab)
				if (item.getId() == id && !isPlaceholder(item))
					return item;
		return null;
	}

	/** The slot of this item's stack, skipping placeholders - [addItem] merges into it. */
	public int[] getItemSlot(int id) {
		for (int tab = 0; tab < bankTabs.length; tab++)
			for (int slot = 0; slot < bankTabs[tab].length; slot++)
				if (bankTabs[tab][slot].getId() == id && !isPlaceholder(bankTabs[tab][slot]))
					return new int[] { tab, slot };
		return null;
	}

	public Item getItem(int[] slot) {
		if (slot == null)
			return null;
		return bankTabs[slot[0]][slot[1]];
	}

	public int getStartSlot(int tabId) {
		int slotId = 0;
		for (int tab = 1; tab < (tabId == 0 ? bankTabs.length : tabId); tab++)
			slotId += bankTabs[tab].length;

		return slotId;

	}

	public int[] getRealSlot(int slot) {
		for (int tab = 1; tab < bankTabs.length; tab++) {
			if (slot < bankTabs[tab].length)
				return new int[] { tab, slot };
			slot -= bankTabs[tab].length;
		}
		if (slot >= bankTabs[0].length)
			return null;
		return new int[] { 0, slot };
	}

	public void refreshViewingTab() {
		player.getVars().setVarBit(4893, currentTab + 1);
		player.getVars().syncVarsToClient();
	}

	public void refreshTab(Player other, int slot) {
		if (slot == 0)
			return;
		player.getVars().setVarBit(4885 + (slot - 1), getTabSize(other, slot));
		refreshViewingTab();
	}

	public void refreshTab(int slot) {
		if (slot == 0)
			return;
		player.getVars().setVarBit(4885 + (slot - 1), getTabSize(slot));
		refreshViewingTab();
	}

	public void sendItems() {
		player.getPackets().sendItems(95, getContainerCopy());
		player.getPackets().sendVarc(1038, 0);
		player.getPackets().sendVarc(192, getBankSize());
	}

	public void sendItemsOther(Player other) {
		player.getPackets().sendItems(95, other.getBank().getContainerCopy());
	}

	public void refreshItems(int[] slots) {
		player.getPackets().sendUpdateItems(95, getContainerCopy(), slots);
		player.getPackets().sendVarc(1038, 0);
		player.getPackets().sendVarc(192, getBankSize());
	}

	public Item[] getContainerCopy() {
		if (lastContainerCopy == null)
			lastContainerCopy = generateContainer();
		return lastContainerCopy;
	}

	public void refreshItems() {
		refreshItems(generateContainer(), getContainerCopy());
	}

	public void refreshOpVars() {
		int eatFlags = 0;
		for (int i = 0;i < 28;i++) {
			Item item = player.getInventory().getItem(i);
			if (item == null)
				continue;
			if (Foods.Food.forId(item.getId()) != null || Potion.forId(item.getId()) != null)
				eatFlags |= (1 << i);
		}
		player.getPackets().sendVarc(95, eatFlags);
		player.getPackets().sendVarc(96, ~eatFlags);
	}

	public void refreshItems(Item[] itemsAfter, Item[] itemsBefore) {
		if (itemsBefore.length != itemsAfter.length) {
			lastContainerCopy = itemsAfter;
			sendItems();
			return;
		}
		int[] changedSlots = new int[itemsAfter.length];
		int count = 0;
		for (int index = 0; index < itemsAfter.length; index++)
			if (itemsBefore[index] != itemsAfter[index])
				changedSlots[count++] = index;
		int[] finalChangedSlots = new int[count];
		System.arraycopy(changedSlots, 0, finalChangedSlots, 0, count);
		lastContainerCopy = itemsAfter;
		refreshItems(finalChangedSlots);
	}

	public int getBankSize() {
		int size = 0;
		for (Item[] bankTab : bankTabs)
			size += bankTab.length;
		return size;
	}

	public Item[] generateContainer() {
		Item[] container = new Item[getBankSize()];
		int count = 0;
		for (int slot = 1; slot < bankTabs.length; slot++) {
			System.arraycopy(bankTabs[slot], 0, container, count, bankTabs[slot].length);
			count += bankTabs[slot].length;
		}
		System.arraycopy(bankTabs[0], 0, container, count, bankTabs[0].length);
		return container;
	}

	public void unlockButtons() {
		//		player.getPackets().sendHideIComponent(762, 42, false);
		//		player.getPackets().sendHideIComponent(762, 43, false); //unlocks bank pin shit
		//		player.getPackets().sendHideIComponent(762, 44, false);

		player.getPackets().setIFEvents(new IFEvents(762, 95, 0, MAX_BANK_SIZE)
				.enableRightClickOptions(0,1,2,3,4,5,6,7,8,9)
				.setDepth(2)
				.enableDrag());
		player.getPackets().setIFEvents(new IFEvents(763, 0, 0, 27)
				.enableUseOptions(UseFlag.ICOMPONENT)
				.enableRightClickOptions(0,1,2,3,4,5,6,7,8,9)
				.setDepth(1)
				.enableDrag());
	}

	public void switchWithdrawNotes() {
		withdrawNotes = !withdrawNotes;
	}

	public void switchInsertItems() {
		insertItems = !insertItems;
		player.getVars().setVar(305, insertItems ? 1 : 0);
	}

	public boolean getInsertItems() {
		return insertItems;
	}

	public boolean getWithdrawNotes() {
		return withdrawNotes;
	}

	public int getCurrentTab() {
		return currentTab;
	}

	public void setCurrentTab(int currentTab) {
		if (currentTab >= bankTabs.length)
			return;
		this.currentTab = currentTab;
		player.getPackets().sendVarc(190, 1);
	}

	public int getLastX() {
		return lastX;
	}

	public void setLastX(int lastX) {
		this.lastX = lastX;
	}

	public void clear() {
		bankTabs = new Item[1][0];
	}

	public boolean enteredPIN() {
		if (bankPin == 0 || sessionPin)
			return true;
		if (enteredPin == null)
			return false;
		List<String> toRemove = new ArrayList<>();
		for (String ip : enteredPin.keySet())
			if (System.currentTimeMillis() - enteredPin.get(ip) > PIN_VALIDITY_TIME)
				toRemove.add(ip);
		for (String ip : toRemove)
			enteredPin.remove(ip);
		if (enteredPin.containsKey(player.getSession().getIP())) {
			sessionPin = true;
			return true;
		}
		return false;
	}

	public void setEnteredPIN() {
		if (enteredPin == null)
			enteredPin = new ConcurrentHashMap<>();
		enteredPin.put(player.getSession().getIP(), System.currentTimeMillis());
		sessionPin = true;
	}

	public boolean checkPin() {
		if (bankPin != 0 && !enteredPIN()) {
			openPin();
			return false;
		}
		return true;
	}
}
