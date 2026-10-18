package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelinePage;
import com.elioo.baymax.web.domain.VerifyFailure;
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

    record Fixture(String uri, boolean signedIn, Body body, Map<String, String> form, Consumer<WebUiTestKit> stubs) {
        static Fixture get(String uri, boolean signedIn, Consumer<WebUiTestKit> stubs) {
            return new Fixture(uri, signedIn, Body.NONE, Map.of(), stubs);
        }
    }

    static final Map<String, Fixture> ALL = new LinkedHashMap<>();

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
        put("GET /app/verify", Fixture.get("/app/verify", false, k -> { }));
        put("GET /app/nudges/opt-out", Fixture.get("/app/nudges/opt-out?t=not-a-token", false, k -> { }));
        put("GET /app/home", Fixture.get("/app/home", true, Stubs::familyOfMa));
        put("GET /app/patients/{id}", Fixture.get("/app/patients/" + ma, true, Stubs::maTimeline));
        put("GET /app/patients/{id}/profile", Fixture.get("/app/patients/" + ma + "/profile", true, Stubs::maProfile));
        put("GET /app/documents/{id}/delete", Fixture.get("/app/documents/" + doc + "/delete", true, Stubs::doneLab));
        put("GET /app/documents/{id}/image", Fixture.get("/app/documents/" + doc + "/image?key=f/p/" + doc + "/crop-1.jpg", true, Stubs::signedUrls));
        put("GET /app/documents/{id}/page/{n}", Fixture.get("/app/documents/" + doc + "/page/1", true, Stubs::signedUrls));
        put("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::doneLab));
        put("POST /app/login", new Fixture("/app/login", false, Body.FORM, Map.of("whatsapp_number", "01700000000", "confirmed", "1"), Stubs::noCode));
        put("POST /app/verify", new Fixture("/app/verify", false, Body.FORM, Map.of("code", "123456"), Stubs::noCode));
        put("POST /app/logout", new Fixture("/app/logout", true, Body.FORM, Map.of(), Stubs::logsOut));
        put("POST /app/nudges/opt-out", new Fixture("/app/nudges/opt-out", false, Body.FORM, Map.of("t", "not-a-token", "scope", "patient"), k -> { }));
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

        private Stubs() {
        }

        static void familyOfMa(WebUiTestKit k) {
            k.familyWith(k.ma(3));
        }

        static void maTimeline(WebUiTestKit k) {
            familyOfMa(k);
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
