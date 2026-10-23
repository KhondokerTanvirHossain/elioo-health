package com.elioo.baymax.web.domain;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /**
     * Whose paper the sheet opens on, for a page opened with {@code asked} (its {@code ?patient=}, as the browser sent
     * it): the only one the viewer may send for, else the asked patient when the viewer may send for them, else nobody.
     * The one rule both sheets choose by: the HTML sheet's checked chip ({@code UploadUiHandler.chosen}) and
     * {@code GET /app/api/upload-options}'s {@code chosen_patient_id}, which the Next sheet renders and never derives.
     */
    public Optional<UUID> chosen(Optional<String> asked) {
        if (preselected().isPresent()) {
            return preselected().map(p -> p.patient().id());
        }
        return asked.flatMap(raw -> canSend.stream().map(p -> p.patient().id())
                .filter(id -> id.toString().equalsIgnoreCase(raw)).findFirst());
    }
}
