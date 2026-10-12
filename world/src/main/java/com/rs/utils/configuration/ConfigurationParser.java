package com.rs.utils.configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigurationParser {

	private final Pattern scalarPattern = Pattern.compile("\\s*(.*?)\\s*\"((?:\\\"|[^\"])+?)\"\\s*(?:\\#.*)?$");

	private final Pattern arrayPattern = Pattern.compile("\\s*(.*?)\\s*\\s*\\{\\s*(?:\\#.*)?$");

	private final Pattern nestedArrayPattern = Pattern.compile("\\s*(.*?)\\s*\"((?:\\\"|[^\"])+?)\"\\s*\\(\\s*(?:\\#.*)?$");

	private final Pattern nestedHashPattern = Pattern.compile("\\s*(.*?)\\s*\"((?:\\\\\"|[^\"])+?)\"\\s*\\{\\s*(?:\\#.*)?$");

	private final Pattern nestedEndPattern = Pattern.compile("^\\s*(}|\\))\\s*");

	private final BufferedReader reader;

	public ConfigurationParser(InputStream input) {
		reader = new BufferedReader(new InputStreamReader(input));
	}

	public ConfigurationNode parse() throws IOException {
		ConfigurationNode node = new ConfigurationNode();
		try {
			parse(node);
		} finally {
			reader.close();
		}
		return node;
	}

	public void parse(ConfigurationNode node) throws IOException {
		String line = reader.readLine();
		if (line == null)
			return;
		line = line.trim();

		if (!line.startsWith("#") && line.length() != 0) {
			Matcher scalar = scalarPattern.matcher(line);
			Matcher array = arrayPattern.matcher(line);
			Matcher nestedArrayBlock = nestedArrayPattern.matcher(line);
			Matcher nestedHashBlock = nestedHashPattern.matcher(line);
			if (scalar.find())
				node.set(scalar.group(1), scalar.group(2));
			else if (nestedArrayBlock.find()) {
				String name = nestedArrayBlock.group(1);
				String key = nestedArrayBlock.group(2);
				key = key.replaceAll("\\\"", "\"");
				if (!node.has(name))
					node.set(name, new ConfigurationNode());
				parse(node.nodeFor(name));
			} else if (nestedHashBlock.find()) {
				String name = nestedHashBlock.group(1);
				String key = nestedHashBlock.group(2);
				ConfigurationNode sub = node.has(name) ? node.nodeFor(name) : new ConfigurationNode();
				if (!node.has(name))
					node.set(name, sub);
				if (!sub.has(key))
					sub.set(key, new ConfigurationNode());
				parse(sub.nodeFor(key));
			} else if (array.find()) {
				ConfigurationNode newNode = new ConfigurationNode();
				node.set(array.group(1), newNode);
				parse(newNode);
			}
			Matcher nestedEnd = nestedEndPattern.matcher(line);
			if (nestedEnd.find())
				return;
		}
		parse(node);
	}
}