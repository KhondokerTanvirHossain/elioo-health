package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * A patient's chronic flags (asked, never inferred) and the nudge switch (BMX-8): the two writes the পরিবার tab posts
 * ({@link FamilyView}), each answered with a redirect back to that tab, never a page of its own, so a reload never posts
 * twice. The old profile URL (bookmarks, the WhatsApp opt-out page) opens the tab with the patient chosen.
 */
@Component
@RequiredArgsConstructor
public class ProfilePages implements WebPages {

    public static final List<String> FLAGS = List.of("diabetes", "hypertension", "kidney", "thyroid", "heart");

    private final TimelineUseCase timeline;
    private final NudgeOptOutUseCase optOut;

    @Override
    public int order() {
        return 40;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET(BASE + "/patients/{id}/profile", this::profilePage)
                .POST(BASE + "/patients/{id}/profile", this::saveProfile)
                .POST(BASE + "/patients/{id}/nudges/opt-out", this::optOutFromProfile)
                .filter(requireSession)
                .build();
    }

    /** The old profile page: the পরিবার tab with this patient chosen; the tab answers 404 for one this family may not see. */
    public Mono<ServerResponse> profilePage(ServerRequest request) {
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return ServerResponse.status(HttpStatus.FOUND).location(familyTab(patientId, false)).build();
    }

    public Mono<ServerResponse> saveProfile(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return request.formData().flatMap(form -> {
            List<String> flags = new java.util.ArrayList<>(form.getOrDefault("flag", List.of()));
            String other = form.getFirst("other");
            if (other != null && !other.isBlank()) {
                flags.add(other.trim());
            }
            return timeline.updateChronicFlags(session.familyId(), patientId, flags);
        }).flatMap(p -> ServerResponse.seeOther(familyTab(patientId, true)).build());
    }

    /** From the পরিবার tab, with a session: stop nudges for this patient; only the owner may. */
    public Mono<ServerResponse> optOutFromProfile(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return timeline.me(session.familyId())
                .filter(me -> me.patients().stream().anyMatch(p -> p.patient().id().equals(patientId) && p.owner()))
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(me -> optOut.optOutPatient(patientId))
                .then(Mono.defer(() -> ServerResponse.seeOther(familyTab(patientId, false)).build()));
    }

    private static URI familyTab(UUID patientId, boolean saved) {
        return URI.create(Tab.FAMILY.path + "?patient=" + patientId + (saved ? "&saved=1" : ""));
    }
}
