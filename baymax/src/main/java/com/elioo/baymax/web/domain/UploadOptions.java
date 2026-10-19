package com.elioo.baymax.web.domain;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;

import java.util.List;
import java.util.Optional;

/**
 * What the add-report sheet may offer this viewer: the patients they may send papers for (the owner today; D30's
 * "কাগজ পাঠাতে পারবেন" role joins here later), those they may only see, and this month's allowance.
 */
public record UploadOptions(List<PatientSummary> canSend, List<PatientSummary> viewOnly, DocumentAllowance allowance) {

    public UploadOptions {
        canSend = List.copyOf(canSend);
        viewOnly = List.copyOf(viewOnly);
    }

    /** Preselect only when there is nobody else it could be (spec 4.7; never guess the patient, messages_bn:79). */
    public Optional<PatientSummary> preselected() {
        return canSend.size() == 1 ? Optional.of(canSend.get(0)) : Optional.empty();
    }
}
