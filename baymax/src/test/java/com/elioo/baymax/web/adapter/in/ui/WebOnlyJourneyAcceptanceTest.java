package com.elioo.baymax.web.adapter.in.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.ExtractionPipelineAcceptanceTest;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.service.DocumentIntakeService;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.application.service.StoredUrgencyService;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.testsupport.DeletionSweep;
import com.elioo.baymax.testsupport.PaintedPaper;
import com.elioo.baymax.testsupport.PaintedPaper.Hold;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.web.adapter.in.PhoneDisplay;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.util.pattern.PathPatternParser;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.elioo.baymax.testsupport.PaintedPaper.PAPER;
import static com.elioo.baymax.testsupport.PaintedPaper.WORDS;
import static com.elioo.baymax.testsupport.PaintedPaper.colourAt;
import static com.elioo.baymax.testsupport.PaintedPaper.paintedPage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PO's request, as a test: a family uses the whole web app with WhatsApp switched off. Sign in with a code from the
 * log fallback (the supported laptop path), see the tabs, add a paper, watch it read, read the NOW banner, look at the
 * crop and the page, use পরিবার (a flag, the reminders switch), delete the paper, sign out. Each step asserts its
 * element. Everything is real — Postgres, MinIO, the router, the filters, the services and adapters — except OCR and
 * the model, stubbed at their ports. The route sweep at the end is derived twice: the routes from the router, and the
 * route each request reached from the request itself (method and path matched against the router's patterns), so a
 * family-facing route a later PR adds fails here until the journey walks it or this class writes down why not.
 */
@Testcontainers
class WebOnlyJourneyAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    /** The painted page's reading: Potassium 11.0, the stopgap's NOW ({@link PaintedPaper#NOW_REPLY}). */
    static final String NOW_REPLY = PaintedPaper.NOW_REPLY;

    /** B5: the same paper read badly — overall 0.40, below the gate — so the second extraction is a low_overall retake. */
    static final String RETAKE_REPLY = NOW_REPLY.replace("\"confidence\":{\"overall\":0.95", "\"confidence\":{\"overall\":0.40");

    private static final String NUMBER = "+8801700000731";
    /** The WhatsApp-on journey's family: its own number, so the two tests share no row and run in either order. */
    private static final String WA_NUMBER = "+8801700000732";
    /** The held journey's family (aResultOpenedWhileThePaperIsStillBeingAssessedIsTheFinishedPage): its own number too. */
    private static final String HELD_NUMBER = "+8801700000733";
    private static final String DELIVERY_LOGGER = "com.elioo.baymax.web.adapter.out.delivery";
    /** How long the WhatsApp port must stay silent before J1.2 counts its calls: longer than any retry or follow-up. */
    private static final Duration SETTLE = PaintedPaper.SETTLE;

    /** Routes a family never reaches by browsing the app, each with why it is not a step of this journey. */
    static final Map<String, String> NOT_IN_JOURNEY = Map.ofEntries(
            Map.entry("GET /assets/{file}", "fetched by the browser; AssetListTest and the CSS tests cover every asset"),
            Map.entry("GET /lang/{code}", "the language toggle; BaymaxWebUiRouterTest"),
            Map.entry("GET /baymax", "a permanent redirect for old links; BaymaxWebUiRouterTest"),
            Map.entry("GET /baymax/{*rest}", "a permanent redirect for old links; BaymaxWebUiRouterTest"),
            Map.entry("GET /app/nudges/opt-out", "reached only from a link inside a WhatsApp reminder (HMAC token)"),
            Map.entry("POST /app/nudges/opt-out", "reached only from a link inside a WhatsApp reminder (HMAC token)"),
            Map.entry("POST /app/patients/{id}/upload", "the legacy upload for cached pages; AddReportSubmitTest"),
            Map.entry("GET /app/patients/{id}", "a 302 for old links; AppShellRoutesTest"),
            Map.entry("GET /app/patients/{id}/profile", "the pre-tab profile URL; redirects to /app/family (A6)"),
            // plan W3-api, S1: the journey signs in through /app; the same pages under /app/basic are walked end to end,
            // from the <noscript> href to the held page, by BasicSignInPagesTest.aNoJsSignInThroughBasicOpensTheHeldPage
            Map.entry("GET /app/basic", "the no-JS sign-in fallback; BasicSignInPagesTest walks it"),
            Map.entry("GET /app/basic/", "the no-JS sign-in fallback; BasicSignInPagesTest walks it"),
            Map.entry("POST /app/basic/login", "the no-JS sign-in fallback; BasicSignInPagesTest walks it"),
            Map.entry("GET /app/basic/verify", "the no-JS sign-in fallback; BasicSignInPagesTest walks it"),
            Map.entry("POST /app/basic/verify", "the no-JS sign-in fallback; BasicSignInPagesTest walks it"));

    /** Every request the journey made, as "METHOD /raw/path", recorded by the client itself (not labelled by hand). */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private ListAppender<ILoggingEvent> codes;
    private Level deliveryLevelBefore;

    @BeforeAll
    static void bucket() {
        ExtractionPipelineAcceptanceTest.createBucket(MINIO);
    }

    private ReactiveWebApplicationContextRunner runner() {
        // the model's replies in turn: the first paper reads as NOW, the second as a retake (B5), every later one NOW again
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, new ExtractionPipelineAcceptanceTest.StubReply(
                        NOW_REPLY, List.of("Potassium", "11.0"), List.of(RETAKE_REPLY)))
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.auth.web-mints-code=true",
                        "baymax.auth.hmac-secret=journey-secret", "baymax.auth.cookie-secure=false",
                        "baymax.free.max-docs-per-month=50", "baymax.nudge.enabled=false",
                        // markers.properties' potassium row (production imports the file; a context runner does not), so
                        // the sheet's history is looked up as in production: a value with no canonical name has none
                        "baymax.markers[0].canonical=potassium", "baymax.markers[0].aliases=potassium");
    }

    /**
     * The same context with the channel on: the startup validator's shapes (fictional), the allowlist holding only the
     * family's number, the web form NOT minting (the validator refuses that with WhatsApp on), and {@code wa} as the
     * messaging port. {@code WaGraphClient} is still built with the channel on, so the mock is primary.
     */
    private ReactiveWebApplicationContextRunner whatsappRunner(WaMessagingPort wa) {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, NOW_REPLY, "Potassium", "11.0")
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withBean(WaMessagingPort.class, () -> wa, bd -> bd.setPrimary(true))
                .withPropertyValues("baymax.wa.enabled=true",
                        "baymax.wa.token=EAA" + "x".repeat(200),
                        "baymax.wa.app-secret=88e3540dd539aabbccddeeff00112233",
                        "baymax.wa.verify-token=78d41af3449a9bfbd560140166c6f2c2",
                        "baymax.wa.phone-number-id=1412074765313019",
                        "baymax.wa.waba-id=4407553579467225",
                        "baymax.wa.allowlist=" + WA_NUMBER,
                        "baymax.auth.web-mints-code=false",
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
            String login = cookie(web.post().uri("/app/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("whatsapp_number", "01700000731").with("confirmed", "1")).exchange()
                    .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/verify"), "baymax_login");
            String code = codes.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("otp fallback"))
                    .map(m -> m.replaceAll(".*code=([0-9]{6}).*", "$1")).findFirst()
                    .orElseThrow(() -> new AssertionError("no code in the log fallback"));
            assertThat(code).matches("[0-9]{6}");
            assertThat(web.get().uri("/app/verify").cookie("baymax_login", login).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody()).contains("name=\"code\"");
            String session = enterCode(web, login, code);

            // 2. the three tabs; চ্যাট's composer says chat is coming and holds nothing that could send
            String chat = get(web, session, "/app/home");
            assertThat(AppShellRoutesTest.between(chat, "<div class=\"composer\"", "</div>"))
                    .contains(copy.t(Lang.BN, "chat.soon")).doesNotContain("<form", "<textarea", "<input", "<button");
            assertThat(AppShellRoutesTest.between(chat, "<nav class=\"tabs\"", "</nav>"))
                    .contains(">" + copy.t(Lang.BN, "tab.papers") + "<").contains(">" + copy.t(Lang.BN, "tab.family") + "<");
            assertThat(get(web, session, "/app/timeline")).contains("href=\"/app/add?patient=" + ma.id());

            // 3–4. add a paper: the sheet, the post, the reading page, the poll until DONE
            String documentPath = uploadAndWaitUntilDone(web, session, ma);
            UUID documentId = idOf(documentPath);

            // 5. the result: the NOW line first, the patient named. The banner says NOW and the document's stored
            // urgency is NOW; that the banner is read from that column and not recomputed is A12's tests' claim.
            String result = get(web, session, documentPath);
            assertThat(ResultFixtures.section(result, "urgency")).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
            assertThat(scalar("select urgency from baymax.document where id = '" + documentId + "'"))
                    .as("the document's stored urgency").isEqualTo("now");
            assertThat(result.indexOf("id=\"urgency\"")).as("the urgency leads the page").isLessThan(result.indexOf("Potassium"));
            assertThat(result).contains("<span class=\"pchip\">" + copy.t(Lang.BN, "result.patient", "Rokeya", 68) + "</span>");

            // 5a. C2: the কাগজপত্র list, read from the stored row: this paper's own row (C3: the a.titem) carries the NOW short form
            Dom papers = Dom.parse(get(web, session, "/app/timeline?patient=" + ma.id()));
            List<Dom.Element> paperCards = papers.all.stream().filter(e -> e.classes().contains("titem"))
                    .filter(e -> AppShellRoutesTest.subtree(e).stream().anyMatch(x -> documentPath.equals(x.attrs().get("href")))).toList();
            assertThat(paperCards).as("one card for this paper on the list").hasSize(1);
            List<Dom.Element> nowChips = AppShellRoutesTest.subtree(paperCards.get(0)).stream()
                    .filter(e -> e.tag().equals("span") && e.classes().equals(java.util.Set.of("chip", "now"))).toList();
            assertThat(nowChips).as("the NOW chip in this paper's row").hasSize(1);
            assertThat(nowChips.get(0).ownText()).isEqualTo(copy.urgency(Lang.BN, Urgency.NOW, true));

            // 5e. C4: the চ্যাট tab leads with this paper's NOW card, read from the stored level, its one action opening the paper,
            // and the কাগজপত্র strip carries the same paper
            Dom home = Dom.parse(get(web, session, "/app/home"));
            List<Dom.Element> nowCards = home.all.stream()
                    .filter(e -> e.tag().equals("section") && e.classes().equals(java.util.Set.of("attn", "now"))).toList();
            assertThat(nowCards).as("one NOW card on the চ্যাট tab").hasSize(1);
            assertThat(AppShellRoutesTest.subtree(nowCards.get(0)).stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href")))
                    .as("its one action opens this paper").containsExactly(documentPath);
            assertThat(AppShellRoutesTest.text(nowCards.get(0))).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
            List<Dom.Element> strip = papers.all.stream().filter(e -> e.classes().contains("attn-strip")).toList();
            assertThat(strip).as("the strip on কাগজপত্র").hasSize(1);
            assertThat(AppShellRoutesTest.subtree(strip.get(0)).stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href")))
                    .containsExactly(documentPath);

            // 5b. the pictures the result page shows, fetched by the src it renders and checked by what they show
            List<String> crops = srcs(result, "/image\\?key=[^\"]+");
            List<String> pages = srcs(result, "/page/[0-9]+");
            assertThat(crops).as("the Potassium reading's crop on the result page").hasSize(1);
            assertThat(pages).as("the page image on the result page").hasSize(1);
            BufferedImage page = picture(web, session, pages.get(0));
            assertThat((double) page.getWidth() / page.getHeight()).as("the page's shape").isCloseTo(2.0, within(0.02));
            assertThat(colourAt(page, 0.125, 0.125)).as("the page where the words are").isEqualTo(WORDS);
            assertThat(colourAt(page, 0.875, 0.875)).as("the page away from the words").isEqualTo(PAPER);
            BufferedImage crop = picture(web, session, crops.get(0));
            assertThat(crop.getWidth()).as("a crop, not the page").isLessThan(page.getWidth());
            assertThat(crop.getHeight()).as("a crop, not the page").isLessThan(page.getHeight());
            for (double fx = 0.1; fx < 1; fx += 0.2) {
                for (double fy = 0.1; fy < 1; fy += 0.2) {
                    assertThat(colourAt(crop, fx, fy)).as("the crop shows the words' region at %.1f,%.1f", fx, fy).isEqualTo(WORDS);
                }
            }

            // 5d. B6: the detail (s17) and the Potassium row's value sheet (s13), reached by the links the result renders
            assertThat(result).contains("href=\"" + documentPath + "/all\"");
            String detail = get(web, session, documentPath + "/all");
            assertThat(ResultFixtures.section(detail, "pages")).as("the page itself, on the detail").contains(documentPath + "/page/1");
            Matcher row = Pattern.compile("<a class=\"vrow [^\"]*\" id=\"v-([0-9a-f-]{36})\" href=\"([^\"]+)\"[^>]*>(?:(?!</a>).)*?Potassium",
                    Pattern.DOTALL).matcher(result);
            assertThat(row.find()).as("the Potassium row on the result page").isTrue();
            assertThat(row.group(1)).as("the row's id is the stored observation's")
                    .isEqualTo(scalar("select id from baymax.observation where document_id = '" + documentId + "' and name = 'Potassium'"));
            assertThat(row.group(2)).isEqualTo(documentPath + "/values/" + row.group(1) + "?from=summary");
            String sheet = get(web, session, row.group(2));
            // the value's own field, not "11.0" anywhere (the sheet's aria-label also carries it)
            assertThat(sheet).contains("<span class=\"val big\">11.0</span>").contains(copy.t(Lang.BN, "sheet.earlier.none"))
                    .contains("href=\"" + documentPath + "#v-" + row.group(1) + "\"");

            // 5c. a paper read badly (B5): the retake card in Bangla, a retake for the same patient through the one entry,
            // and the month's allowance the same before and after — a retake is never counted (DR-24)
            String quotaBefore = quotaLine(get(web, session, "/app/add?patient=" + ma.id()));
            String retakePath = upload(web, session, ma);
            assertThat(waitUntilFinished(web, session, retakePath)).as("the badly read paper").isEqualTo("NEEDS_RETAKE");
            String retakePage = get(web, session, retakePath);
            String card = ResultFixtures.section(retakePage, "retake");
            assertThat(card).contains(copy.t(Lang.BN, "retake.head")).contains(copy.t(Lang.BN, "retake.reason.low_overall"))
                    .doesNotContain("not read confidently").doesNotContain("low_overall");
            assertThat(retakePage).doesNotContain("id=\"urgency\"");
            assertThat(quotaLine(get(web, session, "/app/add?patient=" + ma.id()))).as("the allowance after a retake").isEqualTo(quotaBefore);
            String form = ResultFixtures.section(retakePage, "retake-form");
            assertThat(form).contains("action=\"/app/add\"").contains("capture=\"environment\"");
            Map<String, String> hidden = hiddenFields(form);
            assertThat(hidden).as("the retake goes to the same patient").containsEntry("patient", ma.id().toString());
            MultipartBodyBuilder again = new MultipartBodyBuilder();
            hidden.forEach(again::part);
            again.part("file", paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
            String retakenPath = web.post().uri(form.replaceAll("(?s).*?action=\"([^\"]+)\".*", "$1")).cookie("baymax_session", session)
                    .body(BodyInserters.fromMultipartData(again.build())).exchange()
                    .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
            assertThat(retakenPath).matches("/app/documents/[0-9a-f-]{36}").isNotEqualTo(retakePath).isNotEqualTo(documentPath);
            assertThat(waitUntilFinished(web, session, retakenPath)).as("the retaken paper").isEqualTo("DONE");
            assertThat(scalar("select patient_id from baymax.document where id = '" + idOf(retakenPath) + "'"))
                    .as("the retaken paper is the same patient's").isEqualTo(ma.id().toString());

            // 5e. B6, on real rows: the retaken paper's Potassium sheet lists the first paper's reading (same marker, same
            // unit, an earlier DONE paper; the retake that was never read is not one) beside THAT paper's own crop, and
            // opens THAT paper's sheet from the detail
            String retakenObs = scalar("select id from baymax.observation where document_id = '" + idOf(retakenPath) + "' and name = 'Potassium'");
            String history = ResultFixtures.section(get(web, session, retakenPath + "/values/" + retakenObs + "?from=summary"), "earlier");
            List<String> earlierRows = Pattern.compile("<a class=\"erow\".*?</a>", Pattern.DOTALL).matcher(history).results()
                    .map(java.util.regex.MatchResult::group).toList();
            assertThat(earlierRows).as("one earlier reading: the first paper's").hasSize(1);
            assertThat(earlierRows.get(0)).contains("href=\"" + documentPath + "/values/" + row.group(1) + "?from=all\"")
                    .contains("src=\"" + PageChrome.imageHref(documentId,
                            scalar("select crop_key from baymax.observation where id = '" + row.group(1) + "'")) + "\"")
                    .contains("<span class=\"val\">11.0</span>");

            // 6. পরিবার: the account, the flag saved and shown ticked, the reminders stopped and shown stopped
            String familyTab = get(web, session, "/app/family");
            assertThat(familyTab).contains(PhoneDisplay.full(NUMBER)).contains("action=\"/app/logout\"");
            assertThat(familyTab).doesNotContain("value=\"diabetes\" checked");
            web.post().uri("/app/patients/" + ma.id() + "/profile").cookie("baymax_session", session)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData("flag", "diabetes")).exchange()
                    .expectStatus().isSeeOther()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/family?patient=" + ma.id() + "&saved=1");
            String saved = get(web, session, "/app/family?patient=" + ma.id() + "&saved=1");
            assertThat(saved).contains(copy.t(Lang.BN, "profile.saved")).contains("value=\"diabetes\" checked");

            String optOut = "action=\"/app/patients/" + ma.id() + "/nudges/opt-out\"";
            assertThat(saved).contains("Rokeya-র রিমাইন্ডার চালু আছে").contains(optOut);
            assertThat(scalar("select count(*) from baymax.patient_profile where id = '" + ma.id() + "' and nudges_opted_out_at is null"))
                    .as("reminders on before the family stops them").isEqualTo("1");
            web.post().uri("/app/patients/" + ma.id() + "/nudges/opt-out").cookie("baymax_session", session).exchange()
                    .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/family?patient=" + ma.id());
            assertThat(scalar("select count(*) from baymax.patient_profile where id = '" + ma.id() + "' and nudges_opted_out_at is not null"))
                    .as("the stop is stored on the patient").isEqualTo("1");
            assertThat(get(web, session, "/app/family?patient=" + ma.id()))
                    .contains("Rokeya-র রিমাইন্ডার বন্ধ আছে।").doesNotContain(optOut);

            // 7. delete the paper: the confirmation, then gone — the rows, the ledger and the objects in the bucket
            List<String> keys = column("select storage_key from baymax.stored_object where document_id = '" + documentId
                    + "' order by kind, storage_key");
            String prefix = StorageKeys.documentPrefix(ma.familyId(), ma.id(), documentId);
            assertThat(keys).as("the page and the crop in the ledger before the delete").hasSize(2).allSatisfy(k -> assertThat(k).startsWith(prefix));
            StoragePort bucket = context.getBean(StoragePort.class);
            assertThat(bucket.listKeys(prefix).collectList().block()).as("the objects in the bucket before the delete")
                    .containsExactlyInAnyOrderElementsOf(keys);
            // the paper arrived over WhatsApp too (V22): its inbound row holds the patient, the document and Meta's media id,
            // a handle that fetches the photo again while Meta keeps it. "Removes the images, permanently" covers it.
            String inboundKey = "f".repeat(32) + documentId.toString().replace("-", "");
            sql("insert into baymax.inbound_message (provider_key, family_id, patient_id, document_id, type, media_id) values ('"
                    + inboundKey + "','" + ma.familyId() + "','" + ma.id() + "','" + documentId + "','image','MEDIA-JOURNEY')");
            // one unsent explanation of this paper, seeded: the journey's own explanation was delivered (sent_at set, a
            // survivor until PO decision P1), so without this row the sent_at-is-null half of the shared sweep — the
            // rows a reviewer could still approve and send about a deleted paper — would never be exercised here
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("insert into baymax.outbound_message (family_id, patient_id, document_id, kind, urgency, body, gate_status) "
                        + "values ('" + ma.familyId() + "', '" + ma.id() + "', '" + documentId + "', 'explanation', 'now', "
                        + "'a pending explanation of the paper', 'pending')");
            }
            assertThat(scalar("select count(*) from baymax.outbound_message where document_id = '" + documentId + "' and sent_at is null"))
                    .as("the seeded unsent explanation, before the delete").isEqualTo("1");
            assertThat(get(web, session, documentPath + "/delete"))
                    .contains("method=\"post\"").contains("action=\"" + documentPath + "/delete\"");
            assertThat(web.post().uri(documentPath + "/delete").cookie("baymax_session", session).exchange()
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody()).contains(copy.t(Lang.BN, "doc.deleted"));
            web.get().uri(documentPath).cookie("baymax_session", session).exchange().expectStatus().isNotFound();
            assertThat(tablesStillHolding(documentId)).as("tables still holding the deleted document").isEmpty();
            // not swept by document_id: SET NULL clears that very column. Asked of the family instead
            assertThat(scalar("select count(*) from baymax.inbound_message where family_id = '" + ma.familyId()
                    + "' and (media_id is not null or patient_id is not null)"))
                    .as("inbound rows of the family still holding a media id or the patient after the paper was deleted").isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.inbound_message where provider_key = '" + inboundKey + "'"))
                    .as("the key itself stays: a Meta re-delivery must still be a duplicate").isEqualTo("1");
            assertThat(bucket.listKeys(prefix).collectList().block()).as("the bucket after the delete").isEmpty();

            // 8. sign out: the session no longer opens a tab; the landing and the number page are there, at both URLs
            web.post().uri("/app/logout").cookie("baymax_session", session).exchange().expectStatus().isSeeOther()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
            web.get().uri("/app/home").cookie("baymax_session", session).exchange().expectStatus().is3xxRedirection()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
            assertThat(web.get().uri("/").exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                    .getResponseBody()).contains("href=\"/app\"");
            String numberPage = web.get().uri("/app/").exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                    .getResponseBody();
            assertThat(numberPage).contains("name=\"whatsapp_number\"");
            assertThat(web.get().uri("/app").exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                    .getResponseBody()).as("GET /app is the same page as GET /app/").isEqualTo(numberPage);

            // 9. every family-facing route is visited or has a written reason; the visited ones derived from the requests
            List<RouteTable.Route> routes = RouteTable.of(context.getBean("baymaxWebUiRoutes", RouterFunction.class));
            assertThat(routes).hasSizeGreaterThanOrEqualTo(25);
            List<String> keysOfRoutes = routes.stream().map(RouteTable.Route::key).toList();
            Set<String> visited = new TreeSet<>();
            List<String> unrouted = new ArrayList<>();
            List<String> made = List.copyOf(requests);
            assertThat(made).as("requests the journey made").hasSizeGreaterThanOrEqualTo(20);
            for (String request : made) {
                List<String> hits = routesServing(routes, request);
                if (hits.size() == 1) {
                    visited.add(hits.get(0));
                } else {
                    unrouted.add(request + " -> " + hits);
                }
            }
            assertThat(unrouted).as("requests not served by exactly one published route").isEmpty();
            List<String> missed = keysOfRoutes.stream().filter(k -> !visited.contains(k) && !NOT_IN_JOURNEY.containsKey(k)).toList();
            assertThat(missed).as("routes neither walked by the journey nor listed with a reason").isEmpty();
            assertThat(NOT_IN_JOURNEY.keySet()).as("a reason for a route that no longer exists").allSatisfy(k -> assertThat(keysOfRoutes).contains(k));
            assertThat(NOT_IN_JOURNEY.keySet()).as("a reason for a route the journey walks after all").noneMatch(visited::contains);
        });
    }

    /**
     * Today's behaviour (D32's opt-in "WhatsApp-এও জানাবেন" is Phase 4): a paper uploaded on the web is also explained on
     * WhatsApp. With the channel on, the only messages are the sign-in code and this paper's own delivered rows — read
     * from the port's every invocation once it has been quiet for {@link #SETTLE}, and read again after the context
     * closes, so a message sent late or asynchronously is counted too. Nudges are off in this context
     * ({@code baymax.nudge.enabled=false}): a reminder is outside this claim; the nudge engine's own tests cover its
     * sends.
     */
    @Test
    void withWhatsAppOnTheOnlyMessagesAreTheCodeAndTheUploadedPapersOwnExplanation() {
        WaMessagingPort wa = mock(WaMessagingPort.class);
        AtomicInteger n = new AtomicInteger();
        AtomicInteger counted = new AtomicInteger(-1);
        when(wa.sendText(anyString(), anyString()))
                .thenAnswer(i -> Mono.just(WaFixtures.wamid(WA_NUMBER.substring(1), n.incrementAndGet())));
        whatsappRunner(wa).run(context -> {
            assertThat(context).hasNotFailed();
            // the family wrote to Medioo on WhatsApp within the window (PR 12's free path), then signs in on the web
            PatientProfile ma = familyWithMa(context, WA_NUMBER);
            context.getBean(WebAuthUseCase.class).noteInbound(WA_NUMBER.substring(1), Instant.now()).block();
            WebTestClient web = client(context);
            String session = signInWithTheCodeWhatsAppGot(context, web, wa);
            String documentPath = uploadAndWaitUntilDone(web, session, ma);
            UUID documentId = idOf(documentPath);
            awaitFinalDeliveryOfExplanation(documentId);
            counted.set(settle(wa));
            List<String> delivered = deliveredBodiesOf(documentId);

            // derived from the port: every call of any WaMessagingPort method, not a list of the ones we expect
            List<Invocation> calls = List.copyOf(Mockito.mockingDetails(wa).getInvocations());
            assertThat(calls).as("WhatsApp calls made").isNotEmpty().hasSize(counted.get());
            assertThat(calls).allSatisfy(c -> assertThat(c.getMethod().getName()).as("no other WhatsApp operation").isEqualTo("sendText"));
            assertThat(calls).allSatisfy(c -> assertThat((String) c.getArgument(0)).as("only the family's own number").isEqualTo(WA_NUMBER));
            List<String> bodies = calls.stream().map(c -> (String) c.getArgument(1)).toList();
            assertThat(bodies.get(0)).isEqualTo(codeMessage(context, bodies.get(0)));
            assertThat(bodies.subList(1, bodies.size())).as("after the code, only this paper's own delivered messages")
                    .allSatisfy(b -> assertThat(delivered).contains(b));
            assertThat(bodies.subList(1, bodies.size())).as("each of this paper's delivered rows, sent once")
                    .containsExactlyInAnyOrderElementsOf(delivered);
            assertThat(scalar("select count(*) from baymax.outbound_message where document_id = '" + documentId
                    + "' and kind = 'explanation' and urgency = 'now' and delivery_status = 'sent'"))
                    .as("the explanation of this paper, at the stored NOW, went out").isEqualTo("1");
        });
        assertThat(Mockito.mockingDetails(wa).getInvocations()).as("WhatsApp calls by the time the context closed")
                .hasSize(counted.get());
    }

    /**
     * The race behind the journey's flake, reproduced on Postgres and held open instead of hoped for. Main CI failed
     * twice at "the page image on the result page" hasSize(1) (run 39245684895 attempt 1, B5 #81; run 39379212824
     * attempt 1, #84), then passed on a rerun: the journey opens the result the moment the status says DONE, and DONE is written
     * by extraction BEFORE the chain stores the urgency and the verdicts (DocumentProcessingChain), so the page can
     * arrive while the chain is still composing. The page then assesses the paper itself (currentOrAssess), and its
     * verdicts commit together with the level; a view read alongside that assessment held no verdict while the level
     * said value_critical, so the NOW block drew the whole page as its proof above the page already drawn below.
     *
     * <p>Here the chain's assessment is held until the page has been drawn ({@link Hold#pageDrawn}), so the page always
     * arrives mid-composition, and the page's own assessment yields until a DONE view has been read (at most
     * {@link Hold#YIELD}), which is the interleaving that lost. DONE is the readiness signal the reading page itself
     * opens the result on, so the page, not this wait, is what must be right at DONE.
     */
    @Test
    void aResultOpenedWhileThePaperIsStillBeingAssessedIsTheFinishedPage() {
        Hold hold = new Hold();
        // the hourly sweep is the one other writer of the level in this context (UrgencySweepJob, minute :20): switched
        // off, so the stored level below can only be the page's own; HeldUrgency still counts any sweep that does run
        runner().withPropertyValues("baymax.urgency.sweep-cron=-")
                .withBean(Hold.class, () -> hold)
                .withBean(HeldUrgency.class)
                .withBean(SignallingIntake.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PatientProfile ma = familyWithMa(context, HELD_NUMBER);
                    WebTestClient web = client(context);
                    UiCopy copy = context.getBean(UiCopy.class);
                    String login = cookie(web.post().uri("/app/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(BodyInserters.fromFormData("whatsapp_number", "0" + HELD_NUMBER.substring(4)).with("confirmed", "1"))
                            .exchange().expectStatus().isSeeOther(), "baymax_login");
                    String code = codes.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("otp fallback"))
                            .map(m -> m.replaceAll(".*code=([0-9]{6}).*", "$1")).findFirst()
                            .orElseThrow(() -> new AssertionError("no code in the log fallback"));
                    String session = enterCode(web, login, code);
                    try {
                        // the post, then the status poll alone: the page is opened once, at DONE, as the reading page opens it
                        String sheet = get(web, session, "/app/add?patient=" + ma.id());
                        Matcher key = Pattern.compile("name=\"upload_key\" value=\"([0-9a-f-]{36})\"").matcher(sheet);
                        assertThat(key.find()).as("the sheet carries its press's key").isTrue();
                        MultipartBodyBuilder body = new MultipartBodyBuilder();
                        body.part("patient", ma.id().toString());
                        body.part("upload_key", key.group(1));
                        body.part("file", paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
                        String documentPath = web.post().uri("/app/add").cookie("baymax_session", session)
                                .body(BodyInserters.fromMultipartData(body.build())).exchange()
                                .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
                        assertThat(waitUntilFinished(web, session, documentPath)).as("the paper was read").isEqualTo("DONE");
                        UUID documentId = idOf(documentPath);

                        String result = get(web, session, documentPath);

                        // the page was drawn mid-composition: the chain's assessment still waiting, nothing explained yet
                        assertThat(hold.chainWaiting.get()).as("the chain's assessment, held while the page was drawn").isEqualTo(1);
                        assertThat(scalar("select count(*) from baymax.outbound_message where document_id = '" + documentId + "'"))
                                .as("no explanation composed when the page was drawn").isEqualTo("0");
                        assertThat(scalar("select urgency from baymax.document where id = '" + documentId + "'"))
                                .as("the level the page assessed itself").isEqualTo("now");
                        assertThat(hold.sweeps.get()).as("sweeps that could have stored that level instead of the page").isZero();

                        assertThat(ResultFixtures.section(result, "urgency")).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
                        // the path, not only the count: the NOW block's proof is the Potassium row, never the stand-in pages
                        assertThat(ResultFixtures.section(result, "why-value-critical")).as("the NOW block's proof")
                                .contains("Potassium").doesNotContain("/page/");
                        assertThat(srcs(result, "/page/[0-9]+")).as("the page image on the result page").hasSize(1);
                        assertThat(srcs(result, "/image\\?key=[^\"]+")).as("the Potassium reading's crop on the result page").hasSize(1);
                    } finally {
                        hold.pageDrawn.tryEmitEmpty();
                    }
                    // the chain resumes and finishes: its own assessment, then the explanation (a page left open is not the end)
                    String explained = "select count(*) from baymax.outbound_message where kind = 'explanation' and document_id in "
                            + "(select id from baymax.document where patient_id = '" + ma.id() + "')";
                    for (int i = 0; i < 60 && "0".equals(scalar(explained)); i++) {
                        Thread.sleep(500);
                    }
                    assertThat(scalar(explained)).as("the chain finished once released").isEqualTo("1");
                });
    }

    /**
     * The real writer, with the chain's call held and the page's call yielding. Every caller of the use case reaches it
     * through this bean: the chain ({@code assessAndStore}, held), the page ({@code currentOrAssess}, yielding), and
     * UrgencySweepJob ({@code sweep}, counted; its cron is off in this run). RecropHandler also calls
     * {@code assessAndStore} through the use case, but this journey never re-crops; the sweep and
     * {@code currentOrAssess} reach the service's own {@code assessAndStore} inside it, past this wrapper.
     */
    @Primary
    static final class HeldUrgency implements DocumentUrgencyUseCase {
        private final StoredUrgencyService real;
        private final Hold hold;

        HeldUrgency(StoredUrgencyService real, Hold hold) {
            this.real = real;
            this.hold = hold;
        }

        @Override
        public Mono<StoredUrgency> assessAndStore(UUID documentId) {
            return Mono.defer(() -> {
                hold.chainWaiting.incrementAndGet();
                return hold.pageDrawn.asMono().timeout(Duration.ofSeconds(60), Mono.empty());
            }).doFinally(s -> hold.chainWaiting.decrementAndGet()).then(Mono.defer(() -> real.assessAndStore(documentId)));
        }

        @Override
        public Mono<StoredUrgency> current(UUID documentId) {
            return real.current(documentId);
        }

        @Override
        public Mono<StoredUrgency> currentOrAssess(UUID documentId) {
            return hold.doneViewRead.asMono().timeout(Hold.YIELD, Mono.empty()).then(Mono.defer(() -> real.currentOrAssess(documentId)));
        }

        @Override
        public Mono<Long> sweep() {
            hold.sweeps.incrementAndGet();
            return real.sweep();
        }
    }

    /** The real intake; a view of a DONE paper, once read, lets the page's assessment go on. */
    @Primary
    static final class SignallingIntake implements DocumentIntakeUseCase {
        private final DocumentIntakeService real;
        private final Hold hold;

        SignallingIntake(DocumentIntakeService real, Hold hold) {
            this.real = real;
            this.hold = hold;
        }

        @Override
        public Mono<Document> accept(UUID patientId, List<Upload> uploads) {
            return real.accept(patientId, uploads);
        }

        @Override
        public Mono<CreatedDocument> accept(UUID patientId, List<Upload> uploads, String uploadKey) {
            return real.accept(patientId, uploads, uploadKey);
        }

        @Override
        public Flux<CreatedDocument> acceptEach(UUID patientId, List<Upload> uploads, String uploadKey) {
            return real.acceptEach(patientId, uploads, uploadKey);
        }

        @Override
        public Mono<DocumentView> view(UUID documentId) {
            return read(documentId).map(DocumentView.Read::view);
        }

        /** The result page reads the view with its row (D2); the signal is the same: a DONE view was read. */
        @Override
        public Mono<DocumentView.Read> read(UUID documentId) {
            return real.read(documentId).doOnNext(r -> {
                if (Document.Status.DONE.name().equals(r.view().status())) {
                    hold.doneViewRead.tryEmitEmpty();
                }
            });
        }
    }

    // ---- the steps, shared by both journeys -------------------------------------------------------------------

    private PatientProfile familyWithMa(ApplicationContext context) {
        return familyWithMa(context, NUMBER);
    }

    private PatientProfile familyWithMa(ApplicationContext context, String number) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        Instant t = Instant.now();
        FamilyAccount family = records.createFamily(new FamilyAccount(null, number, "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
        return records.createPatient(new PatientProfile(null, family.id(), "Rokeya", 68, PatientProfile.Sex.FEMALE, List.of(), t, t)).block();
    }

    /** The client the journey browses with; it records every request it makes, which is what the route sweep reads. */
    private WebTestClient client(ApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context).configureClient()
                .filter((request, next) -> {
                    requests.add(request.method().name() + " " + request.url().getRawPath());
                    return next.exchange(request);
                })
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    /** The published routes whose method and pattern serve this "METHOD /path" request. */
    private static List<String> routesServing(List<RouteTable.Route> routes, String request) {
        String method = request.substring(0, request.indexOf(' '));
        PathContainer path = PathContainer.parsePath(request.substring(request.indexOf(' ') + 1));
        return routes.stream().filter(r -> r.method().name().equals(method))
                .filter(r -> PathPatternParser.defaultInstance.parse(r.pattern()).matches(path))
                .map(RouteTable.Route::key).toList();
    }

    private static UUID idOf(String documentPath) {
        return UUID.fromString(documentPath.substring(documentPath.lastIndexOf('/') + 1));
    }

    private String enterCode(WebTestClient web, String login, String code) {
        return cookie(web.post().uri("/app/verify").cookie("baymax_login", login)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData("code", code)).exchange()
                .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/home"), "baymax_session");
    }

    /**
     * J1.1's sign-in with the channel on: the number page sends the code to WhatsApp (the login cookie carries the sent
     * mark, the code page says so, and nothing went to the log fallback), and the code in that one message opens the
     * session.
     */
    private String signInWithTheCodeWhatsAppGot(ApplicationContext context, WebTestClient web, WaMessagingPort wa) {
        String login = cookie(web.post().uri("/app/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("whatsapp_number", "0" + WA_NUMBER.substring(4)).with("confirmed", "1")).exchange()
                .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/verify"), "baymax_login");
        assertThat(login).as("WhatsApp accepted the code").endsWith("." + SignInPages.SENT_MARK);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        // the first message; how many there were is the derived sweep's question, asserted on every call at the end
        verify(wa, atLeastOnce()).sendText(eq(WA_NUMBER), body.capture());
        Matcher six = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])").matcher(body.getAllValues().get(0));
        assertThat(six.find()).as("a six-digit code in the message").isTrue();
        assertThat(codes.list).as("the code never went to the log fallback")
                .noneMatch(e -> e.getFormattedMessage().contains("otp fallback"));
        UiCopy copy = context.getBean(UiCopy.class);
        assertThat(web.get().uri("/app/verify").cookie("baymax_login", login).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody()).contains(copy.t(Lang.BN, "login.code.sent"));
        return enterCode(web, login, six.group(1));
    }

    /** The sign-in message as the bundle words it (wa.login_code) for the code it carries. */
    private static String codeMessage(ApplicationContext context, String sent) {
        Matcher six = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])").matcher(sent);
        assertThat(six.find()).as("a six-digit code in the first message").isTrue();
        BaymaxProperties properties = context.getBean(BaymaxProperties.class);
        return context.getBean(Copy.class).bn("wa.login_code", Map.of("code", six.group(1),
                "minutes", BanglaDate.digits(String.valueOf(properties.getAuth().getOtpTtl().toMinutes()))));
    }

    /**
     * Polls (30 s at most) until the explanation's delivery has a final outcome. 'pending' is not one: the adapter
     * claims the row as pending before it calls the port and records 'sent' or 'failed' after.
     */
    private static void awaitFinalDeliveryOfExplanation(UUID documentId) throws Exception {
        String explained = "select count(*) from baymax.outbound_message where document_id = '" + documentId
                + "' and kind = 'explanation' and delivery_status in ('sent', 'failed')";
        for (int i = 0; i < 60 && "0".equals(scalar(explained)); i++) {
            Thread.sleep(500);
        }
        assertThat(scalar(explained)).as("the explanation's delivery was recorded").isNotEqualTo("0");
    }

    /**
     * Waits until the port has had no new call for {@link #SETTLE} (30 s at most) and returns how many calls there were:
     * the count is taken when the system has gone quiet, not at the first moment the row said it went out.
     */
    private static int settle(WaMessagingPort wa) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int last = Mockito.mockingDetails(wa).getInvocations().size();
        long quietSince = System.nanoTime();
        while (System.nanoTime() - quietSince < SETTLE.toNanos()) {
            assertThat(System.nanoTime()).as("the WhatsApp port never went quiet").isLessThan(deadline);
            Thread.sleep(250);
            int now = Mockito.mockingDetails(wa).getInvocations().size();
            if (now != last) {
                last = now;
                quietSince = System.nanoTime();
            }
        }
        return last;
    }

    /** The bodies of this document's rows whose delivery the provider accepted. */
    private static List<String> deliveredBodiesOf(UUID documentId) throws Exception {
        List<String> bodies = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "select body from baymax.outbound_message where document_id = ? and delivery_status = 'sent' order by created_at")) {
            s.setObject(1, documentId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    bodies.add(rs.getString(1));
                }
            }
        }
        assertThat(bodies).as("this paper's delivered rows").isNotEmpty();
        return bodies;
    }

    /**
     * The shared schema-derived sweep ({@link DeletionSweep}): {@code table=count} for every table still holding this
     * document outside {@link DeletionSweep#SURVIVES}, so this journey and the {@code /app/api} delete tests read one
     * list of survivors.
     */
    private static List<String> tablesStillHolding(UUID documentId) throws Exception {
        try (Connection c = connect()) {
            return DeletionSweep.tablesStillHolding(c, documentId);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static List<String> column(String query) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The {@code src} of every {@code <img>} on the page pointing at this document's pictures, matching {@code tail}. */
    private static List<String> srcs(String html, String tail) {
        Matcher m = Pattern.compile("<img [^>]*src=\"(/app/documents/[0-9a-f-]{36}" + tail + ")\"").matcher(html);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /**
     * What a browser does with an {@code <img src>}: ask the app (a 302 to a freshly signed URL), follow it to the bucket,
     * and decode what comes back. Fails unless the bytes are a JPEG that decodes.
     */
    private static BufferedImage picture(WebTestClient web, String session, String src) throws Exception {
        String signed = web.get().uri(URI.create(src)).cookie("baymax_session", session).exchange()
                .expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(signed).as("the signed URL for " + src).isNotNull();
        HttpResponse<byte[]> got = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(signed)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(got.statusCode()).as("the bucket served " + src).isEqualTo(200);
        byte[] bytes = got.body();
        assertThat(bytes).as("JPEG magic bytes for " + src).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("a decodable image for " + src).isNotNull();
        return image;
    }

    /** The sheet, the multipart post, the reading page, then the status poll until DONE; returns the document's path. */
    private String uploadAndWaitUntilDone(WebTestClient web, String session, PatientProfile ma) throws Exception {
        String documentPath = upload(web, session, ma);
        assertThat(waitUntilFinished(web, session, documentPath)).as("the paper was read").isEqualTo("DONE");
        return documentPath;
    }

    /**
     * The sheet for this patient, then the multipart post with the sheet's own key; returns the new document's path once
     * its page answers. The same form is then sent again, as a double tap sends it, or Back to the sheet with the same file (D3):
     * it lands on the same document, and the database holds no second one.
     */
    private String upload(WebTestClient web, String session, PatientProfile ma) throws Exception {
        String sheet = get(web, session, "/app/add?patient=" + ma.id());
        assertThat(sheet).containsPattern("name=\"patient\" value=\"" + ma.id() + "\" required checked>");
        java.util.regex.Matcher key = java.util.regex.Pattern.compile("name=\"upload_key\" value=\"([0-9a-f-]{36})\"").matcher(sheet);
        assertThat(key.find()).as("the sheet carries its press's key").isTrue();
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.id().toString());
        body.part("upload_key", key.group(1));
        body.part("file", paintedPage()).filename("page-1.jpg").contentType(MediaType.IMAGE_JPEG);
        String documentPath = web.post().uri("/app/add").cookie("baymax_session", session)
                .body(BodyInserters.fromMultipartData(body.build())).exchange()
                .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(documentPath).matches("/app/documents/[0-9a-f-]{36}");
        String papers = "select count(*) from baymax.document where patient_id = '" + ma.id() + "'";
        String before = scalar(papers);
        String resent = web.post().uri("/app/add").cookie("baymax_session", session)
                .body(BodyInserters.fromMultipartData(body.build())).exchange()
                .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(resent).as("the same press, sent again, opens the same paper").isEqualTo(documentPath);
        assertThat(scalar(papers)).as("and makes no second one").isEqualTo(before);
        String first = get(web, session, documentPath);
        // still reading, or already read: as a result, or as a retake (B5)
        assertThat(first).containsAnyOf("id=\"progress\"", "id=\"urgency\"", "id=\"retake\"");
        return documentPath;
    }

    /** Polls the status (30 s at most) until the paper is no longer RECEIVED or PROCESSING; returns that status. */
    private static String waitUntilFinished(WebTestClient web, String session, String documentPath) throws Exception {
        String status = "";
        for (int i = 0; i < 60 && (status.isEmpty() || status.equals("RECEIVED") || status.equals("PROCESSING")); i++) {
            if (!status.isEmpty()) {
                Thread.sleep(500);
            }
            String json = web.get().uri(documentPath + "/status").cookie("baymax_session", session).exchange()
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
            assertThat(json).matches("\\{\"status\":\"[A-Z_]+\"}").doesNotContain("urgency");
            status = json.replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
        }
        return status;
    }

    /** The add sheet's allowance line ({@code <p class="quota">}), as served. */
    private static String quotaLine(String sheet) {
        Matcher m = Pattern.compile("<p[^>]* class=\"quota\">[^<]*</p>").matcher(sheet);
        assertThat(m.find()).as("the allowance line on the add sheet").isTrue();
        return m.group();
    }

    /** A form's hidden fields, name → value, in order. */
    private static Map<String, String> hiddenFields(String form) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        Matcher m = Pattern.compile("<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\">").matcher(form);
        while (m.find()) {
            fields.put(m.group(1), m.group(2));
        }
        return fields;
    }

    private static String get(WebTestClient web, String session, String uri) {
        return web.get().uri(uri).cookie("baymax_session", session).exchange().expectStatus().isOk()
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
