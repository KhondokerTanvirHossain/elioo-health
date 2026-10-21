package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan B4b step 4: {@code POST /app/api/uploads} and {@code GET /app/api/upload-options} over the router and its guard,
 * with the upload use case mocked. What reaches intake, where the family goes next, and that a refusal is its name and
 * nothing else: never the exception's message, which can carry a filename.
 */
class UploadApiHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4210001");
    private static final UUID MA = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4210002");
    private static final UUID BABA = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4210003");
    private static final String KEY = "0b7c4a52-9a1e-4d1f-8b0e-5f7a2c3d4e10";

    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final UploadUseCase uploads = mock(UploadUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final WebTestClient client;

    UploadApiHandlerTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        // the viewer may send for every patient here unless a test says otherwise: admission is its own test
        when(uploads.admit(any(), any(), any())).thenReturn(Mono.empty());
        AppApiGuard guard = new AppApiGuard(new SessionAuthFilter(auth, properties), properties);
        client = WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(mock(TimelineUseCase.class), mock(AttentionUseCase.class), properties),
                new SignInApiHandler(auth, new SessionAuthFilter(auth, properties), properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy(), mock(DocumentStorageUseCase.class)),
                new UploadApiHandler(uploads, properties, new UiCopy()),
                new PaperStatusApiHandler(mock(ResultUseCase.class)),
                new PaperDeleteApiHandler(mock(TimelineUseCase.class), new DeleteConfirmation(properties, java.time.Clock.systemUTC())),
                new FamilyApiHandler(mock(TimelineUseCase.class), mock(NudgeDataPort.class), mock(NudgeOptOutUseCase.class),
                        new UiCopy(), properties),
                guard)).build();
    }

    private WebTestClient.ResponseSpec post(MultipartBodyBuilder body) {
        return client.post().uri("/app/api/uploads").cookie("baymax_session", "tok").header("X-Medioo", "1")
                .header("Origin", AppApiGuardTest.ORIGIN).body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    private static MultipartBodyBuilder press(UUID patient, String key, String group, String... files) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        if (patient != null) {
            body.part("patient", patient.toString());
        }
        if (key != null) {
            body.part("upload_key", key);
        }
        if (group != null) {
            body.part("group", group);
        }
        for (String name : files) {
            body.part("file", new byte[]{1, 2, 3, (byte) name.length()}).filename(name);
        }
        return body;
    }

    private static Document doc(UUID patient) {
        return Document.received(patient, FAMILY, 1, NOW).withId(UUID.randomUUID());
    }

    @Test
    void aPressReturnsItsDocumentIdsAndWhereToGo() {
        Document one = doc(MA);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Upload>> sent = ArgumentCaptor.forClass(List.class);
        when(uploads.upload(eq(FAMILY), eq(MA), sent.capture(), eq(Grouping.ONE), eq(KEY), any())).thenReturn(Mono.just(List.of(one)));
        post(press(MA, KEY, "one", "p1.jpg", "p2.jpg"))
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"documents\":[{\"id\":\"" + one.id() + "\"}],\"next\":\"/app/documents/" + one.id() + "\"}", true);
        assertThat(sent.getValue()).extracting(Upload::filename).containsExactly("p1.jpg", "p2.jpg");

        Document a = doc(MA);
        Document b = doc(MA);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), eq(Grouping.SEPARATE), eq(KEY), any())).thenReturn(Mono.just(List.of(a, b)));
        post(press(MA, KEY, "separate", "p1.jpg", "p2.jpg"))
                .expectStatus().isOk()
                .expectBody().json("{\"documents\":[{\"id\":\"" + a.id() + "\"},{\"id\":\"" + b.id() + "\"}],"
                        + "\"next\":\"/app/timeline?patient=" + MA + "\"}", true);
    }

    /** No {@code group} part is one report, the sheet's default (D3). */
    @Test
    void aPressWithoutAGroupIsOneReport() {
        Document one = doc(MA);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), eq(Grouping.ONE), eq(KEY), any())).thenReturn(Mono.just(List.of(one)));
        post(press(MA, KEY, null, "p1.jpg")).expectStatus().isOk().expectBody().jsonPath("$.documents[0].id").isEqualTo(one.id().toString());
    }

    /** A group the client misspelt is refused, never guessed as one report: "seperate" would merge papers silently. */
    @Test
    void aGroupThatIsNeitherOneNorSeparateIsInvalid() {
        // intake would accept it: a route that guessed one report would answer 200, not fail on a null
        when(uploads.upload(any(), any(), any(), any(), any(), any())).thenReturn(Mono.just(List.of(doc(MA))));
        post(press(MA, KEY, "seperate", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPressWithoutAPatientIsChoosePatientAndNothingIsUploaded() {
        post(press(null, KEY, "one", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CHOOSE_PATIENT\"}", true);
        // a patient that is not an id is nobody chosen, as on the sheet
        MultipartBodyBuilder named = press(null, KEY, "one", "p1.jpg");
        named.part("patient", "Ma");
        post(named).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CHOOSE_PATIENT\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());
        verify(uploads, never()).upload(any(), any(), any());
    }

    @Test
    void aPressWithoutAValidUploadKeyIsInvalid() {
        post(press(MA, null, "one", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        post(press(MA, "x", "one", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());
        verify(uploads, never()).upload(any(), any(), any());
    }

    @Test
    void aPressWithNoFileIsNoFileAndNothingIsUploaded() {
        post(press(MA, KEY, "one")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"NO_FILE\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());
    }

    /**
     * "আলাদা আলাদা" makes one document, one OCR and one model call per file, so a press may carry no more chosen files
     * than one paper has pages. Both directions: the limit passes, one more is refused before anything is uploaded, and
     * an input the family did not use (an empty part with no name, which the sheet posts) is not a file. A press of
     * thousands of tiny files, the same refusal as the parts arrive, is {@code UploadApiRealServerTest}'s.
     */
    @Test
    void aPressOfMoreFilesThanOnePaperHasPagesIsRefusedBeforeAnyIsUploaded() {
        properties.getExtract().setMaxPages(3);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), eq(Grouping.SEPARATE), eq(KEY), any()))
                .thenReturn(Mono.just(List.of(doc(MA), doc(MA), doc(MA))));

        MultipartBodyBuilder atTheLimit = press(MA, KEY, "separate", "p1.jpg", "p2.jpg", "p3.jpg");
        atTheLimit.part("file", new byte[0]).filename("");
        atTheLimit.part("file", new byte[0]).filename("");
        post(atTheLimit).expectStatus().isOk();

        post(press(MA, KEY, "separate", "p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg"))
                .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"TOO_MANY_FILES\"}", true);
        // one report of four files is four pages: the paper's own limit, by the name intake would give it
        post(press(MA, KEY, "one", "p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg"))
                .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"TOO_MANY_PAGES\"}", true);
        verify(uploads, times(1)).upload(any(), any(), any(), any(), any(), isNull());
    }

    /**
     * Spring's reader refuses a form field over its memory limit with its own exception, which is neither a refusal
     * nor a bad body: unmapped, it was a bodiless 500 and an ERROR line ("unhandled error"), a client's mistake read
     * as a server incident. It is TOO_LARGE, and nothing is logged at ERROR.
     */
    @Test
    void aFormFieldOverTheReadersLimitIsTooLargeAndNoServerFault() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        try {
            MultipartBodyBuilder oversized = press(null, KEY, "one", "p1.jpg");
            oversized.part("patient", "x".repeat(300 * 1024));
            post(oversized).expectStatus().isEqualTo(413).expectBody().json("{\"reason\":\"TOO_LARGE\"}", true);
        } finally {
            root.detachAppender(logged);
        }
        assertThat(logged.list).as("lines at ERROR").noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());
    }

    /**
     * Whether the viewer may send for this patient is asked before a file is joined into memory: a view-only member's
     * press is refused for what it is, and costs no reading. Shown by order: a view-only press with no file at all is
     * VIEW_ONLY, never NO_FILE, which only reading the files could have said.
     */
    @Test
    void aViewOnlyPressIsRefusedBeforeItsFilesAreRead() {
        when(uploads.admit(FAMILY, BABA, null)).thenReturn(Mono.error(BaymaxException.forbidden("shared_patient_read_only", "view only")));
        post(press(BABA, KEY, "one")).expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
        post(press(BABA, KEY, "one", "p1.jpg")).expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
        when(uploads.admit(FAMILY, BABA, null)).thenReturn(Mono.error(BaymaxException.notFound("patient_not_found", "nobody")));
        post(press(BABA, KEY, "one")).expectStatus().isNotFound().expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());
    }

    /**
     * D1: {@code replaces} present but not an id ({@code undefined}, a truncated id) is the client's own mistake, INVALID
     * like a misspelt {@code group}, and never guessed as "replaces nothing": a 200 with an unlinked paper would bring the
     * stale retake card back with no signal. Refused before admission, so nothing is looked up or read.
     */
    @Test
    void aReplacesThatIsNotAnIdIsInvalidAndNothingIsAdmittedOrUploaded() {
        // the wrong path answers too (200, an unlinked paper), so taking it fails on the status, not on a mock's null
        when(uploads.upload(eq(FAMILY), eq(MA), any(), any(), eq(KEY), any())).thenReturn(Mono.just(List.of(doc(MA))));
        for (String bad : List.of("undefined", "5e9edf6d-0a2a-4913", "null")) {
            MultipartBodyBuilder body = press(MA, KEY, "one", "p1.jpg");
            body.part("replaces", bad);
            post(body).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        }
        verify(uploads, never()).admit(any(), any(), any());
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());

        // the other direction: a blank replaces is the same as none
        Document one = doc(MA);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), eq(Grouping.ONE), eq(KEY), isNull())).thenReturn(Mono.just(List.of(one)));
        MultipartBodyBuilder blank = press(MA, KEY, "one", "p1.jpg");
        blank.part("replaces", " ");
        post(blank).expectStatus().isOk();
        verify(uploads).admit(FAMILY, MA, null);
    }

    /** D1: a replaces the use case refuses ({@code not_a_retake}) is NOT_A_RETAKE, asked before any file is read. */
    @Test
    void aReplacesThatIsNotThisPatientsRetakeIsRefusedBeforeItsFilesAreRead() {
        UUID finished = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4210009");
        when(uploads.admit(FAMILY, MA, finished)).thenReturn(Mono.error(BaymaxException.badRequest("not_a_retake", "finished")));
        MultipartBodyBuilder body = press(MA, KEY, "one", "p1.jpg");
        body.part("replaces", finished.toString());
        post(body).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"NOT_A_RETAKE\"}", true);
        verify(uploads).admit(FAMILY, MA, finished);
        verify(uploads, never()).upload(any(), any(), any(), any(), any(), any());
    }

    static Stream<String> namedReasons() {
        return ApiReason.BY_REASON.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namedReasons")
    void aRefusalIsItsNameAndNothingElse(String reason) {
        ApiReason name = ApiReason.BY_REASON.get(reason);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), any(), eq(KEY), any()))
                .thenReturn(Mono.error(new BaymaxException(name.status(), reason, "Ma's report.jpg is bad")));
        post(press(MA, KEY, "one", "Ma's report.jpg"))
                .expectStatus().isEqualTo(name.status())
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"reason\":\"" + name.name() + "\"}", true);
    }

    @Test
    void optionsPreselectOnlyWhenThereIsNobodyElse() {
        PatientSummary ma = summary(MA, "Ma", true);
        PatientSummary baba = summary(BABA, "Baba", true);
        PatientSummary shared = summary(UUID.randomUUID(), "Nani", false);
        preselected(List.of(ma), List.of()).isEqualTo(MA.toString());
        preselected(List.of(ma, baba), List.of()).isEmpty();
        preselected(List.of(ma), List.of(shared)).isEqualTo(MA.toString());
        preselected(List.of(), List.of(shared)).isEmpty();
    }

    private WebTestClient.BodyContentSpec options(UploadOptions o) {
        when(uploads.uploadOptions(FAMILY)).thenReturn(Mono.just(o));
        return client.get().uri("/app/api/upload-options").cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store").expectBody();
    }

    private org.springframework.test.web.reactive.server.JsonPathAssertions preselected(List<PatientSummary> canSend, List<PatientSummary> viewOnly) {
        return options(new UploadOptions(canSend, viewOnly, DocumentAllowance.unlimited(YearMonth.of(2026, 10))))
                .jsonPath("$.preselected_patient_id");
    }

    /**
     * The patients with the name forms React must not build, the allowance as numbers and a date, and every limit the
     * server refuses by, as numbers: the copy a refusal needs comes from here, never from the error body.
     */
    @Test
    void optionsCarryThePatientsTheAllowanceAndTheLimits() {
        properties.getExtract().setMaxPages(7);
        PatientSummary shared = summary(BABA, "Baba", false);
        options(new UploadOptions(List.of(summary(MA, "Ma", true)), List.of(shared),
                new DocumentAllowance(true, 5, 3, YearMonth.of(2026, 10))))
                .jsonPath("$.can_send[0].id").isEqualTo(MA.toString())
                .jsonPath("$.can_send[0].name").isEqualTo("Ma")
                .jsonPath("$.can_send[0].name_possessive").isEqualTo(com.elioo.baymax.common.text.NameGrammar.possessive("Ma"))
                .jsonPath("$.can_send[0].name_objective").isEqualTo(com.elioo.baymax.common.text.NameGrammar.objective("Ma"))
                .jsonPath("$.can_send[0].owner").isEqualTo(true)
                .jsonPath("$.view_only[0].id").isEqualTo(BABA.toString())
                .jsonPath("$.view_only[0].owner").isEqualTo(false)
                .jsonPath("$.allowance.limited").isEqualTo(true)
                .jsonPath("$.allowance.max").isEqualTo(5)
                .jsonPath("$.allowance.remaining").isEqualTo(2)
                .jsonPath("$.allowance.resets_on").isEqualTo("2026-11-01")
                .jsonPath("$.limits.max_pages").isEqualTo(7)
                .jsonPath("$.limits.max_files").isEqualTo(7)
                .jsonPath("$.limits.max_file_bytes").isEqualTo(25 * 1024 * 1024)
                .jsonPath("$.limits.max_request_bytes").isEqualTo(60 * 1024 * 1024)
                .jsonPath("$.limits.downscale_max_edge").isEqualTo(0)
                .jsonPath("$.limits.downscale_quality").isEqualTo(0.92)
                .jsonPath("$.limits.pass_through_bytes").isEqualTo(1_500_000);

        options(new UploadOptions(List.of(), List.of(), DocumentAllowance.unlimited(YearMonth.of(2026, 10))))
                .jsonPath("$.allowance.limited").isEqualTo(false)
                .jsonPath("$.allowance.max").isEmpty()
                .jsonPath("$.allowance.remaining").isEmpty()
                .jsonPath("$.allowance.resets_on").isEmpty()
                .jsonPath("$.can_send").isEmpty()
                .jsonPath("$.view_only").isEmpty();
    }

    /**
     * The name forms are the viewer's language, as the HTML sheet this route replaces writes them ({@code UiCopy.poss}
     * and {@code obj}): "Ma's" for a {@code baymax_lang=en} viewer, never a Bangla case ending React cannot undo.
     */
    @Test
    void theNameIsDeclinedInTheViewersLanguage() {
        UploadOptions o = new UploadOptions(List.of(summary(MA, "Ma", true)), List.of(summary(BABA, "আম্মা", false)),
                DocumentAllowance.unlimited(YearMonth.of(2026, 10)));
        when(uploads.uploadOptions(FAMILY)).thenReturn(Mono.just(o));
        client.get().uri("/app/api/upload-options").cookie("baymax_session", "tok").cookie("baymax_lang", "en").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.can_send[0].name_possessive").isEqualTo("Ma's")
                .jsonPath("$.can_send[0].name_objective").isEqualTo("Ma");
        client.get().uri("/app/api/upload-options").cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.view_only[0].name_possessive").isEqualTo(NameGrammar.possessive("আম্মা"))
                .jsonPath("$.view_only[0].name_objective").isEqualTo(NameGrammar.objective("আম্মা"));
    }

    private static PatientSummary summary(UUID id, String name, boolean owner) {
        return new PatientSummary(new PatientProfile(id, FAMILY, name, 60, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), owner, 0, null);
    }
}
