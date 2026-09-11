package com.elioo.healthcare.llm.exception;

/** Failure talking to an LLM provider, or an unusable reply. */
public class LlmException extends RuntimeException {
    private final String provider;
    private final Integer httpStatus;

    public LlmException(String message) { this(null, null, message, null); }
    public LlmException(String message, Throwable cause) { this(null, null, message, cause); }
    public LlmException(String provider, int httpStatus, String message) { this(provider, httpStatus, message, null); }

    private LlmException(String provider, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public String provider() { return provider; }
    public Integer httpStatus() { return httpStatus; }
    public boolean isRetryable() { return httpStatus != null && (httpStatus == 429 || httpStatus >= 500); }
}
