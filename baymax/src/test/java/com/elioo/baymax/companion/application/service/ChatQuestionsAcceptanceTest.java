package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.application.port.in.AskUseCase;
import com.elioo.baymax.companion.application.port.in.AskUseCase.AskAnswer;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.config.ChatModelConfiguration;
import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-50's acceptance questions (the roadmap's D11 test questions, no longer a clinician gate) over the real Baymax module
 * on Testcontainers Postgres: real adapters, the real context builder over stored rows, the real metering, and a stub
 * model in place of Claude (no real model call). Each question's kind and handler, what the family is shown, and what the
 * cost log and the audit trail hold afterwards. A synthetic family: +8801700000xxx, the fictional medicine Demoprol.
 */
@Testcontainers
class ChatQuestionsAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** A stand-in for Claude that answers by the question it is asked, as the five cases need. */
    static final class StubModel implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Mono<LlmResponse> invoke(LlmRequest request) {
            calls.incrementAndGet();
            String q = request.userPrompt().substring(request.userPrompt().indexOf("QUESTION:"));
            String text;
            if (q.contains("ক্রিয়েটিনিন")) {
                text = "Ma-র ক্রিয়েটিনিন 1.8 mg/dL, কাগজে ছাপা সীমা 0.7–1.3-এর বাইরে।";
            } else if (q.contains("অর্ধেক")) {
                text = "Ma-র শেষ প্রেসক্রিপশনে লেখা: {{rx:1}}";
            } else if (q.contains("Napa")) {
                text = "Napa সাধারণত জ্বর আর ব্যথার জন্য দেওয়া হয়। Ma-র কাগজে এই ওষুধ লেখা নেই।";
            } else {
                text = "ধন্যবাদ। Ma-র কাগজগুলো কাগজপত্রে আছে।";
            }
            return Mono.just(new LlmResponse(text, "end_turn", new TokenUsage(900, 120), "claude-sonnet-5", null, "anthropic", 7L));
        }

        @Override
        public String providerName() {
            return "anthropic";
        }
    }

    private ReactiveWebApplicationContextRunner runner(StubModel stub) {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES)
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false",
                        "baymax.llm.prices.anthropic.[claude-sonnet-5].input=2.00",
                        "baymax.llm.prices.anthropic.[claude-sonnet-5].output=10.00")
                .withBean(ChatModelConfiguration.CHAT_CLIENT, BaymaxModelClient.class, () -> new BaymaxModelClient(stub));
    }

    @Test
    void theFiveQuestionsOnASyntheticFamily() {
        StubModel stub = new StubModel();
        runner(stub).run(context -> {
            Instant t = Instant.now();
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            FamilyAccount f = records.createFamily(new FamilyAccount(null, "+8801700000771", "Rahim", FamilyAccount.Plan.FAMILY, t, t)).block();
            PatientProfile ma = records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), t, t))
                    .block();
            UUID lab = paper(ma, "lab_report", "routine");
            sql("INSERT INTO baymax.observation (patient_id, document_id, name, canonical_name, value, unit, ref_low, ref_high, crop_key,"
                    + " observed_at) VALUES ('" + ma.id() + "','" + lab + "','Creatinine','creatinine','1.8','mg/dL','0.7','1.3','k',now())");
            UUID rx = paper(ma, "prescription", "routine");
            sql("INSERT INTO baymax.medication_event (patient_id, document_id, name, dose_text, timing_text, crop_key, at) VALUES ('"
                    + ma.id() + "','" + rx + "','Tab. Demoprol 5 mg','1+0+0','খাবারের পরে','k',now())");
            paper(ma, "lab_report", "now");
            long views = count("SELECT count(*) FROM baymax.audit_event WHERE kind = 'document_view'");
            long chatRows = count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat'");

            AskUseCase asks = context.getBean(AskUseCase.class);
            ChatCopyPort copy = context.getBean(ChatCopyPort.class);

            AskAnswer creatinine = ask(asks, f, ma, "আম্মার ক্রিয়েটিনিন কত?");
            assertThat(creatinine.kind()).isEqualTo(AnswerKind.ANSWER);
            assertThat(handler(creatinine)).isEqualTo("model");
            assertThat(creatinine.answer()).contains("1.8");

            AskAnswer dose = ask(asks, f, ma, "ওষুধটা অর্ধেক করব?");
            assertThat(dose.kind()).isEqualTo(AnswerKind.ANSWER);
            assertThat(dose.answer()).contains("Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে").contains(copy.line("bn", "chat.dose.ask_doctor"));

            AskAnswer napa = ask(asks, f, ma, "Napa কীসের?");
            assertThat(napa.kind()).isEqualTo(AnswerKind.ANSWER);

            int before = stub.calls.get();
            long logBefore = count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat'");
            AskAnswer chest = ask(asks, f, ma, "আম্মার বুকে ব্যথা");
            assertThat(chest.kind()).isEqualTo(AnswerKind.EMERGENCY);
            assertThat(handler(chest)).isEqualTo("red_flag");
            assertThat(stub.calls.get()).as("model calls for the emergency").isEqualTo(before);
            assertThat(count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat'")).isEqualTo(logBefore);
            assertThat(chest.call999()).isTrue();

            AskAnswer thanks = ask(asks, f, ma, "ok thanks");
            assertThat(thanks.kind()).isEqualTo(AnswerKind.ANSWER);
            assertThat(thanks.answer()).contains(copy.line("bn", "urgency.now"));

            assertThat(count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat'") - chatRows).as("chat cost rows").isEqualTo(4);
            assertThat(count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat' AND (document_id IS NOT NULL OR cost_usd IS NULL)"))
                    .as("chat cost rows naming a paper, or with no cost").isZero();
            assertThat(count("SELECT count(*) FROM baymax.chat_turn WHERE family_id = '" + f.id() + "'")).isEqualTo(5);
            assertThat(count("SELECT count(*) FROM baymax.chat_turn WHERE family_id = '" + f.id() + "' AND counted")).isEqualTo(4);
            // askingRecordsNoView: reading the papers into a context is not opening a paper
            assertThat(count("SELECT count(*) FROM baymax.audit_event WHERE kind = 'document_view'")).isEqualTo(views);
            // what was shown is what is stored, byte for byte
            for (AskAnswer a : List.of(creatinine, dose, napa, chest, thanks)) {
                assertThat(text("SELECT answer FROM baymax.chat_turn WHERE id = '" + a.turnId() + "'")).isEqualTo(a.answer());
            }
        });
    }

    private static AskAnswer ask(AskUseCase asks, FamilyAccount f, PatientProfile p, String question) {
        return asks.ask(f.id(), p.id(), question, "bn").block(Duration.ofSeconds(30));
    }

    private static String handler(AskAnswer a) throws Exception {
        return text("SELECT handler FROM baymax.chat_turn WHERE id = '" + a.turnId() + "'");
    }

    /** A finished paper uploaded now with a stored level. */
    private static UUID paper(PatientProfile p, String type, String urgency) throws Exception {
        UUID id = UUID.randomUUID();
        sql("INSERT INTO baymax.document (id, patient_id, family_id, document_type, doc_date, status, page_count, urgency, urgency_reasons,"
                + " urgency_assessed_at) VALUES ('" + id + "','" + p.id() + "','" + p.familyId() + "','" + type + "', current_date, 'DONE', 1,'"
                + urgency + "','[]', now())");
        return id;
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static long count(String query) throws Exception {
        return Long.parseLong(text(query));
    }

    private static String text(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
