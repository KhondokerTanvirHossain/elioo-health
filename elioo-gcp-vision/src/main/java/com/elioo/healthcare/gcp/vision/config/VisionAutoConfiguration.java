package com.elioo.healthcare.gcp.vision.config;

import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.service.VisionServiceImpl;
import com.google.auth.Credentials;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/**
 * Spring Boot auto-configuration for Google Cloud Vision API.
 *
 * <p>This configuration is automatically discovered by Spring Boot via the
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * file.</p>
 *
 * <p><b>Provides the following beans:</b></p>
 * <ul>
 *   <li>{@link ImageAnnotatorClient} - Google Cloud Vision API client</li>
 *   <li>{@link VisionService} - OCR service interface implementation</li>
 * </ul>
 *
 * <p><b>Configuration example:</b></p>
 * <pre>
 * gcp:
 *   vision:
 *     enabled: true
 *     min-confidence-threshold: 0.80
 *     default-language-hints: bn,en
 * </pre>
 *
 * <p><b>Dependencies:</b></p>
 * <ul>
 *   <li>Requires: elioo-gcp-common (for GCP credentials)</li>
 *   <li>Requires: google-cloud-vision library</li>
 *   <li>Optional: Spring Boot autoconfigure</li>
 * </ul>
 *
 * @see VisionProperties
 * @see VisionService
 * @since 0.1.0
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(ImageAnnotatorClient.class)
@ConditionalOnProperty(name = "gcp.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(VisionProperties.class)
public class VisionAutoConfiguration {

    /**
     * Creates Google Cloud Vision ImageAnnotatorClient.
     *
     * <p>The client is configured with credentials from {@code elioo-gcp-common}
     * module's {@link Credentials} bean.</p>
     *
     * <p><b>Client Configuration:</b></p>
     * <ul>
     *   <li>Credentials: From GCP common module</li>
     *   <li>Endpoint: Default (vision.googleapis.com)</li>
     *   <li>Connection pooling: Enabled (default)</li>
     *   <li>Retry: Default retry settings from GCP SDK</li>
     * </ul>
     *
     * @param credentials GCP credentials (from elioo-gcp-common)
     * @return Configured ImageAnnotatorClient
     * @throws IOException if client initialization fails
     */
    @Bean
    @ConditionalOnMissingBean
    public ImageAnnotatorClient imageAnnotatorClient(Credentials credentials) throws IOException {
        log.info("Initializing Google Cloud Vision ImageAnnotatorClient");

        ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();

        ImageAnnotatorClient client = ImageAnnotatorClient.create(settings);
        log.info("Google Cloud Vision ImageAnnotatorClient initialized successfully");

        return client;
    }

    /**
     * Creates VisionService bean (Google Cloud Vision implementation).
     *
     * <p>This bean provides the OCR service contract defined in {@link VisionService}
     * interface, implemented using Google Cloud Vision API.</p>
     *
     * @param client     ImageAnnotatorClient for Vision API calls
     * @param properties Vision configuration properties
     * @return Configured VisionService (interface)
     */
    @Bean
    @ConditionalOnMissingBean
    public VisionService visionService(
            ImageAnnotatorClient client,
            VisionProperties properties
    ) {
        log.info("Configuring Vision service (Google Cloud Vision implementation)");
        log.info("  - Min confidence threshold: {}", properties.getMinConfidenceThreshold());
        log.info("  - Max image size: {} MB", properties.getMaxImageSizeMb());
        log.info("  - Default language hints: {}", properties.getDefaultLanguageHints());
        log.info("  - Timeout: {} ms", properties.getTimeoutMs());

        return new VisionServiceImpl(client, properties);
    }
}
