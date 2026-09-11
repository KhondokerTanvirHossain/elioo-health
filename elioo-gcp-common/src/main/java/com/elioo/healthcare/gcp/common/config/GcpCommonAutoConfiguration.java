package com.elioo.healthcare.gcp.common.config;

import com.google.auth.Credentials;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Spring Boot auto-configuration for Google Cloud Platform common infrastructure.
 *
 * <p>This configuration is automatically discovered by Spring Boot via the
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * file.</p>
 *
 * <p><b>Provides the following beans:</b></p>
 * <ul>
 *   <li>{@link Credentials} - GCP credentials for authentication</li>
 * </ul>
 *
 * <p><b>Configuration example:</b></p>
 * <pre>
 * gcp:
 *   project-id: medscribe-ai-prod
 *   credentials-path: /etc/secrets/gcp-service-account.json
 * </pre>
 *
 * <p><b>Credential Resolution Strategy:</b></p>
 * <ol>
 *   <li><b>Service Account File:</b> If {@code gcp.credentials-path} is set, load from file</li>
 *   <li><b>Service Account JSON:</b> If {@code gcp.credentials-json} is set, parse inline JSON</li>
 *   <li><b>Application Default Credentials (ADC):</b> Auto-discovery from environment</li>
 * </ol>
 *
 * <p><b>Application Default Credentials (ADC) sources (in order):</b></p>
 * <ol>
 *   <li>GOOGLE_APPLICATION_CREDENTIALS environment variable</li>
 *   <li>Google Cloud SDK credentials (gcloud auth application-default login)</li>
 *   <li>Compute Engine/GKE/Cloud Run service account</li>
 * </ol>
 *
 * <p><b>Dependencies:</b></p>
 * <ul>
 *   <li>Requires: elioo-gcp-common module</li>
 *   <li>Optional: Spring Boot autoconfigure (for conditional bean creation)</li>
 * </ul>
 *
 * @see GcpCommonProperties
 * @see Credentials
 * @since 0.1.0
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(GoogleCredentials.class)
@ConditionalOnProperty(name = "gcp.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(GcpCommonProperties.class)
public class GcpCommonAutoConfiguration {

    /**
     * Creates GCP credentials bean for authentication.
     *
     * <p><b>Credential resolution order:</b></p>
     * <ol>
     *   <li>credentialsPath → Load service account from file system</li>
     *   <li>credentialsJson → Parse service account from environment variable</li>
     *   <li>Application Default Credentials (ADC) → Auto-discovery</li>
     * </ol>
     *
     * <p><b>Why use explicit service account vs ADC?</b></p>
     * <ul>
     *   <li><b>Explicit:</b> More control, works in any environment, easier debugging</li>
     *   <li><b>ADC:</b> Simpler configuration, follows GCP best practices, no secrets management</li>
     * </ul>
     *
     * @param properties GCP common properties from configuration
     * @return Configured GCP credentials, or placeholder credentials if none could be loaded
     */
    @Bean
    @ConditionalOnMissingBean(name = "gcpCredentials")
    public Credentials gcpCredentials(GcpCommonProperties properties) {
        log.info("Initializing GCP credentials");

        try {
            // Strategy 1: Load from file path
            if (properties.getCredentialsPath() != null && !properties.getCredentialsPath().isBlank()) {
                log.info("Loading GCP credentials from file: {}", properties.getCredentialsPath());
                return loadCredentialsFromFile(properties.getCredentialsPath());
            }

            // Strategy 2: Load from JSON string (K8s secrets, environment variables)
            if (properties.getCredentialsJson() != null && !properties.getCredentialsJson().isBlank()) {
                log.info("Loading GCP credentials from inline JSON");
                return loadCredentialsFromJson(properties.getCredentialsJson());
            }

            // Strategy 3: Application Default Credentials
            log.info("Using GCP Application Default Credentials (ADC)");
            log.debug("ADC will attempt to load credentials from:");
            log.debug("  1. GOOGLE_APPLICATION_CREDENTIALS environment variable");
            log.debug("  2. Google Cloud SDK credentials");
            log.debug("  3. Compute Engine/GKE/Cloud Run service account");
            return GoogleCredentials.getApplicationDefault();

        } catch (IOException e) {
            // Do not fail application startup: the rest of the system (AWS pipeline, DB,
            // query API, UI) is usable without GCP. Every GCP call will fail with an
            // authentication error until real credentials are supplied.
            log.warn("GCP credentials could not be loaded ({}). Starting with PLACEHOLDER credentials: "
                            + "Vision OCR and Translation calls WILL FAIL until gcp.credentials-path, "
                            + "gcp.credentials-json or Application Default Credentials are configured.",
                    e.getMessage());
            return placeholderCredentials();
        }
    }

    /**
     * Credentials that satisfy client construction but are rejected by every GCP API.
     * Used only when real credentials are unavailable so the application can still boot.
     */
    static Credentials placeholderCredentials() {
        return GoogleCredentials.create(new AccessToken("gcp-credentials-not-configured", null));
    }

    /**
     * Load credentials from file system or classpath.
     *
     * <p>Supports both file system paths and classpath resources:</p>
     * <ul>
     *   <li><b>File system:</b> /etc/secrets/gcp-service-account.json</li>
     *   <li><b>Classpath:</b> classpath:gcp/service-account.json</li>
     * </ul>
     *
     * @param credentialsPath Path to service account JSON file (file system or classpath)
     * @return Service account credentials
     * @throws IOException if file cannot be read or parsed
     */
    private Credentials loadCredentialsFromFile(String credentialsPath) throws IOException {
        InputStream inputStream = null;

        try {
            // Check if it's a classpath resource
            if (credentialsPath.startsWith("classpath:")) {
                String resourcePath = credentialsPath.substring("classpath:".length());
                log.debug("Loading credentials from classpath: {}", resourcePath);

                Resource resource = new ClassPathResource(resourcePath);
                if (!resource.exists()) {
                    throw new IOException("Classpath resource not found: " + resourcePath);
                }

                inputStream = resource.getInputStream();
                log.info("Successfully loaded GCP credentials from classpath: {}", resourcePath);

            } else {
                // Load from file system
                log.debug("Loading credentials from file system: {}", credentialsPath);
                inputStream = new FileInputStream(credentialsPath);
                log.info("Successfully loaded GCP credentials from file system");
            }

            Credentials credentials = ServiceAccountCredentials.fromStream(inputStream);
            return credentials;

        } catch (IOException e) {
            log.error("Failed to load credentials from: {}", credentialsPath, e);
            throw new IOException("Cannot read credentials from: " + credentialsPath, e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.warn("Failed to close input stream", e);
                }
            }
        }
    }

    /**
     * Load credentials from JSON string.
     *
     * @param credentialsJson Service account JSON as string
     * @return Service account credentials
     * @throws IOException if JSON cannot be parsed
     */
    private Credentials loadCredentialsFromJson(String credentialsJson) throws IOException {
        try {
            byte[] jsonBytes = credentialsJson.getBytes();
            try (ByteArrayInputStream bis = new ByteArrayInputStream(jsonBytes)) {
                Credentials credentials = ServiceAccountCredentials.fromStream(bis);
                log.info("Successfully loaded GCP credentials from inline JSON");
                return credentials;
            }
        } catch (IOException e) {
            log.error("Failed to parse credentials JSON", e);
            throw new IOException("Cannot parse credentials JSON", e);
        }
    }
}
