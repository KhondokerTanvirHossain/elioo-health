package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentReadPort;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The document page's read side. A9: the status and the reading view. A12: the result. */
class ResultServiceTest {

    static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000201");
    static final UUID PATIENT = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000202");
    static final UUID DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000203");
    static final Instant SENT = NOW.minusSeconds(30);
    static final PatientProfile MA = new PatientProfile(PATIENT, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);

    final HealthRecordPort records = mock(HealthRecordPort.class);
    final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    final AuditPort audit = mock(AuditPort.class);
    final OutboundMessagePort messages = mock(OutboundMessagePort.class);
    final DocumentUrgencyUseCase urgencies = mock(DocumentUrgencyUseCase.class);
    final DocumentReadPort reads = mock(DocumentReadPort.class);
    ResultService service;

    @BeforeEach
    void wire() {
        service = new ResultService(records, documents, intake, audit, Clock.fixed(NOW, ZoneOffset.UTC), messages, urgencies,
                new BaymaxProperties(), reads);
        // sent 30 s ago and moved to PROCESSING 10 s ago: its arrival (createdAt) and its last change (updatedAt) differ
        Document processing = Document.received(PATIENT, FAMILY, 2, SENT).withId(DOC).withStatus(Document.Status.PROCESSING, null, NOW.minusSeconds(10));
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(processing));
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(MA, true)));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW)));
        when(audit.record(any())).thenReturn(Mono.empty());
        when(intake.view(DOC)).thenReturn(Mono.just(DocumentView.pending(processing)));
        // D2: the result reads the view and its row in one read; unless a test says otherwise, that row is the one the
        // visibility read saw, as read back (with a filed day), and the view is whatever the test stubbed for view()
        when(intake.read(DOC)).thenAnswer(inv -> Mono.zip(documents.findVisible(FAMILY, DOC), intake.view(DOC))
                .map(t -> new DocumentView.Read(readBack(t.getT1()), t.getT2())));
        // no message and nothing stored until a test says so: an unstubbed read is an empty row, never a null Mono
        when(messages.latestDeliverable(any())).thenReturn(Mono.empty());
        when(messages.latestOfKind(any(), any())).thenReturn(Mono.empty());
        when(urgencies.currentOrAssess(any())).thenReturn(Mono.empty());
        when(reads.retakeOrdinal(any())).thenReturn(Mono.just(0));
    }

    /** The poll reads the status and nothing else: no audit row every 5 s, no view, no patient. */
    @Test
    void theStatusIsTheStoredStatusAloneAndRecordsNothing() {
        StepVerifier.create(service.status(FAMILY, DOC)).expectNext(Document.Status.PROCESSING).verifyComplete();
        verify(audit, never()).record(any());
        verify(intake, never()).view(any());
        verify(records, never()).visiblePatient(any(), any());
        verify(records, never()).findFamily(any());
    }

    @Test
    void theReadingViewNamesThePatientAndKnowsWhenItArrivedAndWhereTheReaderIs() {
        StepVerifier.create(service.reading(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.patient().name()).isEqualTo("Ma");
            assertThat(r.owner()).isTrue();
            assertThat(r.document().status()).isEqualTo("PROCESSING");
            // when it arrived, not when its status last changed: the page's time and the five-minute line count from here
            assertThat(r.receivedAt()).isEqualTo(SENT);
            assertThat(r.now()).isEqualTo(NOW);
            assertThat(r.viewer().bangladesh()).isTrue();
        }).verifyComplete();
        // the reading page is the document page: it records the view, as the document page always has
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_VIEW && DOC.equals(e.documentId())));
    }

    /** The other direction of owner(): a patient shared with this family is read as not theirs, from PatientAccess. */
    @Test
    void aSharedPatientsReadingViewIsNotTheOwners() {
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(MA, false)));
        StepVerifier.create(service.reading(FAMILY, DOC)).assertNext(r -> assertThat(r.owner()).isFalse()).verifyComplete();
    }

    /** A reader abroad: the viewer comes from the family's own number. */
    @Test
    void aFamilyAbroadReadsAsAbroad() {
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(new FamilyAccount(FAMILY, "+447700900123", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW)));
        StepVerifier.create(service.reading(FAMILY, DOC)).assertNext(r -> assertThat(r.viewer().bangladesh()).isFalse()).verifyComplete();
    }

    @Test
    void aDocumentTheFamilyCannotSeeIsNotFoundForEitherRead() {
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.empty());
        StepVerifier.create(service.status(FAMILY, DOC))
                .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        StepVerifier.create(service.reading(FAMILY, DOC))
                .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        verify(audit, never()).record(any());
    }

    /** F3 as a constructor rule: wording exists only for RELEASED, so no renderer can show a parked or rejected body. */
    @ParameterizedTest
    @EnumSource(value = GateState.class, names = {"NONE", "PENDING", "REJECTED", "FAILED_SAFETY"})
    void modelWordingCannotTravelWithAnyGateStateButReleased(GateState gate) {
        assertThatThrownBy(() -> new ResultView(DocumentView.pending(Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC)
                .withStatus(Document.Status.DONE, null, NOW)), MA, true, new ViewerLocale(true), Urgency.NOW, List.of(), gate,
                "body", 0, 2, NOW, NOW, new com.elioo.baymax.extraction.domain.FiledDay(java.time.LocalDate.parse("2026-10-20"), true, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F3: model wording only when released");
    }

    /** The other direction: released wording travels, and a document still being read carries no urgency. */
    @Test
    void releasedWordingTravelsAndUrgencyWaitsForDone() {
        DocumentView done = DocumentView.pending(Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC).withStatus(Document.Status.DONE, null, NOW));
        assertThat(new ResultView(done, MA, true, new ViewerLocale(true), Urgency.NOW, null, GateState.RELEASED, "body", 0, 2, NOW, NOW,
                new com.elioo.baymax.extraction.domain.FiledDay(java.time.LocalDate.parse("2026-10-20"), true, false))
                .wording()).isEqualTo("body");
        DocumentView reading = DocumentView.pending(Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC));
        assertThatThrownBy(() -> new ResultView(reading, MA, true, new ViewerLocale(true), Urgency.NOW, List.of(), GateState.NONE,
                null, 0, 2, NOW, NOW, new com.elioo.baymax.extraction.domain.FiledDay(java.time.LocalDate.parse("2026-10-20"), true, false))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from DONE onwards");
    }

    /** Upload day of every fixture paper here; a stand-in for the generated column a mocked port never computes. */
    private static final java.time.LocalDate UPLOAD_DAY = java.time.LocalDate.parse("2026-10-20");

    /** {@code d} as its row reads back: a stand-in filed day (the upload day unless DONE with a printed date). */
    private static Document readBack(Document d) {
        com.elioo.baymax.extraction.domain.FiledDay filed = d.status() == Document.Status.DONE && d.docDate() != null
                ? new com.elioo.baymax.extraction.domain.FiledDay(d.docDate(), false, false)
                : new com.elioo.baymax.extraction.domain.FiledDay(UPLOAD_DAY, true, false);
        return new Document(d.id(), d.patientId(), d.familyId(), d.documentType(), d.docDate(), d.facility(), d.extractionJson(),
                d.confidenceOverall(), d.status(), d.statusReason(), d.modelFinal(), d.costUsd(), d.pageCount(), d.createdAt(),
                d.updatedAt(), d.unverified(), d.clinicalContextJson(), d.statusReasonCode(), d.docDateUncertain(), filed);
    }

    /**
     * D2 (review): the filed day and the view come from ONE read of the row. The visibility read sees the paper still
     * PROCESSING; by the time the view is read it has finished, DONE with a printed 12 October. The page must be dated as
     * the DONE row it shows (printed date, not the upload day), never as the PROCESSING row it no longer is.
     */
    @Test
    void theFiledDayComesFromTheSameReadAsTheView() {
        Document finished = new Document(DOC, PATIENT, FAMILY, "lab_report", java.time.LocalDate.parse("2026-10-12"), null, "{}", 0.9,
                Document.Status.DONE, null, "m", null, 2, SENT, NOW, null, null, null, false,
                new com.elioo.baymax.extraction.domain.FiledDay(java.time.LocalDate.parse("2026-10-12"), false, false));
        DocumentView doneView = new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", null, 0.9, "m", 2,
                List.of(), List.of(), List.of(), null, null);
        org.mockito.Mockito.doReturn(Mono.just(new DocumentView.Read(finished, doneView))).when(intake).read(DOC);
        // the visibility read: the same paper a moment earlier, PROCESSING, read back with its own (upload-day) filed day
        Document earlier = readBack(Document.received(PATIENT, FAMILY, 2, SENT).withId(DOC).withStatus(Document.Status.PROCESSING, null, NOW));
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(earlier));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.document().status()).isEqualTo("DONE");
            assertThat(r.date().date()).as("the filed day of the DONE row the view was built from").isEqualTo(java.time.LocalDate.parse("2026-10-12"));
            assertThat(r.date().uploadDay()).as("the DONE row's printed date, not the PROCESSING row's upload day").isFalse();
        }).verifyComplete();
    }

    /** A row read back without its filed day is a mapping defect: the result fails loudly, never dates the paper. */
    @Test
    void aRowReadWithoutItsFiledDayFailsLoudly() {
        Document processing = Document.received(PATIENT, FAMILY, 2, SENT).withId(DOC).withStatus(Document.Status.PROCESSING, null, NOW);
        org.mockito.Mockito.doReturn(Mono.just(new DocumentView.Read(processing, DocumentView.pending(processing)))).when(intake).read(DOC);
        StepVerifier.create(service.result(FAMILY, DOC))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("was read without its filed day"))
                .verify();
    }

    private void done() {
        Document done = Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC).withStatus(Document.Status.DONE, null, NOW);
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(done));
        when(intake.view(DOC)).thenReturn(Mono.just(DocumentView.pending(done)));
        when(urgencies.currentOrAssess(DOC)).thenReturn(Mono.just(new StoredUrgency(DOC, Urgency.NOW, List.of("value_critical:potassium|stopgap"), NOW)));
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.empty());
        when(messages.latestDeliverable(DOC)).thenReturn(Mono.empty());
    }

    /**
     * The newest EXPLANATION as the adapter answers both reads: latestOfKind returns it in any state; latestDeliverable
     * only when it is deliverable (RELEASED or APPROVED with a body), as PostgresOutboundMessageAdapter does.
     */
    private void stored(OutboundMessage m) {
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(m));
        when(messages.latestDeliverable(DOC)).thenReturn(m.isDeliverable() ? Mono.just(m) : Mono.empty());
    }

    /**
     * F3 table, one row per stored gate status: the urgency is the stored NOW in every one; the stored body travels,
     * itself, only when deliverable. A RELEASED or APPROVED row with no body (nothing in the schema forbids it) is a
     * safety failure that closed: withheld, never an empty "Medioo-র কথা".
     */
    @ParameterizedTest(name = "{0} with body [{1}] shows as {2}")
    @CsvSource({"RELEASED,রিপোর্টটি পেয়েছি।,RELEASED", "APPROVED,রিপোর্টটি পেয়েছি।,RELEASED",
            "PENDING,রিপোর্টটি পেয়েছি।,PENDING", "REJECTED,রিপোর্টটি পেয়েছি।,REJECTED", "FAILED_SAFETY,,FAILED_SAFETY",
            "RELEASED,,FAILED_SAFETY", "APPROVED,,FAILED_SAFETY"})
    void theExplanationsGateStateDecidesTheWordingAndNeverTheUrgency(OutboundMessage.GateStatus stored, String body, GateState shown) {
        done();
        stored(message(stored, body, Urgency.NOW));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.urgency()).isEqualTo(Urgency.NOW);
            assertThat(r.urgencyReasons()).containsExactly("value_critical:potassium|stopgap");
            assertThat(r.gate()).isEqualTo(shown);
            // the stored body itself, not merely something: only RELEASED carries it, every other state carries nothing
            if (shown == GateState.RELEASED) {
                assertThat(r.wording()).isEqualTo(body);
            } else {
                assertThat(r.wording()).isNull();
            }
        }).verifyComplete();
    }

    /** The PENDING line's hours are the configured ones (spec §4.10 "[N]"), not a constant that happens to match the default. */
    @Test
    void theReviewHoursAreTheConfiguredOnes() {
        BaymaxProperties five = new BaymaxProperties();
        five.getOutbound().setReviewTypicalHours(5);
        service = new ResultService(records, documents, intake, audit, Clock.fixed(NOW, ZoneOffset.UTC), messages, urgencies, five, reads);
        done();
        stored(message(OutboundMessage.GateStatus.PENDING, "x", Urgency.NOW));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> assertThat(r.reviewHours()).isEqualTo(5)).verifyComplete();
    }

    /** A DONE document whose explanation was never stored: the urgency still leads, there is simply no wording line. */
    @Test
    void aDoneDocumentWithNoExplanationStoredShowsTheUrgencyAndNoWording() {
        done();
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.urgency()).isEqualTo(Urgency.NOW);
            assertThat(r.gate()).isEqualTo(GateState.NONE);
            assertThat(r.wording()).isNull();
            assertThat(r.reviewHours()).isEqualTo(2);
        }).verifyComplete();
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_VIEW && DOC.equals(e.documentId())));
    }

    /** F1: the page reads the document's stored level. A message row's urgency is history, not the answer. */
    @Test
    void theUrgencyComesFromTheDocumentNeverFromAMessage() {
        done();
        stored(message(OutboundMessage.GateStatus.RELEASED, "x", Urgency.ROUTINE));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> assertThat(r.urgency()).isEqualTo(Urgency.NOW)).verifyComplete();
        verify(urgencies).currentOrAssess(DOC);
    }

    /** Before DONE there is no urgency and no message is read: "no result text before DONE" (§4.9). */
    @Test
    void aDocumentStillBeingReadCarriesNoUrgencyAndReadsNoMessage() {
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> {   // the wire's PROCESSING document
            assertThat(r.urgency()).isNull();
            assertThat(r.gate()).isEqualTo(GateState.NONE);
        }).verifyComplete();
        verify(messages, never()).latestOfKind(any(), any());
        verify(messages, never()).latestDeliverable(any());
        verify(urgencies, never()).currentOrAssess(any());
    }

    /** A document the family cannot see is never assessed on its behalf, and nothing is read or recorded. */
    @Test
    void aDocumentTheFamilyCannotSeeIsNotFoundForTheResultAndIsNeverAssessed() {
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.empty());
        StepVerifier.create(service.result(FAMILY, DOC))
                .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        verify(urgencies, never()).currentOrAssess(any());
        verify(messages, never()).latestOfKind(any(), any());
        verify(audit, never()).record(any());
    }

    /**
     * B5: a retake carries this patient's streak, read from the stored rows (RetakeOrdinalAndFreeTierTest holds the
     * count on Postgres); the page escalates on it. A retake has no urgency and no message is read for it.
     */
    @Test
    void aRetakeCarriesItsStreakAndNoUrgency() {
        Document retake = Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC)
                .withStatus(Document.Status.NEEDS_RETAKE, "test", "low_overall", NOW);
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(retake));
        when(intake.view(DOC)).thenReturn(Mono.just(DocumentView.pending(retake)));
        when(reads.retakeOrdinal(DOC)).thenReturn(Mono.just(3));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.retakeOrdinal()).isEqualTo(3);
            assertThat(r.document().reasonCode()).isEqualTo("low_overall");
            assertThat(r.urgency()).isNull();
            assertThat(r.gate()).isEqualTo(GateState.NONE);
        }).verifyComplete();
        verify(urgencies, never()).currentOrAssess(any());
    }

    /** The other direction: a paper that is not a retake carries 0, and the streak is not asked for at all. */
    @Test
    void aReadPaperCarriesNoStreakAndNeverAsksForOne() {
        done();
        when(reads.retakeOrdinal(DOC)).thenReturn(Mono.just(3));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> assertThat(r.retakeOrdinal()).isZero()).verifyComplete();
        verify(reads, never()).retakeOrdinal(any());
    }

    /**
     * The race behind the journey's two-page flake (main CI runs 39245684895 on B5 #81 and 39379212824 on #84, each at "the
     * page image on the result page" hasSize(1)): the page's values are read only after the stored level has resolved. A page opened
     * the moment the paper turns DONE can find the chain still composing, so {@code currentOrAssess} assesses there and
     * then, and its verdicts commit in the same transaction as the level (PostgresDocumentUrgencyAdapter). A view read
     * alongside it saw no verdict while the level said value_critical, so the NOW block found no CRITICAL row and drew
     * the whole page as its proof, above the page already drawn below: two page images. Modelled as the database
     * answers it: a view is what has committed when it is read. The real-database reproduction is
     * WebOnlyJourneyAcceptanceTest#aResultOpenedWhileThePaperIsStillBeingAssessedIsTheFinishedPage.
     */
    @Test
    void theValuesAreReadOnlyAfterTheStoredLevelSoTheyCarryItsVerdicts() {
        done();
        AtomicBoolean committed = new AtomicBoolean();
        when(urgencies.currentOrAssess(DOC)).thenReturn(Mono.fromCallable(() -> {
            committed.set(true);   // the assessment's transaction: the level and the verdicts together
            return new StoredUrgency(DOC, Urgency.NOW, List.of("value_critical:potassium|stopgap"), NOW);
        }));
        when(intake.view(DOC)).thenReturn(Mono.fromCallable(() -> potassiumView(committed.get() ? "critical" : null)));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.urgencyReasons()).containsExactly("value_critical:potassium|stopgap");
            assertThat(r.document().values()).as("the verdict the stored level was assessed with")
                    .extracting(v -> v.get("verdict")).containsExactly("critical");
        }).verifyComplete();
    }

    /**
     * The other direction: waiting for the level never costs the page its values. A DONE paper whose level cannot be read
     * (empty: the assessment found no row to raise) is still drawn from its view, with no level, and its view recorded.
     */
    @Test
    void aDonePaperWithNoLevelStillGetsItsValues() {
        done();
        when(urgencies.currentOrAssess(DOC)).thenReturn(Mono.empty());
        when(intake.view(DOC)).thenReturn(Mono.just(potassiumView(null)));
        StepVerifier.create(service.result(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.urgency()).isNull();
            assertThat(r.document().values()).extracting(v -> v.get("name")).containsExactly("Potassium");
        }).verifyComplete();
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_VIEW && DOC.equals(e.documentId())));
    }

    /**
     * Waiting for the level never costs the read its audit row: a page whose assessment fails is still recorded as
     * viewed (the row is written before the level is read, and the failure still reaches the caller). Reading the
     * values after the level must not move the DOCUMENT_VIEW row behind an assessment that may fail.
     */
    @Test
    void aFailedAssessmentStillLeavesTheViewsAuditRow() {
        done();
        when(urgencies.currentOrAssess(DOC)).thenReturn(Mono.error(new IllegalStateException("assessment failed")));
        StepVerifier.create(service.result(FAMILY, DOC)).expectErrorMessage("assessment failed").verify();
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_VIEW && DOC.equals(e.documentId())));
    }

    /** A DONE view with one Potassium row, its verdict as the database holds it at the moment of the read. */
    private static DocumentView potassiumView(String verdict) {
        Map<String, Object> row = new HashMap<>(Map.of("name", "Potassium", "value", "11.0", "unit", "mmol/L"));
        row.put("verdict", verdict);
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", null, 0.95, null, 1,
                List.of(row), List.of(), List.of(), Map.of(), null);
    }

    private static OutboundMessage message(OutboundMessage.GateStatus gate, String body, Urgency level) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, PATIENT, DOC, OutboundMessage.Kind.EXPLANATION, level,
                List.of(), body, gate, null, null, null, null, NOW);
    }
}
