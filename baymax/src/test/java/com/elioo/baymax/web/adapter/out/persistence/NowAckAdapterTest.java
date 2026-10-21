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
            // stored as RAISE stores them: distinct and sorted ("text_…" sorts before "value_…")
            sql("UPDATE baymax.document SET urgency_reasons = '[\"text_emergency_phrase\", \"" + REASON + "\"]' WHERE id = '" + c + "'");

            Map<UUID, NowSeen> seen = seen(context, a, b, c);
            assertThat(seen.get(a).reasonsDigest()).as("the same reasons, the same digest").isEqualTo(seen.get(b).reasonsDigest());
            assertThat(seen.get(c).reasonsDigest()).as("one more reason, another digest").isNotEqualTo(seen.get(a).reasonsDigest());
            assertThat(seen.get(a).reasonsDigest()).as("sha256 hex of urgency_reasons::text as stored")
                    .isEqualTo(sha256(scalar("SELECT urgency_reasons::text FROM baymax.document WHERE id = '" + a + "'")))
                    .matches("[0-9a-f]{64}");

            // re-assessments through the RAISE that change nothing, as a real sweep on a real NOW paper makes them: the same
            // level re-deriving the reasons already stored. b with none (the empty case), a with its one reason again, c
            // with its two in the other order. Each restamps urgency_assessed_at; none may move the digest, which holds only
            // while RAISE merges the reasons distinct and sorted.
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            Instant later = Instant.now().plus(Duration.ofMinutes(5));
            Map<UUID, List<String>> rederived = Map.of(
                    a, List.of(REASON),
                    b, List.of(),
                    c, List.of(REASON, "text_emergency_phrase"));
            for (Map.Entry<UUID, List<String>> e : rederived.entrySet()) {
                UUID paper = e.getKey();
                String assessedBefore = scalar("SELECT urgency_assessed_at::text FROM baymax.document WHERE id = '" + paper + "'");
                port.raise(paper, new UrgencyAssessment(Urgency.NOW, e.getValue()), later).block();
                assertThat(scalar("SELECT urgency_assessed_at::text FROM baymax.document WHERE id = '" + paper + "'"))
                        .as("the guard: the restamp of " + e.getValue() + " really happened").isNotEqualTo(assessedBefore);
                assertThat(seen(context, paper).get(paper).reasonsDigest())
                        .as("the digest after re-deriving " + e.getValue() + ", which changed nothing")
                        .isEqualTo(seen.get(paper).reasonsDigest());
            }
        });
    }

    /**
     * The paper went between the banner read and the tap (deleted on request, or by its owner): the tap records nothing
     * and fails nothing. Without the guard the insert hit {@code now_ack_document_id_patient_id_fkey} and the ack was a 500.
     */
    @Test
    void aTapOnAPaperDeletedMeanwhileRecordsNothing() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            PatientProfile ma = patient(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, ma);
            NowSeen shown = seen(context, paper).get(paper);
            sql("DELETE FROM baymax.document WHERE id = '" + paper + "'");

            assertThat(context.getBean(NowAckPort.class).record(ma.familyId(), List.of(shown), Instant.now()).block())
                    .as("rows recorded for a paper that is gone").isZero();
            assertThat(scalar("SELECT count(*) FROM baymax.now_ack WHERE document_id = '" + paper + "'")).isEqualTo("0");
        });
    }

    /** The other direction: only "the paper is gone" is nothing recorded; a row the table refuses for any other reason still fails. */
    @Test
    void aTapTheTableRefusesForAnotherReasonStillFails() {
        PostgresNudgeAdapterTest.runnerFor(POSTGRES).run(context -> {
            PatientProfile ma = patient(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, ma);
            NowSeen shown = seen(context, paper).get(paper);
            NowSeen bad = new NowSeen(paper, ma.id(), shown.urgency(), shown.reasonsDigest(), "approved");
            assertThatThrownBy(() -> context.getBean(NowAckPort.class).record(ma.familyId(), List.of(bad), Instant.now()).block())
                    .as("a release state the CHECK refuses").hasMessageContaining("now_ack_release_state_check");
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
