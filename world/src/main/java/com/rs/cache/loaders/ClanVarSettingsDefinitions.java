package com.rs.cache.loaders;

import java.util.HashMap;

import com.rs.cache.loaders.cs2.CS2Type;

/**
 * Stub ClanVarSettingsDefinitions. Returns empty definitions until wired to the modern cache system.
 */
public class ClanVarSettingsDefinitions {

	private static final HashMap<Integer, ClanVarSettingsDefinitions> CACHE = new HashMap<>();

	public int id;
	public char aChar4832;
	public CS2Type type;
	public int baseVar = -1;
	public int startBit;
	public int endBit;

	public static final ClanVarSettingsDefinitions getDefs(int id) {
		ClanVarSettingsDefinitions defs = CACHE.get(id);
		if (defs != null)
			return defs;
		defs = new ClanVarSettingsDefinitions();
		defs.id = id;
		// TODO: Load from modern cache
		CACHE.put(id, defs);
		return defs;
	}

	public long getMaxSize() {
		if (baseVar == -1) {
			if (type == CS2Type.INT)
				return Integer.MAX_VALUE;
			else if (type == CS2Type.LONG)
				return Long.MAX_VALUE;
			else
				return -1;
		} else {
			return 1L << (endBit - startBit);
		}
	}
}
