package com.elioo.baymax.companion.adapter.out.persistence;

import com.elioo.baymax.companion.application.port.out.ChatTurnPort;
import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.companion.domain.ChatOutcome;
import com.elioo.baymax.companion.domain.ChatTurn;
import com.elioo.baymax.companion.domain.CitedMedicine;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan CHAT-api Task 1, on real Postgres: V29's constraints raise where a mock could not, the daily cap is reserved under
 * the account's row lock so concurrent presses at the boundary reserve exactly as many turns as the cap allows, and a turn
 * citing a deleted paper is no longer listed (S10). Fictional families only (+8801700000xxx).
 */
@Testcontainers
class PostgresChatTurnAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final AtomicInteger NUMBERS = new AtomicInteger(600);

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    private static Instant dayStart() {
        return LocalDate.now(DHAKA).atStartOfDay(DHAKA).toInstant();
    }

    private static FamilyAccount family(ApplicationContext context) {
        Instant t = Instant.now();
        return context.getBean(HealthRecordPort.class).createFamily(new FamilyAccount(null, "+8801700000" + NUMBERS.incrementAndGet(),
                "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
    }

    private static PatientProfile patient(ApplicationContext context, UUID family) {
        Instant t = Instant.now();
        return context.getBean(HealthRecordPort.class).createPatient(new PatientProfile(null, family, "Ma", 68, PatientProfile.Sex.FEMALE,
                List.of(), t, t)).block();
    }

    private static ChatOutcome answer(String text, List<UUID> documents) {
        return new ChatOutcome(text, AnswerKind.ANSWER, "model", List.of(), 1, 1000, 200, new BigDecimal("0.00400000"), 1234L, documents);
    }

    @Test
    void aReservedTurnIsCompletedWithWhatWasShown() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            UUID id = turns.reserveCounted(f.id(), p.id(), "bn", "আম্মার ক্রিয়েটিনিন কত?", dayStart(), 50).block();
            assertThat(id).isNotNull();
            assertThat(row(id, "answer IS NULL AND kind IS NULL AND answered_at IS NULL AND counted")).as("reserved, not answered").isEqualTo("t");

            turns.complete(id, new ChatOutcome("Ma-র ক্রিয়েটিনিন 1.8", AnswerKind.ANSWER, "repair", List.of("number_not_on_paper"), 2, 2100,
                    350, new BigDecimal("0.00770000"), 4321L, List.of())).block();

            assertThat(row(id, "answer")).isEqualTo("Ma-র ক্রিয়েটিনিন 1.8");
            assertThat(row(id, "kind")).isEqualTo("answer");
            assertThat(row(id, "handler")).isEqualTo("repair");
            assertThat(row(id, "violations")).isEqualTo("number_not_on_paper");
            assertThat(row(id, "model_calls")).isEqualTo("2");
            assertThat(row(id, "input_tokens")).isEqualTo("2100");
            assertThat(row(id, "output_tokens")).isEqualTo("350");
            assertThat(row(id, "cost_usd")).isEqualTo("0.00770000");
            assertThat(row(id, "latency_ms")).isEqualTo("4321");
            assertThat(row(id, "answered_at IS NOT NULL")).isEqualTo("t");
        });
    }

    @Test
    void theCapCountsOnlyCountedTurnsOfThisAccountToday() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            FamilyAccount f = family(context);
            FamilyAccount other = family(context);
            PatientProfile p = patient(context, f.id());
            PatientProfile q = patient(context, other.id());
            Instant today = dayStart();
            // yesterday's counted turn (Asia/Dhaka): a minute before today's start
            UUID yesterday = turns.reserveCounted(f.id(), p.id(), "bn", "গতকালের প্রশ্ন", today.minus(Duration.ofDays(1)), 2).block();
            sql("UPDATE baymax.chat_turn SET created_at = '" + today.minusSeconds(60) + "' WHERE id = '" + yesterday + "'");
            // today's emergency, never counted
            UUID emergency = turns.reserveUncounted(f.id(), p.id(), "bn", "আম্মার বুকে ব্যথা").block();
            turns.complete(emergency, new ChatOutcome("জরুরি", AnswerKind.EMERGENCY, "red_flag", List.of(), 0, 0, 0, null, 3L, List.of())).block();
            // another account's counted turn today
            assertThat(turns.reserveCounted(other.id(), q.id(), "bn", "অন্য পরিবার", today, 2).block()).isNotNull();

            assertThat(turns.reserveCounted(f.id(), p.id(), "bn", "এক", today, 2).block()).as("first of two today").isNotNull();
            assertThat(turns.reserveCounted(f.id(), p.id(), "bn", "দুই", today, 2).block()).as("second of two today").isNotNull();
            assertThat(turns.reserveCounted(f.id(), p.id(), "bn", "তিন", today, 2).blockOptional()).as("the third today is over the cap").isEmpty();
        });
    }

    @Test
    void twoAsksAtTheCapReserveExactlyOne() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            Instant today = dayStart();
            int presses = Integer.parseInt(System.getProperty("chat.cap.presses", "20"));
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(presses);
            try {
                List<Future<UUID>> results = new ArrayList<>();
                for (int i = 0; i < presses; i++) {
                    results.add(pool.submit(() -> {
                        go.await();
                        return turns.reserveCounted(f.id(), p.id(), "bn", "একই প্রশ্ন", today, 1).block(Duration.ofSeconds(30));
                    }));
                }
                go.countDown();
                int reserved = 0;
                for (Future<UUID> r : results) {
                    if (r.get(60, TimeUnit.SECONDS) != null) {
                        reserved++;
                    }
                }
                assertThat(reserved).as("ids returned to %d concurrent presses at a cap of one", presses).isEqualTo(1);
                assertThat(scalar("SELECT count(*) FROM baymax.chat_turn WHERE family_id = '" + f.id() + "' AND counted"))
                        .as("counted rows stored").isEqualTo("1");
            } finally {
                pool.shutdownNow();
            }
        });
    }

    @Test
    void anEmergencyRowCannotClaimAModelCall() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            UUID id = turns.reserveUncounted(f.id(), p.id(), "bn", "আম্মার বুকে ব্যথা").block();
            turns.complete(id, new ChatOutcome("জরুরি", AnswerKind.EMERGENCY, "red_flag", List.of(), 0, 0, 0, null, 2L, List.of())).block();

            assertThatThrownBy(() -> sql("UPDATE baymax.chat_turn SET model_calls = 1 WHERE id = '" + id + "'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23514"));
            assertThatThrownBy(() -> sql("UPDATE baymax.chat_turn SET counted = true WHERE id = '" + id + "'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23514"));
        });
    }

    @Test
    void aRowCannotBeAnsweredWithoutAKind() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            UUID id = turns.reserveCounted(f.id(), p.id(), "bn", "প্রশ্ন", dayStart(), 50).block();

            assertThatThrownBy(() -> sql("UPDATE baymax.chat_turn SET answer = 'x', answered_at = now() WHERE id = '" + id + "'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23514"));
            assertThatThrownBy(() -> sql("UPDATE baymax.chat_turn SET answer = 'x', kind = 'answer', answered_at = now(), model_calls = 0"
                    + " WHERE id = '" + id + "'"))
                    .as("an ANSWER with no model call")
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23514"));
        });
    }

    @Test
    void aTurnCitingADeletedPaperIsNotListed() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            UUID paper = documents.create(Document.received(p.id(), f.id(), 1, Instant.now())).block().id();
            UUID kept = turns.reserveCounted(f.id(), p.id(), "bn", "এক", dayStart(), 50).block();
            turns.complete(kept, answer("উত্তর এক", List.of())).block();
            UUID cites = turns.reserveCounted(f.id(), p.id(), "bn", "দুই", dayStart(), 50).block();
            turns.complete(cites, answer("উত্তর দুই", List.of(paper))).block();
            assertThat(ids(turns.answered(f.id(), p.id()).collectList().block())).as("before the delete").contains(kept, cites);

            documents.deleteDocument(paper).block();

            assertThat(ids(turns.answered(f.id(), p.id()).collectList().block())).as("after the paper's delete")
                    .contains(kept).doesNotContain(cites);
        });
    }

    /** Review of #166, finding 5 (V30): the quoted lines' crops are stored with the turn and listed with it, in order. */
    @Test
    void aQuotedLinesCropIsStoredAndListed() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            UUID rx = documents.create(Document.received(p.id(), f.id(), 1, Instant.now())).block().id();
            UUID id = turns.reserveCounted(f.id(), p.id(), "bn", "ওষুধটা অর্ধেক করব?", dayStart(), 50).block();
            List<CitedMedicine> quoted = List.of(new CitedMedicine(rx, "f/p/rx/crop-m2.jpg"), new CitedMedicine(rx, "f/p/rx/crop-m1.jpg"));
            turns.complete(id, new ChatOutcome("Tab. B · Tab. A", AnswerKind.ANSWER, "model", List.of(), 1, 1, 1, null, 9L, List.of(rx),
                    quoted)).block();

            List<ChatTurn> listed = turns.answered(f.id(), p.id()).collectList().block();
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).medicines()).containsExactlyElementsOf(quoted);
        });
    }

    /**
     * V30's constraints raise where a mock could not: a crop without its paper, and a quoted line on a paper the answer did
     * not cite (which S10's rule would then never hide when that paper is deleted).
     */
    @Test
    void aQuotedLineIsAlwaysOnACitedPaper() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            UUID rx = documents.create(Document.received(p.id(), f.id(), 1, Instant.now())).block().id();
            UUID id = turns.reserveCounted(f.id(), p.id(), "bn", "প্রশ্ন", dayStart(), 50).block();
            turns.complete(id, answer("উত্তর", List.of(rx))).block();

            assertThatThrownBy(() -> sql("UPDATE baymax.chat_turn SET medicine_document_ids = '{" + rx + "}' WHERE id = '" + id + "'"))
                    .as("a paper without its crop")
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23514"));
            assertThatThrownBy(() -> sql("UPDATE baymax.chat_turn SET medicine_document_ids = '{" + UUID.randomUUID() + "}',"
                    + " medicine_crop_keys = '{k}' WHERE id = '" + id + "'"))
                    .as("a quoted line on a paper the answer did not cite")
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("chat_turn_medicine_on_a_cited_paper"));
            sql("UPDATE baymax.chat_turn SET medicine_document_ids = '{" + rx + "}', medicine_crop_keys = '{k}' WHERE id = '" + id + "'");
        });
    }

    @Test
    void aTurnWithNoPaperIsStillListed() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            FamilyAccount f = family(context);
            PatientProfile p = patient(context, f.id());
            UUID id = turns.reserveCounted(f.id(), p.id(), "bn", "ok thanks", dayStart(), 50).block();
            turns.complete(id, answer("ধন্যবাদ", List.of())).block();
            UUID unanswered = turns.reserveCounted(f.id(), p.id(), "bn", "অপেক্ষায়", dayStart(), 50).block();

            List<ChatTurn> listed = turns.answered(f.id(), p.id()).collectList().block();
            assertThat(ids(listed)).containsExactly(id).doesNotContain(unanswered);
            assertThat(listed.get(0).answer()).isEqualTo("ধন্যবাদ");
            assertThat(listed.get(0).kind()).isEqualTo(AnswerKind.ANSWER);
        });
    }

    @Test
    void anotherAccountsTurnsAreNotListed() {
        runner().run(context -> {
            ChatTurnPort turns = context.getBean(ChatTurnPort.class);
            FamilyAccount owner = family(context);
            FamilyAccount member = family(context);
            PatientProfile p = patient(context, owner.id());
            UUID mine = turns.reserveCounted(owner.id(), p.id(), "bn", "আমার প্রশ্ন", dayStart(), 50).block();
            turns.complete(mine, answer("মালিকের উত্তর", List.of())).block();
            UUID theirs = turns.reserveCounted(member.id(), p.id(), "bn", "বোনের প্রশ্ন", dayStart(), 50).block();
            turns.complete(theirs, answer("বোনের উত্তর", List.of())).block();

            assertThat(ids(turns.answered(owner.id(), p.id()).collectList().block())).containsExactly(mine);
            assertThat(ids(turns.answered(member.id(), p.id()).collectList().block())).containsExactly(theirs);
        });
    }

    private static List<UUID> ids(List<ChatTurn> turns) {
        return turns.stream().map(ChatTurn::id).toList();
    }

    private static String row(UUID id, String expression) throws Exception {
        return scalar("SELECT " + expression + " FROM baymax.chat_turn WHERE id = '" + id + "'");
    }

    static void sql(String statement) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    static String scalar(String query) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
