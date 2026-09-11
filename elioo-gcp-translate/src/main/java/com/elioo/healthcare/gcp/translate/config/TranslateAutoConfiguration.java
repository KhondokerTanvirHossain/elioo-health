package com.elioo.healthcare.gcp.translate.config;

import com.elioo.healthcare.gcp.common.config.GcpCommonProperties;
import com.elioo.healthcare.gcp.common.exception.GcpConfigurationException;
import com.elioo.healthcare.gcp.translate.api.TranslationService;
import com.elioo.healthcare.gcp.translate.service.TranslationServiceImpl;
import com.google.auth.Credentials;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.google.cloud.translate.v3.TranslationServiceSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import jakarta.annotation.PostConstruct;
import java.io.IOException;

/**
 * Spring Boot auto-configuration for Google Cloud Translation API.
 *
 * <p>This configuration is automatically discovered by Spring Boot via the
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * file.</p>
 *
 * <p><b>Provides the following beans:</b></p>
 * <ul>
 *   <li>{@link TranslationServiceClient} - GCP Translation API client</li>
 *   <li>{@link TranslationService} - Translation service implementation</li>
 * </ul>
 *
 * <p><b>Configuration example (application-gcp.properties):</b></p>
 * <pre>
 * # GCP Common Configuration
 * gcp.enabled=true
 * gcp.project-id=medscribe-ai-prod
 * gcp.credentials-path=classpath:gcp/service-account-key.json
 *
 * # Translation Configuration
 * gcp.translate.enabled=true
 * gcp.translate.default-source-language=bn
 * gcp.translate.target-language=en
 * gcp.translate.preserve-english=true
 * </pre>
 *
 * <p><b>Activation:</b></p>
 * <ul>
 *   <li>Requires: {@code gcp.enabled=true}</li>
 *   <li>Requires: {@code gcp.translate.enabled=true}</li>
 *   <li>Requires: GCP Translation API library on classpath</li>
 * </ul>
 *
 * <p><b>Dependencies:</b></p>
 * <ul>
 *   <li>Requires: elioo-gcp-common module (for credentials)</li>
 *   <li>Requires: google-cloud-translate library</li>
 * </ul>
 *
 * @see TranslateProperties
 * @see TranslationService
 * @see TranslationServiceImpl
 * @since 0.1.0
 */
@Slf4j
@AutoConfiguration
@RequiredArgsConstructor
@ConditionalOnClass(TranslationServiceClient.class)
@ConditionalOnProperty(name = "gcp.translate.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties({TranslateProperties.class, GcpCommonProperties.class})
public class TranslateAutoConfiguration {

    private final TranslateProperties translateProperties;
    private final GcpCommonProperties gcpProperties;

    /**
     * Initialize TranslateProperties with project ID from GcpCommonProperties.
     */
    @PostConstruct
    public void init() {
        // Inherit project ID from common GCP configuration
        if (translateProperties.getProjectId() == null || translateProperties.getProjectId().isBlank()) {
            translateProperties.setProjectId(gcpProperties.getProjectId());
            log.debug("Inherited project ID from gcp.project-id: {}", gcpProperties.getProjectId());
        }

        // Validate configuration
        if (!translateProperties.isValid()) {
            throw new GcpConfigurationException(
                    "Invalid translation configuration: projectId and targetLanguage are required"
            );
        }

        log.info("Translation configuration:");
        log.info("  Project ID: {}", translateProperties.getProjectId());
        log.info("  Default Source Language: {}",
                translateProperties.isAutoDetect() ? "auto-detect" : translateProperties.getDefaultSourceLanguage());
        log.info("  Target Language: {}", translateProperties.getTargetLanguage());
        log.info("  Preserve English: {}", translateProperties.getPreserveEnglish());
        log.info("  Batch Size: {}", translateProperties.getBatchSize());
    }

    /**
     * Creates Google Cloud Translation API client bean.
     *
     * <p>This bean is configured with:</p>
     * <ul>
     *   <li>GCP credentials from {@link com.elioo.healthcare.gcp.common.config.GcpCommonAutoConfiguration}</li>
     *   <li>Retry settings from {@link GcpCommonProperties}</li>
     *   <li>Timeout settings from {@link TranslateProperties}</li>
     * </ul>
     *
     * @param gcpCredentials GCP credentials bean (auto-injected from GcpCommonAutoConfiguration)
     * @return Configured TranslationServiceClient
     * @throws GcpConfigurationException if client creation fails
     */
    @Bean
    @ConditionalOnMissingBean
    public TranslationServiceClient translationServiceClient(Credentials gcpCredentials) {
        log.info("Initializing GCP Translation API client");
        log.debug("GCP Project ID: {}", gcpProperties.getProjectId());

        try {
            TranslationServiceSettings settings = TranslationServiceSettings.newBuilder()
                    .setCredentialsProvider(() -> gcpCredentials)
                    .build();

            TranslationServiceClient client = TranslationServiceClient.create(settings);
            log.info("GCP Translation API client initialized successfully");
            return client;

        } catch (IOException e) {
            String errorMsg = "Failed to create TranslationServiceClient: " + e.getMessage();
            log.error(errorMsg, e);
            throw new GcpConfigurationException(errorMsg, e);
        }
    }

    /**
     * Creates TranslationService implementation bean.
     *
     * <p>This bean provides the high-level translation API used by application code.</p>
     *
     * @param translationClient GCP Translation API client
     * @param properties Translation configuration properties
     * @return TranslationService implementation
     */
    @Bean
    @ConditionalOnMissingBean
    public TranslationService translationService(
            TranslationServiceClient translationClient,
            TranslateProperties properties
    ) {
        log.info("Creating TranslationService bean");
        return new TranslationServiceImpl(translationClient, properties);
    }
}
