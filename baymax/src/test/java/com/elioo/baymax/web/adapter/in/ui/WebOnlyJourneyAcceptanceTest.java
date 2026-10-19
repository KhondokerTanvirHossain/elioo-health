package com.elioo.baymax.web.adapter.in.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.testsupport.TryDocuments;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PO's request, as a test: a family uses the whole web app with WhatsApp switched off. Sign in with a code from the
 * log fallback (the supported laptop path), see the tabs, add a paper, watch it read, read the stored NOW banner, use
 * পরিবার, delete the paper, sign out. Each step asserts its element. Everything is real — Postgres, MinIO, the router,
 * the filters, the services and adapters — except OCR and the model, stubbed at their ports. The route sweep at the end
 * is derived from the router: a family-facing route a later PR adds fails here until the journey walks it or this class
 * writes down why not.
 */
@Testcontainers
class WebOnlyJourneyAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    /** Synthetic (DR-36): Potassium 11.0 against 3.5–5.1 is ≥ 2× high, the stopgap's NOW. */
    static final String NOW_REPLY = """
            {"document_type":"lab_report","document_date":"2026-10-12","facility":"Shapla Diagnostics",
             "values":[{"name":"Potassium","value":"11.0","unit":"mmol/L","ref_low":"3.5","ref_high":"5.1",
                        "source_span":{"page":1,"start":0,"end":9}}],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                                 "investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.95,"values":0.95,"medicines":1.0,"follow_up":1.0,"clinical_context":1.0}}
            """;

    private static final String NUMBER = "+8801700000731";
    private static final String DELIVERY_LOGGER = "com.elioo.baymax.web.adapter.out.delivery";

    /** Routes a family never reaches by browsing the app, each with why it is not a step of this journey. */
    static final Map<String, String> NOT_IN_JOURNEY = Map.ofEntries(
            Map.entry("GET /assets/{file}", "fetched by the browser; AssetListTest and the CSS tests cover every asset"),
            Map.entry("GET /lang/{code}", "the language toggle; BaymaxWebUiRouterTest"),
            Map.entry("GET /baymax", "a permanent redirect for old links; BaymaxWebUiRouterTest"),
            Map.entry("GET /baymax/{*rest}", "a permanent redirect for old links; BaymaxWebUiRouterTest"),
            Map.entry("GET /app", "the same page as GET /app/"),
            Map.entry("GET /app/nudges/opt-out", "reached only from a link inside a WhatsApp reminder (HMAC token)"),
            Map.entry("POST /app/nudges/opt-out", "reached only from a link inside a WhatsApp reminder (HMAC token)"),
            Map.entry("POST /app/patients/{id}/nudges/opt-out", "stops real reminders; FamilyViewTest; tried locally"),
            Map.entry("POST /app/patients/{id}/upload", "the legacy upload for cached pages; AddReportSubmitTest"),
            Map.entry("GET /app/patients/{id}", "the pre-tab timeline URL; C1 turns it into a redirect"),
            Map.entry("GET /app/patients/{id}/profile", "the pre-tab profile URL; redirects to /app/family (A6)"),
            Map.entry("GET /app/documents/{id}/image", "a crop, fetched by the browser from a page"),
            Map.entry("GET /app/documents/{id}/page/{n}", "a page image, fetched by the browser from a page"));

    private final Set<String> visited = new TreeSet<>();
    private ListAppender<ILoggingEvent> codes;
    private Level deliveryLevelBefore;

    @BeforeAll
    static void bucket() {
        ExtractionPipelineAcceptanceTest.createBucket(MINIO);
    }

    private ReactiveWebApplicationContextRunner runner() {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, NOW_REPLY, "Potassium", "11.0")
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.auth.web-mints-code=true",
                        "baymax.auth.hmac-secret=journey-secret", "baymax.auth.cookie-secure=false",
                        "baymax.free.max-docs-per-month=50", "baymax.nudge.enabled=false");
    }

    @BeforeEach
    void listen() {
        Logger delivery = (Logger) LoggerFactory.getLogger(DELIVERY_LOGGER);
        deliveryLevelBefore = delivery.getLevel();
        delivery.setLevel(Level.DEBUG);   // the log fallback writes the code at DEBUG only (DR-39)
        codes = new ListAppender<>();
        codes.start();
        delivery.addAppender(codes);
    }

    @AfterEach
    void stop() {
        Logger delivery = (Logger) LoggerFactory.getLogger(DELIVERY_LOGGER);
        delivery.detachAppender(codes);
        delivery.setLevel(deliveryLevelBefore);
    }

    @Test
    void aFamilyDoesEverythingOnTheWebWithWhatsAppSwitchedOff() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            PatientProfile ma = familyWithMa(context);
            WebTestClient web = client(context);
            UiCopy copy = context.getBean(UiCopy.class);
            assertThat(context.getBeanNamesForType(WaMessagingPort.class)).as("WhatsApp is off: no messaging bean exists").isEmpty();

            // 1. sign in: the number, then the code from the log fallback, then the session
            String login = cookie(step(web.post().uri("/app/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("whatsapp_number", "01700000731").with("confirmed", "1")), "POST /app/login")
                    .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/verify"), "baymax_login");
            String code = codes.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("otp fallback"))
                    .map(m -> m.replaceAll(".*code=([0-9]{6}).*", "$1")).findFirst()
                    .orElseThrow(() -> new AssertionError("no code in the log fallback"));
            assertThat(code).matches("[0-9]{6}");
            assertThat(step(web.get().uri("/app/verify").cookie("baymax_login", login), "GET /app/verify").expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody()).contains("name=\"code\"");
            String session = verify(web, login, code);

            // 2. the three tabs; চ্যাট's composer says chat is coming and cannot send
            String chat = get(web, session, "/app/home", "GET /app/home");
            assertThat(AppShellRoutesTest.between(chat, "<div class=\"composer\"", "</div>"))
                    .contains(copy.t(Lang.BN, "chat.soon")).doesNotContain("<form");
            assertThat(AppShellRoutesTest.between(chat, "<nav class=\"tabs\"", "</nav>"))
                    .contains(">" + copy.t(Lang.BN, "tab.papers") + "<").contains(">" + copy.t(Lang.BN, "tab.family") + "<");
            assertThat(get(web, session, "/app/timeline", "GET /app/timeline")).contains("href=\"/app/add?patient=" + ma.id());

            // 3–4. add a paper: the sheet, the post, the reading page, the poll until DONE
            String documentPath = uploadAndWaitUntilDone(web, session, ma);

            // 5. the result: the stored NOW line first, the patient named
            String result = get(web, session, documentPath, "GET /app/documents/{id}");
            assertThat(ResultFixtures.section(result, "urgency")).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
            assertThat(result.indexOf("id=\"urgency\"")).as("the urgency leads the page").isLessThan(result.indexOf("Potassium"));
            assertThat(result).contains("<span class=\"pchip\">" + copy.t(Lang.BN, "result.patient", "Rokeya", 68) + "</span>");

            // 6. পরিবার: the account, the flag saved and shown ticked
            String familyTab = get(web, session, "/app/family", "GET /app/family");
            assertThat(familyTab).contains(PhoneDisplay.full(NUMBER)).contains("action=\"/app/logout\"");
            assertThat(familyTab).doesNotContain("value=\"diabetes\" checked");
            step(web.post().uri("/app/patients/" + ma.id() + "/profile").cookie("baymax_session", session)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData("flag", "diabetes")),
                    "POST /app/patients/{id}/profile").expectStatus().isSeeOther()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/family?patient=" + ma.id() + "&saved=1");
            String saved = get(web, session, "/app/family?patient=" + ma.id() + "&saved=1", "GET /app/family");
            assertThat(saved).contains(copy.t(Lang.BN, "profile.saved")).contains("value=\"diabetes\" checked");

            // 7. delete the paper: the confirmation, then gone
            assertThat(get(web, session, documentPath + "/delete", "GET /app/documents/{id}/delete"))
                    .contains("method=\"post\"").contains("action=\"" + documentPath + "/delete\"");
            assertThat(step(web.post().uri(documentPath + "/delete").cookie("baymax_session", session), "POST /app/documents/{id}/delete")
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody()).contains(copy.t(Lang.BN, "doc.deleted"));
            web.get().uri(documentPath).cookie("baymax_session", session).exchange().expectStatus().isNotFound();

            // 8. sign out: the session no longer opens a tab; the landing and the number page are there
            step(web.post().uri("/app/logout").cookie("baymax_session", session), "POST /app/logout").expectStatus().isSeeOther()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
            web.get().uri("/app/home").cookie("baymax_session", session).exchange().expectStatus().is3xxRedirection()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
            assertThat(step(web.get().uri("/"), "GET /").expectStatus().isOk().expectBody(String.class).returnResult()
                    .getResponseBody()).contains("href=\"/app\"");
            assertThat(step(web.get().uri("/app/"), "GET /app/").expectStatus().isOk().expectBody(String.class).returnResult()
                    .getResponseBody()).contains("name=\"whatsapp_number\"");

            // 9. every family-facing route is visited or has a written reason (derived from the router)
            List<RouteTable.Route> routes = RouteTable.of(context.getBean("baymaxWebUiRoutes", RouterFunction.class));
            assertThat(routes).hasSizeGreaterThanOrEqualTo(25);
            List<String> keys = routes.stream().map(RouteTable.Route::key).toList();
            List<String> missed = keys.stream().filter(k -> !visited.contains(k) && !NOT_IN_JOURNEY.containsKey(k)).toList();
            assertThat(missed).as("routes neither walked by the journey nor listed with a reason").isEmpty();
            assertThat(NOT_IN_JOURNEY.keySet()).as("a reason for a route that no longer exists").allSatisfy(k -> assertThat(keys).contains(k));
            assertThat(visited).as("a step under a key the router does not publish").allSatisfy(k -> assertThat(keys).contains(k));
        });
    }

    // ---- the steps, shared by both journeys -------------------------------------------------------------------

    private PatientProfile familyWithMa(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        Instant t = Instant.now();
        FamilyAccount family = records.createFamily(new FamilyAccount(null, NUMBER, "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
        return records.createPatient(new PatientProfile(null, family.id(), "Rokeya", 68, PatientProfile.Sex.FEMALE, List.of(), t, t)).block();
    }

    private static WebTestClient client(ApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30)).build();
    }

    private String verify(WebTestClient web, String login, String code) {
        return cookie(step(web.post().uri("/app/verify").cookie("baymax_login", login)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData("code", code)), "POST /app/verify")
                .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/home"), "baymax_session");
    }

    /** The sheet, the multipart post, the reading page, then the status poll until DONE; returns the document's path. */
    private String uploadAndWaitUntilDone(WebTestClient web, String session, PatientProfile ma) throws Exception {
        assertThat(get(web, session, "/app/add?patient=" + ma.id(), "GET /app/add"))
                .containsPattern("name=\"patient\" value=\"" + ma.id() + "\" required checked>");
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.id().toString());
        body.part("file", TryDocuments.jpeg(400, 200)).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        String documentPath = step(web.post().uri("/app/add").cookie("baymax_session", session)
                .body(BodyInserters.fromMultipartData(body.build())), "POST /app/add")
                .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(documentPath).matches("/app/documents/[0-9a-f-]{36}");
        String first = get(web, session, documentPath, "GET /app/documents/{id}");
        assertThat(first).containsAnyOf("id=\"progress\"", "id=\"urgency\"");   // still reading, or already read

        String status = "";
        for (int i = 0; i < 60 && !status.equals("DONE"); i++) {
            String json = step(web.get().uri(documentPath + "/status").cookie("baymax_session", session), "GET /app/documents/{id}/status")
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
            assertThat(json).matches("\\{\"status\":\"[A-Z_]+\"}").doesNotContain("urgency");
            status = json.replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
            if (!status.equals("DONE")) {
                Thread.sleep(500);
            }
        }
        assertThat(status).as("the paper was read").isEqualTo("DONE");
        return documentPath;
    }

    private WebTestClient.ResponseSpec step(WebTestClient.RequestHeadersSpec<?> request, String routeKey) {
        visited.add(routeKey);
        return request.exchange();
    }

    private String get(WebTestClient web, String session, String uri, String routeKey) {
        return step(web.get().uri(uri).cookie("baymax_session", session), routeKey).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    /** The value a Set-Cookie header gives this cookie (the handlers write the header, not the cookie map). */
    private static String cookie(WebTestClient.ResponseSpec response, String name) {
        List<String> set = response.returnResult(Void.class).getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        String value = set.stream().map(h -> java.net.HttpCookie.parse(h).get(0)).filter(c -> c.getName().equals(name))
                .map(java.net.HttpCookie::getValue).filter(v -> !v.isEmpty()).findFirst().orElse(null);
        assertThat(value).as("cookie " + name + " in " + set).isNotNull();
        return value;
    }
}
