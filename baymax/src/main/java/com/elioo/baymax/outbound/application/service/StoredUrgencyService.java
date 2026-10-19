package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * F1: the writer of a document's stored urgency. It assesses a DONE document from its persisted facts with
 * {@link UrgencyService} and stores max(stored, assessed) through {@link DocumentUrgencyPort#raise}; the database's
 * trigger rejects any lowering whoever writes. A11 makes this the only caller of both (ArchUnit).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoredUrgencyService implements DocumentUrgencyUseCase {

    private final UrgencyService urgency;
    private final DocumentUrgencyPort stored;
    private final DocumentRecordPort documents;
    private final HealthRecordPort records;
    private final BaymaxProperties properties;
    private final Clock clock;

    /** The writer. A11 makes this the only caller of UrgencyService.assess and of DocumentUrgencyPort.raise. */
    Mono<StoredUrgency> assessAndStore(DocumentFacts facts) {
        UrgencyAssessment assessed = urgency.assess(facts);
        return stored.raise(facts.document().id(), assessed, clock.instant())
                .switchIfEmpty(Mono.error(new IllegalStateException("no document row for urgency id=" + facts.document().id())))
                // reasons carry marker names: their count only, never the reasons themselves
                .doOnNext(s -> log.info("[baymax] urgency stored documentId={} assessed={} stored={} reasons={}",
                        s.documentId(), assessed.level().dbValue(), s.level().dbValue(), s.reasons().size()));
    }

    @Override
    public Mono<StoredUrgency> assessAndStore(UUID documentId) {
        return documents.find(documentId).filter(d -> d.status() == Document.Status.DONE)
                .flatMap(this::facts).flatMap(this::assessAndStore);
    }

    @Override
    public Mono<StoredUrgency> current(UUID documentId) {
        return stored.find(documentId);
    }

    @Override
    public Mono<StoredUrgency> currentOrAssess(UUID documentId) {
        return stored.find(documentId).switchIfEmpty(Mono.defer(() -> assessAndStore(documentId)));
    }

    /** ExplanationService.facts, copied verbatim; A11 points ExplanationService here and deletes its copy. */
    Mono<DocumentFacts> facts(Document document) {
        String link = properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + document.id();
        return Mono.zip(
                documents.observationsOf(document.id()).collectList(),
                documents.followUpsOf(document.id()).collectList(),
                documents.clinicalContextOf(document.id()).defaultIfEmpty(Map.of()),
                records.findPatient(document.patientId()).map(p -> p.name()).defaultIfEmpty("")
        ).map(t -> new DocumentFacts(document, t.getT4(), t.getT1(), t.getT2(), t.getT3(), link));
    }
}
