package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProcessingChainTest {

    private static final Instant T = Instant.parse("2026-10-17T06:00:00Z");
    private final DocumentExtractionService extraction = mock(DocumentExtractionService.class);
    private final ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
    private final NudgeUseCase nudges = mock(NudgeUseCase.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final DocumentUrgencyUseCase urgency = mock(DocumentUrgencyUseCase.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentProcessingChain chain =
            new DocumentProcessingChain(extraction, explainer, nudges, documents, Clock.fixed(T, ZoneOffset.UTC), urgency, storage);

    private static final Document DONE_DOC = received().withStatus(Document.Status.DONE, null, T);
    private static final Document RETAKE_DOC = received().withStatus(Document.Status.NEEDS_RETAKE, "blurred", T);

    private static Document received() {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null,
                Document.Status.RECEIVED, null, null, null, 1, T, T);
    }

    @BeforeEach
    void wire() {
        when(explainer.explain(any())).thenReturn(Mono.empty());
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
        when(documents.update(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(urgency.assessAndStore(any())).thenReturn(Mono.empty());
        // every paper below still exists when its extraction ends, unless a test deletes it
        when(documents.find(any())).thenAnswer(i -> Mono.just(received().withId(i.getArgument(0))));
        when(documents.deleteDocument(any())).thenReturn(Mono.just(0L));
        when(storage.objectsOf(any())).thenReturn(Flux.empty());
        when(storage.deleteDocumentObjects(any(), any(), any(), anyLong())).thenReturn(Mono.just(new DeletionReport("p/", 0, 0)));
    }

    /**
     * Review Focus 4, one direction: the family deleted the paper while the model read it. Whatever the extraction ended
     * in, the chain removes what the pipeline wrote after the delete — the rows again, then the objects — and goes no
     * further: no urgency, no explanation, no nudge.
     */
    @Test
    void aPaperDeletedWhileBeingReadIsSweptAgainAndNeverExplained() {
        Document d = received();
        when(extraction.process(eq(d), any())).thenReturn(Mono.just(d.withStatus(Document.Status.DONE, null, T)));
        when(documents.find(d.id())).thenReturn(Mono.empty());

        StepVerifier.create(chain.run(d, List.of(new byte[]{1}))).verifyComplete();

        InOrder order = inOrder(documents, storage);
        order.verify(documents).deleteDocument(d.id());
        order.verify(storage).deleteDocumentObjects(d.familyId(), d.patientId(), d.id(), 0L);
        verify(urgency, never()).assessAndStore(any());
        verify(explainer, never()).explain(any());
        verify(nudges, never()).onDocumentDone(any());
    }

    /** The same when the extraction itself errored on the vanished row: the error is the deletion's, not an incident. */
    @Test
    void anExtractionErrorOnADeletedPaperIsTheDeletionAndIsSweptToo() {
        Document d = received();
        when(extraction.process(eq(d), any())).thenReturn(Mono.error(new IllegalStateException("row gone")));
        when(documents.find(d.id())).thenReturn(Mono.empty());

        StepVerifier.create(chain.run(d, List.of(new byte[]{1}))).verifyComplete();

        verify(storage).deleteDocumentObjects(d.familyId(), d.patientId(), d.id(), 0L);
        verify(explainer, never()).explain(any());
    }

    /**
     * The other direction: an extraction that answered nothing (or errored) for a paper that still exists is not a
     * deletion. Its rows and its pages are never touched, and an error still reaches the caller.
     */
    @Test
    void anExtractionThatEndsForAPaperStillThereNeverTouchesItsRowsOrPages() {
        Document empty = received();
        when(extraction.process(eq(empty), any())).thenReturn(Mono.empty());
        StepVerifier.create(chain.run(empty, List.of(new byte[]{1}))).verifyComplete();

        Document failing = received();
        when(extraction.process(eq(failing), any())).thenReturn(Mono.error(new IllegalStateException("boom")));
        StepVerifier.create(chain.run(failing, List.of(new byte[]{1}))).verifyError(IllegalStateException.class);

        verify(documents, never()).deleteDocument(any());
        verify(storage, never()).deleteDocumentObjects(any(), any(), any(), anyLong());
        verify(storage, never()).deleteDocument(any(), any(), any());
    }

    /** F1: a DONE document is assessed and stored before explain runs, so an explanation that throws leaves it assessed. */
    @Test
    void urgencyIsStoredBeforeExplainEvenWhenTheExplanationThrows() {
        when(extraction.process(any(), any())).thenReturn(Mono.just(DONE_DOC));
        when(urgency.assessAndStore(DONE_DOC.id())).thenReturn(Mono.just(
                new StoredUrgency(DONE_DOC.id(), Urgency.THIS_WEEK, List.of("diagnosis_present"), T)));
        when(explainer.explain(DONE_DOC.id())).thenReturn(Mono.error(new IllegalStateException("medicine transcription is not verbatim")));

        StepVerifier.create(chain.run(DONE_DOC, List.of(new byte[]{1}))).expectNext(DONE_DOC).verifyComplete();

        InOrder order = inOrder(urgency, explainer);
        order.verify(urgency).assessAndStore(DONE_DOC.id());
        order.verify(explainer).explain(DONE_DOC.id());
    }

    /** The other path into the chain: a recovered document that finished but was never explained (F9b) is assessed too. */
    @Test
    void aRecoveredFinishedDocumentIsAssessedOnTheSamePath() {
        StepVerifier.create(chain.explainOnly(DONE_DOC)).expectNext(DONE_DOC).verifyComplete();
        verify(urgency).assessAndStore(DONE_DOC.id());
    }

    /** A failing store is logged by class and never undoes the document; explain and the nudges still run. */
    @Test
    void aFailingStoreNeverUndoesTheDocumentAndExplainStillRuns() {
        when(urgency.assessAndStore(DONE_DOC.id())).thenReturn(Mono.error(new IllegalStateException("db down")));
        StepVerifier.create(chain.explainOnly(DONE_DOC)).expectNext(DONE_DOC).verifyComplete();
        verify(urgency).assessAndStore(DONE_DOC.id());
        verify(explainer).explain(DONE_DOC.id());
        verify(nudges).onDocumentDone(DONE_DOC.id());
    }

    /** A NEEDS_RETAKE persisted nothing to assess: no urgency is stored for it, and none is invented. */
    @Test
    void aRetakeIsNotAssessed() {
        when(extraction.process(any(), any())).thenReturn(Mono.just(RETAKE_DOC));
        StepVerifier.create(chain.run(RETAKE_DOC, List.of(new byte[]{1}))).expectNext(RETAKE_DOC).verifyComplete();
        verify(explainer).explain(RETAKE_DOC.id());
        verify(urgency, never()).assessAndStore(any());
    }

    /** Today's behaviour, now in one place: whatever extraction ends in, the explainer and the nudges see it. */
    @Test
    void anExtractionThatEndsFailedStillReachesTheExplainer() {
        Document d = received();
        when(extraction.process(eq(d), any())).thenReturn(Mono.just(d.withStatus(Document.Status.FAILED, "llm_error", T)));

        StepVerifier.create(chain.run(d, List.of(new byte[]{1}))).assertNext(r -> assertThat(r.status())
                .isEqualTo(Document.Status.FAILED)).verifyComplete();
        verify(explainer).explain(d.id());
    }

    /** The one writer of FAILED outside extraction: it records the reason, then meets the same explainer. */
    @Test
    void failRecordsTheReasonThenGoesThroughTheExplainer() {
        Document d = received();

        StepVerifier.create(chain.fail(d, "interrupted_repeatedly")).assertNext(r -> {
            assertThat(r.status()).isEqualTo(Document.Status.FAILED);
            assertThat(r.statusReason()).isEqualTo("interrupted_repeatedly");
        }).verifyComplete();
        InOrder order = inOrder(documents, explainer);
        order.verify(documents).update(argThat(u -> u.status() == Document.Status.FAILED));
        order.verify(explainer).explain(d.id());
    }

    /** An explainer error never undoes the extraction, and the log carries the class only (PR 1). */
    @Test
    void anExplainerErrorIsSwallowedAndTheDocumentStillFinishes() {
        Document d = received();
        when(extraction.process(eq(d), any())).thenReturn(Mono.just(d.withStatus(Document.Status.DONE, null, T)));
        when(explainer.explain(d.id())).thenReturn(Mono.error(new IllegalStateException("Cap. DDR ১+০+০")));

        StepVerifier.create(chain.run(d, List.of(new byte[]{1}))).expectNextCount(1).verifyComplete();
        verify(nudges).onDocumentDone(d.id());
    }
}
