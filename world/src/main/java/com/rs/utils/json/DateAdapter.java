package com.rs.utils.json;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Date;

public class DateAdapter implements JsonSerializer<Date>, JsonDeserializer<Date> {

	@Override
	public JsonElement serialize(Date date, Type type, JsonSerializationContext context) {
		JsonObject numberLong = new JsonObject();
		numberLong.addProperty("$numberLong", Long.toString(date.getTime()));
		JsonObject result = new JsonObject();
		result.add("$date", numberLong);
		return result;
	}

	@Override
	public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber())
			return new Date(json.getAsLong());
		if (json.isJsonObject()) {
			JsonElement date = json.getAsJsonObject().get("$date");
			if (date != null)
				return parseExtended(date);
		}
		throw new JsonParseException("Unsupported date value: " + json);
	}

	private Date parseExtended(JsonElement date) {
		if (date.isJsonPrimitive()) {
			JsonPrimitive primitive = date.getAsJsonPrimitive();
			if (primitive.isString())
				return Date.from(Instant.parse(primitive.getAsString()));
			return new Date(primitive.getAsLong());
		}
		if (date.isJsonObject()) {
			JsonElement numberLong = date.getAsJsonObject().get("$numberLong");
			if (numberLong != null)
				return new Date(Long.parseLong(numberLong.getAsString()));
		}
		throw new JsonParseException("Unsupported $date value: " + date);
	}
}
