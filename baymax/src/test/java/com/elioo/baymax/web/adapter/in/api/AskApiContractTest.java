package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.companion.config.ChatModelConfiguration;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan CHAT-api Task 7: {@code POST /app/api/patients/{id}/ask} over the real router, guard, services and Postgres under a
 * fixed clock, with a stub model in place of Claude (no real model call). One case per committed MSW fixture under
 * {@code medioo-web/fixtures/api/ask/}, compared byte for byte. The turn id is the one value the server draws at random;
 * the fixture carries the case's fixed id in its place, after the test has asserted the real one is a UUID naming the
 * stored turn. No gate or model field reaches the browser, walked by field. Fictional throughout.
 */
@Testcontainers
class AskApiContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 12:00 in Dhaka on 30 October 2026. */
    static final Instant NOW = Instant.parse("2026-10-30T06:00:00Z");
    static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/ask");
    static final Path WRITTEN = Path.of("build/web-contract/ask");
    static final List<String> CASES = List.of("answer", "answer-dose", "answer-long", "emergency", "emergency-abroad", "limit", "fallback",
            "declined");
    /** The answer-long case's body: one stored fact, said over and over, so the whole answer runs close to the cap. */
    static final String LONG_LINE = "Ma-র ক্রিয়েটিনিন 1.8 mg/dL, কাগজে ছাপা সীমা 0.7–1.3-এর বাইরে।";
    static final Set<String> NEVER_SENT = Set.of("handler", "violations", "model", "model_calls", "input_tokens", "output_tokens",
            "cost_usd", "prompt", "counted", "latency_ms", "document_ids", "family_id");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger NUMBERS = new AtomicInteger(800);
    private static final AtomicInteger ABROAD = new AtomicInteger(50);

    /** Claude's stand-in: the answer each case's question gets. */
    static final class StubModel implements LlmClient {
        @Override
        public Mono<LlmResponse> invoke(LlmRequest request) {
            String q = request.userPrompt().substring(request.userPrompt().indexOf("QUESTION:"));
            String text = q.contains("ক্রিয়েটিনিন") ? "Ma-র ক্রিয়েটিনিন 1.8 mg/dL, কাগজে ছাপা সীমা 0.7–1.3-এর বাইরে।"
                    : q.contains("অর্ধেক") ? "Ma-র শেষ প্রেসক্রিপশনে লেখা: {{rx:1}}"
                    // review of #166, finding 9: an answer near the cap, taller than a phone's window above the composer
                    : q.contains("সব কাগজে") ? String.join("\n", java.util.Collections.nCopies(15, LONG_LINE))
                    : "Ma-র প্রেশার 140/90।";
            return Mono.just(new LlmResponse(text, "end_turn", new TokenUsage(900, 120), "claude-sonnet-5", null, "anthropic", 7L));
        }

        @Override
        public String providerName() {
            return "anthropic";
        }
    }

    static ReactiveWebApplicationContextRunner runner(String... extra) {
        return FixedPapers.runnerAt(POSTGRES, NOW)
                .withPropertyValues("baymax.chat.enabled=true", "baymax.llm.prices.anthropic.[claude-sonnet-5].input=2.00",
                        "baymax.llm.prices.anthropic.[claude-sonnet-5].output=10.00")
                .withPropertyValues(extra)
                .withBean(ChatModelConfiguration.CHAT_CLIENT, BaymaxModelClient.class, () -> new BaymaxModelClient(new StubModel()));
    }

    static Stream<String> cases() {
        return CASES.stream();
    }

    /** Who asks, about whom, and what. */
    record Seeded(PatientProfile patient, String session, String question) {
        String uri() {
            return "/app/api/patients/" + patient.id() + "/ask";
        }
    }

    static String question(String name) {
        return switch (name) {
            case "answer", "limit", "declined" -> "আম্মার ক্রিয়েটিনিন কত?";
            case "answer-dose" -> "ওষুধটা অর্ধেক করব?";
            case "answer-long" -> "আম্মার সব কাগজে কী লেখা?";
            case "emergency" -> "আম্মার বুকে ব্যথা";
            case "emergency-abroad" -> "আম্মার শ্বাসকষ্ট হচ্ছে";
            case "fallback" -> "আম্মার প্রেশার কত?";
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** A family, Ma, a finished lab paper with one creatinine and a finished prescription with one fictional medicine. */
    static Seeded seed(ApplicationContext context, String k, String name) throws Exception {
        UUID f = FixedPapers.family(POSTGRES, FixedPapers.id(k, "family"), "+8801700000" + NUMBERS.incrementAndGet(), "Rahim");
        PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "ma"), f, "Ma");
        UUID lab = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "lab"), ma, "lab_report", "DONE", "2026-10-12",
                Instant.parse("2026-10-12T04:00:00Z"), "routine");
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.observation (id, patient_id, document_id, name, canonical_name, value, unit, ref_low,"
                        + " ref_high, crop_key, observed_at) VALUES (?, ?, ?, 'Creatinine', 'creatinine', '1.8', 'mg/dL', '0.7', '1.3', ?, ?)",
                FixedPapers.id(k, "creatinine"), ma.id(), lab, "k", Timestamp.from(Instant.parse("2026-10-12T04:00:00Z")));
        UUID rx = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "rx"), ma, "prescription", "DONE", "2026-10-15",
                Instant.parse("2026-10-15T04:00:00Z"), "routine");
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.medication_event (id, patient_id, document_id, name, dose_text, timing_text, crop_key, at)"
                        + " VALUES (?, ?, ?, 'Tab. Demoprol 5 mg', '1+0+0', 'খাবারের পরে', ?, ?)",
                FixedPapers.id(k, "demoprol"), ma.id(), rx, cropKey(rx), Timestamp.from(Instant.parse("2026-10-15T04:00:00Z")));
        String session = FixedPapers.session(context, f, NOW);
        if ("emergency-abroad".equals(name)) {
            String number = "+4477009000" + ABROAD.incrementAndGet();
            UUID sibling = FixedPapers.family(POSTGRES, FixedPapers.id(k, "sibling"), number, "Sister");
            FixedPapers.share(POSTGRES, ma, number);
            session = FixedPapers.session(context, sibling, NOW);
        }
        if ("limit".equals(name)) {
            // the day's one counted question is already asked
            FixedPapers.exec(POSTGRES, "INSERT INTO baymax.chat_turn (family_id, patient_id, lang, question, answer, kind, counted, handler,"
                    + " model_calls, created_at, answered_at) VALUES (?, ?, 'bn', 'আগের প্রশ্ন', 'আগের উত্তর', 'answer', true, 'model', 1, ?, ?)",
                    f, ma.id(), Timestamp.from(NOW.minusSeconds(600)), Timestamp.from(NOW.minusSeconds(590)));
        }
        return new Seeded(ma, session, question(name));
    }

    /** The seeded medicine line's stored crop key, on its prescription. */
    static String cropKey(UUID rx) {
        return "f/p/" + rx + "/crop-demoprol.jpg";
    }

    static ReactiveWebApplicationContextRunner runnerFor(String name) {
        return switch (name) {
            case "limit" -> runner("baymax.chat.daily-cap=1");
            case "declined" -> runner("baymax.chat.enabled=false");
            default -> runner();
        };
    }

    static EntityExchangeResult<byte[]> post(WebTestClient client, String session, String uri, Object body) {
        WebTestClient.RequestBodySpec spec = client.post().uri(uri).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            spec = spec.cookie("baymax_session", session);
        }
        return spec.bodyValue(body).exchange().expectBody().returnResult();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void eachCaseIsItsCommittedMswFixture(String name) {
        runnerFor(name).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "fixture-ask-" + name;
            Seeded s = seed(context, k, name);
            EntityExchangeResult<byte[]> r = post(client, s.session(), s.uri(), Map.of("question", s.question()));
            assertThat(r.getStatus().value()).as("POST " + s.uri() + " for " + name).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(r.getResponseBody(), LinkedHashMap.class);
            UUID turn = UUID.fromString((String) body.get("turn_id"));
            assertThat(FixedPapers.rows(POSTGRES, "SELECT answer FROM baymax.chat_turn WHERE id = '" + turn + "'"))
                    .as("the answer sent is the answer stored").containsExactly(List.of((String) body.get("answer")));
            body.put("turn_id", FixedPapers.id(k, "turn").toString());
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("_meta", Snapshot.meta(AskApiContractTest.class, "POST /app/api/patients/{id}/ask over the case " + name));
            envelope.put("status", r.getStatus().value());
            envelope.put("body", body);
            String file = name + ".json";
            Snapshot.matches(COMMITTED.resolve(file), WRITTEN.resolve(file), Snapshot.json(envelope),
                    "cp baymax/" + WRITTEN.resolve(file) + " medioo-web/fixtures/api/ask/" + file);
        });
    }

    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        assertThat(AttentionApiContractTest.committed(COMMITTED))
                .containsExactlyInAnyOrderElementsOf(CASES.stream().map(n -> n + ".json").toList());
    }

    @Test
    void eachCaseHasItsKind() throws IOException {
        Map<String, String> kinds = Map.of("answer", "answer", "answer-dose", "answer", "answer-long", "answer", "emergency", "emergency",
                "emergency-abroad", "emergency", "limit", "limit", "fallback", "fallback", "declined", "declined");
        for (String name : CASES) {
            JsonNode body = JSON.readTree(COMMITTED.resolve(name + ".json").toFile()).get("body");
            assertThat(body.get("kind").asText()).as(name).isEqualTo(kinds.get(name));
            assertThat(body.get("question").asText()).as(name).isEqualTo(question(name));
        }
    }

    @Test
    void noGateOrModelFieldReachesTheBrowser() throws IOException {
        List<String> found = new ArrayList<>();
        int walked = 0;
        for (String name : CASES) {
            walked += walk(JSON.readTree(COMMITTED.resolve(name + ".json").toFile()).get("body"), name, found);
        }
        assertThat(walked).as("fields walked").isGreaterThanOrEqualTo(CASES.size() * 6);
        assertThat(found).isEmpty();
    }

    private static int walk(JsonNode node, String path, List<String> found) {
        int n = 0;
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                n++;
                if (NEVER_SENT.contains(e.getKey())) {
                    found.add(path + "." + e.getKey());
                }
                n += walk(e.getValue(), path + "." + e.getKey(), found);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                n += walk(child, path + "[]", found);
            }
        }
        return n;
    }

    /**
     * Review of #166, finding 5 (DR-12, DR-16): a dose answer quotes the prescription line verbatim, and the line goes to the
     * browser with its crop, the prescription's own image of that line, so the family can check what was transcribed. Every
     * other case quotes no line and carries an empty list, never a missing field.
     */
    @Test
    void aQuotedLineIsSentWithItsCrop() throws IOException {
        UUID rx = FixedPapers.id("fixture-ask-answer-dose", "rx");
        JsonNode dose = JSON.readTree(COMMITTED.resolve("answer-dose.json").toFile()).get("body").get("medicines");
        assertThat(dose).as("answer-dose medicines").hasSize(1);
        assertThat(dose.get(0).get("document_id").asText()).isEqualTo(rx.toString());
        assertThat(dose.get(0).get("crop").get("src").asText()).isEqualTo(com.elioo.baymax.web.adapter.in.ImageRefs.crop(rx, cropKey(rx)));
        for (String name : CASES) {
            if (!"answer-dose".equals(name)) {
                JsonNode none = JSON.readTree(COMMITTED.resolve(name + ".json").toFile()).get("body").get("medicines");
                assertThat(none).as(name + " medicines").isNotNull();
                assertThat(none.isArray() && none.isEmpty()).as(name + " quotes no line").isTrue();
            }
        }
    }

    /** Review of #166, finding 9: the long case is a real answer within the cap and long enough to outgrow a phone's window. */
    @Test
    void theLongAnswerIsNearTheCap() throws IOException {
        JsonNode body = JSON.readTree(COMMITTED.resolve("answer-long.json").toFile()).get("body");
        assertThat(body.get("kind").asText()).isEqualTo("answer");
        assertThat(body.get("answer").asText().length()).as("characters").isBetween(900, 1200);
    }

    @Test
    void anEmergencyAbroadOffersNo999() throws IOException {
        assertThat(JSON.readTree(COMMITTED.resolve("emergency.json").toFile()).get("body").get("call_999").asBoolean()).isTrue();
        assertThat(JSON.readTree(COMMITTED.resolve("emergency-abroad.json").toFile()).get("body").get("call_999").asBoolean()).isFalse();
    }

    @Test
    void aTooLongQuestionIsInvalidAndStoresNothing() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "too-long-" + UUID.randomUUID(), "answer");
            long before = FixedPapers.count(POSTGRES, "SELECT count(*) FROM baymax.chat_turn");
            for (Object body : List.of(Map.of("question", "ক".repeat(1001)), Map.of("question", "   "), Map.of("other", "x"),
                    List.of("not", "an", "object"))) {
                EntityExchangeResult<byte[]> r = post(client, s.session(), s.uri(), body);
                assertThat(r.getStatus().value()).as("POST " + body.toString().length() + " chars").isEqualTo(400);
                assertThat(new String(r.getResponseBody())).isEqualTo("{\"reason\":\"INVALID\"}");
            }
            EntityExchangeResult<byte[]> notUuid = post(client, s.session(), "/app/api/patients/not-a-uuid/ask", Map.of("question", "q"));
            assertThat(notUuid.getStatus().value()).isEqualTo(400);
            EntityExchangeResult<byte[]> other = post(client, s.session(), "/app/api/patients/" + UUID.randomUUID() + "/ask",
                    Map.of("question", "আম্মার ক্রিয়েটিনিন কত?"));
            assertThat(other.getStatus().value()).as("a patient this account cannot see").isEqualTo(404);
            assertThat(FixedPapers.count(POSTGRES, "SELECT count(*) FROM baymax.chat_turn")).isEqualTo(before);
        });
    }
}
