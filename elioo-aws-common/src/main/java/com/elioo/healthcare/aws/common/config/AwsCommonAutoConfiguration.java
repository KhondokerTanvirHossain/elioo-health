package com.elioo.healthcare.aws.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Spring Boot auto-configuration for common AWS infrastructure.
 *
 * This configuration is automatically discovered by Spring Boot via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 *
 * Provides:
 * - AWS credentials provider (static or default chain)
 * - AWS region configuration
 * - ObjectMapper for JSON processing
 *
 * Configuration example:
 * <pre>
 * aws:
 *   region: us-east-1
 *   access-key-id: AKIA...  # Optional
 *   secret-access-key: ...  # Optional
 * </pre>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(AwsCredentialsProvider.class)
@EnableConfigurationProperties(AwsCommonProperties.class)
public class AwsCommonAutoConfiguration {

    /**
     * Configure AWS credentials provider.
     *
     * Strategy:
     * 1. If access-key-id and secret-access-key are configured → StaticCredentialsProvider
     * 2. Otherwise → DefaultCredentialsProvider (IAM roles, env vars, ~/.aws/credentials)
     */
    @Bean
    @ConditionalOnMissingBean
    public AwsCredentialsProvider awsCredentialsProvider(AwsCommonProperties properties) {
        if (properties.hasStaticCredentials()) {
            log.info("Using static AWS credentials for region: {}", properties.getRegion());
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                            properties.getAccessKeyId(),
                            properties.getSecretAccessKey()
                    )
            );
        }

        log.info("Using default AWS credentials provider chain for region: {}", properties.getRegion());
        return DefaultCredentialsProvider.create();
    }

    /**
     * Provides the configured AWS region.
     */
    @Bean
    @ConditionalOnMissingBean(name = "awsRegion")
    public Region awsRegion(AwsCommonProperties properties) {
        log.debug("Configured AWS region: {}", properties.getRegion());
        return Region.of(properties.getRegion());
    }

    /**
     * ObjectMapper for JSON processing (used by Bedrock and other services).
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
