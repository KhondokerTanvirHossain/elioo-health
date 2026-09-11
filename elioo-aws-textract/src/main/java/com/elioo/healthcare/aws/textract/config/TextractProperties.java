package com.elioo.healthcare.aws.textract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AWS Textract service.
 *
 * Configuration in application.properties:
 * <pre>
 * aws.textract.enabled=true
 * aws.textract.min-confidence-threshold=0.80
 * aws.textract.max-image-size-mb=10
 * aws.textract.default-feature-types=TABLES,FORMS,LAYOUT
 * </pre>
 */
@ConfigurationProperties(prefix = "aws.textract")
public class TextractProperties {

    /**
     * Enable/disable Textract integration.
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
     * Textract has a limit of 10 MB for synchronous operations.
     * Default: 10 MB
     */
    private int maxImageSizeMb = 10;

    /**
     * Minimum image size in megabytes.
     * Images smaller than this may lack sufficient detail for accurate OCR.
     * Default: 0.1 MB (100 KB)
     */
    private double minImageSizeMb = 0.1;

    /**
     * Default feature types to extract (comma-separated).
     * Options: TABLES, FORMS, LAYOUT, SIGNATURES
     * Default: TABLES,FORMS,LAYOUT
     */
    private String defaultFeatureTypes = "TABLES,FORMS,LAYOUT";

    /**
     * Timeout for Textract API calls (milliseconds).
     * Default: 30000 (30 seconds)
     */
    private int timeoutMs = 30000;

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

    public String getDefaultFeatureTypes() {
        return defaultFeatureTypes;
    }

    public void setDefaultFeatureTypes(String defaultFeatureTypes) {
        this.defaultFeatureTypes = defaultFeatureTypes;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
