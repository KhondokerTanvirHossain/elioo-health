package com.elioo.healthcare.gcp.translate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for GCP Translation API.
 *
 * <p>These properties configure the Google Cloud Translation API client behavior,
 * including project ID, credentials, translation settings, and performance tuning.</p>
 *
 * <p><b>Configuration Example (application-gcp.properties):</b></p>
 * <pre>
 * # GCP Project Configuration
 * gcp.project-id=my-gcp-project
 * gcp.credentials-path=classpath:gcp/service-account-key.json
 *
 * # Translation Configuration
 * gcp.translate.enabled=true
 * gcp.translate.default-source-language=bn
 * gcp.translate.target-language=en
 * gcp.translate.preserve-english=true
 * gcp.translate.model=nmt
 * gcp.translate.batch-size=100
 * gcp.translate.timeout-seconds=30
 * </pre>
 *
 * @since 0.1.0
 */
@Data
@ConfigurationProperties(prefix = "gcp.translate")
public class TranslateProperties {

    /**
     * Enable/disable translation service.
     * Default: true
     */
    private Boolean enabled = true;

    /**
     * GCP project ID (inherited from gcp.project-id).
     */
    private String projectId;

    /**
     * Default source language code for translation.
     * null = auto-detect
     * Examples: "bn" (Bangla), "hi" (Hindi), "es" (Spanish)
     * Default: null (auto-detect)
     */
    private String defaultSourceLanguage;

    /**
     * Target language code for translation.
     * Default: "en" (English)
     */
    private String targetLanguage = "en";

    /**
     * Whether to preserve English text as-is without re-translating.
     * When true, text detected as English will skip translation.
     * Default: true
     */
    private Boolean preserveEnglish = true;

    /**
     * Translation model to use.
     * Options:
     * - null (default): Use GCP's default NMT model
     * - "nmt": Neural Machine Translation (recommended)
     * - "base": Phrase-Based Machine Translation (legacy)
     * - "general/nmt": Specific NMT model
     * Default: null (GCP default)
     */
    private String model;

    /**
     * Batch size for batch translation operations.
     * Maximum number of texts to translate in a single batch.
     * Range: 1-100
     * Default: 100
     */
    private Integer batchSize = 100;

    /**
     * Timeout for translation requests in seconds.
     * Default: 30 seconds
     */
    private Integer timeoutSeconds = 30;

    /**
     * Number of retry attempts for failed translation requests.
     * Default: 2
     */
    private Integer retryAttempts = 2;

    /**
     * MIME type for translation requests.
     * Options: "text/plain", "text/html"
     * Default: "text/plain"
     */
    private String mimeType = "text/plain";

    /**
     * Glossary name for domain-specific translation (optional).
     * Format: "projects/{project-id}/locations/{location}/glossaries/{glossary-id}"
     * Default: null (no glossary)
     */
    private String glossaryName;

    /**
     * Location/region for translation API.
     * Default: "global"
     */
    private String location = "global";

    // ==================== Validation Methods ====================

    /**
     * Check if translation is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    /**
     * Check if auto-detect is enabled (no default source language).
     *
     * @return true if auto-detect
     */
    public boolean isAutoDetect() {
        return defaultSourceLanguage == null || defaultSourceLanguage.isBlank();
    }

    /**
     * Validate configuration.
     *
     * @return true if valid
     */
    public boolean isValid() {
        return projectId != null && !projectId.isBlank() &&
               targetLanguage != null && !targetLanguage.isBlank();
    }
}
