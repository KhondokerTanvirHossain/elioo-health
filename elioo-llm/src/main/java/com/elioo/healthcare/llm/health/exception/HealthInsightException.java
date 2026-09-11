package com.elioo.healthcare.llm.health.exception;

/**
 * Exception thrown by HealthInsightService operations.
 *
 * <p>This exception wraps all errors that occur during health-related
 * AI operations, including:</p>
 * <ul>
 *   <li>LLM provider errors</li>
 *   <li>Response parsing failures</li>
 *   <li>Invalid request validation errors</li>
 *   <li>Timeout and connectivity issues</li>
 * </ul>
 */
public class HealthInsightException extends RuntimeException {

    /**
     * Create exception with message.
     *
     * @param message Error message
     */
    public HealthInsightException(String message) {
        super(message);
    }

    /**
     * Create exception with message and cause.
     *
     * @param message Error message
     * @param cause   Underlying cause
     */
    public HealthInsightException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create exception with cause only.
     *
     * @param cause Underlying cause
     */
    public HealthInsightException(Throwable cause) {
        super(cause);
    }
}
