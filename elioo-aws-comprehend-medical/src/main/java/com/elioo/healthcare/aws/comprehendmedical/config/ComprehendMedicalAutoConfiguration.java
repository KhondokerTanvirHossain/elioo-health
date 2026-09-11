package com.elioo.healthcare.aws.comprehendmedical.config;

import com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService;
import com.elioo.healthcare.aws.comprehendmedical.service.ComprehendMedicalServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.comprehendmedical.ComprehendMedicalAsyncClient;

/**
 * Spring Boot auto-configuration for AWS Comprehend Medical.
 *
 * Depends on:
 * - elioo-aws-common (for credentials and region)
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(ComprehendMedicalAsyncClient.class)
@ConditionalOnProperty(prefix = "aws.comprehend-medical", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ComprehendMedicalProperties.class)
public class ComprehendMedicalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ComprehendMedicalAsyncClient comprehendMedicalAsyncClient(
            AwsCredentialsProvider credentialsProvider,
            Region region
    ) {
        log.info("Initializing Amazon Comprehend Medical client in region: {}", region);

        return ComprehendMedicalAsyncClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ComprehendMedicalService comprehendMedicalService(
            ComprehendMedicalAsyncClient comprehendMedicalClient,
            ComprehendMedicalProperties properties
    ) {
        log.info("Configuring Comprehend Medical service (AWS implementation) - min confidence: {}, max text length: {}",
                properties.getMinConfidenceThreshold(),
                properties.getMaxTextLength());

        return new ComprehendMedicalServiceImpl(comprehendMedicalClient, properties);
    }
}
