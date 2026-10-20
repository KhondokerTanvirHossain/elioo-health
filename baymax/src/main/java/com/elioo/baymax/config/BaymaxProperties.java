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
    private Upload upload = new Upload();
    private Extract extract = new Extract();
    private Auth auth = new Auth();
    private Web web = new Web();
    private Wa wa = new Wa();
    private Outbound outbound = new Outbound();
    private Nudge nudge = new Nudge();
    private Recovery recovery = new Recovery();
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
        /**
         * Local development only: the web form mints a code for an account number without consulting the WhatsApp
         * window, and the code goes to the log at DEBUG (WhatsApp is off on a laptop). Startup refuses it with
         * WhatsApp on ({@code WaConfigValidator}): the web form must never become a sender to typed numbers.
         */
        private boolean webMintsCode = false;
        private Duration otpTtl = Duration.ofMinutes(10);
        /**
         * Codes made per number per rolling hour, on both paths (web and WhatsApp কোড), counted whatever became of
         * them: used, burned and expired codes all count. Beyond it nothing is minted or sent (RATE_LIMITED). The name
         * predates that rule and is kept so {@code BAYMAX_OTP_MAX_LIVE_PER_HOUR} keeps working; it once counted only
         * live codes, which let a burned code buy another send (review of plan PR 12).
         */
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

    /** The browser JSON namespace {@code /app/api/**} that the Next.js frontend calls (rebuild plan, B1). */
    @Data
    public static class Web {
        /**
         * The one origin a mutating {@code /app/api} request may come from whenever it sends an {@code Origin} header
         * (scheme + host [+ port], no trailing slash). Blank refuses every request that sends one, which is fail-closed
         * and is reported with a WARN at startup: a present-but-empty {@code BAYMAX_WEB_PUBLIC_ORIGIN=} line resolves to
         * "" rather than the default, and every browser mutation then fails. A laptop sets
         * {@code BAYMAX_WEB_PUBLIC_ORIGIN=http://localhost:8086}. There is no CORS configuration, ever.
         */
        private String publicOrigin = "https://medioo.eliooo.org";
        /**
         * Families that see the Next.js twins of routes not yet flipped ({@code me.preview}), from
         * {@code BAYMAX_WEB_PREVIEW_FAMILIES} (comma-separated family ids). Decided here only, never by a cookie.
         *
         * <p>Bound as text, never as {@code List<UUID>}: one bad entry in a {@code List<UUID>} refuses startup, and
         * {@code deploy.sh} has already removed the running container by then, so a typo would take MedScribe and
         * Medioo down together. Read it through {@link #previewFamilies()}.
         */
        private List<String> previewFamilyIds = new ArrayList<>();

        /**
         * The allowlisted family ids. Each entry is stripped of whitespace and of the quotes {@code docker --env-file}
         * keeps; one that is still not a UUID is skipped (that family is not previewed: fail closed), counted by
         * {@link #rejectedPreviewEntries()} for the startup WARN, and never refuses boot.
         */
        public java.util.Set<java.util.UUID> previewFamilies() {
            java.util.Set<java.util.UUID> ids = new java.util.LinkedHashSet<>();
            for (String entry : previewFamilyIds) {
                parsePreviewEntry(entry).ifPresent(ids::add);
            }
            return ids;
        }

        /** Non-blank entries of {@link #previewFamilyIds} that are not a family id once quotes and spaces are gone. */
        public int rejectedPreviewEntries() {
            int rejected = 0;
            for (String entry : previewFamilyIds) {
                if (!bare(entry).isEmpty() && parsePreviewEntry(entry).isEmpty()) {
                    rejected++;
                }
            }
            return rejected;
        }

        private static java.util.Optional<java.util.UUID> parsePreviewEntry(String entry) {
            String id = bare(entry);
            if (id.length() != 36) {
                return java.util.Optional.empty();   // UUID.fromString accepts short groups ("1-2-3-4-5"); a family id is never short
            }
            try {
                return java.util.Optional.of(java.util.UUID.fromString(id));
            } catch (IllegalArgumentException notAnId) {
                return java.util.Optional.empty();
            }
        }

        private static String bare(String entry) {
            return entry == null ? "" : entry.strip().replaceAll("^[\"']+|[\"']+$", "").strip();
        }
    }

    /**
     * The WhatsApp channel (BMX-10 phase 1). Entirely off unless {@link #enabled} is true: with it unset every
     * existing behaviour is unchanged, which is the RUNBOOK contract. Login codes go out here (DR-39), always as a
     * free-form message inside the window the family's own message opened, never as a template.
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
        /**
         * The display number of {@link #phoneNumberId}, digits only (8801…), for the sign-in page's wa.me link
         * (D37: on /app only, never on the landing page). Blank = the page instructs but shows no link.
         */
        private String publicNumber = "";
        /**
         * How recent the family's own last message must be for a web sign-in code to be sent to WhatsApp
         * automatically. WhatsApp's free customer-service window is 24 hours; the default keeps an hour's margin so a
         * send never races the window closing (PO 2026-10-18). Startup refuses anything above 24 hours.
         */
        private Duration serviceWindow = Duration.ofHours(23);

        /**
         * The one allowlist rule, for every outbound path (intake replies, explanations, login codes). An empty
         * list is "no phase-1 restriction". Only E.164 matches: callers normalise first, so a number without its
         * plus is refused rather than guessed at.
         */
        public boolean permits(String e164) {
            return e164 != null && (allowlist.isEmpty() || allowlist.stream().anyMatch(a -> a.trim().equals(e164)));
        }
    }

    /** Explanation, urgency, safety and the review gate (BMX-6, DR-13). */
    @Data
    public static class Outbound {
        /** Base URL for links in messages. */
        private String publicBaseUrl = "https://baymax.eliooo.org";
        /** off | all | urgency — which messages wait for a reviewer. */
        private String gateMode = "off";
        /** The PENDING copy's [N] (spec §4.10): how many hours a review typically takes. Measure in week 0. */
        private int reviewTypicalHours = 2;
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
        /**
         * Per-marker urgency thresholds, per unit, as a clinician states them — the replacement for the
         * 2×/0.5× stopgap above, which batch 2 showed to be wrong in both directions.
         *
         * <p><b>Every entry ships as {@code status: proposed} and is inert.</b> Only {@code active} can
         * change a family's message, so a doctor's sign-off is a config change rather than a code change.
         * {@code ProposedThresholdsAreInertTest} asserts that a proposed entry which WOULD change a verdict
         * does not, and that an active one does.</p>
         */
        private List<com.elioo.baymax.outbound.domain.MarkerThreshold> markerThresholds = new ArrayList<>();
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

    /** F9b: the startup sweep that finishes documents a restart interrupted. */
    @Data
    public static class Recovery {
        /** Set false only to stop a crash loop (BAYMAX_RECOVERY_ENABLED). */
        private boolean enabled = true;
        /** Re-runs per document before it is failed {@code interrupted_repeatedly}. */
        private int maxAttempts = 2;
        /** Documents created longer ago than this are reported {@code skipped_too_old} and never touched. */
        private Duration maxAge = Duration.ofDays(7);
        /** A finished document never explained is explained only if updated within this window; older ones are counted. */
        private Duration explainMaxAge = Duration.ofHours(6);
    }

    @Data
    public static class Free {
        /** Patient profiles a free family may have. */
        private int maxPatients = 1;
        /** Documents a free family may upload per calendar month (UTC). */
        private int maxDocsPerMonth = 3;
    }

    /** Web upload (Phase 4, area 4): the add-report sheet's caps and the browser's downscale (D5, spec 4.7). */
    @Data
    public static class Upload {
        /** Per file; matches the JSON API's DocumentHandler.MAX_UPLOAD_BYTES (25 MB). */
        private long maxFileBytes = 25L * 1024 * 1024;
        /** One press of "send", every page together: bounds what a request may hold in memory. */
        private long maxRequestBytes = 60L * 1024 * 1024;
        /** Long edge the browser downscales to before upload; 0 = off until measured (D5, spec 4.7). */
        private int downscaleMaxEdge = 0;
        private double downscaleQuality = 0.92;
        /** A JPEG within the edge and under this many bytes is sent byte for byte, never re-compressed. */
        private long passThroughBytes = 1_500_000;
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
        /**
         * Below this, a {@link #gatingSections gating section} for the document type that was READ (not empty)
         * forces a retake. Other sections, and an empty gating section, never do.
         */
        private double minConfidenceSection = 0.70;
        /**
         * Which sections must CARRY something, by {@code document_type}: one read as empty is a failed read,
         * whatever confidence the model claims.
         *
         * <p>An empty section means one of two things — nothing on the page, or we could not read it — and the
         * confidence number alone cannot tell them apart. A prescription has no lab values, so a zero there is
         * "nothing to find"; a lab report with no values read is a failed read whatever confidence it claims.
         * Before this table, a prescription with no values was rejected as an unclear photo (0.85 overall, every
         * medicine correct) and the family was coached on photography for a document we had read perfectly.
         *
         * <p>Kept apart from {@link #gatingSections} on purpose (RX, the DR-27 correction). DR-27 replaced this
         * table with the gating list, and the gating list carries {@code follow_up} for a prescription — so a
         * clearly read prescription with no follow-up instruction, which most are (batch 1: 1 of 10 had one),
         * was sent back for a retake. Whether a section must be present and whether its confidence matters are
         * two different questions.
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
         * Which sections the confidence gate CHECKS, by {@code document_type} (DR-27).
         *
         * <p>Only the sections that matter for the type gate a retake: a lab report on its values, a
         * prescription on its medicines and follow-up. Every other section is still extracted and shown —
         * its confidence simply never triggers a retake. lab1 was retaken because {@code clinical_context}
         * scored 0.60 on a lab report, which barely has clinical context, while its 21 values read at 0.92.
         *
         * <p>Confidence only: a gating section that came back EMPTY is skipped (confidence of nothing is not a
         * weak read), and whether it may be empty at all is {@link #expectedSections}'s question. A follow-up
         * that WAS read at 0.5 still retakes a prescription; a prescription with no follow-up does not.
         *
         * <p>A type absent from this map gates nothing on section confidence; the overall threshold still applies.
         */
        private Map<String, List<String>> gatingSections = new java.util.LinkedHashMap<>(Map.of(
                "lab_report", List.of("values"),
                "prescription", List.of("medicines", "follow_up"),
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
        /**
         * Output cap for the model that re-reads a crop when OCR read nothing. A crop is one row, so the
         * answer is a few words; this is small on purpose, because the call happens per unlocatable value
         * and an unbounded one would be the most expensive thing in the pipeline.
         */
        private int cropVerifyMaxTokens = 100;
        /**
         * Whether a value whose crop cannot be located in the OCR text may be recovered from the region the
         * model pointed at on the page image (always subject to independent verification).
         *
         * <p>Exists so a before/after measurement can run on ONE build with ONE gate. The first attempt to
         * measure the locator fix compared two runs whose gates differed, which credited a document with
         * recovering three values when it had simply failed the confidence gate and never been cropped.</p>
         */
        private boolean recoverCropsFromImage = true;
    }

    /** A chronic marker the pipeline recognises by any of its aliases. Config, not code. */
    @Data
    public static class Marker {
        private String canonical;
        private List<String> aliases = new ArrayList<>();
        /**
         * Names that must NEVER map to this marker, even though the substring fallback would catch them.
         *
         * <p>"Non-HDL Cholesterol" contains "hdl" and canonicalised to it, which matters because HDL's
         * threshold is direction-inverted: a high non-HDL is bad and a high HDL is protective, so the wrong
         * match reads a bad result as a good one. "Mean Platelet Volume" contains "platelet" and would have
         * been judged against a platelet-count threshold. A marker is excluded when its name contains any of
         * these, and exclusion beats every alias.</p>
         */
        private List<String> notAliases = new ArrayList<>();
    }
}
