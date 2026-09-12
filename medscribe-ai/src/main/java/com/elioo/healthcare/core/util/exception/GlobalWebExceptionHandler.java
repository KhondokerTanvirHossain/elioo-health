package com.elioo.healthcare.core.util.exception;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GlobalWebExceptionHandler implements WebExceptionHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String traceId = exchange.getAttribute("Trace-Id") != null ? exchange.getAttribute("Trace-Id").toString() : "unknown";
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ApiResponse<?> apiResponse;

        if (ex instanceof AppException appEx) {
            status = appEx.getErrorCode().getHttpStatus();
            apiResponse = ApiResponse.error(appEx.getErrorCode(), appEx.getMessage());
            log.warn("[Trace-Id: {}] Application error: {}", traceId, ex.getMessage());
        } else if (ex instanceof ResponseStatusException rsEx) {
            status = HttpStatus.valueOf(rsEx.getStatusCode().value());
            apiResponse = ApiResponse.error(ErrorCode.INVALID_REQUEST, rsEx.getReason() != null ? rsEx.getReason() : "Invalid request");
            log.warn("[Trace-Id: {}] Response status error: {}", traceId, ex.getMessage());
        } else if (ex instanceof MethodArgumentTypeMismatchException) {
            status = HttpStatus.BAD_REQUEST;
            apiResponse = ApiResponse.error(ErrorCode.INVALID_REQUEST, "Invalid parameter type");
            log.warn("[Trace-Id: {}] Parameter type mismatch: {}", traceId, ex.getMessage());
        } else if (ex instanceof ConstraintViolationException cve) {
            status = HttpStatus.BAD_REQUEST;
            String message = cve.getConstraintViolations().stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            apiResponse = ApiResponse.error(ErrorCode.INVALID_REQUEST, "Validation failed: " + message);
            log.warn("[Trace-Id: {}] Validation error: {}", traceId, message);
        } else {
            apiResponse = ApiResponse.error(ErrorCode.DATABASE_ERROR, "An unexpected error occurred");
            log.error("[Trace-Id: {}] Unexpected error: {}", traceId, ex.getMessage(), ex);
        }

        // Add timestamp to response
        apiResponse.setUserMessage(String.format("%s [Timestamp: %s]", apiResponse.getUserMessage(), Instant.now()));

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        return writeResponse(exchange, apiResponse, traceId);
    }

    private Mono<Void> writeResponse(ServerWebExchange exchange, ApiResponse<?> apiResponse, String traceId) {
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(apiResponse);
            DataBuffer buffer = bufferFactory.wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("[Trace-Id: {}] Failed to serialize error response: {}", traceId, e.getMessage(), e);
            byte[] bytes = "{\"error\":\"Internal Server Error\", \"timestamp\":\"%s\"}"
                    .formatted(Instant.now())
                    .getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = bufferFactory.wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }
    }
}