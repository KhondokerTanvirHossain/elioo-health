package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.CreatedDocument;
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
import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private UploadService service;
    private final FamilyAccount family = new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW);

    @BeforeEach
    void wire() {
        service = new UploadService(timeline, records, freeTier, intake, documents, audit, Clock.fixed(NOW, ZoneOffset.UTC));
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

    /**
     * Admission, asked before a press's files are read into memory: the patient rule of {@code upload}, and nothing
     * else. The free allowance is deliberately not asked here: a retry of a press that used the last free paper must
     * still be answered with its document, and only intake can tell a replay from a new press.
     */
    @Test
    void admissionIsThePatientRuleAndNothingElse() {
        PatientProfile ma = summary("আম্মা", true).patient();
        PatientProfile shared = summary("খালা", false).patient();
        UUID nobody = UUID.nameUUIDFromBytes("nobody".getBytes());
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(records.visiblePatient(FAMILY, shared.id())).thenReturn(Mono.just(new PatientAccess(shared, false)));
        when(records.visiblePatient(FAMILY, nobody)).thenReturn(Mono.empty());

        StepVerifier.create(service.admit(FAMILY, ma.id(), null)).verifyComplete();
        StepVerifier.create(service.admit(FAMILY, shared.id(), null))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 403
                        && "shared_patient_read_only".equals(b.reason())).verify();
        StepVerifier.create(service.admit(FAMILY, nobody, null))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404
                        && "patient_not_found".equals(b.reason())).verify();
        org.mockito.Mockito.verifyNoInteractions(freeTier);
        verify(intake, never()).accept(any(), any(), any());
        verify(intake, never()).acceptEach(any(), any(), any());
    }

    /** Moved from TimelineServiceTest: a shared patient can be seen but not added to; nothing reaches intake. */
    @Test
    void aShareMemberCannotUploadAndIntakeIsNeverCalled() {
        PatientProfile ma = summary("আম্মা", false).patient();
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, false)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), List.of(new Upload("a.jpg", new byte[]{1}))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 403
                        && e.getMessage().contains("can be seen but not added to")).verify();
        verify(intake, never()).accept(any(), any(), any());
    }

    /** A patient this family cannot see is a 404, the same answer as one that does not exist; nothing reaches intake. */
    @Test
    void anotherFamilysPatientIs404AndIntakeIsNeverCalled() {
        UUID other = UUID.randomUUID();
        when(records.visiblePatient(FAMILY, other)).thenReturn(Mono.empty());
        StepVerifier.create(service.upload(FAMILY, other, List.of(new Upload("a.jpg", new byte[]{1}))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404).verify();
        verify(intake, never()).accept(any(), any(), any());
    }

    /** The other direction: the owner's upload reaches intake once and is audited against the document it made. */
    @Test
    void theOwnersUploadReachesIntakeAndIsAudited() {
        PatientProfile ma = summary("আম্মা", true).patient();
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}));
        Document received = Document.received(ma.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(intake.accept(ma.id(), pages, null)).thenReturn(Mono.just(new CreatedDocument(received, true)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages)).expectNext(received).verifyComplete();
        verify(intake).accept(ma.id(), pages, null);   // no key: the JSON API is never a replay
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_UPLOAD && received.id().equals(e.documentId())));
        verify(documents, never()).linkRetake(any(), any());   // replaces nothing: nothing is linked, nothing is looked up
        verify(documents, never()).findVisible(any(), any());
    }

    // --- D3: the grouping and the key ------------------------------------------------------------------------------

    /** "আলাদা আলাদা" goes to intake as separate documents with the press's key, and each one is audited. */
    @Test
    void separatePagesGoToIntakeAsSeparateDocumentsWithTheKeyAndEachIsAudited() {
        PatientProfile ma = summary("আম্মা", true).patient();
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}), new Upload("b.jpg", new byte[]{2}));
        Document one = Document.received(ma.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        Document two = Document.received(ma.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(intake.acceptEach(ma.id(), pages, "k-sep00010")).thenReturn(Flux.just(new CreatedDocument(one, true), new CreatedDocument(two, true)));
        // the wrong path answers too, so taking it fails on what comes back rather than on a mock's null
        when(intake.accept(any(), any(), any())).thenReturn(Mono.just(new CreatedDocument(Document.received(ma.id(), FAMILY, 2, NOW).withId(UUID.randomUUID()), true)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.SEPARATE, "k-sep00010", null))
                .expectNext(List.of(one, two)).verifyComplete();
        verify(intake, never()).accept(any(), any(), any());
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_UPLOAD && one.id().equals(e.documentId())));
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_UPLOAD && two.id().equals(e.documentId())));
    }

    /** The other direction: "একটা রিপোর্ট" is one document of every file, with the same key. */
    @Test
    void oneReportGoesToIntakeAsOneDocumentWithTheKey() {
        PatientProfile ma = summary("আম্মা", true).patient();
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}), new Upload("b.jpg", new byte[]{2}));
        Document both = Document.received(ma.id(), FAMILY, 2, NOW).withId(UUID.randomUUID());
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(intake.accept(eq(ma.id()), eq(pages), isNull())).thenReturn(Mono.just(new CreatedDocument(Document.received(ma.id(), FAMILY, 2, NOW).withId(UUID.randomUUID()), true)));
        when(intake.acceptEach(any(), any(), any())).thenReturn(Flux.just(new CreatedDocument(both, true), new CreatedDocument(both, true)));
        when(intake.accept(ma.id(), pages, "k-one00010")).thenReturn(Mono.just(new CreatedDocument(both, true)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.ONE, "k-one00010", null))
                .expectNext(List.of(both)).verifyComplete();
        verify(intake, never()).acceptEach(any(), any(), any());
        verify(audit, times(1)).record(any());
    }

    /**
     * The audit says an upload happened only when one did: a replay of the same press (a double tap, a resend) made
     * nothing, so it records nothing — the family still lands on the document.
     */
    @Test
    void aReplayOfTheSamePressIsNotRecordedAsAnUpload() {
        PatientProfile ma = summary("আম্মা", true).patient();
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}));
        Document earlier = Document.received(ma.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(intake.accept(ma.id(), pages, "k-replay01")).thenReturn(Mono.just(new CreatedDocument(earlier, false)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.ONE, "k-replay01", null)).expectNext(List.of(earlier)).verifyComplete();
        verify(audit, never()).record(any());
    }

    /** Both directions in one press: of separate papers, only the ones this press made are recorded, each once. */
    @Test
    void separatePagesRecordOnlyThePapersThisPressMade() {
        PatientProfile ma = summary("আম্মা", true).patient();
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}), new Upload("b.jpg", new byte[]{2}));
        Document landed = Document.received(ma.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        Document made = Document.received(ma.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(intake.acceptEach(ma.id(), pages, "k-replay02")).thenReturn(Flux.just(new CreatedDocument(landed, false), new CreatedDocument(made, true)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.SEPARATE, "k-replay02", null)).expectNext(List.of(landed, made)).verifyComplete();
        verify(audit, times(1)).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_UPLOAD && made.id().equals(e.documentId())));
        verify(audit, never()).record(argThat(e -> landed.id().equals(e.documentId())));
    }

    @Test
    void separatePagesForAPatientTheFamilyMayOnlySeeAreRefusedAndIntakeIsNeverCalled() {
        PatientProfile shared = summary("খালা", false).patient();
        when(records.visiblePatient(FAMILY, shared.id())).thenReturn(Mono.just(new PatientAccess(shared, false)));
        StepVerifier.create(service.upload(FAMILY, shared.id(), List.of(new Upload("a.jpg", new byte[]{1})), Grouping.SEPARATE, "k-share001", null))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 403).verify();
        verify(intake, never()).acceptEach(any(), any(), any());
        verify(intake, never()).accept(any(), any(), any());
    }

    @Test
    void separatePagesForAnotherFamilysPatientAre404() {
        UUID other = UUID.randomUUID();
        when(records.visiblePatient(FAMILY, other)).thenReturn(Mono.empty());
        StepVerifier.create(service.upload(FAMILY, other, List.of(new Upload("a.jpg", new byte[]{1})), Grouping.SEPARATE, "k-other002", null))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404).verify();
        verify(intake, never()).acceptEach(any(), any(), any());
    }

    // --- D1: a retake names the paper it replaces ------------------------------------------------------------------

    private PatientProfile owned(String name) {
        PatientProfile p = summary(name, true).patient();
        when(records.visiblePatient(FAMILY, p.id())).thenReturn(Mono.just(new PatientAccess(p, true)));
        return p;
    }

    private static Document paper(PatientProfile patient, Document.Status status) {
        Document d = Document.received(patient.id(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        return status == Document.Status.RECEIVED ? d : d.withStatus(status, null, NOW);
    }

    /**
     * {@code replaces} must name a paper of THIS patient that is waiting for a retake: a finished paper and another
     * patient's retake (one the family can see) are 400 {@code not_a_retake}, a paper the family cannot see is 404. Each
     * refusal comes before intake: nothing is stored, nothing is created, nothing is linked.
     */
    @Test
    void aRetakeNamesOnlyAPaperOfThisPatientThatNeedsARetake() {
        PatientProfile ma = owned("আম্মা");
        PatientProfile abbu = owned("আব্বু");
        Document done = paper(ma, Document.Status.DONE);
        Document abbusRetake = paper(abbu, Document.Status.NEEDS_RETAKE);
        UUID unseen = UUID.randomUUID();
        when(documents.findVisible(FAMILY, done.id())).thenReturn(Mono.just(done));
        when(documents.findVisible(FAMILY, abbusRetake.id())).thenReturn(Mono.just(abbusRetake));
        when(documents.findVisible(FAMILY, unseen)).thenReturn(Mono.empty());
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}));
        // the wrong path answers too, so taking it fails on what comes back rather than on a mock's null
        when(intake.accept(any(), any(), any())).thenReturn(Mono.just(new CreatedDocument(paper(ma, Document.Status.RECEIVED), true)));
        when(documents.linkRetake(any(), any())).thenReturn(Mono.empty());

        for (UUID refused : List.of(done.id(), abbusRetake.id())) {
            StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.ONE, null, refused))
                    .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400 && "not_a_retake".equals(b.reason()))
                    .verify();
            StepVerifier.create(service.admit(FAMILY, ma.id(), refused))
                    .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400 && "not_a_retake".equals(b.reason()))
                    .verify();
        }
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.ONE, null, unseen))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404 && "document_not_found".equals(b.reason()))
                .verify();
        StepVerifier.create(service.admit(FAMILY, ma.id(), unseen))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404 && "document_not_found".equals(b.reason()))
                .verify();
        verify(intake, never()).accept(any(), any(), any());
        verify(intake, never()).acceptEach(any(), any(), any());
        verify(documents, never()).create(any());
        verify(documents, never()).linkRetake(any(), any());
    }

    /** The patient rule comes first: for a patient the family may only see, the paper named is never even looked up. */
    @Test
    void aViewOnlyPressNamingARetakeIsRefusedBeforeThePaperIsLookedUp() {
        PatientProfile shared = summary("খালা", false).patient();
        when(records.visiblePatient(FAMILY, shared.id())).thenReturn(Mono.just(new PatientAccess(shared, false)));
        when(documents.findVisible(any(), any())).thenReturn(Mono.just(paper(shared, Document.Status.NEEDS_RETAKE)));
        StepVerifier.create(service.admit(FAMILY, shared.id(), UUID.randomUUID()))
                .expectErrorMatches(e -> e instanceof BaymaxException b && "shared_patient_read_only".equals(b.reason())).verify();
        verify(documents, never()).findVisible(any(), any());
    }

    /** The other direction: this patient's retake is admitted, and every document the press made names it. */
    @Test
    void aPressThatReplacesLinksEveryDocumentItMade() {
        PatientProfile ma = owned("আম্মা");
        Document retake = paper(ma, Document.Status.NEEDS_RETAKE);
        when(documents.findVisible(FAMILY, retake.id())).thenReturn(Mono.just(retake));
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}), new Upload("b.jpg", new byte[]{2}));
        Document one = paper(ma, Document.Status.RECEIVED);
        Document two = paper(ma, Document.Status.RECEIVED);
        when(intake.acceptEach(ma.id(), pages, "k-retake01")).thenReturn(Flux.just(new CreatedDocument(one, true), new CreatedDocument(two, true)));
        when(documents.linkRetake(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.admit(FAMILY, ma.id(), retake.id())).verifyComplete();
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.SEPARATE, "k-retake01", retake.id()))
                .expectNext(List.of(one, two)).verifyComplete();
        verify(documents).linkRetake(one.id(), retake.id());
        verify(documents).linkRetake(two.id(), retake.id());
        verify(documents, times(2)).linkRetake(any(), any());
    }

    /**
     * A replayed press (the same key, made nothing new) is linked again: the press that made it may have died between the
     * document and the link, and the second identical UPDATE is a no-op.
     */
    @Test
    void aReplayedPressThatReplacesIsLinkedAgain() {
        PatientProfile ma = owned("আম্মা");
        Document retake = paper(ma, Document.Status.NEEDS_RETAKE);
        when(documents.findVisible(FAMILY, retake.id())).thenReturn(Mono.just(retake));
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}));
        Document earlier = paper(ma, Document.Status.RECEIVED);
        when(intake.accept(ma.id(), pages, "k-retake02")).thenReturn(Mono.just(new CreatedDocument(earlier, false)));
        when(documents.linkRetake(any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.ONE, "k-retake02", retake.id()))
                .expectNext(List.of(earlier)).verifyComplete();
        verify(documents).linkRetake(earlier.id(), retake.id());
    }

    /**
     * A link that fails does not fail the upload: the photo is stored and read either way, and the worst case is today's
     * stale card. The one WARN line carries exactly the two ids, nothing else (no exception text, which could carry a
     * value), asserted on the event's arguments, not on a substring of the message.
     */
    @Test
    void aLinkThatFailsStillStoresTheUpload() {
        PatientProfile ma = owned("আম্মা");
        Document retake = paper(ma, Document.Status.NEEDS_RETAKE);
        when(documents.findVisible(FAMILY, retake.id())).thenReturn(Mono.just(retake));
        List<Upload> pages = List.of(new Upload("a.jpg", new byte[]{1}));
        Document made = paper(ma, Document.Status.RECEIVED);
        when(intake.accept(ma.id(), pages, null)).thenReturn(Mono.just(new CreatedDocument(made, true)));
        when(documents.linkRetake(made.id(), retake.id())).thenReturn(Mono.error(new IllegalStateException("Potassium 11.0 boom")));

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(UploadService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            StepVerifier.create(service.upload(FAMILY, ma.id(), pages, Grouping.ONE, null, retake.id()))
                    .expectNext(List.of(made)).verifyComplete();
        } finally {
            logger.detachAppender(events);
        }
        verify(intake).accept(ma.id(), pages, null);
        verify(audit).record(argThat(e -> e.kind() == AuditEvent.Kind.DOCUMENT_UPLOAD && made.id().equals(e.documentId())));
        List<ch.qos.logback.classic.spi.ILoggingEvent> warns = events.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
        assertThat(warns).singleElement().satisfies(w -> {
            assertThat(w.getArgumentArray()).containsExactly(made.id(), retake.id());
            assertThat(w.getThrowableProxy()).as("no exception (and its message) on the line").isNull();
        });
    }
}
