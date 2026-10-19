package com.elioo.baymax.family.application.port.in;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The paywall (nudges and volume live behind it). Each check completes empty when allowed and fails with
 * {@link com.elioo.baymax.common.error.FreeTierExceededException} (HTTP 402) when not.
 */
public interface FreeTierUseCase {

    Mono<Void> checkCanAddPatient(FamilyAccount family);

    Mono<Void> checkCanUploadDocument(FamilyAccount family);

    /** Convenience for intake (BMX-2): loads the family, 404 when unknown, then the document check. */
    Mono<Void> checkCanUploadDocument(UUID familyId);

    /**
     * This month's allowance: the one value the add-report sheet states and intake refuses by, so the page can never
     * promise a slot intake would refuse.
     */
    Mono<DocumentAllowance> documentAllowance(FamilyAccount family);

    /** Loads the family (404 when unknown), then {@link #documentAllowance(FamilyAccount)}. */
    Mono<DocumentAllowance> documentAllowance(UUID familyId);

    /** Several documents in one press ("আলাদা আলাদা"): each page is a document and each takes a slot. */
    Mono<Void> checkCanUploadDocuments(FamilyAccount family, int documents);

    /** Loads the family (404 when unknown), then {@link #checkCanUploadDocuments(FamilyAccount, int)}. */
    Mono<Void> checkCanUploadDocuments(UUID familyId, int documents);
}
