package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentRecoveryUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.RecoveryReport;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * F9b: a deploy is {@code docker rm -f} (deploy.sh), and processing is a detached subscription, so whatever was in
 * flight is killed and would sit in RECEIVED/PROCESSING forever. At startup this re-runs those documents from their
 * stored pages — sequentially (1200 MB container), each attempt counted and committed before the work, and bounded:
 * after {@code max-attempts} the document is failed, so a document that kills the JVM cannot crash-loop the
 * container. Documents older than {@code max-age} are only reported, never touched. A document that finished but
 * died before {@code explain()} is explained if it was updated within {@code explain-max-age}; older ones are only
 * counted, so a first deploy cannot text a family about last week's paper.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRecoveryService implements DocumentRecoveryUseCase {

    private final DocumentRecordPort documents;
    private final DocumentStorageUseCase storage;
    private final DocumentProcessingChain chain;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public Flux<RecoveryReport> recoverInterrupted(Instant cutoff) {
        return Flux.concat(interrupted(cutoff), Flux.defer(() -> unexplained(cutoff)))
                .doOnNext(r -> log.info("[baymax] recovery documentId={} outcome={}", r.documentId(), r.outcome()));
    }

    /** RECEIVED or PROCESSING when the previous instance died: re-run, fail, or leave alone. */
    private Flux<RecoveryReport> interrupted(Instant cutoff) {
        Instant tooOld = clock.instant().minus(properties.getRecovery().getMaxAge());
        return Flux.concat(documents.idsWithStatus(Document.Status.PROCESSING), documents.idsWithStatus(Document.Status.RECEIVED))
                .concatMap(documents::find)                                    // sequential: 1200 MB container
                .filter(d -> d.updatedAt().isBefore(cutoff))                  // this instance owns everything newer
                .concatMap(d -> d.createdAt().isBefore(tooOld)
                        ? Mono.just(new RecoveryReport(d.id(), "skipped_too_old"))
                        : recover(d, cutoff).onErrorResume(e -> {
                            log.error("[baymax] recovery errored documentId={}: {}", d.id(), e.getClass().getSimpleName());
                            return Mono.just(new RecoveryReport(d.id(), "error"));
                        }));
    }

    /**
     * DONE or NEEDS_RETAKE with no message, or FAILED with no failure notice (PR 9): explained if recent, only counted
     * if older (a late text about an old paper helps nobody).
     */
    private Flux<RecoveryReport> unexplained(Instant cutoff) {
        Instant since = clock.instant().minus(properties.getRecovery().getExplainMaxAge());
        return documents.unexplainedSince(since, cutoff)
                .concatMap(documents::find)
                .concatMap(d -> chain.explainOnly(d).thenReturn(new RecoveryReport(d.id(), "explained"))
                        .onErrorResume(e -> {
                            log.error("[baymax] recovery explain errored documentId={}: {}", d.id(), e.getClass().getSimpleName());
                            return Mono.just(new RecoveryReport(d.id(), "error"));
                        }))
                .concatWith(documents.countUnexplainedBefore(since)
                        .doOnNext(n -> {
                            if (n > 0) {
                                log.warn("[baymax] recovery: {} finished document(s) older than {} were never explained", n,
                                        properties.getRecovery().getExplainMaxAge());
                            }
                        })
                        .thenMany(Flux.<RecoveryReport>empty()))
                // apart, and at INFO: every FAILED document from before V23 has no notice and never will, so this count
                // is non-zero on every startup; what an operator watches is whether it grows between restarts
                .concatWith(documents.countFailedUnnotifiedBefore(since)
                        .doOnNext(n -> {
                            if (n > 0) {
                                log.info("[baymax] recovery: {} FAILED document(s) older than {} have no failure notice", n,
                                        properties.getRecovery().getExplainMaxAge());
                            }
                        })
                        .thenMany(Flux.<RecoveryReport>empty()));
    }

    private Mono<RecoveryReport> recover(Document d, Instant cutoff) {
        return documents.markRecoveryAttempt(d.id(), cutoff)               // committed before the work
                .flatMap(attempt -> attempt > properties.getRecovery().getMaxAttempts()
                        ? chain.fail(d, "interrupted_repeatedly").thenReturn(new RecoveryReport(d.id(), "failed_interrupted_repeatedly"))
                        : storedPages(d)
                                .flatMap(pages -> storage.deleteCrops(d.familyId(), d.patientId(), d.id())
                                        .then(documents.deleteItems(d.id()))
                                        .then(chain.run(d, pages))
                                        .thenReturn(new RecoveryReport(d.id(), "reprocessed")))
                                .switchIfEmpty(Mono.defer(() -> chain.fail(d, "pages_missing_after_restart")
                                        .thenReturn(new RecoveryReport(d.id(), "failed_pages_missing")))));
    }

    /**
     * Every page or nothing: a partial page list would extract half a report as if it were whole. Completeness is
     * read from the LEDGER (DocumentStorageService.store writes the row only after the put succeeded), not from a
     * failed GET: the S3 adapter reports a missing key as an AWS NoSuchKeyException, which nothing above adapter.out
     * may import — and a transient S3 outage must stay an error (retried next restart, bounded by attempts), never
     * be misread as "pages missing" and fail the document for good.
     */
    private Mono<List<byte[]>> storedPages(Document d) {
        int expected = Math.max(1, d.pageCount());
        return storage.objectsOf(d.id())
                .filter(o -> o.kind() == ObjectKind.PAGE && o.pageNo() != null)
                .map(StoredObject::pageNo)
                .collect(Collectors.toSet())
                .flatMap(stored -> IntStream.rangeClosed(1, expected).allMatch(stored::contains)
                        ? Flux.range(1, expected)
                                .concatMap(n -> storage.pageBytes(d.familyId(), d.patientId(), d.id(), n))
                                .collectList()
                        : Mono.empty());
    }

    // No private fail(): every FAILED written here goes through DocumentProcessingChain.fail, which PR 9's notice meets.
}
