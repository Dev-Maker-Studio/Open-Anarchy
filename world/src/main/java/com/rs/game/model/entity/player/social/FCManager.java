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
package com.rs.game.model.entity.player.social;

import com.rs.game.model.entity.player.Player;
import org.darkan.core.model.FriendsChatRank;

import org.darkan.world.server.WorldServer;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ButtonClickHandler;

@PluginEventHandler
public class FCManager {

	private static final int FC_SETUP_INTER = 1108;
	private static final int FC_TAB = 1109;

	public static ButtonClickHandler handleInterface = new ButtonClickHandler(FC_SETUP_INTER, e -> {
		switch (e.getComponentId()) {
		case 1 -> {
			switch (e.getOpNum()) {
			case 1 -> e.getPlayer().sendInputName("Enter chat prefix:", str -> {
				e.getPlayer().getSocial().getFriendsChat().setName(str);
				WorldServer.INSTANCE.saveAccountAsync(e.getPlayer().getAccount());
				syncFcSettingsToGateway(e.getPlayer());
				refreshFC(e.getPlayer());
			});
			case 2 -> {
				e.getPlayer().getSocial().getFriendsChat().setName(null);
				WorldServer.INSTANCE.saveAccountAsync(e.getPlayer().getAccount());
				syncFcSettingsToGateway(e.getPlayer());
				refreshFC(e.getPlayer());
			}
			default -> e.getPlayer().sendMessage("Unexpected FC interface packet...");
			}
		}
		case 2 -> e.getPlayer().getSocial().getFriendsChat().setRankToEnter(getRankFromPacket(e.getOpNum()));
		case 3 -> e.getPlayer().getSocial().getFriendsChat().setRankToSpeak(getRankFromPacket(e.getOpNum()));
		case 4 -> e.getPlayer().getSocial().getFriendsChat().setRankToKick(getRankFromPacket(e.getOpNum()));
		case 5 -> e.getPlayer().getSocial().getFriendsChat().setRankToLS(getRankFromPacket(e.getOpNum()));
		}
		WorldServer.INSTANCE.saveAccountAsync(e.getPlayer().getAccount());
		syncFcSettingsToGateway(e.getPlayer());
		refreshFC(e.getPlayer());
	});

	public static ButtonClickHandler handleTab = new ButtonClickHandler(FC_TAB, e -> {
		switch (e.getComponentId()) {
		case 26 -> {
			String currentFc = e.getPlayer().getSocial().getCurrentFriendsChat();
			if (currentFc != null && !currentFc.isEmpty()) {
				WorldServer.INSTANCE.forwardFcJoin(e.getPlayer().getUsername(), null);
			} else {
				e.getPlayer().sendInputName("Whose chat would you like to join?", name -> {
					if (name != null && !name.isEmpty())
						WorldServer.INSTANCE.forwardFcJoin(e.getPlayer().getUsername(), name);
				});
			}
		}
		case 31 -> {
			if (e.getPlayer().getInterfaceManager().containsScreenInter()) {
				e.getPlayer().sendMessage("Please close the interface you have opened before using Friends Chat setup.");
				return;
			}
			e.getPlayer().stopAll();
			openFriendChatSetup(e.getPlayer());
		}
		case 19 -> e.getPlayer().toggleLootShare();
		}
	});

	public static void syncFcSettingsToGateway(Player player) {
		var fc = player.getSocial().getFriendsChat();
		WorldServer.INSTANCE.syncFcSettings(
			player.getUsername(),
			fc.getName(),
			fc.getRankToEnter().getId(),
			fc.getRankToSpeak().getId(),
			fc.getRankToKick().getId(),
			fc.getRankToLS().getId()
		);
	}

	public static void openFriendChatSetup(Player player) {
		player.getInterfaceManager().sendInterface(FC_SETUP_INTER);
		refreshFC(player);
		player.getPackets().setIFHidden(FC_SETUP_INTER, 49, true);
		player.getPackets().setIFHidden(FC_SETUP_INTER, 63, true);
		player.getPackets().setIFHidden(FC_SETUP_INTER, 77, true);
		player.getPackets().setIFHidden(FC_SETUP_INTER, 91, true);
	}

	public static void refreshFC(Player player) {
		player.getPackets().setIFText(FC_SETUP_INTER, 1, player.getSocial().getFriendsChat().getName() == null ? "Chat disabled" : player.getSocial().getFriendsChat().getName());
		sendRankRequirement(player, player.getSocial().getFriendsChat().getRankToEnter(), 2);
		sendRankRequirement(player, player.getSocial().getFriendsChat().getRankToSpeak(), 3);
		sendRankRequirement(player, player.getSocial().getFriendsChat().getRankToKick(), 4);
		sendRankRequirement(player, player.getSocial().getFriendsChat().getRankToLS(), 5);
	}

	public static void sendRankRequirement(Player player, FriendsChatRank rank, int component) {
		switch (rank) {
		case UNRANKED -> player.getPackets().setIFText(FC_SETUP_INTER, component, "Anyone");
		case FRIEND -> player.getPackets().setIFText(FC_SETUP_INTER, component, "Any friends");
		case RECRUIT -> player.getPackets().setIFText(FC_SETUP_INTER, component, "Recruit+");
		case CORPORAL -> player.getPackets().setIFText(FC_SETUP_INTER, component, "Corporal+");
		case SERGEANT -> player.getPackets().setIFText(FC_SETUP_INTER, component, "Sergeant+");
		case LIEUTENANT -> player.getPackets().setIFText(FC_SETUP_INTER, component, "Lieutenant+");
		case CAPTAIN -> player.getPackets().setIFText(FC_SETUP_INTER, component, "Captain+");
		case GENERAL -> player.getPackets().setIFText(FC_SETUP_INTER, component, "General+");
		case OWNER -> player.getPackets().setIFText(FC_SETUP_INTER, component, component == 5 ? "No-one" : "Only me");
		default -> throw new IllegalArgumentException("Unexpected value: " + rank);
		}
	}

	public static FriendsChatRank getRankFromPacket(int opNum) {
		return switch (opNum) {
		case 1 -> FriendsChatRank.UNRANKED;
		case 2 -> FriendsChatRank.FRIEND;
		case 3 -> FriendsChatRank.RECRUIT;
		case 4 -> FriendsChatRank.CORPORAL;
		case 5 -> FriendsChatRank.SERGEANT;
		case 6 -> FriendsChatRank.LIEUTENANT;
		case 7 -> FriendsChatRank.CAPTAIN;
		case 8 -> FriendsChatRank.GENERAL;
		case 9 -> FriendsChatRank.OWNER;
		default -> FriendsChatRank.UNRANKED;
		};
	}

}
