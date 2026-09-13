package com.elioo.baymax.common.error;

import org.springframework.http.HttpStatus;

/**
 * An error the API answers with a specific status and a machine-readable {@code reason}. Messages are
 * for developers and never contain patient data.
 */
public class BaymaxException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    public BaymaxException(HttpStatus status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public static BaymaxException badRequest(String reason, String message) {
        return new BaymaxException(HttpStatus.BAD_REQUEST, reason, message);
    }

    public static BaymaxException notFound(String reason, String message) {
        return new BaymaxException(HttpStatus.NOT_FOUND, reason, message);
    }

    public static BaymaxException conflict(String reason, String message) {
        return new BaymaxException(HttpStatus.CONFLICT, reason, message);
    }
}
