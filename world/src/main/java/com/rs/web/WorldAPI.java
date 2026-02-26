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
package com.rs.web;

import com.rs.Settings;
import com.rs.game.World;
import com.rs.game.content.clans.ClansManager;
import com.rs.game.model.entity.player.Player;
import com.rs.game.model.entity.player.social.FCManager;
import org.darkan.core.model.Account;
import org.darkan.core.model.Clan;
import com.rs.web.APIUtil;
import com.rs.web.ErrorResponse;
import com.rs.web.WebAPI;
import com.rs.web.dto.FCData;
import io.undertow.util.StatusCodes;

public class WorldAPI extends WebAPI {

	public WorldAPI() {
		super("api", Settings.getConfig().getWorldInfo().port()+1);

		routes.get("/players", ex ->
				ex.dispatch(() -> APIUtil.sendResponse(ex, StatusCodes.OK, World.getPlayers().size())));
		
		routes.post("/logout", ex -> ex.dispatch(() -> {
            if (!APIUtil.authenticate(ex, Settings.getConfig().getLobbyApiKey())) {
                APIUtil.sendResponse(ex, StatusCodes.UNAUTHORIZED, new ErrorResponse("Invalid authorization key."));
                return;
            }
            APIUtil.readJSON(ex, Account.class, account -> {
                Player player = World.getPlayerByUsername(account.getUsername());
                if (player == null || player.getSession() == null) {
                    APIUtil.sendResponse(ex, StatusCodes.OK, true);
                    return;
                }
                player.forceLogout();
                APIUtil.sendResponse(ex, StatusCodes.OK, true);
            });
        }));
		
		routes.post("/updateclan", ex -> ex.dispatch(() -> {
            if (!APIUtil.authenticate(ex, Settings.getConfig().getLobbyApiKey())) {
                APIUtil.sendResponse(ex, StatusCodes.UNAUTHORIZED, new ErrorResponse("Invalid authorization key."));
                return;
            }
            APIUtil.readJSON(ex, Clan.class, clan -> {
                ClansManager.syncClanFromLobby(clan);
                APIUtil.sendResponse(ex, StatusCodes.OK, true);
            });
        }));

		routes.post("/updatesocial", ex -> ex.dispatch(() -> {
            if (!APIUtil.authenticate(ex, Settings.getConfig().getLobbyApiKey())) {
                APIUtil.sendResponse(ex, StatusCodes.UNAUTHORIZED, new ErrorResponse("Invalid authorization key."));
                return;
            }
            APIUtil.readJSON(ex, Account.class, account -> {
                Player player = World.getPlayerByUsername(account.getUsername());
                if (player == null || player.getSession() == null) {
                    APIUtil.sendResponse(ex, StatusCodes.OK, true);
                    return;
                }
                player.getAccount().setSocial(account.getSocial());
                player.getClan();
                APIUtil.sendResponse(ex, StatusCodes.OK, true);
            });
        }));

		routes.post("/updatefc", ex -> ex.dispatch(() -> {
            if (!APIUtil.authenticate(ex, Settings.getConfig().getLobbyApiKey())) {
                APIUtil.sendResponse(ex, StatusCodes.UNAUTHORIZED, new ErrorResponse("Invalid authorization key."));
                return;
            }
            APIUtil.readJSON(ex, FCData.class, fc -> {
                FCManager.updateFCData(fc);
                APIUtil.sendResponse(ex, StatusCodes.OK, true);
            });
        }));

		addRoute(new Telemetry());
	}

}
