package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Intake and read-back. The upload is checked against the free tier, rendered to JPEG pages, stored, and
 * recorded; processing then runs on its own, so the family's phone is not held open while models work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIntakeService implements DocumentIntakeUseCase {

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final FreeTierUseCase freeTier;
    private final DocumentStorageUseCase storage;
    private final PageRenderer renderer;
    private final BaymaxProperties properties;
    private final Clock clock;
    /** F9b: extract → explain → nudges, shared with the startup recovery sweep. */
    private final DocumentProcessingChain chain;

    @Override
    public Mono<Document> accept(UUID patientId, List<Upload> uploads) {
        return accept(patientId, uploads, null).map(CreatedDocument::document);
    }

    @Override
    public Mono<CreatedDocument> accept(UUID patientId, List<Upload> uploads, String uploadKey) {
        if (uploads == null || uploads.isEmpty()) {
            return Mono.error(BaymaxException.badRequest("no_file", "attach at least one file"));
        }
        String press = pressKey(uploadKey, uploads);
        return patient(patientId).flatMap(patient -> replay(patient, press)
                .switchIfEmpty(Mono.defer(() -> freeTier.checkCanUploadDocument(patient.familyId())
                        .then(Mono.fromCallable(() -> render(uploads)))
                        .flatMap(pages -> store(patient, pages, press)))));
    }

    @Override
    public Flux<CreatedDocument> acceptEach(UUID patientId, List<Upload> uploads, String uploadKey) {
        if (uploads == null || uploads.isEmpty()) {
            return Flux.error(BaymaxException.badRequest("no_file", "attach at least one file"));
        }
        String press = pressKey(uploadKey, uploads);
        return patient(patientId).flatMapMany(patient -> Flux.range(0, uploads.size())
                .concatMap(i -> replay(patient, key(press, i)).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .collectList()
                .flatMapMany(found -> {
                    List<Integer> missing = IntStream.range(0, found.size()).filter(i -> found.get(i).isEmpty()).boxed().toList();
                    Mono<Void> allowed = missing.isEmpty() ? Mono.empty()
                            : freeTier.checkCanUploadDocuments(patient.familyId(), missing.size());
                    // every missing file is read before the first document exists: one bad file refuses them all
                    return allowed.then(Mono.fromCallable(() -> {
                                Map<Integer, List<byte[]>> pages = new HashMap<>();
                                for (int i : missing) {
                                    pages.put(i, render(List.of(uploads.get(i))));
                                }
                                return pages;
                            }))
                            .flatMapMany(pages -> Flux.range(0, uploads.size()).concatMap(i -> found.get(i).map(Mono::just)
                                    .orElseGet(() -> store(patient, pages.get(i), key(press, i)))));
                }));
    }

    /**
     * The row, then its pages, then processing — for the press that made the row only. The same press already made it
     * (a race on the key): that press stores and processes, this one returns its document — if it is this patient's
     * ({@link #samePatient}). A failed store removes the row, so nothing is left without its pages and a retry with the
     * same key starts clean. A row that vanished between the loser's insert and its read (the winner's store failed) is
     * a refusal, never an empty answer: the sheet is drawn again with a new key.
     */
    private Mono<CreatedDocument> store(PatientProfile patient, List<byte[]> pages, String uploadKey) {
        return documents.createOnce(Document.received(patient.id(), patient.familyId(), pages.size(), clock.instant()), uploadKey)
                .switchIfEmpty(Mono.error(() -> BaymaxException.conflict("upload_key_reused",
                        "the document this upload key made went away before it could be read back")))
                .flatMap(created -> !created.fresh() ? samePatient(patient, created.document()).thenReturn(created)
                        : storePages(created.document(), pages)
                                .onErrorResume(e -> forget(created.document()).then(Mono.error(e)))
                                .thenReturn(created)
                                .doOnNext(made -> startProcessing(made.document(), pages)));
    }

    /** The document an earlier press with this key made, for this patient; another patient's is a conflict, never a replay. */
    private Mono<CreatedDocument> replay(PatientProfile patient, String uploadKey) {
        if (uploadKey == null) {
            return Mono.empty();
        }
        return documents.findByUploadKey(patient.familyId(), uploadKey)
                .flatMap(d -> samePatient(patient, d).thenReturn(new CreatedDocument(d, false)));
    }

    /**
     * The one place the patient rule is asserted, on both paths that hand an existing document back (a replay, and the
     * loser of a race): only to the press for the same patient. Another patient's is 409, never a document to land on.
     */
    private static Mono<Void> samePatient(PatientProfile patient, Document d) {
        return d.patientId().equals(patient.id()) ? Mono.empty()
                : Mono.error(BaymaxException.conflict("upload_key_reused", "the upload key belongs to another patient's document"));
    }

    /**
     * The key a press is stored under: the sheet's key AND what was sent with it. A double tap, a resend or a script's
     * retry sends the same files and is the same press; a sheet restored by Back keeps its key, and the family choosing
     * another paper on it (or adding a page) is a new press — read and counted, never the old document handed back. The
     * digest is SHA-256 over every file, its length then its bytes, in order, cut to {@value #DIGEST_HEX} hex characters:
     * the sheet's key (at most 64), ':', the digest and ':' plus the file's index stay inside V21's 80-character CHECK.
     */
    static String pressKey(String uploadKey, List<Upload> uploads) {
        if (uploadKey == null) {
            return null;
        }
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
        for (Upload upload : uploads) {
            byte[] bytes = upload.bytes() == null ? new byte[0] : upload.bytes();
            sha.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
            sha.update(bytes);
        }
        return uploadKey + ":" + java.util.HexFormat.of().formatHex(sha.digest()).substring(0, DIGEST_HEX);
    }

    private static final int DIGEST_HEX = 10;

    /**
     * Objects first, then the row — deliberately the reverse of {@code TimelineService.deleteDocument} (rows first, then
     * objects). That order protects a paper whose rows cannot be deleted from losing its images; here there is no such
     * paper: the row is deleted even when removing the objects fails, so no row is ever left pointing at nothing, and an
     * object left behind is an orphan nobody can see. Never fails itself, so the store's own error is the one the caller
     * sees.
     */
    private Mono<Void> forget(Document d) {
        return storage.deleteDocument(d.familyId(), d.patientId(), d.id())
                .doOnError(e -> log.warn("[baymax] intake could not remove stored pages documentId={} error={}",
                        d.id(), e.getClass().getSimpleName()))
                .onErrorResume(e -> Mono.empty())
                .then(documents.deleteDocument(d.id()))
                .doOnNext(rows -> log.info("[baymax] intake removed a document whose pages were not stored documentId={}", d.id()))
                .doOnError(e -> log.error("[baymax] intake could not remove a document without pages documentId={} error={}",
                        d.id(), e.getClass().getSimpleName()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private static String key(String uploadKey, int index) {
        return uploadKey == null ? null : uploadKey + ":" + index;
    }

    /** Renders every upload to JPEG pages, in the order the caller sent them. */
    private List<byte[]> render(List<Upload> uploads) {
        int maxPages = properties.getExtract().getMaxPages();
        List<byte[]> pages = new ArrayList<>();
        for (Upload upload : uploads) {
            pages.addAll(renderer.toJpegPages(upload.bytes(), upload.filename(), maxPages));
            if (pages.size() > maxPages) {
                throw BaymaxException.badRequest("too_many_pages",
                        "a document may have at most " + maxPages + " pages");
            }
        }
        return pages;
    }

    private Mono<Void> storePages(Document document, List<byte[]> pages) {
        return Flux.range(0, pages.size())
                .concatMap(index -> storage.storePage(document.familyId(), document.patientId(),
                        document.id(), index + 1, pages.get(index)))
                .then();
    }

    /**
     * Processing is deliberately detached: the HTTP response is 202 and the work continues. Errors are
     * recorded on the document by the pipeline itself, so nothing is lost when this subscription ends; a restart
     * that kills it leaves the document RECEIVED/PROCESSING, which the startup recovery sweep re-runs (F9b).
     */
    private void startProcessing(Document document, List<byte[]> pages) {
        chain.run(document, pages)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        done -> log.info("[baymax] document finished documentId={} status={}",
                                done.id(), done.status()),
                        error -> log.error("[baymax] document processing ended in error documentId={} error={}",
                                document.id(), error.getClass().getSimpleName()));
    }

    @Override
    public Mono<DocumentView> view(UUID documentId) {
        return read(documentId).map(DocumentView.Read::view);
    }

    /**
     * Only a DONE paper's view carries its items and its printed date; every other status, a retake included, gets the
     * status-only view ({@link DocumentView#pending}), so a date misread off a page the model could not read never
     * leaves here (D2; {@code FilingDayOnPostgresTest} holds it on a real row).
     */
    @Override
    public Mono<DocumentView.Read> read(UUID documentId) {
        return documents.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found",
                        "no document with id " + documentId)))
                .flatMap(document -> (document.status() == Document.Status.DONE
                        ? withItems(document)
                        : Mono.just(DocumentView.pending(document))).map(v -> new DocumentView.Read(document, v)));
    }

    private Mono<DocumentView> withItems(Document document) {
        return Mono.zip(
                documents.observationsOf(document.id()).collectList(),
                documents.medicinesOf(document.id()).collectList(),
                documents.followUpsOf(document.id()).collectList(),
                documents.clinicalContextOf(document.id()).defaultIfEmpty(Map.of())
        ).map(items -> new DocumentView(
                document.id().toString(),
                document.status().name(),
                null,
                document.documentType(),
                document.docDate() == null ? null : document.docDate().toString(),
                document.facility(),
                document.confidenceOverall(),
                document.modelFinal(),
                document.pageCount(),
                items.getT1(), items.getT2(), items.getT3(),
                items.getT4().isEmpty() ? null : items.getT4(),
                DocumentView.unverifiedOf(document)));
    }

    private Mono<PatientProfile> patient(UUID patientId) {
        return records.findPatient(patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found",
                        "no patient with id " + patientId)));
    }
}
