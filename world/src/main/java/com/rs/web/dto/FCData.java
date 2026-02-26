package com.rs.web.dto;

import java.util.Set;

import org.darkan.core.model.Account;
import org.darkan.core.model.FriendsChat;
import org.darkan.core.model.FriendsChatRank;
import org.darkan.core.model.Rights;

/**
 * Friends chat data DTO for lootshare and friends chat operations.
 */
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

    public FriendsChatRank getRank(Account account) {
        return getRank(account.getRights(), account.getUsername());
    }

    private FriendsChatRank getRank(Rights rights, String username) {
        if (rights.ordinal() >= Rights.ADMIN.ordinal())
            return FriendsChatRank.JMOD;
        if (username.equals(ownerUsername))
            return FriendsChatRank.OWNER;
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
