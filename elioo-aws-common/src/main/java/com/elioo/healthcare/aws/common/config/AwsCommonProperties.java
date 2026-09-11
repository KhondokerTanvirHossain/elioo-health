package com.elioo.healthcare.aws.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Common AWS configuration properties shared across all services.
 *
 * Configuration in application.properties:
 * <pre>
 * aws.region=us-east-1
 * aws.access-key-id=AKIA...
 * aws.secret-access-key=secret...
 * aws.retry.max-attempts=3
 * aws.retry.backoff-base-delay-ms=100
 * </pre>
 *
 * Credentials Strategy:
 * 1. If access-key-id and secret-access-key are configured → Use static credentials
 * 2. Otherwise → Use AWS default credentials chain (IAM roles, env vars, ~/.aws/credentials)
 */
@ConfigurationProperties(prefix = "aws")
public class AwsCommonProperties {

    /**
     * AWS region (e.g., us-east-1, eu-west-1).
     * Default: us-east-1
     */
    private String region = "us-east-1";

    /**
     * AWS access key ID (optional).
     * If not provided, uses default credentials provider chain.
     */
    private String accessKeyId;

    /**
     * AWS secret access key (optional).
     * If not provided, uses default credentials provider chain.
     */
    private String secretAccessKey;

    /**
     * Retry configuration for AWS SDK clients.
     */
    private RetryConfig retry = new RetryConfig();

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(String secretAccessKey) {
        this.secretAccessKey = secretAccessKey;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    /**
     * Checks if static credentials are configured.
     */
    public boolean hasStaticCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank();
    }

    public static class RetryConfig {
        /**
         * Maximum number of retry attempts.
         * Default: 3
         */
        private int maxAttempts = 3;

        /**
         * Base delay for exponential backoff (milliseconds).
         * Default: 100ms
         */
        private int backoffBaseDelayMs = 100;

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
    }
}
