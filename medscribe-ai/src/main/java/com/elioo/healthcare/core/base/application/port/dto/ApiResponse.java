package com.elioo.healthcare.core.base.application.port.dto;

import com.elioo.healthcare.core.util.exception.ErrorCode;
import lombok.Data;

@Data
public class ApiResponse<T> {
    private String userMessage;
    private int statusCode;
    private T data;
    private ErrorDetail error;

    @Data
    public static class ErrorDetail {
        private String code;
        private String message;

        public ErrorDetail(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    // Success response
    public static <T> ApiResponse<T> success(String message, int statusCode, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setUserMessage(message);
        response.setStatusCode(statusCode);
        response.setData(data);
        return response;
    }

    // Error response
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setUserMessage(message);
        response.setStatusCode(errorCode.getHttpStatus().value());
        response.setError(new ErrorDetail(errorCode.getCode(), message));
        return response;
    }
}
