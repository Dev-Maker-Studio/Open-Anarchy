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
package com.rs.lib.net;

import com.rs.lib.util.Logger;

/**
 * Stub for legacy ServerChannelHandler. Netty dependencies removed for compilation.
 * Retains the static API surface that the world module code calls.
 */
public final class ServerChannelHandler {

	public static final void init(int port, Class<? extends Decoder> baseDecoderClass) {
		Logger.info(ServerChannelHandler.class, "init", "Putting server online... (" + baseDecoderClass + ")");
		// Netty bootstrap removed -- stub only
	}

	public static int getConnectedChannelsSize() {
		return 0;
	}

	public static final void shutdown() {
		// no-op stub
	}
}
