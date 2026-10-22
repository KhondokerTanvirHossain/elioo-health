package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.testsupport.UploadRefusalSweep;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan W7: the add sheet's two routes as the Next screens' stub Java serves them, one MSW fixture per case, in the
 * generated-file envelope and compared byte for byte with the committed {@code medioo-web/fixtures/api/upload-options/}
 * and {@code medioo-web/fixtures/api/uploads/}. Over the real {@link AppApiRouter}, {@link AppApiGuard} and
 * {@link UploadApiHandler}, with a stub session and a mocked {@link UploadUseCase} serving fixed ids, so the bodies are
 * byte-stable.
 *
 * <p>{@code uploads/refusals.json} is every refusal the upload path can make, as this router answers it: the reasons
 * {@link UploadRefusalSweep} reads from the source (the list {@code UploadApiReasonsTest} holds to {@link ApiReason}),
 * each thrown by the use case and served through the guard, grouped by the name the family's sheet receives. The web's
 * copy map ({@code upload-reasons.ts}) is held to this file, so a refusal intake learns later reaches the sheet's copy
 * test without anyone listing it by hand.
 *
 * <p>The patients are {@link MeApiContractTest}'s, so a page reading {@code me} and the options sees the same family.
 */
class UploadApiContractTest {

    private static final Path FIXTURES = Path.of("../medioo-web/fixtures/api");
    private static final Path WRITTEN = Path.of("build/web-contract");
    private static final Instant NOW = PaperFixtures.NOW;
    private static final UUID FAMILY = PaperFixtures.FAMILY;
    /** The month the free allowance counts (the reset is the first of the next one). */
    private static final YearMonth MONTH = YearMonth.of(2026, 10);
    private static final String KEY = "0b7c4f3e-9a1d-4e2b-8c5f-6d7e8f9a0b1c";
    private static final List<UUID> DOCS = List.of(
            UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100811"),
            UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100812"),
            UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100813"));

    private final ObjectMapper json = new ObjectMapper();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final UploadUseCase uploads = mock(UploadUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();

    UploadApiContractTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
    }

    private WebTestClient client() {
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        return WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, mock(AttentionUseCase.class), properties),
                new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy(), mock(DocumentStorageUseCase.class)),
                new UploadApiHandler(uploads, properties, new UiCopy()),
                new PaperStatusApiHandler(mock(ResultUseCase.class)),
                new PaperDeleteApiHandler(timeline, new DeleteConfirmation(properties, Clock.systemUTC())),
                new FamilyApiHandler(mock(TimelineUseCase.class), mock(NudgeDataPort.class), mock(NudgeOptOutUseCase.class),
                        new UiCopy(), properties),
                new HealthApiHandler(mock(HealthUseCase.class)),
                B3Handlers.nowBanner(), B3Handlers.attention(), B3Handlers.timeline(),
                new PreferencesApiHandler(properties), B3Handlers.thread(),
                guard)).build();
    }

    // ---- GET /app/api/upload-options ------------------------------------------------------------------------

    /** A free family that has had {@code used} of its 30 papers read this month. */
    private static DocumentAllowance free(int used) {
        return new DocumentAllowance(true, 30, used, MONTH);
    }

    static Map<String, UploadOptions> optionCases() {
        Map<String, UploadOptions> cases = new LinkedHashMap<>();
        // the only patient the family may send for: preselected, never a guess
        cases.put("owner-one", new UploadOptions(List.of(MeApiContractTest.ma()), List.of(), free(4)));
        // two the family owns: nobody preselected, the sheet asks
        cases.put("owner-two", new UploadOptions(List.of(MeApiContractTest.ma(), MeApiContractTest.baba()), List.of(), free(4)));
        // me/two-patients' family: Ma is theirs, the sibling's mother is shared with them to see only
        cases.put("view-only", new UploadOptions(List.of(MeApiContractTest.ma()), List.of(MeApiContractTest.shared()), free(4)));
        // this month's free papers are read: the sheet says until when, and offers no press
        cases.put("cap-reached", new UploadOptions(List.of(MeApiContractTest.ma()), List.of(), free(30)));
        // the area's longest: me/long-name's patient on the sheet's chip and title
        cases.put("long-name", new UploadOptions(List.of(MeApiContractTest.maLong()), List.of(), free(4)));
        return cases;
    }

    static Stream<String> optionCaseNames() {
        return optionCases().keySet().stream();
    }

    private EntityExchangeResult<byte[]> options(UploadOptions o) {
        when(uploads.uploadOptions(FAMILY)).thenReturn(Mono.just(o));
        return client().get().uri("/app/api/upload-options").cookie("baymax_session", "tok").exchange().expectBody().returnResult();
    }

    @ParameterizedTest
    @MethodSource("optionCaseNames")
    void eachOptionsCaseIsItsCommittedMswFixture(String name) throws IOException {
        EntityExchangeResult<byte[]> r = options(optionCases().get(name));
        assertThat(r.getStatus().value()).as(name).isEqualTo(200);
        write("upload-options", name, "GET /app/api/upload-options over the UploadApiContractTest case " + name, r);
    }

    @Test
    void theOptionsCasesShowWhatTheirNamesSay() throws IOException {
        Map<String, UploadOptions> cases = optionCases();
        JsonNode one = body(options(cases.get("owner-one")));
        assertThat(one.at("/can_send").size()).isEqualTo(1);
        assertThat(one.at("/preselected_patient_id").asText()).isEqualTo(PaperFixtures.MA_ID.toString());
        assertThat(one.at("/allowance/remaining").asInt()).isEqualTo(26);

        JsonNode two = body(options(cases.get("owner-two")));
        assertThat(two.at("/can_send").size()).isEqualTo(2);
        assertThat(two.at("/preselected_patient_id").isNull()).as("two to send for: nobody is chosen for the family").isTrue();

        JsonNode viewOnly = body(options(cases.get("view-only")));
        assertThat(viewOnly.at("/view_only/0/id").asText()).isEqualTo(MeApiContractTest.shared().patient().id().toString());
        assertThat(viewOnly.at("/view_only/0/owner").asBoolean()).isFalse();

        JsonNode cap = body(options(cases.get("cap-reached")));
        assertThat(cap.at("/allowance/limited").asBoolean()).isTrue();
        assertThat(cap.at("/allowance/remaining").asInt()).isZero();
        assertThat(cap.at("/allowance/resets_on").asText()).isEqualTo("2026-11-01");

        JsonNode longest = body(options(cases.get("long-name")));
        assertThat(longest.at("/can_send/0/name").asText()).isEqualTo(MeApiContractTest.longName());
        // the limits the sheet holds the tray to, never restated in TypeScript
        assertThat(one.at("/limits/max_pages").asInt()).isEqualTo(properties.getExtract().getMaxPages());
        assertThat(one.at("/limits/max_files").asInt()).isPositive();
    }

    // ---- POST /app/api/uploads ------------------------------------------------------------------------------

    private static MultipartBodyBuilder press(String group, int files) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", PaperFixtures.MA_ID.toString());
        body.part("upload_key", KEY);
        body.part("group", group);
        for (int i = 0; i < files; i++) {
            body.part("file", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) i}).filename("page-" + (i + 1) + ".jpg").contentType(MediaType.IMAGE_JPEG);
        }
        return body;
    }

    private EntityExchangeResult<byte[]> post(MultipartBodyBuilder body) {
        return client().post().uri("/app/api/uploads").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .header("Origin", AppApiGuardTest.ORIGIN).body(BodyInserters.fromMultipartData(body.build()))
                .exchange().expectBody().returnResult();
    }

    private static Document doc(UUID id) {
        return Document.received(PaperFixtures.MA_ID, FAMILY, 1, NOW).withId(id);
    }

    @Test
    void oneDocumentIsItsCommittedMswFixture() throws IOException {
        when(uploads.admit(eq(FAMILY), eq(PaperFixtures.MA_ID), any())).thenReturn(Mono.empty());
        when(uploads.upload(eq(FAMILY), eq(PaperFixtures.MA_ID), any(), any(), eq(KEY), any())).thenReturn(Mono.just(List.of(doc(DOCS.get(0)))));
        EntityExchangeResult<byte[]> r = post(press("one", 3));
        assertThat(r.getStatus().value()).as(text(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.at("/documents").size()).isEqualTo(1);
        assertThat(b.at("/next").asText()).as("one paper opens itself").isEqualTo("/app/documents/" + DOCS.get(0));
        write("uploads", "one-document", "POST /app/api/uploads over the UploadApiContractTest case one-document (three pages as one report)", r);
    }

    @Test
    void separatePapersAreTheirCommittedMswFixture() throws IOException {
        when(uploads.admit(eq(FAMILY), eq(PaperFixtures.MA_ID), any())).thenReturn(Mono.empty());
        when(uploads.upload(eq(FAMILY), eq(PaperFixtures.MA_ID), any(), any(), eq(KEY), any()))
                .thenReturn(Mono.just(DOCS.stream().map(UploadApiContractTest::doc).toList()));
        EntityExchangeResult<byte[]> r = post(press("separate", 3));
        assertThat(r.getStatus().value()).as(text(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.at("/documents").size()).isEqualTo(3);
        assertThat(b.at("/next").asText()).as("several land on the papers").isEqualTo("/app/timeline?patient=" + PaperFixtures.MA_ID);
        write("uploads", "separate", "POST /app/api/uploads over the UploadApiContractTest case separate (three pages as separate papers)", r);
    }

    /**
     * Every reason the upload path can be refused for (the sweep, plus the free allowance, which intake raises through
     * {@link FreeTierExceededException} and the sweep's call patterns do not see), thrown by the use case as intake
     * throws it, and answered through the guard. Grouped by name: two reasons with one name (an unsupported image and
     * one that would not re-encode) must be answered alike, or the sheet would say one thing for two answers.
     */
    @Test
    void everyUploadRefusalIsItsCommittedMswFixture() throws IOException {
        Map<String, Integer> swept = new TreeMap<>(UploadRefusalSweep.reasonsWithStatus());
        assertThat(swept).as("the sweep found the refusals; an empty set would pass vacuously").hasSizeGreaterThanOrEqualTo(10);
        swept.put(FreeTierExceededException.DOCUMENTS, 402);
        Map<String, Map<String, Object>> byName = new TreeMap<>();
        List<String> raw = new ArrayList<>();
        for (Map.Entry<String, Integer> e : swept.entrySet()) {
            String reason = e.getKey();
            BaymaxException thrown = reason.equals(FreeTierExceededException.DOCUMENTS)
                    ? new FreeTierExceededException(reason, "refused")
                    : new BaymaxException(HttpStatus.valueOf(e.getValue()), reason, "refused");
            when(uploads.admit(eq(FAMILY), eq(PaperFixtures.MA_ID), any())).thenReturn(Mono.error(thrown));
            EntityExchangeResult<byte[]> r = post(press("one", 1));
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", r.getStatus().value());
            answer.put("body", json.readValue(r.getResponseBody(), Object.class));
            String name = body(r).path("reason").asText();
            assertThat(name).as(reason + " answered " + text(r)).isEqualTo(ApiReason.BY_REASON.get(reason).name());
            assertThat(r.getStatus().value()).as(reason).isEqualTo(e.getValue());
            Map<String, Object> before = byName.putIfAbsent(name, answer);
            if (before != null) {
                assertThat(answer).as(reason + " is answered as the other reasons named " + name).isEqualTo(before);
            }
            raw.add(reason);
        }
        assertThat(raw).as("reasons served").hasSize(swept.size());
        assertThat(byName).as("the names the sheet must word").containsKeys("CHOOSE_PATIENT", "VIEW_ONLY", "FREE_TIER_DOCUMENTS", "TOO_LARGE")
                .doesNotContainKey("NOT_A_RETAKE");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_meta", Snapshot.meta(UploadApiContractTest.class,
                "POST /app/api/uploads refused for every reason UploadRefusalSweep reads from the upload path, by name"));
        envelope.put("body", byName);
        Snapshot.matches(FIXTURES.resolve("uploads/refusals.json"), WRITTEN.resolve("uploads/refusals.json"), Snapshot.json(envelope),
                "cp baymax/" + WRITTEN.resolve("uploads/refusals.json") + " medioo-web/fixtures/api/uploads/refusals.json");
    }

    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        assertThat(committed("upload-options")).as("upload-options fixtures vs the cases")
                .containsExactlyInAnyOrderElementsOf(optionCaseNames().map(n -> n + ".json").toList());
        assertThat(committed("uploads")).as("uploads fixtures vs the cases")
                .containsExactlyInAnyOrder("one-document.json", "separate.json", "refusals.json");
    }

    // ---- support --------------------------------------------------------------------------------------------

    private List<String> committed(String area) throws IOException {
        List<String> files;
        try (Stream<Path> list = Files.list(FIXTURES.resolve(area))) {
            files = list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("the committed fixtures in " + FIXTURES.resolve(area).toAbsolutePath()).isNotEmpty();
        return files;
    }

    private void write(String area, String name, String source, EntityExchangeResult<byte[]> r) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_meta", Snapshot.meta(UploadApiContractTest.class, source));
        envelope.put("status", r.getStatus().value());
        envelope.put("body", json.readValue(r.getResponseBody(), Object.class));
        String file = name + ".json";
        Snapshot.matches(FIXTURES.resolve(area).resolve(file), WRITTEN.resolve(area).resolve(file), Snapshot.json(envelope),
                "cp baymax/" + WRITTEN.resolve(area).resolve(file) + " medioo-web/fixtures/api/" + area + "/" + file);
    }

    private JsonNode body(EntityExchangeResult<byte[]> r) throws IOException {
        return json.readTree(r.getResponseBody());
    }

    private static String text(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }
}
