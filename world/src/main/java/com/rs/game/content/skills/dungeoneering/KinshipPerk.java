package com.rs.game.content.skills.dungeoneering;

public enum KinshipPerk {
	TANK(),
	TACTICIAN(),
	BERSERKER(),
	SNIPER(),
	KEEN_EYE(),
	DESPERADO(),
	BLAZER(),
	BLASTER(),
	BLITZER(),
	MEDIC(),
	GATHERER(),
	ARTISAN();

	public int getVarbit() {
		return 8053 + ordinal();
	}

	public int getItemId() {
		return 18817 + ordinal();
	}
}
