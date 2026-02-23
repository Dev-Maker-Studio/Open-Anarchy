package com.rs.lib.web.dto;

import java.util.Set;

import com.rs.lib.game.Rights;
import com.rs.lib.model.Account;
import com.rs.lib.model.FriendsChat;
import com.rs.lib.model.FriendsChat.Rank;

public class FCData {

	private String ownerUsername;
	private String ownerDisplayName;
	private FriendsChat settings;
	private Set<String> usernames;

	public FCData(String ownerUsername, String ownerDisplayName, FriendsChat settings, Set<String> usernames) {
		this.ownerUsername = ownerUsername;
		this.ownerDisplayName = ownerDisplayName;
		this.settings = settings;
		this.usernames = usernames;
	}

	public Rank getRank(Account account) {
		return getRank(account.getRights(), account.getUsername());
	}

	private Rank getRank(Rights rights, String username) {
		if (rights.ordinal() >= Rights.ADMIN.ordinal())
			return Rank.JMOD;
		if (username.equals(ownerUsername))
			return Rank.OWNER;
		return settings.getRank(username);
	}

	public Set<String> getUsernames() {
		return usernames;
	}

	public FriendsChat getSettings() {
		return settings;
	}

	public String getOwnerDisplayName() {
		return ownerDisplayName;
	}
}
