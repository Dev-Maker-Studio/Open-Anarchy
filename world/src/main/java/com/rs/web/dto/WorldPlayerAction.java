package com.rs.web.dto;

import com.rs.game.WorldInfo;
import org.darkan.core.model.Account;

/**
 * Stub DTO for world player add/remove actions.
 */
public class WorldPlayerAction {
    private final Account account;
    private final WorldInfo worldInfo;

    public WorldPlayerAction(Account account, WorldInfo worldInfo) {
        this.account = account;
        this.worldInfo = worldInfo;
    }

    public Account getAccount() { return account; }
    public WorldInfo getWorldInfo() { return worldInfo; }
}
