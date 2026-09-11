package com.elioo.healthcare.core.util;

import com.google.gson.*;
import com.elioo.healthcare.core.enums.DateTimeFormatterPattern;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@UtilityClass
@Slf4j
public class CommonFunctions {
	public String buildGsonBuilder(Object object) {
		return buildGson(object).toJson(object);
	}

	public Gson buildGson(Object object) {
		DateTimeFormatter formater = DateTimeFormatter.ofPattern(DateTimeFormatterPattern.DATE_TIME.getValue());
		return new GsonBuilder()
				.registerTypeAdapter(LocalDateTime.class,
						(JsonDeserializer<LocalDateTime>) (json, typeOfT, context) -> LocalDateTime.parse(json.getAsString(), formater))
				.registerTypeAdapter(LocalDateTime.class,
						(JsonSerializer<LocalDateTime>) (localDateTime, type, jsonSerializationContext) ->
								new JsonPrimitive(localDateTime.format(formater)))
				.registerTypeAdapter(LocalDate.class,
						(JsonDeserializer<LocalDate>) (json, typeOfT, context) -> LocalDate.parse(json.getAsString(),
								DateTimeFormatter.ofPattern("yyyy-MM-dd")))
				.registerTypeAdapter(LocalDate.class,
						(JsonSerializer<LocalDate>) (localDateTime, type, jsonSerializationContext) ->
								new JsonPrimitive(localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))))
				.setPrettyPrinting().create();
	}

	public static String camelToSnake(String str) {
		return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
	}

	public static String snakeToCamel(String snakeCase) {
		if (snakeCase == null || snakeCase.isBlank()) {
			throw new IllegalArgumentException("Input string cannot be null or empty");
		}

		StringBuilder camelCase = new StringBuilder();
		boolean capitalizeNext = false;

		for (char c : snakeCase.toLowerCase().toCharArray()) {
			if (c == '_') {
				capitalizeNext = true;
			} else {
				if (capitalizeNext) {
					camelCase.append(Character.toUpperCase(c));
					capitalizeNext = false;
				} else {
					camelCase.append(c);
				}
			}
		}

		return camelCase.toString();
	}

}
