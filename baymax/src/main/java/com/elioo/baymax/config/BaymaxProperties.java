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
    private Auth auth = new Auth();
    private Wa wa = new Wa();
    private Outbound outbound = new Outbound();
    private Nudge nudge = new Nudge();
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

    /** OTP login and web sessions (BMX-5). */
    @Data
    public static class Auth {
        /**
         * Secret for the HMAC that stands in for a phone number in otp_code, web_session and audit_event.
         * Blank = OTP login answers 503, the same fail-closed shape as the admin token. Rotating it logs
         * every family out (sessions are keyed by it too) and orphans in-flight codes; nothing else.
         */
        private String hmacSecret = "";
        /** How a code reaches the family: {@code log} writes it to the server log (v1); WhatsApp is BMX-10. */
        private String otpDelivery = "log";
        private Duration otpTtl = Duration.ofMinutes(10);
        /** Live (unexpired, unused, unburned) codes allowed per number per hour; beyond it, requests are silently ignored. */
        private int otpMaxLivePerHour = 5;
        /** Wrong guesses that burn a code. */
        private int otpMaxAttempts = 5;
        /** Sliding session lifetime, measured from last use. */
        private Duration sessionTtl = Duration.ofDays(30);
        /** Sliding expiry is pushed forward at most this often, so a busy session is not a write per request. */
        private Duration sessionTouchInterval = Duration.ofHours(1);
        private String cookieName = "baymax_session";
        /** Secure attribute on the cookie. Browsers exempt http://localhost, so this stays true in dev too. */
        private boolean cookieSecure = true;
    }

    /**
     * The WhatsApp channel (BMX-10 phase 1). Entirely off unless {@link #enabled} is true: with it unset every
     * existing behaviour is unchanged, which is the RUNBOOK contract. OTP is deliberately NOT routed here —
     * {@code auth.otpDelivery} stays {@code log}, because a blank or expired token must never become a login
     * outage.
     */
    @Data
    public static class Wa {
        /** Master switch. False (the default) means no webhook route, no outbound, nothing to misconfigure. */
        private boolean enabled = false;
        /** Cloud API phone number id (non-secret; it identifies the sending number, it does not authorise). */
        private String phoneNumberId = "";
        /** WhatsApp Business Account id (non-secret); needed for the subscribed_apps call. */
        private String wabaId = "";
        /** System-user access token. Blank with the channel enabled = fail closed at startup, never silently. */
        private String token = "";
        /** App secret, for validating X-Hub-Signature-256 on every inbound POST. */
        private String appSecret = "";
        /** The string Meta echoes back during the GET verification handshake; ours to invent, must match there. */
        private String verifyToken = "";
        /**
         * Phase-1 guard: outbound refuses any number not on this list, and says so in the log. Enforced in code
         * rather than relying on the Meta test number's own restriction, which disappears the moment we move to
         * a production number.
         */
        private List<String> allowlist = new java.util.ArrayList<>();
        /** Pinned in config, not the environment: this is a code-compatibility concern, not a deployment secret. */
        private String graphVersion = "v21.0";
    }

    /** Explanation, urgency, safety and the review gate (BMX-6, DR-13). */
    @Data
    public static class Outbound {
        /** Base URL for links in messages. */
        private String publicBaseUrl = "https://baymax.eliooo.org";
        /** off | all | urgency — which messages wait for a reviewer. */
        private String gateMode = "off";
        /** Urgencies gated when gateMode=urgency. */
        private List<String> gateUrgencyLevels = new ArrayList<>(List.of("now", "this_week"));
        /** Hard cap on any message body, characters. */
        private int maxChars = 600;
        /**
         * STOPGAP (PO ruling 2026-09-18): a value is critical → NOW when it is at least this multiple of the
         * printed ref_high, or at most {@code criticalLowMultiple} of the printed ref_low, or the page's own
         * text marks it with one of {@code criticalMarkers} near the value. The correct source is per-marker
         * panic thresholds from a clinician, not a multiple; that is on the pending list, blocking week 0.
         */
        private double criticalHighMultiple = 2.0;
        private double criticalLowMultiple = 0.5;
        /** Words the page itself uses to mark a value critical, both scripts, matched near the value. */
        private List<String> criticalMarkers = new ArrayList<>(List.of("critical", "panic", "alarm", "very high", "very low",
                "ক্রিটিক্যাল", "প্যানিক", "খুব বেশি", "খুব কম", "অত্যধিক", "অতি বেশি", "অতি কম"));
        /** Verbatim tokens in the document text that mean NOW, both scripts. */
        private List<String> emergencyPhrases = new ArrayList<>(List.of("emergency", "urgent", "urgently", "admit", "admission",
                "immediately", "জরুরি", "জরুরী", "ভর্তি", "অবিলম্বে"));
        /** Tokens/phrases a body may never contain: advice to start/stop/change a medicine or dose. */
        private List<String> forbiddenPhrases = new ArrayList<>(List.of("ওষুধ খান", "ওষুধ খাবেন", "ওষুধ বন্ধ", "বন্ধ করুন", "শুরু করুন",
                "ডোজ", "বাড়ান", "কমান", "দ্বিগুণ", "take the", "stop taking", "start taking", "increase the", "reduce the",
                "double the", "dose", "dosage", "mg", "tablet", "tab.", "cap."));
        /** At least one must appear in every NOW / THIS_WEEK body: the doctor-first framing. */
        private List<String> doctorFirstMarkers = new ArrayList<>(List.of("ডাক্তার", "হাসপাতাল"));
        /** DR-18: during the pilot every nudge waits for the reviewer, whatever its urgency and whatever gateMode says. */
        private boolean gateNudges = true;
    }

    /** The proactive engine (BMX-8, DR-17, DR-18). All rules read stored data only; the policy is enforced centrally. */
    @Data
    public static class Nudge {
        /** Run the hourly evaluation. Off in tests and on boxes that should never message anyone. */
        private boolean enabled = true;
        /** Spring cron for the evaluation job; hourly, five past. */
        private String cron = "0 5 * * * *";
        /** Send window in {@code zone}: hours [windowStartHour, windowEndHour). Outside it a nudge is held, never dropped. */
        private String zone = "Asia/Dhaka";
        private int windowStartHour = 9;
        private int windowEndHour = 20;
        /** Caps per patient: rolling 7 days and per (zone) day. Dropped, not queued. */
        private int weeklyCap = 2;
        private int dailyCap = 1;
        /** follow_up_due fires this many days before due_date; course_ending this many days before the course ends. */
        private int followUpLeadDays = 2;
        private int courseLeadDays = 1;
        /** silence: no document for this many days, patient has chronic flags; then suppressed for suppressDays. */
        private int silenceDays = 60;
        private int silenceSuppressDays = 30;
        /** trend: this many consecutive readings of one canonical marker moving the wrong way (DR-17). */
        private int trendReadings = 3;
        /** PO ruling 2026-09-19: same-day readings are one report; the readings must span at least this many days. */
        private int trendMinSpanDays = 14;
        /** DR-17: markers where rising is wrong; {@code trendEitherWay} where either direction is. */
        private List<String> trendRising = new ArrayList<>(List.of("hba1c", "fasting_glucose", "creatinine", "bp_systolic", "bp_diastolic", "ldl"));
        private List<String> trendEitherWay = new ArrayList<>(List.of("tsh"));
    }

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
         * The model that reads every document, vision-capable. Blank runs text-only. DR-10: sonnet,
         * single-tier, for printed documents — haiku measured 26 points lower and its confidence could not
         * route escalation. Haiku stays selectable here; Groq (DR-9) is out, its free tier cannot run one.
         */
        private String visionModel = "claude-sonnet-5";
        /** Model used when escalating (DR-9: sonnet, on its own Anthropic client). */
        private String strongModel = "claude-sonnet-5";
        private int maxOutputTokens = 8192;
        /** Below this overall confidence the document needs a retake; nothing but the document row is written. */
        private double minConfidenceOverall = 0.80;
        /** Below this, any single section (values, medicines, follow-up) forces a retake. */
        private double minConfidenceSection = 0.70;
        /**
         * Which sections each document type is expected to carry, by {@code document_type}.
         *
         * <p>An empty section means one of two things — nothing on the page, or we could not read it — and the
         * confidence number alone cannot tell them apart. A prescription has no lab values, so a zero there is
         * "nothing to find"; a lab report with no values read is a failed read whatever confidence it claims.
         * Before this, a prescription with no values was rejected as an unclear photo (0.85 overall, every
         * medicine correct) and the family was coached on photography for a document we had read perfectly.
         *
         * <p>A type absent from this map expects nothing, so only its confidence numbers gate it.
         */
        private Map<String, List<String>> expectedSections = new java.util.LinkedHashMap<>(Map.of(
                "lab_report", List.of("values"),
                "prescription", List.of("medicines"),
                "discharge_summary", List.of(),
                "imaging_report", List.of(),
                "other", List.of()));
        /**
         * Below this overall confidence, or on any critical flag, the strong model is asked as well.
         * 0.0 (DR-10) turns the confidence path off; the critical-flag path stays.
         */
        private double strongModelThreshold = 0.0;
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
