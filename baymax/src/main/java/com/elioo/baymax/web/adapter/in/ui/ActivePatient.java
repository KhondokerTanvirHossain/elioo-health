package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Whose papers are in view (spec §3): ?patient= if this family may see that patient (else 404, the same answer as a
 * patient that does not exist), else the only patient. Two or more and no choice: empty, and the tab asks. The visible
 * set is timeline.me(), i.e. records.visiblePatients in SQL; nothing the browser sends is trusted.
 */
record ActivePatient(Optional<PatientSummary> patient) {

    static ActivePatient resolve(ServerRequest request, FamilyOverview me) {
        Optional<String> asked = request.queryParam("patient");
        if (asked.isPresent()) {
            return new ActivePatient(Optional.of(find(me, asked.get())
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no such patient"))));
        }
        return new ActivePatient(me.patients().size() == 1 ? Optional.of(me.patients().get(0)) : Optional.empty());
    }

    boolean is(PatientSummary p) {
        return patient.map(a -> a.patient().id().equals(p.patient().id())).orElse(false);
    }

    static Optional<PatientSummary> find(FamilyOverview me, String raw) {
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return me.patients().stream().filter(p -> p.patient().id().equals(id)).findFirst();
    }
}
