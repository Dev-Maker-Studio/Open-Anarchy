package com.rs.web.dto;

import org.darkan.core.model.FriendsChat;

/**
 * Stub DTO for updating friends chat settings.
 */
public class UpdateFC {
    private final String displayName;
    private final FriendsChat friendsChat;

    public UpdateFC(String displayName, FriendsChat friendsChat) {
        this.displayName = displayName;
        this.friendsChat = friendsChat;
    }

    public String getDisplayName() { return displayName; }
    public FriendsChat getFriendsChat() { return friendsChat; }
}
