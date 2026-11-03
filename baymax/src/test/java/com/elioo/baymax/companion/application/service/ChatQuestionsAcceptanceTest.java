package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.companion.application.port.in.AskUseCase;
import com.elioo.baymax.companion.application.port.in.AskUseCase.AskAnswer;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.config.ChatCheckModelConfiguration;
import com.elioo.baymax.companion.config.ChatModelConfiguration;
import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.companion.domain.CitedMedicine;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.testsupport.PassingChatCheck;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
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
import java.util.concurrent.atomic.AtomicReference;

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
        return runner(stub, new PassingChatCheck());
    }

    /**
     * A stand-in for the refuse-only model check (PO ruling P15) that answers the verdict it is set to, {@code pass} or
     * {@code refuse}, and counts the answers it was asked to read.
     */
    static final class ScriptedCheck implements LlmClient {
        final AtomicReference<String> verdict;
        final AtomicInteger calls = new AtomicInteger();

        ScriptedCheck(String verdict) {
            this.verdict = new AtomicReference<>(verdict);
        }

        @Override
        public Mono<LlmResponse> invoke(LlmRequest request) {
            calls.incrementAndGet();
            return Mono.just(new LlmResponse("{\"verdict\":\"" + verdict.get() + "\"}", "end_turn", new TokenUsage(400, 8),
                    PassingChatCheck.MODEL, null, "anthropic", 2L));
        }

        @Override
        public String providerName() {
            return "anthropic";
        }
    }

    private ReactiveWebApplicationContextRunner runner(StubModel stub, LlmClient check) {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES)
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false", "baymax.chat.enabled=true",
                        "baymax.llm.prices.anthropic.[claude-sonnet-5].input=2.00",
                        "baymax.llm.prices.anthropic.[claude-sonnet-5].output=10.00",
                        "baymax.chat.check.model=" + PassingChatCheck.MODEL,
                        "baymax.llm.prices.anthropic.[" + PassingChatCheck.MODEL + "].input=0.10",
                        "baymax.llm.prices.anthropic.[" + PassingChatCheck.MODEL + "].output=0.50")
                .withBean(ChatModelConfiguration.CHAT_CLIENT, BaymaxModelClient.class, () -> new BaymaxModelClient(stub))
                .withBean(ChatCheckModelConfiguration.CHECK_CLIENT, BaymaxModelClient.class, () -> new BaymaxModelClient(check));
    }

    @Test
    void theFiveQuestionsOnASyntheticFamily() {
        StubModel stub = new StubModel();
        PassingChatCheck check = new PassingChatCheck();
        runner(stub, check).run(context -> {
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
            long checkRows = count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat_check'");
            long unpricedChecks = count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat_check' AND (document_id IS NOT NULL"
                    + " OR cost_usd IS NULL)");

            AskUseCase asks = context.getBean(AskUseCase.class);
            ChatCopyPort copy = context.getBean(ChatCopyPort.class);

            AskAnswer creatinine = ask(asks, f, ma, "আম্মার ক্রিয়েটিনিন কত?");
            assertThat(creatinine.kind()).isEqualTo(AnswerKind.ANSWER);
            assertThat(handler(creatinine)).isEqualTo("model");
            assertThat(creatinine.answer()).contains("1.8");

            AskAnswer dose = ask(asks, f, ma, "ওষুধটা অর্ধেক করব?");
            assertThat(dose.kind()).isEqualTo(AnswerKind.ANSWER);
            assertThat(dose.answer()).contains("Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে").contains(copy.line("bn", "chat.dose.ask_doctor"));

            // PO ruling P14: what a medicine is for is answered by code, with no model call, and stored as such (V30)
            int beforeNapa = stub.calls.get();
            AskAnswer napa = ask(asks, f, ma, "Napa কীসের?");
            assertThat(napa.kind()).isEqualTo(AnswerKind.DECLINED);
            assertThat(handler(napa)).isEqualTo("medicine_purpose");
            assertThat(stub.calls.get()).as("model calls for what a medicine is for").isEqualTo(beforeNapa);
            // a name no stored line holds: every stored line, never that the medicine is missing (review of #171, finding 6)
            assertThat(napa.answer()).startsWith(copy.line("bn", "chat.purpose.unmatched", "Ma-র") + "\nTab. Demoprol 5 mg · 1+0+0 · খাবারের পরে")
                    .contains(copy.line("bn", "chat.purpose.ask")).doesNotContain("জ্বর");
            AskAnswer demoprol = ask(asks, f, ma, "Demoprol কীসের ওষুধ?");
            assertThat(handler(demoprol)).isEqualTo("medicine_purpose");
            assertThat(demoprol.answer()).startsWith(copy.line("bn", "chat.purpose.written", "Ma-র") + "\nTab. Demoprol 5 mg · 1+0+0 · খাবারের পরে");
            assertThat(stub.calls.get()).isEqualTo(beforeNapa);

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

            assertThat(count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat'") - chatRows).as("chat cost rows").isEqualTo(3);
            assertThat(count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat' AND (document_id IS NOT NULL OR cost_usd IS NULL)"))
                    .as("chat cost rows naming a paper, or with no cost").isZero();
            // PO rulings P15 and P16(c), on real Postgres: each of the three modelled answers is read by the model check, the two
            // that name no medicine included; the two purpose replies and the emergency are code's and never are. One
            // ai_call_log row each under chat_check (migration chat_model_check), priced, and each turn counts its one check
            assertThat(check.calls.get()).as("model check calls").isEqualTo(3);
            assertThat(count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat_check'") - checkRows)
                    .as("model check cost rows").isEqualTo(3);
            assertThat(count("SELECT count(*) FROM baymax.ai_call_log WHERE purpose = 'chat_check' AND (document_id IS NOT NULL"
                    + " OR cost_usd IS NULL)")).as("model check rows naming a paper, or with no cost").isEqualTo(unpricedChecks);
            assertThat(text("SELECT check_calls || '/' || model_calls || '/' || handler FROM baymax.chat_turn WHERE id = '"
                    + dose.turnId() + "'")).isEqualTo("1/1/model");
            assertThat(text("SELECT check_calls || '/' || model_calls || '/' || handler FROM baymax.chat_turn WHERE id = '"
                    + creatinine.turnId() + "'")).as("a labs-only answer, checked (P16(c))").isEqualTo("1/1/model");
            assertThat(text("SELECT check_calls || '/' || model_calls || '/' || handler FROM baymax.chat_turn WHERE id = '"
                    + napa.turnId() + "'")).as("a code-built purpose reply, never checked").isEqualTo("0/0/medicine_purpose");
            assertThat(text("SELECT check_calls || '/' || model_calls || '/' || handler FROM baymax.chat_turn WHERE id = '"
                    + chest.turnId() + "'")).as("an emergency, never checked").isEqualTo("0/0/red_flag");
            assertThat(count("SELECT count(*) FROM baymax.chat_turn WHERE family_id = '" + f.id() + "'")).isEqualTo(6);
            assertThat(count("SELECT count(*) FROM baymax.chat_turn WHERE family_id = '" + f.id() + "' AND counted")).isEqualTo(3);
            // askingRecordsNoView: reading the papers into a context is not opening a paper
            assertThat(count("SELECT count(*) FROM baymax.audit_event WHERE kind = 'document_view'")).isEqualTo(views);
            // what was shown is what is stored, byte for byte
            for (AskAnswer a : List.of(creatinine, dose, napa, demoprol, chest, thanks)) {
                assertThat(text("SELECT answer FROM baymax.chat_turn WHERE id = '" + a.turnId() + "'")).isEqualTo(a.answer());
            }
        });
    }

    // ---- The model check (PO ruling P15) and #166's quoted lines (V31: medicine_document_ids, medicine_crop_keys), on real
    // Postgres: the real service, context builder and adapter write the row, and each assertion reads one column of it.

    /** The stored crop of the prescription's one line: what a quoted line is shown beside (DR-12). */
    static final String RX_CROP = "fam/rx/crop-m1.jpg";
    static final String WRITTEN = "Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে";

    /** A family, its patient Ma, and Ma's latest prescription, one line with that line's crop. */
    private record Seeded(FamilyAccount family, PatientProfile ma, UUID rx) {
    }

    private static Seeded seed(ApplicationContext context, String number) throws Exception {
        Instant t = Instant.now();
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, number, "Rahim", FamilyAccount.Plan.FAMILY, t, t)).block();
        PatientProfile ma = records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), t, t))
                .block();
        UUID rx = paper(ma, "prescription", "routine");
        sql("INSERT INTO baymax.medication_event (patient_id, document_id, name, dose_text, timing_text, crop_key, at) VALUES ('"
                + ma.id() + "','" + rx + "','Tab. Demoprol 5 mg','1+0+0','খাবারের পরে','" + RX_CROP + "',now())");
        return new Seeded(f, ma, rx);
    }

    /** One stored turn's quoted lines, as {@code <papers>|<crops>}, each array comma-joined in its stored order. */
    private static String quoted(AskAnswer a) throws Exception {
        return text("SELECT array_to_string(medicine_document_ids, ',') || '|' || array_to_string(medicine_crop_keys, ',')"
                + " FROM baymax.chat_turn WHERE id = '" + a.turnId() + "'");
    }

    /** One stored turn's path: {@code handler/kind/model_calls/check_calls}. */
    private static String path(AskAnswer a) throws Exception {
        return text("SELECT handler || '/' || kind || '/' || model_calls || '/' || check_calls FROM baymax.chat_turn WHERE id = '"
                + a.turnId() + "'");
    }

    /**
     * A pass leaves the answer as the code checks passed it, and the row keeps the line it quotes with that line's crop.
     * Byte-equal is asserted on real Postgres against a twin family asking the same question with the check switched off
     * (second review of #173, finding 1): the unread answer is what the code checks passed, so a pass that rebuilt, trimmed
     * or appended to that text shows as a difference between the two stored rows, not only on mocked ports.
     */
    @Test
    void aCheckPassKeepsTheQuotedLineAndItsCropInTheRow() {
        AtomicReference<String> unread = new AtomicReference<>();
        StubModel twin = new StubModel();
        ScriptedCheck unused = new ScriptedCheck("refuse");
        runner(twin, unused).withPropertyValues("baymax.chat.check.enabled=false").run(context -> {
            Seeded s = seed(context, "+8801700000775");
            AskAnswer dose = ask(context.getBean(AskUseCase.class), s.family(), s.ma(), "ওষুধটা অর্ধেক করব?");
            assertThat(unused.calls.get()).as("precondition: the twin's answer is never read by the check").isZero();
            assertThat(path(dose)).as("precondition: the twin's answer is the model's, shown").isEqualTo("model/answer/1/0");
            unread.set(text("SELECT answer FROM baymax.chat_turn WHERE id = '" + dose.turnId() + "'"));
        });
        assertThat(unread.get()).as("precondition: the twin stored its answer").isNotBlank();

        StubModel stub = new StubModel();
        ScriptedCheck check = new ScriptedCheck("pass");
        runner(stub, check).run(context -> {
            Seeded s = seed(context, "+8801700000772");
            AskUseCase asks = context.getBean(AskUseCase.class);

            AskAnswer dose = ask(asks, s.family(), s.ma(), "ওষুধটা অর্ধেক করব?");

            assertThat(check.calls.get()).as("model check calls").isEqualTo(1);
            assertThat(path(dose)).isEqualTo("model/answer/1/1");
            assertThat(dose.answer()).startsWith("Ma-র শেষ প্রেসক্রিপশনে লেখা: " + WRITTEN);
            assertThat(text("SELECT answer FROM baymax.chat_turn WHERE id = '" + dose.turnId() + "'")).isEqualTo(dose.answer());
            assertThat(text("SELECT answer FROM baymax.chat_turn WHERE id = '" + dose.turnId() + "'"))
                    .as("the passed answer, stored, against the same answer the check never read").isEqualTo(unread.get());
            assertThat(quoted(dose)).as("papers|crops of the passed turn").isEqualTo(s.rx() + "|" + RX_CROP);
            assertThat(text("SELECT ('" + s.rx() + "' = ANY(document_ids))::text FROM baymax.chat_turn WHERE id = '" + dose.turnId() + "'"))
                    .as("the quoted line's paper is cited").isEqualTo("true");
            assertThat(dose.medicines()).containsExactly(new CitedMedicine(s.rx(), RX_CROP));
        });
    }

    /**
     * A refusal after an answer that quoted a line stores the fixed reply and nothing the refused answer quoted: no paper,
     * no crop (V31's two arrays empty), and no cited paper. The same family's turn just before, passed, shows the fixture
     * does quote a line with its crop, so the empty arrays are the refusal's, not the fixture's.
     */
    @Test
    void aCheckRefusalAfterAQuotedLineStoresNoMedicineInTheRow() {
        StubModel stub = new StubModel();
        ScriptedCheck check = new ScriptedCheck("pass");
        runner(stub, check).run(context -> {
            Seeded s = seed(context, "+8801700000773");
            AskUseCase asks = context.getBean(AskUseCase.class);
            ChatCopyPort copy = context.getBean(ChatCopyPort.class);
            AskAnswer passed = ask(asks, s.family(), s.ma(), "ওষুধটা অর্ধেক করব?");
            assertThat(quoted(passed)).as("precondition: the same answer, passed, quotes the line with its crop")
                    .isEqualTo(s.rx() + "|" + RX_CROP);

            check.verdict.set("refuse");
            AskAnswer refused = ask(asks, s.family(), s.ma(), "ওষুধটা অর্ধেক করব?");

            assertThat(check.calls.get()).as("model check calls").isEqualTo(2);
            assertThat(path(refused)).isEqualTo("model_check/fallback/1/1");
            // PO ruling P16(d): the check's own line, never the code checks' chat.ask.failed
            assertThat(refused.answer()).startsWith(copy.line("bn", "chat.check.refused")).doesNotContain(WRITTEN)
                    .doesNotContain(copy.line("bn", "chat.ask.failed"));
            assertThat(text("SELECT answer FROM baymax.chat_turn WHERE id = '" + refused.turnId() + "'")).isEqualTo(refused.answer());
            assertThat(quoted(refused)).as("papers|crops of the refused turn").isEqualTo("|");
            assertThat(text("SELECT cardinality(medicine_document_ids) || '/' || cardinality(medicine_crop_keys) || '/'"
                    + " || cardinality(document_ids) FROM baymax.chat_turn WHERE id = '" + refused.turnId() + "'"))
                    .as("quoted papers/crops/cited papers of the refused turn").isEqualTo("0/0/0");
            assertThat(refused.medicines()).isEmpty();
        });
    }

    /**
     * A P14 reply is built by code from the stored lines: it is never sent to the model check, and its row keeps the line it
     * shows with that line's crop (#166, {@code MedicinePurposeReply.Reply#quoted}).
     */
    @Test
    void aPurposeReplyIsNeverCheckedAndKeepsItsCropInTheRow() {
        StubModel stub = new StubModel();
        ScriptedCheck check = new ScriptedCheck("pass");
        runner(stub, check).run(context -> {
            Seeded s = seed(context, "+8801700000774");
            AskUseCase asks = context.getBean(AskUseCase.class);

            AskAnswer demoprol = ask(asks, s.family(), s.ma(), "Demoprol কীসের ওষুধ?");

            assertThat(check.calls.get()).as("model check calls for a code-built reply").isZero();
            assertThat(stub.calls.get()).as("chat model calls for a code-built reply").isZero();
            assertThat(path(demoprol)).isEqualTo("medicine_purpose/declined/0/0");
            assertThat(demoprol.answer()).contains(WRITTEN);
            assertThat(quoted(demoprol)).as("papers|crops of the purpose reply").isEqualTo(s.rx() + "|" + RX_CROP);
            assertThat(demoprol.medicines()).containsExactly(new CitedMedicine(s.rx(), RX_CROP));
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
