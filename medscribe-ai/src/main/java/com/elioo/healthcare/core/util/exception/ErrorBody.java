package com.elioo.healthcare.core.util.exception;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Builder
public class ErrorBody implements Serializable {
    private final long timestamp = System.currentTimeMillis();
    private final int status;
    private final String error;
    private final String message;
    private final String path;

    public ErrorBody(ExceptionHandlerUtil error, String path) {
        this.status = error.getCode().value();
        this.error = error.getCode().toString();
        this.message = error.getMessage();
        this.path = path;
    }

    public ErrorBody(int status, String error, String message, String path) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }
}
