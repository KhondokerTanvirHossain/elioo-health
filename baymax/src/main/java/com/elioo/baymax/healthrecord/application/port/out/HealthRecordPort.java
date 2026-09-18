package com.elioo.baymax.healthrecord.application.port.out;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.FamilyActivity;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The one door to the family's health record (DR-1): family accounts, patient profiles, share members,
 * and, once BMX-2 adds them, documents, observations, medication events and follow-ups. Services depend
 * on this interface only; the Postgres adapter is the only implementation in v1.
 *
 * <p>There is deliberately no update method for consent timestamps.</p>
 */
public interface HealthRecordPort {

    // --- family accounts -------------------------------------------------------------------------

    /** Inserts; the id in the argument is ignored and the stored one returned. */
    Mono<FamilyAccount> createFamily(FamilyAccount family);

    Mono<FamilyAccount> findFamily(UUID familyId);

    Mono<FamilyAccount> findFamilyByWhatsapp(String whatsappNumber);

    // --- patient profiles ------------------------------------------------------------------------

    Mono<PatientProfile> createPatient(PatientProfile patient);

    /**
     * BMX-8: chronic flags are asked, never inferred. This is the only write to them after creation; nothing in the
     * extraction or nudge packages may call it (asserted by ChronicFlagsAreAskedNeverInferredTest).
     */
    Mono<PatientProfile> updateChronicFlags(UUID patientId, List<String> chronicFlags);

    Mono<PatientProfile> findPatient(UUID patientId);

    Flux<PatientProfile> patientsOf(UUID familyId);

    Mono<Long> countPatients(UUID familyId);

    // --- share members ---------------------------------------------------------------------------

    Mono<ShareMember> addShareMember(ShareMember member);

    Mono<Long> countShareMembers(UUID patientId);

    // --- documents ------------------------------------------------------------------------------
    // Counting documents moved to DocumentRecordPort with BMX-2; what remains here is the id lookup
    // delete-on-request needs, which still reads the storage ledger so that images are removed even
    // for an upload that never produced a document row.

    Flux<UUID> documentIdsOf(UUID familyId);

    Flux<UUID> documentIdsOfPatient(UUID patientId);

    Mono<Long> countDocumentsOfPatient(UUID patientId);

    // --- deletion (hard, transactional) ----------------------------------------------------------

    /**
     * Removes the family, its patients and share members. Cost rows in ai_call_log for the given documents
     * are kept (numbers only) but their document_id is cleared. Returns the number of patient rows removed.
     */
    Mono<Long> deleteFamily(UUID familyId, Collection<UUID> documentIds);

    /** Removes one patient and its share members; same treatment of ai_call_log. Returns 1 when removed. */
    Mono<Long> deletePatient(UUID patientId, Collection<UUID> documentIds);

    // --- reporting -------------------------------------------------------------------------------

    /** One row per family: plan, patients, documents created in {@code [from, to)}, last document time. */
    Flux<FamilyActivity> familyActivity(Instant from, Instant to);

    /**
     * The patients a family may see: its own, and any it is a share member on (matched by the family's
     * WhatsApp number in share_member). Access is decided here, in one query, not in a service (BMX-5).
     */
    Flux<PatientAccess> visiblePatients(UUID familyId);

    /** One patient if the family may see them; empty otherwise, so the caller answers 404 and never 403. */
    Mono<PatientAccess> visiblePatient(UUID familyId, UUID patientId);
}
