package com.elioo.baymax.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private Free free = new Free();
    private Extract extract = new Extract();
    private List<Marker> markers = new ArrayList<>();

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
        /** Path-style addressing: false for AWS S3 (virtual-hosted), true for MinIO and Supabase. */
        private boolean pathStyle = false;
        /**
         * Where the S3 credentials come from. {@code static}: access-key/secret-key (required);
         * {@code instance-role}: the EC2 instance profile only, ignoring any AWS_* variables in the
         * environment (the container also carries the Comprehend user's keys, which the default chain
         * would prefer); {@code default-chain}: the AWS SDK default chain.
         */
        private Credentials credentials = Credentials.DEFAULT_CHAIN;
        /** Value for x-amz-server-side-encryption on every PUT ("AES256"); blank = omit the header. */
        private String serverSideEncryption = "AES256";
        /** Lifetime of signed GET URLs handed to the web timeline. */
        private Duration signedUrlTtl = Duration.ofMinutes(15);
    }

    public enum Credentials { STATIC, INSTANCE_ROLE, DEFAULT_CHAIN }

    @Data
    public static class Free {
        /** Patient profiles a free family may have. */
        private int maxPatients = 1;
        /** Documents a free family may upload per calendar month (UTC). */
        private int maxDocsPerMonth = 3;
    }

    @Data
    public static class Extract {
        /** Send page images to the model alongside the OCR text, when the client supports images. */
        private boolean sendImages = true;
        /**
         * The cheap tier: a vision-capable model that reads every document. Blank runs text-only.
         * DR-9 makes this Anthropic Haiku 4.5 rather than Groq, whose free tier caps output at
         * 1,000 tokens/minute against the ~1,810 one document declares.
         */
        private String visionModel = "claude-haiku-4-5";
        /** Model used when escalating (DR-9: sonnet, on its own Anthropic client). */
        private String strongModel = "claude-sonnet-5";
        private int maxOutputTokens = 8192;
        /** Below this overall confidence the document needs a retake; nothing but the document row is written. */
        private double minConfidenceOverall = 0.80;
        /** Below this, any single section (values, medicines, follow-up) forces a retake. */
        private double minConfidenceSection = 0.70;
        /** Below this overall confidence, or on any critical flag, the strong model is asked as well. */
        private double strongModelThreshold = 0.85;
        /** Refuse documents with more pages than this. */
        private int maxPages = 10;
        /** Padding around a source-span bounding box when cutting the crop, in pixels. */
        private int cropPaddingPx = 12;
    }

    /** A chronic marker the pipeline recognises by any of its aliases. Config, not code. */
    @Data
    public static class Marker {
        private String canonical;
        private List<String> aliases = new ArrayList<>();
    }
}
