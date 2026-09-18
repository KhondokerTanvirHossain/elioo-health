package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIntakeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final FreeTierUseCase freeTier = mock(FreeTierUseCase.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentExtractionService extraction = mock(DocumentExtractionService.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase explainer =
            mock(com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase.class);
    private final com.elioo.baymax.nudge.application.port.in.NudgeUseCase nudges =
            mock(com.elioo.baymax.nudge.application.port.in.NudgeUseCase.class);

    private DocumentIntakeService service;

    @BeforeEach
    void setUp() {
        service = new DocumentIntakeService(records, documents, freeTier, storage, new PageRenderer(),
                extraction, properties, Clock.fixed(NOW, ZoneOffset.UTC), explainer, nudges);
        when(explainer.explain(any())).thenReturn(Mono.empty());
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());

        when(records.findPatient(PATIENT)).thenReturn(Mono.just(new PatientProfile(
                PATIENT, FAMILY, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)));
        when(freeTier.checkCanUploadDocument(FAMILY)).thenReturn(Mono.empty());
        when(documents.create(any())).thenAnswer(i -> Mono.just(
                ((Document) i.getArgument(0)).withId(UUID.randomUUID())));
        when(storage.storePage(any(), any(), any(), anyInt(), any())).thenAnswer(i -> Mono.just(
                new StoredObject(UUID.randomUUID(), FAMILY, PATIENT, i.getArgument(2), ObjectKind.PAGE,
                        i.getArgument(3), null, "page", "image/jpeg", 10, NOW)));
        when(extraction.process(any(), any())).thenReturn(Mono.empty());
    }

    private static Upload jpeg(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return new Upload(name, out.toByteArray());
    }

    @Test
    void anAcceptedUploadStoresEveryPageAndReturnsImmediately() throws Exception {
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"))))
                .assertNext(document -> {
                    assertThat(document.id()).isNotNull();
                    assertThat(document.status()).isEqualTo(Document.Status.RECEIVED);
                    assertThat(document.pageCount()).isEqualTo(2);
                    assertThat(document.familyId()).isEqualTo(FAMILY);
                })
                .verifyComplete();

        verify(storage, times(2)).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction).process(any(), any());
        // BMX-8: extraction of any document leaves chronic_flags exactly as the family set them
        verify(records, never()).updateChronicFlags(any(), any());
    }

    @Test
    void theFreeTierIsCheckedBeforeAnythingIsStored() throws Exception {
        when(freeTier.checkCanUploadDocument(FAMILY)).thenReturn(Mono.error(
                new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "3 a month")));

        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"))))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f
                        && f.reason().equals("free_tier_documents"))
                .verify();

        verify(documents, never()).create(any());
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction, never()).process(any(), any());
    }

    @Test
    void anUnknownPatientIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(records.findPatient(unknown)).thenReturn(Mono.empty());

        StepVerifier.create(service.accept(unknown, List.of(jpeg("a.jpg"))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404)
                .verify();
    }

    @Test
    void anEmptyUploadListIsRejected() {
        StepVerifier.create(service.accept(PATIENT, List.of()))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("no_file"))
                .verify();
    }

    @Test
    void tooManyPagesAreRefused() throws Exception {
        properties.getExtract().setMaxPages(2);

        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"), jpeg("c.jpg"))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("too_many_pages"))
                .verify();

        verify(documents, never()).create(any());
    }

    @Test
    void aPendingDocumentShowsStatusOnly() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, null, null, null,
                null, null, Document.Status.PROCESSING, null, null, null, 1, NOW, NOW)));

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.status()).isEqualTo("PROCESSING");
                    assertThat(view.values()).isNull();
                })
                .verifyComplete();

        verify(documents, never()).observationsOf(any());
    }

    @Test
    void aRetakeShowsItsReason() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, null, null, null,
                null, null, Document.Status.NEEDS_RETAKE, "too blurry", null, null, 1, NOW, NOW)));

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.status()).isEqualTo("NEEDS_RETAKE");
                    assertThat(view.reason()).isEqualTo("too blurry");
                })
                .verifyComplete();
    }

    @Test
    void aDoneDocumentShowsItsItemsWithCropKeys() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, "lab_report",
                java.time.LocalDate.parse("2026-03-14"), "Popular", "{}", 0.93, Document.Status.DONE,
                null, "groq/gpt-oss", null, 1, NOW, NOW)));
        when(documents.observationsOf(id)).thenReturn(Flux.just(
                Map.of("name", "HbA1c", "value", "8.2", "crop_key", "f/p/d/crop-v1.jpg")));
        when(documents.medicinesOf(id)).thenReturn(Flux.empty());
        when(documents.followUpsOf(id)).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(id)).thenReturn(Mono.just(
                Map.of("diagnosis", List.of(Map.of("text", "Type 2 Diabetes Mellitus")))));

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.status()).isEqualTo("DONE");
                    assertThat(view.documentType()).isEqualTo("lab_report");
                    assertThat(view.documentDate()).isEqualTo("2026-03-14");
                    assertThat(view.model()).isEqualTo("groq/gpt-oss");
                    assertThat(view.values()).singleElement()
                            .satisfies(v -> assertThat(v).containsEntry("crop_key", "f/p/d/crop-v1.jpg"));
                    assertThat(view.clinicalContext()).containsKey("diagnosis");
                })
                .verifyComplete();
    }

    /**
     * A document whose clinical_context is absent must still return its values: the context is an extra
     * section, not a precondition. This is a {@code Mono.zip}, which completes empty if any source does,
     * so an empty context here would silently turn a good document into a 404-shaped empty response.
     */
    @Test
    void aDocumentWithoutClinicalContextStillShowsItsValues() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, "lab_report",
                java.time.LocalDate.parse("2026-03-14"), "Popular", "{}", 0.93, Document.Status.DONE,
                null, "groq/gpt-oss", null, 1, NOW, NOW)));
        when(documents.observationsOf(id)).thenReturn(Flux.just(
                Map.of("name", "HbA1c", "value", "8.2", "crop_key", "f/p/d/crop-v1.jpg")));
        when(documents.medicinesOf(id)).thenReturn(Flux.empty());
        when(documents.followUpsOf(id)).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.values()).hasSize(1);
                    assertThat(view.clinicalContext()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void anUnknownDocumentIs404() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.view(id))
                .expectErrorMatches(e -> e instanceof BaymaxException b
                        && b.reason().equals("document_not_found"))
                .verify();
    }
}
