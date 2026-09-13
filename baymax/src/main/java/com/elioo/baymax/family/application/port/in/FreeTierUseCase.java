package com.elioo.baymax.family.application.port.in;

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
}
