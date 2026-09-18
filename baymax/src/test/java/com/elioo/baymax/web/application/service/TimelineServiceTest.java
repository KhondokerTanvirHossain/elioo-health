package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.web.application.port.out.AuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scoping. The port decides visibility; this class must turn "not visible" into 404 (never 403), and
 * "visible but shared" into 403 for writes only.
 */
class TimelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final UUID FAMILY_A = UUID.randomUUID();
    private static final UUID FAMILY_B = UUID.randomUUID();
    private static final UUID PATIENT_B = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final AuditPort audit = mock(AuditPort.class);
    private TimelineService service;

    private static final PatientProfile PATIENT = new PatientProfile(PATIENT_B, FAMILY_B, "Ma", 60,
            PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    private static final Document DOCUMENT = Document.received(PATIENT_B, FAMILY_B, 1, NOW).withId(DOC_B);

    @BeforeEach
    void wire() {
        service = new TimelineService(records, documents, intake, storage, audit, mock(com.elioo.baymax.outbound.application.port.out.OutboundMessagePort.class), Clock.fixed(NOW, ZoneOffset.UTC));
        when(audit.record(any())).thenReturn(Mono.empty());
    }

    /** Acceptance: given family A's session and family B's document id, then 404. */
    @Test
    void anotherFamilysDocumentIs404NotForbidden() {
        when(documents.findVisible(FAMILY_A, DOC_B)).thenReturn(Mono.empty());
        StepVerifier.create(service.document(FAMILY_A, DOC_B))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        StepVerifier.create(service.deleteDocument(FAMILY_A, DOC_B))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verify(intake, never()).view(any());
        verify(storage, never()).deleteDocument(any(), any(), any());
    }

    @Test
    void anotherFamilysPatientTimelineIs404() {
        when(records.visiblePatient(FAMILY_A, PATIENT_B)).thenReturn(Mono.empty());
        StepVerifier.create(service.timeline(FAMILY_A, PATIENT_B, null))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verify(documents, never()).timelineOf(any(), any(), anyInt());
    }

    /** Acceptance: given a share_member session, the shared timeline is visible and delete returns 403. */
    @Test
    void aShareMemberSeesTheTimelineButMayNotDeleteOrUpload() {
        when(records.visiblePatient(FAMILY_A, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, false)));
        when(documents.timelineOf(eq(PATIENT_B), any(), anyInt())).thenReturn(Flux.just(DOCUMENT));
        when(documents.sectionCounts(DOC_B)).thenReturn(Mono.just(new int[]{2, 3, 0}));
        when(documents.findVisible(FAMILY_A, DOC_B)).thenReturn(Mono.just(DOCUMENT));

        StepVerifier.create(service.timeline(FAMILY_A, PATIENT_B, null))
                .assertNext(page -> {
                    assertThat(page.entries()).hasSize(1);
                    assertThat(page.entries().get(0).medicines()).isEqualTo(3);
                    assertThat(page.nextCursor()).isNull();
                }).verifyComplete();

        StepVerifier.create(service.deleteDocument(FAMILY_A, DOC_B))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 403).verify();
        StepVerifier.create(service.upload(FAMILY_A, PATIENT_B, List.of(new Upload("a.jpg", new byte[]{1}))))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 403).verify();
        verify(storage, never()).deleteDocument(any(), any(), any());
        verify(intake, never()).accept(any(), any());
    }

    @Test
    void theOwnerDeletesObjectsThenRows() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        when(storage.deleteDocument(FAMILY_B, PATIENT_B, DOC_B)).thenReturn(Mono.just(new DeletionReport("p/", 4, 4)));
        when(documents.deleteDocument(DOC_B)).thenReturn(Mono.just(1L));
        StepVerifier.create(service.deleteDocument(FAMILY_B, DOC_B))
                .assertNext(r -> assertThat(r.objectsDeleted()).isEqualTo(4)).verifyComplete();
        verify(documents).deleteDocument(DOC_B);
    }

    @Test
    void aTimelinePageOfTwentyOneMeansThereIsANextCursor() {
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        List<Document> docs = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            docs.add(Document.received(PATIENT_B, FAMILY_B, 1, NOW.minusSeconds(i)).withId(UUID.randomUUID()));
        }
        when(documents.timelineOf(eq(PATIENT_B), any(), eq(21))).thenReturn(Flux.fromIterable(docs));
        when(documents.sectionCounts(any())).thenReturn(Mono.just(new int[]{0, 0, 0}));
        StepVerifier.create(service.timeline(FAMILY_B, PATIENT_B, null))
                .assertNext(page -> {
                    assertThat(page.entries()).hasSize(20);
                    assertThat(page.nextCursor()).isEqualTo(NOW.minusSeconds(19).toString());
                    assertThat(page.entries().get(0).dateIsFallback()).isTrue();   // received(): no doc_date yet
                }).verifyComplete();
    }

    @Test
    void anImageKeyOutsideTheDocumentIs404() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        StepVerifier.create(service.imageUrl(FAMILY_B, DOC_B, "someone/else/page-1.jpg"))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verify(storage, never()).signedUrl(any());
    }
}
