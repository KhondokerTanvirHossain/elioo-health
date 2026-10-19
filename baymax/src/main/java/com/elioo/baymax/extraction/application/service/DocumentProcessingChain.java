package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;

/**
 * The one processing chain (F9b): extract, then store the urgency (F1), then explain, then the nudge check. Fresh uploads and documents a
 * restart interrupted both run through here, so they cannot drift apart.
 *
 * <p>Every FAILED written outside {@link DocumentExtractionService} is written here ({@link #fail}), so whatever
 * answers a FAILED document (PR 9) is reached by all of them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessingChain {

    private final DocumentExtractionService extraction;
    private final ExplainDocumentUseCase explainer;
    private final NudgeUseCase nudges;
    private final DocumentRecordPort documents;
    private final Clock clock;
    private final DocumentUrgencyUseCase urgency;

    /**
     * Extract → explain → nudges. Not subscribed and not scheduled: the caller decides where it runs. Extraction
     * never signals an error (it records one), and the explain and nudge steps swallow theirs, so a finished
     * extraction is never undone by what follows it.
     */
    public Mono<Document> run(Document document, List<byte[]> pages) {
        return extraction.process(document, pages)
                .flatMap(this::explainOnly);
    }

    /**
     * Records {@code reason} as FAILED on the document, then meets the same explainer as every other outcome.
     * The reason is developer-facing and never patient text (V5).
     */
    public Mono<Document> fail(Document document, String reason) {
        return documents.update(document.withStatus(Document.Status.FAILED, reason, clock.instant()))
                .flatMap(this::explain);
    }

    /**
     * The urgency → explain → nudges half of {@link #run}, for a document whose extraction already finished. Fresh
     * uploads ({@link #run}) and recovered documents (F9b) both come through here.
     */
    public Mono<Document> explainOnly(Document done) {
        return storeUrgency(done).flatMap(this::explain).flatMap(this::nudge);
    }

    // F1: assessed and stored before explain, on the path both fresh uploads (run) and recovery (explainOnly) take, so a
    // throwing explanation cannot leave a DONE document unassessed. A failure is logged by class name and never undoes
    // the extraction (the same contract as explain and nudge).
    private Mono<Document> storeUrgency(Document done) {
        if (done.status() != Document.Status.DONE) {
            return Mono.just(done);
        }
        return urgency.assessAndStore(done.id())
                .onErrorResume(e -> {
                    log.error("[baymax] urgency store failed documentId={} error={}", done.id(), e.getClass().getSimpleName());
                    return Mono.empty();
                })
                .thenReturn(done);
    }

    // BMX-6: once the document is DONE or NEEDS_RETAKE, compose the family's message; a failure there is logged and
    // never undoes the extraction
    private Mono<Document> explain(Document done) {
        return explainer.explain(done.id())
                .doOnNext(m -> log.info("[baymax] message composed documentId={} kind={} urgency={} gate={}",
                        done.id(), m.kind(), m.urgency(), m.gateStatus()))
                .onErrorResume(e -> {
                    // the message can carry medicine text (MedicineTranscription.elided): class at ERROR,
                    // detail at DEBUG only
                    log.error("[baymax] explanation failed documentId={} error={}", done.id(),
                            e.getClass().getSimpleName());
                    log.debug("[baymax] explanation failure detail documentId={}: {}", done.id(), e.getMessage());
                    return Mono.empty();
                })
                .thenReturn(done);
    }

    // BMX-8: the medicine_changed rule looks at this prescription against the previous one; a failure is logged and
    // never undoes anything. chronic_flags are never touched from here (asserted).
    private Mono<Document> nudge(Document done) {
        return nudges.onDocumentDone(done.id())
                .onErrorResume(e -> {
                    log.error("[baymax] nudge check failed documentId={} error={}", done.id(),
                            e.getClass().getSimpleName());
                    return Mono.empty();
                })
                .thenReturn(done);
    }
}
