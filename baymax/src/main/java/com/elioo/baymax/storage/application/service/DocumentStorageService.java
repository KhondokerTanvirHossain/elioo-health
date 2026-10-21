package com.elioo.baymax.storage.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
    /** How many objects a paper's first read fetches at once to measure them. */
    static final int MEASURE_CONCURRENCY = 2;

    private final ObjectProvider<StoragePort> storage;
    private final StoredObjectLedgerPort ledger;
    private final BaymaxProperties properties;

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
                    return ledger.save(new StoredObject(null, familyId, patientId, documentId, kind, pageNo, itemId, key,
                            JPEG, stored.getT1(), Instant.now(), size.map(ImageDimensions::width).orElse(null),
                            size.map(ImageDimensions::height).orElse(null)));
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
     * The ledger rows with their sizes. A row with none (written before V24) is measured from its object once, here, on
     * its paper's first read, and recorded; the record only takes on a row still without a size, so a stored size is
     * never rewritten. At most {@link #MEASURE_CONCURRENCY} objects are fetched at once, so a legacy paper's first read
     * never holds more than two images in memory. A row whose object is missing or does not read comes back as it is,
     * without a size: a paper is never refused for a size it cannot show.
     */
    @Override
    public Flux<StoredObject> measuredObjectsOf(UUID documentId) {
        return ledger.findByDocument(documentId)
                .flatMapSequential(o -> o.dimensions().isPresent() ? Mono.just(o) : measured(o), MEASURE_CONCURRENCY);
    }

    private Mono<StoredObject> measured(StoredObject o) {
        return storagePort()
                .flatMap(port -> port.get(o.storageKey()))
                .flatMap(this::measure)
                .flatMap(size -> size.isEmpty() ? Mono.just(o)
                        : ledger.recordDimensions(o.storageKey(), size.get().width(), size.get().height())
                                .thenReturn(o.withDimensions(size.get())))
                .defaultIfEmpty(o)
                .onErrorResume(e -> {
                    log.info("[baymax] could not measure stored {} documentId={} ({})", o.kind().dbValue(), o.documentId(),
                            e.getClass().getSimpleName());
                    return Mono.just(o);
                });
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
