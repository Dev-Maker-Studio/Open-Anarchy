package com.rs.utils;

import org.darkan.core.Logger;

import java.util.*;

public class Getlineonce {
	static Dictionary<String, List<Integer>> lines = new Hashtable<>();

	public Getlineonce(boolean repeat) {
		StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

		int line = stackTrace[2].getLineNumber();
		String fileName = stackTrace[2].getFileName();
		List<Integer> tempLines = new ArrayList<>();
		if (lines.isEmpty()) {
			tempLines.add(line);
			lines.put(fileName, tempLines);
		} else {
			boolean keyExists = false;
			for (Enumeration<String> k = lines.keys(); k.hasMoreElements(); ) {
				String key = k.nextElement();

				if (!key.equalsIgnoreCase(fileName))
					continue;
				keyExists = true;
				if (!lines.get(key).contains(line)) {
					tempLines = lines.get(key);
					tempLines.add(line);
					lines.put(fileName, tempLines);
					Logger.debug(Getlineonce.class, "constructor()", fileName + ": " + line);
				} else if (repeat)
					Logger.debug(Getlineonce.class, "constructor()", fileName + ": " + line);
			}
			if (!keyExists) {
				tempLines.add(line);
				lines.put(fileName, tempLines);
			}
		}
	}
}