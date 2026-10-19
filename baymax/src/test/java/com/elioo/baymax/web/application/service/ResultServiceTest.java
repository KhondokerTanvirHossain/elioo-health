package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
    ResultService service;

    @BeforeEach
    void wire() {
        service = new ResultService(records, documents, intake, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        // sent 30 s ago and moved to PROCESSING 10 s ago: its arrival (createdAt) and its last change (updatedAt) differ
        Document processing = Document.received(PATIENT, FAMILY, 2, SENT).withId(DOC).withStatus(Document.Status.PROCESSING, null, NOW.minusSeconds(10));
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(processing));
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(MA, true)));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW)));
        when(audit.record(any())).thenReturn(Mono.empty());
        when(intake.view(DOC)).thenReturn(Mono.just(DocumentView.pending(processing)));
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
                "body", 0, 2, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F3: model wording only when released");
    }

    /** The other direction: released wording travels, and a document still being read carries no urgency. */
    @Test
    void releasedWordingTravelsAndUrgencyWaitsForDone() {
        DocumentView done = DocumentView.pending(Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC).withStatus(Document.Status.DONE, null, NOW));
        assertThat(new ResultView(done, MA, true, new ViewerLocale(true), Urgency.NOW, null, GateState.RELEASED, "body", 0, 2, NOW, NOW)
                .wording()).isEqualTo("body");
        DocumentView reading = DocumentView.pending(Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC));
        assertThatThrownBy(() -> new ResultView(reading, MA, true, new ViewerLocale(true), Urgency.NOW, List.of(), GateState.NONE,
                null, 0, 2, NOW, NOW)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from DONE onwards");
    }
}
