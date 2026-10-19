package com.elioo.baymax.outbound.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F1: the one writer of a document's stored urgency, over a fake port with the adapter's merge semantics. */
class StoredUrgencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final InMemoryDocumentUrgency stored = new InMemoryDocumentUrgency();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final StoredUrgencyService service =
            new StoredUrgencyService(new UrgencyService(properties, clock), stored, documents, records, properties, clock);

    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(StoredUrgencyService.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logger.setLevel(null);
    }

    private Document document(Document.Status status) {
        Document d = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "lab_report", null, null, "{}",
                0.9, status, null, "m", null, 1, NOW, NOW);
        when(documents.find(d.id())).thenReturn(Mono.just(d));
        when(documents.observationsOf(d.id())).thenReturn(Flux.just(
                Map.of("name", "Potassium", "value", "11.0", "unit", "mmol/L", "ref_low", "3.5", "ref_high", "5.1", "crop_key", "k")));
        when(documents.followUpsOf(d.id())).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(d.id())).thenReturn(Mono.empty());
        when(records.findPatient(d.patientId())).thenReturn(Mono.empty());
        return d;
    }

    @Test
    void aDoneDocumentWithACriticalValueIsStoredNowWithItsReason() {
        Document d = document(Document.Status.DONE);

        StepVerifier.create(service.assessAndStore(d.id())).assertNext(s -> {
            assertThat(s.level()).isEqualTo(Urgency.NOW);
            assertThat(s.reasons()).containsExactly("value_critical:potassium|stopgap");
            assertThat(s.assessedAt()).isEqualTo(NOW);
        }).verifyComplete();
        assertThat(stored.rows.get(d.id()).level()).isEqualTo(Urgency.NOW);
    }

    @Test
    void aDocumentStillBeingReadIsNotAssessedAndNothingIsStored() {
        Document d = document(Document.Status.PROCESSING);

        StepVerifier.create(service.assessAndStore(d.id())).verifyComplete();
        assertThat(stored.rows).isEmpty();
        verify(documents, never()).observationsOf(d.id());
    }

    /** A page read twice writes once: the second read finds the level stored and assesses nothing. */
    @Test
    void currentOrAssessAssessesAnUnassessedDocumentOnceAndThenOnlyReads() {
        Document d = document(Document.Status.DONE);

        StepVerifier.create(service.currentOrAssess(d.id())).assertNext(s -> assertThat(s.level()).isEqualTo(Urgency.NOW))
                .verifyComplete();
        StepVerifier.create(service.currentOrAssess(d.id())).assertNext(s -> assertThat(s.level()).isEqualTo(Urgency.NOW))
                .verifyComplete();

        verify(documents, times(1)).observationsOf(d.id());
    }

    /** current() never assesses: an unassessed document reads empty, never a ROUTINE it was not given. */
    @Test
    void currentOfAnUnassessedDocumentIsEmptyAndAssessesNothing() {
        Document d = document(Document.Status.DONE);

        StepVerifier.create(service.current(d.id())).verifyComplete();
        verify(documents, never()).observationsOf(d.id());
    }

    /** The INFO line carries ids, levels and a count; the reasons hold marker names, so only their count is logged. */
    @Test
    void theStoreLineCarriesLevelsAndACountNeverAMarkerOrAValue() {
        Document d = document(Document.Status.DONE);

        service.assessAndStore(d.id()).block();

        List<String> lines = logged.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(l -> l.startsWith("[baymax] urgency stored documentId="));
        String line = lines.stream().filter(l -> l.startsWith("[baymax] urgency stored documentId=")).findFirst().orElseThrow();
        assertThat(line).contains("documentId=" + d.id()).contains("assessed=now").contains("stored=now").contains("reasons=1");
        assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContainIgnoringCase("potassium").doesNotContain("11.0"));
    }

    /**
     * What the service owns when a later assessment is lower (the merge itself is the port's, on Postgres in
     * DocumentUrgencyNeverLowersTest): it hands the port the fresh assessment as assessed, neither dropped nor
     * pre-merged, and returns what the port stored, never its own lower reading.
     */
    @Test
    void aLowerAssessmentIsHandedToThePortAsAssessedAndTheStoredLevelIsReturned() {
        Document d = document(Document.Status.DONE);
        service.assessAndStore(d.id()).block();
        when(documents.observationsOf(d.id())).thenReturn(Flux.empty());

        StoredUrgency after = service.assessAndStore(d.id()).block();

        assertThat(stored.raised).hasSize(2);
        assertThat(stored.raised.get(1).level()).as("the second assessment reaches the port as assessed").isEqualTo(Urgency.ROUTINE);
        assertThat(stored.raised.get(1).reasons()).isEmpty();
        assertThat(after.level()).as("the service returns the stored level, not its own").isEqualTo(Urgency.NOW);
        assertThat(after.reasons()).containsExactly("value_critical:potassium|stopgap");
        assertThat(logged.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.contains("documentId=" + d.id()) && l.contains("assessed=routine") && l.contains("stored=now"));
    }
}
