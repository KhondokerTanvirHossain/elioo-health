package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.RecoveryReport;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRecoveryServiceTest {

    private static final Instant BOOT = Instant.parse("2026-10-17T06:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentProcessingChain chain = mock(DocumentProcessingChain.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private DocumentRecoveryService service;

    @BeforeEach
    void wire() {
        service = new DocumentRecoveryService(documents, storage, chain, properties,
                Clock.fixed(BOOT.plusSeconds(30), ZoneOffset.UTC));
        when(documents.idsWithStatus(any())).thenReturn(Flux.empty());
        when(documents.update(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(documents.deleteItems(any())).thenReturn(Mono.just(0L));
        when(storage.deleteCrops(any(), any(), any())).thenReturn(Mono.just(new DeletionReport("p/", 0, 0)));
        when(chain.fail(any(), anyString())).thenAnswer(i -> Mono.just(((Document) i.getArgument(0))
                .withStatus(Document.Status.FAILED, i.getArgument(1), BOOT)));

        when(documents.unexplainedSince(any(), any())).thenReturn(Flux.empty());
        when(documents.countUnexplainedBefore(any())).thenReturn(Mono.just(0L));
    }

    private Document stuck(Document.Status status, Instant updatedAt, int pages) {
        UUID id = UUID.randomUUID();
        Document d = new Document(id, PATIENT, FAMILY, null, null, null, null, null, status, null, null, null,
                pages, updatedAt.minusSeconds(5), updatedAt);
        when(documents.find(id)).thenReturn(Mono.just(d));
        return d;
    }

    private static StoredObject page(UUID documentId, int n) {
        return new StoredObject(UUID.randomUUID(), FAMILY, PATIENT, documentId, ObjectKind.PAGE, n, null,
                "k" + n, "image/jpeg", 4, BOOT);
    }

    /** The other direction of the pages rule: an S3 outage is an error to retry, never "pages missing" for good. */
    @Test
    void aTransientStorageFailureIsAnErrorNotAPermanentFailure() {
        Document d = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(1));
        when(storage.objectsOf(d.id())).thenReturn(Flux.just(page(d.id(), 1)));
        when(storage.pageBytes(FAMILY, PATIENT, d.id(), 1)).thenReturn(Mono.error(new RuntimeException("503 SlowDown")));

        StepVerifier.create(service.recoverInterrupted(BOOT).map(RecoveryReport::outcome))
                .expectNext("error").verifyComplete();
        verify(chain, never()).fail(any(), anyString());
    }

    @Test
    void aDocumentInterruptedBeforeThisBootIsReRunFromItsStoredPagesAfterItsLeftoverCropsAreCleared() {
        Document d = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(1));
        when(storage.objectsOf(d.id())).thenReturn(Flux.just(page(d.id(), 1)));
        when(storage.pageBytes(FAMILY, PATIENT, d.id(), 1)).thenReturn(Mono.just(JPEG));
        when(chain.run(any(), any())).thenReturn(Mono.just(d.withStatus(Document.Status.DONE, null, BOOT.plusSeconds(60))));

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("reprocessed"))
                .verifyComplete();

        InOrder order = inOrder(documents, storage, chain);
        order.verify(documents).markRecoveryAttempt(d.id(), BOOT);          // committed before any work
        order.verify(storage).deleteCrops(FAMILY, PATIENT, d.id());        // or the re-run collides with its own crop-v1
        order.verify(documents).deleteItems(d.id());
        order.verify(chain).run(eq(d), argThat(pages -> pages.size() == 1 && Arrays.equals(pages.get(0), JPEG)));
    }

    @Test
    void aDocumentThisInstanceIsAlreadyProcessingIsNeverTouched() {
        Document mine = stuck(Document.Status.PROCESSING, BOOT.plusSeconds(1), 1);   // updated after this bean was built
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(mine.id()));

        StepVerifier.create(service.recoverInterrupted(BOOT)).verifyComplete();

        verify(documents, never()).markRecoveryAttempt(any(), any());
        verify(chain, never()).run(any(), any());
        verify(storage, never()).deleteCrops(any(), any(), any());
    }

    @Test
    void aDocumentInterruptedTooOftenIsFailedNotRetried() {
        Document d = stuck(Document.Status.RECEIVED, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.RECEIVED)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(3));   // max-attempts is 2

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("failed_interrupted_repeatedly"))
                .verifyComplete();

        verify(chain).fail(d, "interrupted_repeatedly");   // the one FAILED writer outside extraction (PR 9 answers it)
        verify(chain, never()).run(any(), any());
    }

    /** The boundary of the attempt limit: the max-attempts-th re-run still runs; only the one after it fails. */
    @Test
    void theLastAllowedAttemptIsStillReRun() {
        Document d = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT))
                .thenReturn(Mono.just(properties.getRecovery().getMaxAttempts()));   // 2 of 2
        when(storage.objectsOf(d.id())).thenReturn(Flux.just(page(d.id(), 1)));
        when(storage.pageBytes(FAMILY, PATIENT, d.id(), 1)).thenReturn(Mono.just(JPEG));
        when(chain.run(any(), any())).thenReturn(Mono.just(d.withStatus(Document.Status.DONE, null, BOOT.plusSeconds(60))));

        StepVerifier.create(service.recoverInterrupted(BOOT).map(RecoveryReport::outcome))
                .expectNext("reprocessed").verifyComplete();
        verify(chain, never()).fail(any(), anyString());
    }

    @Test
    void aDocumentWhosePagesWereNeverAllStoredIsFailedWithAReason() {
        Document d = stuck(Document.Status.RECEIVED, BOOT.minusSeconds(40), 2);
        when(documents.idsWithStatus(Document.Status.RECEIVED)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(1));
        when(storage.objectsOf(d.id())).thenReturn(Flux.just(page(d.id(), 1)));   // killed between page 1 and page 2

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("failed_pages_missing"))
                .verifyComplete();
        verify(chain).fail(d, "pages_missing_after_restart");
        verify(chain, never()).run(any(), any());
    }

    @Test
    void oneDocumentErroringDoesNotStopTheSweep() {
        Document a = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(50), 1);
        Document b = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(a.id(), b.id()));
        when(documents.markRecoveryAttempt(any(), eq(BOOT))).thenReturn(Mono.just(1));
        when(storage.objectsOf(any())).thenAnswer(i -> Flux.just(page(i.getArgument(0), 1)));
        when(storage.pageBytes(any(), any(), any(), anyInt())).thenReturn(Mono.just(JPEG));
        when(chain.run(eq(a), any())).thenReturn(Mono.error(new IllegalStateException("boom")));
        when(chain.run(eq(b), any())).thenReturn(Mono.just(b.withStatus(Document.Status.DONE, null, BOOT)));

        StepVerifier.create(service.recoverInterrupted(BOOT).map(RecoveryReport::outcome))
                .expectNext("error", "reprocessed").verifyComplete();
    }

    @Test
    void aDocumentOlderThanTheMaxAgeIsLeftAloneAndReported() {
        properties.getRecovery().setMaxAge(Duration.ofDays(7));
        Document old = stuck(Document.Status.PROCESSING, BOOT.minus(Duration.ofDays(30)), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(old.id()));

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("skipped_too_old"))
                .verifyComplete();
        verify(documents, never()).markRecoveryAttempt(any(), any());
        verify(documents, never()).update(any());
        verify(chain, never()).fail(any(), anyString());   // a paper from weeks ago never messages the family
    }

    /** Died after DONE, before explain(): no message row exists. Recent → explained once through the chain. */
    @Test
    void aRecentDocumentThatFinishedButWasNeverExplainedIsExplainedOnce() {
        Document d = stuck(Document.Status.DONE, BOOT.minusSeconds(600), 1);
        when(documents.unexplainedSince(BOOT.plusSeconds(30).minus(properties.getRecovery().getExplainMaxAge()), BOOT))
                .thenReturn(Flux.just(d.id()));
        when(chain.explainOnly(d)).thenReturn(Mono.just(d));

        StepVerifier.create(service.recoverInterrupted(BOOT).map(RecoveryReport::outcome))
                .expectNext("explained").verifyComplete();
        verify(chain, times(1)).explainOnly(d);
        verify(chain, never()).run(any(), any());
    }

    /**
     * The other direction: an unexplained document older than the window is only counted, never messaged. That nothing
     * is explained here is guaranteed by the stub ({@code unexplainedSince} is empty); which documents fall outside the
     * window is the SQL's job and is asserted on real Postgres in {@code DocumentRecoveryAcceptanceTest}. What this
     * service owns is that the count is not swallowed: the WARN line carries it.
     */
    @Test
    void anOldUnexplainedDocumentIsCountedNotMessaged() {
        Instant since = BOOT.plusSeconds(30).minus(properties.getRecovery().getExplainMaxAge());
        when(documents.unexplainedSince(any(), any())).thenReturn(Flux.empty());
        when(documents.countUnexplainedBefore(since)).thenReturn(Mono.just(3L));
        Logger logger = (Logger) LoggerFactory.getLogger(DocumentRecoveryService.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            StepVerifier.create(service.recoverInterrupted(BOOT)).verifyComplete();
        } finally {
            logger.detachAppender(logged);
        }

        verify(documents).countUnexplainedBefore(since);
        verify(chain, never()).explainOnly(any());
        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .as("the never-explained count, at WARN")
                .containsExactly("[baymax] recovery: 3 finished document(s) older than PT6H were never explained");
    }
}
