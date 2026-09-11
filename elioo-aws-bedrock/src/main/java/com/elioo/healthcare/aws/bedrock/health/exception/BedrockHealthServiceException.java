package com.elioo.healthcare.aws.bedrock.health.exception;

/**
 * Exception thrown by BedrockHealthService operations.
 *
 * <p>This exception wraps all errors that occur during health-related
 * AI operations, including:</p>
 * <ul>
 *   <li>AWS Bedrock API errors</li>
 *   <li>Response parsing failures</li>
 *   <li>Invalid request validation errors</li>
 *   <li>Timeout and connectivity issues</li>
 * </ul>
 */
public class BedrockHealthServiceException extends RuntimeException {

    /**
     * Create exception with message.
     *
     * @param message Error message
     */
    public BedrockHealthServiceException(String message) {
        super(message);
    }

    /**
     * Create exception with message and cause.
     *
     * @param message Error message
     * @param cause   Underlying cause
     */
    public BedrockHealthServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create exception with cause only.
     *
     * @param cause Underlying cause
     */
    public BedrockHealthServiceException(Throwable cause) {
        super(cause);
    }
}
