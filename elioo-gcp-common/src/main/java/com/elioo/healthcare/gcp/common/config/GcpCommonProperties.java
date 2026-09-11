package com.elioo.healthcare.gcp.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Google Cloud Platform services.
 *
 * <p>This class binds properties with prefix "gcp" from application configuration files.
 * It provides common GCP configuration used across all GCP service libraries.</p>
 *
 * <p><b>Configuration example:</b></p>
 * <pre>
 * gcp:
 *   project-id: my-gcp-project
 *   credentials-path: /path/to/service-account.json
 *   # OR for K8s/Docker environments:
 *   credentials-json: ${GCP_CREDENTIALS_JSON}
 *   retry:
 *     max-attempts: 3
 *     backoff-base-delay-ms: 100
 * </pre>
 *
 * <p><b>Credential Resolution Order:</b></p>
 * <ol>
 *   <li>credentialsPath - Load from file system</li>
 *   <li>credentialsJson - Load from environment variable/K8s secret</li>
 *   <li>Application Default Credentials (ADC) - Auto-discovery</li>
 * </ol>
 *
 * @see GcpCommonAutoConfiguration
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "gcp")
public class GcpCommonProperties {

    /**
     * Enable/disable GCP integration.
     * Default: false (only enabled when explicitly set to true)
     */
    private boolean enabled = false;

    /**
     * GCP Project ID.
     * Required for most GCP services.
     *
     * <p>Example: "medscribe-ai-prod"</p>
     */
    private String projectId;

    /**
     * Path to service account JSON key file.
     *
     * <p>Optional - if not specified, Application Default Credentials will be used.</p>
     * <p>Example: "/etc/secrets/gcp-service-account.json"</p>
     */
    private String credentialsPath;

    /**
     * Service account JSON key as inline string.
     *
     * <p>Useful for container deployments where credentials are injected as environment
     * variables or K8s secrets.</p>
     *
     * <p>Optional - if not specified, Application Default Credentials will be used.</p>
     * <p>Example: "${GCP_CREDENTIALS_JSON}"</p>
     */
    private String credentialsJson;

    /**
     * Retry configuration for GCP API calls.
     */
    private RetryConfig retry = new RetryConfig();

    /**
     * Check if explicit credentials are configured.
     *
     * @return true if credentialsPath or credentialsJson is set, false otherwise
     */
    public boolean hasExplicitCredentials() {
        return (credentialsPath != null && !credentialsPath.isBlank()) ||
               (credentialsJson != null && !credentialsJson.isBlank());
    }

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCredentialsPath() {
        return credentialsPath;
    }

    public void setCredentialsPath(String credentialsPath) {
        this.credentialsPath = credentialsPath;
    }

    public String getCredentialsJson() {
        return credentialsJson;
    }

    public void setCredentialsJson(String credentialsJson) {
        this.credentialsJson = credentialsJson;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    /**
     * Retry configuration for GCP API calls.
     */
    public static class RetryConfig {

        /**
         * Maximum number of retry attempts for failed API calls.
         * Default: 3
         */
        private int maxAttempts = 3;

        /**
         * Base delay in milliseconds for exponential backoff.
         * Default: 100ms
         */
        private int backoffBaseDelayMs = 100;

        /**
         * Maximum delay in milliseconds for exponential backoff.
         * Default: 10000ms (10 seconds)
         */
        private int backoffMaxDelayMs = 10000;

        // Getters and Setters

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getBackoffBaseDelayMs() {
            return backoffBaseDelayMs;
        }

        public void setBackoffBaseDelayMs(int backoffBaseDelayMs) {
            this.backoffBaseDelayMs = backoffBaseDelayMs;
        }

        public int getBackoffMaxDelayMs() {
            return backoffMaxDelayMs;
        }

        public void setBackoffMaxDelayMs(int backoffMaxDelayMs) {
            this.backoffMaxDelayMs = backoffMaxDelayMs;
        }
    }
}
