package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Adding papers on the web. Whom the viewer may send for comes from {@code visiblePatients} (access decided in SQL);
 * the allowance is {@link FreeTierUseCase#documentAllowance}, the same value intake refuses by.
 */
@Service
@RequiredArgsConstructor
public class UploadService implements UploadUseCase {

    private final TimelineUseCase timeline;
    private final HealthRecordPort records;
    private final FreeTierUseCase freeTier;
    private final DocumentIntakeUseCase intake;
    private final AuditPort audit;
    private final Clock clock;

    @Override
    public Mono<UploadOptions> uploadOptions(UUID familyId) {
        return timeline.me(familyId).flatMap(me -> freeTier.documentAllowance(me.family()).map(allowance -> new UploadOptions(
                me.patients().stream().filter(PatientSummary::owner).toList(),
                me.patients().stream().filter(p -> !p.owner()).toList(),
                allowance)));
    }

    /** The JSON API's upload: one document of every file, no key, so never a replay. */
    @Override
    public Mono<Document> upload(UUID familyId, UUID patientId, List<Upload> uploads) {
        return upload(familyId, patientId, uploads, Grouping.ONE, null).map(documents -> documents.get(0));
    }

    @Override
    public Mono<Void> admit(UUID familyId, UUID patientId) {
        return owned(familyId, patientId).then();
    }

    /** The one place the patient rule of an upload is written: visible to this family, and its own to add to. */
    private Mono<PatientAccess> owned(UUID familyId, UUID patientId) {
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(access -> access.owner() ? Mono.just(access) : Mono.error(BaymaxException.forbidden("shared_patient_read_only",
                        "a shared patient's records can be seen but not added to")));
    }

    @Override
    public Mono<List<Document>> upload(UUID familyId, UUID patientId, List<Upload> uploads, Grouping grouping, String uploadKey) {
        return owned(familyId, patientId)
                .flatMap(access -> grouping == Grouping.SEPARATE
                        ? intake.acceptEach(patientId, uploads, uploadKey).collectList()
                        : intake.accept(patientId, uploads, uploadKey).map(List::of))
                // only what this press made is an upload: a replay or a race's loser made nothing, so it records nothing
                .flatMap(created -> Flux.fromIterable(created).filter(CreatedDocument::fresh)
                        .concatMap(c -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_UPLOAD, familyId, patientId, c.document().id(), clock.instant())))
                        .then(Mono.just(created.stream().map(CreatedDocument::document).toList())));
    }
}
