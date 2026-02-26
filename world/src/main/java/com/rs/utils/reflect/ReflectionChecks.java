package com.rs.utils.reflect;

import java.util.concurrent.ThreadLocalRandom;

public class ReflectionChecks {

	private int id;
	private ReflectionCheck[] checks;

	public ReflectionChecks() {
		this.id = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE - 1);
	}

	public int getId() {
		return id;
	}

	public ReflectionCheck[] getChecks() {
		return checks;
	}
	
	public ReflectionChecks setReflectionChecks(ReflectionCheck[] checks) {
		this.checks = checks;
		return this;
	}
}
