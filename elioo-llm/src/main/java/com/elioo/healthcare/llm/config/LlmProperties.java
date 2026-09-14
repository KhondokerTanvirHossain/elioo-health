package com.elioo.healthcare.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code llm.*} settings. Exactly one provider is active, chosen by {@link #provider}.
 */
@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    /** groq | anthropic | openai | bedrock */
    private String provider = "groq";
    private int defaultMaxTokens = 8192;
    private double defaultTemperature = 0.3;
    private int timeoutSeconds = 120;

    private Anthropic anthropic = new Anthropic();
    private OpenAiCompatible groq = new OpenAiCompatible("https://api.groq.com/openai/v1", "openai/gpt-oss-120b");
    private OpenAiCompatible openai = new OpenAiCompatible("https://api.openai.com/v1", "gpt-4o");

    @Data
    public static class Anthropic {
        private String apiKey = "";
        private String model = "claude-opus-5";
        /** low | medium | high | xhigh | max */
        private String effort = "medium";
    }

    @Data
    public static class OpenAiCompatible {
        /**
         * Whether the configured model accepts image content parts. Text-only models (e.g.
         * openai/gpt-oss-120b) reject an array-shaped user message outright, so this must stay false
         * for them; set it true for a vision model (e.g. qwen/qwen3.8-27b on Groq).
         */
        private boolean supportsImages = false;

        private String apiKey = "";
        private String baseUrl;
        private String model;

        public OpenAiCompatible() {
        }

        public OpenAiCompatible(String baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }
    }
}
