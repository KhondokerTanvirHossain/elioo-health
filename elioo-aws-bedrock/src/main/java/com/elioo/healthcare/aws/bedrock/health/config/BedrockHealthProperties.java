package com.elioo.healthcare.aws.bedrock.health.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Bedrock Health API.
 *
 * <p>Configure via application properties:</p>
 * <pre>
 * aws.bedrock.health.enabled=true
 * aws.bedrock.health.cache.enabled=true
 * aws.bedrock.health.cache.ttl-seconds=3600
 * aws.bedrock.health.default-audience=PATIENT
 * </pre>
 */
@ConfigurationProperties(prefix = "aws.bedrock.health")
public class BedrockHealthProperties {

    /**
     * Enable/disable Bedrock Health API.
     */
    private boolean enabled = true;

    /**
     * Default target audience for content generation.
     */
    private String defaultAudience = "PATIENT";

    /**
     * Cache configuration.
     */
    private CacheConfig cache = new CacheConfig();

    /**
     * Prompt customization.
     */
    private PromptConfig prompt = new PromptConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultAudience() {
        return defaultAudience;
    }

    public void setDefaultAudience(String defaultAudience) {
        this.defaultAudience = defaultAudience;
    }

    public CacheConfig getCache() {
        return cache;
    }

    public void setCache(CacheConfig cache) {
        this.cache = cache;
    }

    public PromptConfig getPrompt() {
        return prompt;
    }

    public void setPrompt(PromptConfig prompt) {
        this.prompt = prompt;
    }

    /**
     * Cache configuration.
     */
    public static class CacheConfig {
        /**
         * Enable/disable response caching.
         */
        private boolean enabled = true;

        /**
         * Cache TTL in seconds (default: 1 hour).
         */
        private int ttlSeconds = 3600;

        /**
         * Maximum cache entries.
         */
        private int maxEntries = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /**
     * Prompt customization configuration.
     */
    public static class PromptConfig {
        /**
         * Path to custom system prompt file (optional).
         */
        private String customSystemPromptPath;

        /**
         * Enable/disable verbose prompts (includes more context).
         */
        private boolean verbose = false;

        public String getCustomSystemPromptPath() {
            return customSystemPromptPath;
        }

        public void setCustomSystemPromptPath(String customSystemPromptPath) {
            this.customSystemPromptPath = customSystemPromptPath;
        }

        public boolean isVerbose() {
            return verbose;
        }

        public void setVerbose(boolean verbose) {
            this.verbose = verbose;
        }
    }
}
