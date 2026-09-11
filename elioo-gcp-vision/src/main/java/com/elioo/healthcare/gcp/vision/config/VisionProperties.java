package com.elioo.healthcare.gcp.vision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for Google Cloud Vision API.
 *
 * <p>This class binds properties with prefix "gcp.vision" from application configuration files.</p>
 *
 * <p><b>Configuration example:</b></p>
 * <pre>
 * gcp:
 *   vision:
 *     enabled: true
 *     min-confidence-threshold: 0.80
 *     max-image-size-mb: 20
 *     min-image-size-mb: 0.05
 *     default-language-hints: bn,en
 *     timeout-ms: 30000
 * </pre>
 *
 * @see VisionAutoConfiguration
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "gcp.vision")
public class VisionProperties {

    /**
     * Enable/disable Vision API integration.
     * Default: true
     */
    private boolean enabled = true;

    /**
     * Minimum confidence threshold for accepting OCR results (0.0 - 1.0).
     * Blocks with confidence below this threshold may be filtered out.
     * Default: 0.80 (80%)
     */
    private double minConfidenceThreshold = 0.80;

    /**
     * Maximum allowed image size in megabytes.
     * Google Cloud Vision supports up to 20 MB for synchronous operations.
     * Default: 20 MB
     */
    private int maxImageSizeMb = 20;

    /**
     * Minimum image size in megabytes.
     * Images smaller than this may lack sufficient detail for accurate OCR.
     * Default: 0.05 MB (50 KB)
     */
    private double minImageSizeMb = 0.05;

    /**
     * Default language hints for OCR operations (comma-separated ISO 639-1 codes).
     * Used when client doesn't specify language hints in request.
     * Examples: "en", "bn,en", "hi,en"
     * Default: "en"
     */
    private String defaultLanguageHints = "en";

    /**
     * Timeout for Vision API calls (milliseconds).
     * Default: 30000 (30 seconds)
     */
    private int timeoutMs = 30000;

    /**
     * Maximum number of concurrent Vision API calls.
     * Used for rate limiting and resource management.
     * Default: 10
     */
    private int maxConcurrentCalls = 10;

    /**
     * Enable detailed logging for Vision API requests/responses.
     * Useful for debugging but may impact performance.
     * Default: false
     */
    private boolean enableDetailedLogging = false;

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getMinConfidenceThreshold() {
        return minConfidenceThreshold;
    }

    public void setMinConfidenceThreshold(double minConfidenceThreshold) {
        this.minConfidenceThreshold = minConfidenceThreshold;
    }

    public int getMaxImageSizeMb() {
        return maxImageSizeMb;
    }

    public void setMaxImageSizeMb(int maxImageSizeMb) {
        this.maxImageSizeMb = maxImageSizeMb;
    }

    public double getMinImageSizeMb() {
        return minImageSizeMb;
    }

    public void setMinImageSizeMb(double minImageSizeMb) {
        this.minImageSizeMb = minImageSizeMb;
    }

    public String getDefaultLanguageHints() {
        return defaultLanguageHints;
    }

    public void setDefaultLanguageHints(String defaultLanguageHints) {
        this.defaultLanguageHints = defaultLanguageHints;
    }

    /**
     * Get default language hints as a list.
     *
     * @return List of language codes
     */
    public List<String> getDefaultLanguageHintsAsList() {
        if (defaultLanguageHints == null || defaultLanguageHints.isBlank()) {
            return List.of("en");
        }
        return List.of(defaultLanguageHints.split(","));
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public void setMaxConcurrentCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    public boolean isEnableDetailedLogging() {
        return enableDetailedLogging;
    }

    public void setEnableDetailedLogging(boolean enableDetailedLogging) {
        this.enableDetailedLogging = enableDetailedLogging;
    }
}
