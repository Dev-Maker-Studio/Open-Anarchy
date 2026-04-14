package com.rs.game;

import org.darkan.core.model.Rights;

/**
 * Legacy stub Account for social encoder compatibility.
 * The authoritative Account is in core: org.darkan.core.model.Account
 */
public class Account {
    private String username;
    private String displayName;
    private String prevDisplayName;
    private Rights rights = Rights.PLAYER;

    public Account() {}

    public Account(String username, String displayName) {
        this.username = username;
        this.displayName = displayName;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName != null ? displayName : username; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPrevDisplayName() { return prevDisplayName != null ? prevDisplayName : ""; }
    public void setPrevDisplayName(String prevDisplayName) { this.prevDisplayName = prevDisplayName; }

    public Rights getRights() { return rights; }
    public void setRights(Rights rights) { this.rights = rights; }

    public boolean onlineTo(Account other) {
        return true; // stub
    }
}
