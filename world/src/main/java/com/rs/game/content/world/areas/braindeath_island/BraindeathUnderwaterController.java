package com.rs.game.content.world.areas.braindeath_island;

import com.rs.game.content.skills.magic.TeleType;
import com.rs.game.model.entity.player.Controller;

public class BraindeathUnderwaterController extends Controller {

    private static final int SWIMMING_BAS = 152;

    @Override
    public void start() {
        player.getAppearance().setBAS(SWIMMING_BAS);
    }

    @Override
    public void onTeleported(TeleType type) {
        player.getControllerManager().forceStop();
    }

    @Override
    public boolean login() {
        player.getAppearance().setBAS(SWIMMING_BAS);
        return false;
    }

    @Override
    public boolean logout() {
        return false;
    }

    @Override
    public boolean sendDeath() {
        player.getControllerManager().forceStop();
        return true;
    }

    @Override
    public void forceClose() {
        player.getAppearance().setBAS(-1);
    }

    @Override
    public void onRemove() {
        player.getAppearance().setBAS(-1);
    }
}
