package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.port.in.ReviewGateUseCase;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.testsupport.NowPapers;
import com.elioo.baymax.testsupport.SchemaSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B3 Step 3, DR-41 item 5: the global NOW banner and its দেখেছি over the real router, guard, services and Postgres.
 * Every test GETs the banner, PUTs {@code seen} exactly as it was received, then asserts. Both directions are here: an ack
 * hides exactly what was seen and changes nothing but {@code now_ack} (asked of the whole schema, never of a list); and
 * a new paper, a raise, a new reason, a first assessment or a later release shows the banner again, while a
 * re-assessment that changes none of them does not. The banner's papers are the attention stack's NOW cards, read, not
 * restated. An ack is per account holder: a share member and the owner never hide the banner for each other.
 */
@Testcontainers
class NowBannerApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final AtomicInteger NUMBERS = new AtomicInteger(500);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NEW_REASON = "value_critical:k|stopgap";

    static ReactiveWebApplicationContextRunner runner() {
        return AppApiContext.runner(POSTGRES)
                .withBean(PaperDeleteApiTest.RecordingDelivery.class, PaperDeleteApiTest.RecordingDelivery::new, bd -> bd.setPrimary(true))
                // no scheduled writer runs behind the test's back: a sweep is run only where a test runs it
                .withPropertyValues("baymax.urgency.sweep-cron=-");
    }

    /** A family with a fresh fictional number and one patient, Ma; or, with {@code null} owner name, just the family. */
    record Holder(FamilyAccount family, PatientProfile ma, String session) {
    }

    static String nextNumber() {
        return "+8801700000" + NUMBERS.incrementAndGet();
    }

    static Holder owner(ApplicationContext context) {
        FamilyAccount f = AppApiContext.family(context, nextNumber(), "Rahim");
        PatientProfile ma = AppApiContext.patient(context, f.id(), "Ma");
        return new Holder(f, ma, AppApiContext.session(context, f.id()));
    }

    /** Another account holder sharing {@code patient} (a sibling's view): a separate family_account. */
    static Holder member(ApplicationContext context, PatientProfile patient, String number) {
        FamilyAccount f = AppApiContext.family(context, number, "Sister");
        AppApiContext.share(context, patient, number);
        return new Holder(f, patient, AppApiContext.session(context, f.id()));
    }

    static JsonNode banner(WebTestClient client, String session) {
        return body(client.get().uri("/app/api/now-banner").cookie("baymax_session", session).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    static WebTestClient.ResponseSpec put(WebTestClient client, String session, Object body) {
        return client.put().uri("/app/api/now-banner/ack").cookie("baymax_session", session)
                .header(AppApiGuard.HEADER, "1").header(HttpHeaders.ORIGIN, AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    /** PUTs {@code seen} exactly as given and returns the answer. */
    static JsonNode ack(WebTestClient client, String session, JsonNode seen) {
        return body(put(client, session, Map.of("seen", seen)).expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private static JsonNode body(byte[] bytes) {
        try {
            return JSON.readTree(bytes);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The banner's document entry for this paper, by field, never by a substring of the body. */
    static JsonNode document(JsonNode banner, UUID id) {
        for (JsonNode d : banner.get("documents")) {
            if (id.toString().equals(d.get("document_id").asText())) {
                return d;
            }
        }
        throw new AssertionError("no banner document " + id + " in " + banner);
    }

    static JsonNode seenOf(JsonNode banner, UUID id) {
        for (JsonNode s : banner.get("seen")) {
            if (id.toString().equals(s.get("document_id").asText())) {
                return s;
            }
        }
        throw new AssertionError("no seen entry " + id + " in " + banner);
    }

    static Set<String> documentIds(JsonNode banner) {
        Set<String> ids = new HashSet<>();
        banner.get("documents").forEach(d -> ids.add(d.get("document_id").asText()));
        return ids;
    }

    private static void raise(ApplicationContext context, UUID paper, Urgency level, String reason) {
        context.getBean(DocumentUrgencyPort.class)
                .raise(paper, new UrgencyAssessment(level, List.of(reason)), Instant.now().plusSeconds(1)).block();
    }

    // ---- hides, exactly ---------------------------------------------------------------------------------------

    @Test
    void anAckHidesTheBannerForTheDocumentsAcked() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, a.ma());

            JsonNode before = banner(client, a.session());
            assertThat(before.get("show").asBoolean()).as("a NOW paper not yet seen").isTrue();
            assertThat(before.get("line_key").asText()).isEqualTo("urgency.now");
            assertThat(document(before, paper).get("acked").asBoolean()).isFalse();

            JsonNode answer = ack(client, a.session(), before.get("seen"));
            assertThat(answer.get("show").asBoolean()).as("the ack's own answer").isFalse();
            assertThat(document(answer, paper).get("acked").asBoolean()).isTrue();
            assertThat(banner(client, a.session()).get("show").asBoolean()).as("the next GET").isFalse();
        });
    }

    @Test
    void anAckHidesNothingItDidNotSee() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID first = NowPapers.nowPaper(context, POSTGRES, a.ma());
            UUID second = NowPapers.nowPaper(context, POSTGRES, a.ma());

            JsonNode before = banner(client, a.session());
            ArrayNode onlyFirst = JSON.createArrayNode().add(seenOf(before, first));
            JsonNode answer = ack(client, a.session(), onlyFirst);

            assertThat(answer.get("show").asBoolean()).as("the second paper was never acknowledged").isTrue();
            assertThat(document(answer, first).get("acked").asBoolean()).isTrue();
            assertThat(document(answer, second).get("acked").asBoolean()).isFalse();
        });
    }

    @Test
    void anAckHoldsAcrossThatAccountsDevices() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            NowPapers.nowPaper(context, POSTGRES, a.ma());
            String phone = a.session();
            String laptop = AppApiContext.session(context, a.family().id());

            ack(client, phone, banner(client, phone).get("seen"));
            assertThat(banner(client, laptop).get("show").asBoolean()).as("the same account holder's other device").isFalse();
        });
    }

    /**
     * The restamp direction: ExplanationService.detail and the hourly sweep both restamp {@code urgency_assessed_at} on a
     * paper whose level and reasons do not change. That is not something new to see, so the banner stays hidden. Each
     * restamper is checked on its own: the restamp is asserted, and the banner read, after detail and again after the
     * sweep, so neither can stand in for the other.
     */
    @Test
    void aReassessmentThatChangesNothingKeepsTheBannerHidden() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, a.ma());
            // inside its printed range and with no verdict yet: NEEDING_ASSESSMENT picks it, and its assessment adds no reason
            NowPapers.value(POSTGRES, a.ma(), paper, "Kalium-B3-inside", "4.2");
            String assessed = "SELECT urgency_assessed_at::text FROM baymax.document WHERE id = '" + paper + "'";
            String assessedBefore = scalar(assessed);

            ack(client, a.session(), banner(client, a.session()).get("seen"));

            // 1. a WhatsApp detail request: it re-assesses first (assessAndStore), then composes; no error is swallowed, so a
            //    detail that never reached the re-assessment fails here
            context.getBean(ExplainDocumentUseCase.class).detail(paper).block(Duration.ofSeconds(30));
            String afterDetail = scalar(assessed);
            assertThat(afterDetail).as("the guard: detail restamped urgency_assessed_at").isNotEqualTo(assessedBefore);
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + paper + "'")).isEqualTo("now");
            assertThat(banner(client, a.session()).get("show").asBoolean()).as("$.show after detail's re-assessment").isFalse();

            // 2. the hourly sweep: detail gave the first value its verdict, so a second value with none (a re-crop's, F2's
            //    backfill) is what puts the paper back on the sweep's queue; it too is inside its range and adds no reason
            NowPapers.value(POSTGRES, a.ma(), paper, "Kalium-B3-inside-2", "4.3");
            context.getBean(DocumentUrgencyUseCase.class).sweep().block(Duration.ofSeconds(60));
            assertThat(scalar(assessed)).as("the guard: the sweep restamped urgency_assessed_at again").isNotEqualTo(afterDetail);
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + paper + "'")).isEqualTo("now");
            assertThat(banner(client, a.session()).get("show").asBoolean()).as("$.show after the sweep's re-assessment").isFalse();
        });
    }

    /**
     * D2 (Q6, spec §4.18): a printed date the model marked uncertain is marked wherever it is shown, the banner included;
     * an upload day is certain and never marked. Read from the stack's card, never re-derived.
     */
    @Test
    void theBannerCarriesTheUncertainMarkOnlyWhereTheModelSetIt() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID printed = NowPapers.nowPaper(context, POSTGRES, a.ma());
            exec("UPDATE baymax.document SET doc_date = DATE '2026-07-11', doc_date_uncertain = true WHERE id = ?", printed);
            UUID uploadDay = NowPapers.nowPaper(context, POSTGRES, a.ma());

            JsonNode banner = banner(client, a.session());
            assertThat(document(banner, printed).get("date").asText()).as("the printed date the card is dated by").isEqualTo("2026-07-11");
            assertThat(document(banner, printed).path("date_uncertain").asBoolean()).as("$.documents[printed].date_uncertain").isTrue();
            assertThat(document(banner, uploadDay).get("date_is_upload_day").asBoolean()).isTrue();
            assertThat(document(banner, uploadDay).path("date_uncertain").isBoolean()).as("the field is always there").isTrue();
            assertThat(document(banner, uploadDay).path("date_uncertain").asBoolean()).as("$.documents[upload day].date_uncertain").isFalse();
        });
    }

    /**
     * "Changes nothing else", derived from the schema: every baymax table, before and after the PUT, by count and by the
     * md5 of its rows. A write to any table the author did not think of fails here.
     */
    @Test
    void anAckNeverChangesAnythingButNowAck() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            PaperDeleteApiTest.RecordingDelivery delivery = context.getBean(PaperDeleteApiTest.RecordingDelivery.class);
            Holder a = owner(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, a.ma());
            NowPapers.value(POSTGRES, a.ma(), paper, "Kalium-B3", NowPapers.CRITICAL);
            AppApiContext.explanation(POSTGRES, a.ma(), paper, "pending", "a pending explanation");
            UUID pending = UUID.fromString(scalar("SELECT id FROM baymax.outbound_message WHERE document_id = '" + paper + "'"));
            JsonNode seen = banner(client, a.session()).get("seen");

            SchemaSnapshot.Exclusions exclusions = SchemaSnapshot.Exclusions.none()
                    .table("now_ack", "the one write this request makes")
                    .column("web_session", "last_seen_at", "AppApiGuard's session resolve slides it on every request (V8)")
                    .column("web_session", "expires_at", "AppApiGuard's session resolve slides it on every request (V8)");
            Map<String, SchemaSnapshot.Table> before;
            Map<String, SchemaSnapshot.Table> after;
            try (Connection c = connect()) {
                before = SchemaSnapshot.of(c, "baymax", exclusions);
            }
            ack(client, a.session(), seen);
            List<String> all;
            try (Connection c = connect()) {
                after = SchemaSnapshot.of(c, "baymax", exclusions);
                all = SchemaSnapshot.tables(c, "baymax");
            }

            List<String> allButNowAck = new ArrayList<>(all);
            allButNowAck.remove("now_ack");
            assertThat(before.keySet()).as("the snapshot read every baymax table but now_ack").hasSizeGreaterThanOrEqualTo(15)
                    .containsExactlyInAnyOrderElementsOf(allButNowAck);
            assertThat(count("SELECT count(*) FROM baymax.outbound_message WHERE document_id = '" + paper + "'"))
                    .as("the paper has a message the snapshot would see change").isPositive();
            assertThat(count("SELECT count(*) FROM baymax.observation WHERE document_id = '" + paper + "'"))
                    .as("the paper has a value the snapshot would see change").isPositive();
            List<String> changed = new ArrayList<>();
            before.forEach((table, was) -> {
                SchemaSnapshot.Table now = after.get(table);
                if (!was.equals(now)) {
                    changed.add(table + " rows " + was.rows() + "→" + (now == null ? "gone" : now.rows()));
                }
            });
            assertThat(changed).as("tables the ack changed, besides now_ack").isEmpty();
            assertThat(after).as("every table but now_ack, before and after the ack").isEqualTo(before);
            assertThat(count("SELECT count(*) FROM baymax.now_ack WHERE document_id = '" + paper + "'")).as("the ack itself").isEqualTo(1);

            // outside the snapshot: the record is unchanged where the family reads it
            client.get().uri("/app/api/documents/" + paper).cookie("baymax_session", a.session()).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.banner.level").isEqualTo("now").jsonPath("$.now.notdoctor_key").isEqualTo("now.notdoctor");
            client.get().uri("/app/api/me").cookie("baymax_session", a.session()).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.patients[0].urgent").isEqualTo(true);
            int mark = delivery.handed.size();
            context.getBean(ReviewGateUseCase.class).approve(pending, "tanvir").block(Duration.ofSeconds(30));
            assertThat(delivery.since(mark, a.family().id())).as("the explanation approved after the ack, delivered")
                    .extracting(OutboundMessage::id).containsExactly(pending);
        });
    }

    // ---- shows again ------------------------------------------------------------------------------------------

    @Test
    void aNewNowUploadShowsTheBannerAgain() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID old = NowPapers.nowPaper(context, POSTGRES, a.ma());
            ack(client, a.session(), banner(client, a.session()).get("seen"));

            UUID fresh = NowPapers.nowPaper(context, POSTGRES, a.ma());
            JsonNode after = banner(client, a.session());
            assertThat(after.get("show").asBoolean()).as("$.show after a new NOW upload").isTrue();
            assertThat(document(after, fresh).get("acked").asBoolean()).isFalse();
            assertThat(document(after, old).get("acked").asBoolean()).isTrue();
        });
    }

    @Test
    void aThisWeekToNowRaiseShowsTheBannerAgain() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID week = NowPapers.paper(context, POSTGRES, a.ma(), "this_week");
            JsonNode before = banner(client, a.session());
            assertThat(before.get("show").asBoolean()).as("THIS_WEEK has no banner").isFalse();
            ack(client, a.session(), before.get("seen"));

            raise(context, week, Urgency.NOW, NEW_REASON);
            JsonNode after = banner(client, a.session());
            assertThat(after.get("show").asBoolean()).as("$.show after a this_week to now raise").isTrue();
            assertThat(document(after, week).get("acked").asBoolean()).isFalse();
        });
    }

    @Test
    void aNewReasonOnANowPaperShowsTheBannerAgain() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, a.ma());
            JsonNode before = banner(client, a.session());
            ack(client, a.session(), before.get("seen"));

            raise(context, paper, Urgency.NOW, NEW_REASON);
            JsonNode after = banner(client, a.session());
            assertThat(seenOf(after, paper).get("reasons_digest").asText()).as("the reasons changed")
                    .isNotEqualTo(seenOf(before, paper).get("reasons_digest").asText());
            assertThat(after.get("show").asBoolean()).as("$.show after a new reason on the NOW paper").isTrue();
        });
    }

    @Test
    void aFirstAssessmentBySweepShowsTheBannerAgain() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            NowPapers.nowPaper(context, POSTGRES, a.ma());
            UUID unassessed = NowPapers.paper(context, POSTGRES, a.ma(), null);
            NowPapers.criticalValue(POSTGRES, a.ma(), unassessed);
            JsonNode before = banner(client, a.session());
            assertThat(documentIds(before)).as("the unassessed paper is not on the banner yet").doesNotContain(unassessed.toString());
            ack(client, a.session(), before.get("seen"));

            context.getBean(DocumentUrgencyUseCase.class).sweep().block(Duration.ofSeconds(60));
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + unassessed + "'")).as("the sweep's first assessment")
                    .isEqualTo("now");
            JsonNode after = banner(client, a.session());
            assertThat(after.get("show").asBoolean()).as("$.show after the sweep's first assessment").isTrue();
            assertThat(document(after, unassessed).get("acked").asBoolean()).isFalse();
        });
    }

    /** Review Focus 1: a NOW paper held for the reviewer, acked while pending; the release is something new to see. */
    @Test
    void aLaterGateReleaseShowsTheBannerAgain() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, a.ma());
            AppApiContext.explanation(POSTGRES, a.ma(), paper, "pending", "held for the reviewer");
            UUID pending = UUID.fromString(scalar("SELECT id FROM baymax.outbound_message WHERE document_id = '" + paper + "'"));
            JsonNode before = banner(client, a.session());
            assertThat(seenOf(before, paper).get("release_state").asText()).isEqualTo("pending");
            ack(client, a.session(), before.get("seen"));

            context.getBean(ReviewGateUseCase.class).approve(pending, "tanvir").block(Duration.ofSeconds(30));
            JsonNode after = banner(client, a.session());
            assertThat(seenOf(after, paper).get("release_state").asText()).isEqualTo("released");
            assertThat(after.get("show").asBoolean()).as("$.show after the gate released the paper").isTrue();
        });
    }

    // ---- the banner is the stack ------------------------------------------------------------------------------

    @Test
    void theBannerIsTheStacksNowSet() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        runner().withBean(Clock.class, () -> Clock.fixed(t, ZoneOffset.UTC)).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            for (String status : List.of("RECEIVED", "PROCESSING", "NEEDS_RETAKE", "DONE", "FAILED")) {
                UUID p = NowPapers.nowPaper(context, POSTGRES, a.ma());
                exec("UPDATE baymax.document SET status = ?" + ("NEEDS_RETAKE".equals(status) ? ", status_reason_code = 'low_overall'" : "")
                        + ", created_at = ? WHERE id = ?", status, java.sql.Timestamp.from(t.minus(Duration.ofHours(1))), p);
            }
            NowPapers.paper(context, POSTGRES, a.ma(), "this_week");
            UUID inside = NowPapers.nowPaper(context, POSTGRES, a.ma());
            exec("UPDATE baymax.document SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(t.minus(Duration.ofDays(30)).plusSeconds(1)), inside);
            UUID outside = NowPapers.nowPaper(context, POSTGRES, a.ma());
            exec("UPDATE baymax.document SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(t.minus(Duration.ofDays(30)).minusSeconds(1)), outside);
            UUID oldPrinted = NowPapers.nowPaper(context, POSTGRES, a.ma());
            exec("UPDATE baymax.document SET doc_date = date '2026-01-05', created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(t.minus(Duration.ofMinutes(1))), oldPrinted);
            Holder stranger = owner(context);
            NowPapers.nowPaper(context, POSTGRES, stranger.ma());

            Set<String> stackNow = new HashSet<>();
            JsonNode stack = body(client.get().uri("/app/api/patients/" + a.ma().id() + "/attention").cookie("baymax_session", a.session())
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
            stack.get("items").forEach(i -> {
                if ("urgent".equals(i.get("kind").asText()) && "now".equals(i.get("level").asText())) {
                    stackNow.add(i.get("document_id").asText());
                }
            });
            assertThat(stackNow).as("the guard: the stack's NOW set holds the old printed date and the window's first second")
                    .isNotEmpty().contains(oldPrinted.toString(), inside.toString()).doesNotContain(outside.toString());
            Set<String> onBanner = documentIds(banner(client, a.session()));
            assertThat(onBanner).as("the banner holds the paper uploaded today with a printed date of 2026-01-05")
                    .contains(oldPrinted.toString());
            assertThat(onBanner).as("the banner leaves out the paper uploaded a second before the window")
                    .doesNotContain(outside.toString());
            assertThat(onBanner).as("the banner's papers are the stack's NOW cards").isEqualTo(stackNow);
        });
    }

    // ---- per account holder (Q2, Review Focus 2) --------------------------------------------------------------

    @Test
    void afterTheOwnerAcksTheShareMemberStillSeesTheBanner() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            Holder b = member(context, a.ma(), nextNumber());
            NowPapers.nowPaper(context, POSTGRES, a.ma());

            ack(client, a.session(), banner(client, a.session()).get("seen"));
            assertThat(banner(client, a.session()).get("show").asBoolean()).as("the owner, who tapped").isFalse();
            assertThat(banner(client, b.session()).get("show").asBoolean()).as("the share member, who never saw it").isTrue();
        });
    }

    @Test
    void afterTheShareMemberAcksTheOwnerStillSeesTheBanner() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            Holder b = member(context, a.ma(), nextNumber());
            NowPapers.nowPaper(context, POSTGRES, a.ma());

            ack(client, b.session(), banner(client, b.session()).get("seen"));
            assertThat(banner(client, b.session()).get("show").asBoolean()).as("the share member, who tapped").isFalse();
            assertThat(banner(client, a.session()).get("show").asBoolean()).as("the owner, who never saw it").isTrue();
        });
    }

    /**
     * DR-45 item 6, under reading (a), the default until the PO confirms decision 8's meaning: B is a share member of Ma
     * (owner A) and owns Pb, shared with C. B acks a seen set holding Ma's NOW paper. C's banner for Pb is untouched, A's
     * still shows, and the stored row names B's own account, read by column so a row keyed on the patient's family is
     * caught even when every count agrees.
     */
    @Test
    void aTapperInTwoFamiliesAcksOnlyWhereTheReadingSays() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            Holder b = member(context, a.ma(), nextNumber());
            PatientProfile pb = AppApiContext.patient(context, b.family().id(), "Pb");
            Holder c = member(context, pb, nextNumber());
            UUID maPaper = NowPapers.nowPaper(context, POSTGRES, a.ma());
            UUID pbPaper = NowPapers.nowPaper(context, POSTGRES, pb);

            JsonNode drawn = banner(client, b.session());
            assertThat(documentIds(drawn)).as("B sees both papers").contains(maPaper.toString(), pbPaper.toString());
            ack(client, b.session(), JSON.createArrayNode().add(seenOf(drawn, maPaper)));

            List<String> stored = new ArrayList<>();
            try (Connection conn = connect(); java.sql.Statement s = conn.createStatement();
                 java.sql.ResultSet rs = s.executeQuery("SELECT family_id FROM baymax.now_ack WHERE document_id = '" + maPaper + "'")) {
                while (rs.next()) {
                    stored.add(rs.getString(1));
                }
            }
            assertThat(stored).as("now_ack.family_id of the row for Ma's paper: the tapper's own account, B "
                    + b.family().id() + ", not Ma's owner A " + a.family().id()).containsExactly(b.family().id().toString());
            JsonNode forC = banner(client, c.session());
            assertThat(forC.get("show").asBoolean()).as("$.show for C, who shares Pb").isTrue();
            assertThat(document(forC, pbPaper).get("acked").asBoolean()).as("C's Pb paper").isFalse();
            assertThat(banner(client, a.session()).get("show").asBoolean()).as("$.show for A, Ma's owner").isTrue();
        });
    }

    // ---- refusals ---------------------------------------------------------------------------------------------

    @Test
    void aStaleSeenSetAcknowledgesNothingNew() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, a.ma());
            JsonNode drawn = banner(client, a.session());
            raise(context, paper, Urgency.NOW, NEW_REASON);
            String mine = "SELECT count(*) FROM baymax.now_ack WHERE family_id = '" + a.family().id() + "'";
            long before = count(mine);

            JsonNode answer = ack(client, a.session(), drawn.get("seen"));
            assertThat(answer.get("show").asBoolean()).as("the banner changed after it was drawn: the tap saw the old one").isTrue();
            assertThat(count(mine)).as("now_ack rows of this account holder, before and after the stale PUT").isEqualTo(before).isZero();
        });
    }

    @Test
    void aMalformedAckIs400Invalid() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            put(client, a.session(), Map.of("seen", "x")).expectStatus().isBadRequest()
                    .expectBody().json("{\"reason\":\"INVALID\"}", true);
            put(client, a.session(), Map.of("seen", List.of(Map.of("document_id", "not-a-uuid", "urgency", "now",
                    "reasons_digest", "a".repeat(64), "release_state", "none")))).expectStatus().isBadRequest()
                    .expectBody().json("{\"reason\":\"INVALID\"}", true);
            put(client, a.session(), Map.of()).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        });
    }

    /**
     * Master Review Focus 3, the Java half (plan W5-api): দেখেছি tapped after the session ended writes nothing. A PUT with
     * an expired session and one with a signed-out (revoked) token each answer 401 {@code UNAUTHENTICATED}, and the
     * {@code now_ack} count is unchanged; then the same seen set from a live session writes its row, so the zero above was
     * not a seen set that could never have been stored.
     */
    @Test
    void anAckWithoutALiveSessionIs401AndWritesNoAck() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            UUID paper = NowPapers.nowPaper(context, POSTGRES, a.ma());
            JsonNode seen = banner(client, a.session()).get("seen");
            assertThat(seen).as("a real seen set naming the NOW paper").hasSize(1);
            com.elioo.baymax.web.application.port.out.SessionStorePort store =
                    context.getBean(com.elioo.baymax.web.application.port.out.SessionStorePort.class);
            Instant now = Instant.now();
            String expired = "tok-expired-" + UUID.randomUUID();
            store.create(com.elioo.baymax.web.application.service.PhoneHasher.sha256(expired), a.family().id(),
                    now.minus(Duration.ofDays(2)), now.minus(Duration.ofHours(1))).block();
            String signedOut = "tok-signed-out-" + UUID.randomUUID();
            store.create(com.elioo.baymax.web.application.service.PhoneHasher.sha256(signedOut), a.family().id(),
                    now, now.plus(Duration.ofDays(1))).block();
            store.revoke(com.elioo.baymax.web.application.service.PhoneHasher.sha256(signedOut), now).block();
            String acks = "SELECT count(*) FROM baymax.now_ack WHERE document_id = '" + paper + "'";
            long before = count(acks);

            for (Map.Entry<String, String> dead : Map.of("an expired session", expired, "a signed-out token", signedOut).entrySet()) {
                EntityExchangeResult<byte[]> r = put(client, dead.getValue(), Map.of("seen", seen)).expectBody().returnResult();
                assertThat(r.getStatus().value()).as("PUT /app/api/now-banner/ack with " + dead.getKey()).isEqualTo(401);
                assertThat(body(r.getResponseBody())).as("its body").isEqualTo(body("{\"reason\":\"UNAUTHENTICATED\"}".getBytes()));
                assertThat(count(acks)).as("now_ack rows for the paper after the ack with " + dead.getKey()).isEqualTo(before);
            }
            assertThat(before).isZero();
            ack(client, a.session(), seen);
            assertThat(count(acks)).as("the same seen set from a live session").isEqualTo(1);
        });
    }

    @Test
    void anotherFamilysDocumentInSeenIsIgnored() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            Holder third = owner(context);
            UUID theirs = NowPapers.nowPaper(context, POSTGRES, third.ma());
            JsonNode theirSeen = banner(client, third.session()).get("seen");
            assertThat(theirSeen).as("the third family's own banner names its paper").hasSize(1);
            long before = count("SELECT count(*) FROM baymax.now_ack");

            ack(client, a.session(), theirSeen);
            assertThat(count("SELECT count(*) FROM baymax.now_ack")).as("now_ack after A sent the third family's seen entry").isEqualTo(before);
            assertThat(count("SELECT count(*) FROM baymax.now_ack WHERE document_id = '" + theirs + "'")).isZero();
        });
    }

    // ---- abroad -------------------------------------------------------------------------------------------------

    @Test
    void aViewerAbroadGetsNoCall999() {
        runner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Holder a = owner(context);
            Holder abroad = member(context, a.ma(), "+447700900001");
            NowPapers.nowPaper(context, POSTGRES, a.ma());

            assertThat(banner(client, abroad.session()).get("call_999").asBoolean()).as("a UK phone: no tel:999").isFalse();
            assertThat(banner(client, abroad.session()).get("show").asBoolean()).as("the banner still reaches them").isTrue();
            assertThat(banner(client, a.session()).get("call_999").asBoolean()).as("a Bangladeshi phone").isTrue();
        });
    }

    // ---- the MSW fixtures -------------------------------------------------------------------------------------

    /**
     * The four banner states React's MSW handlers serve (W2d-b's NowBanner states read them by this class's name), with
     * fixed ids under a fixed clock, compared byte for byte with the committed
     * {@code medioo-web/fixtures/api/now-banner/*.json}: one NOW paper; two patients each with one; the same paper after
     * the ack; and a viewer abroad (no {@code call_999}).
     */
    @Nested
    class NowBannerApiContractTest {

        static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
        static final java.nio.file.Path COMMITTED = java.nio.file.Path.of("../medioo-web/fixtures/api/now-banner");
        static final java.nio.file.Path WRITTEN = java.nio.file.Path.of("build/web-contract/now-banner");
        static final List<String> CASES = List.of("one", "two-patients", "acked", "abroad");

        @ParameterizedTest
        @ValueSource(strings = {"one", "two-patients", "acked", "abroad"})
        void eachCaseIsItsCommittedMswFixture(String name) {
            FixedPapers.runnerAt(POSTGRES, NOW).run(context -> {
                WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
                String k = "banner-" + name;
                UUID family = FixedPapers.family(POSTGRES, FixedPapers.id(k, "family"), nextNumber(), "Rahim");
                PatientProfile ma = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "ma"), family, "Ma");
                FixedPapers.paper(POSTGRES, FixedPapers.id(k, "ma-now"), ma, "lab_report", "DONE", "2026-10-19",
                        NOW.minus(Duration.ofDays(1)), "now");
                String viewer = FixedPapers.session(context, family, NOW);
                if ("two-patients".equals(name)) {
                    PatientProfile baba = FixedPapers.patient(POSTGRES, FixedPapers.id(k, "baba"), family, "Baba");
                    FixedPapers.paper(POSTGRES, FixedPapers.id(k, "baba-now"), baba, "lab_report", "DONE", null,
                            NOW.minus(Duration.ofHours(2)), "now");
                }
                if ("abroad".equals(name)) {
                    String uk = "+447700900002";
                    UUID sibling = FixedPapers.family(POSTGRES, FixedPapers.id(k, "sibling"), uk, "Sister");
                    FixedPapers.share(POSTGRES, ma, uk);
                    viewer = FixedPapers.session(context, sibling, NOW);
                }
                if ("acked".equals(name)) {
                    ack(client, viewer, banner(client, viewer).get("seen"));
                }
                EntityExchangeResult<byte[]> r = client.get().uri("/app/api/now-banner").cookie("baymax_session", viewer)
                        .exchange().expectBody().returnResult();
                AttentionApiContractTest.write(NowBannerApiContractTest.class, COMMITTED, WRITTEN, "now-banner", name,
                        "GET /app/api/now-banner over the case " + name, r);
            });
        }

        @Test
        void everyCommittedFixtureIsACurrentCase() throws Exception {
            assertThat(AttentionApiContractTest.committed(COMMITTED))
                    .containsExactlyInAnyOrderElementsOf(CASES.stream().map(n -> n + ".json").toList());
        }
    }

    // ---- SQL helpers ------------------------------------------------------------------------------------------

    static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    static String scalar(String query) throws Exception {
        try (Connection c = connect(); java.sql.Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            return rs.getString(1);
        }
    }

    static long count(String query) throws Exception {
        return Long.parseLong(scalar(query));
    }

    static void exec(String sql, Object... args) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            s.executeUpdate();
        }
    }
}
