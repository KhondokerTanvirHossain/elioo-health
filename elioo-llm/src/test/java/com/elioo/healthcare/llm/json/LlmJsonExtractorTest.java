package com.elioo.healthcare.llm.json;

import com.elioo.healthcare.llm.exception.LlmException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJsonExtractorTest {

    @Test
    void returnsPlainJsonUnchanged() {
        assertThat(LlmJsonExtractor.extract("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsMarkdownFences() {
        assertThat(LlmJsonExtractor.extract("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(LlmJsonExtractor.extract("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void ignoresProseAroundTheObject() {
        String content = "Here is the analysis:\n{\"summary\":\"ok\",\"n\":{\"x\":[1,2]}}\nLet me know if you need more.";
        assertThat(LlmJsonExtractor.extract(content)).isEqualTo("{\"summary\":\"ok\",\"n\":{\"x\":[1,2]}}");
    }

    @Test
    void handlesBracesInsideStrings() {
        String content = "{\"text\":\"a } b { c\",\"ok\":true}";
        assertThat(LlmJsonExtractor.extract(content)).isEqualTo(content);
    }

    @Test
    void supportsTopLevelArrays() {
        assertThat(LlmJsonExtractor.extract("Result: [{\"a\":1},{\"b\":2}] done")).isEqualTo("[{\"a\":1},{\"b\":2}]");
    }

    @Test
    void throwsWhenNoJsonPresent() {
        assertThatThrownBy(() -> LlmJsonExtractor.extract("I cannot help with that."))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("No JSON");
    }

    @Test
    void throwsOnNull() {
        assertThatThrownBy(() -> LlmJsonExtractor.extract(null)).isInstanceOf(LlmException.class);
    }
}
