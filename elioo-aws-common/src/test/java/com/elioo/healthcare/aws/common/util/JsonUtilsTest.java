package com.elioo.healthcare.aws.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonUtils Test")
class JsonUtilsTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should clean JSON from markdown code block")
    void testCleanJsonFromMarkdown_WithCodeBlock() {
        // Given: JSON wrapped in markdown code block
        String markdown = """
                Here's the result:
                ```json
                {"key": "value"}
                ```
                """;

        // When: Clean markdown
        String cleaned = JsonUtils.cleanJsonFromMarkdown(markdown);

        // Then: Should extract JSON
        assertThat(cleaned).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    @DisplayName("Should handle JSON without markdown")
    void testCleanJsonFromMarkdown_PlainJson() {
        // Given: Plain JSON
        String json = "{\"key\": \"value\"}";

        // When: Clean (no markdown to clean)
        String cleaned = JsonUtils.cleanJsonFromMarkdown(json);

        // Then: Should return unchanged
        assertThat(cleaned).isEqualTo(json);
    }

    @Test
    @DisplayName("Should clean JSON with leading text")
    void testCleanJsonFromMarkdown_WithLeadingText() {
        // Given: JSON with leading explanation
        String withText = "Here's the data: {\"key\": \"value\"}";

        // When: Clean
        String cleaned = JsonUtils.cleanJsonFromMarkdown(withText);

        // Then: Should extract JSON
        assertThat(cleaned).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    @DisplayName("Should clean array JSON from markdown")
    void testCleanJsonFromMarkdown_Array() {
        // Given: Array wrapped in markdown
        String markdown = """
                ```json
                [{"id": 1}, {"id": 2}]
                ```
                """;

        // When: Clean
        String cleaned = JsonUtils.cleanJsonFromMarkdown(markdown);

        // Then: Should extract array
        assertThat(cleaned).isEqualTo("[{\"id\": 1}, {\"id\": 2}]");
    }

    @Test
    @DisplayName("Should parse JSON with markdown cleaning")
    void testParseJson_WithMarkdown() throws Exception {
        // Given: JSON in markdown
        String markdown = """
                ```json
                {"name": "test", "value": 123}
                ```
                """;

        // When: Parse JSON
        JsonNode node = JsonUtils.parseJson(objectMapper, markdown);

        // Then: Should parse correctly
        assertThat(node.get("name").asText()).isEqualTo("test");
        assertThat(node.get("value").asInt()).isEqualTo(123);
    }

    @Test
    @DisplayName("Should extract field from object")
    void testExtractFieldOrSelf_Object() throws Exception {
        // Given: Object with nested field
        String json = "{\"results\": [{\"id\": 1}], \"metadata\": {}}";
        JsonNode node = objectMapper.readTree(json);

        // When: Extract "results" field
        JsonNode extracted = JsonUtils.extractFieldOrSelf(node, "results");

        // Then: Should return the array
        assertThat(extracted.isArray()).isTrue();
        assertThat(extracted.size()).isEqualTo(1);
        assertThat(extracted.get(0).get("id").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should return array as-is when root is array")
    void testExtractFieldOrSelf_Array() throws Exception {
        // Given: Root is an array
        String json = "[{\"id\": 1}, {\"id\": 2}]";
        JsonNode node = objectMapper.readTree(json);

        // When: Try to extract field (should return array itself)
        JsonNode extracted = JsonUtils.extractFieldOrSelf(node, "results");

        // Then: Should return the array unchanged
        assertThat(extracted.isArray()).isTrue();
        assertThat(extracted.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle null input")
    void testCleanJsonFromMarkdown_Null() {
        // When: Clean null
        String result = JsonUtils.cleanJsonFromMarkdown(null);

        // Then: Should return null
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle empty string")
    void testCleanJsonFromMarkdown_Empty() {
        // When: Clean empty string
        String result = JsonUtils.cleanJsonFromMarkdown("");

        // Then: Should return empty
        assertThat(result).isEmpty();
    }
}
