package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The পরিবার tab's reads and writes (plan B5): {@code GET /app/api/family}, {@code PUT /app/api/patients/{id}/flags},
 * {@code PUT /app/api/patients/{id}/reminders}, {@code PUT /app/api/family/reminders} and {@code PUT /app/api/language}.
 *
 * <ul>
 *   <li>The patients and the owner flag are {@link TimelineUseCase#me}'s; a patient the viewer cannot see is a 404, the
 *       same as one that does not exist; one they see as a share member is 403 {@code VIEW_ONLY} on every write (D30),
 *       the HTML tab's own rule.</li>
 *   <li>The flags are saved through {@link TimelineUseCase#updateChronicFlags}, the HTML form's save: owner only,
 *       normalised there, refused 400 {@code INVALID} when too many or too long.</li>
 *   <li>Reminders can only be stopped here: {@code {"on": false}}. {@code {"on": true}} is 400 {@code INVALID} and clears
 *       nothing: opting back in is the E1/E2-API task, which waits for the PO's yes (Q5, Q9). The family stop is the
 *       viewer's own family's, by its id, so a share member's stop never reaches the family whose patient they see.</li>
 *   <li>The language is the {@code baymax_lang} cookie and nothing else ({@link Lang#cookie}); anything but {@code en}
 *       is Bangla ({@link Lang#parse}).</li>
 * </ul>
 * Nothing here logs: the guard's access line (route pattern, status, duration) is the only one, so the number, the
 * names and the flags (family-typed text) never reach a log.
 */
@Component
@RequiredArgsConstructor
public class FamilyApiHandler {

    private final TimelineUseCase timeline;
    private final NudgeDataPort nudgeData;
    private final NudgeOptOutUseCase optOut;
    private final UiCopy copy;
    private final BaymaxProperties properties;

    public Mono<ServerResponse> family(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return familyBody(session.familyId(), Lang.of(request)).flatMap(FamilyApiHandler::ok);
    }

    public Mono<ServerResponse> setFlags(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = uuid(request.pathVariable("id"));
        return request.bodyToMono(Flags.class)
                // a body without the list would otherwise save an empty one: every flag cleared by a misspelt key
                .filter(f -> f.chronicFlags() != null)
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "chronic_flags is required")))
                .flatMap(f -> timeline.updateChronicFlags(session.familyId(), patientId, f.chronicFlags()))
                .then(Mono.defer(() -> patientBody(session.familyId(), patientId, Lang.of(request))))
                .flatMap(FamilyApiHandler::ok);
    }

    public Mono<ServerResponse> patientReminders(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = uuid(request.pathVariable("id"));
        return owned(session.familyId(), patientId)
                .then(stop(request))
                .then(Mono.defer(() -> optOut.optOutPatient(patientId)))
                .then(Mono.defer(() -> patientBody(session.familyId(), patientId, Lang.of(request))))
                .flatMap(FamilyApiHandler::ok);
    }

    public Mono<ServerResponse> familyReminders(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return stop(request)
                .then(Mono.defer(() -> optOut.optOutFamilyById(session.familyId())))
                .then(Mono.defer(() -> familyBody(session.familyId(), Lang.of(request))))
                .flatMap(FamilyApiHandler::ok);
    }

    public Mono<ServerResponse> language(ServerRequest request) {
        return request.bodyToMono(Choice.class)
                .map(c -> Lang.parse(c.lang()))
                .defaultIfEmpty(Lang.BN)
                .flatMap(lang -> ServerResponse.noContent().cookie(lang.cookie(properties.getAuth().isCookieSecure())).build());
    }

    /** {@code {"chronic_flags": [...]}}; any other field is ignored. */
    record Flags(@JsonProperty("chronic_flags") List<String> chronicFlags) {
    }

    /** {@code {"on": false}}; any other field is ignored. */
    record Switch(@JsonProperty("on") Boolean on) {
    }

    /** {@code {"lang": "bn" | "en"}}; any other field is ignored. */
    record Choice(@JsonProperty("lang") String lang) {
    }

    /** Completes when the body is exactly a stop; anything else, {@code on: true} included, is 400 until E1/E2-API. */
    private static Mono<Void> stop(ServerRequest request) {
        return request.bodyToMono(Switch.class)
                .filter(s -> Boolean.FALSE.equals(s.on()))
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "only on=false is accepted")))
                .then();
    }

    /** 404 for a patient this family cannot see, 403 {@code not_owner} for one it sees as a share member. */
    private Mono<Void> owned(UUID familyId, UUID patientId) {
        return timeline.me(familyId).flatMap(me -> {
            PatientSummary p = find(me, patientId);
            return p.owner() ? Mono.<Void>empty()
                    : Mono.<Void>error(BaymaxException.forbidden("not_owner", "only the family owner can change a profile"));
        });
    }

    private Mono<Map<String, Object>> familyBody(UUID familyId, Lang lang) {
        return timeline.me(familyId).flatMap(me -> Flux.fromIterable(me.patients()).concatMap(this::reminders).collectList()
                .zipWith(optOut.familyOptedOut(familyId))
                .map(t -> FamilyJson.family(me, t.getT1(), t.getT2(), lang, copy)));
    }

    private Mono<Map<String, Object>> patientBody(UUID familyId, UUID patientId, Lang lang) {
        return timeline.me(familyId).map(me -> find(me, patientId))
                .flatMap(p -> reminders(p).map(r -> FamilyJson.patient(p, r, lang, copy)));
    }

    /** The stored stops of a patient the family sees; a visible patient always has its row, so none reads as on. */
    private Mono<String> reminders(PatientSummary p) {
        return nudgeData.patient(p.patient().id()).map(FamilyJson::reminders).defaultIfEmpty(FamilyJson.ON);
    }

    private static PatientSummary find(FamilyOverview me, UUID patientId) {
        return me.patients().stream().filter(p -> p.patient().id().equals(patientId)).findFirst()
                .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no such patient"));
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw BaymaxException.notFound("patient_not_found", "no such patient");
        }
    }

    private static Mono<ServerResponse> ok(Map<String, Object> body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
