package com.rs.cache.loaders.cutscenes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Stub CutsceneDefinitions. Returns empty definitions until wired to the modern cache system.
 */
public class CutsceneDefinitions {

	private static final HashMap<Integer, CutsceneDefinitions> CACHE = new HashMap<>();

	public int id;
	public int anInt825;
	public int anInt824;
	public List<CutsceneArea> areas = new ArrayList<>();

	public static final CutsceneDefinitions getDefs(int id) {
		CutsceneDefinitions defs = CACHE.get(id);
		if (defs != null)
			return defs;
		defs = new CutsceneDefinitions();
		defs.id = id;
		// TODO: Load from modern cache
		CACHE.put(id, defs);
		return defs;
	}

	public static boolean cutsceneExists(int id) {
		return false; // TODO: Wire to cache
	}
}
