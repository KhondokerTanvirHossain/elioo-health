package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
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
 * there or about none (S10), its body as sent, its kind as stored. No delivery or gate field reaches the browser, walked
 * by field. Every kind the database allows is read from {@code pg_constraint}, never from a list written here. The eight
 * cases React's MSW handlers serve are written with fixed ids and compared byte for byte with the committed
 * {@code medioo-web/fixtures/api/thread/*.json}. Fictional throughout: +8801700000xxx and +447700900xxx numbers, English
 * names, and bodies no family received.
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

    /**
     * A message the provider accepted for {@code family} at {@code sentAt}: delivery_status 'sent' with its wamid (V14),
     * composed a minute before. {@code document} null is a message about no paper (a nudge).
     */
    static UUID delivered(String k, String role, UUID family, PatientProfile p, UUID document, String kind, String gate, String body,
                          Instant sentAt) throws Exception {
        UUID id = FixedPapers.id(k, role);
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (id, family_id, patient_id, document_id, kind, urgency, urgency_reasons,"
                        + " body, gate_status, reviewer, decided_at, sent_at, delivery_status, provider_message_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'routine', '[]', ?, ?, ?, ?, ?, 'sent', ?, ?)",
                id, family, p.id(), document, kind, body, gate, "approved".equals(gate) ? "tanvir" : null,
                "approved".equals(gate) ? Timestamp.from(sentAt.minusSeconds(30)) : null, Timestamp.from(sentAt),
                "wamid." + role, Timestamp.from(sentAt.minusSeconds(60)));
        return id;
    }

    /** A message composed and never delivered: the explanation waiting for the reviewer (PENDING), sent_at NULL. */
    static void pending(String k, String role, UUID family, PatientProfile p, UUID document, Instant createdAt) throws Exception {
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (id, family_id, patient_id, document_id, kind, urgency, urgency_reasons,"
                        + " body, gate_status, created_at) VALUES (?, ?, ?, ?, 'explanation', 'this_week', '[]', ?, 'pending', ?)",
                FixedPapers.id(k, role), family, p.id(), document, "A pending explanation nobody was sent.", Timestamp.from(createdAt));
    }

    /** The log adapter's outcome (S11): sent_at stamped, no delivery status, nothing reached the family. */
    static void logged(String k, String role, UUID family, PatientProfile p, UUID document, Instant sentAt) throws Exception {
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (id, family_id, patient_id, document_id, kind, urgency, urgency_reasons,"
                        + " body, gate_status, sent_at, created_at) VALUES (?, ?, ?, ?, 'detail', 'routine', '[]', ?, 'released', ?, ?)",
                FixedPapers.id(k, role), family, p.id(), document, "A logged detail no family received.", Timestamp.from(sentAt),
                Timestamp.from(sentAt.minusSeconds(60)));
    }

    /**
     * One case's rows, every id fixed by {@code k}:
     * <ul>
     *   <li>{@code default}: a routine paper and its delivered explanation, a this-week paper whose explanation waits for the
     *       reviewer (its card says PENDING, no message is listed), a delivered nudge about no paper, and a logged detail
     *       (not listed);</li>
     *   <li>{@code now}: a NOW paper and its approved, delivered explanation, read from a Bangladeshi phone;</li>
     *   <li>{@code now-abroad}: the same, read by a share member with a UK phone: the card offers no 999, and the owner's
     *       message is not theirs;</li>
     *   <li>{@code retake}, {@code failed}: the paper and the notice the family got about it;</li>
     *   <li>{@code first-run}: a patient with nothing yet;</li>
     *   <li>{@code share-member}: a Bangladeshi share member's view of the owner's paper, without the owner's message;</li>
     *   <li>{@code second-page}: 25 papers each with a delivered explanation, read with the first page's cursor;</li>
     *   <li>{@code mixed} (not a fixture): every status a card can have, for the card-equals-paper test.</li>
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
                delivered(k, "routine-explanation", f, ma, routine, "explanation", "released",
                        "Ma's report from Shapla Diagnostics has been read. Every value printed is inside its range.",
                        Instant.parse("2026-10-12T04:02:00Z"));
                UUID week = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "this-week"), ma, "lab_report", "DONE", "2026-10-18",
                        Instant.parse("2026-10-18T05:00:00Z"), "this_week");
                pending(k, "week-explanation", f, ma, week, Instant.parse("2026-10-18T05:01:00Z"));
                logged(k, "logged-detail", f, ma, week, Instant.parse("2026-10-18T05:03:00Z"));
                delivered(k, "nudge", f, ma, null, "nudge", "released", "Ma's follow-up visit is in three days.",
                        Instant.parse("2026-10-20T03:00:00Z"));
                papers.add(routine);
                papers.add(week);
            }
            case "now", "now-abroad" -> {
                UUID now = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "now"), ma, "lab_report", "DONE", "2026-10-20",
                        Instant.parse("2026-10-20T04:00:00Z"), "now");
                AppApiContext.sql(POSTGRES, "INSERT INTO baymax.observation (id, patient_id, document_id, name, value, unit, ref_low, ref_high,"
                        + " crop_key, observed_at, verdict, verdict_level, verdict_side, verdict_reason) VALUES ('"
                        + FixedPapers.id(k, "potassium") + "','" + ma.id() + "','" + now
                        + "','Potassium','6.9','mmol/L','3.5','5.1','" + f + "/" + ma.id() + "/" + now
                        + "/crop-v1.jpg','2026-10-20T04:00:00Z','critical','now','high','value_critical:potassium|stopgap')");
                delivered(k, "now-explanation", f, ma, now, "explanation", "approved",
                        "Ma's potassium is printed above its range. Please speak to a doctor today.", Instant.parse("2026-10-20T04:30:00Z"));
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
                delivered(k, "retake-notice", f, ma, retake, "retake", "released",
                        "The photo of Ma's paper is too blurred to read. Please send it again in good light.",
                        Instant.parse("2026-10-19T04:01:00Z"));
                papers.add(retake);
            }
            case "failed" -> {
                UUID failed = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "failed"), ma, "lab_report", "FAILED", null,
                        Instant.parse("2026-10-19T04:00:00Z"), null);
                delivered(k, "failure-notice", f, ma, failed, "failure", "released",
                        "We could not finish reading Ma's paper. Nothing was lost; please send it again.",
                        Instant.parse("2026-10-19T04:05:00Z"));
                papers.add(failed);
            }
            case "first-run" -> {
                // nothing yet: the patient was just added
            }
            case "share-member" -> {
                UUID paper = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper"), ma, "lab_report", "DONE", "2026-10-12",
                        Instant.parse("2026-10-12T04:00:00Z"), "routine");
                delivered(k, "owners-explanation", f, ma, paper, "explanation", "released",
                        "Ma's report has been read. This message went to the owner's phone only.", Instant.parse("2026-10-12T04:02:00Z"));
                String number = bd();
                UUID sibling = family(k, "sibling", number, "Sister");
                FixedPapers.share(POSTGRES, ma, number);
                session = FixedPapers.session(context, sibling, NOW);
                papers.add(paper);
            }
            case "second-page" -> {
                for (int i = 0; i < 25; i++) {
                    Instant at = Instant.parse("2026-09-01T04:00:00Z").plus(Duration.ofDays(i));
                    UUID paper = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper-" + i), ma, "lab_report", "DONE",
                            at.toString().substring(0, 10), at, "routine");
                    delivered(k, "explanation-" + i, f, ma, paper, "explanation", "released",
                            "Ma's report number " + (i + 1) + " has been read. Every value printed is inside its range.", at.plusSeconds(120));
                    papers.add(paper);
                }
            }
            case "mixed" -> {
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "routine"), ma, "lab_report", "DONE", "2026-10-10",
                        Instant.parse("2026-10-10T04:00:00Z"), "routine"));
                delivered(k, "routine-explanation", f, ma, papers.get(0), "explanation", "released", "A released explanation.",
                        Instant.parse("2026-10-10T04:02:00Z"));
                papers.add(FixedPapers.paper(POSTGRES, FixedPapers.id(k, "this-week"), ma, "lab_report", "DONE", "2026-10-11",
                        Instant.parse("2026-10-11T04:00:00Z"), "this_week"));
                pending(k, "week-explanation", f, ma, papers.get(1), Instant.parse("2026-10-11T04:01:00Z"));
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
                    "message:nudge", "paper:" + FixedPapers.id(k, "this-week"), "message:explanation", "paper:" + FixedPapers.id(k, "routine"));
            assertThat(body.get("next_cursor").isNull()).isTrue();
            JsonNode nudge = body.get("items").get(0);
            assertThat(nudge.get("document_id").isNull()).as("a nudge names no paper").isTrue();
            assertThat(nudge.get("body").asText()).isEqualTo("Ma's follow-up visit is in three days.");
            assertThat(nudge.get("at").asText()).isEqualTo("2026-10-20T03:00:00Z");
            JsonNode explanation = body.get("items").get(2);
            assertThat(explanation.get("document_id").asText()).isEqualTo(FixedPapers.id(k, "routine").toString());
            assertThat(fieldNames(explanation)).containsExactly("kind", "at", "message_kind", "document_id", "body");
            JsonNode paper = body.get("items").get(3);
            assertThat(fieldNames(paper)).containsExactly("kind", "at", "card");
            assertThat(paper.get("at").asText()).isEqualTo(paper.get("card").get("received_at").asText());
            assertThat(body.get("items").get(1).get("card").get("gate").asText()).as("the pending explanation's paper says so")
                    .isEqualTo("PENDING");
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
            }
            assertThat(statuses).as("the statuses the cards covered")
                    .contains("DONE/RELEASED", "DONE/PENDING", "DONE/NONE", "NEEDS_RETAKE/NONE", "FAILED/NONE", "PROCESSING", "RECEIVED");
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
                String body = "A delivered " + kind + " message.";
                delivered(k, kind, f, ma, paper, kind, "released", body, Instant.parse("2026-10-10T05:00:00Z").plusSeconds(60L * i++));
                expected.put(body, kind);
            }
            JsonNode thread = ok(client, FixedPapers.session(context, f, NOW), "/app/api/patients/" + ma.id() + "/thread");
            Map<String, String> listed = new LinkedHashMap<>();
            itemsOf(thread, "message").forEach(m -> listed.put(m.get("body").asText(), m.get("message_kind").asText()));
            assertThat(listed).as("each allowed kind's delivered message, by body, with its message_kind").isEqualTo(expected);
        });
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
            assertThat(sizes).as("pages of 50 items").containsExactly(20, 20, 10);
            assertThat(seen).as("every item once").hasSize(50);
        });
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
