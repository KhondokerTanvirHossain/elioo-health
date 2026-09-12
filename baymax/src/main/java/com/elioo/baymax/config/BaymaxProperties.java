package com.elioo.baymax.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code baymax.*} settings. Defaults are chosen so that the module is invisible unless
 * explicitly enabled, and so that its Flyway connection follows MedScribe's
 * ({@code spring.flyway.*}) unless overridden.
 */
@Data
@ConfigurationProperties(prefix = "baymax")
public class BaymaxProperties {

    /** Master switch for the whole module. */
    private boolean enabled = false;

    private Flyway flyway = new Flyway();
    private Llm llm = new Llm();
    private Vision vision = new Vision();
    private Admin admin = new Admin();
    private Storage storage = new Storage();

    @Data
    public static class Flyway {
        /** Run the Baymax migrations at startup. */
        private boolean enabled = true;
        /** Let Flyway create the schema if it is missing (false where the DB role cannot CREATE SCHEMA). */
        private boolean createSchemas = true;
        /** JDBC URL; defaults to {@code spring.flyway.url} via application.properties. */
        private String url;
        private String user;
        private String password;
    }

    @Data
    public static class Llm {
        /**
         * List prices in USD per 1M tokens, keyed by provider then model id, e.g.
         * {@code baymax.llm.prices.groq.[openai/gpt-oss-120b].input=0.15}. Model ids contain
         * slashes and dots, so the model key must be written in brackets. A missing entry means
         * the call is logged with a NULL cost and a WARN, never an invented number.
         */
        private Map<String, Map<String, Price>> prices = new LinkedHashMap<>();
    }

    @Data
    public static class Price {
        private BigDecimal input;
        private BigDecimal output;
    }

    @Data
    public static class Vision {
        /** USD per image for Cloud Vision document text detection; null = unpriced (NULL cost + WARN). */
        private BigDecimal costPerImage;
    }

    @Data
    public static class Admin {
        /** Shared secret expected in the {@code X-Baymax-Admin-Token} header on /api/v1/baymax/admin/**. */
        private String token;
    }

    @Data
    public static class Storage {
        /** S3-compatible endpoint (blank = AWS S3 for the region; Supabase: https://<project>.storage.supabase.co/storage/v1/s3). */
        private String endpoint;
        private String region = "ap-south-1";
        /** Private bucket for pages and crops. Blank = storage not configured (module still loads; calls fail). */
        private String bucket;
        private String accessKey;
        private String secretKey;
        /** Path-style addressing; required by Supabase and MinIO. */
        private boolean pathStyle = true;
        /** Value for x-amz-server-side-encryption on every PUT ("AES256"); blank = omit the header. */
        private String serverSideEncryption = "AES256";
        /** Lifetime of signed GET URLs handed to the web timeline. */
        private Duration signedUrlTtl = Duration.ofMinutes(15);
    }
}
