package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.domain.TimelineCursor;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    private final DocumentUrgencyUseCase urgency = mock(DocumentUrgencyUseCase.class);
    private final TimelineReadPort timelineRead = mock(TimelineReadPort.class);
    private TimelineService service;

    private static final PatientProfile PATIENT = new PatientProfile(PATIENT_B, FAMILY_B, "Ma", 60,
            PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    private static final Document DOCUMENT = Document.received(PATIENT_B, FAMILY_B, 1, NOW).withId(DOC_B);

    @BeforeEach
    void wire() {
        service = new TimelineService(records, documents, intake, storage, audit, Clock.fixed(NOW, ZoneOffset.UTC), urgency, timelineRead);
        when(audit.record(any())).thenReturn(Mono.empty());
        // stubbed for every test, so a path that asks for urgency fails on verify(never()), not on a null Mono
        when(urgency.currentOrAssess(any())).thenReturn(Mono.empty());
        // likewise: a path that reads the timeline when it must not fails on verify(never()) or its assertion, not on a null Flux
        when(timelineRead.page(any(), any(), any(), anyInt())).thenReturn(Flux.empty());
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
        verify(urgency, never()).currentOrAssess(any());   // a document the family cannot see is never assessed for it
    }

    /** F1: the document a family may see carries its stored level, read (or, once, assessed) through the writer. */
    @Test
    void aVisibleDocumentCarriesItsStoredUrgency() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        when(intake.view(DOC_B)).thenReturn(Mono.just(DocumentView.pending(DOCUMENT)));
        when(urgency.currentOrAssess(DOC_B)).thenReturn(Mono.just(
                new StoredUrgency(DOC_B, Urgency.THIS_WEEK, List.of("diagnosis_present"), NOW)));

        StepVerifier.create(service.document(FAMILY_B, DOC_B)).assertNext(v -> assertThat(v.urgency()).isEqualTo("this_week"))
                .verifyComplete();
    }

    /** The other direction: nothing stored and nothing to assess reads as no level, never a ROUTINE it was not given. */
    @Test
    void aDocumentWithNoStoredUrgencyCarriesNone() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        when(intake.view(DOC_B)).thenReturn(Mono.just(DocumentView.pending(DOCUMENT)));
        when(urgency.currentOrAssess(DOC_B)).thenReturn(Mono.empty());

        StepVerifier.create(service.document(FAMILY_B, DOC_B)).assertNext(v -> assertThat(v.urgency()).isNull())
                .verifyComplete();
    }

    @Test
    void anotherFamilysPatientTimelineIs404() {
        when(records.visiblePatient(FAMILY_A, PATIENT_B)).thenReturn(Mono.empty());
        StepVerifier.create(service.timeline(FAMILY_A, PATIENT_B, null))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verify(timelineRead, never()).page(any(), any(), any(), anyInt());
    }

    /** A cursor that is neither absent, a pre-C2 instant, nor a v1 token is a 400, and nothing is read. */
    @Test
    void aGarbageCursorIs400() {
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        StepVerifier.create(Mono.defer(() -> service.timeline(FAMILY_B, PATIENT_B, "not-a-cursor")))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400 && "invalid_cursor".equals(b.reason()))
                .verify();
        verify(timelineRead, never()).page(any(), any(), any(), anyInt());
    }

    /** The cursor and the filter reach the read port as given; the three-argument form is the unfiltered list. */
    @Test
    void theCursorAndTheFilterReachTheReadPort() {
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        when(timelineRead.page(eq(PATIENT_B), any(), any(), eq(21))).thenReturn(Flux.empty());
        TimelineCursor after = new TimelineCursor(LocalDate.parse("2026-10-12"), NOW, DOC_B);
        StepVerifier.create(service.timeline(FAMILY_B, PATIENT_B, after.encode(), TimelineFilter.LAB))
                .assertNext(page -> assertThat(page.nextCursor()).isNull()).verifyComplete();
        verify(timelineRead).page(PATIENT_B, after, TimelineFilter.LAB, 21);
        StepVerifier.create(service.timeline(FAMILY_B, PATIENT_B, null))
                .assertNext(page -> assertThat(page.entries()).isEmpty()).verifyComplete();
        verify(timelineRead).page(PATIENT_B, TimelineCursor.FIRST, TimelineFilter.ALL, 21);
    }

    /**
     * Acceptance: given a share_member session, the shared timeline is visible and delete returns 403. The upload half
     * moved to UploadServiceTest with the upload itself (A7).
     */
    @Test
    void aShareMemberSeesTheTimelineButMayNotDelete() {
        when(records.visiblePatient(FAMILY_A, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, false)));
        when(timelineRead.page(eq(PATIENT_B), any(), any(), anyInt())).thenReturn(Flux.just(entry(DOC_B, NOW)));
        when(documents.findVisible(FAMILY_A, DOC_B)).thenReturn(Mono.just(DOCUMENT));

        StepVerifier.create(service.timeline(FAMILY_A, PATIENT_B, null))
                .assertNext(page -> {
                    assertThat(page.entries()).hasSize(1);
                    assertThat(page.entries().get(0).medicines()).isEqualTo(3);
                    assertThat(page.nextCursor()).isNull();
                }).verifyComplete();

        StepVerifier.create(service.deleteDocument(FAMILY_A, DOC_B))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 403).verify();
        verify(storage, never()).deleteDocument(any(), any(), any());
    }

    /**
     * The server half of "the owner saves, a share member only sees" (spec §3, D30): the পরিবার tab hides the save from
     * a share member, and this is what refuses the post if it is sent anyway. 403, nothing written.
     */
    @Test
    void aShareMemberMayNotChangeTheChronicFlags() {
        when(records.visiblePatient(FAMILY_A, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, false)));
        // the write would succeed if reached: the refusal must come from the owner check, not a failing mock
        when(records.updateChronicFlags(any(), any())).thenReturn(Mono.just(PATIENT));
        StepVerifier.create(service.updateChronicFlags(FAMILY_A, PATIENT_B, List.of("diabetes")))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 403 && "not_owner".equals(b.reason()))
                .verify();
        verify(records, never()).updateChronicFlags(any(), any());
    }

    /** The other direction: the owner's flags are written, normalised. */
    @Test
    void theOwnerChangesTheChronicFlags() {
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        when(records.updateChronicFlags(eq(PATIENT_B), any())).thenReturn(Mono.just(PATIENT));
        StepVerifier.create(service.updateChronicFlags(FAMILY_B, PATIENT_B, List.of("diabetes", " Asthma ")))
                .expectNext(PATIENT).verifyComplete();
        verify(records).updateChronicFlags(PATIENT_B, List.of("diabetes", "asthma"));
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
        List<TimelineEntry> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            rows.add(entry(UUID.randomUUID(), NOW.minusSeconds(i)));
        }
        when(timelineRead.page(eq(PATIENT_B), eq(TimelineCursor.FIRST), eq(TimelineFilter.ALL), eq(21))).thenReturn(Flux.fromIterable(rows));
        TimelineEntry e20 = rows.get(19);
        StepVerifier.create(service.timeline(FAMILY_B, PATIENT_B, null))
                .assertNext(page -> {
                    assertThat(page.entries()).hasSize(20).doesNotContain(rows.get(20));
                    // the whole (date, upload, id) of the last row shown, opaque: never a bare createdAt
                    assertThat(page.nextCursor()).isEqualTo(new TimelineCursor(e20.date(), e20.createdAt(), e20.documentId()).encode());
                }).verifyComplete();
    }

    /** A read-model row as TimelineReadPort returns it: three medicines, nothing assessed. */
    private static TimelineEntry entry(UUID id, Instant createdAt) {
        return new TimelineEntry(id, LocalDate.parse("2026-09-17"), false, "prescription", null, "DONE", 0, 3, 0, 0,
                "k/" + id + "/page-1.jpg", createdAt);
    }

    @Test
    void anImageKeyOutsideTheDocumentIs404() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        StepVerifier.create(service.imageUrl(FAMILY_B, DOC_B, "someone/else/page-1.jpg"))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verify(storage, never()).signedUrl(any());
    }
}
