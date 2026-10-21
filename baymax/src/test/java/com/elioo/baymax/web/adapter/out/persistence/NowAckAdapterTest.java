package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.testsupport.NowPapers;
import com.elioo.baymax.web.application.port.out.NowAckPort;
import com.elioo.baymax.web.domain.NowSeen;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan B3 Step 1, on real Postgres: what the দেখেছি store reads and records. The digest is of the reasons as stored, so
 * a re-assessment that changes nothing keeps it while {@code urgency_assessed_at} moves; the release state is the latest
 * explanation's, {@code approved} collapsed to {@code released}; the port enriches the ids it is given and chooses none
 * (choosing is {@code AttentionUseCase}'s); a row must name its document's own patient; recording is idempotent.
 */
@Testcontainers
class NowAckAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final AtomicInteger FAMILIES = new AtomicInteger();
    private static final String REASON = "value_critical:kalium-b3|stopgap";

    private static PatientProfile patient(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        Instant now = Instant.now();
        FamilyAccount f = records.createFamily(new FamilyAccount(null, "+88017000006" + String.format("%02d", FAMILIES.incrementAndGet()),
                "Rahim", FamilyAccount.Plan.FAMILY, now, now)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), now, now)).block();
    }

    private static Map<UUID, NowSeen> seen(ApplicationContext context, UUID... ids) {
        return context.getBean(NowAckPort.class).seenOf(List.of(ids)).collectList().block().stream()
                .collect(Collectors.toMap(NowSeen::documentId, Function.identity()));
    }

    @Test
    void theDigestIsTheStoredReasons() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            PatientProfile ma = patient(context);
            UUID a = NowPapers.nowPaper(context, POSTGRES, ma);
            UUID b = NowPapers.nowPaper(context, POSTGRES, ma);
            UUID c = NowPapers.nowPaper(context, POSTGRES, ma);
            sql("UPDATE baymax.document SET urgency_reasons = '[\"" + REASON + "\"]' WHERE id IN ('" + a + "', '" + b + "')");
            sql("UPDATE baymax.document SET urgency_reasons = '[\"" + REASON + "\", \"text_emergency_phrase\"]' WHERE id = '" + c + "'");

            Map<UUID, NowSeen> seen = seen(context, a, b, c);
            assertThat(seen.get(a).reasonsDigest()).as("the same reasons, the same digest").isEqualTo(seen.get(b).reasonsDigest());
            assertThat(seen.get(c).reasonsDigest()).as("one more reason, another digest").isNotEqualTo(seen.get(a).reasonsDigest());
            assertThat(seen.get(a).reasonsDigest()).as("sha256 hex of urgency_reasons::text as stored")
                    .isEqualTo(sha256(scalar("SELECT urgency_reasons::text FROM baymax.document WHERE id = '" + a + "'")))
                    .matches("[0-9a-f]{64}");

            // a re-assessment through the RAISE: same level, no new reason (what detail, compose, re-crop and the sweep do)
            String assessedBefore = scalar("SELECT urgency_assessed_at::text FROM baymax.document WHERE id = '" + a + "'");
            context.getBean(DocumentUrgencyPort.class)
                    .raise(a, new UrgencyAssessment(Urgency.NOW, List.of()), Instant.now().plus(Duration.ofMinutes(5))).block();
            String assessedAfter = scalar("SELECT urgency_assessed_at::text FROM baymax.document WHERE id = '" + a + "'");
            assertThat(assessedAfter).as("the restamp really happened").isNotEqualTo(assessedBefore);
            assertThat(seen(context, a).get(a).reasonsDigest()).as("the digest after a re-assessment that changed nothing")
                    .isEqualTo(seen.get(a).reasonsDigest());
        });
    }

    @Test
    void theReleaseStateIsTheLatestExplanationCollapsed() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            PatientProfile ma = patient(context);
            UUID explained = NowPapers.nowPaper(context, POSTGRES, ma);
            UUID none = NowPapers.nowPaper(context, POSTGRES, ma);
            explanation(ma, explained, "pending", "2026-10-21T04:00:00Z");
            explanation(ma, explained, "approved", "2026-10-21T05:00:00Z");

            Map<UUID, NowSeen> seen = seen(context, explained, none);
            assertThat(seen.get(explained).releaseState()).as("approved, the later explanation, collapses to released").isEqualTo("released");
            assertThat(seen.get(none).releaseState()).as("no explanation").isEqualTo("none");
            assertThat(seen.get(explained).patientId()).isEqualTo(ma.id());
            assertThat(seen.get(explained).urgency()).isEqualTo("now");
        });
    }

    @Test
    void seenOfFiltersNothingItWasNotGiven() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            PatientProfile ma = patient(context);
            UUID week = NowPapers.paper(context, POSTGRES, ma, "this_week");
            assertThat(seen(context, week)).as("a THIS_WEEK paper's id is enriched, not dropped: choosing is AttentionUseCase's")
                    .containsOnlyKeys(week);
            assertThat(seen(context, week).get(week).urgency()).isEqualTo("this_week");
        });
    }

    @Test
    void aRowMustNameThePatientOfItsDocument() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            PatientProfile ma = patient(context);
            PatientProfile other = patient(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, ma);
            assertThatThrownBy(() -> sql("INSERT INTO baymax.now_ack (family_id, patient_id, document_id, urgency, reasons_digest, release_state) "
                    + "VALUES ('" + ma.familyId() + "', '" + other.id() + "', '" + paper + "', 'now', '" + "a".repeat(64) + "', 'none')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("violates foreign key constraint \"now_ack_document_id_patient_id_fkey\"");
        });
    }

    @Test
    void recordingTwiceIsOneRow() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            PatientProfile ma = patient(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, ma);
            NowAckPort acks = context.getBean(NowAckPort.class);
            NowSeen shown = seen(context, paper).get(paper);

            assertThat(acks.record(ma.familyId(), List.of(shown), Instant.now()).block()).as("the first tap").isEqualTo(1L);
            assertThat(acks.record(ma.familyId(), List.of(shown), Instant.now()).block()).as("the same tap again").isEqualTo(0L);
            assertThat(scalar("SELECT count(*) FROM baymax.now_ack WHERE document_id = '" + paper + "'")).isEqualTo("1");
            assertThat(acks.ackedBy(ma.familyId(), List.of(paper)).collectList().block()).containsExactly(shown);
            assertThat(acks.ackedBy(UUID.randomUUID(), List.of(paper)).collectList().block()).as("another account holder").isEmpty();
        });
    }

    private static void explanation(PatientProfile p, UUID document, String gate, String createdAt) throws Exception {
        sql("INSERT INTO baymax.outbound_message (family_id, patient_id, document_id, kind, urgency, urgency_reasons, body, gate_status, created_at)"
                + " VALUES ('" + p.familyId() + "','" + p.id() + "','" + document + "','explanation','now','[]','x','" + gate + "','" + createdAt + "')");
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
