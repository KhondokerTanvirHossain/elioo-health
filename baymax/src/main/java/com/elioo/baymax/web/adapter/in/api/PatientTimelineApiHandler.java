package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.DocumentPages;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.TimelineFilter;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Plan B3: a patient's papers as the কাগজপত্র and স্বাস্থ্য screens read them ({@link TimelineJson}).
 * <ul>
 *   <li>{@code GET /app/api/patients/{id}/timeline?cursor=&type=lab|rx}: one page (C2's read model, its order and opaque
 *       cursor), each entry with the month it is grouped under;</li>
 *   <li>{@code GET /app/api/patients/{id}/calendar?month=YYYY-MM}: the month's papers and follow-ups by day; no
 *       {@code month} is the patient's current month, anything but {@code YYYY-MM} is 400 {@code INVALID};</li>
 *   <li>{@code GET /app/api/patients/{id}/follow-ups}: the doctor's open dates, soonest first, each {@code overdue}
 *       against the patient's Dhaka today ({@link PatientCalendar#today}), never a UTC one.</li>
 * </ul>
 * A patient this family cannot see, or a path id that is not a UUID, is 404 {@code NOT_FOUND}.
 */
@Component
@RequiredArgsConstructor
public class PatientTimelineApiHandler {

    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

    private final TimelineUseCase timeline;
    private final AttentionUseCase attention;
    private final UiCopy copy;
    private final Clock clock;

    public Mono<ServerResponse> timeline(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = AttentionApiHandler.patientId(request);
        String cursor = request.queryParam("cursor").orElse(null);
        TimelineFilter filter = TimelineFilter.fromParam(request.queryParam("type").orElse(null));
        return timeline.timeline(session.familyId(), patientId, cursor, filter)
                .flatMap(page -> json(TimelineJson.page(page, code -> DocumentPages.retakeReasonKey(copy, code))));
    }

    public Mono<ServerResponse> calendar(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = AttentionApiHandler.patientId(request);
        YearMonth month = month(request.queryParam("month"));
        return timeline.calendar(session.familyId(), patientId, month).flatMap(m -> json(TimelineJson.calendar(m)));
    }

    public Mono<ServerResponse> followUps(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = AttentionApiHandler.patientId(request);
        return attention.followUps(session.familyId(), patientId)
                .flatMap(items -> {
                    LocalDate today = PatientCalendar.today(clock);
                    return json(TimelineJson.followUps(items, due -> due.isBefore(today)));
                });
    }

    /** Absent: the patient's current month (the service decides it). Present: exactly {@code YYYY-MM}, else INVALID. */
    static YearMonth month(Optional<String> raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String value = raw.get();
        if (!MONTH.matcher(value).matches()) {
            throw BaymaxException.badRequest("invalid_request", "month must be YYYY-MM");
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw BaymaxException.badRequest("invalid_request", "month must be YYYY-MM");
        }
    }

    private static Mono<ServerResponse> json(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
