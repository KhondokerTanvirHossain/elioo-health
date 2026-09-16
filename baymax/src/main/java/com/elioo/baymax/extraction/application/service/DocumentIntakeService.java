package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final DocumentExtractionService extraction;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public Mono<Document> accept(UUID patientId, List<Upload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            return Mono.error(BaymaxException.badRequest("no_file", "attach at least one file"));
        }
        return patient(patientId)
                .flatMap(patient -> freeTier.checkCanUploadDocument(patient.familyId()).thenReturn(patient))
                .flatMap(patient -> {
                    List<byte[]> pages = render(uploads);
                    return documents.create(Document.received(patient.id(), patient.familyId(),
                                    pages.size(), clock.instant()))
                            .flatMap(document -> storePages(document, pages).thenReturn(document))
                            .doOnNext(document -> startProcessing(document, pages));
                });
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
     * recorded on the document by the pipeline itself, so nothing is lost when this subscription ends.
     */
    private void startProcessing(Document document, List<byte[]> pages) {
        extraction.process(document, pages)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        done -> log.info("[baymax] document finished documentId={} status={}",
                                done.id(), done.status()),
                        error -> log.error("[baymax] document processing ended in error documentId={}: {}",
                                document.id(), error.getMessage()));
    }

    @Override
    public Mono<DocumentView> view(UUID documentId) {
        return documents.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found",
                        "no document with id " + documentId)))
                .flatMap(document -> document.status() == Document.Status.DONE
                        ? withItems(document)
                        : Mono.just(DocumentView.pending(document)));
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
