package dev.changeassurance.persistence;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class PersistenceJson {
    private static final JsonMapper MAPPER = new JsonMapper();

    private PersistenceJson() { }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    static <T> T read(String json, TypeReference<T> type) {
        return MAPPER.readValue(json, type);
    }
}
