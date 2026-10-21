package com.elioo.baymax.storage.application.port.in;

import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.StoredObject;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/**
 * Everything Baymax does with document images: store pages and crops (JPEG only), hand out signed URLs
 * for the timeline, report bytes per document, and delete on request at document, patient or family level.
 */
public interface DocumentStorageUseCase {

    Mono<StoredObject> storePage(UUID familyId, UUID patientId, UUID documentId, int pageNo, byte[] jpegBytes);

    Mono<StoredObject> storeCrop(UUID familyId, UUID patientId, UUID documentId, String itemId, byte[] jpegBytes);

    /** Signed GET URL with the configured TTL (15 minutes by default). */
    Mono<URI> signedUrl(String storageKey);

    Flux<StoredObject> objectsOf(UUID documentId);

    Mono<Long> bytesStored(UUID documentId);

    /** Objects, then their ledger rows: for a caller whose ledger rows are still there. */
    Mono<DeletionReport> deleteDocument(UUID familyId, UUID patientId, UUID documentId);

    /**
     * The bucket half of a paper deleted rows first: its ledger rows already went with its other rows, and
     * {@code ledgerRowsDeleted} is how many (read before they went). Empties the paper's prefix, removes any ledger row
     * written since, and warns of drift only when the objects removed differ from the ledger rows that recorded them.
     */
    Mono<DeletionReport> deleteDocumentObjects(UUID familyId, UUID patientId, UUID documentId, long ledgerRowsDeleted);

    /** Only the crops of a document (objects and ledger), keeping its pages; for re-cropping. */
    Mono<DeletionReport> deleteCrops(UUID familyId, UUID patientId, UUID documentId);

    /** The stored JPEG of one page. */
    Mono<byte[]> pageBytes(UUID familyId, UUID patientId, UUID documentId, int pageNo);

    Mono<DeletionReport> deletePatient(UUID familyId, UUID patientId);

    Mono<DeletionReport> deleteFamily(UUID familyId);
}
