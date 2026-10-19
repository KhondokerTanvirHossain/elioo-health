package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
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

/** The add-report sheet's options and the upload behind it: owners only, never a guessed patient, the allowance first. */
class UploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200001");
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final FreeTierUseCase freeTier = mock(FreeTierUseCase.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final AuditPort audit = mock(AuditPort.class);
    private UploadService service;
    private final FamilyAccount family = new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW);

    @BeforeEach
    void wire() {
        service = new UploadService(timeline, records, freeTier, intake, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        when(audit.record(any())).thenReturn(Mono.empty());
    }

    private static PatientSummary summary(String name, boolean owner) {
        return new PatientSummary(new PatientProfile(UUID.nameUUIDFromBytes(name.getBytes()), FAMILY, name, 64,
                PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), owner, 0, null);
    }

    @Test
    void theSheetOffersOnlyPatientsTheViewerMaySendForAndPreselectsOnlyWhenThereIsOne() {
        PatientSummary ma = summary("আম্মা", true);
        PatientSummary shared = summary("খালা", false);
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(family, List.of(ma, shared))));
        when(freeTier.documentAllowance(family)).thenReturn(Mono.just(new DocumentAllowance(true, 3, 1, YearMonth.of(2026, 10))));

        StepVerifier.create(service.uploadOptions(FAMILY)).assertNext(o -> {
            assertThat(o.canSend()).containsExactly(ma);
            assertThat(o.viewOnly()).containsExactly(shared);
            assertThat(o.preselected()).contains(ma);
            assertThat(o.allowance().remaining()).isEqualTo(2);
        }).verifyComplete();
    }

    /** The other direction: two patients the viewer may send for, and neither is chosen for them (spec §4.7). */
    @Test
    void withTwoPatientsNothingIsPreselected() {
        assertThat(new UploadOptions(List.of(summary("আম্মা", true), summary("আব্বু", true)), List.of(),
                DocumentAllowance.unlimited(YearMonth.of(2026, 10))).preselected()).isEmpty();
    }

    /** Moved from TimelineServiceTest: a shared patient can be seen but not added to; nothing reaches intake. */
    @Test
    void aShareMemberCannotUploadAndIntakeIsNeverCalled() {
        PatientProfile ma = summary("আম্মা", false).patient();
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, false)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), List.of(new Upload("a.jpg", new byte[]{1}))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 403
                        && e.getMessage().contains("can be seen but not added to")).verify();
        verify(intake, never()).accept(any(), any());
    }

    /** A patient this family cannot see is a 404, the same answer as one that does not exist; nothing reaches intake. */
    @Test
    void anotherFamilysPatientIs404AndIntakeIsNeverCalled() {
        UUID other = UUID.randomUUID();
        when(records.visiblePatient(FAMILY, other)).thenReturn(Mono.empty());
        StepVerifier.create(service.upload(FAMILY, other, List.of(new Upload("a.jpg", new byte[]{1}))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404).verify();
        verify(intake, never()).accept(any(), any());
    }

    /** The other direction: the owner's upload reaches intake once and is audited against the document it made. */
    @Test
    void theOwnersUploadReachesIntakeAndIsAudited() {
        PatientProfile ma = summary("আম্মা", true).patient();
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}));
        Document received = Document.received(ma.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(intake.accept(ma.id(), pages)).thenReturn(Mono.just(received));
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages)).expectNext(received).verifyComplete();
        verify(intake).accept(ma.id(), pages);
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_UPLOAD && received.id().equals(e.documentId())));
    }
}
