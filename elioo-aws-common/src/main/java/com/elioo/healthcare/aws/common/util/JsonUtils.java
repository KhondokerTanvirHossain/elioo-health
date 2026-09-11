package com.elioo.healthcare.aws.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for JSON processing in AWS service responses.
 *
 * Provides helper methods for:
 * - Cleaning markdown code blocks from JSON strings
 * - Parsing JSON strings with error handling
 * - Extracting nested JSON fields
 */
public class JsonUtils {

    private static final Pattern MARKDOWN_CODE_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.MULTILINE);

    private JsonUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Removes markdown code block formatting from JSON strings.
     *
     * LLMs (like Claude) often wrap JSON responses in markdown code blocks:
     * <pre>
     * Here's the result:
     * ```json
     * {"key": "value"}
     * ```
     * </pre>
     *
     * This method extracts the JSON content and removes markdown formatting.
     *
     * @param jsonString Raw string that may contain markdown
     * @return Cleaned JSON string
     */
    public static String cleanJsonFromMarkdown(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) {
            return jsonString;
        }

        String cleaned = jsonString.trim();

        // Check if string contains markdown code block
        Matcher matcher = MARKDOWN_CODE_BLOCK_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            // Extract content from first code block
            cleaned = matcher.group(1).trim();
        }

        // Remove leading/trailing text if JSON starts with { or [
        int objectStart = cleaned.indexOf('{');
        int arrayStart = cleaned.indexOf('[');

        int jsonStart = -1;
        if (objectStart >= 0 && arrayStart >= 0) {
            jsonStart = Math.min(objectStart, arrayStart);
        } else if (objectStart >= 0) {
            jsonStart = objectStart;
        } else if (arrayStart >= 0) {
            jsonStart = arrayStart;
        }

        if (jsonStart > 0) {
            cleaned = cleaned.substring(jsonStart);
        }

        // Find the matching closing bracket
        int objectEnd = cleaned.lastIndexOf('}');
        int arrayEnd = cleaned.lastIndexOf(']');

        int jsonEnd = -1;
        if (objectEnd >= 0 && arrayEnd >= 0) {
            jsonEnd = Math.max(objectEnd, arrayEnd);
        } else if (objectEnd >= 0) {
            jsonEnd = objectEnd;
        } else if (arrayEnd >= 0) {
            jsonEnd = arrayEnd;
        }

        if (jsonEnd > 0 && jsonEnd < cleaned.length() - 1) {
            cleaned = cleaned.substring(0, jsonEnd + 1);
        }

        return cleaned.trim();
    }

    /**
     * Parses a JSON string into a JsonNode, with markdown cleaning.
     *
     * @param objectMapper Jackson ObjectMapper
     * @param jsonString   JSON string (may contain markdown)
     * @return Parsed JsonNode
     * @throws JsonProcessingException if parsing fails
     */
    public static JsonNode parseJson(ObjectMapper objectMapper, String jsonString)
            throws JsonProcessingException {
        String cleaned = cleanJsonFromMarkdown(jsonString);
        return objectMapper.readTree(cleaned);
    }

    /**
     * Extracts a field from a JSON object or returns the object itself if it's an array.
     *
     * Useful for handling LLM responses that may return:
     * - Direct array: [...]
     * - Wrapped in object: {"results": [...], "metadata": {...}}
     *
     * @param node      Root JSON node
     * @param fieldName Field name to extract
     * @return The field value if object contains field, otherwise the node itself
     */
    public static JsonNode extractFieldOrSelf(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }

        // If node is already an array, return it
        if (node.isArray()) {
            return node;
        }

        // If node is an object, try to extract field
        if (node.isObject() && node.has(fieldName)) {
            return node.get(fieldName);
        }

        // Otherwise return the node itself
        return node;
    }
}
