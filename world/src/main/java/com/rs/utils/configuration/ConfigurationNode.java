package com.rs.utils.configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class ConfigurationNode {

	private final Map<String, Object> children = new HashMap<>();

	public void set(String key, Object value) {
		children.put(key, value);
	}

	public boolean has(String key) {
		return children.containsKey(key);
	}

	public ConfigurationNode nodeFor(String name) {
		if (children.containsKey(name)) {
			Object value = children.get(name);
			if (value.getClass() != this.getClass())
				throw new ConfigurationException("Invalid node " + name + "!");
			return (ConfigurationNode) value;
		}
		return null;
	}

	public String listChildren() {
		StringBuilder builder = new StringBuilder();
		builder.append("[");
		for (Entry<String, Object> entry : children.entrySet()) {
			builder.append(entry.getKey()).append(" => ");
			if (entry.getValue() instanceof ConfigurationNode cn)
				builder.append(cn.listChildren());
			else
				builder.append(entry.getValue());
			builder.append(", ");
		}
		if (builder.length() > 2) {
			builder.deleteCharAt(builder.length() - 1);
			builder.deleteCharAt(builder.length() - 1);
		}
		builder.append("]");
		return builder.toString();
	}

	public Object get(String key) {
		return children.get(key);
	}

	public String getString(String string) {
		Object value = get(string);
		if (value instanceof String)
			return (String) value;
		return "null";
	}

	public int getInteger(String key) {
		return Integer.parseInt(getString(key));
	}

	public boolean getBoolean(String key) {
		return Boolean.parseBoolean(getString(key));
	}

	public Map<String, Object> getChildren() {
		return children;
	}
}