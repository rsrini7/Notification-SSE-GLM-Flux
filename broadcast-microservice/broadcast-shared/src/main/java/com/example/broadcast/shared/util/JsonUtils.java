package com.example.broadcast.shared.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Utility class for common JSON operations, primarily for handling JSON arrays in database columns.
 */
@Slf4j
public final class JsonUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private JsonUtils() {}

    /**
     * Parses a JSON array string into a List of Strings.
     *
     * @param json The JSON string to parse.
     * @return A List of Strings, or an empty list if parsing fails or the input is null/empty.
     */
    public static List<String> parseJsonArray(String json) {
        // Gracefully handle null, empty strings, and the erroneous "{}" value from the DB
        if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON array string: {}", json, e);
            return List.of();
        }
    }

    /**
     * Converts a List of Strings into a JSON array string.
     *
     * @param list The list to convert.
     * @return A JSON array as a string, or null if the list is null/empty.
     */
    public static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.error("Failed to serialize list to JSON array string", e);
            return null;
        }
    }
}