package com.elioo.healthcare.core.filters;


import com.elioo.healthcare.core.enums.DateTimeFormatterPattern;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class IWebFilter implements WebFilter {

    private final Tracer tracer;
    private final static DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .appendPattern(DateTimeFormatterPattern.DATE_TIME.getValue())
            .appendFraction(ChronoField.NANO_OF_SECOND, 3, 6, true)
            .toFormatter();

    @Override
    public Mono<Void> filter(ServerWebExchange serverWebExchange, WebFilterChain webFilterChain) {
        ServerWebExchange mutatedServerWebExchange = setRequestHeaders(serverWebExchange);
        setMdcAttributeForLogBack(mutatedServerWebExchange);
        logRequest(mutatedServerWebExchange.getRequest());
        setResponseHeader(mutatedServerWebExchange);
        logResponse(mutatedServerWebExchange);

//        return webFilterChain.filter(serverWebExchange);

        Map<String, String> mdcContextMap = MDC.getCopyOfContextMap(); // copy the current MDC context

        return webFilterChain.filter(serverWebExchange)
                .contextWrite(ctx -> ctx.put("mdcContextMap", mdcContextMap)); // put the MDC context into the subscriber context
    }

    private void logRequest(ServerHttpRequest request) {
        if (request.getURI().getPath().contains("actuator") || request.getURI().getPath().contains("swagger")) {
            return;
        }
        log.info("""
                        Request Received From {}
                         Uri : {}
                         Method : {}
                         Headers : {}
                         Path : {}
                         Query Params : {}
                         Content type : {}
                         Acceptable Media Type {}
                        """,
                request.getLocalAddress(),
                request.getURI(),
                request.getMethod(),
                request.getHeaders(),
                request.getPath(),
                request.getQueryParams(),
                request.getHeaders().getContentType(),
                request.getHeaders().getAccept());
    }

    private void logResponse(ServerWebExchange serverWebExchange) {
        if (serverWebExchange.getRequest().getURI().getPath().contains("actuator") || serverWebExchange.getRequest().getURI().getPath().contains("swagger")
        || serverWebExchange.getRequest().getURI().getPath().contains("medical-report/query/status/")) {
            return;
        }
        serverWebExchange.getResponse().beforeCommit(() -> {
            log.info("""
                            Response Sending To {}
                             Uri : {}
                             Path : {}
                             Headers : {}
                             Response Status : {}
                             Content type : {}
                            """,
                    serverWebExchange.getRequest().getLocalAddress(),
                    serverWebExchange.getRequest().getURI(),
                    serverWebExchange.getRequest().getPath(),
                    serverWebExchange.getResponse().getHeaders(),
                    serverWebExchange.getResponse().getStatusCode(),
                    serverWebExchange.getResponse().getHeaders().getContentType()
            );
            log.info("Response processing time for {} is {} ms", serverWebExchange.getRequest().getPath(),
                    Objects.requireNonNull(serverWebExchange.getResponse().getHeaders().
                            get(HeaderNames.RESPONSE_PROCESSING_TIME_IN_MS.getValue())).stream().findFirst().orElse("0"));
            return Mono.empty();
        });
    }

    private void setResponseHeader(ServerWebExchange serverWebExchange) {
        serverWebExchange.getResponse().beforeCommit(() -> {
            Objects.requireNonNull(serverWebExchange.getRequest().getHeaders().get(HeaderNames.REQUEST_RECEIVED_TIME_IN_MS.getValue()))
                    .stream().
                    findFirst()
                    .ifPresent(s -> {
                        serverWebExchange.getResponse().getHeaders().set(HeaderNames.RESPONSE_PROCESSING_TIME_IN_MS.getValue(), LocalDateTime.now().format(formatter));
                    });
            serverWebExchange.getResponse().getHeaders().set(HeaderNames.RESPONSE_SENT_TIME_IN_MS.getValue(), String.valueOf(LocalDateTime.now()));
            serverWebExchange.getResponse().getHeaders().set(HeaderNames.TRACE_ID.getValue(),
                    Objects.requireNonNull(serverWebExchange.getRequest().getHeaders().get(HeaderNames.TRACE_ID.getValue()))
                            .stream()
                            .findAny()
                            .orElse(""));
            return Mono.empty();
        });
    }

    private ServerWebExchange setRequestHeaders(ServerWebExchange serverWebExchange) {
        return serverWebExchange.mutate()
                .request(originalRequest -> originalRequest.headers(headers -> {
                    headers.set(HeaderNames.REQUEST_RECEIVED_TIME_IN_MS.getValue(), LocalDateTime.now().format(formatter));

                    String traceId = headers.getOrEmpty(HeaderNames.TRACE_ID.getValue())
                            .stream()
                            .findFirst()
                            .orElseGet(() -> {
                                if (tracer.currentSpan() != null) {
                                    return Objects.requireNonNull(tracer.currentSpan()).context().traceId();
                                } else {
                                    return "default-trace-id";
                                }
                            });

                    headers.set(HeaderNames.TRACE_ID.getValue(), traceId);
                }))
                .build();
    }


    private void setMdcAttributeForLogBack(ServerWebExchange serverWebExchange) {
        MDC.put("Method", Objects.requireNonNull(serverWebExchange.getRequest().getMethod()).name());
        MDC.put("Uri", serverWebExchange.getRequest().getPath().value());
        final String traceId;
        final String spanId;
        if (tracer.currentSpan() != null) {
            traceId = Objects.requireNonNull(tracer.currentSpan()).context().traceId();
        } else {
            traceId = "default-trace-id";
        }
        if (tracer.currentSpan() != null) {
            spanId = Objects.requireNonNull(tracer.currentSpan()).context().spanId();
        } else {
            spanId = "default-span-id";
        }
        MDC.put("traceId", Objects.requireNonNull(serverWebExchange.getRequest().getHeaders().get(HeaderNames.TRACE_ID.getValue()))
                .stream()
                .findFirst()
                .orElseGet(() -> traceId));
        MDC.put("spanId", Optional.ofNullable(serverWebExchange.getRequest().getHeaders().get(HeaderNames.SPAN_ID.getValue()))
                .map(headers -> headers.stream().findFirst().orElse(spanId))
                .orElse(spanId));
        MDC.put("Request-Trace-Id", Objects.requireNonNull(serverWebExchange.getRequest().getHeaders().get(HeaderNames.TRACE_ID.getValue()))
                .stream()
                .findFirst()
                .orElseGet(() -> traceId));
    }

}
