package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.Json;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B3 Step 5: {@code GET /app/api/patients/{id}/attention}, the চ্যাট stack, over the real router, guard, services and
 * Postgres under a fixed clock. The kinds come in {@code AttentionUseCase}'s order; ROUTINE has no card; another family's
 * patient is 404; every {@code *_key} in every committed fixture area resolves in both languages. The three cases React's MSW
 * handlers serve are written here, with fixed ids, and compared byte for byte with the committed
 * {@code medioo-web/fixtures/api/attention/*.json}.
 */
@Testcontainers
class AttentionApiContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 12:00 in Dhaka on 21 October 2026. */
    static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/attention");
    static final Path WRITTEN = Path.of("build/web-contract/attention");
    static final List<String> CASES = List.of("empty", "full-stack", "now-only");
    private static final AtomicInteger NUMBERS = new AtomicInteger(700);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One seeded case: whose stack, read by whom. */
    record Seeded(PatientProfile patient, String session) {
    }

    static Stream<String> cases() {
        return CASES.stream();
    }

    /** A family with one patient, Ma, all ids fixed by the case name. */
    static Seeded family(ApplicationContext context, String kase) throws Exception {
        UUID f = FixedPapers.family(POSTGRES, FixedPapers.id(kase, "family"), "+8801700000" + NUMBERS.incrementAndGet(), "Rahim");
        PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(kase, "ma"), f, "Ma");
        return new Seeded(ma, FixedPapers.session(context, f, NOW));
    }

    /** The case, seeded: {@code empty} holds nothing; {@code now-only} one NOW paper; {@code full-stack} every kind of card. */
    static Seeded seed(ApplicationContext context, String kase, String shape) throws Exception {
        Seeded s = family(context, kase);
        PatientProfile ma = s.patient();
        switch (shape) {
            case "empty" -> {
            }
            case "now-only" -> FixedPapers.paper(POSTGRES, FixedPapers.id(kase, "now"), ma, "lab_report", "DONE", "2026-10-19",
                    NOW.minus(Duration.ofDays(1)), "now");
            case "full-stack" -> {
                FixedPapers.paper(POSTGRES, FixedPapers.id(kase, "now"), ma, "lab_report", "DONE", "2026-10-19",
                        NOW.minus(Duration.ofDays(1)), "now");
                UUID week = FixedPapers.paper(POSTGRES, FixedPapers.id(kase, "week"), ma, "lab_report", "DONE", "2026-10-18",
                        NOW.minus(Duration.ofDays(2)), "this_week");
                // the model marked this printed date uncertain (Q6): the card carries the mark, no other card does
                FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET doc_date_uncertain = true WHERE id = ?", week);
                FixedPapers.paper(POSTGRES, FixedPapers.id(kase, "retake"), ma, "lab_report", "NEEDS_RETAKE", null,
                        NOW.minus(Duration.ofHours(3)), null);
                UUID rx = FixedPapers.paper(POSTGRES, FixedPapers.id(kase, "rx"), ma, "prescription", "DONE", "2026-10-10",
                        NOW.minus(Duration.ofDays(11)), "routine");
                FixedPapers.followUp(POSTGRES, FixedPapers.id(kase, "follow"), ma, rx, "Come back with the CBC report", "2026-10-24");
                FixedPapers.paper(POSTGRES, FixedPapers.id(kase, "reading"), ma, null, "PROCESSING", null,
                        NOW.minus(Duration.ofMinutes(5)), null);
            }
            default -> throw new IllegalArgumentException(shape);
        }
        return s;
    }

    static EntityExchangeResult<byte[]> get(WebTestClient client, String session, UUID patient) {
        return client.get().uri("/app/api/patients/" + patient + "/attention").cookie("baymax_session", session)
                .exchange().expectBody().returnResult();
    }

    static JsonNode ok(WebTestClient client, String session, UUID patient) throws IOException {
        EntityExchangeResult<byte[]> r = get(client, session, patient);
        assertThat(r.getStatus().value()).as("GET attention").isEqualTo(200);
        return JSON.readTree(r.getResponseBody());
    }

    private static List<String> kinds(JsonNode body) {
        List<String> kinds = new ArrayList<>();
        body.get("items").forEach(i -> kinds.add(i.get("kind").asText() + (i.has("level") ? ":" + i.get("level").asText() : "")));
        return kinds;
    }

    @Test
    void theStackIsAttentionUseCasesOrder() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "order", "full-stack");
            JsonNode body = ok(client, s.session(), s.patient().id());
            assertThat(kinds(body)).containsExactly("urgent:now", "urgent:this_week", "retake", "follow_up", "reading");
            assertThat(body.get("items")).allSatisfy(card -> assertThat(card.get("action_key").asText()).isEqualTo("attn.open"));
            JsonNode follow = body.get("items").get(3);
            assertThat(follow.get("instruction").asText()).as("the doctor's words as written").isEqualTo("Come back with the CBC report");
            assertThat(follow.get("due_date").asText()).isEqualTo("2026-10-24");
            assertThat(follow.get("crop").get("src").asText()).as("the crop it was read from")
                    .startsWith("/app/documents/" + FixedPapers.id("order", "rx") + "/image?key=");
        });
    }

    /** D2's half, owed by B3: an urgent card carries the read model's uncertain mark, and only where it was set. */
    @Test
    void anUrgentCardCarriesTheUncertainMarkOnlyWhereItWasSet() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "uncertain", "full-stack");
            JsonNode items = ok(client, s.session(), s.patient().id()).get("items");
            assertThat(items.get(0).get("date_uncertain").asBoolean()).as("the NOW card, printed and certain").isFalse();
            assertThat(items.get(1).get("date_uncertain").asBoolean()).as("the THIS_WEEK card, marked by the model").isTrue();
        });
    }

    /** Plan D1's additive half, owed here: the retake and reading cards carry the day they are filed under (D2). */
    @Test
    void theRetakeAndReadingCardsCarryTheirFiledDay() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "filed-day", "full-stack");
            JsonNode items = ok(client, s.session(), s.patient().id()).get("items");
            for (int i : new int[]{2, 4}) {
                JsonNode card = items.get(i);
                assertThat(card.path("date").asText()).as(card.get("kind").asText() + " $.date: its Dhaka upload day").isEqualTo("2026-10-21");
                assertThat(card.path("date_is_upload_day").asBoolean()).as(card.get("kind").asText() + " $.date_is_upload_day").isTrue();
            }
        });
    }

    @Test
    void routineHasNoCard() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = family(context, "routine");
            FixedPapers.paper(POSTGRES, FixedPapers.id("routine", "paper"), s.patient(), "lab_report", "DONE", "2026-10-19",
                    NOW.minus(Duration.ofDays(1)), "routine");
            assertThat(ok(client, s.session(), s.patient().id()).get("items")).as("a routine paper alone").isEmpty();
        });
    }

    @Test
    void anotherFamilysPatientIsNotFound() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded theirs = seed(context, "theirs", "now-only");
            Seeded mine = family(context, "mine");
            // the attention route writes no audit row on any path, so "the 404 records nothing" is asserted where it can
            // fail: PatientTimelineApiContractTest.theCalendarRecordsOneTimelineViewForTheOwnerAndNoneFor404
            EntityExchangeResult<byte[]> r = get(client, mine.session(), theirs.patient().id());
            assertThat(r.getStatus().value()).isEqualTo(404);
            assertThat(JSON.readTree(r.getResponseBody())).isEqualTo(JSON.readTree("{\"reason\":\"NOT_FOUND\"}"));
            assertThat(get(client, mine.session(), UUID.randomUUID()).getStatus().value()).as("a patient that does not exist").isEqualTo(404);
        });
    }

    @ParameterizedTest
    @MethodSource("cases")
    void eachCaseIsItsCommittedMswFixture(String name) {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "fixture-" + name, name);
            EntityExchangeResult<byte[]> r = get(client, s.session(), s.patient().id());
            write(AttentionApiContractTest.class, COMMITTED, WRITTEN, "attention", name,
                    "GET /app/api/patients/{id}/attention over the case " + name, r);
        });
    }

    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        assertThat(committed(COMMITTED)).as("committed fixtures vs the cases")
                .containsExactlyInAnyOrderElementsOf(CASES.stream().map(n -> n + ".json").toList());
    }

    /**
     * Every {@code *_key} in every committed fixture of the B3 areas resolves in both languages: a UI key in
     * {@code ui-*_{bn,en}}, or an {@code urgency.*} line of {@code messages_{bn,en}}. Guarded: the walk saw keys.
     */
    @Test
    void everyEmittedKeyResolvesInBothLanguages() throws IOException {
        UiCopy copy = new UiCopy();
        Set<String> both = new HashSet<>(copy.keys(Lang.BN));
        both.retainAll(copy.keys(Lang.EN));
        Set<String> urgency = new HashSet<>(messages("baymax/messages_bn.properties"));
        urgency.retainAll(messages("baymax/messages_en.properties"));
        urgency.removeIf(k -> !k.startsWith("urgency."));
        List<String> seen = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String area : areas()) {
            Path dir = COMMITTED.resolveSibling(area);
            for (String file : committed(dir)) {
                JsonNode tree = JSON.readTree(Files.readString(dir.resolve(file), StandardCharsets.UTF_8));
                Json.walk(tree.get("body"), (path, node) -> {
                    String field = Json.fieldName(path);
                    if (field != null && field.endsWith("_key") && node.isTextual()) {
                        seen.add(node.asText());
                        if (!both.contains(node.asText()) && !urgency.contains(node.asText())) {
                            unresolved.add(area + "/" + file + " " + path + " = " + node.asText());
                        }
                    }
                });
            }
        }
        assertThat(seen).as("the walk saw the keys the fixtures carry").contains("attn.open", "attn.retake.title", "urgency.now");
        assertThat(unresolved).as("keys that do not resolve in both languages").isEmpty();
    }

    // ---- shared by the B3 contract tests ----------------------------------------------------------------------

    /** The generated-file envelope, written and compared with the committed fixture (the failure prints the cp). */
    static void write(Class<?> generator, Path committed, Path written, String area, String name, String source,
                      EntityExchangeResult<byte[]> r) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_meta", Snapshot.meta(generator, source));
        envelope.put("status", r.getStatus().value());
        envelope.put("body", JSON.readValue(r.getResponseBody(), Object.class));
        String file = name + ".json";
        Snapshot.matches(committed.resolve(file), written.resolve(file), Snapshot.json(envelope),
                "cp baymax/" + written.resolve(file) + " medioo-web/fixtures/api/" + area + "/" + file);
    }

    /**
     * Every fixture area, derived: each subdirectory of {@code medioo-web/fixtures/api}, so an area added later (or a
     * {@code *_key} added to one) is walked without anyone remembering to list it. Guarded against emptiness and with a
     * floor: a wrong path or a deleted area fails here instead of the walks passing over nothing.
     */
    static List<String> areas() throws IOException {
        List<String> areas;
        try (Stream<Path> list = Files.list(COMMITTED.getParent())) {
            areas = list.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(areas).as("the fixture areas under " + COMMITTED.getParent().toAbsolutePath())
                .isNotEmpty().contains("attention", "timeline", "now-banner", "calendar", "follow-ups");
        return areas;
    }

    static List<String> committed(Path dir) throws IOException {
        List<String> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("the committed fixtures in " + dir.toAbsolutePath()).isNotEmpty();
        return files;
    }

    private static Set<String> messages(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = AttentionApiContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p.stringPropertyNames();
    }
}
