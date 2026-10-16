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


    /**
     * DR-23: parameter names whose VALUE is a credential and must never reach the log.
     *
     * <p>Matched on the whole name, case-insensitively, never as a substring: {@code tokenizer=bert} and
     * {@code keyword=fever} are not credentials, and redacting them would make a real incident harder to
     * diagnose. A log nobody trusts gets turned off, so over-redaction is its own failure.</p>
     */
    private static final java.util.Set<String> SECRET_PARAMS = java.util.Set.of(
            "t", "token", "verify_token", "hub.verify_token", "key", "secret", "api_key", "apikey",
            "access_token", "refresh_token", "auth", "authorization", "password", "passwd", "pwd",
            "signature", "sig", "hmac", "otp", "code");

    /**
     * The URI with any credential-bearing query parameter's value replaced.
     *
     * <p>Two credentials were reaching the production log through this filter. The WhatsApp verify token was
     * written on every Meta handshake and accumulated. BMX-8's opt-out link,
     * {@code /app/nudges/opt-out?p=<patientId>&t=<HMAC>}, has carried an unauthenticated access token in a
     * query string since #33 — and stayed out of the log only because the review gate has never released a
     * nudge, so no family has clicked one. The first click would have logged a working token for a real
     * patient.</p>
     *
     * <p>Applied to every place the URI or its parameters reach a log line, in both directions. Never throws:
     * logging must not be able to break a request.</p>
     */
    static String redactSecrets(String uri) {
        if (uri == null) {
            return null;
        }
        int q = uri.indexOf('?');
        if (q < 0 || q == uri.length() - 1) {
            return uri;
        }
        try {
            String[] pairs = uri.substring(q + 1).split("&", -1);
            StringBuilder out = new StringBuilder(uri.substring(0, q + 1));
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    out.append('&');
                }
                String pair = pairs[i];
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    out.append(pair);                                  // a bare flag carries no value
                    continue;
                }
                String name = pair.substring(0, eq);
                out.append(name).append('=')
                        .append(SECRET_PARAMS.contains(name.toLowerCase(java.util.Locale.ROOT))
                                ? "REDACTED" : pair.substring(eq + 1));
            }
            return out.toString();
        } catch (RuntimeException e) {
            // A URI we cannot split is one we certainly must not log in full.
            return uri.substring(0, q + 1) + "REDACTED";
        }
    }

    /** Query parameters for the log, with credential values replaced — same rule as the URI. */
    private static Map<String, java.util.List<String>> redactParams(
            org.springframework.util.MultiValueMap<String, String> params) {
        Map<String, java.util.List<String>> safe = new java.util.LinkedHashMap<>();
        params.forEach((name, values) ->
                safe.put(name, SECRET_PARAMS.contains(name.toLowerCase(java.util.Locale.ROOT))
                        ? java.util.List.of("REDACTED") : values));
        return safe;
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
                redactSecrets(String.valueOf(request.getURI())),
                request.getMethod(),
                request.getHeaders(),
                request.getPath(),
                redactParams(request.getQueryParams()),
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
                    redactSecrets(String.valueOf(serverWebExchange.getRequest().getURI())),
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
