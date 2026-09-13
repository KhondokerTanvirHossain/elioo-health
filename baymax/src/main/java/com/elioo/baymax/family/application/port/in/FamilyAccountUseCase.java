package com.elioo.baymax.family.application.port.in;

import com.elioo.baymax.healthrecord.domain.DeletionReceipt;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Onboarding and delete-on-request for families and patients. */
public interface FamilyAccountUseCase {

    /** @param termsAccepted must be true; the timestamp is taken server-side and never updated */
    record CreateFamilyCommand(String whatsappNumber, String ownerName, boolean termsAccepted) {
    }

    /** @param proxyConsent must be true; the timestamp is taken server-side and never updated */
    record AddPatientCommand(String name, Integer age, String sex, List<String> chronicFlags, boolean proxyConsent) {
    }

    Mono<FamilyAccount> createFamily(CreateFamilyCommand command);

    Mono<PatientProfile> addPatient(UUID familyId, AddPatientCommand command);

    Mono<ShareMember> addShareMember(UUID patientId, String whatsappNumber);

    /** Hard-deletes the patient's rows and images; returns what was removed. */
    Mono<DeletionReceipt> deletePatient(UUID patientId);

    /** Hard-deletes the family, its patients, share members and images; returns what was removed. */
    Mono<DeletionReceipt> deleteFamily(UUID familyId);
}
