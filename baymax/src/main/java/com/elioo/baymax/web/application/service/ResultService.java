package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ViewerLocale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * The document page's read side (A9: the status and the reading view; A12 adds the result). Every read starts from the
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

    Mono<ViewerLocale> viewer(UUID familyId) {
        return records.findFamily(familyId).map(f -> ViewerLocale.of(f.whatsappNumber()))
                .switchIfEmpty(Mono.error(BaymaxException.unauthorized("session_family_gone", "the family no longer exists")));
    }

    Mono<Document> visibleDocument(UUID familyId, UUID documentId) {
        return documents.findVisible(familyId, documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)));
    }
}
