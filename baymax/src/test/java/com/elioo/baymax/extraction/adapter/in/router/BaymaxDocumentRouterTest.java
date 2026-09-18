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
    private final WebTestClient client;

    BaymaxDocumentRouterTest() {
        BaymaxProperties props = new BaymaxProperties();
        props.getAdmin().setToken(TOKEN);
        client = WebTestClient.bindToRouterFunction(new BaymaxDocumentRouter()
                        .baymaxDocumentRoutes(new DocumentHandler(intake, timeline), new SessionOrAdminAuthFilter(new AdminAuthFilter(props), new SessionAuthFilter(auth, props)),
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

    @Test
    void deleteNeedsASessionNotTheAdminToken() {
        client.delete().uri(PATH + "/" + UUID.randomUUID()).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange().expectStatus().isUnauthorized();
        verify(timeline, never()).deleteDocument(any(), any());
    }
}
