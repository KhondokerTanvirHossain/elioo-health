package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.testsupport.LoggedText;
import com.elioo.baymax.web.adapter.in.PhoneDisplay;
import com.elioo.baymax.testsupport.PaintedPaper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.util.UriUtils;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B1 step 9: no {@code /app/api} request puts the family's number or a patient's name into a log line at INFO or
 * above — not in the message, not in an attached exception's chain, not in an MDC value; each field checked on its own,
 * raw and percent-encoded. The requests put the name and the number in the query and in a catch-all path segment too,
 * so the access line must log the matched route pattern, never the path the client sent. This is the Baymax module's
 * own logging; the deployable's request logger ({@code IWebFilter}, medscribe-ai) is swept by the same recipe in
 * medscribe-ai's {@code AppApiDeployableTest}.
 * Every route is exercised, read from the context's routers: a route added later with no request below fails here
 * instead of going unchecked. The capture starts after the context is up, so the events are the requests' own, and at
 * least one {@code [baymax-api]} line must be among them — a sweep over nothing would pass for the wrong reason.
 */
@Testcontainers
class AppApiLogSafetyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    /** Object storage, so an upload and a delete reach their success paths and the lines written there are swept (B4b, B4c). */
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    private static final String NUMBER = "+8801700000123";
    private static final String NAME = "রোকেয়া পরীক্ষা";
    /** An iPhone photo named for the patient (Review Focus 3): never logged, never returned. */
    private static final String FILENAME = NAME + ".jpg";
    private static final String OWNER = "Tanvir";
    /** The other family's number and owner: their requests are the 404 branch of every route. */
    private static final String OTHER_NUMBER = "+8801700000124";
    private static final String OTHER_OWNER = "Karim";
    /** A share member's number and owner (plan B5): their requests read and render their own account. */
    private static final String MEMBER_NUMBER = "+8801700000125";
    private static final String MEMBER_OWNER = "Selim";
    /** Every family the test seeds, by its number: the needles are derived from this list, never written per family. */
    private static final Map<String, String> FAMILIES = Map.of(NUMBER, "the owner's", OTHER_NUMBER, "the other family's",
            MEMBER_NUMBER, "the share member's");

    /** A needle and what it is, so a leak is reported by its label, never by its text. */
    record Needle(String label, String text) {
    }

    /**
     * Each needle raw and as it appears in a logged URI: a path or query is logged percent-encoded, so the raw Bangla
     * and the raw {@code +880…} alone would miss a name in a path ({@code %E0%A6%B0…}) or a number ({@code %2B880…}).
     * Plan B5: the owners' names, and every seeded family's number in every form the পরিবার JSON or a log line could
     * carry it — E.164 without {@code +}, percent-encoded, the 11- and 10-digit national forms, {@code PhoneDisplay.full},
     * and each form again in Bangla digits (a Bangla page shows them so).
     */
    private static final List<Needle> NEEDLES = needles();
    /**
     * Each number's last eight digits as a token: a UUID, a hash or a duration can hold them by chance, so they are
     * matched with no hex digit on either side, never as a raw substring (the Bangla form is a plain needle, which
     * nothing else a log writes can hold).
     */
    private static final Map<String, Pattern> LAST_EIGHTS = FAMILIES.entrySet().stream().collect(Collectors.toMap(
            e -> e.getValue() + " number, last eight digits", e -> token(e.getKey().substring(e.getKey().length() - 8))));

    private static List<Needle> needles() {
        List<Needle> out = new ArrayList<>();
        out.add(new Needle("the patient's name", NAME));
        out.add(new Needle("the patient's name, form-encoded", URLEncoder.encode(NAME, StandardCharsets.UTF_8)));
        out.add(new Needle("the patient's name, path-encoded", UriUtils.encodePathSegment(NAME, StandardCharsets.UTF_8)));
        out.add(new Needle("the patient's name, query-encoded", UriUtils.encodeQueryParam(NAME, StandardCharsets.UTF_8)));
        out.add(new Needle("the filename", FILENAME));
        out.add(new Needle("the filename, form-encoded", URLEncoder.encode(FILENAME, StandardCharsets.UTF_8)));
        out.add(new Needle("the shown number's prefix", "+880 1700"));
        out.add(new Needle("the owner's name", OWNER));
        out.add(new Needle("the other family's owner's name", OTHER_OWNER));
        out.add(new Needle("the share member's name", MEMBER_OWNER));
        FAMILIES.forEach((number, whose) -> {
            String digits = number.substring(1);
            String national = "0" + digits.substring(3);
            String ten = digits.substring(3);
            String shown = PhoneDisplay.full(number);
            Map<String, String> forms = new LinkedHashMap<>();
            forms.put("E.164 digits", digits);
            forms.put("E.164, percent-encoded", "%2B" + digits);
            forms.put("11-digit national", national);
            forms.put("10-digit national", ten);
            forms.put("as shown", shown);
            forms.put("E.164 digits in Bangla", bangla(digits));
            forms.put("11-digit national in Bangla", bangla(national));
            forms.put("10-digit national in Bangla", bangla(ten));
            forms.put("as shown in Bangla", bangla(shown));
            forms.put("last eight digits in Bangla", bangla(digits.substring(digits.length() - 8)));
            forms.forEach((form, text) -> out.add(new Needle(whose + " number, " + form, text)));
        });
        return List.copyOf(out);
    }
    /** Where the minted sign-in code is written, at DEBUG only (DR-39): the second appender reads it from here. */
    private static final String DELIVERY_LOGGER = "com.elioo.baymax.web.adapter.out.delivery";
    private static final Pattern MINTED = Pattern.compile("otp fallback .*code=([0-9]{6})");

    /**
     * A sign-in code as a token, never a substring: INFO lines carry UUIDs, 64-hex phone hashes and durations, any of
     * which can hold six given digits by chance, so a raw {@code contains} would fail for the wrong reason.
     */
    static Pattern token(String code) {
        return Pattern.compile("(?<![0-9A-Fa-f])" + Pattern.quote(code) + "(?![0-9A-Fa-f])");
    }

    /** The same text with Bangla digits in place of ASCII ones. */
    static String bangla(String ascii) {
        StringBuilder b = new StringBuilder();
        ascii.chars().forEach(c -> b.append(c >= '0' && c <= '9' ? (char) ('০' + (c - '0')) : (char) c));
        return b.toString();
    }

    @Test
    void noApiRouteLogsTheOwnersNumberOrThePatientsNameAtInfoOrAbove() {
        AppApiContext.runner(POSTGRES, MINIO).withPropertyValues("baymax.auth.web-mints-code=true", "baymax.auth.hmac-secret=log-safety-secret").run(context -> {
            FamilyAccount family = AppApiContext.family(context, NUMBER, OWNER);
            PatientProfile rokeya = AppApiContext.patient(context, family.id(), NAME);
            PatientProfile karim = AppApiContext.patient(context, family.id(), OTHER_OWNER);
            UUID paper = AppApiContext.paper(context, POSTGRES, rokeya, "now");
            AppApiContext.value(POSTGRES, rokeya, paper, NAME, "6.9");
            // the paper's page in the bucket and the ledger, so the owner's delete below empties a real prefix
            context.getBean(DocumentStorageUseCase.class).storePage(rokeya.familyId(), rokeya.id(), paper, 1, PaintedPaper.paintedPage()).block();
            String token = AppApiContext.session(context, family.id());
            FamilyAccount other = AppApiContext.family(context, OTHER_NUMBER, OTHER_OWNER);
            String otherToken = AppApiContext.session(context, other.id());
            // a sibling who sees Karim through a share: their writes on him are the 403 branch of the পরিবার routes
            FamilyAccount member = AppApiContext.family(context, MEMBER_NUMBER, MEMBER_OWNER);
            AppApiContext.share(context, karim, member.whatsappNumber());
            String memberToken = AppApiContext.session(context, member.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            // each route's requests: the success path and its refusals, so every branch that could log is reached
            Map<String, BiConsumer<WebTestClient, String>> exercise = new LinkedHashMap<>();
            exercise.put("GET /app/api/me", (c, t) -> {
                c.get().uri("/app/api/me").cookie("baymax_session", t).exchange().expectStatus().isOk();
                c.get().uri(b -> b.path("/app/api/me").queryParam("probe", NAME).queryParam("n", NUMBER).build())
                        .cookie("baymax_session", t).exchange().expectStatus().isOk();
                c.get().uri("/app/api/me").cookie("baymax_session", t).cookie("baymax_patient", rokeya.id().toString())
                        .exchange().expectStatus().isOk();
                c.get().uri("/app/api/me").exchange().expectStatus().isUnauthorized();
            });
            exercise.put("PUT /app/api/active-patient", (c, t) -> {
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("patient_id", rokeya.id().toString())).exchange().expectStatus().isNoContent();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", UUID.randomUUID().toString()))
                        .exchange().expectStatus().isNotFound();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"patient_id\": " + NAME.length() + ", bad json")
                        .exchange().expectStatus().isBadRequest();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("patient_id", NAME)).exchange().expectStatus().isForbidden();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("patient_id", NAME)).exchange().expectStatus().isForbidden();
            });
            exercise.put("GET /app/api/documents/{id}", (c, t) -> {
                // the owner's GET carries the patient's name and a value named after her; another family's and a
                // visitor's are refused before anything is read
                // both needles provably reached the handler's output, so the sweep below ran over a response carrying them
                c.get().uri("/app/api/documents/" + paper).cookie("baymax_session", t).exchange().expectStatus().isOk()
                        .expectBody().jsonPath("$.patient.name").isEqualTo(NAME)
                        .jsonPath("$.values.length()").isEqualTo(1)
                        .jsonPath("$.values[0].name").isEqualTo(NAME);
                c.get().uri("/app/api/documents/" + paper).cookie("baymax_session", otherToken).exchange().expectStatus().isNotFound();
                c.get().uri("/app/api/documents/" + paper).exchange().expectStatus().isUnauthorized();
                c.get().uri(b -> b.path("/app/api/documents/{id}").build(NAME)).cookie("baymax_session", t)
                        .exchange().expectStatus().isNotFound();
            });
            exercise.put("GET /app/api/documents/{id}/status", (c, t) -> {
                c.get().uri("/app/api/documents/" + paper + "/status").cookie("baymax_session", t).exchange().expectStatus().isOk();
                c.get().uri("/app/api/documents/" + paper + "/status").cookie("baymax_session", otherToken).exchange().expectStatus().isNotFound();
                c.get().uri(b -> b.path("/app/api/documents/{id}/status").build(NAME)).cookie("baymax_session", t)
                        .exchange().expectStatus().isNotFound();
            });
            exercise.put("GET /app/api/documents/{id}/delete-token", (c, t) -> {
                c.get().uri("/app/api/documents/" + paper + "/delete-token").cookie("baymax_session", t).exchange().expectStatus().isOk();
                c.get().uri("/app/api/documents/" + paper + "/delete-token").cookie("baymax_session", otherToken)
                        .exchange().expectStatus().isNotFound();
            });
            exercise.put("DELETE /app/api/documents/{id}", (c, t) -> {
                Map<?, ?> minted = c.get().uri("/app/api/documents/" + paper + "/delete-token").cookie("baymax_session", t).exchange()
                        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
                Map<String, Object> confirm = Map.of("confirm_token", minted.get("confirm_token"));
                c.method(HttpMethod.DELETE).uri("/app/api/documents/" + paper).cookie("baymax_session", otherToken).header("X-Medioo", "1")
                        .header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON).bodyValue(confirm)
                        .exchange().expectStatus().isNotFound();
                c.method(HttpMethod.DELETE).uri("/app/api/documents/" + paper).cookie("baymax_session", t).header("X-Medioo", "1")
                        .header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("confirm_token", NAME)).exchange().expectStatus().isBadRequest();
                c.method(HttpMethod.DELETE).uri("/app/api/documents/" + paper).cookie("baymax_session", t).header("X-Medioo", "1")
                        .header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON).bodyValue(confirm)
                        .exchange().expectStatus().isOk();
            });
            // the পরিবার tab (plan B5): the only answer in /app/api that carries the number, and a flag typed as the name
            exercise.put("GET /app/api/family", (c, t) -> {
                c.get().uri("/app/api/family").cookie("baymax_session", t).exchange().expectStatus().isOk()
                        .expectBody().jsonPath("$.account.number").isEqualTo(PhoneDisplay.full(NUMBER))
                        .jsonPath("$.account.owner_name").isEqualTo(OWNER);
                c.get().uri(b -> b.path("/app/api/family").queryParam("probe", NAME).queryParam("n", NUMBER).build())
                        .cookie("baymax_session", t).cookie("baymax_lang", "en").exchange().expectStatus().isOk();
                c.get().uri("/app/api/family").cookie("baymax_session", memberToken).exchange().expectStatus().isOk();
                c.get().uri("/app/api/family").exchange().expectStatus().isUnauthorized();
            });
            exercise.put("PUT /app/api/patients/{id}/flags", (c, t) -> {
                json(c.put().uri("/app/api/patients/" + rokeya.id() + "/flags"), t, Map.of("chronic_flags", List.of("diabetes", NAME)))
                        .expectStatus().isOk().expectBody().jsonPath("$.chronic_flags[1].value").isEqualTo(NAME);
                refusedByTheGuard(c, "/app/api/patients/" + rokeya.id() + "/flags", t, Map.of("chronic_flags", List.of(NAME, NUMBER)));
                json(c.put().uri("/app/api/patients/" + rokeya.id() + "/flags"), t,
                        Map.of("chronic_flags", Collections.nCopies(21, NAME + NUMBER)))
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
                json(c.put().uri("/app/api/patients/" + rokeya.id() + "/flags"), t, Map.of("chronic_flags", List.of(NAME.repeat(4))))
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
                json(c.put().uri("/app/api/patients/" + karim.id() + "/flags"), memberToken, Map.of("chronic_flags", List.of(NAME)))
                        .expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
                json(c.put().uri("/app/api/patients/" + rokeya.id() + "/flags"), otherToken, Map.of("chronic_flags", List.of(NAME)))
                        .expectStatus().isNotFound();
                json(c.put().uri(b -> b.path("/app/api/patients/{id}/flags").build(NAME)), t, Map.of("chronic_flags", List.of()))
                        .expectStatus().isNotFound();
            });
            exercise.put("PUT /app/api/patients/{id}/reminders", (c, t) -> {
                json(c.put().uri("/app/api/patients/" + rokeya.id() + "/reminders"), t, Map.of("on", true))
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
                refusedByTheGuard(c, "/app/api/patients/" + rokeya.id() + "/reminders", t, Map.of("on", false, "name", NAME, "n", NUMBER));
                json(c.put().uri("/app/api/patients/" + karim.id() + "/reminders"), memberToken, Map.of("on", false))
                        .expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
                json(c.put().uri("/app/api/patients/" + rokeya.id() + "/reminders"), otherToken, Map.of("on", false))
                        .expectStatus().isNotFound();
                json(c.put().uri("/app/api/patients/" + rokeya.id() + "/reminders"), t, Map.of("on", false))
                        .expectStatus().isOk().expectBody().jsonPath("$.reminders").isEqualTo("stopped_for_patient")
                        .jsonPath("$.name").isEqualTo(NAME);
            });
            exercise.put("PUT /app/api/family/reminders", (c, t) -> {
                json(c.put().uri("/app/api/family/reminders"), t, Map.of("on", true))
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
                refusedByTheGuard(c, "/app/api/family/reminders", t, Map.of("on", false, "name", NAME, "n", NUMBER));
                json(c.put().uri("/app/api/family/reminders"), memberToken, Map.of("on", false)).expectStatus().isOk();
                json(c.put().uri("/app/api/family/reminders"), t, Map.of("on", false)).expectStatus().isOk()
                        .expectBody().jsonPath("$.account.number").isEqualTo(PhoneDisplay.full(NUMBER))
                        .jsonPath("$.account.reminders_stopped").isEqualTo(true);
            });
            exercise.put("PUT /app/api/language", (c, t) -> {
                json(c.put().uri("/app/api/language"), t, Map.of("lang", "en")).expectStatus().isNoContent();
                json(c.put().uri("/app/api/language"), t, Map.of("lang", NAME + NUMBER)).expectStatus().isNoContent();
                c.put().uri("/app/api/language").cookie("baymax_session", t).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("lang", NAME)).exchange().expectStatus().isForbidden();
            });
            exercise.put("* /app/api/**", (c, t) -> {
                c.get().uri("/app/api/nothing-here").cookie("baymax_session", t).exchange().expectStatus().isNotFound();
                c.post().uri("/app/api/nothing-here").cookie("baymax_session", t).header("X-Medioo", "1")
                        .contentType(MediaType.TEXT_PLAIN).bodyValue(NAME).exchange().expectStatus().isEqualTo(415);
                // the catch-all is reached before any route decides anything: whatever the client put in the path or
                // the query must not reach the access line, with a session and without one
                c.get().uri(b -> b.path("/app/api/x").queryParam("q", NAME).queryParam("n", NUMBER).build())
                        .cookie("baymax_session", t).exchange().expectStatus().isNotFound();
                c.get().uri(b -> b.path("/app/api/{name}").build(NAME)).cookie("baymax_session", t)
                        .exchange().expectStatus().isNotFound();
                c.get().uri(b -> b.path("/app/api/{n}/{name}").build(NUMBER, NAME)).exchange().expectStatus().isUnauthorized();
            });
            // sign-in needs no session (the token is not sent): the number in the body, then the code it minted
            ListAppender<ILoggingEvent> codes = new ListAppender<>();
            AtomicReference<String> login = new AtomicReference<>();
            exercise.put("POST /app/api/sign-in/request", (c, t) -> {
                signIn(c, Map.of("whatsapp_number", NUMBER)).expectStatus().isOk();
                login.set(signIn(c, Map.of("whatsapp_number", "0" + NUMBER.substring(4), "confirmed", true, "next", "/app/home"))
                        .expectStatus().isOk().returnResult(String.class).getResponseCookies().getFirst("baymax_login").getValue());
                signIn(c, Map.of("whatsapp_number", "12", "confirmed", true)).expectStatus().isBadRequest();
                c.post().uri("/app/api/sign-in/request").header("X-Medioo", "1").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("whatsapp_number", NUMBER, "confirmed", true))
                        .exchange().expectStatus().isForbidden();
            });
            exercise.put("POST /app/api/sign-in/verify", (c, t) -> {
                String code = minted(codes).stream().reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no code was minted"));
                String wrong = code.equals("000000") ? "111111" : "000000";
                verifyCode(c, login.get(), wrong).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CODE_WRONG\"}", true);
                verifyCode(c, login.get(), code).expectStatus().isOk();
            });
            exercise.put("GET /app/api/upload-options", (c, t) -> c.get().uri("/app/api/upload-options").cookie("baymax_session", t)
                    .exchange().expectStatus().isOk());
            // the success path first: the photo is named for the patient, and the line intake and the handler write once
            // it is stored is what must not carry the name (Review Focus 3)
            AtomicReference<String> uploaded = new AtomicReference<>();
            exercise.put("POST /app/api/uploads", (c, t) -> {
                MultipartBodyBuilder ok = new MultipartBodyBuilder();
                ok.part("patient", rokeya.id().toString());
                ok.part("upload_key", "5c0d8e3a-2f41-4b6a-9e7d-8a1b2c3d4e5f");
                ok.part("file", PaintedPaper.paintedPage()).filename(FILENAME).contentType(MediaType.IMAGE_JPEG);
                String body = new String(upload(c, t, ok).expectStatus().isOk().expectBody().returnResult().getResponseBody(),
                        StandardCharsets.UTF_8);
                assertThat(body).as("the upload's answer").doesNotContain(NAME);
                Matcher id = Pattern.compile("\"documents\":\\[\\{\"id\":\"([0-9a-f-]{36})\"").matcher(body);
                assertThat(id.find()).as("documents[0].id in the answer").isTrue();
                uploaded.set(id.group(1));

                MultipartBodyBuilder nobody = new MultipartBodyBuilder();
                nobody.part("upload_key", "5c0d8e3a-2f41-4b6a-9e7d-8a1b2c3d4e60");
                nobody.part("file", PaintedPaper.paintedPage()).filename(FILENAME).contentType(MediaType.IMAGE_JPEG);
                upload(c, t, nobody).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CHOOSE_PATIENT\"}", true);

                MultipartBodyBuilder large = new MultipartBodyBuilder();
                large.part("patient", rokeya.id().toString());
                large.part("upload_key", "5c0d8e3a-2f41-4b6a-9e7d-8a1b2c3d4e61");
                large.part("file", new byte[26 * 1024 * 1024]).filename(FILENAME).contentType(MediaType.IMAGE_JPEG);
                upload(c, t, large).expectStatus().isEqualTo(413).expectBody().json("{\"reason\":\"TOO_LARGE\"}", true);
            });

            List<String> apiRoutes = AppApiRouteContractTest.routes(context).stream()
                    .filter(r -> r.pattern().startsWith(AppApiRouter.BASE + "/")).map(RouteWalk.Route::line).sorted().toList();
            assertThat(apiRoutes).as("routes read from the context").isNotEmpty();
            assertThat(exercise.keySet()).as("every /app/api route has requests in this test").containsExactlyInAnyOrderElementsOf(apiRoutes);

            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Level before = root.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            root.addAppender(appender);
            root.setLevel(Level.INFO);
            Logger delivery = (Logger) LoggerFactory.getLogger(DELIVERY_LOGGER);
            Level deliveryBefore = delivery.getLevel();
            codes.start();
            delivery.addAppender(codes);
            delivery.setLevel(Level.DEBUG);   // the log fallback writes the code at DEBUG only
            try {
                exercise.values().forEach(requests -> requests.accept(client, token));
            } finally {
                root.detachAppender(appender);
                root.setLevel(before);
                delivery.detachAppender(codes);
                delivery.setLevel(deliveryBefore);
            }

            // the code is a needle only if it is real: minted, and seen at DEBUG by the same token pattern
            List<String> minted = minted(codes);
            assertThat(minted).as("the confirmed sign-in request minted a code").isNotEmpty();
            for (String code : minted) {
                assertThat(codes.list).as("the DEBUG appender saw the code as a token")
                        .anyMatch(e -> e.getLevel() == Level.DEBUG && token(code).matcher(e.getFormattedMessage()).find());
            }
            List<Pattern> codeTokens = minted.stream().map(AppApiLogSafetyTest::token).toList();

            List<ILoggingEvent> events = appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList();
            assertThat(events.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("[baymax-api]")))
                    .as("the requests logged their access lines, so the sweep below reads something").isNotEmpty();
            // the success path was swept, not only refusals: its line names the id the answer returned, as a field
            assertThat(events.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> Pattern.compile("(^|\\s)documentId=" + uploaded.get() + "(\\s|$)").matcher(m).find()))
                    .as("the upload's success line, by the id it returned").isNotEmpty();
            List<String> leaks = new ArrayList<>();
            for (ILoggingEvent e : events) {
                check(leaks, e, "message", e.getFormattedMessage(), codeTokens);
                check(leaks, e, "message+throwable", LoggedText.of(e), codeTokens);
                e.getMDCPropertyMap().forEach((k, v) -> check(leaks, e, "mdc." + k, v, codeTokens));
            }
            assertThat(leaks).as("log fields at INFO or above carrying the number, the name or a sign-in code").isEmpty();
        });
    }

    private static WebTestClient.ResponseSpec signIn(WebTestClient c, Map<String, Object> body) {
        return c.post().uri("/app/api/sign-in/request").header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static WebTestClient.ResponseSpec verifyCode(WebTestClient c, String login, String code) {
        return c.post().uri("/app/api/sign-in/verify").header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                .cookie("baymax_login", login).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", code)).exchange();
    }

    /** The codes the log fallback wrote, in order. */
    private static List<String> minted(ListAppender<ILoggingEvent> codes) {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : List.copyOf(codes.list)) {
            Matcher m = MINTED.matcher(e.getFormattedMessage());
            if (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }


    /**
     * The guard's two refusals of a PUT carrying the name and the number in its body: no session (401
     * {@code UNAUTHENTICATED}, both CSRF headers sent, so the session branch is the one reached) and no {@code X-Medioo}
     * (403 {@code MISSING_HEADER}, with a session).
     */
    private static void refusedByTheGuard(WebTestClient c, String uri, String token, Object body) {
        c.put().uri(uri).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isUnauthorized().expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true);
        c.put().uri(uri).cookie("baymax_session", token).header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isForbidden().expectBody().json("{\"reason\":\"MISSING_HEADER\"}", true);
    }

    /** A JSON mutation with a session and both CSRF headers. */
    private static WebTestClient.ResponseSpec json(WebTestClient.RequestBodySpec spec, String token, Object body) {
        return spec.cookie("baymax_session", token).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static WebTestClient.ResponseSpec upload(WebTestClient c, String token, MultipartBodyBuilder body) {
        return c.post().uri("/app/api/uploads").cookie("baymax_session", token).header("X-Medioo", "1")
                .header("Origin", AppApiContext.ORIGIN).body(BodyInserters.fromMultipartData(body.build())).exchange();
    }
    private static void check(List<String> leaks, ILoggingEvent e, String field, String value, List<Pattern> codeTokens) {
        if (value == null) {
            return;
        }
        // each needle by its label, never its text: the report then says which form leaked without echoing it
        List<String> hits = NEEDLES.stream().filter(n -> value.contains(n.text())).map(Needle::label)
                .collect(Collectors.toCollection(ArrayList::new));
        codeTokens.stream().filter(p -> p.matcher(value).find()).forEach(p -> hits.add("a sign-in code"));
        LAST_EIGHTS.forEach((label, p) -> {
            if (p.matcher(value).find()) {
                hits.add(label);
            }
        });
        if (!hits.isEmpty()) {
            leaks.add(e.getLevel() + " " + e.getLoggerName() + " " + field + " carries " + hits);
        }
    }
}
