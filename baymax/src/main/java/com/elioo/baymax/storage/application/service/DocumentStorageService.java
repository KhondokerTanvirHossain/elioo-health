package com.elioo.baymax.storage.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.application.port.out.StoredObjectLedgerPort;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.storage.domain.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
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
                .flatMap(size -> ledger.save(new StoredObject(null, familyId, patientId, documentId, kind,
                        pageNo, itemId, key, JPEG, size, Instant.now())))
                .doOnNext(saved -> log.info("[baymax] stored {} bytes={} documentId={} key={}",
                        kind.dbValue(), saved.sizeBytes(), documentId, key));
    }

    @Override
    public Mono<URI> signedUrl(String storageKey) {
        return storagePort().flatMap(port -> port.signedGetUrl(storageKey, properties.getStorage().getSignedUrlTtl()));
    }

    @Override
    public Flux<StoredObject> objectsOf(UUID documentId) {
        return ledger.findByDocument(documentId);
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

    /** Objects first, then ledger rows: if the bucket delete fails, the ledger still says what is out there. */
    private Mono<DeletionReport> deleteByPrefix(String prefix) {
        return storagePort()
                .flatMap(port -> port.deleteByPrefix(prefix))
                .flatMap(objects -> ledger.deleteByKeyPrefix(prefix)
                        .map(rows -> new DeletionReport(prefix, objects, rows)))
                .doOnNext(report -> {
                    if (report.objectsDeleted() != report.ledgerRowsDeleted()) {
                        log.warn("[baymax] delete prefix={} removed {} objects but {} ledger rows (drift)",
                                prefix, report.objectsDeleted(), report.ledgerRowsDeleted());
                    } else {
                        log.info("[baymax] deleted prefix={} objects={} ", prefix, report.objectsDeleted());
                    }
                });
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
