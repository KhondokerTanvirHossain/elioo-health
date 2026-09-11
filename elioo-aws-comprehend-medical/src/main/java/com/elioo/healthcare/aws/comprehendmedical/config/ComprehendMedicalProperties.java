package com.elioo.healthcare.aws.comprehendmedical.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AWS Comprehend Medical service.
 *
 * Configuration in application.properties:
 * <pre>
 * aws.comprehend-medical.enabled=true
 * aws.comprehend-medical.min-confidence-threshold=0.70
 * aws.comprehend-medical.max-text-length=20000
 * </pre>
 */
@ConfigurationProperties(prefix = "aws.comprehend-medical")
public class ComprehendMedicalProperties {

    /**
     * Enable/disable Comprehend Medical integration.
     * Default: true
     */
    private boolean enabled = true;

    /**
     * Minimum confidence threshold for accepting entity detections (0.0 - 1.0).
     * Entities with confidence below this threshold may be filtered out.
     * Default: 0.70 (70%)
     */
    private double minConfidenceThreshold = 0.70;

    /**
     * Maximum allowed text length in characters.
     * AWS Comprehend Medical has a limit of 20,000 characters.
     * Default: 20000
     */
    private int maxTextLength = 20000;

    /**
     * Enable PHI (Protected Health Information) detection.
     * Default: false (for performance)
     */
    private boolean detectPhi = false;

    /**
     * Timeout for Comprehend Medical API calls (milliseconds).
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

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public boolean isDetectPhi() {
        return detectPhi;
    }

    public void setDetectPhi(boolean detectPhi) {
        this.detectPhi = detectPhi;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
