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
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.domain.AuditEvent;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
        verify(storage, never()).deleteDocumentObjects(any(), any(), any(), anyLong());
        verify(documents, never()).deleteDocument(any());
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

    /**
     * The JSON document read has the result page's race (ResultServiceTest#theValuesAreReadOnlyAfterTheStoredLevelSoTheyCarryItsVerdicts):
     * the view was read first and the level after, and the level's read may assess the paper there and then, committing
     * its verdicts with it. A paper read the moment it turned DONE then answered urgency "now" over values with no
     * verdict. The view is read after the level, so it carries what the level was assessed with. Modelled as the
     * database answers: a view is what has committed when it is read.
     */
    @Test
    void theViewIsReadAfterTheLevelSoItCarriesItsVerdicts() {
        Document done = DOCUMENT.withStatus(Document.Status.DONE, null, NOW);
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(done));
        AtomicBoolean committed = new AtomicBoolean();
        when(urgency.currentOrAssess(DOC_B)).thenReturn(Mono.fromCallable(() -> {
            committed.set(true);   // the assessment's transaction: the level and the verdicts together
            return new StoredUrgency(DOC_B, Urgency.NOW, List.of("value_critical:potassium|stopgap"), NOW);
        }));
        when(intake.view(DOC_B)).thenReturn(Mono.fromCallable(() -> potassiumView(committed.get() ? "critical" : null)));

        StepVerifier.create(service.document(FAMILY_B, DOC_B)).assertNext(v -> {
            assertThat(v.urgency()).isEqualTo("now");
            assertThat(v.values()).as("the verdict the stored level was assessed with")
                    .extracting(row -> row.get("verdict")).containsExactly("critical");
        }).verifyComplete();
    }

    /** The other direction: a level that cannot be read never costs the read its view, nor the view its audit row. */
    @Test
    void aDocumentWithNoLevelStillGetsItsViewAndItsAuditRow() {
        Document done = DOCUMENT.withStatus(Document.Status.DONE, null, NOW);
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(done));
        when(urgency.currentOrAssess(DOC_B)).thenReturn(Mono.empty());
        when(intake.view(DOC_B)).thenReturn(Mono.just(potassiumView(null)));

        StepVerifier.create(service.document(FAMILY_B, DOC_B)).assertNext(v -> {
            assertThat(v.urgency()).isNull();
            assertThat(v.values()).extracting(row -> row.get("name")).containsExactly("Potassium");
        }).verifyComplete();
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_VIEW && DOC_B.equals(e.documentId())));
    }

    /**
     * Reading the view after the level must not move the DOCUMENT_VIEW row behind an assessment that may fail: the row
     * is written before the level is read, as it was before the reorder, and the failure still reaches the caller.
     */
    @Test
    void aFailedAssessmentStillLeavesTheReadsAuditRow() {
        Document done = DOCUMENT.withStatus(Document.Status.DONE, null, NOW);
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(done));
        when(urgency.currentOrAssess(DOC_B)).thenReturn(Mono.error(new IllegalStateException("assessment failed")));
        when(intake.view(DOC_B)).thenReturn(Mono.just(potassiumView(null)));

        StepVerifier.create(service.document(FAMILY_B, DOC_B)).expectErrorMessage("assessment failed").verify();
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_VIEW && DOC_B.equals(e.documentId())));
    }

    /** A DONE view with one Potassium row, its verdict as the database holds it at the moment of the read. */
    private static DocumentView potassiumView(String verdict) {
        Map<String, Object> row = new HashMap<>(Map.of("name", "Potassium", "value", "11.0", "unit", "mmol/L"));
        row.put("verdict", verdict);
        return new DocumentView(DOC_B.toString(), "DONE", null, "lab_report", "2026-10-12", null, 0.95, null, 1,
                List.of(row), List.of(), List.of(), Map.of(), null);
    }

    @Test
    void anotherFamilysPatientTimelineIs404() {
        when(records.visiblePatient(FAMILY_A, PATIENT_B)).thenReturn(Mono.empty());
        StepVerifier.create(service.timeline(FAMILY_A, PATIENT_B, null, TimelineFilter.ALL))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verify(timelineRead, never()).page(any(), any(), any(), anyInt());
    }

    /** A cursor that is neither absent, a pre-C2 instant, nor a v1 token is a 400, and nothing is read. */
    @Test
    void aGarbageCursorIs400() {
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        StepVerifier.create(Mono.defer(() -> service.timeline(FAMILY_B, PATIENT_B, "not-a-cursor", TimelineFilter.ALL)))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400 && "invalid_cursor".equals(b.reason()))
                .verify();
        verify(timelineRead, never()).page(any(), any(), any(), anyInt());
    }

    /** The cursor and the filter reach the read port as given; no cursor is the first page. */
    @Test
    void theCursorAndTheFilterReachTheReadPort() {
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        when(timelineRead.page(eq(PATIENT_B), any(), any(), eq(21))).thenReturn(Flux.empty());
        TimelineCursor after = new TimelineCursor(LocalDate.parse("2026-10-12"), NOW, DOC_B);
        StepVerifier.create(service.timeline(FAMILY_B, PATIENT_B, after.encode(), TimelineFilter.LAB))
                .assertNext(page -> assertThat(page.nextCursor()).isNull()).verifyComplete();
        verify(timelineRead).page(PATIENT_B, after, TimelineFilter.LAB, 21);
        StepVerifier.create(service.timeline(FAMILY_B, PATIENT_B, null, TimelineFilter.ALL))
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
        TimelineEntry row = entry(DOC_B, NOW);
        when(timelineRead.page(eq(PATIENT_B), any(), any(), anyInt())).thenReturn(Flux.just(row));
        when(documents.findVisible(FAMILY_A, DOC_B)).thenReturn(Mono.just(DOCUMENT));

        // the service passes the read port's row through untouched; its counts are pinned on Postgres (TimelineReadModelTest)
        StepVerifier.create(service.timeline(FAMILY_A, PATIENT_B, null, TimelineFilter.ALL))
                .assertNext(page -> {
                    assertThat(page.entries()).containsExactly(row);
                    assertThat(page.nextCursor()).isNull();
                }).verifyComplete();

        StepVerifier.create(service.deleteDocument(FAMILY_A, DOC_B))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 403).verify();
        verify(storage, never()).deleteDocumentObjects(any(), any(), any(), anyLong());
        verify(documents, never()).deleteDocument(any());
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

    /**
     * Was {@code theOwnerDeletesObjectsThenRows}: its name stated the order that left a paper claiming images already
     * gone when its rows could not be deleted (plan B4c). Rows first, then the bucket, as deleteFamily does; the ledger
     * count is read before the rows go, and the report carries every row deleted.
     */
    @Test
    void theOwnerDeletesRowsThenObjects() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        when(storage.objectsOf(DOC_B)).thenReturn(Flux.just(mock(StoredObject.class), mock(StoredObject.class),
                mock(StoredObject.class), mock(StoredObject.class)));
        when(storage.deleteDocumentObjects(FAMILY_B, PATIENT_B, DOC_B, 4L)).thenReturn(Mono.just(new DeletionReport("p/", 4, 4)));
        when(documents.deleteDocument(DOC_B)).thenReturn(Mono.just(9L));
        StepVerifier.create(service.deleteDocument(FAMILY_B, DOC_B))
                .assertNext(r -> {
                    assertThat(r.objectsDeleted()).isEqualTo(4);
                    assertThat(r.ledgerRowsDeleted()).as("the ledger rows, counted before they went with the rows").isEqualTo(4);
                    assertThat(r.rowsDeleted()).isEqualTo(9);
                }).verifyComplete();
        // the bucket is told how many ledger rows went with the rows, so its drift check compares like with like
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(documents, storage, audit);
        order.verify(documents).deleteDocument(DOC_B);
        order.verify(storage).deleteDocumentObjects(FAMILY_B, PATIENT_B, DOC_B, 4L);
        order.verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_DELETE));
        verify(storage, never()).deleteDocument(any(), any(), any());
    }

    /**
     * An audit write that fails after the rows have committed (a transient database error) still fails the request,
     * but it can no longer skip the bucket: the objects are removed before the audit row is written, so they are never
     * orphaned without a line.
     */
    @Test
    void anAuditFailureAfterTheRowsHaveGoneStillEmptiesTheBucket() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        when(records.visiblePatient(FAMILY_B, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(PATIENT, true)));
        when(storage.objectsOf(DOC_B)).thenReturn(Flux.just(mock(StoredObject.class)));
        when(storage.deleteDocumentObjects(FAMILY_B, PATIENT_B, DOC_B, 1L)).thenReturn(Mono.just(new DeletionReport("p/", 1, 1)));
        when(documents.deleteDocument(DOC_B)).thenReturn(Mono.just(3L));
        when(audit.record(any())).thenReturn(Mono.error(new IllegalStateException("connection reset")));

        StepVerifier.create(service.deleteDocument(FAMILY_B, DOC_B)).verifyError(IllegalStateException.class);

        verify(storage).deleteDocumentObjects(FAMILY_B, PATIENT_B, DOC_B, 1L);
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
        StepVerifier.create(service.timeline(FAMILY_B, PATIENT_B, null, TimelineFilter.ALL))
                .assertNext(page -> {
                    assertThat(page.entries()).hasSize(20).doesNotContain(rows.get(20));
                    // the whole (date, upload, id) of the last row shown, opaque: never a bare createdAt
                    assertThat(page.nextCursor()).isEqualTo(new TimelineCursor(e20.date(), e20.createdAt(), e20.documentId()).encode());
                }).verifyComplete();
    }

    /**
     * A read-model row as TimelineReadPort returns it. The C2 fields are given on purpose (a stored THIS_WEEK, nothing
     * outside, a released review, no retake code), so no fixture reads as "unassessed" by a constructor default.
     */
    private static TimelineEntry entry(UUID id, Instant createdAt) {
        return new TimelineEntry(id, LocalDate.parse("2026-09-17"), false, "prescription", null, "DONE", 0, 3, 0, 0,
                "k/" + id + "/page-1.jpg", createdAt, Urgency.THIS_WEEK, 0, "released", null);
    }

    @Test
    void anImageKeyOutsideTheDocumentIs404() {
        when(documents.findVisible(FAMILY_B, DOC_B)).thenReturn(Mono.just(DOCUMENT));
        StepVerifier.create(service.imageUrl(FAMILY_B, DOC_B, "someone/else/page-1.jpg"))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verify(storage, never()).signedUrl(any());
    }
}
