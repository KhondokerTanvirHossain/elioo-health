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
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The document page's read side. A9: the status and the reading view. A12 adds the result. */
class ResultServiceTest {

    static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000201");
    static final UUID PATIENT = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000202");
    static final UUID DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000203");
    static final PatientProfile MA = new PatientProfile(PATIENT, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);

    final HealthRecordPort records = mock(HealthRecordPort.class);
    final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    final AuditPort audit = mock(AuditPort.class);
    ResultService service;

    @BeforeEach
    void wire() {
        service = new ResultService(records, documents, intake, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        Document received = Document.received(PATIENT, FAMILY, 2, NOW.minusSeconds(30)).withId(DOC);
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(received));
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(MA, true)));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW)));
        when(audit.record(any())).thenReturn(Mono.empty());
        when(intake.view(DOC)).thenReturn(Mono.just(DocumentView.pending(received)));
    }

    @ParameterizedTest(name = "{0} is in Bangladesh: {1}")
    @CsvSource({"+8801700000000,true", "+8801700000799,true", "+447700900123,false", "+971500000000,false", "+15555550123,false"})
    void theViewersCountryComesFromTheirNumber(String e164, boolean bangladesh) {
        assertThat(ViewerLocale.of(e164).bangladesh()).isEqualTo(bangladesh);
    }

    /** The poll reads the status and nothing else: no audit row every 5 s, no view, no patient. */
    @Test
    void theStatusIsTheStoredStatusAloneAndRecordsNothing() {
        StepVerifier.create(service.status(FAMILY, DOC)).expectNext(Document.Status.RECEIVED).verifyComplete();
        verify(audit, never()).record(any());
        verify(intake, never()).view(any());
    }

    @Test
    void theReadingViewNamesThePatientAndKnowsWhenItArrivedAndWhereTheReaderIs() {
        StepVerifier.create(service.reading(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.patient().name()).isEqualTo("Ma");
            assertThat(r.owner()).isTrue();
            assertThat(r.document().status()).isEqualTo("RECEIVED");
            assertThat(r.receivedAt()).isEqualTo(NOW.minusSeconds(30));
            assertThat(r.now()).isEqualTo(NOW);
            assertThat(r.viewer().bangladesh()).isTrue();
        }).verifyComplete();
        // the reading page is the document page: it records the view, as the document page always has
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_VIEW && DOC.equals(e.documentId())));
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
}
