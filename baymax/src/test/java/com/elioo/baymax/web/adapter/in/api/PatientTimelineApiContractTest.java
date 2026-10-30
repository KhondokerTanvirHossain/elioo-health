package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.Json;
import com.elioo.baymax.web.application.service.TimelineService;
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
    static final List<String> TIMELINE = List.of("first-page", "last-page", "empty", "forty-five-first-page", "lab-only", "rx-only",
            "in-flight", "in-flight-settled", "rx-none");
    static final List<String> CALENDAR = List.of("busy", "empty", "crowded", "forty-five", "september");
    /** A paper count above any one page (plan W8, master Review Focus 1): forty-five papers in one month. */
    static final int FORTY_FIVE = 45;
    /** A facility printed at real length, so a row is judged on a long line (Review Focus 1). Fictional. */
    static final String LONG_FACILITY = "Shapla Diagnostic and Consultation Centre, Mirpur Section 10 Branch";
    static final List<String> FOLLOW_UPS = List.of("some", "none");
    /**
     * PO ruling P6 (2026-10-30): what the papers page's quiet check polls while a paper is being read
     * ({@code GET /app/api/documents/{id}/status}, which records nothing), one answer per status, each read from the
     * in-flight case's own paper of that status. The body names no paper: the fixture is the status alone.
     */
    static final List<String> DOCUMENT_STATUS = List.of("processing", "received", "done");
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

    /**
     * No {@code month} is the patient's current month in Dhaka, never a UTC one: at 18:30 UTC on 31 October it is already
     * 00:30 on 1 November in Dhaka, so the calendar opens on November and holds the paper filed on the 1st.
     */
    @Test
    void noMonthIsTheDhakaCurrentMonth() {
        Instant dhakaNovemberFirst = Instant.parse("2026-10-31T18:30:00Z");
        FixedPapers.runnerAt(POSTGRES, dhakaNovemberFirst).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "calendar-default";
            Seeded s = family(context, k, dhakaNovemberFirst);
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "nov1"), s.patient(), "lab_report", "DONE", "2026-11-01",
                    Instant.parse("2026-10-31T18:20:00Z"), "routine");
            FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct30"), s.patient(), "lab_report", "DONE", "2026-10-30",
                    Instant.parse("2026-10-30T04:00:00Z"), "routine");

            JsonNode body = ok(client, s.session(), "/app/api/patients/" + s.patient().id() + "/calendar");
            assertThat(body.get("month").asText()).as("$.month with no month asked for").isEqualTo("2026-11");
            assertThat(field(body.get("days"), "date")).as("$.days[*].date").containsExactly("2026-11-01");
            assertThat(body.get("days").get(0).get("papers").get(0).get("document_id").asText())
                    .isEqualTo(FixedPapers.id(k, "nov1").toString());
        });
    }

    /**
     * PO decision S14 (plan W8-api): the calendar records no audit row; the timeline read records one TIMELINE_VIEW each
     * time it is read ({@code TimelineAuditTest} counts one render). Another family's GET is still 404 and adds nothing; the
     * owner's GET answers 200 with the month's papers and adds nothing either.
     */
    @Test
    void theCalendarRecordsNoAuditRowForTheOwnerAndIs404ForAnotherFamily() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded theirs = seedBusyMonth(context, "calendar-audit-theirs");
            Seeded mine = family(context, "calendar-audit-mine", NOW);
            String uri = "/app/api/patients/" + theirs.patient().id() + "/calendar?month=2026-10";
            String rows = "SELECT count(*) FROM baymax.audit_event WHERE patient_id = '" + theirs.patient().id() + "'";
            assertThat(FixedPapers.count(POSTGRES, rows)).as("audit rows before any calendar read").isZero();

            EntityExchangeResult<byte[]> other = get(client, mine.session(), uri);
            assertThat(other.getStatus().value()).as("another family's patient").isEqualTo(404);
            assertThat(JSON.readTree(other.getResponseBody())).isEqualTo(JSON.readTree("{\"reason\":\"NOT_FOUND\"}"));
            assertThat(FixedPapers.count(POSTGRES, rows)).as("audit rows for the other family's patient after its 404").isZero();

            JsonNode body = ok(client, theirs.session(), uri);
            // the read reached the patient's month: "no row" below is the rule, not a read that found nothing
            assertThat(body.get("month").asText()).as("$.month").isEqualTo("2026-10");
            assertThat(body.get("days").size()).as("$.days of the busy month").isPositive();
            List<List<String>> audit = FixedPapers.rows(POSTGRES,
                    "SELECT kind, family_id::text, patient_id::text, coalesce(document_id::text, '-') FROM baymax.audit_event"
                            + " WHERE patient_id = '" + theirs.patient().id() + "'");
            assertThat(audit).as("audit_event (kind, family_id, patient_id, document_id) after the owner's calendar read").isEmpty();
        });
    }

    /**
     * Plan W8 (master Review Focus 1): the forty-five-paper month the papers page is judged on holds what its fixtures
     * claim, read by field: all forty-five on October's calendar (days with two and three papers among them, NOW and
     * THIS_WEEK days, doctor's dates), the first timeline page full with a cursor, and the long facility on that page.
     * Without this, a seed that wrote fewer papers would still produce two fixtures, and the overflow check would judge a
     * quiet month.
     */
    @Test
    void theFortyFivePaperMonthHoldsFortyFivePapers() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seedFortyFive(context, "forty-five");
            JsonNode days = ok(client, s.session(), "/app/api/patients/" + s.patient().id() + "/calendar?month=2026-10").get("days");
            int papers = 0;
            int most = 0;
            int follows = 0;
            Set<String> chips = new HashSet<>();
            for (JsonNode day : days) {
                papers += day.get("papers").size();
                most = Math.max(most, day.get("papers").size());
                follows += day.get("follow_ups").size();
                day.get("papers").forEach(p -> chips.add(p.get("chip").isNull() ? "-" : p.get("chip").asText()));
            }
            assertThat(papers).as("papers on October's calendar").isEqualTo(FORTY_FIVE);
            assertThat(most).as("the most papers on one day").isEqualTo(3);
            assertThat(follows).as("doctor's dates on October's calendar").isPositive();
            assertThat(chips).as("the chips on the calendar").contains("now", "this_week", "-");

            JsonNode first = ok(client, s.session(), timeline(s.patient()));
            assertThat(first.get("entries")).as("$.entries of the first page").hasSize(TimelineService.PAGE_SIZE);
            assertThat(first.get("next_cursor").isTextual()).as("$.next_cursor of the first page").isTrue();
            assertThat(field(first.get("entries"), "facility")).as("$.entries[*].facility").contains(LONG_FACILITY);
        });
    }

    /** {@code ?type=lab} and {@code ?type=rx} (the page's filter links) answer only that type, read by field. */
    @Test
    void aTypeFilterAnswersOnlyThatType() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seedTwentyFive(context, "type-filter");
            List<String> lab = field(ok(client, s.session(), timeline(s.patient()) + "?type=lab").get("entries"), "document_type");
            List<String> rx = field(ok(client, s.session(), timeline(s.patient()) + "?type=rx").get("entries"), "document_type");
            assertThat(lab).as("$.entries[*].document_type for ?type=lab").hasSize(20).containsOnly("lab_report");
            assertThat(rx).as("$.entries[*].document_type for ?type=rx").hasSize(5).containsOnly("prescription");
        });
    }

    /**
     * Plan W8 review: the in-flight page the papers page reloads on, and the counts line is judged on, holds what its
     * fixture claims, read by field: a paper being read and one just received (the reload), a read lab report with
     * values outside their range and its explanation waiting for review, a prescription with medicines, and a read
     * paper filed under its upload day. And {@code rx-none}: a prescription filter over lab reports only answers nothing.
     */
    @Test
    void theInFlightPageHoldsWhatItsNameSays() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "in-flight-fields";
            Seeded s = seedInFlight(context, k);
            JsonNode entries = ok(client, s.session(), timeline(s.patient())).get("entries");
            assertThat(field(entries, "status")).as("$.entries[*].status").containsExactly("PROCESSING", "RECEIVED", "DONE", "DONE", "DONE");
            JsonNode lab = entries.get(2);
            assertThat(lab.get("document_id").asText()).isEqualTo(FixedPapers.id(k, "lab").toString());
            assertThat(lab.at("/counts/values").asInt()).as("the lab's values").isEqualTo(3);
            assertThat(lab.at("/counts/outside").asInt()).as("the lab's values outside (outside and critical)").isEqualTo(2);
            assertThat(lab.get("review_pending").asBoolean()).as("the lab's explanation waits for review").isTrue();
            assertThat(lab.get("chip").asText()).isEqualTo("now");
            JsonNode rx = entries.get(3);
            assertThat(rx.get("document_type").asText()).isEqualTo("prescription");
            assertThat(rx.at("/counts/medicines").asInt()).as("the prescription's medicines").isEqualTo(2);
            JsonNode undated = entries.get(4);
            assertThat(undated.get("date_is_upload_day").asBoolean()).as("the undated lab is filed under its upload day").isTrue();
            assertThat(undated.at("/counts/values").asInt()).isEqualTo(1);
            assertThat(undated.at("/counts/outside").asInt()).isZero();

            // in-flight-settled (PO ruling P6): the same five papers, the two being read now read, nothing else moved
            Seeded settled = seedInFlight(context, k + "-settled", true);
            JsonNode read = ok(client, settled.session(), timeline(settled.patient())).get("entries");
            assertThat(field(read, "status")).as("$.entries[*].status once read").containsExactly("DONE", "DONE", "DONE", "DONE", "DONE");
            assertThat(field(read, "document_type")).as("$.entries[*].document_type once read")
                    .containsExactly("lab_report", "prescription", "lab_report", "prescription", "lab_report");
            assertThat(read.get(0).at("/counts/values").asInt()).as("the lab read from PROCESSING has its value").isEqualTo(1);
            assertThat(read.get(0).get("date_is_upload_day").asBoolean()).as("filed under its upload day").isTrue();

            Seeded labsOnly = seedBusyMonth(context, k + "-labs", false);
            JsonNode none = ok(client, labsOnly.session(), timeline(labsOnly.patient()) + "?type=rx");
            assertThat(none.get("entries")).as("$.entries for ?type=rx over lab reports only").isEmpty();
            assertThat(none.get("next_cursor").isNull()).isTrue();
            assertThat(ok(client, labsOnly.session(), timeline(labsOnly.patient())).get("entries")).as("the same patient unfiltered").isNotEmpty();
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
        for (String area : AttentionApiContractTest.areas()) {
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
            Seeded s = switch (name) {
                case "empty" -> family(context, k, NOW);
                case "forty-five-first-page" -> seedFortyFive(context, k);
                case "in-flight" -> seedInFlight(context, k);
                case "in-flight-settled" -> seedInFlight(context, k, true);
                case "rx-none" -> seedBusyMonth(context, k, false);
                default -> seedTwentyFive(context, k);
            };
            String uri = timeline(s.patient());
            // the filter the case asks with, named in the fixture's source (the e2e table serves it for that query)
            String query = switch (name) {
                case "lab-only" -> "?type=lab";
                case "rx-only", "rx-none" -> "?type=rx";
                default -> "";
            };
            if ("last-page".equals(name)) {
                uri += "?cursor=" + ok(client, s.session(), uri).get("next_cursor").asText();
            }
            AttentionApiContractTest.write(PatientTimelineApiContractTest.class, FIXTURES.resolve("timeline"), WRITTEN.resolve("timeline"),
                    "timeline", name, "GET /app/api/patients/{id}/timeline" + query + " over the case " + name,
                    get(client, s.session(), uri + query));
        });
    }

    @ParameterizedTest
    @MethodSource("calendarCases")
    void eachCalendarCaseIsItsCommittedMswFixture(String name) {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "fixture-calendar-" + name;
            Seeded s = switch (name) {
                case "empty" -> family(context, k, NOW);
                case "crowded" -> seedCrowdedMonth(context, k);
                case "forty-five" -> seedFortyFive(context, k);
                case "september" -> seedTwentyFive(context, k);
                default -> seedBusyMonth(context, k);
            };
            // the month before October (plan W8: the page's previous-month link lands on a month Java answered)
            String month = "september".equals(name) ? "2026-09" : "2026-10";
            AttentionApiContractTest.write(PatientTimelineApiContractTest.class, FIXTURES.resolve("calendar"), WRITTEN.resolve("calendar"),
                    "calendar", name, "GET /app/api/patients/{id}/calendar?month=" + month + " over the case " + name,
                    get(client, s.session(), "/app/api/patients/" + s.patient().id() + "/calendar?month=" + month));
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

    static Stream<String> documentStatusCases() {
        return DOCUMENT_STATUS.stream();
    }

    /**
     * PO ruling P6: the answers the papers page's quiet check reads, each from the in-flight case's paper of that
     * status, so the e2e table answers the poll with what Java answers for it, never a body written by hand.
     */
    @ParameterizedTest
    @MethodSource("documentStatusCases")
    void eachDocumentStatusCaseIsItsCommittedMswFixture(String name) {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            String k = "fixture-document-status-" + name;
            Seeded s = seedInFlight(context, k);
            String paper = "done".equals(name) ? "lab" : name;
            EntityExchangeResult<byte[]> r = get(client, s.session(), "/app/api/documents/" + FixedPapers.id(k, paper) + "/status");
            assertThat(r.getStatus().value()).as("GET the " + paper + " paper's status").isEqualTo(200);
            assertThat(JSON.readTree(r.getResponseBody()).get("status").asText()).as("the " + paper + " paper's status")
                    .isEqualTo(name.toUpperCase(java.util.Locale.ROOT));
            AttentionApiContractTest.write(PatientTimelineApiContractTest.class, FIXTURES.resolve("document-status"),
                    WRITTEN.resolve("document-status"), "document-status", name,
                    "GET /app/api/documents/{id}/status over the in-flight case's " + paper + " paper", r);
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
        assertThat(AttentionApiContractTest.committed(FIXTURES.resolve("document-status")))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT_STATUS.stream().map(n -> n + ".json").toList());
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

    /**
     * Forty-five papers in October 2026 up to the fixed today (the 21st), newest first (plan W8, master Review Focus 1):
     * two a day, three on the 19th to the 21st; every ninth NOW from the first, every ninth THIS_WEEK from the fifth, the
     * rest routine; every fifth from the third a prescription with a medicine and a doctor's date a week on (in October);
     * every fourth from the second printed by {@link #LONG_FACILITY}.
     */
    static Seeded seedFortyFive(ApplicationContext context, String k) throws Exception {
        Seeded s = family(context, k, NOW);
        for (int i = 0; i < FORTY_FIVE; i++) {
            int day = 21 - (i % 21);
            LocalDate printed = LocalDate.of(2026, 10, day);
            Instant uploaded = printed.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).plus(Duration.ofHours(4)).plus(Duration.ofMinutes(i));
            String level = i % 9 == 0 ? "now" : i % 9 == 4 ? "this_week" : "routine";
            String type = i % 5 == 2 ? "prescription" : "lab_report";
            UUID id = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "paper-" + i), s.patient(), type, "DONE", printed.toString(), uploaded, level);
            if (i % 4 == 1) {
                FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET facility = ? WHERE id = ?", LONG_FACILITY, id);
            }
            if ("prescription".equals(type)) {
                FixedPapers.exec(POSTGRES, "INSERT INTO baymax.medication_event (patient_id, document_id, name, crop_key, at) "
                        + "VALUES (?, ?, 'Zorvalex 10 mg', ?, ?)", s.patient().id(), id, s.patient().familyId() + "/" + s.patient().id()
                        + "/" + id + "/crop-m1.jpg", java.sql.Timestamp.from(uploaded));
                FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "follow-" + i), s.patient(), id, "Review after the course",
                        LocalDate.of(2026, 10, Math.min(day + 7, 31)).toString());
            }
        }
        return s;
    }

    /** October 2026: a NOW lab report on the 1st, a prescription on the 31st with a follow-up due on the 15th, a paper on 1 November. */
    static Seeded seedBusyMonth(ApplicationContext context, String k) throws Exception {
        return seedBusyMonth(context, k, true);
    }

    /** The busy month, with or without its prescription (and the follow-up it set): without, the patient has lab reports only. */
    static Seeded seedBusyMonth(ApplicationContext context, String k, boolean prescription) throws Exception {
        Seeded s = family(context, k, NOW);
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct1"), s.patient(), "lab_report", "DONE", "2026-10-01",
                Instant.parse("2026-10-01T04:00:00Z"), "now");
        UUID rx = prescription ? FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct31"), s.patient(), "prescription", "DONE", "2026-10-31",
                Instant.parse("2026-10-20T04:00:00Z"), "routine") : null;
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "nov1"), s.patient(), "lab_report", "DONE", "2026-11-01",
                Instant.parse("2026-10-20T05:00:00Z"), "routine");
        if (rx != null) {
            FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "follow"), s.patient(), rx, "Bring the sugar chart", "2026-10-15");
        }
        return s;
    }

    /**
     * Plan W8 review: a page with papers still being read (the papers page reloads itself while one is) beside read ones
     * whose counts the row's line says. Newest first: a lab report being read (uploaded ten minutes before the fixed
     * now), one just received (twenty minutes, no type yet); a NOW lab report printed 19 October with three values, two
     * of them outside their range (one critical), whose explanation waits for review; a prescription printed 18 October
     * with two medicines; a lab report that printed no date, filed under its upload day (15 October), with one value.
     */
    static Seeded seedInFlight(ApplicationContext context, String k) throws Exception {
        return seedInFlight(context, k, false);
    }

    /**
     * The in-flight case; {@code settled}: the same papers once the two being read have been read (PO ruling P6, the
     * page the quiet check re-renders into): the one that was PROCESSING a routine lab report with one value inside its
     * range, the one just RECEIVED a routine prescription, both filed under their upload day as Java files an undated
     * paper. Nothing else changes.
     */
    static Seeded seedInFlight(ApplicationContext context, String k, boolean settled) throws Exception {
        Seeded s = family(context, k, NOW);
        UUID processing = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "processing"), s.patient(), "lab_report",
                settled ? "DONE" : "PROCESSING", null, NOW.minus(Duration.ofMinutes(10)), settled ? "routine" : null);
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "received"), s.patient(), settled ? "prescription" : null,
                settled ? "DONE" : "RECEIVED", null, NOW.minus(Duration.ofMinutes(20)), settled ? "routine" : null);
        if (settled) {
            observation(processing, "Glucose", "inside");
        }
        UUID lab = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "lab"), s.patient(), "lab_report", "DONE", "2026-10-19",
                Instant.parse("2026-10-19T04:00:00Z"), "now");
        observation(lab, "Haemoglobin", "outside");
        observation(lab, "Potassium", "critical");
        observation(lab, "Sodium", "inside");
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, gate_status, "
                + "created_at) VALUES (?, ?, 'explanation', 'now', '[]', 'pending', ?)", s.patient().familyId(), lab,
                java.sql.Timestamp.from(Instant.parse("2026-10-19T04:05:00Z")));
        UUID rx = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "rx"), s.patient(), "prescription", "DONE", "2026-10-18",
                Instant.parse("2026-10-18T04:00:00Z"), "routine");
        int m = 0;
        for (String medicine : List.of("Zorvalex 10 mg", "Quentamol 500 mg")) {
            FixedPapers.exec(POSTGRES, "INSERT INTO baymax.medication_event (patient_id, document_id, name, crop_key, at) VALUES (?, ?, ?, ?, ?)",
                    s.patient().id(), rx, medicine, s.patient().familyId() + "/" + s.patient().id() + "/" + rx + "/crop-m" + (++m) + ".jpg",
                    java.sql.Timestamp.from(Instant.parse("2026-10-18T04:00:00Z")));
        }
        UUID undated = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "undated"), s.patient(), "lab_report", "DONE", null,
                Instant.parse("2026-10-15T04:00:00Z"), "routine");
        observation(undated, "Glucose", "inside");
        return s;
    }

    /** One value on {@code document} with this stored verdict (V19), as TimelineReadModelTest writes one. */
    private static void observation(UUID document, String name, String verdict) throws Exception {
        String level = "critical".equals(verdict) ? "now" : "outside".equals(verdict) ? "this_week" : "routine";
        String side = "inside".equals(verdict) ? null : "high";
        String reason = "value_" + ("critical".equals(verdict) ? "critical" : "outside".equals(verdict) ? "outside_range" : verdict) + ":x|stopgap";
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.observation (patient_id, document_id, name, value, crop_key, observed_at, verdict, "
                + "verdict_level, verdict_side, verdict_reason) SELECT patient_id, id, ?, '1', 'k/c.jpg', created_at, ?, ?, ?, ? "
                + "FROM baymax.document WHERE id = ?", name, verdict, level, side, reason, document);
    }

    /**
     * October 2026 with days that hold more than one thing (plan W2e-b, MonthCalendar): on the 8th a NOW lab report, a
     * routine prescription and a doctor's date set by it (two papers and a follow-up on one day); on the 14th a THIS_WEEK
     * lab report alone; on the 22nd a THIS_WEEK and a NOW lab report together, where the day's mark is the higher.
     */
    static Seeded seedCrowdedMonth(ApplicationContext context, String k) throws Exception {
        Seeded s = family(context, k, NOW);
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct8-lab"), s.patient(), "lab_report", "DONE", "2026-10-08",
                Instant.parse("2026-10-08T04:00:00Z"), "now");
        UUID rx = FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct8-rx"), s.patient(), "prescription", "DONE", "2026-10-08",
                Instant.parse("2026-10-08T05:00:00Z"), "routine");
        FixedPapers.followUp(POSTGRES, FixedPapers.id(k, "oct8-follow"), s.patient(), rx, "Fasting sugar on the same day", "2026-10-08");
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct14"), s.patient(), "lab_report", "DONE", "2026-10-14",
                Instant.parse("2026-10-14T04:00:00Z"), "this_week");
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct22-week"), s.patient(), "lab_report", "DONE", "2026-10-22",
                Instant.parse("2026-10-20T04:00:00Z"), "this_week");
        FixedPapers.paper(POSTGRES, FixedPapers.id(k, "oct22-now"), s.patient(), "lab_report", "DONE", "2026-10-22",
                Instant.parse("2026-10-20T05:00:00Z"), "now");
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
