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
package com.rs.lib.model.clan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.rs.lib.model.Account;
import com.rs.lib.model.MemberData;

/**
 * Clan model shim for world module.
 *
 * Cache-dependent methods (getSetting/setSetting/getVar/setVar that use
 * ClanVarDefinitions/ClanVarSettingsDefinitions/EnumDefinitions/CS2Type)
 * have been simplified. The settings and vars maps are preserved for
 * serialization compatibility, but type-checked get/set operations now
 * store values directly without cache definition validation.
 */
public class Clan {

	public static final int MAX_MEMBERS = 100;

	private String clanLeaderUsername;
	private String name;
	private Map<String, MemberData> members;
	private Set<String> bannedUsers;
	private Map<Integer, Object> settings;
	private Map<Integer, Object> vars;

	private ClanRank ccChatRank;
	private ClanRank ccKickRank;

	private byte[] updateBlock;

	public Clan(String name, Account leader) {
		this.name = name;
		setDefaults();
		this.members = new HashMap<>();
		this.bannedUsers = new HashSet<>();
		addMember(leader, ClanRank.OWNER);
		setClanLeaderUsername(leader.getUsername());
	}

	public void setDefaults() {
		settings = new HashMap<>();
		vars = new HashMap<>();
		setCcChatRank(ClanRank.NONE);
		setCcKickRank(ClanRank.ADMIN);
		for (ClanSetting setting : ClanSetting.values())
			if (setting.getDefault() != null)
				setSetting(setting, setting.getDefault());
	}

	public void setMotifColor(int[] colors) {
		if (colors.length != 4) {
			System.err.println("[Clan] setMotifColor: Motif colors must be an array of size 4!");
			return;
		}
		for (int i = 0; i < colors.length; i++)
			setMotifColor(i, colors[i]);
	}

	public void setMotifColor(int index, int color) {
		switch(index) {
		case 0 -> setSetting(ClanSetting.MOTIF_TOP_COLOR, color);
		case 1 -> setSetting(ClanSetting.MOTIF_BOTTOM_COLOR, color);
		case 2 -> setSetting(ClanSetting.MOTIF_PRIMARY_COLOR, color);
		case 3 -> setSetting(ClanSetting.MOTIF_SECONDARY_COLOR, color);
		}
	}

	public int getMotifTopIcon() {
		return (int) getSetting(ClanSetting.MOTIF_TOP_ICON);
	}

	public int getMotifBottomIcon() {
		return (int) getSetting(ClanSetting.MOTIF_BOTTOM_ICON);
	}

	public int[] getMotifColors() {
		return new int[] { (int) getSetting(ClanSetting.MOTIF_TOP_COLOR), (int) getSetting(ClanSetting.MOTIF_BOTTOM_COLOR), (int) getSetting(ClanSetting.MOTIF_PRIMARY_COLOR), (int) getSetting(ClanSetting.MOTIF_SECONDARY_COLOR) };
	}

	public MemberData addMember(Account account, ClanRank rank) {
		MemberData member = new MemberData(rank);
		members.put(account.getUsername(), member);
		return member;
	}

	public void removeMember(String username) {
		members.remove(username);
	}

	public void setClanLeaderUsername(String username) {
		clanLeaderUsername = username;
	}

	public Map<String, MemberData> getMembers() {
		return members;
	}

	public Set<String> getBannedUsers() {
		return bannedUsers;
	}

	public String getName() {
		return name;
	}

	public void switchRecruiting() {
		setSetting(ClanSetting.IS_RECRUITING, (int) getSetting(ClanSetting.IS_RECRUITING) == 0 ? 1 : 0);
	}

	public String getClanLeaderUsername() {
		return clanLeaderUsername;
	}

	public void setMotto(String motto) {
		setSetting(ClanSetting.MOTTO, motto);
	}

	public int[] getMotifTextures() {
		// Stub: In legacy code this used EnumDefinitions.getEnum(3685).getIntValue(slotId)
		// Cache dependency removed for world module shim.
		return new int[] { getMotifTexture((int) getSetting(ClanSetting.MOTIF_TOP_ICON)), getMotifTexture((int) getSetting(ClanSetting.MOTIF_BOTTOM_ICON)) };
	}

	public static int getMotifTexture(int slotId) {
		// Stub: In legacy code this used EnumDefinitions.getEnum(3685).getIntValue(slotId)
		// Returns the slotId directly as a fallback.
		return slotId;
	}

	public byte[] getUpdateBlock() {
		return updateBlock;
	}

	public void setUpdateBlock(byte[] updateBlock) {
		this.updateBlock = updateBlock;
	}

	public Map<Integer, Object> getSettings() {
		if (settings == null) {
			settings = new HashMap<>();
			setDefaults();
		}
		return settings;
	}

	public Object getSetting(ClanSetting setting) {
		return getSetting(setting.getId());
	}

	/**
	 * Simplified getSetting without cache definition type checking.
	 * Returns the raw value from the settings map, defaulting to 0 for
	 * numeric types and null for others.
	 */
	public Object getSetting(int id) {
		Object val = getSettings().get(id);
		if (val == null)
			return 0;
		if (val instanceof Double d)
			return d.intValue();
		return val;
	}

	public int getSettingBit(ClanSetting setting) {
		return getSettingBit(setting.getId());
	}

	/**
	 * Stub: Varbit extraction requires cache definitions.
	 * Returns raw int value from settings map.
	 */
	public int getSettingBit(int id) {
		Object val = getSettings().get(id);
		if (val == null)
			return 0;
		if (val instanceof Double d)
			return d.intValue();
		if (val instanceof Integer i)
			return i;
		return 0;
	}

	/**
	 * Simplified setSetting without cache definition type validation.
	 * Stores the value directly in the settings map.
	 */
	public void setSetting(ClanSetting setting, Object value) {
		getSettings().put(setting.getId(), value);
	}

	public void setSettingInt(int varId, int value) {
		getSettings().put(varId, value);
	}

	public void setSettingBit(int varId, int value) {
		getSettings().put(varId, value);
	}

	public void setSettingLong(int varId, long value) {
		getSettings().put(varId, value);
	}

	public void setSettingString(int varId, String value) {
		if (value.length() > 80)
			value = value.substring(0, 80);
		getSettings().put(varId, value);
	}

	public Map<Integer, Object> getVars() {
		if (vars == null)
			vars = new HashMap<>();
		return vars;
	}

	public Object getVar(ClanVar var) {
		return getVar(var.getId());
	}

	/**
	 * Simplified getVar without cache definition type checking.
	 */
	public Object getVar(int id) {
		Object val = getVars().get(id);
		if (val == null)
			return 0;
		if (val instanceof Double d)
			return d.intValue();
		return val;
	}

	public boolean hasPermissions(String username, ClanRank rank) {
		MemberData data = members.get(username);
		if (data == null)
			return false;
		return data.getRank().ordinal() >= rank.ordinal();
	}

	public boolean hasPermissions(String username, ClanPermission permission) {
		return permission.hasPermission(this, getRank(username));
	}

	public ClanRank getRank(String username) {
		MemberData data = members.get(username);
		if (data == null)
			return ClanRank.NONE;
		return data.getRank();
	}

	public int getVarBit(ClanVar var) {
		return getVarBit(var.getId());
	}

	/**
	 * Stub: Varbit extraction requires cache definitions.
	 * Returns raw int value from vars map.
	 */
	public int getVarBit(int id) {
		Object val = getVars().get(id);
		if (val == null)
			return 0;
		if (val instanceof Double d)
			return d.intValue();
		if (val instanceof Integer i)
			return i;
		return 0;
	}

	public void setVar(ClanVar var, Object value) {
		setVar(var.getId(), value);
	}

	/**
	 * Simplified setVar without cache definition type validation.
	 */
	public void setVar(int var, Object value) {
		getVars().put(var, value);
	}

	public void setVarInt(int varId, int value) {
		getVars().put(varId, value);
	}

	public void setVarBit(int varId, int value) {
		getVars().put(varId, value);
	}

	public void setVarLong(int varId, long value) {
		getVars().put(varId, value);
	}

	public void setVarString(int varId, String value) {
		if (value.length() > 80)
			value = value.substring(0, 80);
		getVars().put(varId, value);
	}

	public ClanRank getCcChatRank() {
		if (ccChatRank == null)
			setDefaults();
		return ccChatRank;
	}

	public void setCcChatRank(ClanRank ccChatRank) {
		this.ccChatRank = ccChatRank;
	}

	public ClanRank getCcKickRank() {
		if (ccChatRank == null)
			setDefaults();
		return ccKickRank;
	}

	public void setCcKickRank(ClanRank ccKickRank) {
		this.ccKickRank = ccKickRank;
	}
}
