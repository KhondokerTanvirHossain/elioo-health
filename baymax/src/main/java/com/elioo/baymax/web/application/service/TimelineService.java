package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PaperDeletion;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelineCursor;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
import com.elioo.baymax.web.domain.TimelinePage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.family.application.service.FamilyAccountService;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The family's view of its records. Every method starts from the session's family id and reaches data only
 * through {@code visiblePatient} / {@code findVisible}, which decide access in SQL; this class never checks
 * ownership by comparing ids itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineService implements TimelineUseCase {

    public static final int PAGE_SIZE = 20;
    /** "No cursor": Instant.MAX does not fit a timestamptz, so the first page starts from a far-future bound. */
    static final Instant NO_CURSOR = Instant.parse("9999-12-31T00:00:00Z");

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final DocumentIntakeUseCase intake;
    private final DocumentStorageUseCase storage;
    private final AuditPort audit;
    private final Clock clock;
    /** F1: the stored level the document page shows; nothing under web assesses urgency itself. */
    private final DocumentUrgencyUseCase urgency;
    /** C2: the one owner of the timeline's order, cursor, stored chip and counts; read only, after visibility. */
    private final TimelineReadPort timelineRead;

    @Override
    public Mono<FamilyOverview> me(UUID familyId) {
        Mono<List<PatientSummary>> patients = records.visiblePatients(familyId)
                .concatMap(access -> Mono.zip(
                        records.countDocumentsOfPatient(access.patient().id()),
                        documents.timelineOf(access.patient().id(), NO_CURSOR, 1).next()
                                .map(Document::createdAt).map(java.util.Optional::of)
                                .defaultIfEmpty(java.util.Optional.empty())
                ).map(t -> new PatientSummary(access.patient(), access.owner(), t.getT1(), t.getT2().orElse(null))))
                .collectList();
        return records.findFamily(familyId)
                .switchIfEmpty(Mono.error(BaymaxException.unauthorized("session_family_gone", "the family no longer exists")))
                .zipWith(patients, FamilyOverview::new);
    }

    @Override
    public Mono<TimelinePage> timeline(UUID familyId, UUID patientId, String cursor, TimelineFilter filter) {
        // parsed inside the chain, so a bad cursor is a 400 signal and nothing is read for it
        return Mono.fromCallable(() -> TimelineCursor.parse(cursor))
                .flatMap(after -> visiblePatient(familyId, patientId)
                        .flatMap(access -> audit.record(AuditEvent.family(AuditEvent.Kind.TIMELINE_VIEW, familyId, patientId, null, clock.instant()))
                                .thenReturn(access))
                        .flatMap(access -> timelineRead.page(patientId, after, filter, PAGE_SIZE + 1).collectList()))
                .map(page -> {
                    boolean more = page.size() > PAGE_SIZE;
                    List<TimelineEntry> shown = more ? page.subList(0, PAGE_SIZE) : page;
                    TimelineEntry last = more ? shown.get(shown.size() - 1) : null;
                    // the whole (date, upload, id) of the last row shown: rows sharing an instant are neither skipped nor repeated
                    String next = last == null ? null : new TimelineCursor(last.date(), last.createdAt(), last.documentId()).encode();
                    return new TimelinePage(shown, next);
                });
    }

    @Override
    public Mono<DocumentView> document(UUID familyId, UUID documentId) {
        // the stored level is read (or, for an unassessed DONE document, assessed once) only after visibility is
        // decided: a document the family cannot see is never assessed on its behalf. The view is read after the level, as
        // ResultService reads it: the level's read may assess the paper, committing its verdicts with it, so a view read
        // first could answer "now" over values with no verdict. The read is recorded before the level, as before, so a
        // failed assessment still leaves its audit row.
        return visibleDocument(familyId, documentId)
                .flatMap(d -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                        .then(Mono.defer(() -> urgency.currentOrAssess(documentId)).map(Optional::of).defaultIfEmpty(Optional.empty()))
                        .flatMap(stored -> intake.view(documentId)
                                .map(view -> view.withUrgency(stored.map(s -> s.level().dbValue()).orElse(null)))));
    }

    @Override
    public Mono<Document> deletableDocument(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId)
                .flatMap(d -> records.visiblePatient(familyId, d.patientId())
                        .switchIfEmpty(Mono.error(() -> BaymaxException.notFound("document_not_found", "no document with id " + documentId)))
                        .flatMap(access -> access.owner() ? Mono.just(d)
                                : Mono.error(BaymaxException.forbidden("shared_patient_read_only",
                                        "a shared patient's records can be seen but not deleted"))));
    }

    /**
     * Rows are deleted and committed FIRST; the bucket is emptied afterwards — {@code FamilyAccountService.deleteFamily}'s
     * rule, for one paper, and for every caller of this method (the HTML delete, {@code /app/api}, {@code /api/v1}).
     *
     * <p>The reverse order destroyed the images and then, when the rows could not be deleted, left a paper that still
     * claimed pages and crops which were already gone — silent and unrecoverable. This way round the only failure mode is
     * images outliving their rows: visible in the bucket, recoverable by a sweep, and invisible to the family, whose paper
     * is gone — which is what they asked for. A storage failure after the commit is therefore logged at ERROR with the
     * marker {@code orphaned_objects}, the paper's id, its prefix and how many objects the ledger recorded, and does NOT
     * fail the request: it answers with {@code objectsDeleted = 0}. The ledger rows go with the other rows
     * ({@code DocumentRecordPort.deleteDocument}), so how many there were is read before they go, and the bucket delete
     * is checked for drift against that count ({@code deleteDocumentObjects}).
     *
     * <p>The delete's audit row is written last, after the bucket: an audit failure (a transient error after the commit)
     * still fails the request, but can no longer skip the bucket and leave its objects orphaned without a line.
     */
    @Override
    public Mono<PaperDeletion> deleteDocument(UUID familyId, UUID documentId) {
        return deletableDocument(familyId, documentId)
                .flatMap(d -> storage.objectsOf(d.id()).count()
                        .flatMap(ledger -> documents.deleteDocument(d.id())
                                .flatMap(rows -> deleteObjects(d, ledger).map(objects -> new PaperDeletion(
                                        StorageKeys.documentPrefix(d.familyId(), d.patientId(), d.id()), objects, ledger, rows))))
                        .flatMap(report -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_DELETE, familyId, d.patientId(), d.id(), clock.instant()))
                                .thenReturn(report)));
    }

    /** The rows are already gone, so a storage failure cannot undo the delete: it is recorded, never propagated. */
    private Mono<Long> deleteObjects(Document d, long ledger) {
        String prefix = StorageKeys.documentPrefix(d.familyId(), d.patientId(), d.id());
        return storage.deleteDocumentObjects(d.familyId(), d.patientId(), d.id(), ledger)
                .map(DeletionReport::objectsDeleted)
                .onErrorResume(e -> {
                    // the class, never the message: a storage error can carry a provider URL or body. The prefix is ids
                    // only, and with the document row gone it is the one place a sweep can read it from.
                    log.error("[baymax] orphaned_objects scope=document id={} prefix={} objects={} — rows are deleted and "
                            + "committed, bucket objects under the prefix survive and need a sweep error={}",
                            d.id(), prefix, ledger, e.getClass().getSimpleName());
                    log.debug("[baymax] orphaned_objects detail id={}", d.id(), e);
                    return Mono.just(0L);
                });
    }

    @Override
    public Mono<URI> imageUrl(UUID familyId, UUID documentId, String storageKey) {
        return visibleDocument(familyId, documentId)
                .filter(d -> storageKey.startsWith(StorageKeys.documentPrefix(d.familyId(), d.patientId(), d.id())))
                .switchIfEmpty(Mono.error(BaymaxException.notFound("image_not_found", "no such image")))
                .flatMap(d -> storage.signedUrl(storageKey));
    }

    @Override
    public Mono<URI> pageUrl(UUID familyId, UUID documentId, int pageNo) {
        return visibleDocument(familyId, documentId)
                .filter(d -> pageNo >= 1 && pageNo <= Math.max(1, d.pageCount()))
                .switchIfEmpty(Mono.error(BaymaxException.notFound("image_not_found", "no such page")))
                .flatMap(d -> storage.signedUrl(StorageKeys.page(d.familyId(), d.patientId(), d.id(), pageNo)));
    }

    private Mono<PatientAccess> visiblePatient(UUID familyId, UUID patientId) {
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)));
    }

    private Mono<Document> visibleDocument(UUID familyId, UUID documentId) {
        return documents.findVisible(familyId, documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)));
    }

    @Override
    public Mono<PatientProfile> updateChronicFlags(UUID familyId, UUID patientId, List<String> chronicFlags) {
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(access -> access.owner()
                        ? records.updateChronicFlags(patientId, FamilyAccountService.normalizeFlags(chronicFlags))
                        : Mono.error(BaymaxException.forbidden("not_owner", "only the family owner can change a profile")));
    }
}
