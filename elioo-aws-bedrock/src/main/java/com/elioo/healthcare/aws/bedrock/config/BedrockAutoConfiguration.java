package com.elioo.healthcare.aws.bedrock.config;

import com.elioo.healthcare.aws.bedrock.api.BedrockService;
import com.elioo.healthcare.aws.bedrock.service.BedrockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

import java.time.Duration;

/**
 * Spring Boot auto-configuration for AWS Bedrock integration.
 *
 * This configuration is automatically applied when:
 * 1. AWS Bedrock SDK is on the classpath
 * 2. Property aws.bedrock.enabled=true (default)
 *
 * Provides:
 * - BedrockRuntimeAsyncClient: AWS SDK async client
 * - BedrockService: Generic LLM invocation service
 * - BedrockProperties: Configuration properties
 *
 * To disable: set aws.bedrock.enabled=false
 *
 * Required dependencies from elioo-aws-common:
 * - AwsCredentialsProvider
 * - Region
 */
@AutoConfiguration
@ConditionalOnClass(BedrockRuntimeAsyncClient.class)
@ConditionalOnProperty(prefix = "aws.bedrock", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BedrockProperties.class)
public class BedrockAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BedrockAutoConfiguration.class);

    /**
     * Configure AWS Bedrock Runtime async client.
     *
     * Uses credentials and region from elioo-aws-common.
     * Configured with timeout from properties.
     *
     * @param credentialsProvider AWS credentials provider (from aws-common)
     * @param region              AWS region (from aws-common)
     * @param properties          Bedrock configuration properties
     * @return Configured BedrockRuntimeAsyncClient
     */
    @Bean
    @ConditionalOnMissingBean
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(
            AwsCredentialsProvider credentialsProvider,
            Region region,
            BedrockProperties properties
    ) {
        log.info("Configuring AWS Bedrock Runtime client for region: {}", region);
        log.info("Default model: {}", properties.getModelId());

        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());

        return BedrockRuntimeAsyncClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(timeout)
                        .writeTimeout(timeout))
                .overrideConfiguration(cfg -> cfg
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout))
                .build();
    }

    /**
     * Configure ObjectMapper for JSON serialization.
     * Reuses existing bean if available, otherwise creates a new one.
     *
     * @return ObjectMapper instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Configure Bedrock service bean (AWS implementation).
     *
     * Provides unified interface for invoking LLMs via AWS Bedrock.
     * Supports multiple model providers (Claude, Titan, Llama, etc.).
     *
     * @param bedrockClient  AWS Bedrock Runtime client
     * @param properties     Bedrock configuration
     * @param objectMapper   JSON object mapper
     * @return Configured BedrockService (interface)
     */
    @Bean
    @ConditionalOnMissingBean
    public BedrockService bedrockService(
            BedrockRuntimeAsyncClient bedrockClient,
            BedrockProperties properties,
            ObjectMapper objectMapper
    ) {
        log.info("Configuring Bedrock service (AWS implementation)");
        log.debug("Configuration - MaxTokens: {}, Temperature: {}, TopP: {}",
                properties.getMaxTokens(),
                properties.getTemperature(),
                properties.getTopP());

        return new BedrockServiceImpl(bedrockClient, properties, objectMapper);
    }

    /** Exposes Bedrock as the application's LlmClient only when llm.provider=bedrock. */
    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "bedrock")
    @ConditionalOnMissingBean(com.elioo.healthcare.llm.api.LlmClient.class)
    public com.elioo.healthcare.llm.api.LlmClient bedrockLlmClient(BedrockService bedrockService) {
        log.info("LLM provider: bedrock");
        return (com.elioo.healthcare.llm.api.LlmClient) bedrockService;
    }
}
