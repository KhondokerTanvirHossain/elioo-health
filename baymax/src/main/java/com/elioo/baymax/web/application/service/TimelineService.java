package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelinePage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The family's view of its records. Every method starts from the session's family id and reaches data only
 * through {@code visiblePatient} / {@code findVisible}, which decide access in SQL; this class never checks
 * ownership by comparing ids itself.
 */
@Service
@RequiredArgsConstructor
public class TimelineService implements TimelineUseCase {

    static final int PAGE_SIZE = 20;
    /** "No cursor": Instant.MAX does not fit a timestamptz, so the first page starts from a far-future bound. */
    static final Instant NO_CURSOR = Instant.parse("9999-12-31T00:00:00Z");

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final DocumentIntakeUseCase intake;
    private final DocumentStorageUseCase storage;
    private final AuditPort audit;
    private final com.elioo.baymax.outbound.application.port.out.OutboundMessagePort messages;
    private final Clock clock;

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
    public Mono<TimelinePage> timeline(UUID familyId, UUID patientId, String cursor) {
        Instant before = parseCursor(cursor);
        return visiblePatient(familyId, patientId)
                .flatMap(access -> audit.record(AuditEvent.family(AuditEvent.Kind.TIMELINE_VIEW, familyId, patientId, null, clock.instant()))
                        .thenReturn(access))
                .flatMapMany(access -> documents.timelineOf(patientId, before, PAGE_SIZE + 1))
                .collectList()
                .flatMap(page -> {
                    boolean more = page.size() > PAGE_SIZE;
                    List<Document> shown = more ? page.subList(0, PAGE_SIZE) : page;
                    String next = more ? shown.get(shown.size() - 1).createdAt().toString() : null;
                    return Flux.fromIterable(shown).concatMap(this::entry).collectList()
                            .map(entries -> new TimelinePage(entries, next));
                });
    }

    private Mono<TimelineEntry> entry(Document d) {
        return documents.sectionCounts(d.id()).defaultIfEmpty(new int[]{0, 0, 0}).map(counts -> {
            boolean fallback = d.docDate() == null;
            LocalDate date = fallback ? LocalDate.ofInstant(d.createdAt(), ZoneOffset.UTC) : d.docDate();
            return new TimelineEntry(d.id(), date, fallback, d.documentType(), d.facility(), d.status().name(),
                    counts[0], counts[1], counts[2], d.unverified() == null ? 0 : d.unverified().total(),
                    StorageKeys.page(d.familyId(), d.patientId(), d.id(), 1), d.createdAt());
        });
    }

    @Override
    public Mono<DocumentView> document(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId)
                .flatMap(d -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                        .then(intake.view(documentId)));
    }

    @Override
    public Mono<Document> upload(UUID familyId, UUID patientId, List<Upload> uploads) {
        return visiblePatient(familyId, patientId)
                .flatMap(access -> access.owner()
                        ? intake.accept(patientId, uploads)
                        : Mono.error(BaymaxException.forbidden("shared_patient_read_only",
                                "a shared patient's records can be seen but not added to")))
                .flatMap(d -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_UPLOAD, familyId, patientId, d.id(), clock.instant()))
                        .thenReturn(d));
    }

    @Override
    public Mono<DeletionReport> deleteDocument(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId)
                .flatMap(d -> visiblePatient(familyId, d.patientId()).map(access -> access.owner()).map(owner -> {
                    if (!owner) {
                        throw BaymaxException.forbidden("shared_patient_read_only",
                                "a shared patient's records can be seen but not deleted");
                    }
                    return d;
                }))
                // objects first, then the ledger, then the rows: a crash between the two leaves an orphan
                // object (harmless) rather than a row pointing at nothing (a broken page)
                .flatMap(d -> storage.deleteDocument(d.familyId(), d.patientId(), d.id())
                        .flatMap(report -> documents.deleteDocument(d.id()).thenReturn(report))
                        .flatMap(report -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_DELETE, familyId, d.patientId(), d.id(), clock.instant()))
                                .thenReturn(report)));
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

    @Override
    public Mono<String> explanationOf(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId)
                .flatMap(d -> messages.latestDeliverable(d.id()))
                .map(m -> m.body());
    }

    private Mono<PatientAccess> visiblePatient(UUID familyId, UUID patientId) {
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)));
    }

    private Mono<Document> visibleDocument(UUID familyId, UUID documentId) {
        return documents.findVisible(familyId, documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)));
    }

    static Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return NO_CURSOR;
        }
        try {
            return Instant.parse(cursor);
        } catch (RuntimeException e) {
            throw BaymaxException.badRequest("invalid_cursor", "cursor must be the next_cursor of a previous page");
        }
    }
}
