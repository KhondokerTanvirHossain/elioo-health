package com.elioo.baymax.extraction.adapter.in.router;

import com.elioo.baymax.aicall.adapter.in.router.AdminAuthFilter;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.router.SessionOrAdminAuthFilter;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.adapter.in.handler.DocumentHandler;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The HTTP surface of intake: the free-tier refusal, and the unverified counts in the status response. */
class BaymaxDocumentRouterTest {

    private static final String TOKEN = "s3cret";
    private static final String PATH = "/api/v1/baymax/documents";

    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final com.elioo.baymax.web.application.port.in.TimelineUseCase timeline = mock(com.elioo.baymax.web.application.port.in.TimelineUseCase.class);
    private final com.elioo.baymax.web.application.port.in.WebAuthUseCase auth = mock(com.elioo.baymax.web.application.port.in.WebAuthUseCase.class);
    private final com.elioo.baymax.web.application.port.in.UploadUseCase uploads = mock(com.elioo.baymax.web.application.port.in.UploadUseCase.class);
    private final WebTestClient client;

    BaymaxDocumentRouterTest() {
        BaymaxProperties props = new BaymaxProperties();
        props.getAdmin().setToken(TOKEN);
        client = WebTestClient.bindToRouterFunction(new BaymaxDocumentRouter()
                        .baymaxDocumentRoutes(new DocumentHandler(intake, timeline, mock(com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase.class), uploads), new SessionOrAdminAuthFilter(new AdminAuthFilter(props), new SessionAuthFilter(auth, props)),
                                new ErrorResponseFilter()))
                .build();
    }

    private static BodyInserters.MultipartInserter upload(UUID patientId) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient_id", patientId.toString());
        body.part("file", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2})
                .filename("report.jpg");
        return BodyInserters.fromMultipartData(body.build());
    }

    @Test
    void aFreeFamilyAtItsMonthlyLimitGets402AndNothingIsProcessed() {
        UUID patient = UUID.randomUUID();
        when(intake.accept(any(), any())).thenReturn(Mono.error(new FreeTierExceededException(
                FreeTierExceededException.DOCUMENTS, "free plan allows 3 document(s) per calendar month")));

        client.post().uri(PATH).header(AdminAuthFilter.HEADER, TOKEN)
                .body(upload(patient))
                .exchange()
                .expectStatus().isEqualTo(402)
                .expectBody()
                .jsonPath("$.reason").isEqualTo("free_tier_documents")
                .jsonPath("$.status").isEqualTo(402);
    }

    @Test
    void anAcceptedUploadIs202WithTheDocumentId() {
        UUID patient = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        when(intake.accept(any(), any())).thenReturn(Mono.just(
                Document.received(patient, UUID.randomUUID(), 1, Instant.now()).withId(document)));

        client.post().uri(PATH).header(AdminAuthFilter.HEADER, TOKEN)
                .body(upload(patient))
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody()
                .jsonPath("$.document_id").isEqualTo(document.toString())
                .jsonPath("$.status").isEqualTo("RECEIVED");
    }

    @Test
    void withoutTheAdminTokenNothingIsAccepted() {
        client.post().uri(PATH).body(upload(UUID.randomUUID()))
                .exchange().expectStatus().isUnauthorized();
        verify(intake, never()).accept(any(), any());
    }

    @Test
    void droppedItemsAreVisibleInTheStatusResponse() {
        UUID document = UUID.randomUUID();
        when(intake.view(document)).thenReturn(Mono.just(new DocumentView(
                document.toString(), "DONE", null, "lab_report", "2026-03-14", "Popular", 0.93,
                "groq/gpt-oss", 1,
                List.of(Map.of("name", "HbA1c", "value", "8.2", "crop_key", "k")),
                List.of(), List.of(),
                Map.of("diagnosis", List.of(Map.of("text", "Type 2 Diabetes Mellitus"))),
                Map.of("values", 2, "medicines", 0, "follow_up", 1, "total", 3))));

        client.get().uri(PATH + "/" + document).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.unverified.values").isEqualTo(2)
                .jsonPath("$.unverified.follow_up").isEqualTo(1)
                .jsonPath("$.unverified.total").isEqualTo(3)
                // persisted since V7 but never surfaced, which read as a clinical_context score of 0/40 —
                // a missing field in this response, not a model that could not find the diagnosis
                .jsonPath("$.clinical_context.diagnosis[0].text").isEqualTo("Type 2 Diabetes Mellitus");
    }

    private static com.elioo.baymax.extraction.adapter.out.persistence.ObservationEntity observation(UUID id, String verdict) {
        com.elioo.baymax.extraction.adapter.out.persistence.ObservationEntity o = new com.elioo.baymax.extraction.adapter.out.persistence.ObservationEntity();
        o.setId(id);
        o.setName("Hb");
        o.setValue("5.0");
        o.setUnit("g/dL");
        o.setRefLow("12.0");
        o.setRefHigh("16.0");
        o.setCropKey("k");
        if (verdict != null) {
            o.setVerdict(verdict);
            o.setVerdictLevel("now");
            o.setVerdictSide("low");
            o.setVerdictReason("value_critical:hb|stopgap");
        }
        return o;
    }

    /**
     * API change (F2, B1): each value in the document JSON carries its observation id and its stored verdict, under
     * the names the real producer (ObservationEntity.toView) writes. B2 and B6 read these field names.
     */
    @Test
    void eachValueCarriesItsObservationIdAndStoredVerdict() {
        UUID document = UUID.randomUUID(), obs = UUID.randomUUID();
        when(intake.view(document)).thenReturn(Mono.just(new DocumentView(document.toString(), "DONE", null, "lab_report",
                null, null, 0.95, "m", 1, List.of(observation(obs, "critical").toView()), List.of(), List.of(), null, null)));

        client.get().uri(PATH + "/" + document).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.values[0].id").isEqualTo(obs.toString())
                .jsonPath("$.values[0].verdict").isEqualTo("critical")
                .jsonPath("$.values[0].verdict_level").isEqualTo("now")
                .jsonPath("$.values[0].verdict_side").isEqualTo("low")
                .jsonPath("$.values[0].verdict_reason").isEqualTo("value_critical:hb|stopgap");
    }

    /** The other direction: a value not yet assessed carries its id and no verdict fields at all (the page reads UNKNOWN). */
    @Test
    void aValueNotYetAssessedCarriesItsIdAndNoVerdict() {
        UUID document = UUID.randomUUID(), obs = UUID.randomUUID();
        when(intake.view(document)).thenReturn(Mono.just(new DocumentView(document.toString(), "DONE", null, "lab_report",
                null, null, 0.95, "m", 1, List.of(observation(obs, null).toView()), List.of(), List.of(), null, null)));

        client.get().uri(PATH + "/" + document).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.values[0].id").isEqualTo(obs.toString())
                .jsonPath("$.values[0].verdict").doesNotExist()
                .jsonPath("$.values[0].verdict_level").doesNotExist()
                .jsonPath("$.values[0].verdict_side").doesNotExist()
                .jsonPath("$.values[0].verdict_reason").doesNotExist();
    }

    @Test
    void aCleanDocumentSaysNothingAboutUnverifiedItems() {
        UUID document = UUID.randomUUID();
        when(intake.view(document)).thenReturn(Mono.just(new DocumentView(
                document.toString(), "DONE", null, "lab_report", null, null, 0.95, "groq/gpt-oss", 1,
                List.of(), List.of(), List.of(), null, null)));

        client.get().uri(PATH + "/" + document).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.unverified").doesNotExist()
                .jsonPath("$.clinical_context").doesNotExist();
    }

    @Test
    void anUnknownDocumentIs404() {
        UUID document = UUID.randomUUID();
        when(intake.view(document)).thenReturn(Mono.error(
                BaymaxException.notFound("document_not_found", "no document with id " + document)));

        client.get().uri(PATH + "/" + document).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.reason").isEqualTo("document_not_found");
    }

    /** BMX-5: a family session takes the scoped path; another family's id is a 404 there, never a 403. */
    @Test
    void aSessionCookieUsesTheScopedLookupAndAnotherFamilysDocumentIs404() {
        UUID family = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.now();
        org.mockito.Mockito.when(auth.authenticate("tok")).thenReturn(Mono.just(
                new com.elioo.baymax.web.domain.WebSession(UUID.randomUUID(), family, now, now, now.plusSeconds(60))));
        org.mockito.Mockito.when(timeline.document(family, document)).thenReturn(Mono.error(
                BaymaxException.notFound("document_not_found", "no document with id " + document)));

        client.get().uri(PATH + "/" + document).cookie("baymax_session", "tok")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.reason").isEqualTo("document_not_found");
        verify(intake, never()).view(any());
    }

    private UUID signedIn() {
        UUID family = UUID.randomUUID();
        Instant now = Instant.now();
        when(auth.authenticate("tok")).thenReturn(Mono.just(
                new com.elioo.baymax.web.domain.WebSession(UUID.randomUUID(), family, now, now, now.plusSeconds(60))));
        return family;
    }

    /**
     * A7: a family's upload through the JSON API goes through the one upload rule (UploadUseCase: owner only, audited),
     * never straight into intake, which checks no access at all. Intake would accept it here, so a handler that skipped
     * the rule would answer 202 and this would see it.
     */
    @Test
    void aSessionUploadGoesThroughTheUploadRuleWithTheSessionsFamilyNeverStraightToIntake() {
        UUID family = signedIn();
        UUID patient = UUID.randomUUID();
        Document received = Document.received(patient, UUID.randomUUID(), 1, Instant.now()).withId(UUID.randomUUID());
        when(intake.accept(any(), any())).thenReturn(Mono.just(received));
        when(uploads.upload(eq(family), eq(patient), any())).thenReturn(Mono.just(received));

        client.post().uri(PATH).cookie("baymax_session", "tok").body(upload(patient))
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody().jsonPath("$.document_id").isEqualTo(received.id().toString());
        verify(uploads).upload(eq(family), eq(patient), any());
        verify(intake, never()).accept(any(), any());
    }

    /** The other direction: a shared patient's file refuses the upload, and the family's browser hears why. */
    @Test
    void aSessionUploadForASharedPatientIs403AndIntakeNeverSeesIt() {
        UUID family = signedIn();
        UUID patient = UUID.randomUUID();
        when(intake.accept(any(), any())).thenReturn(Mono.just(
                Document.received(patient, UUID.randomUUID(), 1, Instant.now()).withId(UUID.randomUUID())));
        when(uploads.upload(eq(family), eq(patient), any())).thenReturn(Mono.error(BaymaxException.forbidden(
                "shared_patient_read_only", "a shared patient's records can be seen but not added to")));

        client.post().uri(PATH).cookie("baymax_session", "tok").body(upload(patient))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.reason").isEqualTo("shared_patient_read_only");
        verify(intake, never()).accept(any(), any());
    }

    @Test
    void deleteNeedsASessionNotTheAdminToken() {
        client.delete().uri(PATH + "/" + UUID.randomUUID()).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange().expectStatus().isUnauthorized();
        verify(timeline, never()).deleteDocument(any(), any());
    }
}
