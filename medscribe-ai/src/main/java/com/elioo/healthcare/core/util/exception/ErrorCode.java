package com.elioo.healthcare.core.util.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    UNKNOWN_ERROR("0000", "Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR),
    NOT_FOUND("1001", "Resource not found", HttpStatus.NOT_FOUND),
    INVALID_REQUEST("1002", "Invalid request parameters", HttpStatus.BAD_REQUEST),
    DUPLICATE_RESOURCE("1003", "Resource already exists", HttpStatus.CONFLICT),
    DATABASE_ERROR("1004", "Database error occurred", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_FILTER("1005", "Invalid filter parameters", HttpStatus.BAD_REQUEST),
    INVALID_SORT("1006", "Invalid sort parameters", HttpStatus.BAD_REQUEST),
    INVALID_PAGE_SIZE("1007", "Invalid page size", HttpStatus.BAD_REQUEST),
    MAPPER_ERROR("1008", "Error While Mapping", HttpStatus.INTERNAL_SERVER_ERROR),
    ;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}