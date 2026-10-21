package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan W8-api, PO decision S14: the calendar read records no audit row, and the timeline read records one TIMELINE_VIEW
 * each time it is read. One render of the papers page reads both, so it is one view, not two. What this test does not
 * claim: that a month link records nothing. The page is server-rendered and every control is a plain link, so a month,
 * filter or "more" link is a new render whose timeline read records one TIMELINE_VIEW; W8's {@code oneOpenIsOneTimelineView}
 * request log is where the reads per link are counted. Over the real router, guard, services and Postgres: the count is
 * read from {@code baymax.audit_event} itself. Every read asserts its 200 and its content first, because a 404 also adds
 * no row and would make "adds 0" pass for a reason other than the rule.
 */
@Testcontainers
class TimelineAuditTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 12:00 in Dhaka on 21 October 2026. */
    static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    private static final AtomicInteger NUMBERS = new AtomicInteger(870);
    private static final ObjectMapper JSON = new ObjectMapper();

    record Seeded(PatientProfile patient, UUID paper, String session) {
    }

    /** A family with one patient and one finished paper filed on 5 October 2026, and a live session. */
    static Seeded seed(ApplicationContext context, String kase) throws Exception {
        UUID f = FixedPapers.family(POSTGRES, FixedPapers.id(kase, "family"), "+8801700000" + NUMBERS.incrementAndGet(), "Rahim");
        PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(kase, "ma"), f, "Ma");
        UUID paper = FixedPapers.paper(POSTGRES, FixedPapers.id(kase, "oct5"), ma, "lab_report", "DONE", "2026-10-05",
                Instant.parse("2026-10-06T04:00:00Z"), "routine");
        return new Seeded(ma, paper, FixedPapers.session(context, f, NOW));
    }

    static long timelineViews(PatientProfile p) throws Exception {
        return FixedPapers.count(POSTGRES, "SELECT count(*) FROM baymax.audit_event WHERE kind = 'timeline_view' AND patient_id = '"
                + p.id() + "'");
    }

    static long auditRows(PatientProfile p) throws Exception {
        return FixedPapers.count(POSTGRES, "SELECT count(*) FROM baymax.audit_event WHERE patient_id = '" + p.id() + "'");
    }

    static JsonNode ok(WebTestClient client, String session, String uri) throws Exception {
        EntityExchangeResult<byte[]> r = client.get().uri(uri).cookie("baymax_session", session).exchange().expectBody().returnResult();
        assertThat(r.getStatus().value()).as("GET " + uri).isEqualTo(200);
        return JSON.readTree(r.getResponseBody());
    }

    /** The timeline answered, and it lists the seeded paper: the read reached the patient's data. */
    static void readTimeline(WebTestClient client, Seeded s) throws Exception {
        JsonNode entries = ok(client, s.session(), "/app/api/patients/" + s.patient().id() + "/timeline").get("entries");
        assertThat(entries.size()).as("$.entries").isEqualTo(1);
        assertThat(entries.get(0).get("document_id").asText()).as("$.entries[0].document_id").isEqualTo(s.paper().toString());
    }

    /** The calendar for {@code month} answered with that month; October holds the seeded paper on the 5th. */
    static void readCalendar(WebTestClient client, Seeded s, String month) throws Exception {
        JsonNode body = ok(client, s.session(), "/app/api/patients/" + s.patient().id() + "/calendar?month=" + month);
        assertThat(body.get("month").asText()).as("$.month").isEqualTo(month);
        if ("2026-10".equals(month)) {
            assertThat(body.get("days").size()).as("$.days in October").isEqualTo(1);
            assertThat(body.get("days").get(0).get("date").asText()).as("$.days[0].date").isEqualTo("2026-10-05");
            assertThat(body.get("days").get(0).get("papers").get(0).get("document_id").asText())
                    .as("$.days[0].papers[0].document_id").isEqualTo(s.paper().toString());
        }
    }

    @Test
    void aTimelineAndItsCalendarAreOneView() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "one-open");
            long before = timelineViews(s.patient());

            readTimeline(client, s);
            readCalendar(client, s, "2026-10");

            assertThat(timelineViews(s.patient()) - before)
                    .as("timeline_view rows added by one render of the papers page (timeline + calendar)").isEqualTo(1L);
        });
    }

    /** A calendar read by itself, here a second month after a render, adds no row of any kind. */
    @Test
    void aSecondCalendarReadIsNoView() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "second-calendar");
            readTimeline(client, s);
            readCalendar(client, s, "2026-10");
            long views = timelineViews(s.patient());
            long rows = auditRows(s.patient());

            readCalendar(client, s, "2026-09");

            assertThat(timelineViews(s.patient()) - views).as("timeline_view rows added by a second calendar read").isEqualTo(0L);
            assertThat(auditRows(s.patient()) - rows).as("audit_event rows of any kind added by a second calendar read").isEqualTo(0L);
        });
    }

    /** The guard: the rule moves the view onto the timeline, it does not drop it. */
    @Test
    void theTimelineStillRecordsItsView() {
        FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Seeded s = seed(context, "timeline-alone");
            long before = timelineViews(s.patient());

            readTimeline(client, s);

            assertThat(timelineViews(s.patient()) - before).as("timeline_view rows added by the timeline alone").isEqualTo(1L);
        });
    }
}
