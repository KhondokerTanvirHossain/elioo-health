package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.service.ComposedBodies;
import com.elioo.baymax.outbound.application.service.ComposedBodies.Composed;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.testsupport.Json;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan W5-api: {@code GET /app/api/patients/{id}/thread}, the চ্যাট thread, over the real router, guard, services and
 * Postgres under a fixed clock. A paper's card is the paper JSON's own body ({@code GET /app/api/documents/{id}}), compared
 * parsed and whole; a message is what the provider accepted for this viewer's own account (S4), about a paper still
 * there or about none (S10), its body as sent, its kind as stored, except the explanation the paper's card already
 * carries as {@code wording} (review of #135, finding 6). No delivery or gate field reaches the browser, walked by field.
 * Every kind the database allows is read from {@code pg_constraint}, never from a list written here. The eight cases
 * React's MSW handlers serve are written with fixed ids and compared byte for byte with the committed
 * {@code medioo-web/fixtures/api/thread/*.json}.
 *
 * <p>Production-shaped (review of #135, findings 2 and 7): every seeded body is rendered from {@code messages_bn.properties}
 * as the composer assembles it ({@link ComposedBodies}: Bangla, multi-line, with its link, the medicine block and the
 * nudge opt-out line), never hand-written, and a NOW paper's urgency and verdicts are written by the real urgency writer
 * ({@code DocumentUrgencyUseCase.assessAndStore}), never typed into the row. Fictional throughout: +8801700000xxx and
 * +447700900xxx numbers, English names, invented medicines.</p>
 */
@Testcontainers
class ThreadApiContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 12:00 in Dhaka on 21 October 2026. */
    static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/thread");
    static final Path WRITTEN = Path.of("build/web-contract/thread");
    static final List<String> CASES = List.of("default", "now", "now-abroad", "retake", "failed", "first-run", "share-member", "second-page");
    /** The keys a delivery, a review or a model leaves on a row, none of which the browser may receive. */
    static final Set<String> NEVER_SENT = Set.of("wamid", "delivery_status", "reviewer", "reject_reason", "gate_status", "confidence",
            "model", "status_reason", "provider_message_id", "delivery_error", "urgency_reasons");
    /** A fictional opt-out token: the fixture carries the link's shape, never an HMAC of any real secret. */
    static final String OPTOUT_TOKEN = "5e9edf6d0a2a4913867500000000beef";
    /** The second-page case's long body: its paper, which sits on the page after the first. */
    static final int LONG_PAPER = 10;
    private static final AtomicInteger NUMBERS = new AtomicInteger(900);
    private static final AtomicInteger ABROAD = new AtomicInteger(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A seeded case: whose thread (the patient), read by whom (the session), and the papers it holds. */
    record Seeded(PatientProfile patient, String session, List<UUID> papers) {
        String uri() {
            return "/app/api/patients/" + patient.id() + "/thread";
        }
    }

    static Stream<String> cases() {
        return CASES.stream();
    }

    // ---- seeds ----------------------------------------------------------------------------------------------------

    static UUID family(String k, String role, String number, String owner) throws Exception {
        return FixedPapers.family(POSTGRES, FixedPapers.id(k, role), number, owner);
    }

    static String bd() {
        return "+8801700000" + NUMBERS.incrementAndGet();
    }

    static String uk() {
        return "+4477009000" + ABROAD.incrementAndGet();
    }

    /** The template family each stored kind is rendered from, so a seeded row cannot carry another kind's text. */
    static boolean renderedFor(String kind, Composed body) {
        return switch (kind) {
            case "explanation" -> body.template().startsWith("explanation.");
            case "detail" -> body.template().equals("detail.header");
            case "retake" -> body.template().startsWith("retake");
            case "failure" -> body.template().equals("failure.document");
            case "nudge" -> body.template().startsWith("nudge.");
            default -> false;
        };
    }

    /**
     * A message the provider accepted for {@code family} at {@code sentAt}: delivery_status 'sent' with its wamid (V14),
     * composed a minute before. {@code document} null is a message about no paper (a nudge). The body is a {@link Composed}
     * one, never a string written here.
     */
    static UUID delivered(String k, String role, UUID family, PatientProfile p, UUID document, String kind, String gate, Composed body,
                          Instant sentAt) throws Exception {
        assertThat(renderedFor(kind, body)).as("a " + kind + " row rendered from " + body.template()).isTrue();
        UUID id = FixedPapers.id(k, role);
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (id, family_id, patient_id, document_id, kind, urgency, urgency_reasons,"
                        + " body, gate_status, reviewer, decided_at, sent_at, delivery_status, provider_message_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'routine', '[]', ?, ?, ?, ?, ?, 'sent', ?, ?)",
                id, family, p.id(), document, kind, body.body(), gate, "approved".equals(gate) ? "tanvir" : null,
                "approved".equals(gate) ? Timestamp.from(sentAt.minusSeconds(30)) : null, Timestamp.from(sentAt),
                "wamid." + role, Timestamp.from(sentAt.minusSeconds(60)));
        return id;
    }

    /** A message composed and never delivered: the explanation waiting for the reviewer (PENDING), sent_at NULL. */
    static void pending(ApplicationContext context, String k, String role, UUID family, PatientProfile p, UUID document, Instant createdAt)
            throws Exception {
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (id, family_id, patient_id, document_id, kind, urgency, urgency_reasons,"
                        + " body, gate_status, created_at) VALUES (?, ?, ?, ?, 'explanation', 'this_week', '[]', ?, 'pending', ?)",
                FixedPapers.id(k, role), family, p.id(), document, ComposedBodies.explanation(context, document).body(), Timestamp.from(createdAt));
    }

    /** The log adapter's outcome (S11): sent_at stamped, no delivery status, nothing reached the family. */
    static void logged(ApplicationContext context, String k, String role, UUID family, PatientProfile p, UUID document, Instant sentAt)
            throws Exception {
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (id, family_id, patient_id, document_id, kind, urgency, urgency_reasons,"
                        + " body, gate_status, sent_at, created_at) VALUES (?, ?, ?, ?, 'detail', 'routine', '[]', ?, 'released', ?, ?)",
                FixedPapers.id(k, role), family, p.id(), document, ComposedBodies.detail(context, document).body(), Timestamp.from(sentAt),
                Timestamp.from(sentAt.minusSeconds(60)));
    }

    /** The follow-up nudge every case that has one sends: the patient's own name, a Bangla date, the doctor's words. */
    static Composed nudge(ApplicationContext context, PatientProfile p) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("patient", p.name());
        vars.put("due_date", "২৪ অক্টোবর");
        vars.put("instruction", "৭ দিন পর রিপোর্ট নিয়ে আবার আসবেন");
        String base = context.getBean(BaymaxProperties.class).getOutbound().getPublicBaseUrl();
        return ComposedBodies.nudge(context, "follow_up_due", vars, base + "/app/nudges/opt-out?p=" + p.id() + "&t=" + OPTOUT_TOKEN);
    }

    /** A medicine as the prescription wrote it, with its crop. */
    static void medicine(UUID id, PatientProfile p, UUID document, String name, String dose, String frequency, String duration, Instant at)
            throws Exception {
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.medication_event (id, patient_id, document_id, name, dose_text, frequency_text,"
                        + " duration_text, crop_key, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, p.id(), document, name, dose, frequency,
                duration, p.familyId() + "/" + p.id() + "/" + document + "/med-" + id.toString().substring(0, 8) + ".jpg", Timestamp.from(at));
    }

    /**
     * A DONE lab report with one critical potassium, assessed by the real urgency writer: the level, the reasons and the
     * value's verdict are what {@code UrgencyService} stores for 12.0 against a printed 3.5–5.1 (the 2× stopgap's NOW; the
     * potassium entry in marker-thresholds is still proposed), never typed into the row (review of #135, finding 2).
     */
    static UUID nowPaper(ApplicationContext context, String k, PatientProfile ma) throws Exception {
        UUID now = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "now"), ma, "lab_report", "DONE", "2026-10-20",
                Instant.parse("2026-10-20T04:00:00Z"), null);
        AppApiContext.sql(POSTGRES, "INSERT INTO baymax.observation (id, patient_id, document_id, name, value, unit, ref_low, ref_high,"
                + " crop_key, observed_at) VALUES ('" + FixedPapers.id(k, "potassium") + "','" + ma.id() + "','" + now
                + "','Potassium','12.0','mmol/L','3.5','5.1','" + ma.familyId() + "/" + ma.id() + "/" + now
                + "/crop-v1.jpg','2026-10-20T04:00:00Z')");
        context.getBean(DocumentUrgencyUseCase.class).assessAndStore(now).block(Duration.ofSeconds(30));
        return now;
    }

    /**
     * One case's rows, every id fixed by {@code k}:
     * <ul>
     *   <li>{@code default}: a routine paper whose delivered explanation its card carries and the detail the family asked
     *       for after it, a this-week paper whose explanation waits for the reviewer (its card says PENDING, no message is
     *       listed) and a logged detail (not listed), and a delivered nudge about no paper, with its opt-out line;</li>
     *   <li>{@code now}: a NOW paper, assessed by the urgency writer, and its approved, delivered explanation (the card's
     *       wording), read from a Bangladeshi phone;</li>
     *   <li>{@code now-abroad}: the same, read by a share member with a UK phone: the card offers no 999;</li>
     *   <li>{@code retake}, {@code failed}: the paper and the notice the family got about it;</li>
     *   <li>{@code first-run}: a patient with nothing yet;</li>
     *   <li>{@code share-member}: a Bangladeshi share member's view of the owner's paper: the card, with the explanation's
     *       wording as the paper page shows it, and none of the owner's message rows (S4);</li>
     *   <li>{@code second-page}: 25 papers, each with a delivered explanation (on its card) and a delivered detail, read with
     *       the first page's cursor; paper {@link #LONG_PAPER} is a prescription whose detail runs to the outbound cap
     *       with its medicine block;</li>
     *   <li>{@code mixed} (not a fixture): every status a card can have, and a value with a stored crop size, for the
     *       card-equals-paper test.</li>
     * </ul>
     */
    static Seeded seed(ApplicationContext context, String k, String shape) throws Exception {
        UUID f = family(k, "family", bd(), "Rahim");
        PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "ma"), f, "Ma");
        String session = FixedPapers.session(context, f, NOW);
        List<UUID> papers = new ArrayList<>();
        switch (shape) {
            case "default" -> {
                UUID routine = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "routine"), ma, "lab_report", "DONE", "2026-10-12",
                        Instant.parse("2026-10-12T04:00:00Z"), "routine");
                delivered(k, "routine-explanation", f, ma, routine, "explanation", "released", ComposedBodies.explanation(context, routine),
                        Instant.parse("2026-10-12T04:02:00Z"));
                delivered(k, "routine-detail", f, ma, routine, "detail", "released", ComposedBodies.detail(context, routine),
                        Instant.parse("2026-10-12T04:05:00Z"));
                UUID week = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "this-week"), ma, "lab_report", "DONE", "2026-10-18",
                        Instant.parse("2026-10-18T05:00:00Z"), "this_week");
                pending(context, k, "week-explanation", f, ma, week, Instant.parse("2026-10-18T05:01:00Z"));
                logged(context, k, "logged-detail", f, ma, week, Instant.parse("2026-10-18T05:03:00Z"));
                delivered(k, "nudge", f, ma, null, "nudge", "released", nudge(context, ma), Instant.parse("2026-10-20T03:00:00Z"));
                papers.add(routine);
                papers.add(week);
            }
            case "now", "now-abroad" -> {
                UUID now = nowPaper(context, k, ma);
                delivered(k, "now-explanation", f, ma, now, "explanation", "approved", ComposedBodies.explanation(context, now),
                        Instant.parse("2026-10-20T04:30:00Z"));
                papers.add(now);
                if ("now-abroad".equals(shape)) {
                    String number = uk();
                    UUID sibling = family(k, "sibling", number, "Sister");
                    FixedPapers.share(POSTGRES, ma, number);
                    session = FixedPapers.session(context, sibling, NOW);
                }
            }
            case "retake" -> {
                UUID retake = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "retake"), ma, "lab_report", "NEEDS_RETAKE", "2026-10-19",
                        Instant.parse("2026-10-19T04:00:00Z"), null);
                delivered(k, "retake-notice", f, ma, retake, "retake", "released", ComposedBodies.retake(context),
                        Instant.parse("2026-10-19T04:01:00Z"));
                papers.add(retake);
            }
            case "failed" -> {
                UUID failed = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "failed"), ma, "lab_report", "FAILED", null,
                        Instant.parse("2026-10-19T04:00:00Z"), null);
                delivered(k, "failure-notice", f, ma, failed, "failure", "released", ComposedBodies.failure(context),
                        Instant.parse("2026-10-19T04:05:00Z"));
                papers.add(failed);
            }
            case "first-run" -> {
                // nothing yet: the patient was just added
            }
            case "share-member" -> {
                UUID paper = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper"), ma, "lab_report", "DONE", "2026-10-12",
                        Instant.parse("2026-10-12T04:00:00Z"), "routine");
                delivered(k, "owners-explanation", f, ma, paper, "explanation", "released", ComposedBodies.explanation(context, paper),
                        Instant.parse("2026-10-12T04:02:00Z"));
                delivered(k, "owners-detail", f, ma, paper, "detail", "released", ComposedBodies.detail(context, paper),
                        Instant.parse("2026-10-12T04:05:00Z"));
                String number = bd();
                UUID sibling = family(k, "sibling", number, "Sister");
                FixedPapers.share(POSTGRES, ma, number);
                session = FixedPapers.session(context, sibling, NOW);
                papers.add(paper);
            }
            case "second-page" -> {
                int cap = context.getBean(BaymaxProperties.class).getOutbound().getMaxChars();
                for (int i = 0; i < 25; i++) {
                    Instant at = Instant.parse("2026-09-01T04:00:00Z").plus(Duration.ofDays(i));
                    UUID paper = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper-" + i), ma, i == LONG_PAPER ? "prescription" : "lab_report",
                            "DONE", at.toString().substring(0, 10), at, "routine");
                    if (i == LONG_PAPER) {
                        // medicines one at a time while the detail still fits the cap: the longest body a page can hold
                        for (int m = 1; m <= 40; m++) {
                            UUID med = FixedPapers.id(k, "medicine-" + m);
                            medicine(med, ma, paper, "Tab. Demoxin-" + m + " 500 mg", "১+০+১", "খাবারের পরে", "৭ দিন", at);
                            if (ComposedBodies.detail(context, paper).body().length() > cap) {
                                FixedPapers.exec(POSTGRES, "DELETE FROM baymax.medication_event WHERE id = ?", med);
                                break;
                            }
                        }
                    }
                    delivered(k, "explanation-" + i, f, ma, paper, "explanation", "released", ComposedBodies.explanation(context, paper),
                            at.plusSeconds(120));
                    delivered(k, "detail-" + i, f, ma, paper, "detail", "released", ComposedBodies.detail(context, paper), at.plusSeconds(180));
                    papers.add(paper);
                }
            }
            case "mixed" -> {
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "routine"), ma, "lab_report", "DONE", "2026-10-10",
                        Instant.parse("2026-10-10T04:00:00Z"), "routine"));
                delivered(k, "routine-explanation", f, ma, papers.get(0), "explanation", "released",
                        ComposedBodies.explanation(context, papers.get(0)), Instant.parse("2026-10-10T04:02:00Z"));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "this-week"), ma, "lab_report", "DONE", "2026-10-11",
                        Instant.parse("2026-10-11T04:00:00Z"), "this_week"));
                pending(context, k, "week-explanation", f, ma, papers.get(1), Instant.parse("2026-10-11T04:01:00Z"));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "now"), ma, "prescription", "DONE", "2026-10-12",
                        Instant.parse("2026-10-12T04:00:00Z"), "now"));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "retake"), ma, "lab_report", "NEEDS_RETAKE", "2026-10-13",
                        Instant.parse("2026-10-13T04:00:00Z"), null));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "failed"), ma, "lab_report", "FAILED", null,
                        Instant.parse("2026-10-14T04:00:00Z"), null));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "processing"), ma, null, "PROCESSING", null,
                        Instant.parse("2026-10-21T05:59:00Z"), null));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "received"), ma, null, "RECEIVED", null,
                        Instant.parse("2026-10-21T05:59:30Z"), null));
                // review of #135, finding 4: a value whose crop has a stored size (V24), so the shared builder's sizes are
                // compared between the card and the paper GET, not only a null against a null
                UUID sized = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "sized"), ma, "lab_report", "DONE", "2026-10-15",
                        Instant.parse("2026-10-15T04:00:00Z"), "routine");
                UUID haemoglobin = FixedPapers.id(k, "haemoglobin");
                String crop = ma.familyId() + "/" + ma.id() + "/" + sized + "/crop-" + haemoglobin + ".jpg";
                FixedPapers.exec(POSTGRES, "INSERT INTO baymax.observation (id, patient_id, document_id, name, value, unit, ref_low, ref_high,"
                        + " crop_key, observed_at, verdict, verdict_level, verdict_reason) VALUES (?, ?, ?, 'Haemoglobin', '13.1', 'g/dL',"
                        + " '12.0', '15.5', ?, ?, 'inside', 'routine', 'value_inside:haemoglobin|stopgap')",
                        haemoglobin, ma.id(), sized, crop, Timestamp.from(Instant.parse("2026-10-15T04:00:00Z")));
                FixedPapers.exec(POSTGRES, "INSERT INTO baymax.stored_object (family_id, patient_id, document_id, kind, item_id, storage_key,"
                        + " content_type, size_bytes, width_px, height_px, dimensions_checked_at) VALUES (?, ?, ?, 'crop', ?, ?, 'image/jpeg',"
                        + " 2048, 140, 32, ?)", ma.familyId(), ma.id(), sized, haemoglobin.toString(), crop,
                        Timestamp.from(Instant.parse("2026-10-15T04:01:00Z")));
                papers.add(sized);
            }
            default -> throw new IllegalArgumentException("no thread case " + shape);
        }
        return new Seeded(ma, session, papers);
    }

    static EntityExchangeResult<byte[]> get(WebTestClient client, String session, String uri) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        if (session != null) {
            spec = spec.cookie("baymax_session", session);
        }
        return spec.exchange().expectBody().returnResult();
    }

    static JsonNode ok(WebTestClient client, String session, String uri) throws IOException {
        EntityExchangeResult<byte[]> r = get(client, session, uri);
        assertThat(r.getStatus().value()).as("GET " + uri).isEqualTo(200);
        return JSON.readTree(r.getResponseBody());
    }

    /** The answer the case's fixture holds: the first page, or for {@code second-page} the page after it. */
    static EntityExchangeResult<byte[]> answer(WebTestClient client, Seeded s, String name) throws IOException {
        String uri = s.uri();
        if ("second-page".equals(name)) {
            JsonNode first = ok(client, s.session(), uri);
            assertThat(first.get("next_cursor").isTextual()).as("the first page of 50 items has a next page").isTrue();
            uri += "?cursor=" + first.get("next_cursor").asText();
        }
        return get(client, s.session(), uri);
    }

    static List<JsonNode> itemsOf(JsonNode body, String kind) {
        List<JsonNode> out = new ArrayList<>();
        body.get("items").forEach(i -> {
            if (kind.equals(i.get("kind").asText())) {
                out.add(i);
            }
        });
        return out;
    }

    /** The paper item whose card is this paper, or a failure naming it. */
    static JsonNode cardOf(JsonNode body, UUID paper) {
        return itemsOf(body, "paper").stream().map(i -> i.get("card")).filter(c -> paper.toString().equals(c.get("id").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("no card for paper " + paper));
    }

    // ---- the fixtures ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("cases")
    void eachCaseIsItsCommittedMswFixture(String name) {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "fixture-thread-" + name, name);
            AttentionApiContractTest.write(ThreadApiContractTest.class, COMMITTED, WRITTEN, "thread", name,
                    "GET /app/api/patients/{id}/thread over the case " + name, answer(client, s, name));
        });
    }

    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        assertThat(AttentionApiContractTest.committed(COMMITTED))
                .containsExactlyInAnyOrderElementsOf(CASES.stream().map(n -> n + ".json").toList());
    }

    /**
     * Review of #135, finding 7: every row a case seeds holds the body its template renders, compared by id against a fresh
     * rendering of the same template from the stored facts, so no hand-written body can come back through the seed.
     * Walked from the database (every {@code outbound_message} row of the case's patient), not from a list kept here.
     */
    @Test
    void everySeededBodyIsItsRenderedTemplate() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            int walked = 0;
            List<String> differ = new ArrayList<>();
            for (String name : CASES) {
                Seeded s = seed(context, "rendered-" + name, name);
                for (List<String> row : FixedPapers.rows(POSTGRES, "SELECT id, kind, document_id, body FROM baymax.outbound_message"
                        + " WHERE patient_id = '" + s.patient().id() + "' ORDER BY id")) {
                    walked++;
                    UUID document = row.get(2) == null ? null : UUID.fromString(row.get(2));
                    Composed expected = switch (row.get(1)) {
                        case "explanation" -> ComposedBodies.explanation(context, document);
                        case "detail" -> ComposedBodies.detail(context, document);
                        case "retake" -> ComposedBodies.retake(context);
                        case "failure" -> ComposedBodies.failure(context);
                        case "nudge" -> nudge(context, s.patient());
                        default -> throw new AssertionError("a seeded kind this test cannot render: " + row.get(1));
                    };
                    if (!expected.body().equals(row.get(3))) {
                        differ.add(name + " " + row.get(1) + " " + row.get(0));
                    }
                }
            }
            assertThat(walked).as("seeded rows walked across the cases").isGreaterThanOrEqualTo(10);
            assertThat(differ).as("seeded rows whose body is not their template's rendering").isEmpty();
        });
    }

    /**
     * Review of #135, finding 7: what the committed fixtures hold is what W5's legibility and longest-text checks need to
     * see. Read from the committed files by field: every message body is Bangla and multi-line; the NOW card's wording
     * carries the NOW instruction and the red-flag sentence word for word; a nudge ends with its opt-out line, an
     * unbroken URL; and the second page holds a body at the outbound cap with a medicine block and a link.
     */
    @Test
    void theFixturesCarryProductionShapedBodies() throws IOException {
        Copy copy = new Copy();
        int cap = new BaymaxProperties().getOutbound().getMaxChars();
        Pattern bangla = Pattern.compile("[\\u0980-\\u09FF]");
        int bodies = 0;
        for (String name : CASES) {
            JsonNode body = JSON.readTree(Files.readString(COMMITTED.resolve(name + ".json"))).get("body");
            for (JsonNode m : itemsOf(body, "message")) {
                bodies++;
                String text = m.get("body").asText();
                assertThat(bangla.matcher(text).find()).as(name + " body is Bangla: " + m.get("message_kind")).isTrue();
                assertThat(text).as(name + " body has more than one line: " + m.get("message_kind")).contains("\n");
            }
        }
        assertThat(bodies).as("message bodies in the committed fixtures").isGreaterThanOrEqualTo(10);

        JsonNode now = cardOf0(JSON.readTree(Files.readString(COMMITTED.resolve("now.json"))).get("body"));
        assertThat(now.get("wording").asText()).as("now.json card.wording")
                .contains(copy.bn("urgency.now", Map.of())).contains(copy.bn("safety.red_flag_line", Map.of()));

        JsonNode nudge = itemsOf(JSON.readTree(Files.readString(COMMITTED.resolve("default.json"))).get("body"), "message").stream()
                .filter(m -> "nudge".equals(m.get("message_kind").asText())).findFirst().orElseThrow();
        assertThat(nudge.get("body").asText()).as("default.json nudge body").contains("/app/nudges/opt-out?p=").contains("&t=" + OPTOUT_TOKEN);

        List<String> second = itemsOf(JSON.readTree(Files.readString(COMMITTED.resolve("second-page.json"))).get("body"), "message")
                .stream().map(m -> m.get("body").asText()).toList();
        String longest = second.stream().max(java.util.Comparator.comparingInt(String::length)).orElseThrow();
        assertThat(longest.length()).as("second-page's longest body, against the " + cap + "-char cap").isBetween(cap - 60, cap);
        assertThat(longest).contains(copy.bn("medicines.header", Map.of())).contains("https://");
    }

    private static JsonNode cardOf0(JsonNode body) {
        return itemsOf(body, "paper").get(0).get("card");
    }

    // ---- the contract ---------------------------------------------------------------------------------------------

    @Test
    void theDefaultThreadListsWhatWasDeliveredNewestFirst() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "order";
            Seeded s = seed(context, k, "default");
            JsonNode body = ok(client, s.session(), s.uri());
            List<String> shape = new ArrayList<>();
            body.get("items").forEach(i -> shape.add(i.get("kind").asText() + ":" + ("paper".equals(i.get("kind").asText())
                    ? i.get("card").get("id").asText() : i.get("message_kind").asText())));
            assertThat(shape).as("$.items[*] kind and id or message_kind").containsExactly(
                    "message:nudge", "paper:" + FixedPapers.id(k, "this-week"), "message:detail", "paper:" + FixedPapers.id(k, "routine"));
            assertThat(body.get("next_cursor").isNull()).isTrue();
            JsonNode nudge = body.get("items").get(0);
            assertThat(nudge.get("document_id").isNull()).as("a nudge names no paper").isTrue();
            assertThat(nudge.get("body").asText()).isEqualTo(nudge(context, s.patient()).body());
            assertThat(nudge.get("at").asText()).isEqualTo("2026-10-20T03:00:00Z");
            JsonNode detail = body.get("items").get(2);
            assertThat(detail.get("document_id").asText()).isEqualTo(FixedPapers.id(k, "routine").toString());
            assertThat(detail.get("body").asText()).isEqualTo(ComposedBodies.detail(context, FixedPapers.id(k, "routine")).body());
            assertThat(fieldNames(detail)).containsExactly("kind", "at", "message_kind", "document_id", "body");
            JsonNode paper = body.get("items").get(3);
            assertThat(fieldNames(paper)).containsExactly("kind", "at", "card");
            assertThat(paper.get("at").asText()).isEqualTo(paper.get("card").get("received_at").asText());
            assertThat(body.get("items").get(1).get("card").get("gate").asText()).as("the pending explanation's paper says so")
                    .isEqualTo("PENDING");
        });
    }

    /**
     * Review of #135, finding 6, first direction: a delivered explanation is shown once, inside its paper's card as
     * {@code wording}, never again as a bubble after it. Counted by field over every {@code body} and {@code wording}.
     */
    @Test
    void aDeliveredExplanationIsShownOnceInsideItsCard() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "shown-once";
            Seeded s = seed(context, k, "now");
            String explanation = ComposedBodies.explanation(context, s.papers().get(0)).body();
            JsonNode body = ok(client, s.session(), s.uri());
            List<String> where = new ArrayList<>();
            Json.walk(body, (path, node) -> {
                String field = Json.fieldName(path);
                if (("body".equals(field) || "wording".equals(field)) && node.isTextual() && node.asText().equals(explanation)) {
                    where.add(path);
                }
            });
            assertThat(where).as("where the delivered explanation's text appears in the thread JSON").hasSize(1);
            assertThat(where.get(0)).as("and that one place").endsWith(".card.wording");
            assertThat(itemsOf(body, "message")).as("no explanation bubble after the card").isEmpty();
        });
    }

    /**
     * Review of #135, finding 6, the other direction: only the one explanation the card carries is left out. An older
     * delivered explanation of the same paper (a re-sent one) is still listed, by id order of time.
     */
    @Test
    void anOlderExplanationOfTheSamePaperIsStillListed() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "older-explanation";
            UUID f = family(k, "family", bd(), "Rahim");
            PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "ma"), f, "Ma");
            UUID paper = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper"), ma, "lab_report", "DONE", "2026-10-12",
                    Instant.parse("2026-10-12T04:00:00Z"), "routine");
            Composed text = ComposedBodies.explanation(context, paper);
            delivered(k, "older", f, ma, paper, "explanation", "released", text, Instant.parse("2026-10-12T04:02:00Z"));
            delivered(k, "newer", f, ma, paper, "explanation", "released", text, Instant.parse("2026-10-13T04:02:00Z"));
            JsonNode body = ok(client, FixedPapers.session(context, f, NOW), "/app/api/patients/" + ma.id() + "/thread");
            List<JsonNode> messages = itemsOf(body, "message");
            assertThat(messages).as("the older explanation, listed").hasSize(1);
            assertThat(messages.get(0).get("at").asText()).as("the older one's sent_at").isEqualTo("2026-10-12T04:02:00Z");
            assertThat(messages.get(0).get("message_kind").asText()).isEqualTo("explanation");
            assertThat(cardOf(body, paper).get("wording").asText()).as("the card carries the newer one").isEqualTo(text.body());
        });
    }

    @Test
    void noDeliveryOrGateFieldReachesTheBrowser() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            List<String> offenders = new ArrayList<>();
            int messages = 0;
            for (String name : CASES) {
                Seeded s = seed(context, "walk-" + name, name);
                JsonNode body = JSON.readTree(answer(client, s, name).getResponseBody());
                messages += itemsOf(body, "message").size();
                Json.walk(body, (path, node) -> {
                    String field = Json.fieldName(path);
                    if (field != null && NEVER_SENT.contains(field)) {
                        offenders.add(name + " " + path);
                    }
                    if (node.isTextual() && node.asText().startsWith("wamid.")) {
                        offenders.add(name + " " + path + " carries a wamid");
                    }
                });
            }
            assertThat(messages).as("messages walked (the walk saw delivered rows)").isGreaterThanOrEqualTo(5);
            assertThat(offenders).isEmpty();
        });
    }

    @Test
    void aPaperCardIsThePaperJsonsOwnBody() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "card-equals-paper", "mixed");
            List<JsonNode> cards = itemsOf(ok(client, s.session(), s.uri()), "paper");
            assertThat(cards).as("one card per seeded paper").hasSize(s.papers().size());
            Set<String> statuses = new LinkedHashSet<>();
            int sized = 0;
            for (JsonNode item : cards) {
                JsonNode card = item.get("card");
                String id = card.get("id").asText();
                statuses.add(card.get("status").asText() + (card.has("gate") ? "/" + card.get("gate").asText() : ""));
                JsonNode paper = ok(client, s.session(), "/app/api/documents/" + id);
                List<String> differ = new ArrayList<>();
                Set<String> fields = new LinkedHashSet<>(fieldNames(paper));
                fields.addAll(fieldNames(card));
                for (String field : fields) {
                    if (!java.util.Objects.equals(paper.get(field), card.get(field))) {
                        differ.add(field);
                    }
                }
                assertThat(differ).as("fields of card " + id + " that differ from GET /app/api/documents/" + id).isEmpty();
                assertThat(card).as("card " + id + ", whole").isEqualTo(paper);
                for (int v = 0; card.has("values") && v < card.get("values").size(); v++) {
                    JsonNode crop = card.get("values").get(v).get("crop");
                    if (!crop.get("width").isNull()) {
                        sized++;
                        // by field (review of #135, finding 4): the stored size, the same on the card as on the paper GET
                        assertThat(crop.get("width").asInt()).as("card " + id + " values[" + v + "].crop.width").isEqualTo(140);
                        assertThat(crop.get("height").asInt()).as("card " + id + " values[" + v + "].crop.height").isEqualTo(32);
                        assertThat(paper.get("values").get(v).get("crop")).as("the paper GET's crop").isEqualTo(crop);
                    }
                }
            }
            assertThat(sized).as("values with a stored crop size compared (the sizes path ran)").isEqualTo(1);
            assertThat(statuses).as("the statuses the cards covered")
                    .contains("DONE/RELEASED", "DONE/PENDING", "DONE/NONE", "NEEDS_RETAKE/NONE", "FAILED/NONE", "PROCESSING", "RECEIVED");
        });
    }

    /**
     * Review of #135, finding 2: the NOW card is drawn from the state production writes. The urgency writer stores
     * {@code value_critical} for the critical potassium, so the card's {@code reasons} name it and the value sits in the
     * NOW block, as {@code documents/now-bd.json} shows, never a NOW banner whose block lists no value.
     */
    @Test
    void aNowCardListsItsCriticalValueInTheNowBlock() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            for (String shape : List.of("now", "now-abroad")) {
                Seeded s = seed(context, "now-block-" + shape, shape);
                JsonNode card = cardOf(ok(client, s.session(), s.uri()), s.papers().get(0));
                assertThat(card.get("urgency").asText()).as(shape + " card.urgency").isEqualTo("now");
                List<String> reasons = new ArrayList<>();
                card.get("reasons").forEach(r -> reasons.add(r.asText()));
                assertThat(reasons).as(shape + " card.reasons").contains("value_critical").doesNotContain("other");
                JsonNode potassium = null;
                for (JsonNode v : card.get("values")) {
                    if ("Potassium".equals(v.get("name").asText())) {
                        potassium = v;
                    }
                }
                assertThat(potassium).as(shape + " the Potassium value on the card").isNotNull();
                assertThat(potassium.get("display_kind").asText()).as(shape + " Potassium display_kind").isEqualTo("critical");
                assertThat(potassium.get("in_now_block").asBoolean()).as(shape + " Potassium in_now_block").isTrue();
            }
        });
    }

    /**
     * Review of #135, findings 3 and 8: S4 withholds the owner's message ROWS from a share member, not the explanation's
     * text. The member's card carries the explanation's wording, as the paper page shows it today; asserted by field so the
     * behaviour is explicit and reviewable (the PO is asked whether S4 meant the text too). The owner's own read lists the
     * detail the member does not see.
     */
    @Test
    void aShareMembersCardStillCarriesTheExplanationsWordingButNoMessage() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "member-wording";
            Seeded s = seed(context, k, "share-member");
            UUID paper = s.papers().get(0);
            JsonNode member = ok(client, s.session(), s.uri());
            assertThat(itemsOf(member, "message")).as("message items a share member sees").isEmpty();
            JsonNode card = cardOf(member, paper);
            assertThat(card.get("owner").asBoolean()).as("the member's card: not the owner").isFalse();
            assertThat(card.has("wording")).as("the member's card has wording").isTrue();
            assertThat(card.get("wording").asText()).as("and it is the owner's explanation, as the paper page shows it")
                    .isEqualTo(ComposedBodies.explanation(context, paper).body());

            JsonNode owner = ok(client, FixedPapers.session(context, FixedPapers.id(k, "family"), NOW), s.uri());
            assertThat(itemsOf(owner, "message").stream().map(m -> m.get("message_kind").asText()).toList())
                    .as("the owner's read: the detail (the explanation is on the card)").containsExactly("detail");
        });
    }

    /**
     * Review of #135, finding 10, both directions: a retake the family already sent again carries
     * {@code retake.replaced: true}, so its card asks for nothing more; one still waiting carries false; and one whose
     * replacement FAILED is still owed (false), as the attention stack counts it ({@code DocumentSql.NOT_REPLACED}).
     */
    @Test
    void aRetakeAlreadySentAgainIsMarkedReplaced() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "replaced";
            UUID f = family(k, "family", bd(), "Rahim");
            PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "ma"), f, "Ma");
            UUID retaken = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "retaken"), ma, "lab_report", "NEEDS_RETAKE", "2026-10-10",
                    Instant.parse("2026-10-10T04:00:00Z"), null);
            UUID waiting = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "waiting"), ma, "lab_report", "NEEDS_RETAKE", "2026-10-11",
                    Instant.parse("2026-10-11T04:00:00Z"), null);
            UUID failedAgain = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "failed-again"), ma, "lab_report", "NEEDS_RETAKE", "2026-10-12",
                    Instant.parse("2026-10-12T04:00:00Z"), null);
            UUID resend = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "resend"), ma, "lab_report", "DONE", "2026-10-13",
                    Instant.parse("2026-10-13T04:00:00Z"), "routine");
            UUID resendFailed = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "resend-failed"), ma, "lab_report", "FAILED", null,
                    Instant.parse("2026-10-14T04:00:00Z"), null);
            FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET replaces_document_id = ? WHERE id = ?", retaken, resend);
            FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET replaces_document_id = ? WHERE id = ?", failedAgain, resendFailed);

            JsonNode body = ok(client, FixedPapers.session(context, f, NOW), "/app/api/patients/" + ma.id() + "/thread");
            assertThat(cardOf(body, retaken).get("retake").get("replaced").asBoolean()).as("$.card.retake.replaced, sent again").isTrue();
            assertThat(cardOf(body, waiting).get("retake").get("replaced").asBoolean()).as("$.card.retake.replaced, waiting").isFalse();
            assertThat(cardOf(body, failedAgain).get("retake").get("replaced").asBoolean())
                    .as("$.card.retake.replaced, its replacement FAILED: still owed").isFalse();
            assertThat(cardOf(body, resend).has("retake")).as("a DONE paper has no retake block").isFalse();
        });
    }

    /** The kinds {@code outbound_message_kind_check} allows, parsed from the constraint as Postgres reports it. */
    static Set<String> allowedKinds() throws Exception {
        List<List<String>> def = FixedPapers.rows(POSTGRES, "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                + " JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'baymax' AND c.conname = 'outbound_message_kind_check'");
        assertThat(def).as("outbound_message_kind_check exists").hasSize(1);
        Set<String> kinds = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([a-z_]+)'").matcher(def.get(0).get(0));
        while (m.find()) {
            kinds.add(m.group(1));
        }
        assertThat(kinds).as("the kinds parsed from " + def.get(0).get(0)).isNotEmpty();
        return kinds;
    }

    @Test
    void everyOutboundKindHasAMessageKind() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "every-kind";
            UUID f = family(k, "family", bd(), "Rahim");
            PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "ma"), f, "Ma");
            UUID paper = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper"), ma, "lab_report", "DONE", "2026-10-10",
                    Instant.parse("2026-10-10T04:00:00Z"), "routine");
            Map<String, String> expected = new LinkedHashMap<>();
            int i = 0;
            for (String kind : allowedKinds()) {
                Composed body = switch (kind) {
                    case "explanation" -> ComposedBodies.explanation(context, paper);
                    case "detail" -> ComposedBodies.detail(context, paper);
                    case "retake" -> ComposedBodies.retake(context);
                    case "failure" -> ComposedBodies.failure(context);
                    case "nudge" -> nudge(context, ma);
                    default -> throw new AssertionError("a kind the database allows and no composer renders here: " + kind);
                };
                delivered(k, kind, f, ma, paper, kind, "released", body, Instant.parse("2026-10-10T05:00:00Z").plusSeconds(60L * i++));
                expected.put(kind, body.body());
            }
            // the paper's newest explanation is the card's wording (finding 6), so the explanation listed above is an older one
            delivered(k, "newest-explanation", f, ma, paper, "explanation", "released", ComposedBodies.explanation(context, paper),
                    Instant.parse("2026-10-10T06:00:00Z"));
            JsonNode thread = ok(client, FixedPapers.session(context, f, NOW), "/app/api/patients/" + ma.id() + "/thread");
            Map<String, String> listed = new LinkedHashMap<>();
            itemsOf(thread, "message").forEach(m -> assertThat(listed.put(m.get("message_kind").asText(), m.get("body").asText()))
                    .as("one listed message per kind: " + m.get("message_kind")).isNull());
            assertThat(listed).as("each allowed kind's delivered message, by message_kind, with its body").isEqualTo(reversed(expected));
        });
    }

    private static Map<String, String> reversed(Map<String, String> m) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(m.entrySet());
        java.util.Collections.reverse(entries);
        Map<String, String> out = new LinkedHashMap<>();
        entries.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Test
    void aThreadCardForAViewerAbroadOffersNo999() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded abroad = seed(context, "abroad-999", "now-abroad");
            List<JsonNode> cards = itemsOf(ok(client, abroad.session(), abroad.uri()), "paper");
            List<JsonNode> now = cards.stream().map(c -> c.get("card")).filter(c -> c.has("now")).toList();
            assertThat(now).as("NOW cards in the abroad thread").isNotEmpty();
            for (JsonNode card : now) {
                assertThat(card.get("now").get("call_999").asBoolean()).as("$.items[].card.now.call_999 of " + card.get("id")).isFalse();
                assertThat(card.get("viewer").get("bangladesh").asBoolean()).isFalse();
            }
            Seeded home = seed(context, "home-999", "now");
            JsonNode homeCard = itemsOf(ok(client, home.session(), home.uri()), "paper").get(0).get("card");
            assertThat(homeCard.get("now").get("call_999").asBoolean()).as("the other direction: a Bangladeshi phone").isTrue();
        });
    }

    @Test
    void aPatientOfAnotherFamilyIsNotFound() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded theirs = seed(context, "not-found-theirs", "default");
            Seeded mine = seed(context, "not-found-mine", "first-run");
            EntityExchangeResult<byte[]> other = get(client, mine.session(), theirs.uri());
            assertThat(other.getStatus().value()).as("another family's patient").isEqualTo(404);
            assertThat(JSON.readTree(other.getResponseBody())).isEqualTo(JSON.readTree("{\"reason\":\"NOT_FOUND\"}"));
            for (String bad : List.of("/app/api/patients/not-a-uuid/thread", mine.uri() + "?cursor=v1.not-a-cursor", mine.uri() + "?cursor=Ma")) {
                EntityExchangeResult<byte[]> r = get(client, mine.session(), bad);
                assertThat(r.getStatus().value()).as(bad).isEqualTo(400);
                assertThat(JSON.readTree(r.getResponseBody())).isEqualTo(JSON.readTree("{\"reason\":\"INVALID\"}"));
            }
            assertThat(get(client, null, mine.uri()).getStatus().value()).as("no session").isEqualTo(401);
        });
    }

    @Test
    void theCursorPagesTheThread() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "pages", "second-page");
            Set<String> seen = new LinkedHashSet<>();
            List<Integer> sizes = new ArrayList<>();
            String uri = s.uri();
            for (int guard = 0; guard < 5 && uri != null; guard++) {
                JsonNode page = ok(client, s.session(), uri);
                sizes.add(page.get("items").size());
                page.get("items").forEach(i -> seen.add(i.get("kind").asText() + ":" + i.get("at").asText()));
                uri = page.get("next_cursor").isNull() ? null : s.uri() + "?cursor=" + page.get("next_cursor").asText();
            }
            assertThat(sizes).as("pages of 50 items (25 cards, 25 details; each explanation is on its card)").containsExactly(20, 20, 10);
            assertThat(seen).as("every item once").hasSize(50);
        });
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
