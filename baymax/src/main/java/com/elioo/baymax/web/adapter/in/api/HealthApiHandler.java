package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The health screen's two reads (plan B6), {@link HealthJson} over {@link HealthUseCase}: {@code GET
 * /app/api/patients/{id}/labs} (the series and the blood-pressure pairs) and {@code GET /app/api/patients/{id}/medicines}
 * (the latest prescription's medicines as written). Both read only and record nothing. A patient this family cannot see,
 * or an id that is not one, is 404 {@code NOT_FOUND}, so ids cannot be probed. Nothing here logs: the guard writes the
 * access line by route pattern.
 */
@Component
@RequiredArgsConstructor
public class HealthApiHandler {

    private final HealthUseCase health;

    public Mono<ServerResponse> labs(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return health.labs(session.familyId(), patientId(request))
                .flatMap(labs -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(HealthJson.labs(labs)));
    }

    public Mono<ServerResponse> medicines(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return health.medicines(session.familyId(), patientId(request))
                .flatMap(latest -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(HealthJson.medicines(latest)));
    }

    /** The {@code {id}} path variable; one that is not a UUID names no patient, so it is the same 404 as a hidden one. */
    private static UUID patientId(ServerRequest request) {
        try {
            return UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            throw BaymaxException.notFound("patient_not_found", "no such patient");
        }
    }
}
