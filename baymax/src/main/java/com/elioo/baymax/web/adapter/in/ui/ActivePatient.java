package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Whose papers are in view (spec §3, F6), for this browser: {@code ?patient=} if this family may see that patient (else
 * a 404, the same answer as a patient that does not exist), else the remembered choice if this family still sees it,
 * else the only patient. Two or more and no choice: empty, and the tab asks — Medioo never guesses (PatientSwitcher
 * README). The visible set is {@code timeline.me()}, i.e. {@code records.visiblePatients} in SQL; the cookie is never
 * trusted: it only names a patient to look for in that set, and one that is not there is forgotten, never a fallback.
 */
record ActivePatient(Optional<PatientSummary> patient) {

    static final String COOKIE = "baymax_patient";
    /** Thirty minutes of silence forgets the choice (PatientSwitcher README: "expires after 30 minutes of silence"). */
    static final Duration TTL = Duration.ofMinutes(30);

    static ActivePatient resolve(ServerRequest request, FamilyOverview me) {
        Optional<String> asked = request.queryParam("patient");
        if (asked.isPresent()) {
            return new ActivePatient(Optional.of(find(me, asked.get())
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no such patient"))));
        }
        Optional<PatientSummary> remembered = Optional.ofNullable(request.cookies().getFirst(COOKIE))
                .map(HttpCookie::getValue).flatMap(v -> find(me, v));
        if (remembered.isPresent()) {
            return new ActivePatient(remembered);
        }
        return new ActivePatient(me.patients().size() == 1 ? Optional.of(me.patients().get(0)) : Optional.empty());
    }

    /** Sliding: re-set on every page that has a patient in view, so thirty minutes of silence forgets it. */
    Optional<ResponseCookie> cookie(boolean secure) {
        return patient.map(p -> ResponseCookie.from(COOKIE, p.patient().id().toString()).httpOnly(true).secure(secure)
                .sameSite("Lax").path(PageChrome.BASE).maxAge(TTL).build());
    }

    boolean is(PatientSummary p) {
        return patient.map(a -> a.patient().id().equals(p.patient().id())).orElse(false);
    }

    private static Optional<PatientSummary> find(FamilyOverview me, String raw) {
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return me.patients().stream().filter(p -> p.patient().id().equals(id)).findFirst();
    }
}
