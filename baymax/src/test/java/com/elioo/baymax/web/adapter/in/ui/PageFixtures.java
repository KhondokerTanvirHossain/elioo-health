package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelinePage;
import com.elioo.baymax.web.domain.VerifyFailure;
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
        // A6: the patient in view as the owner sees them (and after a save), and a shared patient (no save, no switch)
        variant("GET /app/family", Fixture.get("/app/family?patient=" + ma, true, Stubs::familyOfTwo).named("chosen"));
        variant("GET /app/family", Fixture.get("/app/family?patient=" + ma + "&saved=1", true, Stubs::maProfile).named("saved"));
        variant("GET /app/family", Fixture.get("/app/family?patient=" + Stubs.ABBU, true, Stubs::familyOfTwo).named("shared"));
        put("GET /app/patients/{id}", Fixture.get("/app/patients/" + ma, true, Stubs::maTimeline));
        // A6: a 302 to the পরিবার tab now; the derived page tests skip non-HTML answers
        put("GET /app/patients/{id}/profile", Fixture.get("/app/patients/" + ma + "/profile", true, Stubs::maProfile));
        put("GET /app/documents/{id}/delete", Fixture.get("/app/documents/" + doc + "/delete", true, Stubs::doneLab));
        put("GET /app/documents/{id}/image", Fixture.get("/app/documents/" + doc + "/image?key=f/p/" + doc + "/crop-1.jpg", true, Stubs::signedUrls));
        put("GET /app/documents/{id}/page/{n}", Fixture.get("/app/documents/" + doc + "/page/1", true, Stubs::signedUrls));
        put("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::doneLab));
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
            when(k.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                    new TimelineEntry(DONE_DOC, LocalDate.parse("2026-10-12"), false, "lab_report", "Shapla Diagnostics", "DONE",
                            14, 2, 1, 3, "f/p/" + DONE_DOC + "/page-1.jpg", WebUiTestKit.NOW),
                    new TimelineEntry(PROCESSING_DOC, LocalDate.parse("2026-10-18"), true, null, null, "PROCESSING",
                            0, 0, 0, 0, "f/p/" + PROCESSING_DOC + "/page-1.jpg", WebUiTestKit.NOW)), null)));
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
                    List.of(Map.of("name", "HbA1c", "value", "8.2", "unit", "%", "crop_key", crop + "1.jpg"),
                            Map.of("name", "Creatinine", "value", "130", "unit", "µmol/L", "crop_key", crop + "2.jpg")),
                    List.of(Map.of("name", "Metformin", "dose_text", "500 mg", "frequency_text", "1+0+1",
                            "timing_text", "after meals", "duration_text", "30 days", "crop_key", crop + "3.jpg")),
                    List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-11-15", "crop_key", crop + "4.jpg")),
                    null, null);
            when(k.timeline.document(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(view));
            when(k.timeline.explanationOf(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID))
                    .thenReturn(Mono.just("রিপোর্টটি পেয়েছি। এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।"));
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
            when(k.timeline.upload(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), anyList())).thenReturn(Mono.just(
                    Document.received(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, 1, WebUiTestKit.NOW).withId(WebUiTestKit.DOC_ID)));
        }

        static void deletes(WebUiTestKit k) {
            when(k.timeline.deleteDocument(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID))
                    .thenReturn(Mono.just(new DeletionReport("f/p/" + WebUiTestKit.DOC_ID + "/", 4, 1)));
        }
    }
}
