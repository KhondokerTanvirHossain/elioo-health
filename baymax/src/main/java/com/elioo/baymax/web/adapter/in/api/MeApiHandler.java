package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.adapter.in.ActivePatient;
import com.elioo.baymax.web.adapter.in.PatientJson;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /app/api/me} and {@code PUT /app/api/active-patient}: who is signed in, whose papers they may see, whose are
 * in view, and whether this family previews the Next.js screens. The patients come from {@link TimelineUseCase#me}
 * (the visible set, {@code records.visiblePatients} in SQL); the active patient is {@link ActivePatient}, the HTML pages'
 * own rule and cookie, unchanged; the name forms are {@link NameGrammar}'s, so React never builds "{name}-এর".
 *
 * <p>{@code urgent} is true when the patient's attention stack holds a NOW paper ({@link AttentionUseCase#urgent}: stored
 * F1 levels, the same 30-day window as the চ্যাট stack and the কাগজপত্র strip), so the switcher's red mark and the stack
 * never disagree. THIS_WEEK alone is not urgent here: red is reserved for NOW.
 *
 * <p>{@code chronic_flags_set} is true when the patient has any chronic flag stored: the papers page's BMX-8 ask
 * (AppTabs.papersBody) shows exactly when it is false.
 *
 * <p>{@code preview} is decided by {@code baymax.web.preview-family-ids} and nothing else: no cookie, header or query
 * parameter can turn it on. The JSON carries no phone number, no plan, no confidence, no model.
 */
@Component
@RequiredArgsConstructor
public class MeApiHandler {

    private final TimelineUseCase timeline;
    private final AttentionUseCase attention;
    private final BaymaxProperties properties;

    public Mono<ServerResponse> me(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return timeline.me(session.familyId()).flatMap(me -> Flux.fromIterable(me.patients())
                .concatMap(p -> attention.urgent(session.familyId(), p.patient().id())
                        .map(cards -> cards.stream().anyMatch(c -> c.level() == Urgency.NOW)))
                .collectList()
                // a read writes nothing: the active-patient cookie is set by PUT /app/api/active-patient only (plan B1).
                // A Next server component cannot pass a Set-Cookie on, so a sliding re-set here would expire the
                // choice on a different clock depending on who made the call.
                .flatMap(urgent -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body(me, urgent, ActivePatient.remembered(request, me), session.familyId()))));
    }

    public Mono<ServerResponse> setActivePatient(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return request.bodyToMono(Choice.class)
                .filter(c -> c.patientId() != null && !c.patientId().isBlank())
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "patient_id is required")))
                .flatMap(c -> timeline.me(session.familyId()).map(me -> ActivePatient.chosen(me, c.patientId().strip())))
                .flatMap(active -> ServerResponse.noContent()
                        .cookie(active.cookie(properties.getAuth().isCookieSecure()).orElseThrow())
                        .build());
    }

    /** {@code {"patient_id": "<uuid>"}}; any other field is ignored. */
    record Choice(@JsonProperty("patient_id") String patientId) {
    }

    private Map<String, Object> body(FamilyOverview me, List<Boolean> urgent, ActivePatient active, UUID familyId) {
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("id", me.family().id().toString());
        family.put("owner_name", me.family().ownerName());
        List<Map<String, Object>> patients = new java.util.ArrayList<>();
        for (int i = 0; i < me.patients().size(); i++) {
            patients.add(patient(me.patients().get(i), urgent.get(i)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("family", family);
        body.put("patients", patients);
        body.put("active_patient_id", active.patient().map(p -> p.patient().id().toString()).orElse(null));
        body.put("preview", properties.getWeb().previewFamilies().contains(familyId));
        return body;
    }

    /** {@link PatientJson}'s fields (shared with {@code /api/v1/baymax/me}), then the name forms, the red mark and whether flags are set. */
    private static Map<String, Object> patient(PatientSummary p, boolean urgent) {
        Map<String, Object> m = PatientJson.fields(p);
        String name = p.patient().name();
        m.put("name_possessive", NameGrammar.possessive(name));
        m.put("name_objective", NameGrammar.objective(name));
        m.put("urgent", urgent);
        // BMX-8 (PO option 1): whether any chronic flag is stored, so the papers page asks exactly when Java's does
        // (AppTabs.papersBody: none stored is asked). A fact, never the flags themselves; /app/api/family carries those.
        List<String> flags = p.patient().chronicFlags();
        m.put("chronic_flags_set", flags != null && !flags.isEmpty());
        return m;
    }
}
