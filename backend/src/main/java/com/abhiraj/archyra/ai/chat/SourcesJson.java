package com.abhiraj.archyra.ai.chat;

import java.util.List;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The sources of an assistant message as stored in its {@code jsonb} column. */
final class SourcesJson {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final TypeReference<List<ChatSource>> SOURCES = new TypeReference<>() {
	};

	private SourcesJson() {
	}

	static String write(List<ChatSource> sources) {
		return MAPPER.writeValueAsString(sources);
	}

	static List<ChatSource> read(String json) {
		return json == null || json.isBlank() ? List.of() : MAPPER.readValue(json, SOURCES);
	}
}
