package com.elioo.baymax.extraction;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.application.service.DocumentProcessingChain;
import com.elioo.baymax.extraction.application.service.DocumentRecoveryService;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.RecoveryReport;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.outbound.InMemoryOutboundMessages;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.ReviewerNotificationPort;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.application.service.ExplanationService;
import com.elioo.baymax.outbound.application.service.InMemoryDocumentUrgency;
import com.elioo.baymax.outbound.application.service.MessageSafetyCheck;
import com.elioo.baymax.outbound.application.service.OutboundMessageGate;
import com.elioo.baymax.outbound.application.service.StoredUrgencyService;
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.healthcare.llm.api.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F9b meets PR 9 end to end: the REAL DocumentRecoveryService, the REAL DocumentProcessingChain and the REAL
 * ExplanationService, so a document a restart interrupted too often is failed through the chain and the family is
 * told once — not failed in silence. Both directions: a paper older than {@code max-age} is never touched, so it never
 * texts the family about a paper from weeks ago. Rows live in {@link InMemoryOutboundMessages} (no constraint is under
 * test here; V23's CHECK is exercised on Postgres in {@code FailureKindAcceptanceTest}).
 */
class RecoveredFailureIsToldOnceTest {

    private static final Instant BOOT = Instant.parse("2026-10-17T06:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID(), PATIENT = UUID.randomUUID();

    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final InMemoryOutboundMessages messages = new InMemoryOutboundMessages();
    private final MessageDeliveryPort delivery = mock(MessageDeliveryPort.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    /** the document table as the mocked port holds it: update() stores what find() then returns */
    private final Map<UUID, Document> table = new ConcurrentHashMap<>();
    private DocumentRecoveryService recovery;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(BOOT.plusSeconds(30), ZoneOffset.UTC);
        LlmClient model = mock(LlmClient.class);
        StoredUrgencyService storedUrgency = new StoredUrgencyService(new UrgencyService(properties, clock),
                new InMemoryDocumentUrgency(), documents, records, properties, clock);
        ExplanationService explainer = new ExplanationService(documents, records, messages, mock(MeteredLlmClient.class),
                new ExtractionModelConfiguration.ExtractionClients(model, model, model, true),
                new MessageSafetyCheck(properties), new OutboundMessageGate(properties),
                mock(ReviewerNotificationPort.class), delivery, new Copy(), properties, clock, storedUrgency);
        NudgeUseCase nudges = mock(NudgeUseCase.class);
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
        DocumentProcessingChain chain = new DocumentProcessingChain(mock(DocumentExtractionService.class), explainer, nudges,
                documents, clock, storedUrgency, storage);
        recovery = new DocumentRecoveryService(documents, storage, chain, properties, clock);

        when(documents.find(any(UUID.class))).thenAnswer(i -> Mono.justOrEmpty(table.get(i.<UUID>getArgument(0))));
        when(documents.update(any())).thenAnswer(i -> {
            Document d = i.getArgument(0);
            table.put(d.id(), d);
            return Mono.just(d);
        });
        when(documents.idsWithStatus(any())).thenAnswer(i -> Flux.fromIterable(table.values())
                .filter(d -> d.status() == i.getArgument(0)).map(Document::id));
        when(documents.unexplainedSince(any(), any())).thenReturn(Flux.empty());
        when(documents.countUnexplainedBefore(any())).thenReturn(Mono.just(0L));
        when(delivery.deliver(any())).thenReturn(Mono.just(DeliveryOutcome.logged()));
    }

    private Document processing(Instant createdAt) {
        Document d = new Document(UUID.randomUUID(), PATIENT, FAMILY, null, null, null, null, null,
                Document.Status.PROCESSING, null, null, null, 1, createdAt, createdAt.plusSeconds(1));
        table.put(d.id(), d);
        return d;
    }

    private List<OutboundMessage> failureRows() {
        return messages.rows().stream().filter(m -> m.kind() == OutboundMessage.Kind.FAILURE).toList();
    }

    @Test
    void aDocumentInterruptedTooOftenIsFailedAndTheFamilyIsToldExactlyOnce() {
        Document d = processing(BOOT.minus(Duration.ofMinutes(10)));
        // the third attempt, over max-attempts (2): DocumentRecoveryService fails it through the chain
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(3));

        List<String> outcomes = recovery.recoverInterrupted(BOOT).map(RecoveryReport::outcome).collectList().block();

        assertThat(outcomes).containsExactly("failed_interrupted_repeatedly");
        assertThat(table.get(d.id()).status()).isEqualTo(Document.Status.FAILED);
        assertThat(failureRows()).as("the interrupted document must not be failed in silence").hasSize(1);
        assertThat(failureRows().get(0).body()).isEqualTo(new Copy().bn("failure.document", Map.of()));
        assertThat(failureRows().get(0).documentId()).isEqualTo(d.id());
        assertThat(messages.rows()).as("the notice and nothing else").hasSize(1);
        verify(delivery, times(1)).deliver(any());
    }

    @Test
    void aDocumentOlderThanTheRecoveryWindowIsNeverTouchedAndNeverTextsTheFamily() {
        Document d = processing(BOOT.minus(properties.getRecovery().getMaxAge()).minus(Duration.ofDays(1)));

        List<String> outcomes = recovery.recoverInterrupted(BOOT).map(RecoveryReport::outcome).collectList().block();

        assertThat(outcomes).containsExactly("skipped_too_old");
        assertThat(table.get(d.id()).status()).as("left as it was").isEqualTo(Document.Status.PROCESSING);
        assertThat(messages.rows()).isEmpty();
        verify(delivery, never()).deliver(any());
        verify(documents, never()).markRecoveryAttempt(any(), any());
    }
}
