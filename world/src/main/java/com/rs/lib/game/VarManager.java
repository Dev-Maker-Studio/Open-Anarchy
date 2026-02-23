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
package com.rs.lib.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import com.rs.lib.net.Session;

public class VarManager {

	public static final int[] BIT_MASKS = new int[32];

	static {
		int i = 2;
		for (int i2 = 0; i2 < 32; i2++) {
			BIT_MASKS[i2] = i - 1;
			i += i;
		}
	}

	private static final int DEFAULT_VARP_SIZE = 5000;

	private transient Session session;
	private transient HashSet<Integer> modified;
	private transient final Object lock = new Object();
	private transient int[] varpValues;
	private Map<Integer, Integer> vars;

	public VarManager() {

	}

	public void setSession(Session session) {
		this.session = session;
	}

	/**
	 * Initialize the var manager with a default varp array size.
	 * TODO: Reintroduce cache/session dependency for proper initialization.
	 * Original used Cache.STORE.getIndex(IndexType.CONFIG).getLastFileId(ArchiveType.VARS.getId()) + 1
	 * and accepted a Session parameter for packet sending.
	 */
	public void init() {
		if (vars == null)
			vars = new HashMap<>();
		varpValues = new int[DEFAULT_VARP_SIZE];
		modified = new HashSet<>();
		for (int varId : vars.keySet())
			setVar(varId, vars.get(varId));
	}

	public void setVar(int id, int value, boolean forceSend, boolean save) {
		synchronized(lock) {
			if (forceSend)
				modified.add(id);
			if (id < 0 || id >= varpValues.length)
				return;
			if (varpValues[id] == value)
				return;
			varpValues[id] = value;
			if (save)
				vars.put(id, value);
			modified.add(id);
		}
	}

	public void setVar(int id, int value, boolean forceSend) {
		setVar(id, value, forceSend, false);
	}

	public void setVar(int id, int value) {
		setVar(id, value, false, false);
	}

	public void saveVar(int id, int value) {
		setVar(id, value, false, true);
	}

	/**
	 * TODO: Reintroduce cache dependency once VarBitDefinitions shim is available.
	 * Original resolved VarBitDefinitions.getDefs(id) for baseVar, startBit, endBit.
	 * This stub does nothing until VarBitDefinitions is available.
	 */
	public void setVarBit(int id, int value, boolean forceSend, boolean save) {
		// Stub: requires VarBitDefinitions to resolve baseVar, startBit, endBit
	}

	public void setVarBit(int id, int value, boolean forceSend) {
		setVarBit(id, value, forceSend, false);
	}

	public void setVarBit(int id, int value) {
		setVarBit(id, value, false, false);
	}

	public void saveVarBit(int id, int value) {
		setVarBit(id, value, false, true);
	}

	public int getVar(int id) {
		if (id < 0 || id >= varpValues.length)
			return 0;
		return varpValues[id];
	}

	/**
	 * TODO: Reintroduce cache dependency once VarBitDefinitions shim is available.
	 * This stub returns 0 until VarBitDefinitions is available.
	 */
	public int getVarBit(int id) {
		return 0;
	}

	public boolean bitFlagged(int id, int bit) {
		if (id < 0 || id >= varpValues.length)
			return false;
		return (varpValues[id] & (1 << bit)) != 0;
	}

	/**
	 * TODO: Reintroduce network dependency for sending vars to client.
	 * Original called session.writeToQueue(new SetVarp(id, varpValues[id])) for each modified var.
	 */
	public void syncVarsToClient() {
		synchronized(lock) {
			// Stub: no session/packet sending available
			modified.clear();
		}
	}

	/**
	 * TODO: Reintroduce network dependency for clearing vars on client.
	 * Original called session.writeToQueue(ServerPacket.CLEAR_VARPS).
	 */
	public void clearVars() {
		for (int i = 0; i < varpValues.length; i++)
			varpValues[i] = 0;
		// Stub: no session/packet sending available
	}
}
