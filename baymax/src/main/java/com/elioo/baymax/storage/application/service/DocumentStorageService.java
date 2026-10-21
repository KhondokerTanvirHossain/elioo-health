package com.elioo.baymax.storage.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.ObjectMissingException;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.application.port.out.StoredObjectLedgerPort;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.ImageDimensions;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.storage.domain.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores JPEG pages and crops in the private bucket and mirrors each one into the ledger, so a document's
 * images can be listed, sized and deleted without ever putting bytes in Postgres.
 *
 * <p>Only JPEG is accepted (checked by magic bytes, not by trust): keys end in {@code .jpg} and the timeline
 * serves them as such, so the caller renders PDFs and other formats to JPEG first. The storage bean exists
 * only when {@code baymax.storage.bucket} is set; without it every call fails at call time with a clear
 * error, and the module still loads.</p>
 */
@Slf4j
@Service
public class DocumentStorageService implements DocumentStorageUseCase {

    static final String JPEG = "image/jpeg";
    /**
     * How many objects the read-path backfill fetches and holds at once, in the whole process: one gate shared by every
     * paper read (this service is a singleton), so K family members opening legacy papers together still hold two.
     */
    static final int MEASURE_CONCURRENCY = 2;
    /** How long one object's fetch and measurement may take, waiting for a permit included; then it is left untried. */
    static final Duration MEASURE_TIMEOUT = Duration.ofSeconds(2);
    /** How long a paper read waits for its backfill in all; then it answers with the sizes it has. */
    static final Duration READ_BUDGET = Duration.ofSeconds(3);

    private final ObjectProvider<StoragePort> storage;
    private final StoredObjectLedgerPort ledger;
    private final BaymaxProperties properties;
    private final FetchGate fetches = new FetchGate(MEASURE_CONCURRENCY);

    public DocumentStorageService(ObjectProvider<StoragePort> storage, StoredObjectLedgerPort ledger,
                                  BaymaxProperties properties) {
        this.storage = storage;
        this.ledger = ledger;
        this.properties = properties;
    }

    @Override
    public Mono<StoredObject> storePage(UUID familyId, UUID patientId, UUID documentId, int pageNo, byte[] jpegBytes) {
        return store(familyId, patientId, documentId, ObjectKind.PAGE, pageNo, null,
                StorageKeys.page(familyId, patientId, documentId, pageNo), jpegBytes);
    }

    @Override
    public Mono<StoredObject> storeCrop(UUID familyId, UUID patientId, UUID documentId, String itemId, byte[] jpegBytes) {
        return store(familyId, patientId, documentId, ObjectKind.CROP, null, itemId,
                StorageKeys.crop(familyId, patientId, documentId, itemId), jpegBytes);
    }

    private Mono<StoredObject> store(UUID familyId, UUID patientId, UUID documentId, ObjectKind kind,
                                     Integer pageNo, String itemId, String key, byte[] bytes) {
        if (!isJpeg(bytes)) {
            return Mono.error(new IllegalArgumentException("Only JPEG images are stored; render other formats first"));
        }
        return storagePort()
                .flatMap(port -> port.put(key, bytes, JPEG))
                .zipWith(measure(bytes))
                .flatMap(stored -> {
                    Optional<ImageDimensions> size = stored.getT2();
                    // tried now, sized or not: a row stored with no size (its header did not read) is never fetched to measure
                    Instant now = Instant.now();
                    return ledger.save(new StoredObject(null, familyId, patientId, documentId, kind, pageNo, itemId, key,
                            JPEG, stored.getT1(), now, size.map(ImageDimensions::width).orElse(null),
                            size.map(ImageDimensions::height).orElse(null), now));
                })
                .doOnNext(saved -> log.info("[baymax] stored {} bytes={} sized={} documentId={} key={}",
                        kind.dbValue(), saved.sizeBytes(), saved.widthPx() != null, documentId, key));
    }

    /**
     * The image's size from its header, off the event loop: header parsing is blocking CPU work, run on the bounded
     * elastic scheduler as {@code S3StorageAdapter} runs its SDK calls. Empty when the header does not read, never zero.
     */
    private Mono<Optional<ImageDimensions>> measure(byte[] bytes) {
        long cap = properties.getUpload().getMaxFileBytes();
        return Mono.fromCallable(() -> ImageDimensions.of(bytes, cap)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<URI> signedUrl(String storageKey) {
        return storagePort().flatMap(port -> port.signedGetUrl(storageKey, properties.getStorage().getSignedUrlTtl()));
    }

    @Override
    public Flux<StoredObject> objectsOf(UUID documentId) {
        return ledger.findByDocument(documentId);
    }

    /**
     * The document's rows of this kind with their sizes. Only a row never tried (written before the dimension columns
     * existed) is fetched, and only once ever: whatever its read finds is recorded with {@code dimensions_checked_at}, a
     * size or "unreadable" (object missing, bytes that do not decode, over the upload cap), and the record only takes on
     * a row never tried, so a stored size is never rewritten and an unreadable object is never fetched again. A row over
     * the upload cap by its ledger size is recorded unreadable without being fetched at all.
     *
     * <p>Bounds: at most {@link #MEASURE_CONCURRENCY} objects are fetched and held at once in the whole process (one
     * {@link FetchGate} for every read), each fetch gives up after {@link #MEASURE_TIMEOUT}, and the whole read after
     * {@link #READ_BUDGET}, answering with what it has. A fetch that times out or fails for any other reason (a store slow
     * or down) is not recorded: the row stays untried and a later read tries it again. Never errors for a measurement: a
     * paper is never refused, or held, for a size it cannot show.</p>
     */
    @Override
    public Flux<StoredObject> measuredObjectsOf(UUID documentId, ObjectKind kind) {
        return ledger.findByDocument(documentId)
                .filter(o -> o.kind() == kind)
                .collectList()
                .flatMapMany(rows -> {
                    List<StoredObject> untried = rows.stream().filter(StoredObject::neverMeasured).toList();
                    if (untried.isEmpty()) {
                        return Flux.fromIterable(rows);
                    }
                    Map<String, StoredObject> measured = new ConcurrentHashMap<>();
                    Tally tally = new Tally();
                    return Flux.fromIterable(untried)
                            .flatMap(o -> measured(o, tally), MEASURE_CONCURRENCY)
                            .doOnNext(o -> measured.put(o.storageKey(), o))
                            .then()
                            .timeout(READ_BUDGET, Mono.fromRunnable(tally::overBudget))
                            .doFinally(signal -> tally.log(documentId, kind, untried.size()))
                            .thenMany(Flux.fromIterable(rows).map(o -> measured.getOrDefault(o.storageKey(), o)));
                });
    }

    /** One row never tried: measured and recorded, recorded unreadable, or (the store not answering) left untried. */
    private Mono<StoredObject> measured(StoredObject o, Tally tally) {
        if (o.sizeBytes() > properties.getUpload().getMaxFileBytes()) {
            return recordedUnreadable(o, tally);
        }
        return storagePort()
                .flatMap(port -> fetches.run(() -> port.get(o.storageKey()).flatMap(this::measure)))
                .timeout(MEASURE_TIMEOUT)
                .onErrorResume(ObjectMissingException.class, missing -> Mono.just(Optional.empty()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(size -> size.isPresent() ? recorded(o, size.get(), tally) : recordedUnreadable(o, tally))
                .onErrorResume(e -> {
                    // the store slow, down or unconfigured: not a permanent answer, so nothing is recorded
                    tally.failed(e);
                    log.debug("[baymax] could not fetch stored {} to measure documentId={} ({})", o.kind().dbValue(),
                            o.documentId(), e.getClass().getSimpleName());
                    return Mono.just(o);
                });
    }

    /** The size read, recorded; a failed record is the operator's to see (WARN), and the size is still answered. */
    private Mono<StoredObject> recorded(StoredObject o, ImageDimensions size, Tally tally) {
        tally.measured();
        StoredObject sized = o.withDimensions(size, Instant.now());
        return ledger.recordDimensions(o.storageKey(), size.width(), size.height())
                .thenReturn(sized)
                .onErrorResume(e -> {
                    log.warn("[baymax] could not record a measured size documentId={} kind={} ({})", o.documentId(),
                            o.kind().dbValue(), e.getClass().getSimpleName());
                    return Mono.just(sized);
                });
    }

    private Mono<StoredObject> recordedUnreadable(StoredObject o, Tally tally) {
        tally.unreadable();
        return ledger.recordUnmeasurable(o.storageKey())
                .thenReturn(o.withoutDimensions(Instant.now()))
                .onErrorResume(e -> {
                    log.warn("[baymax] could not record an unreadable object documentId={} kind={} ({})", o.documentId(),
                            o.kind().dbValue(), e.getClass().getSimpleName());
                    return Mono.just(o);
                });
    }

    /** What one paper read's backfill did, counted, for one INFO line per read that tried anything: never a key. */
    private static final class Tally {
        private final AtomicInteger measured = new AtomicInteger();
        private final AtomicInteger unreadable = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicReference<String> firstFailure = new AtomicReference<>();
        private volatile boolean overBudget;

        void measured() {
            measured.incrementAndGet();
        }

        void unreadable() {
            unreadable.incrementAndGet();
        }

        void failed(Throwable e) {
            failed.incrementAndGet();
            firstFailure.compareAndSet(null, e.getClass().getSimpleName());
        }

        void overBudget() {
            overBudget = true;
        }

        void log(UUID documentId, ObjectKind kind, int untried) {
            log.info("[baymax] measured stored {}s documentId={} untried={} measured={} unreadable={} failed={}{}{}", kind.dbValue(),
                    documentId, untried, measured.get(), unreadable.get(), failed.get(),
                    firstFailure.get() == null ? "" : " (" + firstFailure.get() + ")", overBudget ? " over-budget" : "");
        }
    }

    @Override
    public Mono<Long> bytesStored(UUID documentId) {
        return ledger.bytesStored(documentId);
    }

    @Override
    public Mono<DeletionReport> deleteDocument(UUID familyId, UUID patientId, UUID documentId) {
        return deleteByPrefix(StorageKeys.documentPrefix(familyId, patientId, documentId));
    }

    @Override
    public Mono<DeletionReport> deleteCrops(UUID familyId, UUID patientId, UUID documentId) {
        return deleteByPrefix(StorageKeys.documentPrefix(familyId, patientId, documentId) + "crop-");
    }

    @Override
    public Mono<byte[]> pageBytes(UUID familyId, UUID patientId, UUID documentId, int pageNo) {
        return storagePort().flatMap(port -> port.get(StorageKeys.page(familyId, patientId, documentId, pageNo)));
    }

    @Override
    public Mono<DeletionReport> deletePatient(UUID familyId, UUID patientId) {
        return deleteByPrefix(StorageKeys.patientPrefix(familyId, patientId));
    }

    @Override
    public Mono<DeletionReport> deleteFamily(UUID familyId) {
        return deleteByPrefix(StorageKeys.familyPrefix(familyId));
    }

    /**
     * The bucket half of a paper's delete when its rows went first. The caller has already removed the ledger rows in
     * the row transaction ({@code DocumentRecordPort.deleteDocument}) and says how many there were; the drift check
     * compares the objects removed against that count (plus any ledger row written since, by a pipeline still storing a
     * crop, which is removed here too), so a normal delete is balanced and only real drift warns.
     */
    @Override
    public Mono<DeletionReport> deleteDocumentObjects(UUID familyId, UUID patientId, UUID documentId, long ledgerRowsDeleted) {
        String prefix = StorageKeys.documentPrefix(familyId, patientId, documentId);
        return storagePort()
                .flatMap(port -> port.deleteByPrefix(prefix))
                .flatMap(objects -> ledger.deleteByKeyPrefix(prefix)
                        .map(late -> new DeletionReport(prefix, objects, ledgerRowsDeleted + late)))
                .doOnNext(DocumentStorageService::logged);
    }

    /**
     * Objects first, then ledger rows: if the bucket delete fails, the ledger still says what is out there. The order for
     * a caller that keeps the ledger rows until now (a patient, a family, the crops of a re-crop); a paper's delete takes
     * the other order ({@link #deleteDocumentObjects}).
     */
    private Mono<DeletionReport> deleteByPrefix(String prefix) {
        return storagePort()
                .flatMap(port -> port.deleteByPrefix(prefix))
                .flatMap(objects -> ledger.deleteByKeyPrefix(prefix)
                        .map(rows -> new DeletionReport(prefix, objects, rows)))
                .doOnNext(DocumentStorageService::logged);
    }

    /** A mismatch between the objects removed and the ledger rows that recorded them is drift, worth a WARN. */
    private static void logged(DeletionReport report) {
        if (report.objectsDeleted() != report.ledgerRowsDeleted()) {
            log.warn("[baymax] delete prefix={} removed {} objects but {} ledger rows (drift)",
                    report.prefix(), report.objectsDeleted(), report.ledgerRowsDeleted());
        } else {
            log.info("[baymax] deleted prefix={} objects={} ", report.prefix(), report.objectsDeleted());
        }
    }

    private Mono<StoragePort> storagePort() {
        StoragePort port = storage.getIfAvailable();
        return port == null
                ? Mono.error(new IllegalStateException("Object storage is not configured (baymax.storage.bucket is blank)"))
                : Mono.just(port);
    }

    static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }
}
