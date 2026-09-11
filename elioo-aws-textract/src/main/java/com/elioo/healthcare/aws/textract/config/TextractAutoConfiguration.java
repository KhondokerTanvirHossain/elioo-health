package com.elioo.healthcare.aws.textract.config;

import com.elioo.healthcare.aws.textract.api.TextractService;
import com.elioo.healthcare.aws.textract.service.TextractServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractAsyncClient;

/**
 * Spring Boot auto-configuration for AWS Textract.
 *
 * This configuration is automatically discovered by Spring Boot via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 *
 * Provides:
 * - TextractAsyncClient (AWS SDK client)
 * - TextractService (generic OCR service)
 *
 * Configuration example:
 * <pre>
 * aws:
 *   textract:
 *     enabled: true
 *     min-confidence-threshold: 0.80
 *     max-image-size-mb: 10
 * </pre>
 *
 * Depends on:
 * - elioo-aws-common (for credentials and region)
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(TextractAsyncClient.class)
@ConditionalOnProperty(prefix = "aws.textract", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TextractProperties.class)
public class TextractAutoConfiguration {

    /**
     * Creates AWS Textract async client.
     *
     * @param credentialsProvider AWS credentials (from common module)
     * @param region              AWS region (from common module)
     * @return Configured TextractAsyncClient
     */
    @Bean
    @ConditionalOnMissingBean
    public TextractAsyncClient textractAsyncClient(
            AwsCredentialsProvider credentialsProvider,
            Region region
    ) {
        log.info("Initializing Amazon Textract client in region: {}", region);

        return TextractAsyncClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();
    }

    /**
     * Creates Textract service bean (AWS implementation).
     *
     * @param textractClient AWS Textract client
     * @param properties     Textract configuration properties
     * @return Configured TextractService (interface)
     */
    @Bean
    @ConditionalOnMissingBean
    public TextractService textractService(
            TextractAsyncClient textractClient,
            TextractProperties properties
    ) {
        log.info("Configuring Textract service (AWS implementation) - min confidence: {}, max image size: {} MB",
                properties.getMinConfidenceThreshold(),
                properties.getMaxImageSizeMb());

        return new TextractServiceImpl(textractClient, properties);
    }
}
