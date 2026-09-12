package com.elioo.baymax.aicall.adapter.in.handler;

import com.elioo.baymax.aicall.application.port.in.WeeklyMetricsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * {@code GET /api/v1/baymax/admin/metrics/weekly?from=YYYY-MM-DD&to=YYYY-MM-DD} → CSV.
 * Dates are UTC days; {@code to} is exclusive. Default window: the last 7 days including today.
 */
@Component
@RequiredArgsConstructor
public class AdminMetricsHandler {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final WeeklyMetricsUseCase metrics;

    public Mono<ServerResponse> weekly(ServerRequest request) {
        LocalDate to;
        LocalDate from;
        try {
            to = request.queryParam("to").map(LocalDate::parse).orElse(LocalDate.now(ZoneOffset.UTC).plusDays(1));
            from = request.queryParam("from").map(LocalDate::parse).orElse(to.minusDays(7));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from/to must be ISO dates (YYYY-MM-DD)");
        }
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.atStartOfDay(ZoneOffset.UTC).toInstant();
        return metrics.weeklyCsv(start, end)
                .flatMap(csv -> ServerResponse.ok()
                        .contentType(TEXT_CSV)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"baymax-metrics-" + from + "_" + to + ".csv\"")
                        .bodyValue(csv));
    }
}
