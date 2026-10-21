package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adding papers on the web. Whom the viewer may send for comes from {@code visiblePatients} (access decided in SQL);
 * the allowance is {@link FreeTierUseCase#documentAllowance}, the same value intake refuses by.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService implements UploadUseCase {

    private final TimelineUseCase timeline;
    private final HealthRecordPort records;
    private final FreeTierUseCase freeTier;
    private final DocumentIntakeUseCase intake;
    private final DocumentRecordPort documents;
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
        return upload(familyId, patientId, uploads, Grouping.ONE, null, null).map(made -> made.get(0));
    }

    @Override
    public Mono<UUID> admit(UUID familyId, UUID patientId, UUID replaces) {
        // deferred: the paper is looked up only once the patient rule passed; silent, so a press logs its drop once (upload)
        return owned(familyId, patientId).then(Mono.defer(() -> retakeOf(familyId, patientId, replaces, false)));
    }

    /**
     * D1, the one place the {@code replaces} rule is written: the paper a press links to, or empty for none, and never a
     * refusal. Null replaces nothing. A paper this family can see, of this patient, waiting for a retake, is linked.
     * Anything else is dropped and the photo is a normal new paper of the chosen patient:
     * <ul>
     *   <li>stale (PO ruling 2026-10-22, D1 finding 16, DR-46/DR-47): a paper it cannot see (deleted since the retake page
     *       was drawn, or never its own: the same answer and the same line, so a press learns nothing about ids it cannot
     *       see), or one of this patient that no longer waits for a retake;</li>
     *   <li>another patient's paper the family can see (DR-47, pending the PO's confirmation): {@code patient} is the field
     *       the family chose and {@code replaces} the context the client carried, so the photo goes where they chose and
     *       the other patient's retake card stays theirs.</li>
     * </ul>
     * {@code sayDrop} logs the drop: one INFO line per press, said by {@link #upload} (admit passes false), no id on it, and the
     * two kinds in their own words so a log reader can tell a found paper from a gone one. The retake_replaces trigger
     * holds the same-patient half for every writer.
     */
    private Mono<UUID> retakeOf(UUID familyId, UUID patientId, UUID replaces, boolean sayDrop) {
        if (replaces == null) {
            return Mono.empty();
        }
        return documents.findVisible(familyId, replaces)
                .map(d -> !patientId.equals(d.patientId()) ? Retake.ANOTHER_PATIENT
                        : d.status() == Document.Status.NEEDS_RETAKE ? Retake.link(d.id()) : Retake.STALE)
                .defaultIfEmpty(Retake.STALE)
                .flatMap(r -> {
                    if (r.linkTo() != null) {
                        return Mono.just(r.linkTo());
                    }
                    if (sayDrop) {
                        log.info(r.dropLine());
                    }
                    return Mono.empty();
                });
    }

    /** What {@link #retakeOf} found: the paper to link, or the line its drop is logged with (no id on either line). */
    private record Retake(UUID linkTo, String dropLine) {
        static final Retake STALE = new Retake(null,
                "[baymax] upload's replaces is stale (its paper is gone or no longer waits for a retake): uploaded unlinked");
        static final Retake ANOTHER_PATIENT = new Retake(null,
                "[baymax] upload's replaces names another patient's paper: uploaded unlinked under the chosen patient");

        static Retake link(UUID paper) {
            return new Retake(paper, null);
        }
    }

    /**
     * Links each document the press returned to the paper it replaces ({@code replaces}, as {@link #retakeOf} resolved the
     * press's own field; null when it links nothing); a replay's documents too (the press that made them
     * may have died before its link). First link wins: a replay naming another retake leaves the first link in place
     * (one INFO line, ids only), so neither the first retake's stale card comes back nor the other's leaves unsent. A
     * link that fails costs only the stale card it exists to clear: logged with the two ids and nothing else, and the
     * upload stands.
     */
    private Mono<Void> link(List<CreatedDocument> created, UUID replaces) {
        if (replaces == null) {
            return Mono.empty();   // nothing to link; a dropped replaces was logged by retakeOf
        }
        return Flux.fromIterable(created).map(c -> c.document().id())
                .concatMap(id -> documents.linkRetake(id, replaces)
                        .doOnNext(linked -> {
                            if (!linked) {
                                log.info("[baymax] retake link kept: the paper already names another documentId={} replaces={}", id, replaces);
                            }
                        })
                        .then()
                        .onErrorResume(e -> {
                            log.warn("[baymax] retake link failed documentId={} replaces={}", id, replaces);
                            return Mono.empty();
                        }))
                .then();
    }

    /** The one place the patient rule of an upload is written: visible to this family, and its own to add to. */
    private Mono<PatientAccess> owned(UUID familyId, UUID patientId) {
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(access -> access.owner() ? Mono.just(access) : Mono.error(BaymaxException.forbidden("shared_patient_read_only",
                        "a shared patient's records can be seen but not added to")));
    }

    @Override
    public Mono<List<Document>> upload(UUID familyId, UUID patientId, List<Upload> uploads, Grouping grouping, String uploadKey,
                                       UUID replaces) {
        return owned(familyId, patientId)
                .flatMap(access -> retakeOf(familyId, patientId, replaces, true).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(linkTo -> (grouping == Grouping.SEPARATE
                        ? intake.acceptEach(patientId, uploads, uploadKey).collectList()
                        : intake.accept(patientId, uploads, uploadKey).map(List::of))
                        .flatMap(created -> link(created, linkTo.orElse(null)).thenReturn(created)))
                // only what this press made is an upload: a replay or a race's loser made nothing, so it records nothing
                .flatMap(created -> Flux.fromIterable(created).filter(CreatedDocument::fresh)
                        .concatMap(c -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_UPLOAD, familyId, patientId, c.document().id(), clock.instant())))
                        .then(Mono.just(created.stream().map(CreatedDocument::document).toList())));
    }
}
