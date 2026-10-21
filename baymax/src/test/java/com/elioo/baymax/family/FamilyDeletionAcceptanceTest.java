package com.elioo.baymax.family;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.aicall.adapter.in.router.AdminAuthFilter;
import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
import com.elioo.baymax.wa.domain.InboundMessage;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BMX-4 acceptance over the real wiring and HTTP: onboard a family and a patient, store images, then
 * DELETE the family (or the patient) and prove nothing is left in any baymax table or under the storage prefix, with
 * a receipt that matches what existed. Also runs the admin storage self-test against MinIO.
 *
 * <p>The sweep is derived from the schema twice — every foreign key that points at a family, a patient or a share
 * member, and every column named {@code family_id} / {@code patient_id} — so a table added in a later migration is
 * swept without anyone remembering it. A derived sweep can still pass vacuously one column at a time: until F9a the
 * fixture wrote nothing into outbound_message, nudge, web_session, otp_code or audit_event, so those "passed" without
 * being tested (the {@code document} defect again). The fixture therefore must hold a row in EVERY swept column before
 * the delete, and the test says which ones it left empty.
 */
@Testcontainers
class FamilyDeletionAcceptanceTest {

    static final String BUCKET = "baymax";
    static final String TOKEN = "admin-test-token";
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    /** The tables that hold family data with no foreign key at all: only the column-name lookup can find them. */
    static final List<String> FK_LESS = List.of("audit_event.family_id", "audit_event.patient_id",
            "stored_object.family_id", "stored_object.patient_id");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    private ListAppender<ILoggingEvent> logged;
    private Logger root;

    @BeforeAll
    static void createBucket() {
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL())).region(Region.US_EAST_1).forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @BeforeEach
    void captureLog() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        root.detachAppender(logged);
    }

    /** What the server logged at WARN or above: a failed delete's red names the constraint, not just "500". */
    private String serverLog() {
        return logged.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
                .filter(e -> e.getLoggerName().startsWith("com.elioo"))
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining(" | "));
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.admin.token=" + TOKEN,
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword(),
                        "baymax.storage.endpoint=" + MINIO.getS3URL(),
                        "baymax.storage.region=us-east-1",
                        "baymax.storage.bucket=" + BUCKET,
                        "baymax.storage.access-key=" + MINIO.getUserName(),
                        "baymax.storage.secret-key=" + MINIO.getPassword(),
                        "baymax.storage.path-style=true",
                        "baymax.storage.credentials=static",
                        "baymax.storage.server-side-encryption=");
    }

    @SuppressWarnings("unchecked")
    private static WebTestClient api(ApplicationContext context) {
        return WebTestClient.bindToRouterFunction(
                        ((RouterFunction<ServerResponse>) context.getBean("baymaxFamilyRoutes"))
                                .and((RouterFunction<ServerResponse>) context.getBean("baymaxAdminRoutes")))
                .build();
    }

    /** A family's whole graph, with a row in every table the schema says can hold its data. */
    private record Fixture(UUID family, UUID patient, UUID docA, UUID docB, List<UUID> shareMembers, UUID costRow) {
    }

    @SuppressWarnings("unchecked")
    private static Fixture seed(ApplicationContext context, WebTestClient api, String number) throws Exception {
        DocumentStorageUseCase storage = context.getBean(DocumentStorageUseCase.class);

        // onboarding
        String familyId = api.post().uri("/api/v1/baymax/families").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"whatsapp_number\":\"" + number + "\",\"owner_name\":\"Rahim\",\"terms_accepted\":true}")
                .exchange().expectStatus().isCreated()
                .returnResult(Map.class).getResponseBody().blockFirst().get("id").toString();
        String patientId = api.post().uri("/api/v1/baymax/families/" + familyId + "/patients").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Ma\",\"age\":74,\"sex\":\"female\",\"chronic_flags\":[\"diabetes\"],\"proxy_consent\":true}")
                .exchange().expectStatus().isCreated()
                .returnResult(Map.class).getResponseBody().blockFirst().get("id").toString();
        // free tier: second patient is 402
        api.post().uri("/api/v1/baymax/families/" + familyId + "/patients").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Baba\",\"age\":80,\"sex\":\"male\",\"proxy_consent\":true}")
                .exchange().expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.reason").isEqualTo("free_tier_patients");
        // sharing: once ok, twice 409
        api.post().uri("/api/v1/baymax/patients/" + patientId + "/share").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"whatsapp_number\":\"+8801811111111\"}")
                .exchange().expectStatus().isCreated();
        api.post().uri("/api/v1/baymax/patients/" + patientId + "/share").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"whatsapp_number\":\"+8801822222222\"}")
                .exchange().expectStatus().isEqualTo(409);

        UUID f = UUID.fromString(familyId);
        UUID p = UUID.fromString(patientId);
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        storage.storePage(f, p, docA, 1, JPEG).block();
        storage.storePage(f, p, docA, 2, JPEG).block();
        storage.storeCrop(f, p, docA, "hba1c", JPEG).block();
        storage.storePage(f, p, docB, 1, JPEG).block();
        // The document rows themselves — the fixture once never created these, so `document` was always empty and
        // the delete was never exercised against the table whose surviving rows actually block it. With the
        // child rows too, so the whole graph is present exactly as a real family's would be.
        for (UUID d : List.of(docA, docB)) {
            sql("insert into baymax.document (id, patient_id, family_id, document_type, doc_date, status, page_count) values ('"
                    + d + "','" + p + "','" + f + "','lab_report','2026-09-01','DONE',1)");
            sql("insert into baymax.observation (patient_id, document_id, name, canonical_name, value, unit, crop_key, observed_at) values ('"
                    + p + "','" + d + "','HbA1c','hba1c','9.8','%','k','2026-09-01T00:00:00Z')");
            sql("insert into baymax.follow_up (patient_id, document_id, instruction, due_date, status, crop_key) values ('"
                    + p + "','" + d + "','Follow up','2026-09-21','open','k')");
            sql("insert into baymax.medication_event (patient_id, document_id, name, crop_key, at) values ('"
                    + p + "','" + d + "','Metformin','k','2026-09-01T00:00:00Z')");
        }
        // a known id: the container is shared by both tests, so "a detached row with 10 tokens" is not this fixture's row
        UUID costRow = UUID.randomUUID();
        sql("insert into baymax.ai_call_log (id, document_id, purpose, provider, model, input_tokens) values ('" + costRow + "','"
                + docA + "','extract','groq','m',10)");
        // what a family accumulates outside the document graph: a composed message, a nudge, a session, a code, an audit
        sql("insert into baymax.outbound_message (family_id, patient_id, document_id, kind, urgency, body, gate_status) values ('"
                + f + "','" + p + "','" + docA + "','explanation','routine','লেখা','released')");
        sql("insert into baymax.nudge (family_id, patient_id, rule, trigger_key, urgency, status) values ('"
                + f + "','" + p + "','follow_up_due','follow_up:" + docA + "','routine','sent')");
        sql("insert into baymax.web_session (token_hash, family_id, expires_at) values ('" + "e".repeat(48) + f.toString().substring(0, 8)
                + "','" + f + "', now() + interval '1 day')");
        sql("insert into baymax.otp_code (phone_hash, code_hash, family_id, expires_at) values ('" + "c".repeat(64) + "','"
                + "d".repeat(64) + "','" + f + "', now() + interval '5 minutes')");
        sql("insert into baymax.audit_event (kind, family_id, patient_id, document_id) values ('document_view','"
                + f + "','" + p + "','" + docA + "')");
        // the number's WhatsApp window (V17), written through the port the intake uses, so the row is real
        context.getBean(com.elioo.baymax.web.application.port.out.InboundWindowPort.class)
                .recordInbound(f.toString().replace("-", "").substring(0, 32) + "d".repeat(32), f, Instant.now()).block();
        // F9a (V22), through the port the webhook uses: a family-level text (no patient), and a photo filed under p
        InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
        String textKey = hexKey(f, 1);
        String photoKey = hexKey(f, 2);
        inbound.claim(InboundMessage.received(textKey, f, "text", "বিস্তারিত", null, Instant.now()), null).block();
        inbound.claim(InboundMessage.received(photoKey, f, "image", null, "MEDIA-1", Instant.now()), null).block();
        inbound.linkDocument(photoKey, p, docA).block();
        // a photo claimed before the family existed and filed under p by a handler that found it: patient set, family not
        // (the claim and the handling each look the family up). Written by SQL: linkDocument now fills the family in.
        sql("insert into baymax.inbound_message (provider_key, patient_id, type) values ('" + hexKey(f, 3) + "','" + p + "','image')");

        List<UUID> shares = new ArrayList<>();
        for (String id : column("select id from baymax.share_member where patient_id = '" + p + "'")) {
            shares.add(UUID.fromString(id));
        }
        return new Fixture(f, p, docA, docB, shares, costRow);
    }

    /** An HMAC-shaped key, unique per family, so two fixtures in one database never collide. */
    private static String hexKey(UUID family, int n) {
        return (family.toString().replace("-", "") + String.format("%032x", n));
    }

    @Test
    void deleteFamilyLeavesNothingBehindAndTheReceiptMatches() {
        runner().run(context -> {
            WebTestClient api = api(context);
            StoragePort bucket = context.getBean(StoragePort.class);
            Fixture fx = seed(context, api, "+8801700000002");
            String familyId = fx.family().toString();
            UUID otherFamily = UUID.randomUUID();
            context.getBean(DocumentStorageUseCase.class).storePage(otherFamily, UUID.randomUUID(), UUID.randomUUID(), 1, JPEG).block();

            List<Target> targets = targets();
            assertThat(emptyBefore(targets, fx, t -> true))
                    .as("the fixture left these empty; the sweep would pass for them without testing anything").isEmpty();

            // admin storage self-test against the same bucket
            api.get().uri("/api/v1/baymax/admin/storage/selftest").header(AdminAuthFilter.HEADER, TOKEN)
                    .exchange().expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.ok").isEqualTo(true)
                    .jsonPath("$.steps.put").value(v -> assertThat(v.toString()).startsWith("ok"))
                    .jsonPath("$.steps.presign").isEqualTo("ok")
                    .jsonPath("$.steps.get").isEqualTo("ok")
                    .jsonPath("$.steps.delete").isEqualTo("ok");
            assertThat(bucket.listKeys("_selftest/").collectList().block()).isEmpty();

            // delete-on-request
            api.delete().uri("/api/v1/baymax/families/" + familyId).header(AdminAuthFilter.HEADER, TOKEN)
                    .exchange()
                    .expectStatus().value(status -> assertThat(status)
                            .as("delete failed; the server log says: %s", serverLog()).isEqualTo(200))
                    .expectBody()
                    .jsonPath("$.families").isEqualTo(1)
                    .jsonPath("$.patients").isEqualTo(1)
                    .jsonPath("$.documents").isEqualTo(2)
                    .jsonPath("$.objects").isEqualTo(4)
                    .jsonPath("$.deleted_at").isNotEmpty();

            assertThat(stillHolding(targets, fx, t -> true)).as("tables still holding rows for the deleted family").isEmpty();
            assertThat(scalar("select count(*) from baymax.ai_call_log where document_id = '" + fx.docA() + "'")).isEqualTo("0");
            assertThat(scalar("select (document_id is null)::text from baymax.ai_call_log where id = '" + fx.costRow() + "'"))
                    .as("the fixture's cost row survives, detached from the deleted document").isEqualTo("true");
            assertThat(bucket.listKeys(familyId + "/").collectList().block()).isEmpty();
            assertThat(bucket.listKeys(otherFamily + "/").collectList().block()).hasSize(1);

            api.delete().uri("/api/v1/baymax/families/" + familyId).header(AdminAuthFilter.HEADER, TOKEN)
                    .exchange().expectStatus().isNotFound();
        });
    }

    @Test
    void deletePatientLeavesNothingForThatPatientAndKeepsTheFamily() {
        runner().run(context -> {
            WebTestClient api = api(context);
            Fixture fx = seed(context, api, "+8801700000003");

            List<Target> targets = targets();
            java.util.function.Predicate<Target> patientScoped = t -> "patient_profile".equals(t.target());
            assertThat(targets.stream().filter(patientScoped).toList())
                    .as("the patient-scoped sweep derived from the schema is not empty").isNotEmpty();
            assertThat(emptyBefore(targets, fx, patientScoped))
                    .as("the fixture left these empty; the sweep would pass for them without testing anything").isEmpty();

            api.delete().uri("/api/v1/baymax/patients/" + fx.patient()).header(AdminAuthFilter.HEADER, TOKEN)
                    .exchange()
                    .expectStatus().value(status -> assertThat(status)
                            .as("delete failed; the server log says: %s", serverLog()).isEqualTo(200));

            assertThat(stillHolding(targets, fx, patientScoped)).as("tables still holding rows for the deleted patient").isEmpty();
            assertThat(scalar("select count(*) from baymax.family_account where id = '" + fx.family() + "'"))
                    .as("the family survives its patient").isEqualTo("1");
            assertThat(scalar("select (document_id is null)::text from baymax.ai_call_log where id = '" + fx.costRow() + "'"))
                    .as("the fixture's cost row survives, detached from the patient's deleted document").isEqualTo("true");
            assertThat(scalar("select count(*) from baymax.inbound_message where family_id = '" + fx.family()
                    + "' and patient_id is null and body is not null"))
                    .as("the family-level WhatsApp text belongs to the family, not the patient").isEqualTo("1");
        });
    }

    /** One swept column, and what it points at: family_account, patient_profile or share_member. */
    private record Target(String table, String column, String target) {
        String name() {
            return table + "." + column;
        }
    }

    /**
     * Every column that can hold a family's data, derived twice and united: (a) every foreign key in schema baymax that
     * points at family_account, patient_profile or share_member, whatever the column is called (a later viewer_id or a
     * sibling link); (b) every column named family_id or patient_id, which finds the tables that carry the ids with no
     * foreign key at all. Guarded against emptiness and against either derivation going blind.
     */
    private static List<Target> targets() throws Exception {
        Map<String, Target> byName = new LinkedHashMap<>();
        List<String> fkNames = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("""
                    SELECT cl.relname AS tbl, a.attname AS col, ref.relname AS target
                    FROM pg_constraint c
                    JOIN pg_class cl ON cl.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    JOIN pg_class ref ON ref.oid = c.confrelid
                    JOIN LATERAL unnest(c.conkey) AS k(attnum) ON true
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                    WHERE c.contype = 'f' AND n.nspname = 'baymax'
                      AND ref.relname IN ('family_account', 'patient_profile', 'share_member')
                    ORDER BY 1, 2""")) {
                while (rs.next()) {
                    Target t = new Target(rs.getString(1), rs.getString(2), rs.getString(3));
                    byName.put(t.name(), t);
                    fkNames.add(t.name());
                }
            }
            List<String> byColumnName = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(
                    "SELECT table_name, column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'baymax' AND column_name IN ('family_id','patient_id') "
                            + "ORDER BY table_name, column_name")) {
                while (rs.next()) {
                    Target t = new Target(rs.getString(1), rs.getString(2),
                            "family_id".equals(rs.getString(2)) ? "family_account" : "patient_profile");
                    byName.putIfAbsent(t.name(), t);
                    byColumnName.add(t.name());
                }
            }
            assertThat(fkNames).as("pg_constraint returned no foreign keys to a family or patient — the sweep would "
                    + "vacuously pass, which is the failure mode this helper exists to prevent")
                    .contains("document.family_id", "inbound_message.family_id");
            assertThat(byColumnName).as("the column-name lookup no longer sees the tables that hold family data "
                    + "without a foreign key").containsAll(FK_LESS);
        }
        return List.copyOf(byName.values());
    }

    /** The ids a column must be checked against for this fixture. */
    private static List<String> idsFor(Target t, Fixture fx) {
        return switch (t.target()) {
            case "family_account" -> List.of(fx.family().toString());
            case "patient_profile" -> List.of(fx.patient().toString());
            case "share_member" -> fx.shareMembers().stream().map(UUID::toString).toList();
            default -> throw new IllegalStateException("unexpected target " + t.target());
        };
    }

    private static long rows(Target t, List<String> ids) throws Exception {
        if (ids.isEmpty()) {
            return 0;
        }
        String in = ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        return Long.parseLong(scalar("SELECT count(*) FROM baymax." + t.table() + " WHERE " + t.column() + " IN (" + in + ")"));
    }

    /** The guard on the guard: the swept columns that hold nothing for this fixture before the delete. */
    private static List<String> emptyBefore(List<Target> targets, Fixture fx,
                                            java.util.function.Predicate<Target> scope) throws Exception {
        List<String> empty = new ArrayList<>();
        for (Target t : targets) {
            if (scope.test(t) && rows(t, idsFor(t, fx)) == 0) {
                empty.add(t.name());
            }
        }
        return empty;
    }

    /** {@code table.column=count} for every swept column that still holds this fixture's rows. */
    private static List<String> stillHolding(List<Target> targets, Fixture fx,
                                             java.util.function.Predicate<Target> scope) throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Target t : targets) {
            long n = scope.test(t) ? rows(t, idsFor(t, fx)) : 0;
            if (n > 0) {
                offenders.add(t.name() + "=" + n);
            }
        }
        return offenders;
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> column(String query) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
