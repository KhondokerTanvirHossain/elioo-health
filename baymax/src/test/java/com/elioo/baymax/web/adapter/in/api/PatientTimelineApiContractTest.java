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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B3 Step 5: a patient's timeline by month ({@code GET /app/api/patients/{id}/timeline}), its calendar
 * ({@code …/calendar?month=YYYY-MM}) and its follow-ups ({@code …/follow-ups}), over the real router, guard, services and
 * Postgres under a fixed clock. Every display decision is Java's and arrives as a field: the month key is
 * {@code YearMonth.from(date)} of the filed day (D2), never a month React computes; {@code overdue} is against the
 * patient's Dhaka today; the chip is NOW or THIS_WEEK or nothing. Assertions read fields, never substrings. The fixtures
 * React's MSW handlers serve are written here with fixed ids and compared byte for byte with the committed
 * {@code medioo-web/fixtures/api/{timeline,calendar,follow-ups}/*.json}.
 */
@Testcontainers
class PatientTimelineApiContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 12:00 in Dhaka on 21 October 2026. */
    static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    /** 01:00 on 22 October in Dhaka, still the 21st in UTC: the instant that tells a Dhaka "today" from a UTC one. */
    static final Instant DHAKA_PAST_MIDNIGHT = Instant.parse("2026-10-21T19:00:00Z");
    static final Path FIXTURES = Path.of("../medioo-web/fixtures/api");
    static final Path WRITTEN = Path.of("build/web-contract");
    static final List<String> TIMELINE = List.of("first-page", "last-page", "empty");
    static final List<String> CALENDAR = List.of("busy", "empty");
    static final List<String> FOLLOW_UPS = List.of("some", "none");
    private static final AtomicInteger NUMBERS = new AtomicInteger(800);
    private static final ObjectMapper JSON = new ObjectMapper();

    record Seeded(PatientProfile patient, String session) {
    }

    static Seeded family(ApplicationContext context, String kase, Instant now) throws Exception {
        UUID f = FixedPapers.family(POSTGRES, FixedPapers.id(kase, "family"), "+8801700000" + NUMBERS.incrementAndGet(), "Rahim");
        PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(kase, "ma"), f, "Ma");
        return new Seeded(ma, FixedPapers.session(context, f, now));
    }

    static EntityExchangeResult<byte[]> get(WebTestClient client, String session, String uri) {
        return client.get().uri(uri).cookie("baymax_session", session).exchange().expectBody().returnResult();
    }

    static JsonNode ok(WebTestClient client, String session, String uri) throws IOException {
        EntityExchangeResult<byte[]> r = get(client, session, uri);
        assertThat(r.getStatus().value()).as("GET " + uri).isEqualTo(200);
        return JSON.readTree(r.getResponseBody());
    }

    static String timeline(PatientProfile p) {
        return "/app/api/patients/" + p.id() + "/timeline";
    }

    private static List<String> field(JsonNode entries, String name) {
        List<String> out = new ArrayList<>();
        entries.forEach(e -> out.add(e.get(name).isNull() ? null : e.get(name).asText()));
        return out;
    }

    // ---- the timeline ---------------------------------------------------------------------------------------------

    @Test
    void theMonthKeyIsTheTimelineDatesMonth() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = family(context, "month-key", NOW);
            String k = "month-key";
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct1"), s.patient(), "lab_report", "DONE", "2026-10-01",
                    Instant.parse("2026-10-02T04:00:00Z"), "routine");
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "sep30"), s.patient(), "lab_report", "DONE", "2026-09-30",
                    Instant.parse("2026-10-02T05:00:00Z"), "routine");
            // printed no date, uploaded 18:30 UTC on 30 September: 00:30 on 1 October in Dhaka
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "undated"), s.patient(), "lab_report", "DONE", null,
                    Instant.parse("2026-09-30T18:30:00Z"), "routine");

            JsonNode entries = ok(client, s.session(), timeline(s.patient())).get("entries");
            assertThat(field(entries, "document_id")).containsExactly(FixedPapers.id(k, "oct1").toString(),
                    FixedPapers.id(k, "undated").toString(), FixedPapers.id(k, "sep30").toString());
            assertThat(field(entries, "month")).as("$.entries[*].month").containsExactly("2026-10", "2026-10", "2026-09");
            assertThat(field(entries, "date_is_upload_day")).as("$.entries[*].date_is_upload_day").containsExactly("false", "true", "false");
        });
    }

    /**
     * D2 Step 1 and Review Focus 5, on the month key the client groups by: a retake uploaded just after midnight in Dhaka
     * on the first of a month is in the new month, by the Dhaka day of its upload, never under the date misread off its
     * blurred page; one uploaded a second before midnight stays in the old month; a finished paper keeps its printed date
     * across midnight; a printed date the model marked uncertain is marked, and only there.
     */
    @Test
    void monthGroupingAcrossADhakaMidnight() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "midnight";
            Seeded s = family(context, k, NOW);
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "retake-after"), s.patient(), "lab_report", "NEEDS_RETAKE", "2026-10-12",
                    Instant.parse("2026-10-31T18:30:00Z"), null);
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "retake-before"), s.patient(), "lab_report", "NEEDS_RETAKE", "2026-10-12",
                    Instant.parse("2026-10-31T17:59:59Z"), null);
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "done-sep30"), s.patient(), "lab_report", "DONE", "2026-09-30",
                    Instant.parse("2026-10-31T18:30:00Z"), "routine");
            UUID uncertain = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "done-jul11"), s.patient(), "lab_report", "DONE", "2026-07-11",
                    Instant.parse("2026-10-20T04:00:00Z"), "routine");
            FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET doc_date_uncertain = true WHERE id = ?", uncertain);

            JsonNode entries = ok(client, s.session(), timeline(s.patient())).get("entries");
            assertThat(field(entries, "document_id")).as("timeline order").containsExactly(
                    FixedPapers.id(k, "retake-after").toString(), FixedPapers.id(k, "retake-before").toString(),
                    FixedPapers.id(k, "done-sep30").toString(), uncertain.toString());
            assertThat(field(entries, "month")).as("$.entries[*].month").containsExactly("2026-11", "2026-10", "2026-09", "2026-07");
            assertThat(field(entries, "date")).as("$.entries[*].date").containsExactly("2026-11-01", "2026-10-31", "2026-09-30", "2026-07-11");
            assertThat(field(entries, "date_is_upload_day")).as("$.entries[*].date_is_upload_day").containsExactly("true", "true", "false", "false");
            assertThat(field(entries, "date_uncertain")).as("$.entries[*].date_uncertain").containsExactly("false", "false", "false", "true");

            // the other direction: a retake whose misread date the model marked uncertain is filed under its upload day,
            // which is certain, so it carries no mark
            Seeded other = family(context, k + "-2", NOW);
            UUID retake = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "uncertain-retake"), other.patient(), "lab_report",
                    "NEEDS_RETAKE", "2021-03-04", Instant.parse("2026-10-20T04:00:00Z"), null);
            FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET doc_date_uncertain = true WHERE id = ?", retake);
            JsonNode only = ok(client, other.session(), timeline(other.patient())).get("entries").get(0);
            assertThat(only.get("date_is_upload_day").asBoolean()).isTrue();
            assertThat(only.get("date_uncertain").asBoolean()).as("an upload day is never marked uncertain").isFalse();
        });
    }

    @Test
    void theTimelinePagesWithTheReadModelsCursor() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seedTwentyFive(context, "pages");
            JsonNode first = ok(client, s.session(), timeline(s.patient()));
            assertThat(first.get("entries")).hasSize(20);
            assertThat(first.get("next_cursor").isTextual()).isTrue();
            JsonNode second = ok(client, s.session(), timeline(s.patient()) + "?cursor=" + first.get("next_cursor").asText());
            assertThat(second.get("entries")).hasSize(5);
            assertThat(second.get("next_cursor").isNull()).isTrue();
            Set<String> ids = new HashSet<>(field(first.get("entries"), "document_id"));
            ids.addAll(field(second.get("entries"), "document_id"));
            assertThat(ids).as("no document_id repeats across the pages").hasSize(25);
            assertThat(get(client, s.session(), timeline(s.patient()) + "?cursor=v1.not-a-cursor").getStatus().value())
                    .as("a cursor that is not a previous page's").isEqualTo(400);
        });
    }

    @Test
    void aChipIsOnlyNowOrThisWeek() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "chips";
            Seeded s = family(context, k, NOW);
            for (String level : new String[]{"now", "this_week", "routine", null}) {
                FixedPapers.paper(POSTGRES, FixedPapers.id(k, String.valueOf(level)), s.patient(), "lab_report", "DONE", "2026-10-10",
                        NOW.minus(Duration.ofDays(level == null ? 1 : level.length())), level);
            }
            JsonNode entries = ok(client, s.session(), timeline(s.patient())).get("entries");
            for (JsonNode e : entries) {
                String id = e.get("document_id").asText();
                String expected = id.equals(FixedPapers.id(k, "now").toString()) ? "now"
                        : id.equals(FixedPapers.id(k, "this_week").toString()) ? "this_week" : null;
                assertThat(e.get("chip").isNull() ? null : e.get("chip").asText()).as("chip of " + id).isEqualTo(expected);
            }
            assertThat(entries).hasSize(4);
        });
    }

    // ---- the calendar ---------------------------------------------------------------------------------------------

    @Test
    void theCalendarHoldsTheMonthsPapersAndFollowUps() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seedBusyMonth(context, "calendar");
            JsonNode body = ok(client, s.session(), "/app/api/patients/" + s.patient().id() + "/calendar?month=2026-10");
            assertThat(body.get("month").asText()).isEqualTo("2026-10");
            assertThat(field(body.get("days"), "date")).as("only days with something, ascending")
                    .containsExactly("2026-10-01", "2026-10-15", "2026-10-31");
            assertThat(body.get("days").get(0).get("papers").get(0).get("chip").asText()).isEqualTo("now");
            assertThat(body.get("days").get(1).get("papers")).isEmpty();
            assertThat(body.get("days").get(1).get("follow_ups").get(0).get("instruction").asText()).isEqualTo("Bring the sugar chart");
            for (String bad : List.of("2026-13", "2026-1", "october", "")) {
                EntityExchangeResult<byte[]> r = get(client, s.session(), "/app/api/patients/" + s.patient().id() + "/calendar?month=" + bad);
                assertThat(r.getStatus().value()).as("month=" + bad).isEqualTo(400);
                assertThat(JSON.readTree(r.getResponseBody())).isEqualTo(JSON.readTree("{\"reason\":\"INVALID\"}"));
            }
        });
    }

    // ---- the follow-ups -------------------------------------------------------------------------------------------

    @Test
    void followUpsAreSoonestFirstWithJavasOverdue() {
        FixedPapers.runnerAt(POSTGRES, DHAKA_PAST_MIDNIGHT).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seedFollowUps(context, "overdue");
            JsonNode items = ok(client, s.session(), "/app/api/patients/" + s.patient().id() + "/follow-ups").get("items");
            assertThat(field(items, "due_date")).as("soonest first, inside [today − 30, today + 365]")
                    .containsExactly("2026-10-21", "2026-10-22", "2027-06-01");
            // a UTC today (the 21st) would call the 21st not overdue: this row is the one that tells the two apart
            assertThat(field(items, "overdue")).as("$.items[*].overdue against the Dhaka today").containsExactly("true", "false", "false");
        });
    }

    // ---- no developer data anywhere -------------------------------------------------------------------------------

    @Test
    void noConfidenceModelOrDeveloperProseAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        int walked = 0;
        for (String area : List.of("timeline", "calendar", "follow-ups", "attention", "now-banner")) {
            Path dir = FIXTURES.resolve(area);
            for (String file : AttentionApiContractTest.committed(dir)) {
                walked++;
                JsonNode tree = JSON.readTree(Files.readString(dir.resolve(file), StandardCharsets.UTF_8));
                Json.walk(tree.get("body"), (path, node) -> {
                    String name = Json.fieldName(path);
                    if ("confidence".equals(name) || "model".equals(name) || "status_reason".equals(name)) {
                        offenders.add(area + "/" + file + " " + path);
                    }
                    if (node.isTextual() && node.asText().equals(FixedPapers.DEVELOPER_PROSE)) {
                        offenders.add(area + "/" + file + " " + path + " carries the developer prose");
                    }
                });
            }
        }
        assertThat(walked).as("fixtures walked").isGreaterThanOrEqualTo(14);
        assertThat(offenders).isEmpty();
    }

    // ---- the fixtures ---------------------------------------------------------------------------------------------

    static Stream<String> timelineCases() {
        return TIMELINE.stream();
    }

    static Stream<String> calendarCases() {
        return CALENDAR.stream();
    }

    static Stream<String> followUpCases() {
        return FOLLOW_UPS.stream();
    }

    @ParameterizedTest
    @MethodSource("timelineCases")
    void eachTimelineCaseIsItsCommittedMswFixture(String name) {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "fixture-timeline-" + name;
            Seeded s = "empty".equals(name) ? family(context, k, NOW) : seedTwentyFive(context, k);
            String uri = timeline(s.patient());
            if ("last-page".equals(name)) {
                uri += "?cursor=" + ok(client, s.session(), uri).get("next_cursor").asText();
            }
            AttentionApiContractTest.write(PatientTimelineApiContractTest.class, FIXTURES.resolve("timeline"), WRITTEN.resolve("timeline"),
                    "timeline", name, "GET /app/api/patients/{id}/timeline over the case " + name, get(client, s.session(), uri));
        });
    }

    @ParameterizedTest
    @MethodSource("calendarCases")
    void eachCalendarCaseIsItsCommittedMswFixture(String name) {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "fixture-calendar-" + name;
            Seeded s = "empty".equals(name) ? family(context, k, NOW) : seedBusyMonth(context, k);
            AttentionApiContractTest.write(PatientTimelineApiContractTest.class, FIXTURES.resolve("calendar"), WRITTEN.resolve("calendar"),
                    "calendar", name, "GET /app/api/patients/{id}/calendar?month=2026-10 over the case " + name,
                    get(client, s.session(), "/app/api/patients/" + s.patient().id() + "/calendar?month=2026-10"));
        });
    }

    @ParameterizedTest
    @MethodSource("followUpCases")
    void eachFollowUpsCaseIsItsCommittedMswFixture(String name) {
        FixedPapers.runnerAt(POSTGRES, DHAKA_PAST_MIDNIGHT).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "fixture-follow-ups-" + name;
            Seeded s = "none".equals(name) ? family(context, k, DHAKA_PAST_MIDNIGHT) : seedFollowUps(context, k);
            AttentionApiContractTest.write(PatientTimelineApiContractTest.class, FIXTURES.resolve("follow-ups"), WRITTEN.resolve("follow-ups"),
                    "follow-ups", name, "GET /app/api/patients/{id}/follow-ups over the case " + name,
                    get(client, s.session(), "/app/api/patients/" + s.patient().id() + "/follow-ups"));
        });
    }

    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        assertThat(AttentionApiContractTest.committed(FIXTURES.resolve("timeline")))
                .containsExactlyInAnyOrderElementsOf(TIMELINE.stream().map(n -> n + ".json").toList());
        assertThat(AttentionApiContractTest.committed(FIXTURES.resolve("calendar")))
                .containsExactlyInAnyOrderElementsOf(CALENDAR.stream().map(n -> n + ".json").toList());
        assertThat(AttentionApiContractTest.committed(FIXTURES.resolve("follow-ups")))
                .containsExactlyInAnyOrderElementsOf(FOLLOW_UPS.stream().map(n -> n + ".json").toList());
    }

    // ---- seeds ----------------------------------------------------------------------------------------------------

    /**
     * Twenty-five papers, one a day back from 1 October: the newest NOW, the next THIS_WEEK, the fourth a retake (filed
     * under its upload day), the sixth a printed date the model marked uncertain, the rest routine; every fifth a
     * prescription with a medicine and a follow-up, so the counts read something.
     */
    static Seeded seedTwentyFive(ApplicationContext context, String k) throws Exception {
        Seeded s = family(context, k, NOW);
        for (int i = 0; i < 25; i++) {
            LocalDate printed = LocalDate.parse("2026-10-01").minusDays(i);
            Instant uploaded = printed.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).plus(Duration.ofHours(4));
            String status = i == 3 ? "NEEDS_RETAKE" : "DONE";
            String level = i == 3 ? null : i == 0 ? "now" : i == 1 ? "this_week" : "routine";
            String type = i % 5 == 4 ? "prescription" : "lab_report";
            UUID id = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper-" + i), s.patient(), type, status, printed.toString(), uploaded, level);
            if (i == 5) {
                FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET doc_date_uncertain = true WHERE id = ?", id);
            }
            if ("prescription".equals(type)) {
                FixedPapers.exec(POSTGRES, "INSERT INTO baymax.medication_event (patient_id, document_id, name, crop_key, at) "
                        + "VALUES (?, ?, 'Zorvalex 10 mg', ?, ?)", s.patient().id(), id, s.patient().familyId() + "/" + s.patient().id()
                        + "/" + id + "/crop-m1.jpg", java.sql.Timestamp.from(uploaded));
                FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "follow-" + i), s.patient(), id, "Review after the course", printed.plusDays(30).toString());
            }
        }
        return s;
    }

    /** October 2026: a NOW lab report on the 1st, a prescription on the 31st with a follow-up due on the 15th, a paper on 1 November. */
    static Seeded seedBusyMonth(ApplicationContext context, String k) throws Exception {
        Seeded s = family(context, k, NOW);
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct1"), s.patient(), "lab_report", "DONE", "2026-10-01",
                Instant.parse("2026-10-01T04:00:00Z"), "now");
        UUID rx = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct31"), s.patient(), "prescription", "DONE", "2026-10-31",
                Instant.parse("2026-10-20T04:00:00Z"), "routine");
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "nov1"), s.patient(), "lab_report", "DONE", "2026-11-01",
                Instant.parse("2026-10-20T05:00:00Z"), "routine");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "follow"), s.patient(), rx, "Bring the sugar chart", "2026-10-15");
        return s;
    }

    /**
     * Follow-ups around 22 October in Dhaka: due the 21st (overdue), the 22nd (today), next June; and three that are not
     * listed: one 41 days past (outside the 30-day look-back), one more than a year out, one already done.
     */
    static Seeded seedFollowUps(ApplicationContext context, String k) throws Exception {
        Seeded s = family(context, k, DHAKA_PAST_MIDNIGHT);
        UUID rx = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "rx"), s.patient(), "prescription", "DONE", "2026-10-01",
                Instant.parse("2026-10-01T04:00:00Z"), "routine");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "jun"), s.patient(), rx, "Eye check in June", "2027-06-01");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "22"), s.patient(), rx, "Fasting sugar test", "2026-10-22");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "21"), s.patient(), rx, "Come back with the CBC report", "2026-10-21");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "past"), s.patient(), rx, "Old visit", "2026-09-10");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "far"), s.patient(), rx, "Far visit", "2027-12-01");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "done"), s.patient(), rx, "Done visit", "2026-10-25");
        FixedPapers.exec(POSTGRES, "UPDATE baymax.follow_up SET status = 'done' WHERE id = ?", FixedPapers.id(k, "done"));
        return s;
    }
}
