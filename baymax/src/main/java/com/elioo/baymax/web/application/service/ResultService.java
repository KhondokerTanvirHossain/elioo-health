package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The document page's read side (A9: the status and the reading view; A12: the result). Every read starts from the
 * session's family and reaches the document only through {@code findVisible}, which decides access in SQL.
 */
@Service
@RequiredArgsConstructor
public class ResultService implements ResultUseCase {

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final DocumentIntakeUseCase intake;
    private final AuditPort audit;
    private final Clock clock;
    private final OutboundMessagePort messages;
    private final DocumentUrgencyUseCase urgencies;
    private final BaymaxProperties properties;

    @Override
    public Mono<Document.Status> status(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).map(Document::status);
    }

    @Override
    public Mono<ReadingView> reading(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).flatMap(d -> Mono.zip(
                        records.visiblePatient(familyId, d.patientId())
                                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + d.patientId()))),
                        viewer(familyId),
                        audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                                .then(intake.view(documentId)))
                .map(t -> new ReadingView(t.getT3(), t.getT1().patient(), t.getT1().owner(), t.getT2(), d.createdAt(), clock.instant())));
    }

    @Override
    public Mono<ResultView> result(UUID familyId, UUID documentId) {
        // the stored level is read (or, for an unassessed DONE document, assessed once) only after visibility is decided
        return visibleDocument(familyId, documentId).flatMap(d -> Mono.zip(
                        records.visiblePatient(familyId, d.patientId())
                                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + d.patientId()))),
                        viewer(familyId),
                        audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                                .then(intake.view(documentId)),
                        stored(d), gate(d))
                .map(t -> new ResultView(t.getT3(), t.getT1().patient(), t.getT1().owner(), t.getT2(),
                        t.getT4().map(StoredUrgency::level).orElse(null), t.getT4().map(StoredUrgency::reasons).orElse(List.of()),
                        t.getT5().state(), t.getT5().wording(), 0, properties.getOutbound().getReviewTypicalHours(),
                        d.createdAt(), clock.instant())));
    }

    /**
     * F1: the stored level, read through the sole writer (DocumentUrgencyUseCase.currentOrAssess), which assesses an
     * unassessed DONE document once; never computed here (OneUrgencyWriterTest). Nothing before DONE.
     */
    private Mono<Optional<StoredUrgency>> stored(Document d) {
        return d.status() != Document.Status.DONE ? Mono.just(Optional.empty())
                : urgencies.currentOrAssess(d.id()).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** What the newest explanation's review state lets the page say. Wording only when deliverable (F3). */
    private record Gate(GateState state, String wording) {
        static final Gate NONE = new Gate(GateState.NONE, null);

        static Gate of(OutboundMessage m) {
            return switch (m.gateStatus()) {
                // a deliverable status with no body is a safety failure that closed: nothing to say, nothing promised
                case RELEASED, APPROVED -> m.body() == null ? new Gate(GateState.FAILED_SAFETY, null) : new Gate(GateState.RELEASED, m.body());
                case PENDING -> new Gate(GateState.PENDING, null);
                case REJECTED -> new Gate(GateState.REJECTED, null);
                case FAILED_SAFETY -> new Gate(GateState.FAILED_SAFETY, null);
            };
        }
    }

    /** F3 reads the newest EXPLANATION whatever its state (latestOfKind), not latestDeliverable, which hides PENDING. */
    private Mono<Gate> gate(Document d) {
        return d.status() != Document.Status.DONE ? Mono.just(Gate.NONE)
                : messages.latestOfKind(d.id(), OutboundMessage.Kind.EXPLANATION).map(Gate::of).defaultIfEmpty(Gate.NONE);
    }

    Mono<ViewerLocale> viewer(UUID familyId) {
        return records.findFamily(familyId).map(f -> ViewerLocale.of(f.whatsappNumber()))
                .switchIfEmpty(Mono.error(BaymaxException.unauthorized("session_family_gone", "the family no longer exists")));
    }

    Mono<Document> visibleDocument(UUID familyId, UUID documentId) {
        return documents.findVisible(familyId, documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)));
    }
}
