package com.elioo.healthcare.aws.bedrock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AWS Bedrock integration.
 *
 * Configure via application properties:
 * <pre>
 * aws.bedrock.enabled=true
 * aws.bedrock.model-id=anthropic.claude-3-5-sonnet-20241022-v2:0
 * aws.bedrock.max-tokens=4096
 * aws.bedrock.temperature=0.7
 * aws.bedrock.top-p=0.9
 * </pre>
 */
@ConfigurationProperties(prefix = "aws.bedrock")
public class BedrockProperties {

    /**
     * Enable/disable Bedrock integration.
     */
    private boolean enabled = true;

    /**
     * Default Bedrock model ID.
     * Common models:
     * - anthropic.claude-3-5-sonnet-20241022-v2:0 (Claude 3.5 Sonnet)
     * - anthropic.claude-3-sonnet-20240229-v1:0 (Claude 3 Sonnet)
     * - anthropic.claude-3-haiku-20240307-v1:0 (Claude 3 Haiku)
     * - amazon.titan-text-express-v1 (Amazon Titan)
     * - meta.llama3-70b-instruct-v1:0 (Meta Llama 3)
     */
    private String modelId = "anthropic.claude-3-5-sonnet-20241022-v2:0";

    /**
     * Maximum tokens to generate in response.
     */
    private int maxTokens = 4096;

    /**
     * Temperature for generation (0.0-1.0).
     * Lower = more deterministic, Higher = more creative.
     */
    private double temperature = 0.7;

    /**
     * Top-P nucleus sampling parameter (0.0-1.0).
     * Controls diversity via nucleus sampling.
     */
    private Double topP;

    /**
     * Request timeout in seconds.
     */
    private int timeoutSeconds = 120;

    /**
     * Maximum retry attempts on failure.
     */
    private int maxRetries = 3;

    /**
     * Enable request/response logging for debugging.
     */
    private boolean logRequests = false;

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isLogRequests() {
        return logRequests;
    }

    public void setLogRequests(boolean logRequests) {
        this.logRequests = logRequests;
    }
}
