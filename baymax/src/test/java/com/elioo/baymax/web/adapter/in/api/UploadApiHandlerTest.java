package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
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

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        AppApiGuard guard = new AppApiGuard(new SessionAuthFilter(auth, properties), properties);
        client = WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(mock(TimelineUseCase.class), mock(AttentionUseCase.class), properties),
                new SignInApiHandler(auth, new SessionAuthFilter(auth, properties), properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy()),
                new UploadApiHandler(uploads, properties), guard)).build();
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
        when(uploads.upload(eq(FAMILY), eq(MA), sent.capture(), eq(Grouping.ONE), eq(KEY))).thenReturn(Mono.just(List.of(one)));
        post(press(MA, KEY, "one", "p1.jpg", "p2.jpg"))
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"documents\":[{\"id\":\"" + one.id() + "\"}],\"next\":\"/app/documents/" + one.id() + "\"}", true);
        assertThat(sent.getValue()).extracting(Upload::filename).containsExactly("p1.jpg", "p2.jpg");

        Document a = doc(MA);
        Document b = doc(MA);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), eq(Grouping.SEPARATE), eq(KEY))).thenReturn(Mono.just(List.of(a, b)));
        post(press(MA, KEY, "separate", "p1.jpg", "p2.jpg"))
                .expectStatus().isOk()
                .expectBody().json("{\"documents\":[{\"id\":\"" + a.id() + "\"},{\"id\":\"" + b.id() + "\"}],"
                        + "\"next\":\"/app/timeline?patient=" + MA + "\"}", true);
    }

    /** No {@code group} part is one report, the sheet's default (D3). */
    @Test
    void aPressWithoutAGroupIsOneReport() {
        Document one = doc(MA);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), eq(Grouping.ONE), eq(KEY))).thenReturn(Mono.just(List.of(one)));
        post(press(MA, KEY, null, "p1.jpg")).expectStatus().isOk().expectBody().jsonPath("$.documents[0].id").isEqualTo(one.id().toString());
    }

    /** A group the client misspelt is refused, never guessed as one report: "seperate" would merge papers silently. */
    @Test
    void aGroupThatIsNeitherOneNorSeparateIsInvalid() {
        post(press(MA, KEY, "seperate", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any());
    }

    @Test
    void aPressWithoutAPatientIsChoosePatientAndNothingIsUploaded() {
        post(press(null, KEY, "one", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CHOOSE_PATIENT\"}", true);
        // a patient that is not an id is nobody chosen, as on the sheet
        MultipartBodyBuilder named = press(null, KEY, "one", "p1.jpg");
        named.part("patient", "Ma");
        post(named).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CHOOSE_PATIENT\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any());
        verify(uploads, never()).upload(any(), any(), any());
    }

    @Test
    void aPressWithoutAValidUploadKeyIsInvalid() {
        post(press(MA, null, "one", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        post(press(MA, "x", "one", "p1.jpg")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any());
        verify(uploads, never()).upload(any(), any(), any());
    }

    @Test
    void aPressWithNoFileIsNoFileAndNothingIsUploaded() {
        post(press(MA, KEY, "one")).expectStatus().isBadRequest().expectBody().json("{\"reason\":\"NO_FILE\"}", true);
        verify(uploads, never()).upload(any(), any(), any(), any(), any());
    }

    static Stream<String> namedReasons() {
        return ApiReason.BY_REASON.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namedReasons")
    void aRefusalIsItsNameAndNothingElse(String reason) {
        ApiReason name = ApiReason.BY_REASON.get(reason);
        when(uploads.upload(eq(FAMILY), eq(MA), any(), any(), eq(KEY)))
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

    private static PatientSummary summary(UUID id, String name, boolean owner) {
        return new PatientSummary(new PatientProfile(id, FAMILY, name, 60, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), owner, 0, null);
    }
}
