package com.rs.lib.model.clan;

/**
 * Clan variable definitions. This is an empty enum in the legacy codebase -
 * all entries are commented out documentation of var IDs. The meaningful code
 * is just the id field and getter.
 *
 * Cache dependency (ClanVarDefinitions) has been removed for the world module shim.
 */
public enum ClanVar {
	;

	private int id;

	ClanVar(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	/**
	 * Stub: In legacy code this returned ClanVarDefinitions.getDefs(id).
	 * Cache definition dependency removed for world module shim.
	 */
	public Object getDef() {
		return null;
	}
}
