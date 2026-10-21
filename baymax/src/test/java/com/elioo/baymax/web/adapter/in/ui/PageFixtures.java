package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.PaperDeletion;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelinePage;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.VerifyFailure;
import com.elioo.baymax.web.domain.ViewerLocale;
import com.elioo.baymax.web.domain.WebSession;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * How to call each route with fixed data. Keyed by RouteTable.Route.key() ("GET /app/documents/{id}"). A fixture names
 * the concrete URI, whether it needs a session, a form or multipart body, and the stubs it needs on the kit.
 */
final class PageFixtures {

    enum Body { NONE, FORM, MULTIPART }

    /**
     * One call of a route. {@code name} is empty for a route's main fixture and names a variant otherwise (it becomes
     * part of the snapshot file name); {@code cookies} are sent besides the session cookie {@code signedIn} adds.
     */
    record Fixture(String uri, boolean signedIn, Body body, Map<String, String> form, Map<String, String> cookies,
                   String name, Consumer<WebUiTestKit> stubs) {

        Fixture(String uri, boolean signedIn, Body body, Map<String, String> form, Consumer<WebUiTestKit> stubs) {
            this(uri, signedIn, body, form, Map.of(), "", stubs);
        }

        static Fixture get(String uri, boolean signedIn, Consumer<WebUiTestKit> stubs) {
            return new Fixture(uri, signedIn, Body.NONE, Map.of(), stubs);
        }

        Fixture withCookie(String cookie, String value) {
            Map<String, String> more = new LinkedHashMap<>(cookies);
            more.put(cookie, value);
            return new Fixture(uri, signedIn, body, form, Map.copyOf(more), name, stubs);
        }

        Fixture named(String variant) {
            return new Fixture(uri, signedIn, body, form, cookies, variant, stubs);
        }
    }

    /** The baymax_login cookie POST /app/login sets: the number's HMAC and its last three digits, never the number. */
    static final String LOGIN = "a".repeat(64) + ".000";

    /** One main fixture per route the router publishes. */
    static final Map<String, Fixture> ALL = new LinkedHashMap<>();
    /** Route key → further fixtures of the same route, for a branch the main fixture does not reach. */
    static final Map<String, List<Fixture>> VARIANTS = new LinkedHashMap<>();

    static {
        String doc = WebUiTestKit.DOC_ID.toString();
        String ma = WebUiTestKit.MA_ID.toString();
        put("GET /", Fixture.get("/", false, k -> { }));
        put("GET /lang/{code}", Fixture.get("/lang/en?back=/app/home", false, k -> { }));
        put("GET /assets/{file}", Fixture.get("/assets/baymax.css", false, k -> { }));
        put("GET /baymax", Fixture.get("/baymax", false, k -> { }));
        put("GET /baymax/{*rest}", Fixture.get("/baymax/patients/" + ma + "?x=1", false, k -> { }));
        put("GET /app/", Fixture.get("/app/", false, k -> { }));
        put("GET /app", Fixture.get("/app", false, k -> { }));
        put("GET /app/verify", Fixture.get("/app/verify", false, k -> { }).withCookie("baymax_login", LOGIN));
        variant("GET /app/verify", Fixture.get("/app/verify", false, k -> { }).withCookie("baymax_login", LOGIN + ".s").named("sent"));
        variant("GET /app/verify", Fixture.get("/app/verify", false, k -> { }).named("nocookie"));
        put("GET /app/nudges/opt-out", Fixture.get("/app/nudges/opt-out?p=" + ma + "&t=valid", false, Stubs::validOptOutLink));
        variant("GET /app/nudges/opt-out", Fixture.get("/app/nudges/opt-out?p=" + ma + "&t=not-a-token", false, Stubs::validOptOutLink).named("badtoken"));
        variant("GET /app/nudges/opt-out", Fixture.get("/app/nudges/opt-out?t=not-a-token", false, k -> { }).named("nopatient"));
        put("GET /app/home", Fixture.get("/app/home", true, Stubs::familyOfMa));
        put("GET /app/timeline", Fixture.get("/app/timeline", true, Stubs::maTimeline));
        put("GET /app/family", Fixture.get("/app/family", true, Stubs::maProfile));
        // A5: what a family of two sees — the chooser (none chosen), a chosen patient with the switcher, both cards
        variant("GET /app/home", Fixture.get("/app/home", true, Stubs::familyOfTwo).named("chooser"));
        variant("GET /app/home", Fixture.get("/app/home?patient=" + ma, true, Stubs::familyOfTwo).named("chosen"));
        variant("GET /app/home", Fixture.get("/app/home", true, Stubs::familyOfNone).named("nopatient"));
        variant("GET /app/timeline", Fixture.get("/app/timeline", true, Stubs::maTimelineOfTwo).named("chooser"));
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma, true, Stubs::maTimelineOfTwo).named("chosen"));
        variant("GET /app/family", Fixture.get("/app/family", true, Stubs::familyOfTwo).named("two"));
        // C1: the patient switcher sheet over the tab it was opened from, with no script
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma + "&sheet=patient", true, Stubs::maTimelineOfTwo).named("switcher"));
        // C3 (s16): every kind of row across two months with a next page, the empty panel, a filter that matches nothing and
        // an empty later page, so every derived page check (touch targets, contrast, inline code, digits, upload entry) reads each
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma, true, Stubs::maEveryRow).named("rows"));
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma, true, Stubs::maNoPapers).named("empty"));
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma + "&type=rx", true, Stubs::maNoPapers).named("filtered-empty"));
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma + "&cursor=v1.next-page", true, Stubs::maNoPapers).named("later-empty"));
        variant("GET /app/home", Fixture.get("/app/home?patient=" + ma + "&sheet=patient", true, Stubs::familyOfTwo).named("switcher"));
        // C4: every card the চ্যাট stack draws (NOW, THIS_WEEK, retake, the doctor's follow-up, being read), the empty চ্যাট, and
        // the কাগজপত্র strip, so every derived page check (touch targets, contrast, paper vs Medioo, inline code) reads them
        variant("GET /app/home", Fixture.get("/app/home", true, Stubs::maStack).named("stack"));
        variant("GET /app/home", Fixture.get("/app/home", true, Stubs::maWithNoPapersYet).named("empty"));
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma, true, Stubs::maStackAndPapers).named("strip"));
        // D1: the heaviest কাগজপত্র FirstLoadBudgetTest weighs — a full page of papers (TimelineService.PAGE_SIZE) over three
        // months, a third NOW and a third THIS_WEEK, under a full urgent strip (AttentionService.URGENT_LIMIT)
        variant("GET /app/timeline", Fixture.get("/app/timeline?patient=" + ma, true, Stubs::maFullPageOfPapers).named("heaviest"));
        // D1: the heaviest চ্যাট — a full urgent strip and three of every other card
        variant("GET /app/home", Fixture.get("/app/home", true, Stubs::maFullStack).named("heaviest"));
        // A6: the patient in view as the owner sees them (and after a save), and a shared patient (no save, no switch)
        variant("GET /app/family", Fixture.get("/app/family?patient=" + ma, true, Stubs::familyOfTwo).named("chosen"));
        variant("GET /app/family", Fixture.get("/app/family?patient=" + ma + "&saved=1", true, Stubs::maProfile).named("saved"));
        variant("GET /app/family", Fixture.get("/app/family?patient=" + Stubs.ABBU, true, Stubs::familyOfTwo).named("shared"));
        // A7: the add-report sheet — one patient (chosen), two (none chosen), the cap reached, a view-only member
        put("GET /app/add", Fixture.get("/app/add", true, Stubs::sheetForMa));
        variant("GET /app/add", Fixture.get("/app/add?back=/app/home", true, Stubs::sheetForTwo).named("two"));
        variant("GET /app/add", Fixture.get("/app/add", true, Stubs::sheetAtTheCap).named("cap"));
        variant("GET /app/add", Fixture.get("/app/add", true, Stubs::sheetViewOnly).named("viewonly"));
        // C1: a 302 to কাগজপত্র with the patient chosen; the derived page tests skip non-HTML answers
        put("GET /app/patients/{id}", Fixture.get("/app/patients/" + ma, true, Stubs::maTimeline));
        // A6: a 302 to the পরিবার tab now; the derived page tests skip non-HTML answers
        put("GET /app/patients/{id}/profile", Fixture.get("/app/patients/" + ma + "/profile", true, Stubs::maProfile));
        put("GET /app/documents/{id}/delete", Fixture.get("/app/documents/" + doc + "/delete", true, Stubs::doneLab));
        put("GET /app/documents/{id}/image", Fixture.get("/app/documents/" + doc + "/image?key=f/p/" + doc + "/crop-1.jpg", true, Stubs::signedUrls));
        put("GET /app/documents/{id}/page/{n}", Fixture.get("/app/documents/" + doc + "/page/1", true, Stubs::signedUrls));
        // A9: the reading page's poll answers JSON, so the derived page tests skip it
        put("GET /app/documents/{id}/status", Fixture.get("/app/documents/" + doc + "/status", true, Stubs::processingStatus));
        put("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::doneLab));
        // A9: the reading page — a paper being read, and one just received for a reader abroad (Dhaka time marked)
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::processing).named("reading"));
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::receivedAbroad).named("received"));
        // B3: a NOW paper (s11) — every reason block, as a Bangladeshi number and as a number abroad sees it
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, k -> Stubs.nowLab(k, true)).named("now"));
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, k -> Stubs.nowLab(k, false)).named("nowabroad"));
        // B5: the retake card (s12) — the first, the third in a row, a view-only member — and the failed page
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, k -> Stubs.retake(k, 1, true)).named("retake"));
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, k -> Stubs.retake(k, 3, true)).named("retake3"));
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, k -> Stubs.retake(k, 2, false)).named("retakeviewonly"));
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::failed).named("failed"));
        // B6: the document detail (s17) — a read paper, and a NOW one (critical rows, every reason) — and the value sheet
        // (s13): a value with an earlier reading, one Medioo could not place (no earlier one), and one opened from the detail
        put("GET /app/documents/{id}/all", Fixture.get("/app/documents/" + doc + "/all", true, Stubs::doneLab));
        variant("GET /app/documents/{id}/all", Fixture.get("/app/documents/" + doc + "/all", true, k -> Stubs.nowLab(k, true)).named("now"));
        put("GET /app/documents/{id}/values/{obs}", Fixture.get("/app/documents/" + doc + "/values/" + Stubs.HBA1C + "?from=summary", true, Stubs::valueSheet));
        variant("GET /app/documents/{id}/values/{obs}", Fixture.get("/app/documents/" + doc + "/values/" + Stubs.CREATININE, true, Stubs::valueSheet).named("unknown"));
        variant("GET /app/documents/{id}/values/{obs}", Fixture.get("/app/documents/" + doc + "/values/" + Stubs.HBA1C + "?from=all", true, Stubs::valueSheet).named("fromall"));
        // D1: the heaviest document pages FirstLoadBudgetTest weighs, sized from the largest labelled documents in the testset
        // (docs/testset, 2026-10-20: a lab report of 29 values, batch2/lab2; a prescription of 10 medicines and 13 doctor's
        // lines, expected/4 and expected/2) at twice that, and a value sheet with every earlier reading the service lists
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::heaviestLab).named("heaviest"));
        variant("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::heaviestPrescription).named("heaviestrx"));
        variant("GET /app/documents/{id}/all", Fixture.get("/app/documents/" + doc + "/all", true, Stubs::heaviestLab).named("heaviest"));
        variant("GET /app/documents/{id}/all", Fixture.get("/app/documents/" + doc + "/all", true, Stubs::heaviestPrescription).named("heaviestrx"));
        variant("GET /app/documents/{id}/values/{obs}", Fixture.get("/app/documents/" + doc + "/values/" + Stubs.HBA1C + "?from=summary", true,
                Stubs::valueSheetWithEveryEarlierReading).named("heaviest"));
        put("POST /app/login", new Fixture("/app/login", false, Body.FORM, Map.of("whatsapp_number", "01700000000", "confirmed", "1"), Stubs::noCode));
        variant("POST /app/login", new Fixture("/app/login", false, Body.FORM, Map.of("whatsapp_number", "01700000000"), Stubs::noCode).named("confirm"));
        variant("POST /app/login", new Fixture("/app/login", false, Body.FORM, Map.of("whatsapp_number", "12"), k -> { }).named("invalid"));
        put("POST /app/verify", new Fixture("/app/verify", false, Body.FORM, Map.of("code", "123456"), Stubs::noCode).withCookie("baymax_login", LOGIN));
        variant("POST /app/verify", new Fixture("/app/verify", false, Body.FORM, Map.of("code", "123456"), Stubs::codeAccepted)
                .withCookie("baymax_login", LOGIN).named("accepted"));
        put("POST /app/logout", new Fixture("/app/logout", true, Body.FORM, Map.of(), Stubs::logsOut));
        put("POST /app/nudges/opt-out", new Fixture("/app/nudges/opt-out", false, Body.FORM, Map.of("p", ma, "t", "valid", "scope", "patient"), Stubs::validOptOutLink));
        variant("POST /app/nudges/opt-out", new Fixture("/app/nudges/opt-out", false, Body.FORM, Map.of("p", ma, "t", "valid", "scope", "family"), Stubs::validOptOutLink).named("family"));
        variant("POST /app/nudges/opt-out", new Fixture("/app/nudges/opt-out", false, Body.FORM, Map.of("p", ma, "t", "not-a-token", "scope", "patient"), Stubs::validOptOutLink).named("badtoken"));
        // A8: the sheet's submit — accepted (303 to the paper), no patient chosen, and a refusal for the chosen patient
        put("POST /app/add", new Fixture("/app/add", true, Body.MULTIPART, Map.of("patient", ma), Stubs::acceptsUpload));
        variant("POST /app/add", new Fixture("/app/add", true, Body.MULTIPART, Map.of(), Stubs::sheetForTwo).named("nopatient"));
        variant("POST /app/add", new Fixture("/app/add", true, Body.MULTIPART, Map.of("patient", ma, "back", "/app/timeline?patient=" + ma),
                Stubs::refusesUpload).named("refused"));
        put("POST /app/patients/{id}/upload", new Fixture("/app/patients/" + ma + "/upload", true, Body.MULTIPART, Map.of(), Stubs::acceptsUpload));
        put("POST /app/patients/{id}/profile", new Fixture("/app/patients/" + ma + "/profile", true, Body.FORM, Map.of("flag", "diabetes"), Stubs::maProfile));
        put("POST /app/patients/{id}/nudges/opt-out", new Fixture("/app/patients/" + ma + "/nudges/opt-out", true, Body.FORM, Map.of("scope", "patient"), Stubs::maProfile));
        put("POST /app/documents/{id}/delete", new Fixture("/app/documents/" + doc + "/delete", true, Body.FORM, Map.of(), Stubs::deletes));
    }

    private PageFixtures() {
    }

    static void put(String key, Fixture f) {
        if (ALL.put(key, f) != null) {
            throw new IllegalStateException("two fixtures for " + key);
        }
    }

    static void variant(String key, Fixture f) {
        if (f.name().isEmpty()) {
            throw new IllegalStateException("a variant of " + key + " needs a name");
        }
        List<Fixture> list = VARIANTS.computeIfAbsent(key, x -> new java.util.ArrayList<>());
        if (list.stream().anyMatch(v -> v.name().equals(f.name()))) {
            throw new IllegalStateException("two variants named " + f.name() + " for " + key);
        }
        list.add(f);
    }

    /** The route's main fixture, then its variants. */
    static List<Fixture> allOf(RouteTable.Route route) {
        List<Fixture> all = new java.util.ArrayList<>();
        all.add(of(route));
        all.addAll(VARIANTS.getOrDefault(route.key(), List.of()));
        return all;
    }

    static Fixture of(RouteTable.Route route) {
        Fixture f = ALL.get(route.key());
        if (f == null) {
            throw new AssertionError("route " + route.key() + " has no fixture in PageFixtures: add one in the PR that adds the route");
        }
        return f;
    }

    static HttpMethod method(String key) {
        return HttpMethod.valueOf(key.substring(0, key.indexOf(' ')));
    }

    /** The stubs each fixture needs: exactly what its page calls on the kit's mocks, with fixed values. */
    static final class Stubs {

        static final UUID DONE_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100004");
        static final UUID PROCESSING_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100005");
        /** আব্বু's id, from the kit's own derivation: the shared patient of {@link #familyOfTwo}. */
        static final UUID ABBU = WebUiTestKit.patientId("আব্বু");
        /** {@link #doneLab}'s two observations: HbA1c (stored OUTSIDE, high) and Creatinine (a stored INSIDE drawn UNKNOWN). */
        static final UUID HBA1C = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100021");
        static final UUID CREATININE = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100022");
        /** The earlier paper {@link #valueSheet} lists HbA1c from. */
        static final UUID EARLIER_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100023");

        private Stubs() {
        }

        static void familyOfMa(WebUiTestKit k) {
            k.familyWith(k.ma(3));
        }

        /** Ma (owned) and আব্বু (shared with this family): the A5 chooser, switcher and two-card states. */
        static void familyOfTwo(WebUiTestKit k) {
            k.familyWith(k.ma(3), k.patient("আব্বু", false, 1));
        }

        static void familyOfNone(WebUiTestKit k) {
            k.familyWith();
        }

        static void maTimelineOfTwo(WebUiTestKit k) {
            familyOfTwo(k);
            maPapers(k);
        }

        static void maTimeline(WebUiTestKit k) {
            familyOfMa(k);
            maPapers(k);
        }

        private static void maPapers(WebUiTestKit k) {
            when(k.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                    new TimelineEntry(DONE_DOC, LocalDate.parse("2026-10-12"), false, "lab_report", "Shapla Diagnostics", "DONE",
                            // C2: a stored NOW, one value outside and a pending review, so every derived page check
                            // (touch targets, contrast, inline code, Bangla digits) sees the chip and the review chip
                            14, 2, 1, 3, "f/p/" + DONE_DOC + "/page-1.jpg", WebUiTestKit.NOW,
                            com.elioo.baymax.outbound.domain.Urgency.NOW, 1, "pending", null),
                    new TimelineEntry(PROCESSING_DOC, LocalDate.parse("2026-10-18"), true, null, null, "PROCESSING",
                            // still being read: not assessed, nothing counted, no review yet, no retake code
                            0, 0, 0, 0, "f/p/" + PROCESSING_DOC + "/page-1.jpg", WebUiTestKit.NOW, null, 0, null, null)), null)));
        }

        /** C4: one card of each kind on Ma's চ্যাট stack, in the service's order (the follow-up carries its crop), and its urgent two for the strip. */
        static void maStack(WebUiTestKit k) {
            familyOfMa(k);
            java.time.Instant sent = WebUiTestKit.NOW;
            when(k.attention.attention(WebUiTestKit.FAMILY, WebUiTestKit.MA_ID)).thenReturn(Mono.just(List.of(
                    new com.elioo.baymax.web.domain.AttentionItem.Urgent(DONE_DOC, Urgency.NOW, "lab_report", LocalDate.parse("2026-10-12"), false, sent),
                    new com.elioo.baymax.web.domain.AttentionItem.Urgent(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100031"), Urgency.THIS_WEEK,
                            null, LocalDate.parse("2026-10-15"), true, sent),
                    new com.elioo.baymax.web.domain.AttentionItem.Retake(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100032"), sent),
                    new com.elioo.baymax.web.domain.AttentionItem.FollowUp(EARLIER_DOC, "Next visit: 20/10/26", LocalDate.parse("2026-10-20"),
                            "f/p/" + EARLIER_DOC + "/crop-f.jpg"),
                    new com.elioo.baymax.web.domain.AttentionItem.Reading(PROCESSING_DOC, sent))));
            when(k.attention.urgent(WebUiTestKit.FAMILY, WebUiTestKit.MA_ID)).thenReturn(Mono.just(List.of(
                    new com.elioo.baymax.web.domain.AttentionItem.Urgent(DONE_DOC, Urgency.NOW, "lab_report", LocalDate.parse("2026-10-12"), false, sent),
                    new com.elioo.baymax.web.domain.AttentionItem.Urgent(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100031"), Urgency.THIS_WEEK,
                            null, LocalDate.parse("2026-10-15"), true, sent))));
        }

        /** C4: Ma before her first paper — the empty চ্যাট (the intro and the one camera). */
        static void maWithNoPapersYet(WebUiTestKit k) {
            k.familyWith(k.ma(0));
        }

        /** C4: Ma's papers under the strip of her urgent ones. */
        static void maStackAndPapers(WebUiTestKit k) {
            maStack(k);
            maPapers(k);
        }

        /**
         * D1: a full page of read papers of Ma's (TimelineService.PAGE_SIZE) with a next page — a third NOW, a third
         * THIS_WEEK, the rest routine prescriptions — under a full urgent strip (AttentionService.URGENT_LIMIT): the most
         * one page of কাগজপত্র draws, for the first-load budget.
         */
        static void maFullPageOfPapers(WebUiTestKit k) {
            familyOfMa(k);
            when(k.attention.urgent(WebUiTestKit.FAMILY, WebUiTestKit.MA_ID)).thenReturn(Mono.just(fullUrgentStrip()));
            List<TimelineEntry> entries = new java.util.ArrayList<>();
            for (int i = 0; i < com.elioo.baymax.web.application.service.TimelineService.PAGE_SIZE; i++) {
                Urgency level = i % 3 == 0 ? Urgency.NOW : i % 3 == 1 ? Urgency.THIS_WEEK : Urgency.ROUTINE;
                boolean lab = level != Urgency.ROUTINE;
                UUID id = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41001%02d".formatted(i));
                entries.add(new TimelineEntry(id, LocalDate.parse("2026-10-18").minusDays(4L * i), false, lab ? "lab_report" : "prescription",
                        lab ? "Shapla Diagnostics" : null, "DONE", lab ? 14 : 0, lab ? 0 : 3, 1, 0, "f/p/" + id + "/page-1.jpg",
                        WebUiTestKit.NOW, level, lab ? 2 : 0, "released", null));
            }
            when(k.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), any(), any()))
                    .thenReturn(Mono.just(new TimelinePage(List.copyOf(entries), "v1.next-page")));
        }

        /** D1: AttentionService.URGENT_LIMIT urgent papers, NOW first, then THIS_WEEK, each named by its type and date. */
        static List<com.elioo.baymax.web.domain.AttentionItem.Urgent> fullUrgentStrip() {
            List<com.elioo.baymax.web.domain.AttentionItem.Urgent> urgent = new java.util.ArrayList<>();
            for (int i = 0; i < com.elioo.baymax.web.application.service.AttentionService.URGENT_LIMIT; i++) {
                urgent.add(new com.elioo.baymax.web.domain.AttentionItem.Urgent(
                        UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41002%02d".formatted(i)), i < 5 ? Urgency.NOW : Urgency.THIS_WEEK,
                        i % 2 == 0 ? "lab_report" : "prescription", LocalDate.parse("2026-10-18").minusDays(i), i % 3 == 0,
                        WebUiTestKit.NOW.minusSeconds(3600L * i)));
            }
            return List.copyOf(urgent);
        }

        /** D1: the heaviest চ্যাট — a full urgent strip, then three retakes, three of the doctor's follow-ups and three being read. */
        static void maFullStack(WebUiTestKit k) {
            familyOfMa(k);
            List<com.elioo.baymax.web.domain.AttentionItem> stack = new java.util.ArrayList<>(fullUrgentStrip());
            for (int i = 0; i < 3; i++) {
                stack.add(new com.elioo.baymax.web.domain.AttentionItem.Retake(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41003%02d".formatted(i)),
                        WebUiTestKit.NOW.minusSeconds(600L * i)));
            }
            for (int i = 0; i < 3; i++) {
                UUID d = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41004%02d".formatted(i));
                stack.add(new com.elioo.baymax.web.domain.AttentionItem.FollowUp(d, "Next visit after 7 days with all reports",
                        LocalDate.parse("2026-10-20").plusDays(i), "f/p/" + d + "/crop-f.jpg"));
            }
            for (int i = 0; i < 3; i++) {
                stack.add(new com.elioo.baymax.web.domain.AttentionItem.Reading(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41005%02d".formatted(i)),
                        WebUiTestKit.NOW.minusSeconds(30L * i)));
            }
            when(k.attention.attention(WebUiTestKit.FAMILY, WebUiTestKit.MA_ID)).thenReturn(Mono.just(List.copyOf(stack)));
            when(k.attention.urgent(WebUiTestKit.FAMILY, WebUiTestKit.MA_ID)).thenReturn(Mono.just(fullUrgentStrip()));
        }

        /** Marker names of a full panel as labs print them, so a row weighs what a real row weighs. */
        private static final List<String> MARKERS = List.of("Haemoglobin", "Total WBC Count", "Neutrophils", "Lymphocytes", "Monocytes",
                "Eosinophils", "Basophils", "Total RBC Count", "Haematocrit (PCV)", "MCV", "MCH", "MCHC", "RDW-CV", "Platelet Count",
                "ESR (Westergren)", "Fasting Blood Sugar", "HbA1c", "Serum Creatinine", "Blood Urea Nitrogen", "Uric Acid",
                "Total Cholesterol", "Triglycerides", "HDL Cholesterol", "LDL Cholesterol", "SGPT (ALT)", "SGOT (AST)",
                "Alkaline Phosphatase", "Total Bilirubin", "Serum TSH");

        /**
         * D1: a read two-page lab report of twice the testset's largest (2 x 29 values): every third outside the printed
         * range (high and low in turn), one in six that Medioo could not place, the rest inside, each with its crop, a
         * follow-up and a doctor's line — stored THIS_WEEK and released, so the summary draws every outside and unknown row.
         */
        static void heaviestLab(WebUiTestKit k) {
            String crop = "f/p/" + WebUiTestKit.DOC_ID + "/crop-";
            List<Map<String, Object>> values = new java.util.ArrayList<>();
            for (int i = 0; i < 2 * 29; i++) {
                Map<String, Object> v = new java.util.LinkedHashMap<>();
                v.put("id", UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41006%02d".formatted(i)));
                v.put("name", MARKERS.get(i % MARKERS.size()));
                v.put("canonical_name", "marker_" + i);
                v.put("unit", "mg/dL");
                v.put("ref_low", "70");
                v.put("ref_high", "110");
                if (i % 6 == 5) {
                    v.put("value", "1+");
                    v.put("verdict", "unknown");
                } else if (i % 3 == 0) {
                    v.put("value", i % 2 == 0 ? "182.4" : "51.6");
                    v.put("verdict", "outside");
                    v.put("verdict_side", i % 2 == 0 ? "high" : "low");
                } else {
                    v.put("value", "96.2");
                    v.put("verdict", "inside");
                }
                v.put("crop_key", crop + "v" + i + ".jpg");
                values.add(Map.copyOf(v));
            }
            result(k, new DocumentView(WebUiTestKit.DOC_ID.toString(), "DONE", null, "lab_report", "2026-10-12",
                    "Shapla Diagnostics", 0.9, "m", 2, List.copyOf(values), List.of(),
                    List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-11-15", "crop_key", crop + "f.jpg")),
                    Map.of("diagnosis", List.of(Map.of("text", "Type 2 diabetes", "crop_key", crop + "d.jpg"))), null));
        }

        /**
         * D1: a read prescription of twice the testset's largest — 20 medicines and 26 doctor's lines across the context
         * sections — with two follow-ups, stored THIS_WEEK and released.
         */
        static void heaviestPrescription(WebUiTestKit k) {
            String crop = "f/p/" + WebUiTestKit.DOC_ID + "/crop-";
            List<Map<String, Object>> medicines = new java.util.ArrayList<>();
            for (int i = 0; i < 2 * 10; i++) {
                medicines.add(Map.of("name", "Tab. Metformin " + (i + 1), "dose_text", "500 mg", "frequency_text", "1+0+1",
                        "timing_text", "after meals", "duration_text", "30 days", "crop_key", crop + "m" + i + ".jpg"));
            }
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            String[] sections = {"chief_complaint", "diagnosis", "investigations_advised", "advice"};
            for (int s = 0; s < sections.length; s++) {
                List<Map<String, Object>> lines = new java.util.ArrayList<>();
                for (int i = s; i < 2 * 13; i += sections.length) {
                    lines.add(Map.of("text", "Burning sensation in chest after meals for 3 weeks", "crop_key", crop + "c" + i + ".jpg"));
                }
                context.put(sections[s], List.copyOf(lines));
            }
            result(k, new DocumentView(WebUiTestKit.DOC_ID.toString(), "DONE", null, "prescription", "2026-10-12",
                    "Dr. Rahman's Chamber", 0.9, "m", 2, List.of(), List.copyOf(medicines),
                    List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-11-15", "crop_key", crop + "f1.jpg"),
                            Map.of("instruction", "১৫ দিন পর রিপোর্টসহ আসবেন", "due_date", "2026-11-01", "crop_key", crop + "f2.jpg")),
                    Map.copyOf(context), null));
        }

        /** A read paper of Ma's whose page is the result summary, stored THIS_WEEK and released (as {@link #doneLab}). */
        private static void result(WebUiTestKit k, DocumentView view) {
            when(k.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.DONE));
            when(k.results.result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(new ResultView(view,
                    k.ma(3).patient(), true, new ViewerLocale(true), Urgency.THIS_WEEK, List.of("diagnosis_present"),
                    GateState.RELEASED, "রিপোর্টটি পেয়েছি। এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।", 0, 2,
                    WebUiTestKit.NOW.minusSeconds(3600), WebUiTestKit.NOW, com.elioo.baymax.testsupport.PaperFixtures.filedAsStored(view, WebUiTestKit.NOW.minusSeconds(3600)))));
        }

        /** D1: {@link #doneLab}'s HbA1c sheet with every earlier reading the service lists (ResultService.EARLIER_READINGS). */
        static void valueSheetWithEveryEarlierReading(WebUiTestKit k) {
            doneLab(k);
            List<com.elioo.baymax.web.domain.EarlierReading> earlier = new java.util.ArrayList<>();
            for (int i = 0; i < com.elioo.baymax.web.application.service.ResultService.EARLIER_READINGS; i++) {
                UUID d = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41007%02d".formatted(i));
                earlier.add(new com.elioo.baymax.web.domain.EarlierReading(d, UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41008%02d".formatted(i)),
                        "7." + i, "%", "f/p/" + d + "/crop-1.jpg", LocalDate.parse("2026-09-12").minusMonths(i), i % 2 == 0));
            }
            when(k.results.earlierReadings(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID, HBA1C)).thenReturn(reactor.core.publisher.Flux.fromIterable(earlier));
        }

        static void maNoPapers(WebUiTestKit k) {
            familyOfMa(k);
            when(k.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), any(), any()))
                    .thenReturn(Mono.just(new TimelinePage(List.of(), null)));
        }

        /** C3: one of each row the list draws — THIS_WEEK, a prescription by its upload date, a retake, a failure, one received. */
        static void maEveryRow(WebUiTestKit k) {
            familyOfMa(k);
            java.time.Instant sent = WebUiTestKit.NOW;
            when(k.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                    new TimelineEntry(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100031"), LocalDate.parse("2026-10-15"), false, "lab_report",
                            "Shapla Diagnostics", "DONE", 9, 0, 0, 0, "k", sent, com.elioo.baymax.outbound.domain.Urgency.THIS_WEEK, 2, "released", null),
                    new TimelineEntry(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100032"), LocalDate.parse("2026-10-10"), true, null,
                            null, "NEEDS_RETAKE", 0, 0, 0, 0, "k", sent, null, 0, null, "expected_section_empty:values"),
                    new TimelineEntry(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100033"), LocalDate.parse("2026-10-09"), true, null,
                            null, "FAILED", 0, 0, 0, 0, "k", sent, null, 0, null, null),
                    new TimelineEntry(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100034"), LocalDate.parse("2026-10-08"), true, null,
                            null, "RECEIVED", 0, 0, 0, 0, "k", sent, null, 0, null, null),
                    new TimelineEntry(UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100035"), LocalDate.parse("2026-09-21"), true, "prescription",
                            null, "DONE", 0, 2, 0, 0, "k", sent, com.elioo.baymax.outbound.domain.Urgency.ROUTINE, 0, "released", null)),
                    "v1.next-page")));
        }

        static void maProfile(WebUiTestKit k) {
            familyOfMa(k);
            when(k.nudgeData.patient(WebUiTestKit.MA_ID)).thenReturn(Mono.just(new NudgeDataPort.PatientRef(
                    WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, List.of(), null, null)));
            when(k.timeline.updateChronicFlags(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), anyList()))
                    .thenReturn(Mono.just(k.ma(3).patient()));
            when(k.optOut.optOutPatient(WebUiTestKit.MA_ID)).thenReturn(Mono.empty());
        }

        static void doneLab(WebUiTestKit k) {
            String crop = "f/p/" + WebUiTestKit.DOC_ID + "/crop-";
            DocumentView view = new DocumentView(WebUiTestKit.DOC_ID.toString(), "DONE", null, "lab_report", "2026-10-12",
                    "Shapla Diagnostics", 0.9, "m", 1,
                    // B2: each value carries its observation id and its stored F2 verdict, as ObservationEntity.toView gives them
                    List.of(Map.of("id", UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100021"), "name", "HbA1c", "canonical_name", "hba1c", "value", "8.2", "unit", "%",
                                    "ref_low", "4.0", "ref_high", "6.0", "verdict", "outside", "verdict_side", "high", "crop_key", crop + "1.jpg"),
                            Map.of("id", UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100022"), "name", "Creatinine", "canonical_name", "creatinine", "value", "130", "unit", "µmol/L",
                                    "ref_low", "59", "ref_high", "104", "verdict", "inside", "crop_key", crop + "2.jpg")),
                    List.of(Map.of("name", "Metformin", "dose_text", "500 mg", "frequency_text", "1+0+1",
                            "timing_text", "after meals", "duration_text", "30 days", "crop_key", crop + "3.jpg")),
                    List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-11-15", "crop_key", crop + "4.jpg")),
                    // B2: a doctor's line with its crop, so the derived PaperIsNeverMediooTest sees a paper block
                    Map.of("diagnosis", List.of(Map.of("text", "Type 2 diabetes", "crop_key", crop + "5.jpg"))), null);
            when(k.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.DONE));
            // A12: a DONE paper's page is the result summary, read through ResultUseCase.result (stored THIS_WEEK, released)
            when(k.results.result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(new ResultView(view,
                    k.ma(3).patient(), true, new ViewerLocale(true), Urgency.THIS_WEEK, List.of("diagnosis_present"),
                    GateState.RELEASED, "রিপোর্টটি পেয়েছি। এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।", 0, 2,
                    WebUiTestKit.NOW.minusSeconds(3600), WebUiTestKit.NOW, com.elioo.baymax.testsupport.PaperFixtures.filedAsStored(view, WebUiTestKit.NOW.minusSeconds(3600)))));
        }

        /** B6: {@link #doneLab}, with one earlier HbA1c (upload-day date) and no earlier Creatinine. */
        static void valueSheet(WebUiTestKit k) {
            doneLab(k);
            when(k.results.earlierReadings(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID, HBA1C)).thenReturn(reactor.core.publisher.Flux.just(
                    new com.elioo.baymax.web.domain.EarlierReading(EARLIER_DOC, UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100024"),
                            "7.9", "%", "f/p/" + EARLIER_DOC + "/crop-1.jpg", LocalDate.parse("2026-07-12"), true)));
            when(k.results.earlierReadings(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID, CREATININE)).thenReturn(reactor.core.publisher.Flux.empty());
        }

        /**
         * B3: a two-page NOW lab report with one stored reason of every kind the rule raises and one it no longer knows,
         * so the derived page tests (contrast, targets, inline code, the paper label) read every block s11 draws.
         */
        static void nowLab(WebUiTestKit k, boolean bangladesh) {
            String crop = "f/p/" + WebUiTestKit.DOC_ID + "/crop-";
            DocumentView view = new DocumentView(WebUiTestKit.DOC_ID.toString(), "DONE", null, "lab_report", "2026-10-12",
                    "Shapla Diagnostics", 0.9, "m", 2,
                    List.of(Map.of("id", UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100031"), "name", "Potassium", "value", "11.0",
                                    "unit", "mmol/L", "ref_low", "3.5", "ref_high", "5.1", "verdict", "critical", "verdict_side", "high",
                                    "crop_key", crop + "1.jpg"),
                            Map.of("id", UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100032"), "name", "Sodium", "value", "131",
                                    "unit", "mmol/L", "ref_low", "135", "ref_high", "145", "verdict", "outside", "verdict_side", "low",
                                    "crop_key", crop + "2.jpg")),
                    List.of(),
                    List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-10-01", "crop_key", crop + "4.jpg")),
                    Map.of("diagnosis", List.of(Map.of("text", "CKD", "crop_key", crop + "5.jpg"))), null);
            List<String> reasons = new java.util.ArrayList<>();
            for (com.elioo.baymax.outbound.domain.UrgencyReason r : com.elioo.baymax.outbound.domain.UrgencyReason.values()) {
                reasons.add(r.of("potassium", null));
            }
            reasons.add("retired_rule_2025:potassium");
            when(k.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.DONE));
            when(k.results.result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(new ResultView(view,
                    k.ma(3).patient(), true, new ViewerLocale(bangladesh), Urgency.NOW, reasons, GateState.PENDING, null, 0, 2,
                    WebUiTestKit.NOW.minusSeconds(3600), WebUiTestKit.NOW, com.elioo.baymax.testsupport.PaperFixtures.filedAsStored(view, WebUiTestKit.NOW.minusSeconds(3600)))));
        }

        /** B5: a two-page NEEDS_RETAKE paper of Ma's (low_overall), the {@code ordinal}-th in a row; {@code owner} false is view-only. */
        static void retake(WebUiTestKit k, int ordinal, boolean owner) {
            finished(k, Document.Status.NEEDS_RETAKE, "low_overall", ordinal, owner);
        }

        /** B5: a one-page FAILED paper of Ma's, seen by its owner. */
        static void failed(WebUiTestKit k) {
            finished(k, Document.Status.FAILED, null, 0, true);
        }

        private static void finished(WebUiTestKit k, Document.Status status, String code, int ordinal, boolean owner) {
            Document d = Document.received(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, code == null ? 1 : 2, WebUiTestKit.NOW.minusSeconds(600))
                    .withId(WebUiTestKit.DOC_ID).withStatus(status, "test", code, WebUiTestKit.NOW.minusSeconds(540));
            when(k.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(status));
            when(k.results.result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(new ResultView(DocumentView.pending(d),
                    k.ma(3).patient(), owner, new ViewerLocale(true), null, List.of(), GateState.NONE, null, ordinal, 2,
                    WebUiTestKit.NOW.minusSeconds(600), WebUiTestKit.NOW, com.elioo.baymax.testsupport.PaperFixtures.filedAsStored(DocumentView.pending(d), WebUiTestKit.NOW.minusSeconds(600)))));
        }

        /** A9: a two-page paper of Ma's being read, sent 30 s ago, seen from a Bangladeshi number. */
        static void processing(WebUiTestKit k) {
            reading(k, Document.Status.PROCESSING, true);
        }

        /** A9: the same paper just received, seen from a number abroad. */
        static void receivedAbroad(WebUiTestKit k) {
            reading(k, Document.Status.RECEIVED, false);
        }

        private static void reading(WebUiTestKit k, Document.Status status, boolean bangladesh) {
            java.time.Instant sent = WebUiTestKit.NOW.minusSeconds(30);
            Document d = Document.received(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, 2, sent).withId(WebUiTestKit.DOC_ID);
            Document at = status == Document.Status.RECEIVED ? d : d.withStatus(status, null, WebUiTestKit.NOW);
            when(k.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(status));
            when(k.results.reading(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(new ReadingView(
                    DocumentView.pending(at), k.ma(3).patient(), true, new ViewerLocale(bangladesh), sent, WebUiTestKit.NOW)));
        }

        static void processingStatus(WebUiTestKit k) {
            when(k.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.PROCESSING));
        }

        static void sheetForMa(WebUiTestKit k) {
            when(k.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(k.ma(3)), List.of(), allowance(1))));
        }

        static void sheetForTwo(WebUiTestKit k) {
            when(k.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(
                    List.of(k.ma(3), k.patient("আব্বু", true, 1)), List.of(), allowance(0))));
        }

        static void sheetAtTheCap(WebUiTestKit k) {
            when(k.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(k.ma(3)), List.of(), allowance(3))));
        }

        static void sheetViewOnly(WebUiTestKit k) {
            when(k.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(
                    List.of(), List.of(k.patient("আব্বু", false, 1)), allowance(0))));
        }

        private static DocumentAllowance allowance(long used) {
            return new DocumentAllowance(true, 3, used, java.time.YearMonth.of(2026, 10));
        }

        static void signedUrls(WebUiTestKit k) {
            when(k.timeline.imageUrl(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.DOC_ID), anyString()))
                    .thenReturn(Mono.just(URI.create("https://storage.example/signed")));
            when(k.timeline.pageUrl(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.DOC_ID), anyInt()))
                    .thenReturn(Mono.just(URI.create("https://storage.example/signed")));
        }

        static void noCode(WebUiTestKit k) {
            when(k.auth.requestCode(anyString())).thenReturn(Mono.just(new CodeRequest("a".repeat(64), CodeIssue.OUTSIDE_WINDOW)));
            when(k.auth.verify(anyString(), anyString())).thenReturn(Mono.error(new CodeRejectedException(VerifyFailure.WRONG)));
        }

        static void codeAccepted(WebUiTestKit k) {
            when(k.auth.verify(PageFixtures.LOGIN.substring(0, 64), "123456"))
                    .thenReturn(Mono.just(new IssuedSession("fresh-session-token", new WebSession(
                            UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100010"), WebUiTestKit.FAMILY, WebUiTestKit.NOW,
                            WebUiTestKit.NOW, WebUiTestKit.NOW.plusSeconds(600)))));
        }

        static void validOptOutLink(WebUiTestKit k) {
            when(k.optOut.tokenValid(WebUiTestKit.MA_ID, "valid")).thenReturn(true);
            when(k.optOut.optOutPatient(WebUiTestKit.MA_ID)).thenReturn(Mono.empty());
            when(k.optOut.optOutFamily(WebUiTestKit.MA_ID)).thenReturn(Mono.empty());
        }

        static void logsOut(WebUiTestKit k) {
            when(k.auth.logout("tok")).thenReturn(Mono.empty());
        }

        static void acceptsUpload(WebUiTestKit k) {
            when(k.uploads.upload(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), anyList(), any(), any())).thenReturn(Mono.just(List.of(
                    Document.received(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, 1, WebUiTestKit.NOW).withId(WebUiTestKit.DOC_ID))));
        }

        static void refusesUpload(WebUiTestKit k) {
            sheetForMa(k);
            when(k.uploads.upload(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), anyList(), any(), any())).thenReturn(Mono.error(
                    com.elioo.baymax.common.error.BaymaxException.badRequest("unsupported_image", "could not read page-1.jpg; send a JPEG, PNG or PDF")));
        }

        static void deletes(WebUiTestKit k) {
            when(k.timeline.deleteDocument(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID))
                    .thenReturn(Mono.just(new PaperDeletion("f/p/" + WebUiTestKit.DOC_ID + "/", 4, 1, 9)));
        }
    }
}
